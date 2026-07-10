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
import me.fakerqu.xposed.storageredirect.config.model.UserConfig
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
import me.fakerqu.xposed.storageredirect.hook.redirect.OverlayHelper

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

    private var hookContext: HookContext? = null

    @SuppressLint("PrivateApi")
    override fun onPackageReady(param: XposedModuleInterface.PackageReadyParam) {
        super.onPackageReady(param)
        if (param.packageName != "com.android.providers.media.module") return

        try {
            log(Log.INFO, HookContext.TAG, "install hook on ${param.packageName}")
            val reflection = MediaProviderReflection(param.classLoader)
            val hc = HookContext(this, reflection)
            hookContext = hc

            // attachInfo hook：在 MediaProvider 初始化完成后加载配置并安装 hooks
            hook(
                reflection.mediaProviderClass.getDeclaredMethod(
                    "attachInfo",
                    Context::class.java,
                    android.content.pm.ProviderInfo::class.java,
                )
            ).intercept { chain ->
                val context = chain.args[0] as Context
                initHooks(context, hc)
                chain.proceed()
            }
        } catch (e: Exception) {
            log(Log.ERROR, HookContext.TAG, "failed on package ready", e)
        }
    }

    // ---- 共用初始化 ----

    /**
     * 初始化 native、加载配置、注册配置监听、安装 Java hooks。
     *
     * 在首次加载（attachInfo 回调中）和热重载时共用。
     *
     * @param context  用于 PackageManager 的 Context（首次加载来自 attachInfo，热重载时为 null，
     *                 reloadConfig 会通过 AppGlobals 获取 PackageManager）
     * @param hc      当前 generation 的 HookContext
     */
    private fun initHooks(context: Context, hc: HookContext) {
        // 1. Initialize native hooks
        try {
            NativeHook.init(this)
        } catch (e: Exception) {
            log(Log.ERROR, HookContext.TAG, "NativeHook.init failed", e)
        }

        // 2. Load config and register listener
        val prefs = getRemotePreferences(ConfigConstants.CONFIG_SHARED_PREFERENCE)
        reloadConfig(context, 0, hc)
        prefs.registerOnSharedPreferenceChangeListener { p, key ->
            if (key == ConfigConstants.CONFIG_VERSION_KEY) {
                reloadConfig(context, p.getLong(key, 0L), hc)
            }
        }

        // 3. Install Java hooks
        try {
            log(Log.INFO, HookContext.TAG, "installAllHooks start")
            installAllHooks(hc)
            log(Log.INFO, HookContext.TAG, "installAllHooks done")
        } catch (e: Exception) {
            log(Log.ERROR, HookContext.TAG, "installAllHooks failed", e)
        }
    }

    // ---- Hook 安装 ----

    private fun installAllHooks(hc: HookContext) {
        // FUSE 相关
        FuseDirectoryHook(hc).install()
        FuseFileLookupHook(hc).install()
        FuseRestrictionHooks(hc).install()

        // 查询
        QueryHook(hc).install()

        // CRUD
        InsertHook(hc).install()
        DeleteHook(hc).install()
        UpdateHook(hc).install()
        OpenFileHook(hc).install()
    }

    // ---- 配置加载 ----

    @SuppressLint("PrivateApi")
    @OptIn(ExperimentalSerializationApi::class)
    private fun reloadConfig(context: Context, version: Long, hc: HookContext = hookContext!!) {
        val pm: PackageManager = context.packageManager ?: run {
            log(Log.ERROR, HookContext.TAG, "Failed to get PackageManager")
            return
        }
        val oldByUid = hc.snapshot().byUid

        openRemoteFile(ConfigConstants.CONFIG_FILE).use { descriptor ->
            ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { inputStream ->
                try {
                    val userConfig = Json.decodeFromStream<
                            UserConfig>(inputStream)

                    if (userConfig.enabled) {
                        val packageConfigs = userConfig.packageConfigs.filter { it.enabled }
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

                        hc.configSnapshot.set(
                            ConfigSnapshot(
                                version,
                                packageConfigs.associateBy { it.packageName },
                                newByUid,
                            )
                        )
                        log(Log.INFO, HookContext.TAG, "reload config ${hc.snapshot()}")

                        // Remove configs for UIDs that are no longer active (disabled or removed)
                        val removedUids = oldByUid.keys - newByUid.keys
                        removedUids.forEach { uid ->
                            NativeHook.removeUidConfig(uid)
                        }

                        // Push active configs to native layer
                        newByUid.forEach { (uid, config) ->
                            val userId = HookUtils.getUserId(uid)
                            // Ensure redirect directory exists before pushing config
                            OverlayHelper.ensureRedirectDir(userId, config)
                            NativeHook.setUidConfig(uid, config, userId)
                        }
                    } else {
                        // Master switch is off — clear everything
                        hc.configSnapshot.set(ConfigSnapshot.EMPTY)
                        log(Log.INFO, HookContext.TAG, "reload config (disabled)")

                        oldByUid.keys.forEach { uid ->
                            NativeHook.removeUidConfig(uid)
                        }
                    }
                } catch (e: Exception) {
                    hc.error("failed reloading config", e)
                }
            }
        }
    }

    // ---- Hot Reload ----

    override fun onHotReloading(param: XposedModuleInterface.HotReloadingParam): Boolean {
        log(Log.INFO, HookContext.TAG, "onHotReloading: preparing for hot reload")
        try {
            // 1. Remove all native inline hooks and clear native state
            NativeHook.cleanup()

            // 2. Clear Java-layer state
            //    The old hookContext references the old XposedModule instance;
            //    it will be GC'd once we release our reference.
            hookContext = null

            log(Log.INFO, HookContext.TAG, "onHotReloading: cleanup done, allowing reload")
            return true
        } catch (e: Exception) {
            log(Log.ERROR, HookContext.TAG, "onHotReloading failed", e)
            return false
        }
    }

    @SuppressLint("PrivateApi")
    override fun onHotReloaded(param: XposedModuleInterface.HotReloadedParam) {
        log(Log.INFO, HookContext.TAG, "onHotReloaded: installing new hooks")
        val oldHandles = param.oldHookHandles

        try {
            // 1. 通过 ActivityThread.currentApplication() 获取目标进程的 Application
            //    Application 对象属于目标进程的 PathClassLoader，不是模块的 InMemoryDexClassLoader
            val app = Class.forName("android.app.ActivityThread")
                .getDeclaredMethod("currentApplication").apply { isAccessible = true }
                .invoke(null) as? Context
            if (app == null) {
                log(Log.ERROR, HookContext.TAG, "onHotReloaded: currentApplication() returned null")
                oldHandles.forEach { it.unhook() }
                return
            }

            // 2. 从 Application class 获取目标进程的 ClassLoader
            val classLoader = app.javaClass.classLoader!!
            val reflection = MediaProviderReflection(classLoader)
            val hc = HookContext(this, reflection)
            hookContext = hc

            // 3. Init native + config + Java hooks (shared with first load)
            initHooks(app, hc)

            log(Log.INFO, HookContext.TAG, "onHotReloaded: all hooks reinstalled successfully")

            // 4. Unhook all old handles (they are now superseded by new hooks)
            oldHandles.forEach { it.unhook() }
        } catch (e: Exception) {
            log(Log.ERROR, HookContext.TAG, "onHotReloaded failed", e)
            // Fallback: unhook old hooks to avoid inconsistent state
            oldHandles.forEach { it.unhook() }
        }
    }
}
