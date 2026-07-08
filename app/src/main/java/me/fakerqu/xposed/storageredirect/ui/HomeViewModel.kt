package me.fakerqu.xposed.storageredirect.ui

import android.app.Application
import android.os.Build
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import me.fakerqu.xposed.storageredirect.XposedServiceManager
import java.io.File

/**
 * 主页 + 框架信息的 UI 状态（不可变）
 */
data class HomeUiState(
    val isHooked: Boolean = false,
    val lspApiVersion: String = "—",
    val frameworkName: String = "—",
    val frameworkVersion: String = "—",
    val deviceModel: String = "",
    val androidVersion: String = "",
    val kernelVersion: String = "",
    val selinuxStatus: String = "",
)

/**
 * HomeScreen / MainScreen 共用的 ViewModel
 *
 * 负责：
 * - 监听 LSP 服务状态
 * - 同步已配置包名到 [AppListViewModel]
 * - 加载设备信息（型号、系统版本、内核、SELinux）
 */
class HomeViewModel(
    application: Application,
) : AndroidViewModel(application) {

    private val _uiState = MutableStateFlow(
        HomeUiState(
            deviceModel = "${Build.MANUFACTURER} ${Build.MODEL}",
            androidVersion = "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})",
        )
    )
    val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()

    init {
        observeXposedService()
        loadDeviceInfo()
    }

    private fun observeXposedService() {
        viewModelScope.launch {
            XposedServiceManager.service.collect { service ->
                if (service != null) {
                    _uiState.update {
                        it.copy(
                            isHooked = true,
                            lspApiVersion = "API ${service.apiVersion}",
                            frameworkName = service.frameworkName,
                            frameworkVersion = "${service.frameworkVersion} (${service.frameworkVersionCode})",
                        )
                    }
                } else {
                    _uiState.update { it.copy(isHooked = false) }
                }
            }
        }
    }

    private fun loadDeviceInfo() {
        viewModelScope.launch {
            val kernel = getKernelVersion()
            val selinux = getSelinuxStatus()
            _uiState.update {
                it.copy(
                    kernelVersion = kernel,
                    selinuxStatus = selinux,
                )
            }
        }
    }

    private fun getKernelVersion(): String {
        return runCatching {
            File("/proc/version").bufferedReader().use { it.readLine()?.trim() }
        }.getOrNull() ?: System.getProperty("os.version") ?: "未知"
    }

    private fun getSelinuxStatus(): String {
        return runCatching {
            ProcessBuilder("getenforce")
                .redirectErrorStream(true)
                .start()
                .inputStream
                .bufferedReader()
                .use { it.readLine()?.trim() }
        }.getOrNull() ?: "未知"
    }
}
