package me.fakerqu.xposed.storageredirect.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import me.fakerqu.xposed.storageredirect.config.ConfigManager
import me.fakerqu.xposed.storageredirect.config.model.DirConfig
import me.fakerqu.xposed.storageredirect.config.model.DirMode
import me.fakerqu.xposed.storageredirect.config.model.PackageConfig

/**
 * 单应用配置详情的 UI 状态（不可变）
 */
data class AppDetailUiState(
    val appLabel: String = "",
    val draftEnabled: Boolean = false,
    val draftDirConfigs: List<DirConfig> = emptyList(),
    val savedEnabled: Boolean = false,
    val savedDirConfigs: List<DirConfig> = emptyList(),
    val hasSavedConfig: Boolean = false,
) {
    val enabledChanged: Boolean get() = draftEnabled != savedEnabled
    val dirsChanged: Boolean
        get() = draftDirConfigs.size != savedDirConfigs.size ||
            draftDirConfigs.any { draft ->
                val saved = savedDirConfigs.find { it.relativePath == draft.relativePath }
                saved == null || saved.enabled != draft.enabled || saved.mode != draft.mode
            }
    val hasAnyUnsaved: Boolean get() = enabledChanged || dirsChanged

    fun isDirChanged(draft: DirConfig): Boolean {
        val saved = savedDirConfigs.find { it.relativePath == draft.relativePath }
        return saved == null || saved.enabled != draft.enabled || saved.mode != draft.mode
    }
}

/**
 * 应用详情页 ViewModel（草稿模式）
 *
 * 从远程配置加载已保存的配置作为草稿基线，所有修改在草稿中进行，
 * [save] 时一次性写入远程配置。
 */
class AppDetailViewModel(
    application: Application,
) : AndroidViewModel(application) {

    private val _uiState = MutableStateFlow(AppDetailUiState())
    val uiState: StateFlow<AppDetailUiState> = _uiState.asStateFlow()

    fun init(packageName: String) {
        // 加载 app label
        viewModelScope.launch {
            val label = runCatching {
                val pm = getApplication<Application>().packageManager
                pm.getApplicationLabel(pm.getApplicationInfo(packageName, 0)).toString()
            }.getOrDefault(packageName)

            val savedConfig = ConfigManager.getPackageConfig(packageName)
            _uiState.update {
                it.copy(
                    appLabel = label,
                    draftEnabled = savedConfig?.enabled ?: false,
                    draftDirConfigs = savedConfig?.dirConfigs ?: emptyList(),
                    savedEnabled = savedConfig?.enabled ?: false,
                    savedDirConfigs = savedConfig?.dirConfigs ?: emptyList(),
                    hasSavedConfig = savedConfig != null,
                )
            }
        }
    }

    fun setEnabled(enabled: Boolean) {
        _uiState.update { it.copy(draftEnabled = enabled) }
    }

    fun addDirConfig(relativePath: String) {
        _uiState.update { state ->
            val exists = state.draftDirConfigs.any { it.relativePath == relativePath }
            if (exists) state
            else state.copy(
                draftDirConfigs = state.draftDirConfigs + DirConfig(
                    enabled = true,
                    relativePath = relativePath,
                    mode = DirMode.WRITE,
                )
            )
        }
    }

    fun updateDirMode(index: Int, mode: DirMode) {
        _uiState.update { state ->
            val updated = state.draftDirConfigs.toMutableList()
            if (index in updated.indices) {
                updated[index] = updated[index].copy(mode = mode)
            }
            state.copy(draftDirConfigs = updated)
        }
    }

    fun removeDirConfig(index: Int) {
        _uiState.update { state ->
            val updated = state.draftDirConfigs.toMutableList()
            if (index in updated.indices) {
                updated.removeAt(index)
            }
            state.copy(draftDirConfigs = updated)
        }
    }

    /**
     * 保存草稿到远程配置。
     * @param packageName 目标应用包名
     * @param onSuccess 保存成功回调
     */
    fun save(packageName: String, onSuccess: () -> Unit) {
        val state = _uiState.value
        viewModelScope.launch {
            ConfigManager.upsertPackageConfig(
                PackageConfig(
                    packageName = packageName,
                    enabled = state.draftEnabled,
                    dirConfigs = state.draftDirConfigs,
                ),
            )
            // 同步 saved 基线
            _uiState.update {
                it.copy(
                    savedEnabled = state.draftEnabled,
                    savedDirConfigs = state.draftDirConfigs,
                    hasSavedConfig = true,
                )
            }
            onSuccess()
        }
    }
}
