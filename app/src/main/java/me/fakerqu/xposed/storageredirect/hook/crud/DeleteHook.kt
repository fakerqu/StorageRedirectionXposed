package me.fakerqu.xposed.storageredirect.hook.crud

import android.net.Uri
import io.github.libxposed.api.XposedInterface
import me.fakerqu.xposed.storageredirect.config.model.DirMode
import me.fakerqu.xposed.storageredirect.hook.core.HookContext
import me.fakerqu.xposed.storageredirect.hook.core.HookUtils
import me.fakerqu.xposed.storageredirect.hook.redirect.OverlayHelper
import me.fakerqu.xposed.storageredirect.hook.redirect.PathConverter
import java.io.File

/**
 * Hook: MediaProvider.delete
 *
 * 删除保护：
 * - w 模式：创建 whiteout，不删除底层文件，返回 1
 * - r 模式：Upper 有实体删 Upper；Lower 有同名创建 whiteout；返回 1
 * - n 模式：透传（删除 Upper 文件）
 */
class DeleteHook(private val ctx: HookContext) {

    fun install() {
        try {
            val method = ctx.reflection.mediaProviderClass.getDeclaredMethod(
                "delete", Uri::class.java, String::class.java, Array<String>::class.java,
            )
            ctx.hookMethod(method) { chain -> intercept(chain) }
        } catch (e: NoSuchMethodException) {
            ctx.warn("delete method not found, skipping hook", e)
        }
    }

    private fun intercept(chain: XposedInterface.Chain): Any? {
        val uid = ctx.reflection.getCallingUid(chain.thisObject)
        val config = ctx.configFor(uid) ?: return chain.proceed()

        try {
            val uri = chain.args[0] as Uri
            val userId = HookUtils.getUserId(uid)

            val dataPath = ctx.reflection.getDataPathFromUri(chain.thisObject, uri)
                ?: return chain.proceed()
            if (!PathConverter.pathNeedRedirect(userId, dataPath)) return chain.proceed()

            val mode = PathConverter.resolveMode(config, userId, dataPath)
            ctx.info("delete path=$dataPath mode=$mode")

            return when (mode) {
                DirMode.WRITE -> {
                    // w 模式：创建 whiteout，不删除底层
                    OverlayHelper.createWhiteout(userId, config, dataPath)
                    1
                }

                DirMode.READ -> {
                    // r 模式：删 Upper 实体 + 对 Lower 创建 whiteout
                    if (OverlayHelper.upperExists(userId, config, dataPath)) {
                        val upperPath = PathConverter.getUpperPath(userId, config, dataPath)
                        File(upperPath).delete()
                    }
                    if (File(dataPath).exists()) {
                        OverlayHelper.createWhiteout(userId, config, dataPath)
                    }
                    1
                }

                DirMode.NONE -> chain.proceed()
            }
        } catch (e: Exception) {
            ctx.error("delete hook failed", e)
            return chain.proceed()
        }
    }
}
