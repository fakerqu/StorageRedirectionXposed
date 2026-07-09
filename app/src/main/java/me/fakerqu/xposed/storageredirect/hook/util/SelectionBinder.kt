package me.fakerqu.xposed.storageredirect.hook.util

import android.database.Cursor

/**
 * 将 SQL selection 中的 `?` 占位符替换为 selectionArgs 中的实际值。
 *
 * 移植自 AOSP SQLiteQueryBuilder.bindSelection，用于在 SQL AST 重写前
 * 将参数内联，便于后续 JSqlParser 解析。
 */
object SelectionBinder {

    fun bind(selection: String, vararg selectionArgs: Any?): String {
        if (selectionArgs.isEmpty()) return selection
        if (selection.indexOf('?') == -1) return selection

        var before = ' '
        var after = ' '

        var argIndex = 0
        val len = selection.length
        val res = StringBuilder(len)
        var i = 0
        while (i < len) {
            var c = selection[i++]
            if (c == '?') {
                after = ' '
                val start = i
                while (i < len) {
                    c = selection[i]
                    if (c !in '0'..'9') {
                        after = c
                        break
                    }
                    i++
                }
                if (start != i) {
                    argIndex = selection.substring(start, i).toInt() - 1
                }
                val arg: Any = selectionArgs[argIndex++]!!
                if (before != ' ' && before != '=') res.append(' ')
                when (getTypeOfObject(arg)) {
                    Cursor.FIELD_TYPE_NULL -> res.append("NULL")
                    Cursor.FIELD_TYPE_INTEGER -> res.append((arg as Number).toLong())
                    Cursor.FIELD_TYPE_FLOAT -> res.append((arg as Number).toDouble())
                    Cursor.FIELD_TYPE_BLOB -> throw IllegalArgumentException("Blobs not supported")
                    Cursor.FIELD_TYPE_STRING -> if (arg is Boolean) {
                        res.append(if (arg) 1 else 0)
                    } else {
                        res.append('\'')
                        res.append(arg.toString().replace("'", "''"))
                        res.append('\'')
                    }
                    else -> if (arg is Boolean) {
                        res.append(if (arg) 1 else 0)
                    } else {
                        res.append('\'')
                        res.append(arg.toString().replace("'", "''"))
                        res.append('\'')
                    }
                }
                if (after != ' ') res.append(' ')
            } else {
                res.append(c)
                before = c
            }
        }
        return res.toString()
    }

    private fun getTypeOfObject(obj: Any?): Int = when (obj) {
        null -> Cursor.FIELD_TYPE_NULL
        is ByteArray -> Cursor.FIELD_TYPE_BLOB
        is Float, is Double -> Cursor.FIELD_TYPE_FLOAT
        is Long, is Int, is Short, is Byte -> Cursor.FIELD_TYPE_INTEGER
        else -> Cursor.FIELD_TYPE_STRING
    }

    fun getUserId(uid: Int): Int = uid / 100000
}
