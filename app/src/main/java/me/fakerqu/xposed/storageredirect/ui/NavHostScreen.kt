package me.fakerqu.xposed.storageredirect.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.lifecycle.viewmodel.navigation3.rememberViewModelStoreNavEntryDecorator
import androidx.navigation3.runtime.NavEntry
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.ui.NavDisplay
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf
import top.yukonga.miuix.kmp.basic.FloatingNavigationBar
import top.yukonga.miuix.kmp.basic.FloatingNavigationBarItem
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Home
import top.yukonga.miuix.kmp.icon.extended.ListView

/**
 * 应用入口页面（纯导航承载）
 *
 * 使用 Navigation 3 的 [NavDisplay] 管理页面栈：
 * - [MainKey] / [AppListKey] 为顶层 tab，通过底部 FloatingNavigationBar 切换
 * - [AppDetailKey] 为应用详情页，从应用列表 push
 * - [DirectoryPickerKey] 为目录选择器，从应用详情页 push
 *
 */
@Composable
fun NavHostScreen() {
    val backStack = remember { mutableStateListOf<Any>(MainKey) }

    // 目录选择器返回的路径，传给 AppDetailScreen 草稿
    var pendingDirPath by remember { mutableStateOf<String?>(null) }

    val tabs = listOf(
        TabItem("主页", MiuixIcons.Home),
        TabItem("应用", MiuixIcons.ListView),
    )

    // 判断当前是否在顶层 tab
    val currentKey = backStack.lastOrNull()
    val isTopLevel = currentKey is MainKey || currentKey is AppListKey
    val selectedTab = if (currentKey is AppListKey) 1 else 0

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        bottomBar = {
            if (isTopLevel) {
                FloatingNavigationBar {
                    tabs.forEachIndexed { index, item ->
                        FloatingNavigationBarItem(
                            selected = selectedTab == index,
                            onClick = {
                                val targetKey = if (index == 0) MainKey else AppListKey
                                // 保留底层 tab，避免销毁 ViewModel：
                                // 先移除目标 key 上方的所有页面，再将目标移到栈顶
                                backStack.removeAll { it is AppDetailKey || it is DirectoryPickerKey }
                                if (backStack.lastOrNull() != targetKey) {
                                    backStack.remove(targetKey)
                                    backStack.add(targetKey)
                                }
                            },
                            icon = item.icon,
                            label = item.label,
                        )
                    }
                }
            }
        },
    ) { paddingValues ->
        val bottomPadding = paddingValues.calculateBottomPadding()
        NavDisplay(
            backStack = backStack,
            modifier = Modifier.fillMaxSize(),
            onBack = { backStack.removeLastOrNull() },
            entryDecorators = listOf(
                rememberSaveableStateHolderNavEntryDecorator(),
                rememberViewModelStoreNavEntryDecorator(),
            ),
            entryProvider = { key ->
                when (key) {
                    is MainKey -> NavEntry(key) {
                        HomePage(bottomBarPadding = bottomPadding)
                    }

                    is AppListKey -> NavEntry(key) {
                        AppListPage(
                            onAppClick = { packageName ->
                                backStack.add(AppDetailKey(packageName))
                            },
                            bottomBarPadding = bottomPadding,
                        )
                    }

                    is AppDetailKey -> NavEntry(key) {
                        val viewModel = koinViewModel<AppDetailViewModel> {
                            parametersOf(key.packageName)
                        }
                        AppDetailScreen(
                            viewModel = viewModel,
                            onBack = { backStack.removeLastOrNull() },
                            onAddDirectory = {
                                backStack.add(DirectoryPickerKey(key.packageName))
                            },
                            pendingDirPath = pendingDirPath,
                            onPendingPathConsumed = { pendingDirPath = null },
                        )
                    }

                    is DirectoryPickerKey -> NavEntry(key) {
                        DirectoryPickerScreen(
                            onPathSelected = { relativePath ->
                                pendingDirPath = relativePath
                                backStack.removeLastOrNull()
                            },
                            onBack = { backStack.removeLastOrNull() },
                        )
                    }

                    else -> NavEntry(Unit) { }
                }
            },
        )
    }
}

private data class TabItem(
    val label: String,
    val icon: ImageVector,
)
