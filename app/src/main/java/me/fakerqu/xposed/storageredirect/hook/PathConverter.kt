package me.fakerqu.xposed.storageredirect.hook

import android.annotation.SuppressLint
import android.util.Log
import me.fakerqu.xposed.storageredirect.config.model.DirMode
import me.fakerqu.xposed.storageredirect.config.model.RuntimeConfig
import kotlin.io.path.Path
import kotlin.io.path.name

/**
 * 纯路径计算工具（零文件 I/O）。
 *
 * 负责 FUSE↔Direct 前缀转换、相对路径提取、DirMode 规则匹配、
 * 重定向判断、Upper 路径构造等。
 * 所有文件系统读写由 [OverlayHelper] 负责。
 */
object PathConverter {

    const val PATH_FILTER_OUT = "path_filter_out"
    private const val MEDIA_REDIRECT_SEGMENT = "Android/media/"
    private const val REDIRECT_DIR_NAME = "sdcard_redirect"

    // ======================= 路径前缀转换 =======================

    /**
     * 将 FUSE 路径（/storage/emulated/X/ 或 /sdcard/）转换为底层直通路径（/data/media/X/）。
     * 用于直接文件 I/O，绕过 FUSE 层，提升性能。
     */
    @SuppressLint("SdCardPath")
    fun toDirectPath(currentUserId: Int, path: String): String {
        val storagePrefix = "/storage/emulated/$currentUserId"
        val sdcardPrefix = "/sdcard"
        val directPrefix = "/data/media/$currentUserId"
        return when {
            path.startsWith(storagePrefix) -> directPrefix + path.substring(storagePrefix.length)
            path.startsWith(sdcardPrefix) -> directPrefix + path.substring(sdcardPrefix.length)
            else -> path
        }
    }

    /**
     * 将底层直通路径（/data/media/X/）转换回 FUSE 路径（/storage/emulated/X/）。
     * 用于需要传入 MediaProvider API 的场景。
     */
    @SuppressLint("SdCardPath")
    fun toFusePath(currentUserId: Int, directPath: String): String {
        val directPrefix = "/data/media/$currentUserId"
        val fusePrefix = "/storage/emulated/$currentUserId"
        return if (directPath.startsWith(directPrefix)) {
            fusePrefix + directPath.substring(directPrefix.length)
        } else {
            directPath
        }
    }

    // ======================= 相对路径 / 规则匹配 =======================

    /**
     * 将绝对路径转换为相对于 /sdcard 的相对路径。
     * 例如：/storage/emulated/0/DCIM/photo.jpg → DCIM/photo.jpg
     *      /sdcard/DCIM/                       → DCIM/
     */
    @SuppressLint("SdCardPath")
    fun toRelativePath(currentUserId: Int, path: String): String {
        return path
            .substringAfter("/storage/emulated/$currentUserId/")
            .substringAfter("/storage/emulated/$currentUserId")
            .substringAfter("/sdcard/")
            .substringAfter("/sdcard")
    }

    /**
     * 判断路径是否在重定向范围内（/sdcard 非 Android 目录）。
     */
    @SuppressLint("SdCardPath")
    fun pathNeedRedirect(currentUserId: Int, path: String): Boolean {
        return (path.startsWith("/sdcard") && !path.startsWith("/sdcard/Android"))
                || (path.startsWith("/storage/emulated/$currentUserId") && !path.startsWith("/storage/emulated/$currentUserId/Android"))
                || (!path.startsWith("/") && !path.startsWith("Android"))
    }

    /**
     * 根据配置和原始路径，匹配最长前缀规则，返回对应的 [DirMode]。
     * 未匹配到规则则返回 [DirMode.NONE]（隔离）。
     */
    fun resolveMode(config: RuntimeConfig, currentUserId: Int, originPath: String): DirMode {
        val relativePath = toRelativePath(currentUserId, originPath)
        if (relativePath.isEmpty()) return DirMode.NONE

        val matchedConfig = config.dirConfigs
            .filter { it.enabled && relativePath.startsWith(it.relativePath) }
            .maxByOrNull { it.relativePath.length }

        return matchedConfig?.mode ?: DirMode.NONE
    }

    // ======================= Upper 路径构造 =======================

    /**
     * 统一的重定向基础路径（相对路径前缀）。
     * 所有非 Android 的 /sdcard 目录统一重定向到 Android/media 以获得 MediaStore 支持。
     */
    fun getRedirectBase(config: RuntimeConfig): String =
        "$MEDIA_REDIRECT_SEGMENT${config.uidName}/$REDIRECT_DIR_NAME"

    /**
     * 获取原始路径对应的 Upper 层物理路径（直通路径，绕过 FUSE）。
     * 例如：/storage/emulated/0/DCIM/photo.jpg
     *    → /data/media/0/Android/media/<pkg>/sdcard_redirect/DCIM/photo.jpg
     */
    @SuppressLint("SdCardPath")
    fun getUpperPath(currentUserId: Int, config: RuntimeConfig, originPath: String): String {
        val relativePath = toRelativePath(currentUserId, originPath)
        val basePath = getRedirectBase(config)
        return "/data/media/$currentUserId/$basePath/$relativePath"
    }

    /**
     * 获取 Upper 层的 FUSE 路径（/storage/emulated/ 前缀）。
     * 仅用于需要传入 MediaProvider API 的场景。
     */
    @SuppressLint("SdCardPath")
    fun getUpperFusePath(currentUserId: Int, config: RuntimeConfig, originPath: String): String {
        return toFusePath(currentUserId, getUpperPath(currentUserId, config, originPath))
    }

    private fun isRedirectionDir(dir: String): Boolean {
        val dirPath = Path(dir)
        return dirPath.contains(Path(REDIRECT_DIR_NAME))
    }

    // ======================= MediaStore ↔ App 方向转换 =======================

    /**
     * 将应用请求的原始路径转换为 MediaStore（FUSE 后端）实际查询的路径列表。
     * - WRITE 模式：透传原始路径
     * - READ 模式：同时查原始路径和重定向路径
     * - NONE 模式：仅查重定向路径
     */
    @SuppressLint("SdCardPath")
    fun toFs(currentUserId: Int, config: RuntimeConfig, originPath: String): List<String> {
        if (originPath.startsWith("/data") || !pathNeedRedirect(currentUserId, originPath)) {
            return listOf(originPath)
        }
        val relativePath = toRelativePath(currentUserId, originPath)

        val matchedConfig = config.dirConfigs
            .filter { it.enabled && relativePath.startsWith(it.relativePath) }
            .maxByOrNull { it.relativePath.length }
        val replacedPath = "${getRedirectBase(config)}/$relativePath"
        val replacedPaths = when (matchedConfig?.mode) {
            DirMode.WRITE -> listOf(relativePath)
            DirMode.READ -> listOf(relativePath, replacedPath)
            DirMode.NONE, null -> listOf(replacedPath)
        }
        return if (originPath.startsWith("/")) {
            replacedPaths.map { "/storage/emulated/$currentUserId/$it" }
        } else {
            replacedPaths
        }
    }

    /**
     * 将 MediaStore 查询返回的重定向路径还原为应用可感知的原始路径。
     *
     * 副作用（文件 I/O）：
     * - 检查重定向路径在直通层是否存在，不存在则返回 [PATH_FILTER_OUT]
     * - 检查非重定向路径是否存在 whiteout，存在则返回 [PATH_FILTER_OUT]
     */
    @SuppressLint("SdCardPath")
    fun toApp(
        currentUserId: Int,
        config: RuntimeConfig,
        redirectPath: String,
        fileExists: (directPath: String) -> Boolean,
        isWhiteouted: (originPath: String) -> Boolean,
    ): String {
        val cleanPath = redirectPath.trim()
        if (cleanPath.isEmpty()) return cleanPath
        val originFile = Path(cleanPath).normalize()

        // whiteout 文件本身，直接过滤
        if (originFile.name.startsWith(".wh.")) {
            return PATH_FILTER_OUT
        }

        // 非重定向路径：检查模式
        if (!isRedirectionDir(cleanPath)) {
            Log.i("SRX", "path convert to app not redirect path originPath=$cleanPath")
            val mode = resolveMode(config, currentUserId, cleanPath)
            when (mode) {
                DirMode.WRITE -> {
                    // WRITE 模式：检查 whiteout
                    if (isWhiteouted(cleanPath)) {
                        return PATH_FILTER_OUT
                    }
                    return cleanPath
                }
                DirMode.NONE -> {
                    // NONE 模式：隔离，过滤掉 Lower 层条目
                    return PATH_FILTER_OUT
                }
                DirMode.READ -> {
                    // READ 模式：检查 whiteout
                    if (isWhiteouted(cleanPath)) {
                        return PATH_FILTER_OUT
                    }
                    return cleanPath
                }
            }
        }

        // 重定向路径：检查文件是否实际存在（使用直通路径绕过 FUSE）
        Log.i("SRX", "path convert to app originPath=$cleanPath")
        val directCheckPath = if (cleanPath.startsWith("/")) {
            toDirectPath(currentUserId, cleanPath)
        } else {
            "/data/media/$currentUserId/$cleanPath"
        }
        if (!fileExists(directCheckPath)) {
            Log.i("SRX", "path convert to app redirect file not exist, directPath=$directCheckPath")
            return PATH_FILTER_OUT
        }

        // 去除重定向前缀，还原原始路径
        val redirectBase = getRedirectBase(config)
        return if (cleanPath.contains(redirectBase)) {
            cleanPath
                .replace("$redirectBase/", "")
                .replace(redirectBase, "")
        } else {
            cleanPath
        }
    }
}
