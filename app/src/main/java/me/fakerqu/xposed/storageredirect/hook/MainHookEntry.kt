package me.fakerqu.xposed.storageredirect.hook

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.os.ParcelFileDescriptor
import android.util.Log
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromStream
import me.fakerqu.xposed.storageredirect.config.ConfigConstants
import me.fakerqu.xposed.storageredirect.config.model.RuntimeConfig
import me.fakerqu.xposed.storageredirect.hook.core.ConfigSnapshot
import me.fakerqu.xposed.storageredirect.hook.core.HookContext
import me.fakerqu.xposed.storageredirect.hook.core.HookUtils
import me.fakerqu.xposed.storageredirect.hook.core.MediaProviderReflection
import me.fakerqu.xposed.storageredirect.hook.crud.DeleteHook
import me.fakerqu.xposed.storageredirect.hook.crud.InsertHook
import me.fakerqu.xposed.storageredirect.hook.crud.OpenFileHook
import me.fakerqu.xposed.storageredirect.hook.crud.UpdateHook
import me.fakerqu.xposed.storageredirect.hook.fuse.FuseDirectoryHook
import me.fakerqu.xposed.storageredirect.hook.fuse.FuseFileLookupHook
import me.fakerqu.xposed.storageredirect.hook.fuse.FuseRestrictionHooks
import me.fakerqu.xposed.storageredirect.hook.query.QueryHook
import me.fakerqu.xposed.storageredirect.hook.redirect.NativeHook

/**
 * Xposed 模块入口。
 *
 * 职责仅限于：
 * 1. 匹配目标包名 (com.android.providers.media.module)
 * 2. 初始化 [HookContext]（反射缓存 + 配置快照）
 * 3. 注册配置变更监听
 * 4. 安装所有 Hook 处理器
 *
 * 各 Hook 的具体逻辑分散在独立类中：
 * - [FuseDirectoryHook] / [FuseFileLookupHook] / [FuseRestrictionHooks] — FUSE 相关
 * - [QueryHook]   — MediaStore 查询拦截
 * - [InsertHook] / [DeleteHook] / [UpdateHook] / [OpenFileHook] — CRUD 操作
 */
class MainHookEntry : XposedModule() {

    private lateinit var hookContext: HookContext

    @SuppressLint("PrivateApi")
    override fun onPackageReady(param: XposedModuleInterface.PackageReadyParam) {
        super.onPackageReady(param)
        if (param.packageName != "com.android.providers.media.module") return

        try {
            log(Log.INFO, HookContext.TAG, "install hook on ${param.packageName}")
            val reflection = MediaProviderReflection(param.classLoader)
            hookContext = HookContext(this, reflection)

            // attachInfo hook：在 MediaProvider 初始化完成后加载配置并安装 hooks
            hook(
                reflection.mediaProviderClass.getDeclaredMethod(
                    "attachInfo",
                    Context::class.java,
                    android.content.pm.ProviderInfo::class.java,
                )
            ).intercept { chain ->
                // Initialize native hooks first so reloadConfig can push configs
                try {
                    NativeHook.init(this@MainHookEntry)
                } catch (e: Exception) {
                    log(Log.ERROR, HookContext.TAG, "NativeHook.init failed", e)
                }

                val context = chain.args[0] as Context
                val prefs = getRemotePreferences(ConfigConstants.CONFIG_SHARED_PREFERENCE)

                reloadConfig(context, 0)

                prefs.registerOnSharedPreferenceChangeListener { p, key ->
                    if (key == ConfigConstants.CONFIG_VERSION_KEY) {
                        reloadConfig(context, p.getLong(key, 0L))
                    }
                }

                val result = chain.proceed()
                try {
                    log(Log.INFO, HookContext.TAG, "installAllHooks start")
                    installAllHooks(param.classLoader)
                    log(Log.INFO, HookContext.TAG, "installAllHooks done")
                } catch (e: Exception) {
                    log(Log.ERROR, HookContext.TAG, "installAllHooks failed", e)
                }
                result
            }
        } catch (e: Exception) {
            log(Log.ERROR, HookContext.TAG, "failed on package ready", e)
        }
    }

    // ---- Hook 安装 ----

    private fun installAllHooks(@Suppress("UNUSED_PARAMETER") classLoader: ClassLoader) {
        // FUSE 相关
        FuseDirectoryHook(hookContext).install()
        FuseFileLookupHook(hookContext).install()
        FuseRestrictionHooks(hookContext).install()

        // 查询
        QueryHook(hookContext).install()

        // CRUD
        InsertHook(hookContext).install()
        DeleteHook(hookContext).install()
        UpdateHook(hookContext).install()
        OpenFileHook(hookContext).install()
    }

    // ---- 配置加载 ----

    @OptIn(ExperimentalSerializationApi::class)
    private fun reloadConfig(context: Context, version: Long) {
        val oldByUid = hookContext.snapshot().byUid

        openRemoteFile(ConfigConstants.CONFIG_FILE).use { descriptor ->
            ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { inputStream ->
                val userConfig = Json.decodeFromStream<
                    me.fakerqu.xposed.storageredirect.config.model.UserConfig>(inputStream)

                if (userConfig.enabled) {
                    val packageConfigs = userConfig.packageConfigs.filter { it.enabled }
                    val pm = context.packageManager
                    val newByUid = packageConfigs.associate {
                        val uid = pm.getPackageUid(
                            it.packageName,
                            PackageManager.MATCH_UNINSTALLED_PACKAGES,
                        )
                        uid to RuntimeConfig(
                            uid,
                            pm.getNameForUid(uid) ?: it.packageName,
                            it.dirConfigs,
                        )
                    }

                    hookContext.configSnapshot.set(
                        ConfigSnapshot(
                            version,
                            packageConfigs.associateBy { it.packageName },
                            newByUid,
                        )
                    )
                    log(Log.INFO, HookContext.TAG, "reload config ${hookContext.snapshot()}")

                    // Remove configs for UIDs that are no longer active (disabled or removed)
                    val removedUids = oldByUid.keys - newByUid.keys
                    removedUids.forEach { uid ->
                        NativeHook.removeUidConfig(uid)
                    }

                    // Push active configs to native layer
                    newByUid.forEach { (uid, config) ->
                        val userId = HookUtils.getUserId(uid)
                        NativeHook.setUidConfig(uid, config, userId)
                    }
                } else {
                    // Master switch is off — clear everything
                    hookContext.configSnapshot.set(ConfigSnapshot.EMPTY)
                    log(Log.INFO, HookContext.TAG, "reload config (disabled)")

                    oldByUid.keys.forEach { uid ->
                        NativeHook.removeUidConfig(uid)
                    }
                }
            }
        }
    }
}
