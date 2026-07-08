package me.fakerqu.xposed.storageredirect.ui

import android.graphics.drawable.Drawable
import me.fakerqu.xposed.storageredirect.model.AppInfo
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import top.yukonga.miuix.kmp.basic.DropdownEntry
import top.yukonga.miuix.kmp.basic.DropdownItem
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.InfiniteProgressIndicator
import top.yukonga.miuix.kmp.basic.InputField
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.ScrollBehavior
import top.yukonga.miuix.kmp.basic.SearchBar
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Contacts
import top.yukonga.miuix.kmp.icon.extended.Refresh
import top.yukonga.miuix.kmp.icon.extended.Sort
import top.yukonga.miuix.kmp.menu.OverlayIconDropdownMenu
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.rememberTopAppBarState
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 应用列表完整页面（含 Scaffold / TopAppBar / ViewModel）
 *
 * 自包含页面，由 HomeScreen 的 NavEntry 调用。
 *
 * @param onAppClick 点击应用后跳转详情页
 * @param bottomBarPadding 底部导航栏高度
 */
@Composable
fun AppListPage(
    onAppClick: (String) -> Unit,
    bottomBarPadding: Dp = 0.dp,
) {
    val viewModel: AppListViewModel = viewModel()
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val scrollBehavior = MiuixScrollBehavior(rememberTopAppBarState())

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = {
            AppListTopBar(
                scrollBehavior = scrollBehavior,
                state = state,
                onRefresh = viewModel::refresh,
                onSwitchUser = viewModel::switchUser,
                onSortModeChange = viewModel::updateSortMode,
                onToggleShowSystemApps = viewModel::toggleShowSystemApps,
                onToggleEnabledFirst = viewModel::toggleEnabledFirst,
            )
        },
    ) { padding ->
        AppListScreen(
            modifier = Modifier.padding(top = padding.calculateTopPadding()),
            scrollBehavior = scrollBehavior,
            state = state,
            onSearchQueryChange = viewModel::updateSearchQuery,
            onAppClick = onAppClick,
            bottomBarPadding = bottomBarPadding,
        )
    }
}

/**
 * 应用列表的 TopAppBar（含刷新 / 用户切换 / 排序 / 筛选按钮）
 *
 * 由 [AppListPage] 的 Scaffold topBar 槽调用。
 * 所有交互通过回调上抛，不直接修改状态。
 */
@Composable
private fun AppListTopBar(
    scrollBehavior: ScrollBehavior,
    state: AppListUiState,
    onRefresh: () -> Unit,
    onSwitchUser: (Int) -> Unit,
    onSortModeChange: (SortMode) -> Unit,
    onToggleShowSystemApps: () -> Unit,
    onToggleEnabledFirst: () -> Unit,
) {
    val usersEntry = DropdownEntry(
        items = state.users.map { user ->
            DropdownItem(
                text = user.name,
                selected = user.id == state.currentUserId,
                onClick = { onSwitchUser(user.id) },
            )
        }
    )

    val sortEntries = listOf(
        // 第一组：排序方式（单选）
        DropdownEntry(
            items = SortMode.entries.map { mode ->
                DropdownItem(
                    text = mode.label,
                    selected = state.sortMode == mode,
                    onClick = { onSortModeChange(mode) },
                )
            }
        ),
        // 第二组：筛选选项（多选）
        DropdownEntry(
            items = listOf(
                DropdownItem(
                    text = "显示系统应用",
                    selected = state.showSystemApps,
                    onClick = onToggleShowSystemApps,
                ),
                DropdownItem(
                    text = "已配置优先",
                    selected = state.enabledFirst,
                    onClick = onToggleEnabledFirst,
                ),
            )
        ),
    )

    TopAppBar(
        title = "应用列表",
        largeTitle = "应用列表",
        scrollBehavior = scrollBehavior,
        actions = {
            IconButton(
                onClick = onRefresh,
                enabled = !state.isLoading,
            ) {
                Icon(
                    imageVector = MiuixIcons.Refresh,
                    contentDescription = "刷新",
                )
            }
            OverlayIconDropdownMenu(usersEntry) {
                Icon(
                    imageVector = MiuixIcons.Contacts,
                    contentDescription = "用户",
                )
            }
            OverlayIconDropdownMenu(sortEntries, collapseOnSelection = false) {
                Icon(
                    imageVector = MiuixIcons.Sort,
                    contentDescription = "排序",
                )
            }
        },
    )
}

/**
 * 应用列表页面内容（不含 Scaffold / TopAppBar）
 *
 * 纯展示组件，所有状态来自 [state]，所有交互通过回调上抛。
 */
@Composable
private fun AppListScreen(
    modifier: Modifier = Modifier,
    scrollBehavior: ScrollBehavior,
    state: AppListUiState,
    onSearchQueryChange: (String) -> Unit,
    onAppClick: (String) -> Unit = {},
    bottomBarPadding: Dp = 0.dp,
) {
    // 列表滚动状态
    val listState = rememberLazyListState()

    // 排序 / 筛选变更后滚动到顶部
    LaunchedEffect(state.sortMode, state.showSystemApps, state.enabledFirst) {
        if (!listState.isScrollInProgress) {
            listState.scrollToItem(0)
        }
    }

    // ---- 过滤 + 排序 ----
    val filteredApps = remember(
        state.allApps, state.searchQuery, state.sortMode,
        state.showSystemApps, state.enabledFirst, state.configuredPackages,
    ) {
        filterAndSortApps(state)
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .nestedScroll(scrollBehavior.nestedScrollConnection)
            .padding(horizontal = 12.dp),
    ) {
        SearchBar(
            inputField = {
                InputField(
                    query = state.searchQuery,
                    onQueryChange = onSearchQueryChange,
                    onSearch = { },
                    expanded = false,
                    onExpandedChange = { },
                    label = "搜索应用",
                )
            },
            modifier = Modifier.fillMaxWidth(),
            onExpandedChange = {},
        ) {}
        AppList(
            modifier = Modifier.fillMaxSize(),
            apps = filteredApps,
            configuredPackages = state.configuredPackages,
            listState = listState,
            onAppClick = onAppClick,
            bottomBarPadding = bottomBarPadding,
        )

        if (state.isLoading) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                horizontalArrangement = Arrangement.Center,
            ) {
                InfiniteProgressIndicator(modifier = Modifier.size(24.dp))
            }
        }
    }
}

/**
 * 过滤 + 排序逻辑（纯函数）
 */
private fun filterAndSortApps(state: AppListUiState): List<AppInfo> {
    var result = state.allApps.asSequence()

    if (!state.showSystemApps) {
        result = result.filter { !it.isSystem }
    }

    if (state.searchQuery.isNotBlank()) {
        val q = state.searchQuery.lowercase()
        result = result.filter {
            it.label.lowercase().contains(q) || it.packageName.lowercase().contains(q)
        }
    }

    // 排序（单选）
    result = when (state.sortMode) {
        SortMode.NAME_ASC -> {
            val collator = java.text.Collator.getInstance(java.util.Locale.CHINA)
            result.sortedWith { a, b -> collator.compare(a.label, b.label) }
        }
        SortMode.INSTALL_TIME -> result.sortedByDescending { it.firstInstallTime }
    }

    // 已配置优先（在 LSP 中存在配置的应用排前面）
    if (state.enabledFirst) {
        val configured = state.configuredPackages
        result = result.sortedByDescending { it.packageName in configured }
    }

    return result.toList()
}

// ========== 子组件 ==========

@Composable
private fun AppList(
    modifier: Modifier = Modifier,
    apps: List<AppInfo>,
    configuredPackages: Set<String>,
    listState: LazyListState,
    onAppClick: (String) -> Unit = {},
    bottomBarPadding: Dp = 0.dp,
) {
    LazyColumn(
        state = listState,
        modifier = modifier,
        contentPadding = PaddingValues(bottom = 32.dp + bottomBarPadding),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        items(items = apps, key = { it.packageName }) { app ->
            AppItem(
                app = app,
                isConfigured = app.packageName in configuredPackages,
                onClick = { onAppClick(app.packageName) },
            )
        }
    }
}

@Composable
private fun AppItem(
    app: AppInfo,
    isConfigured: Boolean = false,
    onClick: () -> Unit = {},
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 4.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AppIcon(drawable = app.icon, modifier = Modifier.size(44.dp))
        Spacer(Modifier.size(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = app.label,
                fontSize = MiuixTheme.textStyles.headline1.fontSize,
                fontWeight = FontWeight.Medium,
                color = if (app.isEnabled) MiuixTheme.colorScheme.onBackground else MiuixTheme.colorScheme.onBackgroundVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = app.packageName,
                fontSize = MiuixTheme.textStyles.body2.fontSize,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (isConfigured) {
            Spacer(Modifier.size(4.dp))
            StatusTag(text = "已配置", color = Color(0xFF007AFF))
        }
        if (app.isSystem) {
            Spacer(Modifier.size(4.dp))
            StatusTag(text = "系统", color = Color(0xFF8E8E93))
        }
        if (!app.isEnabled) {
            Spacer(Modifier.size(4.dp))
            StatusTag(text = "已禁用", color = Color(0xFFFF9500))
        }
    }
}

@Composable
private fun AppIcon(drawable: Drawable?, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier.clip(RoundedCornerShape(12.dp)),
        contentAlignment = Alignment.Center,
    ) {
        if (drawable != null) {
            val bitmap = remember(drawable) { drawable.toBitmap(108, 108).asImageBitmap() }
            Image(bitmap = bitmap, contentDescription = null, modifier = Modifier.fillMaxSize())
        } else {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MiuixTheme.colorScheme.secondaryContainer),
                contentAlignment = Alignment.Center,
            ) {
                Text(text = "?", color = MiuixTheme.colorScheme.onSecondaryContainer)
            }
        }
    }
}

@Composable
private fun StatusTag(text: String, color: Color) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(4.dp))
            .background(color.copy(alpha = 0.15f))
            .padding(horizontal = 6.dp, vertical = 2.dp),
    ) {
        Text(text = text, color = color, fontSize = MiuixTheme.textStyles.body2.fontSize)
    }
}
