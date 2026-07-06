#include <jni.h>
#include <cstring>
#include <cstdlib>
#include <cstdio>
#include <dlfcn.h>
#include <sys/stat.h>
#include <unistd.h>
#include <fcntl.h>
#include <ctime>
#include <sys/uio.h>
#include <sys/socket.h>
#include <android/log.h>
#include <map>
#include <string>
#include <mutex>
#include <vector>
#include <cerrno>
#include <string_view>

#define TAG "SRX-Native"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, TAG, __VA_ARGS__)

#include "native_init.h"

// ============================================================================
// Backup function pointers
// ============================================================================
static int (*backup_stat)(const char*, struct stat*) = nullptr;
static int (*backup_lstat)(const char*, struct stat*) = nullptr;
static int (*backup_access)(const char*, int) = nullptr;
static int (*backup_fstatat)(int, const char*, struct stat*, int) = nullptr;
static ssize_t (*backup_read)(int, void*, size_t) = nullptr;
static ssize_t (*backup_pread64)(int, void*, size_t, off_t) = nullptr;
static ssize_t (*backup_recvmsg)(int, struct msghdr*, int) = nullptr;
static ssize_t (*backup_readv)(int, const struct iovec*, int) = nullptr;

static HookFunType g_hook_func = nullptr;

// ============================================================================
// FUSE protocol structures (kernel ABI, stable across Android versions)
// ============================================================================
struct fuse_in_header {
    uint32_t len;
    uint32_t opcode;
    uint64_t unique;
    uint64_t nodeid;
    uint32_t uid;
    uint32_t gid;
    uint32_t pid;
    uint32_t padding;
};

// ============================================================================
// Mode constants (must match Kotlin DirMode ordinal)
// ============================================================================
static constexpr int MODE_READ = 0;
static constexpr int MODE_WRITE = 1;
static constexpr int MODE_NONE = 2;

// ============================================================================
// TLS-based UID tracking
// ============================================================================
static thread_local int tls_fuse_uid = -1;
static thread_local int64_t tls_fuse_uid_ts_ms = 0;
static constexpr int FUSE_UID_MAX_AGE_MS = 2000;
static constexpr int ANDROID_APP_UID_START = 10000;

static int64_t monotonic_ms() {
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return (int64_t)ts.tv_sec * 1000 + ts.tv_nsec / 1000000;
}

static int get_tls_uid() {
    if (tls_fuse_uid >= ANDROID_APP_UID_START) {
        int64_t age = monotonic_ms() - tls_fuse_uid_ts_ms;
        if (age >= 0 && age <= FUSE_UID_MAX_AGE_MS) {
            return tls_fuse_uid;
        }
        tls_fuse_uid = -1;
        tls_fuse_uid_ts_ms = 0;
    }
    return -1;
}

// ============================================================================
// /dev/fuse fd tracking (lazy auto-detection)
// ============================================================================
static int g_fuse_fd = -1;

static bool is_fuse_fd(int fd) {
    return fd >= 0 && fd == g_fuse_fd;
}

static void check_and_register_fuse_fd(int fd) {
    if (fd < 0) return;
    char linkPath[64];
    snprintf(linkPath, sizeof(linkPath), "/proc/self/fd/%d", fd);
    char buf[256];
    ssize_t len = readlink(linkPath, buf, sizeof(buf) - 1);
    if (len <= 0) return;
    buf[len] = '\0';
    if (strstr(buf, "/dev/fuse") || strstr(buf, "fuse")) {
        if (g_fuse_fd != fd) {
            g_fuse_fd = fd;
            LOGI("FUSE device fd registered: %d", fd);
        }
    }
}

// ============================================================================
// read() hooks — extract UID from FUSE protocol messages
// ============================================================================
// The FUSE daemon reads requests from /dev/fuse via read()/pread64()/etc.
// Each request starts with fuse_in_header containing the calling app's UID.
// We parse it to set the TLS uid BEFORE the daemon processes the request
// (which involves lstat/stat calls).

static void parse_fuse_header(void* buf, ssize_t len) {
    if (len < (ssize_t)sizeof(fuse_in_header)) return;
    auto* header = static_cast<fuse_in_header*>(buf);
    if (header->uid >= ANDROID_APP_UID_START) {
        tls_fuse_uid = (int)header->uid;
        tls_fuse_uid_ts_ms = monotonic_ms();
    }
}

static ssize_t hook_read(int fd, void* buf, size_t count) {
    ssize_t ret = backup_read(fd, buf, count);
    if (ret > 0) {
        if (is_fuse_fd(fd)) {
            parse_fuse_header(buf, ret);
        } else if (g_fuse_fd < 0) {
            check_and_register_fuse_fd(fd);
        }
    }
    return ret;
}

static ssize_t hook_pread64(int fd, void* buf, size_t count, off_t offset) {
    ssize_t ret = backup_pread64(fd, buf, count, offset);
    if (ret > 0 && is_fuse_fd(fd)) {
        parse_fuse_header(buf, ret);
    }
    return ret;
}

static ssize_t hook_recvmsg(int fd, struct msghdr* msg, int flags) {
    ssize_t ret = backup_recvmsg(fd, msg, flags);
    if (ret > 0 && is_fuse_fd(fd) && msg && msg->msg_iov && msg->msg_iovlen > 0) {
        parse_fuse_header(msg->msg_iov[0].iov_base, msg->msg_iov[0].iov_len);
    }
    return ret;
}

static ssize_t hook_readv(int fd, const struct iovec* iov, int iovcnt) {
    ssize_t ret = backup_readv(fd, iov, iovcnt);
    if (ret > 0 && is_fuse_fd(fd) && iov && iovcnt > 0) {
        parse_fuse_header(iov[0].iov_base, iov[0].iov_len);
    }
    return ret;
}

// ============================================================================
// Configuration data structures
// ============================================================================
struct DirRule {
    std::string relativePath;
    int mode;
};

struct UidConfig {
    int uid = 0;
    std::string uidName;
    int userId = 0;
    std::vector<DirRule> rules;
    std::string fusePrefix;    // /storage/emulated/X
    std::string upperMediaBase; // /data/media/X/Android/media/<pkg>/sdcard_redirect
};

static std::mutex g_config_mutex;
static std::map<int, UidConfig> g_configs;

// ============================================================================
// Path utilities
// ============================================================================
static bool starts_with(const char* str, const std::string& prefix) {
    if (!str) return false;
    return strncmp(str, prefix.c_str(), prefix.size()) == 0;
}

static std::string extract_relative(const char* path, const std::string& storagePrefix) {
    std::string_view sv(path);
    std::string_view prefixView(storagePrefix);
    if (sv.size() > prefixView.size() && sv[prefixView.size()] == '/') {
        sv.remove_prefix(prefixView.size() + 1);
    } else if (sv.size() == prefixView.size()) {
        return "";
    } else {
        return "";
    }
    return std::string(sv);
}

static std::string get_whiteout_path(const std::string& upperPath) {
    size_t slash = upperPath.find_last_of('/');
    if (slash == std::string::npos) return upperPath;
    return upperPath.substr(0, slash) + "/.wh." + upperPath.substr(slash + 1);
}

// ============================================================================
// Redirect resolution
// ============================================================================
enum class RedirectAction {
    PASSTHROUGH,
    REDIRECT,
    NOT_FOUND,
};

static RedirectAction resolve_redirect(const char* path, std::string& upperOut) {
    if (!path) return RedirectAction::PASSTHROUGH;

    int uid = get_tls_uid();
    if (uid < 0) return RedirectAction::PASSTHROUGH;

    UidConfig config;
    {
        std::lock_guard<std::mutex> lock(g_config_mutex);
        auto it = g_configs.find(uid);
        if (it == g_configs.end()) return RedirectAction::PASSTHROUGH;
        config = it->second;
    }

    // 仅拦截 FUSE 路径 (/storage/emulated/X)
    // 不拦截 /data/media/X 直通路径，因为 FUSE 守护进程和 Java hook
    // 使用直通路径访问真实文件系统，拦截会导致递归重定向和重复结果。
    if (!starts_with(path, config.fusePrefix)) return RedirectAction::PASSTHROUGH;

    std::string androidPrefix = config.fusePrefix + "/Android";
    if (starts_with(path, androidPrefix)) return RedirectAction::PASSTHROUGH;

    std::string relPath = extract_relative(path, config.fusePrefix);
    if (relPath.empty()) return RedirectAction::PASSTHROUGH;

    // Find longest matching dir rule; default NONE mode
    int mode = MODE_NONE;
    std::string bestMatch;
    bool matched = false;
    for (const auto& rule : config.rules) {
        if (relPath == rule.relativePath ||
            (relPath.size() > rule.relativePath.size() &&
             relPath.compare(0, rule.relativePath.size(), rule.relativePath) == 0 &&
             relPath[rule.relativePath.size()] == '/')) {
            if (!matched || rule.relativePath.size() > bestMatch.size()) {
                bestMatch = rule.relativePath;
                mode = rule.mode;
                matched = true;
            }
        }
    }

    if (mode == MODE_WRITE) return RedirectAction::PASSTHROUGH;

    // 统一使用 Android/media 作为重定向基础路径，以获得 MediaStore 支持
    upperOut = config.upperMediaBase + "/" + relPath;

    // Whiteout check
    std::string whiteoutPath = get_whiteout_path(upperOut);
    struct stat whSt;
    if (backup_lstat && backup_lstat(whiteoutPath.c_str(), &whSt) == 0) {
        return RedirectAction::NOT_FOUND;
    }

    // Upper exists → redirect
    struct stat upSt;
    if (backup_lstat && backup_lstat(upperOut.c_str(), &upSt) == 0) {
        return RedirectAction::REDIRECT;
    }

    // Upper doesn't exist
    if (mode == MODE_READ) {
        return RedirectAction::PASSTHROUGH;
    } else {
        return RedirectAction::NOT_FOUND;
    }
}

// ============================================================================
// Hook functions: stat / lstat / access / fstatat
// ============================================================================
static int hook_stat(const char* path, struct stat* buf) {
    std::string upperPath;
    RedirectAction action = resolve_redirect(path, upperPath);
    switch (action) {
        case RedirectAction::REDIRECT: return backup_stat(upperPath.c_str(), buf);
        case RedirectAction::NOT_FOUND: errno = ENOENT; return -1;
        default: return backup_stat(path, buf);
    }
}

static int hook_lstat(const char* path, struct stat* buf) {
    std::string upperPath;
    RedirectAction action = resolve_redirect(path, upperPath);
    switch (action) {
        case RedirectAction::REDIRECT: return backup_lstat(upperPath.c_str(), buf);
        case RedirectAction::NOT_FOUND: errno = ENOENT; return -1;
        default: return backup_lstat(path, buf);
    }
}

static int hook_access(const char* path, int mode) {
    std::string upperPath;
    RedirectAction action = resolve_redirect(path, upperPath);
    switch (action) {
        case RedirectAction::REDIRECT: return backup_access(upperPath.c_str(), mode);
        case RedirectAction::NOT_FOUND: errno = ENOENT; return -1;
        default: return backup_access(path, mode);
    }
}

static int hook_fstatat(int dirfd, const char* path, struct stat* buf, int flags) {
    std::string upperPath;
    RedirectAction action = resolve_redirect(path, upperPath);
    switch (action) {
        case RedirectAction::REDIRECT: return backup_fstatat(dirfd, upperPath.c_str(), buf, flags);
        case RedirectAction::NOT_FOUND: errno = ENOENT; return -1;
        default: return backup_fstatat(dirfd, path, buf, flags);
    }
}

// ============================================================================
// JNI interface
// ============================================================================
extern "C" {

JNIEXPORT void JNICALL
Java_me_fakerqu_xposed_storageredirect_hook_NativeHook_nativeSetUidConfig(
        JNIEnv* env, jclass /*clazz*/, jint uid,
        jstring jUidName, jint userId,
        jobjectArray jRelativePaths, jintArray jModes) {

    UidConfig config;
    config.uid = uid;
    config.userId = userId;

    const char* uidNameChars = env->GetStringUTFChars(jUidName, nullptr);
    config.uidName = uidNameChars;
    env->ReleaseStringUTFChars(jUidName, uidNameChars);

    config.fusePrefix = "/storage/emulated/" + std::to_string(userId);
    // Upper 层使用直通路径绕过 FUSE，提升性能
    config.upperMediaBase = "/data/media/" + std::to_string(userId) + "/Android/media/" + config.uidName + "/sdcard_redirect";

    jsize ruleCount = env->GetArrayLength(jRelativePaths);
    jint* modes = env->GetIntArrayElements(jModes, nullptr);

    for (jsize i = 0; i < ruleCount; i++) {
        auto jRelPath = (jstring) env->GetObjectArrayElement(jRelativePaths, i);
        const char* relPathChars = env->GetStringUTFChars(jRelPath, nullptr);
        DirRule rule;
        rule.relativePath = relPathChars;
        rule.mode = modes[i];
        config.rules.push_back(std::move(rule));
        env->ReleaseStringUTFChars(jRelPath, relPathChars);
        env->DeleteLocalRef(jRelPath);
    }
    env->ReleaseIntArrayElements(jModes, modes, JNI_ABORT);

    {
        std::lock_guard<std::mutex> lock(g_config_mutex);
        g_configs[uid] = std::move(config);
    }
    LOGI("Config set for uid=%d (%d rules)", uid, (int)ruleCount);
}

JNIEXPORT void JNICALL
Java_me_fakerqu_xposed_storageredirect_hook_NativeHook_nativeRemoveUidConfig(
        JNIEnv* /*env*/, jclass /*clazz*/, jint uid) {
    std::lock_guard<std::mutex> lock(g_config_mutex);
    g_configs.erase(uid);
    LOGI("Config removed for uid=%d", uid);
}

JNIEXPORT void JNICALL
Java_me_fakerqu_xposed_storageredirect_hook_NativeHook_nativeClearAllConfigs(
        JNIEnv* /*env*/, jclass /*clazz*/) {
    std::lock_guard<std::mutex> lock(g_config_mutex);
    g_configs.clear();
    LOGI("All configs cleared");
}

// ============================================================================
// LSPosed Native Hook Entry
// ============================================================================
void on_library_loaded(const char* name, void* handle) {
}

[[gnu::visibility("default")]] [[gnu::used]]
NativeOnModuleLoaded native_init(const NativeAPIEntries* entries) {
    LOGI("native_init: LSPosed native hook starting...");

    g_hook_func = entries->hook_func;

    void* real_stat = dlsym(RTLD_DEFAULT, "stat");
    if (real_stat) {
        g_hook_func(real_stat, (void*)hook_stat, (void**)&backup_stat);
        LOGI("Hooked stat");
    }

    void* real_lstat = dlsym(RTLD_DEFAULT, "lstat");
    if (real_lstat) {
        g_hook_func(real_lstat, (void*)hook_lstat, (void**)&backup_lstat);
        LOGI("Hooked lstat");
    }

    void* real_access = dlsym(RTLD_DEFAULT, "access");
    if (real_access) {
        g_hook_func(real_access, (void*)hook_access, (void**)&backup_access);
        LOGI("Hooked access");
    }

    void* real_fstatat = dlsym(RTLD_DEFAULT, "fstatat");
    if (real_fstatat) {
        g_hook_func(real_fstatat, (void*)hook_fstatat, (void**)&backup_fstatat);
        LOGI("Hooked fstatat");
    }

    void* real_read = dlsym(RTLD_DEFAULT, "read");
    if (real_read) {
        g_hook_func(real_read, (void*)hook_read, (void**)&backup_read);
        LOGI("Hooked read");
    }

    void* real_pread64 = dlsym(RTLD_DEFAULT, "pread64");
    if (real_pread64) {
        g_hook_func(real_pread64, (void*)hook_pread64, (void**)&backup_pread64);
        LOGI("Hooked pread64");
    }

    void* real_recvmsg = dlsym(RTLD_DEFAULT, "recvmsg");
    if (real_recvmsg) {
        g_hook_func(real_recvmsg, (void*)hook_recvmsg, (void**)&backup_recvmsg);
        LOGI("Hooked recvmsg");
    }

    void* real_readv = dlsym(RTLD_DEFAULT, "readv");
    if (real_readv) {
        g_hook_func(real_readv, (void*)hook_readv, (void**)&backup_readv);
        LOGI("Hooked readv");
    }

    LOGI("native_init: hooks installed successfully");
    return on_library_loaded;
}

}  // extern "C"
