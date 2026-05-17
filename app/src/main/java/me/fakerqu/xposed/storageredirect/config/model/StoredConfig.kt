package me.fakerqu.xposed.storageredirect.config.model

data class StoredConfig(
    val packageName: String,
    val enabled: Boolean,
    val dirConfigs: List<DirConfig>
)
