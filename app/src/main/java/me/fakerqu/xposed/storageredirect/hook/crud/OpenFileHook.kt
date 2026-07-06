package me.fakerqu.xposed.storageredirect.hook.crud

import android.net.Uri
import android.os.Bundle
import android.os.CancellationSignal
import io.github.libxposed.api.XposedInterface
import me.fakerqu.xposed.storageredirect.config.model.DirMode
import me.fakerqu.xposed.storageredirect.hook.core.HookContext
import me.fakerqu.xposed.storageredirect.hook.core.HookUtils
import me.fakerqu.xposed.storageredirect.hook.redirect.OverlayHelper
import me.fakerqu.xposed.storageredirect.hook.redirect.PathConverter

/**
 * Hook: MediaProvider.openFile / openTypedAssetFile
 *
 * 文件描述符打开时拦截：
 * - w 模式：透传
 * - n 模式：确保 Upper 目录存在
 * - r 模式：写操作先 copy-up
 */
class OpenFileHook(private val ctx: HookContext) {

    fun install() {
        // openFile(Uri, String mode)
        try {
            val method = ctx.reflection.mediaProviderClass.getDeclaredMethod(
                "openFile", Uri::class.java, String::class.java,
            )
            ctx.hookMethod(method) { chain -> intercept(chain) }
        } catch (e: NoSuchMethodException) {
            ctx.warn("openFile method not found, skipping hook", e)
        }

        // openTypedAssetFile(Uri, String mimeTypeFilter, Bundle opts, CancellationSignal)
        try {
            val method = ctx.reflection.mediaProviderClass.getDeclaredMethod(
                "openTypedAssetFile",
                Uri::class.java, String::class.java,
                Bundle::class.java, CancellationSignal::class.java,
            )
            ctx.hookMethod(method) { chain -> intercept(chain) }
        } catch (e: NoSuchMethodException) {
            ctx.warn("openTypedAssetFile method not found, skipping hook", e)
        }
    }

    private fun intercept(chain: XposedInterface.Chain): Any? {
        val uid = ctx.reflection.getCallingUid(chain.thisObject)
        val config = ctx.configFor(uid) ?: return chain.proceed()

        try {
            val uri = chain.args[0] as Uri
            val userId = HookUtils.getUserId(uid)
            val openMode = (chain.args.getOrNull(1) as? String) ?: "r"

            val dataPath = ctx.reflection.getDataPathFromUri(chain.thisObject, uri)
                ?: return chain.proceed()
            if (!PathConverter.pathNeedRedirect(userId, dataPath)) return chain.proceed()

            val mode = PathConverter.resolveMode(config, userId, dataPath)
            ctx.info("openFile path=$dataPath mode=$mode openMode=$openMode")

            return when (mode) {
                DirMode.WRITE -> chain.proceed()

                DirMode.READ -> {
                    if (openMode.contains("w") || openMode.contains("rw")) {
                        OverlayHelper.copyUp(userId, config, dataPath)
                    }
                    chain.proceed()
                }

                DirMode.NONE -> {
                    val upperPath = PathConverter.getUpperPath(userId, config, dataPath)
                    OverlayHelper.ensureUpperDir(upperPath)
                    chain.proceed()
                }
            }
        } catch (e: Exception) {
            ctx.error("openFile hook failed", e)
            return chain.proceed()
        }
    }
}
