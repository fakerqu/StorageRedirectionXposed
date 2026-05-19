package me.fakerqu.xposed.storageredirect.config.model

import kotlinx.serialization.Serializable

@Serializable
data class UserConfig(
    val userId: Int,
    val enabled: Boolean,
    val packageConfigs: List<PackageConfig>
)