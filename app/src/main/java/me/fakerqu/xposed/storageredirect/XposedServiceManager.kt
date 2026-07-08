package me.fakerqu.xposed.storageredirect

import io.github.libxposed.service.XposedService
import io.github.libxposed.service.XposedServiceHelper
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * XposedService 单例管理器
 *
 * 确保 [XposedServiceHelper.registerListener] 在整个应用生命周期中只被调用一次，
 * 并统一管理 [XposedService] 实例的获取与生命周期监听。
 *
 * 通过 [service] StateFlow 暴露当前绑定的 XposedService 实例（null 表示未绑定/已断开），
 * 通过 [isHooked] 暴露当前是否处于已绑定状态。
 */
object XposedServiceManager : XposedServiceHelper.OnServiceListener {

    private val _service = MutableStateFlow<XposedService?>(null)
    val service: StateFlow<XposedService?> = _service.asStateFlow()

    val isHooked: Boolean get() = _service.value != null

    init {
        XposedServiceHelper.registerListener(this)
    }

    override fun onServiceBind(service: XposedService) {
        _service.value = service
    }

    override fun onServiceDied(service: XposedService) {
        _service.value = null
    }
}
