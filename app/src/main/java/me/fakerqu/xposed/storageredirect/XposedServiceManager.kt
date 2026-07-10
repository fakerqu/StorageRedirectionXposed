package me.fakerqu.xposed.storageredirect

import android.util.Log
import io.github.libxposed.service.HookedTarget
import io.github.libxposed.service.XposedService
import io.github.libxposed.service.XposedServiceHelper
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

private const val TAG = "XposedServiceManager"

/**
 * XposedService 单例管理器
 *
 * 确保 [XposedServiceHelper.registerListener] 在整个应用生命周期中只被调用一次，
 * 并统一管理 [XposedService] 实例的获取与生命周期监听。
 *
 * 通过 [service] StateFlow 暴露当前绑定的 XposedService 实例（null 表示未绑定/已断开），
 * 通过 [isHooked] 暴露当前是否处于已绑定状态。
 * 通过 [needsRestart] 暴露是否有 scope 进程需要重启才能生效。
 */
object XposedServiceManager : XposedServiceHelper.OnServiceListener {

    private val _service = MutableStateFlow<XposedService?>(null)
    val service: StateFlow<XposedService?> = _service.asStateFlow()

    /**
     * LSP 是否已激活且模块的静态作用域均在生效中。
     */
    val isHooked: Boolean
        get() = _service.value.let {
            it != null && it.scope.isNotEmpty()
        }

    private val _needsRestart = MutableStateFlow(false)

    /**
     * 是否有 scope 进程需要重启才能使模块生效。
     *
     * 以下情况返回 true：
     * - scope 进程正在运行，但其 hook 状态为 [HookedTarget.State.STALE]（模块已更新但进程未重启）
     * - scope 进程正在运行，但其 hook 状态为 [HookedTarget.State.FAILED]（上次热重载失败）
     */
    val needsRestart: StateFlow<Boolean> = _needsRestart.asStateFlow()

    init {
        XposedServiceHelper.registerListener(this)
    }

    override fun onServiceBind(service: XposedService) {
        _service.value = service
        refreshNeedsRestart()
    }

    override fun onServiceDied(service: XposedService) {
        _service.value = null
        _needsRestart.value = false
    }

    /**
     * 查询当前正在运行的 scope 进程，判断是否有需要重启的进程。
     *
     * 需要重启的判定条件：
     * - 进程正在运行但 hook 状态为 STALE（模块版本已更新但进程未重启）
     * - 进程正在运行但 hook 状态为 FAILED（热重载失败）
     */
    private fun refreshNeedsRestart() {
        val svc = _service.value ?: return
        if (svc.apiVersion < XposedService.API_102) {
            Log.w(TAG, "API < ${XposedService.API_102}, skip restart check")
            return
        }
        try {
            val targets = svc.getRunningTargets()
            val hasStale = targets.any { target ->
                target.state in setOf(
                    HookedTarget.State.STALE,
                    HookedTarget.State.FAILED,
                )
            }
            _needsRestart.value = hasStale
        } catch (e: XposedService.ServiceException) {
            Log.w(TAG, "Failed to get running targets", e)
        }
    }
}
