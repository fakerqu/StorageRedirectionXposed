package me.fakerqu.xposed.storageredirect.hook.core

import android.util.Log
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import java.lang.reflect.Method
import java.util.concurrent.atomic.AtomicReference

/**
 * 所有 Hook 处理器的共享上下文。
 *
 * 持有模块实例（用于日志/hook 安装）、配置快照和反射缓存，
 * 各 Hook 类通过此上下文访问公共状态，避免重复初始化。
 */
class HookContext(
    val module: XposedModule,
    val reflection: MediaProviderReflection,
) {
    val configSnapshot = AtomicReference(ConfigSnapshot.EMPTY)

    /** 获取当前配置快照（线程安全） */
    fun snapshot(): ConfigSnapshot = configSnapshot.get()

    /** 根据调用者 UID 获取 RuntimeConfig，无配置返回 null */
    fun configFor(uid: Int) = configSnapshot.get().byUid[uid]

    // ---- 日志快捷方法 ----

    fun log(level: Int, tag: String = TAG, msg: String, e: Throwable? = null) {
        module.log(level, tag, msg, e)
    }

    fun info(msg: String, e: Throwable? = null) = module.log(Log.INFO, TAG, msg, e)
    fun warn(msg: String, e: Throwable? = null) = module.log(Log.WARN, TAG, msg, e)
    fun error(msg: String, e: Throwable? = null) = module.log(Log.ERROR, TAG, msg, e)

    // ---- Hook 安装 ----

    fun hookMethod(method: Method, body: (XposedInterface.Chain) -> Any?) {
        module.deoptimize(method)
        module.hook(method).intercept(body)
    }

    companion object {
        const val TAG = "SRX"
    }
}
