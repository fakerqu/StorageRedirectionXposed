package me.fakerqu.xposed.storageredirect.config.model

data class DirConfig(val enabled: Boolean, val relativePath: String, val mode: DirMode)
enum class DirMode {
    READ, WRITE, NONE
}