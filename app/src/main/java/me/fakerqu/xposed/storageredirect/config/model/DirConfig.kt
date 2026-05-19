package me.fakerqu.xposed.storageredirect.config.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class DirConfig(val enabled: Boolean, val relativePath: String, val mode: DirMode)

@Serializable
enum class DirMode {
    @SerialName("r")
    READ,

    @SerialName("w")
    WRITE,

    @SerialName("n")
    NONE
}