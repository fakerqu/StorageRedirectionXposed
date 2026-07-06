package me.fakerqu.xposed.storageredirect.hook.core

import android.net.Uri
import java.lang.reflect.Field
import java.lang.reflect.Method

/**
 * MediaProvider 反射缓存。
 *
 * 在 hook 安装时一次性解析所有需要的字段/方法，避免每次拦截时重复反射查找。
 */
class MediaProviderReflection(classLoader: ClassLoader) {

    val mediaProviderClass: Class<*> =
        classLoader.loadClass("com.android.providers.media.MediaProvider")

    val identityClass: Class<*> =
        classLoader.loadClass("com.android.providers.media.LocalCallingIdentity")

    val localUriMatcherClass: Class<*> =
        classLoader.loadClass("com.android.providers.media.LocalUriMatcher")

    // ---- Fields ----

    val mCallingIdentityField: Field =
        mediaProviderClass.getDeclaredField("mCallingIdentity").apply { isAccessible = true }

    val mUriMatcherField: Field =
        mediaProviderClass.getDeclaredField("mUriMatcher").apply { isAccessible = true }

    val identityUidField: Field =
        identityClass.getDeclaredField("uid").apply { isAccessible = true }

    // ---- Methods ----

    val isPickerUriMethod: Method =
        mediaProviderClass.getDeclaredMethod("isPickerUri", Uri::class.java).apply { isAccessible = true }

    val isCallingPackageAllowedHiddenMethod: Method =
        mediaProviderClass.getDeclaredMethod("isCallingPackageAllowedHidden").apply { isAccessible = true }

    val isCallerPhotoPickerMethod: Method =
        mediaProviderClass.getDeclaredMethod("isCallerPhotoPicker").apply { isAccessible = true }

    val matchUriMethod: Method =
        localUriMatcherClass.getDeclaredMethod(
            "matchUri", Uri::class.java,
            Boolean::class.javaPrimitiveType,
            Boolean::class.javaPrimitiveType,
        ).apply { isAccessible = true }

    val insertFileIfNecessaryMethod: Method =
        mediaProviderClass.getDeclaredMethod(
            "insertFileIfNecessaryForFuse",
            String::class.java,
            Int::class.javaPrimitiveType,
        ).apply { isAccessible = true }

    private val queryMethod: Method =
        mediaProviderClass.getDeclaredMethod(
            "query",
            Uri::class.java,
            Array<String>::class.java,
            android.os.Bundle::class.java,
            android.os.CancellationSignal::class.java,
        ).apply { isAccessible = true }

    // ---- Convenience accessors ----

    /**
     * 从 MediaProvider 实例中提取当前调用者 UID。
     */
    @Suppress("UNCHECKED_CAST")
    fun getCallingUid(mediaProvider: Any): Int {
        val threadLocal = mCallingIdentityField.get(mediaProvider) as ThreadLocal<*>
        val identity = threadLocal.get() ?: return -1
        return identityUidField.get(identity) as Int
    }

    /**
     * 通过 query 方法查询 URI 对应的 _data 路径。
     */
    fun getDataPathFromUri(mediaProvider: Any, uri: Uri): String? {
        val cursor = queryMethod.invoke(
            mediaProvider,
            uri,
            arrayOf(android.provider.MediaStore.MediaColumns.DATA),
            null,
            null,
        ) as? android.database.Cursor
        return cursor?.use {
            if (it.moveToFirst()) it.getString(0) else null
        }
    }

    /**
     * 调用 insertFileIfNecessaryForFuse 注册文件到 MediaStore。
     */
    fun ensureFileInMediaStore(mediaProvider: Any, path: String, uid: Int) {
        insertFileIfNecessaryMethod.invoke(mediaProvider, path, uid)
    }
}
