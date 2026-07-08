package me.fakerqu.xposed.storageredirect.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import kotlinx.coroutines.flow.collectLatest
import me.fakerqu.xposed.storageredirect.XposedServiceManager
import me.fakerqu.xposed.storageredirect.config.ConfigManager
import top.yukonga.miuix.kmp.basic.FloatingNavigationBar
import top.yukonga.miuix.kmp.basic.FloatingNavigationBarItem
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.basic.rememberTopAppBarState
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Home
import top.yukonga.miuix.kmp.icon.extended.ListView

/**
 * 应用入口页面
 *
 * 统一的 Scaffold + TopAppBar，通过底部 FloatingNavigationBar 在页面间切换。
 * LSP 状态在此持有，避免页面切换时丢失。
 */
@Composable
fun HomeScreen() {
    var currentTab by rememberSaveable { mutableIntStateOf(0) }
    val scrollBehavior = MiuixScrollBehavior(state = rememberTopAppBarState())

    // ---- AppList 共享状态 ----
    val appListState = remember { AppListState() }

    // ---- LSP 状态（提升到 HomeScreen，避免页面切换丢失）----
    var isHooked by remember { mutableStateOf(false) }
    var lspApiVersion by remember { mutableStateOf("—") }
    var frameworkName by remember { mutableStateOf("—") }
    var frameworkVersion by remember { mutableStateOf("—") }

    LaunchedEffect(Unit) {
        XposedServiceManager.service.collectLatest { service ->
            if (service != null) {
                isHooked = true
                lspApiVersion = "API ${service.apiVersion}"
                frameworkName = service.frameworkName
                frameworkVersion = "${service.frameworkVersion} (${service.frameworkVersionCode})"

                // 通过 ConfigManager 读取已配置的包名列表
                runCatching {
                    appListState.configuredPackages = ConfigManager.getConfiguredPackageNames()
                }
            } else {
                isHooked = false
            }
        }
    }

    val tabs = listOf(
        TabItem("主页", MiuixIcons.Home),
        TabItem("应用", MiuixIcons.ListView),
    )

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = {
            when (currentTab) {
                0 -> TopAppBar(
                    title = "Storage Redirect",
                    largeTitle = "Storage Redirect",
                    scrollBehavior = scrollBehavior,
                )

                1 -> AppListTopBar(
                    scrollBehavior = scrollBehavior,
                    state = appListState,
                )
            }
        },
        bottomBar = {
            FloatingNavigationBar {
                tabs.forEachIndexed { index, item ->
                    FloatingNavigationBarItem(
                        selected = currentTab == index,
                        onClick = { currentTab = index },
                        icon = item.icon,
                        label = item.label,
                    )
                }
            }
        },
    ) { paddingValues ->
        AnimatedContent(
            targetState = currentTab,
            transitionSpec = {
                fadeIn(tween(200)).togetherWith(fadeOut(tween(200)))
            },
            label = "tabTransition",
            modifier = Modifier.padding(top = paddingValues.calculateTopPadding()),
        ) { tabIndex ->
            when (tabIndex) {
                0 -> MainScreen(
                    scrollBehavior = scrollBehavior,
                    isHooked = isHooked,
                    lspApiVersion = lspApiVersion,
                    frameworkName = frameworkName,
                    frameworkVersion = frameworkVersion,
                )

                1 -> AppListScreen(
                    scrollBehavior = scrollBehavior,
                    state = appListState,
                )
            }
        }
    }
}

private data class TabItem(
    val label: String,
    val icon: ImageVector,
)
