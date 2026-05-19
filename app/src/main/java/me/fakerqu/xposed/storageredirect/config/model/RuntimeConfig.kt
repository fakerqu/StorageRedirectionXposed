package me.fakerqu.xposed.storageredirect.config.model

data class RuntimeConfig(
    val uid: Int,
    val uidName: String,
    val dirConfigs: List<DirConfig>
)