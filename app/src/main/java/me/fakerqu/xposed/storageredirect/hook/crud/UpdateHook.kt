package me.fakerqu.xposed.storageredirect.hook.crud

import android.annotation.SuppressLint
import android.content.ContentValues
import android.net.Uri
import android.provider.MediaStore
import io.github.libxposed.api.XposedInterface
import me.fakerqu.xposed.storageredirect.config.model.DirMode
import me.fakerqu.xposed.storageredirect.hook.core.HookContext
import me.fakerqu.xposed.storageredirect.hook.core.HookUtils
import me.fakerqu.xposed.storageredirect.hook.redirect.OverlayHelper
import me.fakerqu.xposed.storageredirect.hook.redirect.PathConverter

/**
 * Hook: MediaProvider.update
 *
 * 拦截修改和重命名操作：
 * - 修改 RELATIVE_PATH/DISPLAY_NAME（重命名/移动）：
 *   r 模式先 copy-up，检查跨 mode 限制；w→r 返回 0；跨区域返回 0
 * - 其他字段修改：r 模式需先 copy-up
 */
class UpdateHook(private val ctx: HookContext) {

    fun install() {
        try {
            val method = ctx.reflection.mediaProviderClass.getDeclaredMethod(
                "update", Uri::class.java, ContentValues::class.java,
                String::class.java, Array<String>::class.java,
            )
            ctx.hookMethod(method) { chain -> intercept(chain) }
        } catch (e: NoSuchMethodException) {
            ctx.warn("update method not found, skipping hook", e)
        }
    }

    private fun intercept(chain: XposedInterface.Chain): Any? {
        val uid = ctx.reflection.getCallingUid(chain.thisObject)
        val config = ctx.configFor(uid) ?: return chain.proceed()

        try {
            val uri = chain.args[0] as Uri
            val values = chain.args[1] as? ContentValues ?: return chain.proceed()
            val userId = HookUtils.getUserId(uid)

            val dataPath = ctx.reflection.getDataPathFromUri(chain.thisObject, uri)
                ?: return chain.proceed()
            if (!PathConverter.pathNeedRedirect(userId, dataPath)) return chain.proceed()

            val sourceMode = PathConverter.resolveMode(config, userId, dataPath)
            ctx.info("update path=$dataPath sourceMode=$sourceMode")

            val newRelativePath = values.get(MediaStore.MediaColumns.RELATIVE_PATH) as? String
            val newDisplayName = values.get(MediaStore.MediaColumns.DISPLAY_NAME) as? String

            if (newRelativePath != null || newDisplayName != null) {
                // 重命名/移动操作
                handleRenameOrMove(chain, values, userId, config, dataPath, sourceMode, newRelativePath, newDisplayName)
            } else {
                // 普通字段修改
                if (sourceMode == DirMode.READ) {
                    OverlayHelper.copyUp(userId, config, dataPath)
                }
            }

            return chain.proceed()
        } catch (e: Exception) {
            ctx.error("update hook failed", e)
            return chain.proceed()
        }
    }

    @SuppressLint("SdCardPath")
    private fun handleRenameOrMove(
        chain: XposedInterface.Chain,
        values: ContentValues,
        userId: Int,
        config: me.fakerqu.xposed.storageredirect.config.model.RuntimeConfig,
        dataPath: String,
        sourceMode: DirMode,
        newRelativePath: String?,
        newDisplayName: String?,
    ): Any? {
        val currentRelativePath = dataPath
            .substringBeforeLast('/')
            .substringAfter("/storage/emulated/$userId/")

        val targetPath = buildFullPath(
            userId,
            newRelativePath ?: currentRelativePath,
            newDisplayName ?: dataPath.substringAfterLast('/'),
        )

        if (!PathConverter.pathNeedRedirect(userId, targetPath)) return chain.proceed()
        val targetMode = PathConverter.resolveMode(config, userId, targetPath)

        // w → r 禁止
        if (sourceMode == DirMode.WRITE && targetMode == DirMode.READ) {
            ctx.info("update w->r denied (EACCES)")
            return 0
        }

        // 跨 mode 区域（仅 r 模式有跨区域限制）
        if (sourceMode == DirMode.READ && targetMode != DirMode.READ) {
            ctx.info("update cross-region denied (EXDEV): $sourceMode -> $targetMode")
            return 0
        }

        // r 模式：先 copy-up，修改目标路径指向 Upper
        if (sourceMode == DirMode.READ) {
            OverlayHelper.copyUp(userId, config, dataPath)
            val upperRelativePath = buildUpperRelativePath(config, newRelativePath)
            if (upperRelativePath != null) {
                values.put(MediaStore.MediaColumns.RELATIVE_PATH, upperRelativePath)
            }
        }
        return chain.proceed()
    }

    @SuppressLint("SdCardPath")
    private fun buildFullPath(userId: Int, relativePath: String?, displayName: String?): String {
        val base = "/storage/emulated/$userId"
        val parts = mutableListOf<String>()
        if (!relativePath.isNullOrEmpty()) parts.add(relativePath.trimEnd('/'))
        if (!displayName.isNullOrEmpty()) parts.add(displayName)
        return "$base/${parts.joinToString("/")}"
    }

    private fun buildUpperRelativePath(
        config: me.fakerqu.xposed.storageredirect.config.model.RuntimeConfig,
        relativePath: String?,
    ): String? {
        if (relativePath == null) return null
        val basePath = PathConverter.getRedirectBase(config)
        return if (relativePath.isNotEmpty()) {
            "$basePath/${relativePath.trimStart('/')}"
        } else {
            "$basePath/"
        }
    }
}
