package me.fakerqu.xposed.storageredirect.ui

import androidx.navigation3.runtime.NavKey
import kotlinx.serialization.Serializable

@Serializable
sealed interface AppNavKey : NavKey

@Serializable
data object MainKey : AppNavKey

@Serializable
data object AppListKey : AppNavKey

@Serializable
data class AppDetailKey(val packageName: String) : AppNavKey

@Serializable
data class DirectoryPickerKey(val packageName: String) : AppNavKey
