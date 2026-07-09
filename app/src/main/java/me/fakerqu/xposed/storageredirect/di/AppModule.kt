package me.fakerqu.xposed.storageredirect.di

import me.fakerqu.xposed.storageredirect.ui.AppDetailViewModel
import me.fakerqu.xposed.storageredirect.ui.AppListViewModel
import me.fakerqu.xposed.storageredirect.ui.DirectoryPickerViewModel
import me.fakerqu.xposed.storageredirect.ui.HomeViewModel
import org.koin.core.module.dsl.viewModelOf
import org.koin.dsl.module
import org.koin.dsl.navigation3.navigation

/**
 * Koin 模块：注册所有 ViewModel
 */
val appModule = module {
    viewModelOf(::HomeViewModel)
    viewModelOf(::AppListViewModel)
    viewModelOf(::AppDetailViewModel)
    viewModelOf(::DirectoryPickerViewModel)
}
