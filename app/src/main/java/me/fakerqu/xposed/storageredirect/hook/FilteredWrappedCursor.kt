package me.fakerqu.xposed.storageredirect.hook

import android.content.ContentResolver
import android.database.Cursor
import android.net.Uri
import android.util.Log
import android.util.SparseArray
import androidx.core.database.getStringOrNull
import androidx.core.util.size

class FilteredWrappedCursor private constructor(
    private val originCursor: Cursor,
    private val visibleColumn: Array<String>?,
    mappedPath: List<String?>,
) : Cursor by originCursor {
    private val filteredPath: SparseArray<String> = SparseArray()

    init {
        mappedPath.forEachIndexed { index, path ->
            if (path != null && path != PathConverter.PATH_FILTER_OUT) {
                filteredPath[index] = path
            }
        }
    }

    companion object {
        fun wrap(
            originCursor: Cursor?,
            visibleColumn: Array<String>?,
            filterPath: (originPath: String) -> String
        ): Cursor? {
            return originCursor?.let {
                try {
                    val mappedPath = mutableListOf<String?>()
                    val pathIndex = originCursor.getPathColumnIndex()
                    originCursor.moveToPosition(-1)
                    while (originCursor.moveToNext()) {
                        val newPath =
                            originCursor.getStringOrNull(pathIndex)?.let { filterPath(it) }
                        mappedPath.add(newPath)
                    }
                    val result = FilteredWrappedCursor(originCursor, visibleColumn, mappedPath)
                    Log.e("SRX", "wrapped cursor count ${result.count}")
                    result
                } catch (e: Exception) {
                    Log.e("SRX", "failed wrap originCursor with exception", e)
                    originCursor
                }
            }
        }

        private fun Cursor.getPathColumnIndex() =
            columnNames.indexOfFirst { it == "_data" }.takeIf {
                it != -1
            } ?: columnNames.indexOfFirst { it == "data" }
    }

    private val pathIndex: Int = originCursor.getPathColumnIndex()

    private var mPos = -1

    override fun getColumnCount(): Int =
        visibleColumn?.size?.takeIf { it != 0 } ?: originCursor.columnCount

    override fun getColumnNames(): Array<out String?>? =
        visibleColumn.takeIf { !it.isNullOrEmpty() } ?: originCursor.columnNames

    override fun getCount(): Int = filteredPath.size

    override fun getString(index: Int): String? = if (index == pathIndex) {
        val new = filteredPath[originCursor.position]
        Log.i("SRX", "redirect to $new index=$index")
        new
    } else {
        originCursor.getString(index)
    }

    override fun getNotificationUris(): List<Uri?>? {
        return originCursor.getNotificationUris()
    }

    override fun setNotificationUris(
        cr: ContentResolver,
        uris: List<Uri?>
    ) {
        originCursor.setNotificationUris(cr, uris)
    }

    override fun move(offset: Int): Boolean {
        return moveToPosition(mPos + offset)
    }

    override fun moveToFirst(): Boolean = realMove(0)

    override fun moveToLast(): Boolean = realMove(filteredPath.size - 1)

    override fun moveToNext(): Boolean {
        return moveToPosition(mPos + 1)
    }

    override fun moveToPosition(position: Int): Boolean = realMove(position)

    override fun moveToPrevious(): Boolean {
        return moveToPosition(mPos - 1)
    }

    override fun isAfterLast(): Boolean {
        if (count == 0) {
            return true
        }
        return mPos == count
    }

    override fun isBeforeFirst(): Boolean {
        if (count == 0) {
            return true
        }
        return mPos == -1
    }

    override fun isFirst(): Boolean {
        return mPos == 0 && count != 0
    }

    override fun isLast(): Boolean {
        val cnt = count
        return mPos == (cnt - 1) && cnt != 0
    }

    private fun realMove(newPosition: Int): Boolean {
        val size = count
        if (newPosition >= size) {
            mPos = size
            return false
        }
        if (newPosition < 0) {
            mPos = -1
            return false
        }
        if (newPosition == mPos) {
            return true
        }

        if (originCursor.moveToPosition(filteredPath.keyAt(newPosition))) {
            mPos = newPosition
            return true
        } else {
            originCursor.moveToPosition(-1)
            mPos = -1
            return false
        }
    }
}