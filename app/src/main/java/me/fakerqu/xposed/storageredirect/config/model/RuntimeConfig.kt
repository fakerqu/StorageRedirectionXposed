package me.fakerqu.xposed.storageredirect.config.model

data class RuntimeConfig(
    val uid: Int,
    val dirConfigs: List<DirConfig>
)