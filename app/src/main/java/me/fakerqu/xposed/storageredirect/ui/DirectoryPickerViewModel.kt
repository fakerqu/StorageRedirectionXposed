package me.fakerqu.xposed.storageredirect.ui

import android.app.Application
import android.os.Environment
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

private const val BASE_PATH = "/sdcard"
private const val BLOCKED_PATH = "/sdcard/Android"

/**
 * 目录选择器的 UI 状态（不可变）
 */
data class DirectoryPickerUiState(
    val currentPath: String = BASE_PATH,
    val subDirs: List<DirEntryItem> = emptyList(),
    val loading: Boolean = true,
    val hasPermission: Boolean = false,
    val permissionChecked: Boolean = false,
) {
    val relativePath: String
        get() = if (currentPath == BASE_PATH) ""
        else currentPath.removePrefix("$BASE_PATH/")

    val displayPath: String
        get() = if (currentPath == BASE_PATH) "/sdcard" else currentPath

    val isCurrentBlocked: Boolean
        get() = currentPath == BLOCKED_PATH || currentPath.startsWith("$BLOCKED_PATH/")
}

/** 对外暴露的目录条目（隐藏内部实现） */
data class DirEntryItem(
    val name: String,
    val absolutePath: String,
    val isBlocked: Boolean,
)

/**
 * 目录选择器 ViewModel
 *
 * 负责：
 * - 检查 / 刷新 MANAGE_EXTERNAL_STORAGE 权限
 * - 加载当前路径下的子目录列表
 */
class DirectoryPickerViewModel(
    application: Application,
) : AndroidViewModel(application) {

    private val _uiState = MutableStateFlow(DirectoryPickerUiState())
    val uiState: StateFlow<DirectoryPickerUiState> = _uiState.asStateFlow()

    init {
        checkPermission()
    }

    fun checkPermission() {
        val granted = Environment.isExternalStorageManager()
        _uiState.update {
            it.copy(hasPermission = granted, permissionChecked = true)
        }
        if (granted) loadDirs()
    }

    fun navigateTo(path: String) {
        _uiState.update { it.copy(currentPath = path, loading = true) }
        loadDirs()
    }

    private fun loadDirs() {
        val currentPath = _uiState.value.currentPath
        viewModelScope.launch {
            val dirs = withContext(Dispatchers.IO) {
                val dir = File(currentPath)
                dir.listFiles()
                    ?.filter { it.isDirectory }
                    ?.sortedBy { it.name.lowercase() }
                    ?.map { file ->
                        DirEntryItem(
                            name = file.name,
                            absolutePath = file.absolutePath,
                            isBlocked = file.absolutePath == BLOCKED_PATH ||
                                file.absolutePath.startsWith("$BLOCKED_PATH/"),
                        )
                    }
                    ?: emptyList()
            }
            _uiState.update { it.copy(subDirs = dirs, loading = false) }
        }
    }
}
