package me.fakerqu.xposed.storageredirect.ui

import android.app.Application
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import me.fakerqu.xposed.storageredirect.XposedServiceManager
import me.fakerqu.xposed.storageredirect.config.ConfigManager
import me.fakerqu.xposed.storageredirect.model.AppInfo
import me.fakerqu.xposed.storageredirect.model.UserInfo

/**
 * 应用列表的 UI 状态（不可变）
 */
data class AppListUiState(
    val allApps: List<AppInfo> = emptyList(),
    val isLoading: Boolean = true,
    val searchQuery: String = "",
    val currentUserId: Int = 0,
    val users: List<UserInfo> = listOf(UserInfo(0, "机主")),
    val sortMode: SortMode = SortMode.NAME_ASC,
    val showSystemApps: Boolean = true,
    val enabledFirst: Boolean = false,
    val configuredPackages: Set<String> = emptySet(),
    val configEnabledPackages: Set<String> = emptySet(),
)

/**
 * 应用列表的 ViewModel
 *
 * 负责数据加载、过滤排序等逻辑，Composable 通过 [uiState] 读取状态，
 * 通过事件方法（refresh / switchUser / ...）发出意图。
 */
class AppListViewModel(
    application: Application,
) : AndroidViewModel(application) {

    private val _uiState = MutableStateFlow(AppListUiState())
    val uiState: StateFlow<AppListUiState> = _uiState.asStateFlow()

    init {
        refresh()
    }

    /**
     * 监听 LSP 服务状态，连接后自动同步已配置包名
     */
    private fun observeXposedService() {
        viewModelScope.launch(Dispatchers.IO) {
            XposedServiceManager.service.collect { service ->
                if (service != null) {
                    val packages = ConfigManager.getConfiguredPackageNames()
                    val configEnabledPackages = ConfigManager.getConfigEnabledPackageNames()
                    _uiState.update {
                        it.copy(
                            configuredPackages = packages,
                            configEnabledPackages = configEnabledPackages
                        )
                    }
                }
            }
        }
    }

    fun refresh() {
        loadUsers()
        loadApps()
        observeXposedService()
    }

    fun switchUser(userId: Int) {
        _uiState.update { it.copy(currentUserId = userId, isLoading = true) }
        loadApps()
    }

    fun updateSearchQuery(query: String) {
        _uiState.update { it.copy(searchQuery = query) }
    }

    fun updateSortMode(mode: SortMode) {
        _uiState.update { it.copy(sortMode = mode) }
    }

    fun toggleShowSystemApps() {
        _uiState.update { it.copy(showSystemApps = !it.showSystemApps) }
    }

    fun toggleEnabledFirst() {
        _uiState.update { it.copy(enabledFirst = !it.enabledFirst) }
    }

    /**
     * 获取设备上的用户列表
     *
     * 在主用户中通常只有 userId=0（机主）。
     * 如果存在多用户/工作资料（Work Profile），还会出现 10、11 等。
     */
    private fun loadUsers() {
        viewModelScope.launch(Dispatchers.IO) {
            // 由于本应用运行在宿主进程，无法直接通过 createPackageContextAsUser
            // 获取其他用户空间的应用列表。此处仅展示当前用户（userId=0）的应用列表。
            // 如需多用户支持，需要在 Xposed 模块侧通过远程服务获取。
            val users = listOf(UserInfo(id = 0, name = "机主"))
            _uiState.update { it.copy(users = users) }
        }
    }

    /**
     * 加载已安装应用列表（指定用户）
     */
    private fun loadApps() {
        _uiState.update { it.copy(isLoading = true) }
        viewModelScope.launch(Dispatchers.IO) {
            val apps = getInstalledApps(_uiState.value.currentUserId)
            _uiState.update { it.copy(allApps = apps, isLoading = false) }
        }
    }

    /**
     * 从 PackageManager 查询已安装应用列表
     *
     * 使用 [PackageManager.getInstalledPackages] 一次性获取包含 firstInstallTime 的 PackageInfo 列表，
     * 避免对每个应用单独调用 getPackageInfo 造成 N+1 次 IPC 开销。
     */
    private fun getInstalledApps(userId: Int): List<AppInfo> {
        val pm = getApplication<Application>().packageManager
        val flags = PackageManager.GET_META_DATA
        // 单次 IPC 调用，返回的 PackageInfo 已包含 firstInstallTime 和 applicationInfo
        return pm.getInstalledPackages(flags).mapNotNull { pkgInfo ->
            val appInfo = pkgInfo.applicationInfo ?: return@mapNotNull null
            AppInfo(
                packageName = appInfo.packageName,
                label = pm.getApplicationLabel(appInfo).toString(),
                isEnabled = appInfo.enabled,
                isSystem = (appInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0,
                uid = appInfo.uid,
                userId = userId,
                icon = runCatching { pm.getApplicationIcon(appInfo) }.getOrNull(),
                firstInstallTime = pkgInfo.firstInstallTime,
            )
        }
    }
}
