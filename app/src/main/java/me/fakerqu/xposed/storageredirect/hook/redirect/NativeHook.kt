package me.fakerqu.xposed.storageredirect.hook.redirect

import android.util.Log
import io.github.libxposed.api.XposedModule
import me.fakerqu.xposed.storageredirect.config.model.DirMode
import me.fakerqu.xposed.storageredirect.config.model.RuntimeConfig

/**
 * JNI bridge for native stat/lstat/access/fstatat hooking via LSPosed native API.
 *
 * UID resolution is handled entirely in native layer by hooking /dev/fuse read()
 * and parsing fuse_in_header.uid. No Java-side UID management is needed.
 *
 * Configuration is pushed to native via [setUidConfig] / [clearAllConfigs].
 */
object NativeHook {

    private const val TAG = "SRX"
    @Volatile private var loaded = false

    /**
     * Load the native library inside the target process.
     * LSPosed will automatically call native_init() to install inline hooks.
     */
    fun init(module: XposedModule): Boolean {
        if (loaded) return true
        return try {
            System.loadLibrary("srx_native")
            loaded = true
            module.log(Log.INFO, TAG, "NativeHook loaded")
            true
        } catch (e: UnsatisfiedLinkError) {
            module.log(Log.ERROR, TAG, "Failed to load srx_native", e)
            false
        } catch (e: Exception) {
            module.log(Log.ERROR, TAG, "NativeHook init failed", e)
            false
        }
    }

    /**
     * Set redirect configuration for a UID.
     * Replaces any previous configuration for the same UID.
     *
     * Even if [config] has no dirConfigs, it will still be registered with default NONE mode
     * (all non-Android paths treated as isolated).
     *
     * @param uid     Target app UID
     * @param config  RuntimeConfig containing uidName and dirConfigs
     * @param userId  Android user ID (derived from uid)
     */
    fun setUidConfig(uid: Int, config: RuntimeConfig, userId: Int) {
        if (!loaded) return
        try {
            val relativePaths = config.dirConfigs.map { it.relativePath }.toTypedArray()
            val modes = config.dirConfigs.map {
                when (it.mode) {
                    DirMode.READ -> 0
                    DirMode.WRITE -> 1
                    DirMode.NONE -> 2
                }
            }.toIntArray()
            nativeSetUidConfig(uid, config.uidName, userId, relativePaths, modes)
        } catch (e: Exception) {
            Log.e(TAG, "setUidConfig failed: uid=$uid", e)
        }
    }

    /**
     * Remove redirect configuration for a UID.
     */
    fun removeUidConfig(uid: Int) {
        if (!loaded) return
        try {
            nativeRemoveUidConfig(uid)
        } catch (e: Exception) {
            Log.e(TAG, "removeUidConfig failed: uid=$uid", e)
        }
    }

    /**
     * Clear all configurations for all UIDs.
     */
    fun clearAllConfigs() {
        if (!loaded) return
        try {
            nativeClearAllConfigs()
        } catch (e: Exception) {
            Log.e(TAG, "nativeClearAllConfigs failed", e)
        }
    }

    /**
     * Remove all native inline hooks and clear global state.
     * Called during hot reload preparation (onHotReloading).
     *
     * After calling this, [init] can be called again to reload the native library.
     */
    fun cleanup() {
        if (!loaded) return
        try {
            nativeCleanup()
            loaded = false
        } catch (e: Exception) {
            Log.e(TAG, "nativeCleanup failed", e)
        }
    }

    // ---- JNI declarations ----

    @JvmStatic
    private external fun nativeSetUidConfig(
        uid: Int, uidName: String, userId: Int,
        relativePaths: Array<String>, modes: IntArray
    )

    @JvmStatic
    private external fun nativeRemoveUidConfig(uid: Int)

    @JvmStatic
    private external fun nativeClearAllConfigs()

    @JvmStatic
    private external fun nativeCleanup()
}
