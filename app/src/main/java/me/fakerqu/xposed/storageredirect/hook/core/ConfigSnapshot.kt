package me.fakerqu.xposed.storageredirect.hook.core

import me.fakerqu.xposed.storageredirect.config.model.PackageConfig
import me.fakerqu.xposed.storageredirect.config.model.RuntimeConfig

/**
 * 不可变的配置快照，通过 [AtomicReference] 实现 CAS 更新。
 */
data class ConfigSnapshot(
    val version: Long,
    val byPackage: Map<String, PackageConfig>,
    val byUid: Map<Int, RuntimeConfig>,
) {
    companion object {
        val EMPTY = ConfigSnapshot(0, emptyMap(), emptyMap())
    }
}
