package me.fakerqu.xposed.storageredirect.hook.fuse

import io.github.libxposed.api.XposedInterface
import me.fakerqu.xposed.storageredirect.config.model.DirMode
import me.fakerqu.xposed.storageredirect.hook.core.HookContext
import me.fakerqu.xposed.storageredirect.hook.core.HookUtils
import me.fakerqu.xposed.storageredirect.hook.redirect.PathConverter
import java.io.File

/**
 * FUSE 权限/限制相关的 3 个 Hook 合并于一处：
 *
 * 1. shouldBypassFuseRestrictions — 重定向应用强制 false
 * 2. isDirAccessAllowedForFuse    — 根据目录模式决定是否允许访问
 * 3. isCallingPackageRequestingLegacy — 重定向应用强制 false
 */
class FuseRestrictionHooks(private val ctx: HookContext) {

    fun install() {
        hookShouldBypassFuseRestrictions()
        hookIsDirAccessAllowedForFuse()
        hookIsCallingPackageRequestingLegacy()
    }

    // ---- shouldBypassFuseRestrictions ----

    private fun hookShouldBypassFuseRestrictions() {
        try {
            val method = ctx.reflection.mediaProviderClass.getDeclaredMethod(
                "shouldBypassFuseRestrictions",
                Boolean::class.javaPrimitiveType,
                String::class.java,
            )
            ctx.hookMethod(method) { chain ->
                val result = chain.proceed()
                try {
                    val uid = ctx.reflection.getCallingUid(chain.thisObject)
                    val path = chain.args[1] as? String? ?: return@hookMethod result
                    val config = ctx.configFor(uid)
                    if (config != null && PathConverter.pathNeedRedirect(HookUtils.getUserId(uid), path)) {
                        ctx.info("shouldBypassFuseRestrictions force false: path=$path, uid=$uid")
                        return@hookMethod false
                    }
                } catch (e: Exception) {
                    ctx.error("hook shouldBypassFuseRestrictions failed", e)
                }
                result
            }
        } catch (e: NoSuchMethodException) {
            ctx.warn("shouldBypassFuseRestrictions not found", e)
        }
    }

    // ---- isDirAccessAllowedForFuse ----

    private fun hookIsDirAccessAllowedForFuse() {
        try {
            val method = ctx.reflection.mediaProviderClass.getDeclaredMethod(
                "isDirAccessAllowedForFuse",
                String::class.java,
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
            )
            ctx.hookMethod(method) { chain -> interceptDirAccess(chain) }
        } catch (e: NoSuchMethodException) {
            ctx.warn("isDirAccessAllowedForFuse method not found", e)
        }
    }

    private fun interceptDirAccess(chain: XposedInterface.Chain): Any? {
        val path = chain.args[0] as? String? ?: return chain.proceed()
        val uid = chain.args[1] as Int
        try {
            val config = ctx.configFor(uid) ?: return chain.proceed()
            val userId = HookUtils.getUserId(uid)
            if (!PathConverter.pathNeedRedirect(userId, path)) return chain.proceed()

            val mode = PathConverter.resolveMode(config, userId, path)
            // WRITE/READ: always allow
            if (mode == DirMode.WRITE || mode == DirMode.READ) return 0
            // NONE: only allow if upper directory exists
            if (mode == DirMode.NONE) {
                val upperPath = PathConverter.getUpperPath(userId, config, path)
                if (File(upperPath).exists()) return 0
                ctx.info("isDirAccessAllowedForFuse deny (NONE, no upper): path=$path, uid=$uid")
                return 2 // ENOENT
            }
            return chain.proceed()
        } catch (e: Exception) {
            ctx.error("hook isDirAccessAllowedForFuse failed", e)
            return chain.proceed()
        }
    }

    // ---- isCallingPackageRequestingLegacy ----

    private fun hookIsCallingPackageRequestingLegacy() {
        try {
            val method = ctx.reflection.mediaProviderClass.getDeclaredMethod(
                "isCallingPackageRequestingLegacy",
            )
            ctx.hookMethod(method) { chain ->
                val originResult = chain.proceed()
                try {
                    val uid = ctx.reflection.getCallingUid(chain.thisObject)
                    ctx.info("isCallingPackageRequestingLegacy uid=$uid origin=$originResult")
                    if (ctx.configFor(uid) != null) return@hookMethod false
                } catch (e: Exception) {
                    ctx.error("hook isCallingPackageRequestingLegacy failed", e)
                }
                originResult
            }
        } catch (e: NoSuchMethodException) {
            ctx.warn("isCallingPackageRequestingLegacy not found", e)
        }
    }
}
