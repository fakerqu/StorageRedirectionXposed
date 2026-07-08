package me.fakerqu.xposed.storageredirect.ui

import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.content.Context

/**
 * 已安装应用的轻量信息
 */
data class AppInfo(
    val packageName: String,
    val label: String,
    val isEnabled: Boolean,
    val isSystem: Boolean,
    val uid: Int,
    val userId: Int,
    val icon: Drawable?,
    val firstInstallTime: Long,
)

/**
 * 用户信息
 */
data class UserInfo(
    val id: Int,
    val name: String,
)

/**
 * 排序方式（单选）
 */
enum class SortMode(val label: String) {
    NAME_ASC("按名称排序"),
    INSTALL_TIME("按安装时间排序"),
}

/**
 * 加载已安装应用列表（指定用户）
 *
 * 注意：由于本应用运行在宿主进程，无法直接通过 createPackageContextAsUser
 * 获取其他用户空间的应用列表。此处仅展示当前用户（userId=0）的应用列表。
 * 如需多用户支持，需要在 Xposed 模块侧通过远程服务获取。
 */
fun loadInstalledApps(
    context: Context,
    userId: Int = 0,
): List<AppInfo> {
    val pm = context.packageManager
    val flags = PackageManager.GET_META_DATA
    return pm.getInstalledApplications(flags).map { appInfo ->
        AppInfo(
            packageName = appInfo.packageName,
            label = pm.getApplicationLabel(appInfo).toString(),
            isEnabled = appInfo.enabled,
            isSystem = (appInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0,
            uid = appInfo.uid,
            userId = userId,
            icon = runCatching { pm.getApplicationIcon(appInfo) }.getOrNull(),
            firstInstallTime = runCatching {
                pm.getPackageInfo(appInfo.packageName, 0).firstInstallTime
            }.getOrDefault(0L),
        )
    }
}

/**
 * 获取设备上的用户列表
 *
 * 在主用户中通常只有 userId=0（机主）。
 * 如果存在多用户/工作资料（Work Profile），还会出现 10、11 等。
 */
fun loadUsers(@Suppress("UNUSED_PARAMETER") context: Context): List<UserInfo> {
    return listOf(UserInfo(id = 0, name = "机主"))
}
