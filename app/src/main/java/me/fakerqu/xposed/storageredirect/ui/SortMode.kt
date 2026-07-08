package me.fakerqu.xposed.storageredirect.ui

/**
 * 应用列表排序方式（单选）
 */
enum class SortMode(val label: String) {
    NAME_ASC("按名称排序"),
    INSTALL_TIME("按安装时间排序"),
}
