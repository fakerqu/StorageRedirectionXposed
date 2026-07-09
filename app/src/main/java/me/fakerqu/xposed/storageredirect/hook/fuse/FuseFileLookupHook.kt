package me.fakerqu.xposed.storageredirect.hook.fuse

import io.github.libxposed.api.XposedInterface
import me.fakerqu.xposed.storageredirect.config.model.DirMode
import me.fakerqu.xposed.storageredirect.hook.core.HookContext
import me.fakerqu.xposed.storageredirect.hook.core.HookUtils
import me.fakerqu.xposed.storageredirect.hook.redirect.OverlayHelper
import me.fakerqu.xposed.storageredirect.hook.redirect.PathConverter

/**
 * Hook: MediaProvider.onFileLookupForFuse(String path, int uid, int forWrite)
 *
 * FUSE getattr 回调，替代旧版 getFileForFuse。
 *
 * 根据 mode 决定返回哪个层的文件信息：
 * - w 模式：透传
 * - n 模式：替换为 Upper 路径，先确保 MediaStore 注册
 * - r 模式：Upper 存在用 Upper；被 whiteout → null；否则透传原始路径
 */
class FuseFileLookupHook(private val ctx: HookContext) {

    fun install() {
        try {
            val method = ctx.reflection.mediaProviderClass.getDeclaredMethod(
                "onFileLookupForFuse",
                String::class.java,
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
            )
            ctx.hookMethod(method) { chain -> intercept(chain) }
        } catch (e: NoSuchMethodException) {
            ctx.warn("onFileLookupForFuse not found", e)
        }
    }

    private fun intercept(chain: XposedInterface.Chain): Any? {
        val path = chain.args[0] as String
        val uid = chain.args[1] as Int
        val config = ctx.configFor(uid) ?: return chain.proceed()

        val userId = HookUtils.getUserId(uid)
        if (!PathConverter.pathNeedRedirect(userId, path)) return chain.proceed()

        val mode = PathConverter.resolveMode(config, userId, path)
        val isAncestor = PathConverter.isAncestorOfGranted(config, userId, path)
        ctx.info("onFileLookupForFuse path=$path uid=$uid mode=$mode isAncestor=$isAncestor")

        return try {
            when {
                mode == DirMode.WRITE -> chain.proceed()
                mode == DirMode.READ -> handleReadMode(chain, userId, config, path, uid)
                // NONE but ancestor → allow lookup (transparency to lower)
//                isAncestor -> chain.proceed()
                // NONE and not ancestor → redirect to Upper
                else -> handleNoneMode(chain, userId, config, path, uid)
            }
        } catch (e: Exception) {
            ctx.error("onFileLookupForFuse failed", e)
            chain.proceed()
        }
    }

    private fun handleNoneMode(
        chain: XposedInterface.Chain,
        userId: Int,
        config: me.fakerqu.xposed.storageredirect.config.model.RuntimeConfig,
        path: String,
        uid: Int,
    ): Any? {
        // This path is NONE mode and not an ancestor of any granted dir.
        // Redirect to the Upper layer so the app sees only redirected content.
        val upperFusePath = PathConverter.getUpperFusePath(userId, config, path)
        ctx.info("onFileLookupForFuse n-mode upperFusePath=$upperFusePath")
        ensureFileInMediaStore(chain.thisObject, upperFusePath, uid)
        return chain.proceedWith(chain.thisObject, arrayOf<Any?>(upperFusePath, uid, chain.args[2]))
    }

    private fun handleReadMode(
        chain: XposedInterface.Chain,
        userId: Int,
        config: me.fakerqu.xposed.storageredirect.config.model.RuntimeConfig,
        path: String,
        uid: Int,
    ): Any? {
        if (OverlayHelper.upperExists(userId, config, path)) {
            val upperFusePath = PathConverter.getUpperFusePath(userId, config, path)
            ctx.info("onFileLookupForFuse r-mode use upper=$upperFusePath")
            ensureFileInMediaStore(chain.thisObject, upperFusePath, uid)
            return chain.proceedWith(chain.thisObject, arrayOf<Any?>(upperFusePath, uid, chain.args[2]))
        } else if (OverlayHelper.isWhiteouted(userId, config, path)) {
            ctx.info("onFileLookupForFuse r-mode whiteouted, return null: $path")
            return null
        } else {
            ctx.info("onFileLookupForFuse r-mode use lower: $path")
            return chain.proceed()
        }
    }

    private fun ensureFileInMediaStore(mediaProvider: Any, path: String, uid: Int) {
        try {
            val file = java.io.File(path)
            if (!file.exists()) return
            ctx.reflection.ensureFileInMediaStore(mediaProvider, path, uid)
            ctx.info("ensureFileInMediaStore: registered $path")
        } catch (e: Exception) {
            ctx.warn("ensureFileInMediaStore failed for $path", e)
        }
    }
}
