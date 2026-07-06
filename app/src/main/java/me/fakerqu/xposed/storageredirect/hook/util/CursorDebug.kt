package me.fakerqu.xposed.storageredirect.hook.util

import android.database.Cursor
import android.util.Base64
import androidx.core.database.getBlobOrNull
import androidx.core.database.getFloatOrNull
import androidx.core.database.getIntOrNull
import androidx.core.database.getStringOrNull

/**
 * Cursor 调试工具：将 Cursor 内容转换为人类可读的字符串列表。
 */
object CursorDebug {

    fun dump(cursor: Cursor?): List<String> {
        if (cursor == null) return emptyList()
        val result = mutableListOf<String>()
        cursor.moveToPosition(-1)
        while (cursor.moveToNext()) {
            result.add(cursor.columnNames.mapIndexed { index, name ->
                val type = cursor.getType(index)
                "${name}:${
                    typeShortName(type)
                }:${
                    when (type) {
                        Cursor.FIELD_TYPE_BLOB -> Base64.encode(cursor.getBlobOrNull(index), Base64.DEFAULT)
                        Cursor.FIELD_TYPE_NULL -> "null"
                        Cursor.FIELD_TYPE_FLOAT -> cursor.getFloatOrNull(index)
                        Cursor.FIELD_TYPE_STRING -> cursor.getStringOrNull(index)
                        Cursor.FIELD_TYPE_INTEGER -> cursor.getIntOrNull(index)
                        else -> "unknown"
                    }
                }"
            }.joinToString(separator = ";") { it })
        }
        cursor.moveToPosition(-1)
        return result
    }

    private fun typeShortName(type: Int) = when (type) {
        Cursor.FIELD_TYPE_BLOB -> "blob"
        Cursor.FIELD_TYPE_NULL -> "null"
        Cursor.FIELD_TYPE_FLOAT -> "f"
        Cursor.FIELD_TYPE_STRING -> "s"
        Cursor.FIELD_TYPE_INTEGER -> "i"
        else -> "unknown"
    }
}
