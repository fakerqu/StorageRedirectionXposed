package me.fakerqu.xposed.storageredirect.ui

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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.koin.compose.viewmodel.koinViewModel
import me.fakerqu.xposed.storageredirect.config.model.DirConfig
import me.fakerqu.xposed.storageredirect.config.model.DirMode
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.HorizontalDivider
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.basic.rememberTopAppBarState
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.basic.ArrowRight
import top.yukonga.miuix.kmp.icon.basic.Close
import top.yukonga.miuix.kmp.basic.DropdownArrowEndAction
import top.yukonga.miuix.kmp.basic.DropdownDefaults
import top.yukonga.miuix.kmp.basic.DropdownEntry
import top.yukonga.miuix.kmp.basic.DropdownItem
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.popup.WindowDropdownPopup
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 单 APP 配置详情页面（本地草稿模式）
 *
 * 所有修改保存在 [AppDetailViewModel] 的草稿中，点击「保存」后一次性写入远程配置。
 * 退出时有未保存改动会弹窗提示。
 *
 * @param onBack         返回上一页
 * @param onAddDirectory 跳转到目录选择器
 * @param pendingDirPath 从目录选择器返回的待添加路径（非空时自动添加到草稿）
 */
@Composable
fun AppDetailScreen(
    viewModel: AppDetailViewModel,
    onBack: () -> Unit,
    onAddDirectory: () -> Unit,
    pendingDirPath: String? = null,
    onPendingPathConsumed: () -> Unit = {},
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val scrollBehavior = MiuixScrollBehavior(rememberTopAppBarState())

    // 从目录选择器返回的路径自动添加到草稿（仅消费一次）
    LaunchedEffect(pendingDirPath) {
        if (pendingDirPath != null) {
            viewModel.addDirConfig(pendingDirPath)
            onPendingPathConsumed()
        }
    }

    // ---- 退出确认弹窗 ----
    var showExitDialog by remember { mutableStateOf(false) }

    fun handleBack() {
        if (state.hasAnyUnsaved) {
            showExitDialog = true
        } else {
            onBack()
        }
    }

    // 拦截系统返回键，与 TopAppBar 返回按钮保持一致
    BackHandler { handleBack() }

    OverlayDialog(
        show = showExitDialog,
        title = "未保存的更改",
        summary = "当前配置有未保存的更改，确定要退出吗？",
        onDismissRequest = { showExitDialog = false },
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
            horizontalArrangement = Arrangement.End,
        ) {
            TextButton(
                text = "取消",
                onClick = { showExitDialog = false },
                modifier = Modifier.padding(end = 8.dp).weight(1f),
                colors = ButtonDefaults.textButtonColorsPrimary(),
            )
            TextButton(
                text = "不保存退出",
                onClick = {
                    showExitDialog = false
                    onBack()
                },
                modifier = Modifier.weight(1f),
            )
        }
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = state.appLabel,
                largeTitle = state.appLabel,
                navigationIcon = {
                    IconButton(
                        onClick = { handleBack() },
                        modifier = Modifier.size(28.dp),
                    ) {
                        Image(
                            imageVector = MiuixIcons.Basic.ArrowRight,
                            contentDescription = "返回",
                            modifier = Modifier.graphicsLayer { scaleX = -1f },
                        )
                    }
                },
                actions = {
                    TextButton(
                        text = "保存",
                        onClick = {
                            viewModel.save { onBack() }
                        },
                        modifier = Modifier.padding(end = 8.dp),
                        colors = if (state.hasAnyUnsaved) {
                            ButtonDefaults.textButtonColorsPrimary()
                        } else {
                            ButtonDefaults.textButtonColors()
                        },
                    )
                },
                scrollBehavior = scrollBehavior,
            )
        },
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .nestedScroll(scrollBehavior.nestedScrollConnection)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 12.dp)
                .padding(top = paddingValues.calculateTopPadding()),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Spacer(Modifier.height(4.dp))

            // ========== 启用开关 ==========
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                SmallTitle(text = "应用配置")
                if (state.enabledChanged) {
                    UnsavedBadge()
                }
            }
            Card(modifier = Modifier.fillMaxWidth()) {
                SwitchPreference(
                    checked = state.draftEnabled,
                    onCheckedChange = viewModel::setEnabled,
                    title = "启用存储重定向",
                    summary = when {
                        !state.draftEnabled -> "已禁用"
                        state.draftDirConfigs.isEmpty() -> "已启用 · 所有目录默认 N 权限"
                        else -> "已启用"
                    },
                )
            }

            // ========== 目录规则列表 ==========
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                SmallTitle(text = "目录规则")
                if (state.dirsChanged) {
                    UnsavedBadge()
                }
            }
            if (state.draftDirConfigs.isEmpty()) {
                Card(modifier = Modifier.fillMaxWidth()) {
                    BasicComponent(
                        title = "暂无目录规则",
                        summary = if (state.draftEnabled) "所有目录默认 N 权限" else "点击下方按钮添加",
                        enabled = false,
                    )
                }
            } else {
                Card(modifier = Modifier.fillMaxWidth()) {
                    state.draftDirConfigs.forEachIndexed { index, dirConfig ->
                        if (index > 0) {
                            HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                        }
                        DirConfigItem(
                            dirConfig = dirConfig,
                            isChanged = state.isDirChanged(dirConfig),
                            onModeChange = { newMode ->
                                viewModel.updateDirMode(index, newMode)
                            },
                            onDelete = {
                                viewModel.removeDirConfig(index)
                            },
                        )
                    }
                }
            }

            // ========== 添加按钮 ==========
            TextButton(
                text = "添加目录规则",
                onClick = onAddDirectory,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.textButtonColorsPrimary(),
            )

            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun UnsavedBadge() {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(4.dp))
            .background(Color(0xFFFF9500).copy(alpha = 0.15f))
            .padding(horizontal = 6.dp, vertical = 2.dp),
    ) {
        Text(
            text = "未保存",
            color = Color(0xFFFF9500),
            fontSize = MiuixTheme.textStyles.body2.fontSize,
        )
    }
}

/**
 * 单条目录规则项（精简布局）
 *
 * - title:    目录路径
 * - summary:  当前权限模式 / 未保存状态
 * - 右侧:     权限模式下拉 + 删除按钮
 *
 * @param isChanged 该条规则与已保存配置相比是否有改动
 */
@Composable
private fun DirConfigItem(
    dirConfig: DirConfig,
    isChanged: Boolean,
    onModeChange: (DirMode) -> Unit,
    onDelete: () -> Unit,
) {
    val modes = listOf("读取 (R)", "写入 (W)", "无 (N)")
    val modeIndex = when (dirConfig.mode) {
        DirMode.READ -> 0
        DirMode.WRITE -> 1
        DirMode.NONE -> 2
    }

    var dropdownExpanded by remember { mutableStateOf(false) }

    val entry = remember(modes, modeIndex, onModeChange) {
        DropdownEntry(
            modes.mapIndexed { index, item ->
                DropdownItem(
                    text = item,
                    selected = index == modeIndex,
                    onClick = {
                        val newMode = when (index) {
                            0 -> DirMode.READ
                            1 -> DirMode.WRITE
                            else -> DirMode.NONE
                        }
                        onModeChange(newMode)
                    },
                )
            },
        )
    }

    BasicComponent(
        title = dirConfig.relativePath,
        endActions = {
            Text(
                text = modes[modeIndex],
                fontSize = MiuixTheme.textStyles.body2.fontSize,
                color = MiuixTheme.colorScheme.onSurfaceVariantActions,
                modifier = Modifier
                    .align(Alignment.CenterVertically)
                    .padding(end = 4.dp),
            )
            DropdownArrowEndAction(
                actionColor = MiuixTheme.colorScheme.onSurfaceVariantActions,
            )
            WindowDropdownPopup(
                entry = entry,
                show = dropdownExpanded,
                onDismiss = { dropdownExpanded = false },
                onDismissFinished = {},
                maxHeight = null,
                dropdownColors = DropdownDefaults.dropdownColors(),
            )
            IconButton(onClick = onDelete) {
                Image(
                    imageVector = MiuixIcons.Basic.Close,
                    contentDescription = "删除",
                )
            }
        },
        onClick = { dropdownExpanded = !dropdownExpanded },
        bottomAction = {
            if (isChanged) {
                UnsavedBadge()
            }
        },
    )
}
