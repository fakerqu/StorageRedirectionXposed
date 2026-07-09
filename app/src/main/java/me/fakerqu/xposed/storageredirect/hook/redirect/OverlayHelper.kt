package me.fakerqu.xposed.storageredirect.hook.redirect

import android.annotation.SuppressLint
import android.util.Log
import me.fakerqu.xposed.storageredirect.config.model.RuntimeConfig
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * Overlay 文件操作基础设施。
 *
 * 封装 Upper 层所有文件操作：copy-up、whiteout、合并视图、递归建目录等。
 * 路径计算委托 [PathConverter]，本类只负责文件系统读写。
 * 供各 Hook 方法调用。
 */
object OverlayHelper {

    private const val TAG = "SRX"
    private const val WHITEOUT_PREFIX = ".wh."
    private const val WORK_DIR_NAME = "redirect_work"

    // ======================= 工作目录 =======================

    /**
     * 获取工作目录路径（用于临时文件和原子写入，直通路径）。
     */
    @SuppressLint("SdCardPath")
    fun getWorkDirPath(currentUserId: Int, config: RuntimeConfig): String {
        return "/data/media/$currentUserId/Android/data/${config.uidName}/files/$WORK_DIR_NAME"
    }

    // ======================= 重定向根目录 =======================

    /**
     * 确保重定向根目录存在：/data/media/$userId/Android/media/<uidName>/sdcard_redirect
     *
     * 在为应用创建配置时必须立即调用，否则应用访问重定向路径会失败。
     */
    @SuppressLint("SdCardPath")
    fun ensureRedirectDir(currentUserId: Int, config: RuntimeConfig): Boolean {
        val redirectBase = PathConverter.getRedirectBase(config)
        val path = "/data/media/$currentUserId/$redirectBase"
        val dir = File(path)
        if (dir.exists()) return true
        return try {
            val created = dir.mkdirs()
            created || dir.exists()
        } catch (e: Exception) {
            Log.e(TAG, "ensureRedirectDir failed: $path", e)
            false
        }
    }

    // ======================= 目录 / 文件操作 =======================

    /**
     * 递归创建 Upper 层的父目录链（权限 0755）。
     *
     * @param upperFullPath Upper 层完整文件/目录路径（应为直通路径）
     * @return true 表示成功（目录已存在或创建成功）
     */
    fun ensureUpperDir(upperFullPath: String): Boolean {
        val dir = File(upperFullPath).parentFile ?: return true
        if (dir.exists()) return true
        return try {
            val created = dir.mkdirs()
            created || dir.exists()
        } catch (e: Exception) {
            Log.e(TAG, "ensureUpperDir failed: $upperFullPath", e)
            false
        }
    }

    /**
     * Copy-up 操作：将 Lower 层文件拷贝到 Upper 层。
     * 使用临时文件 + rename 保证原子性。
     *
     * @param originPath 原始路径（FUSE 路径，如 /storage/emulated/0/...）
     * @return Upper 层直通路径，copy-up 成功；null 表示无需 copy-up 或失败
     */
    fun copyUp(currentUserId: Int, config: RuntimeConfig, originPath: String): String? {
        val upperPath = PathConverter.getUpperPath(currentUserId, config, originPath)
        val upperFile = File(upperPath)

        // Upper 已存在，无需 copy-up
        if (upperFile.exists()) return upperPath

        // Lower 层使用直通路径
        val lowerDirectPath = PathConverter.toDirectPath(currentUserId, originPath)
        val lowerFile = File(lowerDirectPath)
        if (!lowerFile.exists()) {
            Log.w(TAG, "copyUp: lower file not exist: $originPath (direct: $lowerDirectPath)")
            return null
        }

        if (!ensureUpperDir(upperPath)) {
            Log.e(TAG, "copyUp: failed to create upper dir: $upperPath")
            return null
        }

        return try {
            val workDir = File(getWorkDirPath(currentUserId, config))
            if (!workDir.exists()) workDir.mkdirs()

            val tempFile = File(workDir, "copyup_${System.currentTimeMillis()}_${upperFile.name}")
            Files.copy(lowerFile.toPath(), tempFile.toPath(), StandardCopyOption.COPY_ATTRIBUTES)

            if (tempFile.renameTo(upperFile)) {
                Log.i(TAG, "copyUp success: $originPath -> $upperPath")
                upperPath
            } else {
                Files.copy(lowerFile.toPath(), upperFile.toPath(), StandardCopyOption.REPLACE_EXISTING)
                tempFile.delete()
                Log.w(TAG, "copyUp: rename failed, used fallback copy: $upperPath")
                upperPath
            }
        } catch (e: Exception) {
            Log.e(TAG, "copyUp failed: $originPath -> $upperPath", e)
            null
        }
    }

    // ======================= Whiteout =======================

    /**
     * 在 Upper 层创建 whiteout 文件，屏蔽 Lower 层同名条目。
     *
     * @param originPath 要屏蔽的原始路径（FUSE 路径）
     * @return true 表示 whiteout 创建成功（或已存在）
     */
    fun createWhiteout(currentUserId: Int, config: RuntimeConfig, originPath: String): Boolean {
        val upperPath = PathConverter.getUpperPath(currentUserId, config, originPath)
        val upperFile = File(upperPath)
        val whiteoutFile = File(upperFile.parent, "$WHITEOUT_PREFIX${upperFile.name}")

        if (whiteoutFile.exists()) return true

        if (!ensureUpperDir(upperPath)) {
            Log.e(TAG, "createWhiteout: failed to create upper dir: $upperPath")
            return false
        }

        return try {
            whiteoutFile.createNewFile()
            Log.i(TAG, "createWhiteout success: $whiteoutFile for $originPath")
            true
        } catch (e: Exception) {
            Log.e(TAG, "createWhiteout failed: $originPath", e)
            false
        }
    }

    /**
     * 移除 Upper 层的 whiteout 文件。
     */
    fun removeWhiteout(currentUserId: Int, config: RuntimeConfig, originPath: String): Boolean {
        val upperPath = PathConverter.getUpperPath(currentUserId, config, originPath)
        val upperFile = File(upperPath)
        val whiteoutFile = File(upperFile.parent, "$WHITEOUT_PREFIX${upperFile.name}")

        if (!whiteoutFile.exists()) return true

        return try {
            whiteoutFile.delete()
            Log.i(TAG, "removeWhiteout success: $originPath")
            true
        } catch (e: Exception) {
            Log.e(TAG, "removeWhiteout failed: $originPath", e)
            false
        }
    }

    /**
     * 检查原始路径是否被 whiteout 屏蔽。
     */
    fun isWhiteouted(currentUserId: Int, config: RuntimeConfig, originPath: String): Boolean {
        val upperPath = PathConverter.getUpperPath(currentUserId, config, originPath)
        val upperFile = File(upperPath)
        val whiteoutFile = File(upperFile.parent, "$WHITEOUT_PREFIX${upperFile.name}")
        return whiteoutFile.exists()
    }

    /**
     * 检查 Upper 层是否存在对应文件。
     */
    fun upperExists(currentUserId: Int, config: RuntimeConfig, originPath: String): Boolean {
        val upperPath = PathConverter.getUpperPath(currentUserId, config, originPath)
        return File(upperPath).exists()
    }
}
