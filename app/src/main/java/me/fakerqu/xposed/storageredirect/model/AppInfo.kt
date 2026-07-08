package me.fakerqu.xposed.storageredirect.model

import android.graphics.drawable.Drawable

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
