package me.fakerqu.xposed.storageredirect.config.model

import kotlinx.serialization.Serializable

@Serializable
data class StoredConfig(
    val packageName: String,
    val enabled: Boolean,
    val dirConfigs: List<DirConfig>
)
