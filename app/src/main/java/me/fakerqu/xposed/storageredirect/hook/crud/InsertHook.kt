package me.fakerqu.xposed.storageredirect.hook.crud

import android.annotation.SuppressLint
import android.content.ContentValues
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import io.github.libxposed.api.XposedInterface
import me.fakerqu.xposed.storageredirect.config.model.DirMode
import me.fakerqu.xposed.storageredirect.hook.core.HookContext
import me.fakerqu.xposed.storageredirect.hook.core.HookUtils
import me.fakerqu.xposed.storageredirect.hook.redirect.OverlayHelper
import me.fakerqu.xposed.storageredirect.hook.redirect.PathConverter

/**
 * Hook: MediaProvider.insert
 *
 * 文件创建时拦截，根据 mode 决定创建位置：
 * - w 模式：透传
 * - n 模式：修改 RELATIVE_PATH 指向 Upper
 * - r 模式：先移除 whiteout，再修改 RELATIVE_PATH 指向 Upper
 */
class InsertHook(private val ctx: HookContext) {

    fun install() {
        // insert(Uri, ContentValues) — 旧版
        try {
            val method = ctx.reflection.mediaProviderClass.getDeclaredMethod(
                "insert", Uri::class.java, ContentValues::class.java,
            )
            ctx.hookMethod(method) { chain -> intercept(chain) }
            return
        } catch (_: NoSuchMethodException) { }

        // insert(Uri, ContentValues, Bundle) — Android 13+
        try {
            val method = ctx.reflection.mediaProviderClass.getDeclaredMethod(
                "insert", Uri::class.java, ContentValues::class.java, Bundle::class.java,
            )
            ctx.hookMethod(method) { chain -> intercept(chain) }
        } catch (e: NoSuchMethodException) {
            ctx.warn("insert method not found, skipping hook", e)
        }
    }

    private fun intercept(chain: XposedInterface.Chain): Any? {
        val uid = ctx.reflection.getCallingUid(chain.thisObject)
        val config = ctx.configFor(uid) ?: return chain.proceed()

        try {
            val values = chain.args[1] as? ContentValues ?: return chain.proceed()
            val userId = HookUtils.getUserId(uid)

            val relativePath = values.get(MediaStore.MediaColumns.RELATIVE_PATH) as? String
            val displayName = values.get(MediaStore.MediaColumns.DISPLAY_NAME) as? String
            if (relativePath == null && displayName == null) return chain.proceed()

            val fullPath = buildFullPath(userId, relativePath, displayName)
            if (!PathConverter.pathNeedRedirect(userId, fullPath)) return chain.proceed()

            val mode = PathConverter.resolveMode(config, userId, fullPath)
            ctx.info("insert path=$fullPath mode=$mode")

            return when (mode) {
                DirMode.WRITE -> chain.proceed()
                DirMode.READ, DirMode.NONE -> {
                    // r 模式先移除 whiteout
                    if (mode == DirMode.READ) {
                        OverlayHelper.removeWhiteout(userId, config, fullPath)
                    }
                    // 修改 RELATIVE_PATH 指向 Upper
                    val upperRelativePath = buildUpperRelativePath(config, relativePath)
                    if (upperRelativePath != null) {
                        values.put(MediaStore.MediaColumns.RELATIVE_PATH, upperRelativePath)
                        ctx.info("insert redirected RELATIVE_PATH=$upperRelativePath")
                    }
                    chain.proceed()
                }
            }
        } catch (e: Exception) {
            ctx.error("insert hook failed", e)
            return chain.proceed()
        }
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
