package me.fakerqu.xposed.storageredirect.ui

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.HorizontalDivider
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.InfiniteProgressIndicator
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SmallTopAppBar
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.rememberTopAppBarState
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.basic.ArrowRight
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 目录选择器页面
 *
 * 纯展示组件，所有状态来自 [viewModel]，交互通过回调上抛。
 *
 * @param onPathSelected 回调，返回相对于 /sdcard/ 的路径（如 "Pictures/MyApp"）
 * @param onBack         返回上一页
 */
@Composable
fun DirectoryPickerScreen(
    onPathSelected: (String) -> Unit,
    onBack: () -> Unit,
) {
    val viewModel: DirectoryPickerViewModel = viewModel()
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scrollBehavior = MiuixScrollBehavior(rememberTopAppBarState())

    // 从设置页面返回后重新检查权限
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME && state.permissionChecked) {
                viewModel.checkPermission()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = {
            SmallTopAppBar(
                title = "选择目录",
                navigationIcon = {
                    IconButton(
                        onClick = onBack,
                        modifier = Modifier.size(28.dp),
                    ) {
                        androidx.compose.foundation.Image(
                            imageVector = MiuixIcons.Basic.ArrowRight,
                            contentDescription = "返回",
                            modifier = Modifier.graphicsLayer { scaleX = -1f },
                        )
                    }
                },
                scrollBehavior = scrollBehavior,
            )
        },
        bottomBar = {
            if (state.hasPermission) {
                // 底部确认按钮
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                ) {
                    TextButton(
                        text = if (state.isCurrentBlocked) "Android 目录不可选" else "选择: ${state.displayPath}",
                        onClick = {
                            if (!state.isCurrentBlocked) {
                                onPathSelected(state.relativePath)
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !state.isCurrentBlocked,
                        colors = ButtonDefaults.textButtonColorsPrimary(),
                    )
                    Spacer(Modifier.height(4.dp))
                }
            }
        },
    ) { paddingValues ->
        if (!state.hasPermission) {
            // 无权限提示界面
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
                    .padding(horizontal = 24.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = "缺少必要权限",
                    fontSize = MiuixTheme.textStyles.title1.fontSize,
                    color = MiuixTheme.colorScheme.onBackground,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "需要\"所有文件访问权限\"才能浏览和选择目录，请点击下方按钮前往设置页面手动授权。",
                    fontSize = MiuixTheme.textStyles.body1.fontSize,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(24.dp))
                TextButton(
                    text = "前往设置授权",
                    onClick = {
                        val intent = Intent(
                            Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                            "package:${context.packageName}".toUri(),
                        )
                        context.startActivity(intent)
                    },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.textButtonColorsPrimary(),
                )
            }
        } else {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(top = paddingValues.calculateTopPadding()),
            ) {
                // 面包屑
                BreadcrumbBar(
                    currentPath = state.currentPath,
                    onNavigate = viewModel::navigateTo,
                )

                if (state.loading) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        horizontalArrangement = Arrangement.Center,
                    ) {
                        InfiniteProgressIndicator(
                            modifier = Modifier.size(24.dp),
                        )
                    }
                } else if (state.subDirs.isEmpty()) {
                    Card(modifier = Modifier.fillMaxWidth().padding(12.dp)) {
                        BasicComponent(
                            title = "无子文件夹",
                            summary = "可以选择当前目录",
                            enabled = false,
                        )
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(
                            horizontal = 12.dp,
                            vertical = 4.dp,
                        ),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        items(items = state.subDirs, key = { it.absolutePath }) { entry ->
                            DirRow(
                                entry = entry,
                                onClick = {
                                    if (!entry.isBlocked) {
                                        viewModel.navigateTo(entry.absolutePath)
                                    }
                                },
                            )
                            HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun BreadcrumbBar(
    currentPath: String,
    onNavigate: (String) -> Unit,
) {
    val segments = remember(currentPath) {
        val parts = currentPath.removePrefix("/").split("/")
        val result = mutableListOf<Pair<String, String>>()
        var acc = ""
        parts.forEachIndexed { index, part ->
            acc = if (index == 0) "/$part" else "$acc/$part"
            result.add(part to acc)
        }
        result
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        segments.forEachIndexed { index, (label, path) ->
            if (index > 0) {
                Text(
                    text = " / ",
                    fontSize = MiuixTheme.textStyles.body2.fontSize,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
            }
            Text(
                text = label,
                fontSize = MiuixTheme.textStyles.body2.fontSize,
                color = if (index == segments.lastIndex) {
                    MiuixTheme.colorScheme.primary
                } else {
                    MiuixTheme.colorScheme.onSurfaceVariantActions
                },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.clickable {
                    if (index < segments.lastIndex) {
                        onNavigate(path)
                    }
                },
            )
        }
    }
}

@Composable
private fun DirRow(
    entry: DirEntryItem,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = !entry.isBlocked, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = entry.name,
            fontSize = MiuixTheme.textStyles.headline1.fontSize,
            color = if (entry.isBlocked) {
                MiuixTheme.colorScheme.onSurfaceVariantSummary
            } else {
                MiuixTheme.colorScheme.onBackground
            },
            modifier = Modifier.weight(1f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (entry.isBlocked) {
            Text(
                text = "不可选",
                fontSize = MiuixTheme.textStyles.body2.fontSize,
                color = Color(0xFFFF3B30),
            )
        } else {
            androidx.compose.foundation.Image(
                imageVector = MiuixIcons.Basic.ArrowRight,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
            )
        }
    }
}
