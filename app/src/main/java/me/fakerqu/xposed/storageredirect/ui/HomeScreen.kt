package me.fakerqu.xposed.storageredirect.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.koin.compose.viewmodel.koinViewModel
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.HorizontalDivider
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.ScrollBehavior
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.basic.rememberTopAppBarState
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.basic.Check
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 主页完整页面（含 Scaffold / TopAppBar / ViewModel）
 *
 * 自包含页面，由 NavHostScreen 的 NavEntry 调用。
 *
 * @param bottomBarPadding 底部导航栏高度
 */
@Composable
fun HomePage(
    viewModel: HomeViewModel = koinViewModel(),
    bottomBarPadding: Dp = 0.dp,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val scrollBehavior = MiuixScrollBehavior(rememberTopAppBarState())

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = "Storage Redirect",
                largeTitle = "Storage Redirect",
                scrollBehavior = scrollBehavior,
            )
        },
    ) { padding ->
        HomeScreen(
            modifier = Modifier.padding(top = padding.calculateTopPadding()),
            scrollBehavior = scrollBehavior,
            state = state,
            bottomBarPadding = bottomBarPadding,
        )
    }
}

/**
 * 主页内容（不含 Scaffold / TopAppBar）
 *
 * 纯展示组件，所有数据来自 [state]。
 */
@Composable
private fun HomeScreen(
    modifier: Modifier = Modifier,
    scrollBehavior: ScrollBehavior,
    state: HomeUiState,
    bottomBarPadding: Dp = 0.dp,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .nestedScroll(scrollBehavior.nestedScrollConnection)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Spacer(Modifier.height(4.dp))

        // ========== 状态卡片 ==========
        StatusCard(
            isHooked = state.isHooked,
            needsRestart = state.needsRestart,
            frameworkName = state.frameworkName,
            frameworkVersion = state.frameworkVersion,
        )

        // ========== Hook 详情 ==========
        SmallTitle(text = "框架信息")
        Card(modifier = Modifier.fillMaxWidth()) {
            InfoComponent(title = "运行状态", summary = if (state.isHooked) "已激活" else "未激活")
            HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
            InfoComponent(title = "框架名称", summary = state.frameworkName)
            HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
            InfoComponent(title = "框架版本", summary = state.frameworkVersion)
            HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
            InfoComponent(title = "LSP API 版本", summary = state.lspApiVersion)
        }

        // ========== 设备信息 ==========
        SmallTitle(text = "设备信息")
        Card(modifier = Modifier.fillMaxWidth()) {
            InfoComponent(title = "设备型号", summary = state.deviceModel)
            HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
            InfoComponent(title = "系统版本", summary = state.androidVersion)
            HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
            InfoComponent(title = "内核版本", summary = state.kernelVersion)
            HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
            InfoComponent(title = "SELinux", summary = state.selinuxStatus)
        }

        Spacer(Modifier.height(16.dp + bottomBarPadding))
    }
}

@Composable
private fun StatusCard(
    isHooked: Boolean,
    needsRestart: Boolean,
    frameworkName: String,
    frameworkVersion: String,
) {
    val bgColor = when {
        !isHooked -> Color(0xFF8E8E93)
        needsRestart -> Color(0xFFFF9500)
        else -> Color(0xFF1B8F4C)
    }
    val dotColor = when {
        !isHooked -> Color(0xFFFF3B30)
        needsRestart -> Color(0xFFFFCC02)
        else -> Color(0xFF4CD964)
    }
    val statusText = when {
        !isHooked -> "模块未激活"
        needsRestart -> "需要重启目标应用/热重载"
        else -> "模块已激活"
    }
    val summaryText = when {
        !isHooked -> "请通过 LSPosed 启用本模块"
        needsRestart -> "$frameworkName $frameworkVersion — 目标进程需要重启"
        else -> "$frameworkName $frameworkVersion"
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = top.yukonga.miuix.kmp.basic.CardColors(
            color = bgColor,
            contentColor = Color.White,
        ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .background(Color.White.copy(alpha = 0.2f)),
                contentAlignment = Alignment.Center,
            ) {
                Image(
                    imageVector = MiuixIcons.Basic.Check,
                    contentDescription = null,
                    colorFilter = ColorFilter.tint(Color.White),
                    modifier = Modifier.size(28.dp),
                )
            }

            Spacer(Modifier.size(16.dp))

            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(dotColor),
                    )
                    Spacer(Modifier.size(6.dp))
                    Text(
                        text = statusText,
                        color = Color.White,
                        fontSize = MiuixTheme.textStyles.title2.fontSize,
                        fontWeight = FontWeight.Bold,
                    )
                }
                Spacer(Modifier.size(4.dp))
                Text(
                    text = summaryText,
                    color = Color.White.copy(alpha = 0.8f),
                    fontSize = MiuixTheme.textStyles.body2.fontSize,
                )
            }
        }
    }
}

@Composable
private fun InfoComponent(title: String, summary: String) {
    BasicComponent(
        title = title,
        summary = summary,
        modifier = Modifier.fillMaxWidth(),
    )
}
