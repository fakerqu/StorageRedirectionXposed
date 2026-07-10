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
#include <stdarg.h>
#include <dirent.h>

#define TAG "SRX-Native"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, TAG, __VA_ARGS__)

#include "native_init.h"

// ============================================================================
// Backup function pointers
// ============================================================================
static int (*backup_stat)(const char *, struct stat *) = nullptr;

static int (*backup_lstat)(const char *, struct stat *) = nullptr;

static int (*backup_access)(const char *, int) = nullptr;

static int (*backup_fstatat)(int, const char *, struct stat *, int) = nullptr;

static int (*backup_open)(const char *, int, ...) = nullptr;

static int (*backup_openat)(int, const char *, int, ...) = nullptr;

static int (*backup_mkdir)(const char *, mode_t) = nullptr;

static int (*backup_mkdirat)(int, const char *, mode_t) = nullptr;

static int (*backup_unlink)(const char *) = nullptr;

static int (*backup_unlinkat)(int, const char *, int) = nullptr;

static int (*backup_rmdir)(const char *) = nullptr;

static ssize_t (*backup_read)(int, void *, size_t) = nullptr;

static ssize_t (*backup_pread64)(int, void *, size_t, off_t) = nullptr;

static ssize_t (*backup_recvmsg)(int, struct msghdr *, int) = nullptr;

static ssize_t (*backup_readv)(int, const struct iovec *, int) = nullptr;

static int (*backup_pipe2)(int[2], int) = nullptr;

static int (*backup_pipe)(int[2]) = nullptr;

static ssize_t (*backup_splice)(int, loff_t *, int, loff_t *, size_t, unsigned int) = nullptr;

static ssize_t (*backup_vmsplice)(int, const struct iovec *, size_t, unsigned int) = nullptr;

static int (*backup_close)(int) = nullptr;

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
    return (int64_t) ts.tv_sec * 1000 + ts.tv_nsec / 1000000;
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
    if (g_fuse_fd == fd) return;  // already registered
    char linkPath[64];
    snprintf(linkPath, sizeof(linkPath), "/proc/self/fd/%d", fd);
    char buf[256];
    ssize_t len = readlink(linkPath, buf, sizeof(buf) - 1);
    if (len <= 0) return;
    buf[len] = '\0';
    // Must be exact match /dev/fuse, not just substring "fuse"
    // (other fds like fuse_bpf or anon_inode:fuse will cause false positives)
    if (strcmp(buf, "/dev/fuse") == 0) {
        g_fuse_fd = fd;
        LOGI("FUSE device fd registered: %d (link=%s)", fd, buf);
    }
}

// Scan all already-open file descriptors at startup to find /dev/fuse
// (the FUSE device is opened before our hook is installed)
static void scan_for_fuse_fd() {
    DIR *d = opendir("/proc/self/fd");
    if (!d) return;
    struct dirent *entry;
    while ((entry = readdir(d)) != nullptr) {
        if (entry->d_name[0] == '.') continue;
        int fd = atoi(entry->d_name);
        if (fd > 2) {  // skip stdin/stdout/stderr
            check_and_register_fuse_fd(fd);
        }
    }
    closedir(d);
    LOGI("scan_for_fuse_fd: g_fuse_fd=%d after scan", g_fuse_fd);
}

// ============================================================================
// splice / pipe tracking — FUSE daemon may use splice(fuse_fd → pipe) to avoid copy
//
// Data flow: splice(/dev/fuse → pipe_write_end)  then read(pipe_read_end, buf, n)
// We track pipe fd pairs so that when data is later read from pipe_read_end,
// we can parse the FUSE header.
// ============================================================================

static std::mutex g_pipe_mutex;
// Maps pipe_read_fd → true (indicates this fd may contain FUSE data spliced from /dev/fuse)
static std::map<int, bool> g_fuse_pipe_fds;

static bool is_fuse_pipe_fd(int fd) {
    std::lock_guard<std::mutex> lock(g_pipe_mutex);
    auto it = g_fuse_pipe_fds.find(fd);
    return it != g_fuse_pipe_fds.end();
}

static void mark_fuse_pipe_fd(int fd) {
    std::lock_guard<std::mutex> lock(g_pipe_mutex);
    g_fuse_pipe_fds[fd] = true;
}

static void unmark_fuse_pipe_fd(int fd) {
    std::lock_guard<std::mutex> lock(g_pipe_mutex);
    g_fuse_pipe_fds.erase(fd);
}

// We also need to track which pipe_write_fd maps to which pipe_read_fd.
// pipe2() returns [read_fd, write_fd]; we need to know both.
static std::mutex g_pipe_pair_mutex;
static std::map<int, int> g_pipe_write_to_read;  // write_fd → read_fd

static void record_pipe_pair(int readFd, int writeFd) {
    std::lock_guard<std::mutex> lock(g_pipe_pair_mutex);
    g_pipe_write_to_read[writeFd] = readFd;
}

static int get_pipe_read_for_write(int writeFd) {
    std::lock_guard<std::mutex> lock(g_pipe_pair_mutex);
    auto it = g_pipe_write_to_read.find(writeFd);
    if (it != g_pipe_write_to_read.end()) return it->second;
    return -1;
}

static void forget_pipe_pair_by_write(int writeFd) {
    std::lock_guard<std::mutex> lock(g_pipe_pair_mutex);
    g_pipe_write_to_read.erase(writeFd);
}

static void forget_pipe_pair_by_read(int readFd) {
    std::lock_guard<std::mutex> lock(g_pipe_pair_mutex);
    for (auto it = g_pipe_write_to_read.begin(); it != g_pipe_write_to_read.end(); ++it) {
        if (it->second == readFd) {
            g_pipe_write_to_read.erase(it);
            break;
        }
    }
}

// ============================================================================
// read() hooks — extract UID from FUSE protocol messages
// ============================================================================
// The FUSE daemon reads requests from /dev/fuse via read()/pread64()/splice()/etc.
// Each request starts with fuse_in_header containing the calling app's UID.
// We parse it to set the TLS uid BEFORE the daemon processes the request
// (which involves lstat/stat calls).

static void parse_fuse_header(void *buf, ssize_t len, int fd) {
    if (len < (ssize_t) sizeof(fuse_in_header)) return;
    auto *header = static_cast<fuse_in_header *>(buf);
    if (header->uid >= ANDROID_APP_UID_START) {
        tls_fuse_uid = (int) header->uid;
        tls_fuse_uid_ts_ms = monotonic_ms();
    }
}

static ssize_t hook_read(int fd, void *buf, size_t count) {
    ssize_t ret = backup_read(fd, buf, count);
    if (ret > 0) {
        if (is_fuse_fd(fd)) {
            parse_fuse_header(buf, ret, fd);
        } else if (is_fuse_pipe_fd(fd)) {
            parse_fuse_header(buf, ret, fd);
        } else if (g_fuse_fd < 0) {
            check_and_register_fuse_fd(fd);
            if (is_fuse_fd(fd)) {
                parse_fuse_header(buf, ret, fd);
            }
        }
    }
    return ret;
}

static ssize_t hook_pread64(int fd, void *buf, size_t count, off_t offset) {
    ssize_t ret = backup_pread64(fd, buf, count, offset);
    if (ret > 0) {
        if (is_fuse_fd(fd) || is_fuse_pipe_fd(fd)) {
            parse_fuse_header(buf, ret, fd);
        }
    }
    return ret;
}

static ssize_t hook_recvmsg(int fd, struct msghdr *msg, int flags) {
    ssize_t ret = backup_recvmsg(fd, msg, flags);
    if (ret > 0 && (is_fuse_fd(fd) || is_fuse_pipe_fd(fd)) && msg && msg->msg_iov && msg->msg_iovlen > 0) {
        parse_fuse_header(msg->msg_iov[0].iov_base, msg->msg_iov[0].iov_len, fd);
    }
    return ret;
}

static ssize_t hook_readv(int fd, const struct iovec *iov, int iovcnt) {
    ssize_t ret = backup_readv(fd, iov, iovcnt);
    if (ret > 0 && (is_fuse_fd(fd) || is_fuse_pipe_fd(fd)) && iov && iovcnt > 0) {
        parse_fuse_header(iov[0].iov_base, iov[0].iov_len, fd);
    }
    return ret;
}

static int hook_pipe2(int pipefd[2], int flags) {
    int ret = backup_pipe2(pipefd, flags);
    if (ret == 0) {
        record_pipe_pair(pipefd[0], pipefd[1]);
    }
    return ret;
}

static int hook_pipe(int pipefd[2]) {
    int ret = backup_pipe(pipefd);
    if (ret == 0) {
        record_pipe_pair(pipefd[0], pipefd[1]);
    }
    return ret;
}

static ssize_t hook_splice(int fd_in, loff_t *off_in, int fd_out, loff_t *off_out,
                           size_t len, unsigned int flags) {
    ssize_t ret = backup_splice(fd_in, off_in, fd_out, off_out, len, flags);
    if (ret > 0) {
        // Lazy fuse fd detection
        if (g_fuse_fd < 0) {
            check_and_register_fuse_fd(fd_in);
            check_and_register_fuse_fd(fd_out);
        }
        // splice(fuse_fd → pipe_write_end) — mark pipe_read_end for later parsing
        if (is_fuse_fd(fd_in)) {
            int readFd = get_pipe_read_for_write(fd_out);
            if (readFd >= 0) {
                mark_fuse_pipe_fd(readFd);
            } else {
                // Unknown fd_out (pipe created before our hook), mark directly
                mark_fuse_pipe_fd(fd_out);
            }
        }
    }
    return ret;
}

static ssize_t hook_vmsplice(int fd, const struct iovec *iov, size_t nr_segs, unsigned int flags) {
    // vmsplice writes user pages into a pipe — unlikely for FUSE read path,
    // but hook for completeness
    return backup_vmsplice(fd, iov, nr_segs, flags);
}

static int hook_close(int fd) {
    // Clean up tracking
    if (fd >= 0) {
        unmark_fuse_pipe_fd(fd);
        forget_pipe_pair_by_write(fd);
        forget_pipe_pair_by_read(fd);
    }
    return backup_close(fd);
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
static bool starts_with(const char *str, const std::string &prefix) {
    if (!str) return false;
    return strncmp(str, prefix.c_str(), prefix.size()) == 0;
}

static std::string extract_relative(const char *path, const std::string &storagePrefix) {
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

static std::string get_whiteout_path(const std::string &upperPath) {
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

static RedirectAction resolve_redirect(const char *path, std::string &upperOut) {
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
    for (const auto &rule: config.rules) {
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
// Write redirect helpers
// ============================================================================
static void ensure_upper_parents(const std::string &upperPath) {
    size_t lastSlash = upperPath.find_last_of('/');
    if (lastSlash == std::string::npos || lastSlash == 0) return;
    std::string dirPath = upperPath.substr(0, lastSlash);
    size_t pos = 1;
    while ((pos = dirPath.find('/', pos)) != std::string::npos) {
        std::string component = dirPath.substr(0, pos);
        if (backup_mkdir) backup_mkdir(component.c_str(), 0770);
        pos++;
    }
    if (backup_mkdir) backup_mkdir(dirPath.c_str(), 0770);
}

static bool copy_up_file(const char *lowerPath, const char *upperPath) {
    struct stat st;
    if (!backup_lstat || backup_lstat(lowerPath, &st) != 0) return false;
    if (!S_ISREG(st.st_mode)) return false;

    ensure_upper_parents(upperPath);

    int srcFd = backup_open ? backup_open(lowerPath, O_RDONLY) : -1;
    if (srcFd < 0) return false;

    int dstFd = backup_open ? backup_open(upperPath, O_WRONLY | O_CREAT | O_TRUNC, st.st_mode & 0777) : -1;
    if (dstFd < 0) {
        close(srcFd);
        return false;
    }

    char buf[65536];
    ssize_t n;
    while ((n = backup_read ? backup_read(srcFd, buf, sizeof(buf)) : read(srcFd, buf, sizeof(buf))) > 0) {
        ssize_t written = 0;
        while (written < n) {
            ssize_t w = write(dstFd, buf + written, n - written);
            if (w < 0) {
                close(srcFd);
                close(dstFd);
                return false;
            }
            written += w;
        }
    }

    close(srcFd);
    close(dstFd);
    return true;
}

static void create_whiteout(const std::string &upperPath) {
    std::string whPath = get_whiteout_path(upperPath);
    ensure_upper_parents(whPath);
    int fd = backup_open ? backup_open(whPath.c_str(), O_CREAT | O_WRONLY, 0600) : open(whPath.c_str(), O_CREAT | O_WRONLY, 0600);
    if (fd >= 0) close(fd);
}

static void remove_whiteout(const std::string &upperPath) {
    std::string whPath = get_whiteout_path(upperPath);
    if (backup_unlink) backup_unlink(whPath.c_str());
    else unlink(whPath.c_str());
}

// Returns 0 on success, -1 with errno set on failure
static int redirect_delete(const std::string &upperPath, const char *origPath, bool isDir) {
    // Try to delete from upper first
    int ret;
    if (isDir) {
        ret = backup_rmdir ? backup_rmdir(upperPath.c_str()) : rmdir(upperPath.c_str());
    } else {
        ret = backup_unlink ? backup_unlink(upperPath.c_str()) : unlink(upperPath.c_str());
    }

    if (ret == 0) {
        create_whiteout(upperPath);
        return 0;
    }

    // Check if already whiteout'd (already deleted)
    std::string whPath = get_whiteout_path(upperPath);
    struct stat whSt;
    if (backup_lstat && backup_lstat(whPath.c_str(), &whSt) == 0) {
        errno = ENOENT;
        return -1;
    }

    // File doesn't exist in upper; check if in lower
    struct stat lowSt;
    if (backup_lstat && backup_lstat(origPath, &lowSt) == 0) {
        // File exists in lower, create whiteout to hide it
        create_whiteout(upperPath);
        return 0;
    }

    errno = ENOENT;
    return -1;
}

// ============================================================================
// Write redirect resolution
// ============================================================================
static RedirectAction resolve_redirect_write(const char *path, std::string &upperOut) {
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

    if (!starts_with(path, config.fusePrefix)) return RedirectAction::PASSTHROUGH;

    std::string androidPrefix = config.fusePrefix + "/Android";
    if (starts_with(path, androidPrefix)) return RedirectAction::PASSTHROUGH;

    std::string relPath = extract_relative(path, config.fusePrefix);
    if (relPath.empty()) return RedirectAction::PASSTHROUGH;

    // Find longest matching dir rule
    int mode = MODE_NONE;
    std::string bestMatch;
    bool matched = false;
    for (const auto &rule: config.rules) {
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

    // MODE_WRITE: passthrough writes to original location
    if (mode == MODE_WRITE) return RedirectAction::PASSTHROUGH;

    // MODE_READ and MODE_NONE: redirect writes to upper
    upperOut = config.upperMediaBase + "/" + relPath;
    return RedirectAction::REDIRECT;
}

// ============================================================================
// Hook functions: stat / lstat / access / fstatat
// ============================================================================
static int hook_stat(const char *path, struct stat *buf) {
    std::string upperPath;
    RedirectAction action = resolve_redirect(path, upperPath);
    switch (action) {
        case RedirectAction::REDIRECT:
            return backup_stat(upperPath.c_str(), buf);
        case RedirectAction::NOT_FOUND:
            errno = ENOENT;
            return -1;
        default:
            return backup_stat(path, buf);
    }
}

static int hook_lstat(const char *path, struct stat *buf) {
    std::string upperPath;
    RedirectAction action = resolve_redirect(path, upperPath);

    switch (action) {
        case RedirectAction::REDIRECT:
            return backup_lstat(upperPath.c_str(), buf);
        case RedirectAction::NOT_FOUND:
            errno = ENOENT;
            return -1;
        default:
            return backup_lstat(path, buf);
    }
}

static int hook_access(const char *path, int mode) {
    std::string upperPath;
    RedirectAction action = resolve_redirect(path, upperPath);
    switch (action) {
        case RedirectAction::REDIRECT:
            return backup_access(upperPath.c_str(), mode);
        case RedirectAction::NOT_FOUND:
            errno = ENOENT;
            return -1;
        default:
            return backup_access(path, mode);
    }
}

static int hook_fstatat(int dirfd, const char *path, struct stat *buf, int flags) {
    std::string upperPath;
    RedirectAction action = resolve_redirect(path, upperPath);
    switch (action) {
        case RedirectAction::REDIRECT:
            return backup_fstatat(dirfd, upperPath.c_str(), buf, flags);
        case RedirectAction::NOT_FOUND:
            errno = ENOENT;
            return -1;
        default:
            return backup_fstatat(dirfd, path, buf, flags);
    }
}

// ============================================================================
// Hook functions: open / openat / mkdir / mkdirat / unlink / unlinkat / rmdir
// ============================================================================
static int hook_open(const char *pathname, int flags, ...) {
    mode_t mode = 0;
    if (flags & O_CREAT) {
        va_list args;
        va_start(args, flags);
        mode = static_cast<mode_t>(va_arg(args, int));
        va_end(args);
    }

    bool isWrite = (flags & O_WRONLY) || (flags & O_RDWR) ||
                   (flags & O_CREAT) || (flags & O_TRUNC);

    int ret;
    if (isWrite) {
        std::string upperPath;
        RedirectAction action = resolve_redirect_write(pathname, upperPath);
        if (action == RedirectAction::REDIRECT) {
            // Copy-up if file exists in lower but not in upper
            struct stat lowSt, upSt;
            bool lowerExists = backup_lstat && backup_lstat(pathname, &lowSt) == 0;
            bool upperExists = backup_lstat && backup_lstat(upperPath.c_str(), &upSt) == 0;
            if (!upperExists && lowerExists && S_ISREG(lowSt.st_mode)) {
                copy_up_file(pathname, upperPath.c_str());
            }
            ensure_upper_parents(upperPath);
            return backup_open(upperPath.c_str(), flags, mode);
        }
        return backup_open(pathname, flags, mode);
    }

    // Read operation: redirect if upper exists
    std::string upperPath;
    RedirectAction action = resolve_redirect(pathname, upperPath);
    if (action == RedirectAction::REDIRECT) {
        return backup_open(upperPath.c_str(), flags);
    }
    ret = backup_open(pathname, flags);
    if (ret >= 0 && g_fuse_fd < 0 && strcmp(pathname, "/dev/fuse") == 0) {
        g_fuse_fd = ret;
        LOGI("hook_open: /dev/fuse opened, fd=%d", ret);
    }
    return ret;
}

static int hook_openat(int dirfd, const char *pathname, int flags, ...) {
    mode_t mode = 0;
    if (flags & O_CREAT) {
        va_list args;
        va_start(args, flags);
        mode = static_cast<mode_t>(va_arg(args, int));
        va_end(args);
    }

    // Only handle absolute paths for redirect
    if (pathname[0] != '/') {
        return backup_openat(dirfd, pathname, flags, mode);
    }

    bool isWrite = (flags & O_WRONLY) || (flags & O_RDWR) ||
                   (flags & O_CREAT) || (flags & O_TRUNC);

    if (isWrite) {
        std::string upperPath;
        RedirectAction action = resolve_redirect_write(pathname, upperPath);
        if (action == RedirectAction::REDIRECT) {
            struct stat lowSt, upSt;
            bool lowerExists = backup_lstat && backup_lstat(pathname, &lowSt) == 0;
            bool upperExists = backup_lstat && backup_lstat(upperPath.c_str(), &upSt) == 0;
            if (!upperExists && lowerExists && S_ISREG(lowSt.st_mode)) {
                copy_up_file(pathname, upperPath.c_str());
            }
            ensure_upper_parents(upperPath);
            return backup_openat(AT_FDCWD, upperPath.c_str(), flags, mode);
        }
        return backup_openat(dirfd, pathname, flags, mode);
    }

    // Read operation: redirect if upper exists
    std::string upperPath;
    RedirectAction action = resolve_redirect(pathname, upperPath);
    if (action == RedirectAction::REDIRECT) {
        return backup_openat(AT_FDCWD, upperPath.c_str(), flags);
    }
    int ret = backup_openat(dirfd, pathname, flags, mode);
    if (ret >= 0 && g_fuse_fd < 0 && strcmp(pathname, "/dev/fuse") == 0) {
        g_fuse_fd = ret;
        LOGI("hook_openat: /dev/fuse opened, fd=%d", ret);
    }
    return ret;
}

static int hook_mkdir(const char *pathname, mode_t mode) {
    std::string upperPath;
    RedirectAction action = resolve_redirect_write(pathname, upperPath);
    if (action == RedirectAction::REDIRECT) {
        ensure_upper_parents(upperPath);
        return backup_mkdir(upperPath.c_str(), mode);
    }
    return backup_mkdir(pathname, mode);
}

static int hook_mkdirat(int dirfd, const char *pathname, mode_t mode) {
    if (pathname[0] != '/') {
        return backup_mkdirat(dirfd, pathname, mode);
    }
    std::string upperPath;
    RedirectAction action = resolve_redirect_write(pathname, upperPath);
    if (action == RedirectAction::REDIRECT) {
        ensure_upper_parents(upperPath);
        return backup_mkdirat(AT_FDCWD, upperPath.c_str(), mode);
    }
    return backup_mkdirat(dirfd, pathname, mode);
}

static int hook_unlink(const char *pathname) {
    std::string upperPath;
    RedirectAction action = resolve_redirect_write(pathname, upperPath);
    if (action == RedirectAction::REDIRECT) {
        return redirect_delete(upperPath, pathname, false);
    }
    return backup_unlink(pathname);
}

static int hook_unlinkat(int dirfd, const char *pathname, int flags) {
    if (pathname[0] != '/') {
        return backup_unlinkat(dirfd, pathname, flags);
    }
    std::string upperPath;
    RedirectAction action = resolve_redirect_write(pathname, upperPath);
    if (action == RedirectAction::REDIRECT) {
        bool isDir = (flags & AT_REMOVEDIR) != 0;
        return redirect_delete(upperPath, pathname, isDir);
    }
    return backup_unlinkat(dirfd, pathname, flags);
}

static int hook_rmdir(const char *pathname) {
    std::string upperPath;
    RedirectAction action = resolve_redirect_write(pathname, upperPath);
    if (action == RedirectAction::REDIRECT) {
        return redirect_delete(upperPath, pathname, true);
    }
    return backup_rmdir(pathname);
}

// ============================================================================
// JNI interface
// ============================================================================
extern "C" {

JNIEXPORT void JNICALL
Java_me_fakerqu_xposed_storageredirect_hook_redirect_NativeHook_nativeSetUidConfig(
        JNIEnv *env, jclass /*clazz*/, jint uid,
        jstring jUidName, jint userId,
        jobjectArray jRelativePaths, jintArray jModes) {

    UidConfig config;
    config.uid = uid;
    config.userId = userId;

    const char *uidNameChars = env->GetStringUTFChars(jUidName, nullptr);
    config.uidName = uidNameChars;
    env->ReleaseStringUTFChars(jUidName, uidNameChars);

    config.fusePrefix = "/storage/emulated/" + std::to_string(userId);
    // Upper 层使用直通路径绕过 FUSE，提升性能
    config.upperMediaBase = "/data/media/" + std::to_string(userId) + "/Android/media/" + config.uidName + "/sdcard_redirect";

    jsize ruleCount = env->GetArrayLength(jRelativePaths);
    jint *modes = env->GetIntArrayElements(jModes, nullptr);

    for (jsize i = 0; i < ruleCount; i++) {
        auto jRelPath = (jstring) env->GetObjectArrayElement(jRelativePaths, i);
        const char *relPathChars = env->GetStringUTFChars(jRelPath, nullptr);
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
    LOGI("Config set for uid=%d (%d rules), g_fuse_fd=%d", uid, (int) ruleCount, g_fuse_fd);

    // If /dev/fuse still not found, scan again (it might have been opened since native_init)
    if (g_fuse_fd < 0) {
        scan_for_fuse_fd();
    }
}

JNIEXPORT void JNICALL
Java_me_fakerqu_xposed_storageredirect_hook_redirect_NativeHook_nativeRemoveUidConfig(
        JNIEnv * /*env*/, jclass /*clazz*/, jint uid) {
    std::lock_guard<std::mutex> lock(g_config_mutex);
    g_configs.erase(uid);
    LOGI("Config removed for uid=%d", uid);
}

JNIEXPORT void JNICALL
Java_me_fakerqu_xposed_storageredirect_hook_redirect_NativeHook_nativeClearAllConfigs(
        JNIEnv * /*env*/, jclass /*clazz*/) {
    std::lock_guard<std::mutex> lock(g_config_mutex);
    g_configs.clear();
    LOGI("All configs cleared");
}

// ============================================================================
// LSPosed Native Hook Entry
// ============================================================================
void on_library_loaded(const char *name, void *handle) {
}

[[gnu::visibility("default")]] [[gnu::used]]
NativeOnModuleLoaded native_init(const NativeAPIEntries *entries) {
    LOGI("native_init: LSPosed native hook starting...");

    g_hook_func = entries->hook_func;

    // Scan already-open fds to find /dev/fuse (opened before hook installed)
    scan_for_fuse_fd();

    void *real_stat = dlsym(RTLD_DEFAULT, "stat");
    if (real_stat) {
        g_hook_func(real_stat, (void *) hook_stat, (void **) &backup_stat);
        LOGI("Hooked stat");
    }

    void *real_lstat = dlsym(RTLD_DEFAULT, "lstat");
    if (real_lstat) {
        g_hook_func(real_lstat, (void *) hook_lstat, (void **) &backup_lstat);
        LOGI("Hooked lstat");
    }

    void *real_access = dlsym(RTLD_DEFAULT, "access");
    if (real_access) {
        g_hook_func(real_access, (void *) hook_access, (void **) &backup_access);
        LOGI("Hooked access");
    }

    void *real_fstatat = dlsym(RTLD_DEFAULT, "fstatat");
    if (real_fstatat) {
        g_hook_func(real_fstatat, (void *) hook_fstatat, (void **) &backup_fstatat);
        LOGI("Hooked fstatat");
    }

    void *real_open = dlsym(RTLD_DEFAULT, "open");
    if (real_open) {
        g_hook_func(real_open, (void *) hook_open, (void **) &backup_open);
        LOGI("Hooked open");
    }

    void *real_openat = dlsym(RTLD_DEFAULT, "openat");
    if (real_openat) {
        g_hook_func(real_openat, (void *) hook_openat, (void **) &backup_openat);
        LOGI("Hooked openat");
    }

    void *real_mkdir = dlsym(RTLD_DEFAULT, "mkdir");
    if (real_mkdir) {
        g_hook_func(real_mkdir, (void *) hook_mkdir, (void **) &backup_mkdir);
        LOGI("Hooked mkdir");
    }

    void *real_mkdirat = dlsym(RTLD_DEFAULT, "mkdirat");
    if (real_mkdirat) {
        g_hook_func(real_mkdirat, (void *) hook_mkdirat, (void **) &backup_mkdirat);
        LOGI("Hooked mkdirat");
    }

    void *real_unlink = dlsym(RTLD_DEFAULT, "unlink");
    if (real_unlink) {
        g_hook_func(real_unlink, (void *) hook_unlink, (void **) &backup_unlink);
        LOGI("Hooked unlink");
    }

    void *real_unlinkat = dlsym(RTLD_DEFAULT, "unlinkat");
    if (real_unlinkat) {
        g_hook_func(real_unlinkat, (void *) hook_unlinkat, (void **) &backup_unlinkat);
        LOGI("Hooked unlinkat");
    }

    void *real_rmdir = dlsym(RTLD_DEFAULT, "rmdir");
    if (real_rmdir) {
        g_hook_func(real_rmdir, (void *) hook_rmdir, (void **) &backup_rmdir);
        LOGI("Hooked rmdir");
    }

    void *real_read = dlsym(RTLD_DEFAULT, "read");
    if (real_read) {
        g_hook_func(real_read, (void *) hook_read, (void **) &backup_read);
        LOGI("Hooked read");
    }

    void *real_pread64 = dlsym(RTLD_DEFAULT, "pread64");
    if (real_pread64) {
        g_hook_func(real_pread64, (void *) hook_pread64, (void **) &backup_pread64);
        LOGI("Hooked pread64");
    }

    void *real_recvmsg = dlsym(RTLD_DEFAULT, "recvmsg");
    if (real_recvmsg) {
        g_hook_func(real_recvmsg, (void *) hook_recvmsg, (void **) &backup_recvmsg);
        LOGI("Hooked recvmsg");
    }

    void *real_readv = dlsym(RTLD_DEFAULT, "readv");
    if (real_readv) {
        g_hook_func(real_readv, (void *) hook_readv, (void **) &backup_readv);
        LOGI("Hooked readv");
    }

    void *real_pipe = dlsym(RTLD_DEFAULT, "pipe");
    if (real_pipe) {
        g_hook_func(real_pipe, (void *) hook_pipe, (void **) &backup_pipe);
        LOGI("Hooked pipe");
    }

    void *real_pipe2 = dlsym(RTLD_DEFAULT, "pipe2");
    if (real_pipe2) {
        g_hook_func(real_pipe2, (void *) hook_pipe2, (void **) &backup_pipe2);
        LOGI("Hooked pipe2");
    }

    void *real_splice = dlsym(RTLD_DEFAULT, "splice");
    if (real_splice) {
        g_hook_func(real_splice, (void *) hook_splice, (void **) &backup_splice);
        LOGI("Hooked splice");
    }

    void *real_close = dlsym(RTLD_DEFAULT, "close");
    if (real_close) {
        g_hook_func(real_close, (void *) hook_close, (void **) &backup_close);
        LOGI("Hooked close");
    }

    void *real_vmsplice = dlsym(RTLD_DEFAULT, "vmsplice");
    if (real_vmsplice) {
        g_hook_func(real_vmsplice, (void *) hook_vmsplice, (void **) &backup_vmsplice);
        LOGI("Hooked vmsplice");
    }

    LOGI("native_init: hooks installed successfully");
    return on_library_loaded;
}

}  // extern "C"
