package me.fakerqu.xposed.storageredirect.config.model

import kotlinx.serialization.Serializable

@Serializable
data class RuntimeConfig(
    val uid: Int,
    val uidName: String,
    val dirConfigs: List<DirConfig>
)