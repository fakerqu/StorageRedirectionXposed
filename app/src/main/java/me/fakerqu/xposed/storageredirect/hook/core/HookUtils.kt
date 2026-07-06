package me.fakerqu.xposed.storageredirect.hook.core

/**
 * Hook 模块通用工具方法。
 */
object HookUtils {
    /** 从 UID 提取 Android userId */
    fun getUserId(uid: Int): Int = uid / 100000
}
