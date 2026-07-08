package me.fakerqu.xposed.storageredirect.ui

import android.graphics.drawable.Drawable
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.DropdownEntry
import top.yukonga.miuix.kmp.basic.DropdownItem
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.InfiniteProgressIndicator
import top.yukonga.miuix.kmp.basic.InputField
import top.yukonga.miuix.kmp.basic.ScrollBehavior
import top.yukonga.miuix.kmp.basic.SearchBar
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Contacts
import top.yukonga.miuix.kmp.icon.extended.Sort
import top.yukonga.miuix.kmp.menu.OverlayIconDropdownMenu
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 应用列表页面的共享状态
 *
 * TopBar 和内容通过此对象通信，避免各自定义独立状态
 */
class AppListState {
    var allApps by mutableStateOf<List<AppInfo>>(emptyList())
    var isLoading by mutableStateOf(true)
    var searchQuery by mutableStateOf("")

    var currentUserId by mutableIntStateOf(0)
    var users by mutableStateOf(listOf(UserInfo(0, "机主")))

    var sortMode by mutableStateOf(SortMode.NAME_ASC)
    var showSystemApps by mutableStateOf(true)
    var enabledFirst by mutableStateOf(false)

    /** LSP 配置中已存在的包名集合 */
    var configuredPackages by mutableStateOf<Set<String>>(emptySet())
}

/**
 * 应用列表的 TopAppBar（含用户切换 / 排序 / 筛选按钮）
 *
 * 由 HomeScreen 的 Scaffold topBar 槽调用
 */
@Composable
fun AppListTopBar(
    scrollBehavior: ScrollBehavior,
    state: AppListState,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val usersEntry = DropdownEntry(
        items = state.users.map { user ->
            DropdownItem(
                text = user.name,
                selected = user.id == state.currentUserId,
                onClick = {
                    state.currentUserId = user.id
                    scope.launch {
                        state.isLoading = true
                        state.allApps = withContext(Dispatchers.IO) {
                            loadInstalledApps(context, user.id)
                        }
                        state.isLoading = false
                    }
                },
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
                    onClick = { state.sortMode = mode },
                )
            }
        ),
        // 第二组：筛选选项（多选）
        DropdownEntry(
            items = listOf(
                DropdownItem(
                    text = "显示系统应用",
                    selected = state.showSystemApps,
                    onClick = { state.showSystemApps = !state.showSystemApps },
                ),
                DropdownItem(
                    text = "已配置优先",
                    selected = state.enabledFirst,
                    onClick = { state.enabledFirst = !state.enabledFirst },
                ),
            )
        ),
    )

    TopAppBar(
        title = "应用列表",
        largeTitle = "应用列表",
        scrollBehavior = scrollBehavior,
        actions = {
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
 * @param scrollBehavior 来自 HomeScreen 的滚动行为
 * @param state          共享状态，与 [AppListTopBar] 使用同一实例
 */
@Composable
fun AppListScreen(
    scrollBehavior: ScrollBehavior,
    state: AppListState,
) {
    val context = LocalContext.current

    // 列表滚动状态
    val listState = rememberLazyListState()

    // 排序 / 筛选变更后滚动到顶部
    LaunchedEffect(state.sortMode, state.showSystemApps, state.enabledFirst) {
        if (!listState.isScrollInProgress) {
            listState.scrollToItem(0)
        }
    }

    // ---- 加载数据 ----
    LaunchedEffect(Unit) {
        state.users = withContext(Dispatchers.IO) { loadUsers(context) }
        state.allApps =
            withContext(Dispatchers.IO) { loadInstalledApps(context, state.currentUserId) }
        state.isLoading = false
    }

    // ---- 过滤 + 排序 ----
    val filteredApps by remember(
        state.allApps, state.searchQuery, state.sortMode,
        state.showSystemApps, state.enabledFirst,
    ) {
        derivedStateOf {
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
                SortMode.NAME_ASC -> result.sortedBy { it.label }
                SortMode.INSTALL_TIME -> result.sortedByDescending { it.firstInstallTime }
            }

            // 已配置优先（在 LSP 中存在配置的应用排前面）
            if (state.enabledFirst) {
                val configured = state.configuredPackages
                result = result.sortedByDescending { it.packageName in configured }
            }

            result.toList()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .nestedScroll(scrollBehavior.nestedScrollConnection)
            .padding(horizontal = 12.dp),
    ) {
        SearchBar(
            inputField = {
                InputField(
                    query = state.searchQuery,
                    onQueryChange = { state.searchQuery = it },
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
            apps = filteredApps,
            configuredPackages = state.configuredPackages,
            listState = listState,
            modifier = Modifier.fillMaxSize(),
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

// ========== 子组件 ==========

@Composable
private fun AppList(
    apps: List<AppInfo>,
    configuredPackages: Set<String>,
    listState: androidx.compose.foundation.lazy.LazyListState,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        state = listState,
        modifier = modifier,
        contentPadding = PaddingValues(bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        items(items = apps, key = { it.packageName }) { app ->
            AppItem(app = app, isConfigured = app.packageName in configuredPackages)
        }
    }
}

@Composable
private fun AppItem(app: AppInfo, isConfigured: Boolean = false) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
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
