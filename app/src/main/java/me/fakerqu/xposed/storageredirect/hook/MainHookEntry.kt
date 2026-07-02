package me.fakerqu.xposed.storageredirect.hook

import android.annotation.SuppressLint
import android.content.ContentResolver
import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.content.pm.ProviderInfo
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import android.util.Base64
import android.util.Log
import androidx.core.database.getBlobOrNull
import androidx.core.database.getFloatOrNull
import androidx.core.database.getIntOrNull
import androidx.core.database.getStringOrNull
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromStream
import me.fakerqu.xposed.storageredirect.config.ConfigConstants
import me.fakerqu.xposed.storageredirect.config.model.DirMode
import me.fakerqu.xposed.storageredirect.config.model.PackageConfig
import me.fakerqu.xposed.storageredirect.config.model.RuntimeConfig
import me.fakerqu.xposed.storageredirect.config.model.UserConfig
import net.sf.jsqlparser.expression.StringValue
import net.sf.jsqlparser.expression.operators.relational.EqualsTo
import net.sf.jsqlparser.expression.operators.relational.LikeExpression
import net.sf.jsqlparser.parser.CCJSqlParserUtil
import net.sf.jsqlparser.schema.Column
import net.sf.jsqlparser.util.deparser.ExpressionDeParser
import java.util.concurrent.atomic.AtomicReference

class MainHookEntry : XposedModule() {
    data class ConfigSnapshot(
        val version: Long,
        val byPackage: Map<String, PackageConfig>,      // 只读 Map
        val byUid: Map<Int, RuntimeConfig>,             // 只读 Map；无配置可不放进 map
    ) {
        companion object {
            val EMPTY = ConfigSnapshot(0, emptyMap(), emptyMap())
        }
    }

    private val configSnapshot = AtomicReference(ConfigSnapshot.EMPTY)
    private lateinit var configPreferences: SharedPreferences

    @SuppressLint("PrivateApi")
    override fun onPackageReady(param: XposedModuleInterface.PackageReadyParam) {
        super.onPackageReady(param)
        if (param.packageName == "com.android.providers.media.module") {
            try {
                log(Log.INFO, "SRX", "install hook on ${param.packageName}")
                val mediaProvider =
                    param.classLoader.loadClass("com.android.providers.media.MediaProvider")
                val identityClass =
                    param.classLoader.loadClass("com.android.providers.media.LocalCallingIdentity")
                configPreferences = getRemotePreferences(ConfigConstants.CONFIG_SHARED_PREFERENCE)
                hook(
                    mediaProvider.getDeclaredMethod(
                        "attachInfo",
                        Context::class.java,
                        ProviderInfo::class.java
                    )
                ).intercept { chain ->
                    reloadConfig(chain.args[0] as Context, 0)
                    configPreferences.registerOnSharedPreferenceChangeListener { preferences, key ->
                        if (key == ConfigConstants.CONFIG_VERSION_KEY) {
                            reloadConfig(chain.args[0] as Context, preferences.getLong(key, 0L))
                        }
                    }

                    val result = chain.proceed()
                    hookQueryInternal(param.classLoader)
                    result
                }
            } catch (e: ClassNotFoundException) {
                log(Log.ERROR, "SRX", "failed find class with exception", e)
                throw RuntimeException(e)
            } catch (e: NoSuchMethodException) {
                log(Log.ERROR, "SRX", "failed find method with exception", e)
                throw RuntimeException(e)
            } catch (e: Exception) {
                log(Log.ERROR, "SRX", "failed on package ready exception", e)
            }
        }
    }


    /**
     * hook MediaProvider.queryInternal
     *
     * MediaProvider.shouldBypassFuseRestrictions方法表明应用是否不受fuse限制，开启重定向的应用，强制设置为false
     *
     * MediaProvider.isCallingPackageRequestingLegacy方法表明应用是否为传统权限，开启重定向的应用，强制设置为false, 避免开启fuse后无法访问任何目录
     *
     * MediaProvider.queryInternal方法是MediaProvider的核心查询语句,MediaStore Api的查询均会走到此处
     * 1.检查arg[4] forSelf == true时跳过处理
     * 2.isPickerUri 为true跳过处理--表明是用户手选的文件，符合预期
     * 3.特殊匹配的一些查询，应该是系统的方法，我们不做处理
     * 4.尝试为查询列增补一个路径列，后续根据这个信息过滤路径
     * 5.包装返回Cursor,核心过滤方法在此处
     *
     * TODO:尝试hook FileApi
     *
     * @param classLoader ClassLoader of MediaProvider
     */
    @SuppressLint("PrivateApi")
    private fun hookQueryInternal(classLoader: ClassLoader) {
        //region 反射相关内容
        val mediaProvider =
            classLoader.loadClass("com.android.providers.media.MediaProvider")
        val identityClass =
            classLoader.loadClass("com.android.providers.media.LocalCallingIdentity")
        val localUriMatcher =
            classLoader.loadClass("com.android.providers.media.LocalUriMatcher")
        val mCallingIdentityField = mediaProvider.getDeclaredField("mCallingIdentity").apply {
            isAccessible = true
        }
        val isPickerUriMethod =
            mediaProvider.getDeclaredMethod("isPickerUri", Uri::class.java).apply {
                isAccessible = true
            }
        val providerUriMatcherField = mediaProvider.getDeclaredField("mUriMatcher").apply {
            isAccessible = true
        }
        val isCallingPackageAllowedHiddenMethod =
            mediaProvider.getDeclaredMethod("isCallingPackageAllowedHidden").apply {
                isAccessible = true
            }
        val isCallerPhotoPickerMethod =
            mediaProvider.getDeclaredMethod("isCallerPhotoPicker").apply {
                isAccessible = true
            }
        val localUriMatcherMatchMethod = localUriMatcher.getDeclaredMethod(
            "matchUri", Uri::class.java,
            Boolean::class.javaPrimitiveType,
            Boolean::class.javaPrimitiveType,
        ).apply {
            isAccessible = true
        }
        //endregion

        hook(
            mediaProvider.getDeclaredMethod(
                "getFilesInDirectoryForFuse",
                String::class.java,
                Int::class.javaPrimitiveType
            )
        ).intercept { chain ->
            val originResult = chain.proceed()
            originResult
        }

        //region shouldBypassFuseRestrictions
        //对于重定向的应用，bypass会导致直接读取或写入而不经过fuse检查，这里强制设置为false
        hook(
            mediaProvider.getDeclaredMethod(
                "shouldBypassFuseRestrictions",
                Boolean::class.javaPrimitiveType,
                String::class.java
            )
        ).intercept { chain ->
            log(
                Log.INFO,
                "SRX",
                "hook shouldBypassFuseRestrictions, param=<${chain.args.joinToString { it.toString() }}>"
            )
            val result = chain.proceed()
            try {
                val mCallingIdentity =
                    chain.thisObject::class.java.getDeclaredField("mCallingIdentity")
                        .apply {
                            isAccessible = true
                        }.get(chain.thisObject) as ThreadLocal<*>
                val uid =
                    identityClass.getDeclaredField("uid")
                        .get(mCallingIdentity.get()) as Int?
                log(
                    Log.INFO,
                    "SRX",
                    "hook shouldBypassFuseRestrictions, path=${chain.args[1]}, uid=${uid}, origin result=${result}"
                )
                //path为空，按照原有流程处理
                val path = chain.args[1] as? String? ?: return@intercept result
                val config = configSnapshot.get().byUid[uid]
                val dirConfig = config?.dirConfigs?.filter {
                    it.enabled && path.startsWith("/storage/emulated/${getUserId(uid ?: 0)}/${it.relativePath}")
                }?.maxByOrNull {
                    it.relativePath.length
                }
                log(
                    Log.INFO,
                    "SRX",
                    "shouldBypassFuseRestrictions path=$path matched dir config=${dirConfig}"
                )
                //配置不为空（说明enabled）且不为W模式（透传模式），不能bypass
                if (config != null && dirConfig?.mode != DirMode.WRITE) {
                    log(
                        Log.INFO,
                        "SRX",
                        "hook shouldBypassFuseRestrictions replace result path=${chain.args[1]}, uid=${uid}, result=false stack=${Exception().stackTraceToString()}"
                    )
                    return@intercept false
                }
            } catch (e: Exception) {
                log(
                    Log.ERROR,
                    "SRX",
                    "hook shouldBypassFuseRestrictions failed with exception",
                    e
                )
            }
            result
        }
        //endregion

        //region isCallingPackageRequestingLegacy
        //对于重定向的应用，Legacy STORAGE权限检查是不必要的，这类应用根据配置文件读写
        hook(
            mediaProvider.getDeclaredMethod(
                "isCallingPackageRequestingLegacy",
            )
        ).intercept { chain ->
            log(
                Log.INFO,
                "SRX",
                "hook isCallingPackageRequestingLegacy"
            )
            val originResult = chain.proceed()
            try {
                val mCallingIdentity =
                    chain.thisObject::class.java.getDeclaredField("mCallingIdentity")
                        .apply {
                            isAccessible = true
                        }.get(chain.thisObject) as ThreadLocal<*>
                val uid =
                    identityClass.getDeclaredField("uid")
                        .get(mCallingIdentity.get()) as Int?
                log(
                    Log.INFO,
                    "SRX",
                    "hook isCallingPackageRequestingLegacy, uid=${uid}, origin result=${originResult}"
                )
                if (configSnapshot.get().byUid[uid] != null) {
                    return@intercept false
                }
            } catch (e: Exception) {
                log(
                    Log.ERROR,
                    "SRX",
                    "hook isCallingPackageRequestingLegacy failed with exception",
                    e
                )
            }
            originResult
        }
        //endregion

        //region query internal
        //核心查询方法，hook后fuse相关请求都会在这里处理
        hook(
            mediaProvider.getDeclaredMethod(
                "queryInternal",
                Uri::class.java,
                Array<String>::class.java,
                Bundle::class.java,
                CancellationSignal::class.java,
                Boolean::class.javaPrimitiveType
            )
        ).intercept { chain: XposedInterface.Chain ->
            //region 1.检查一些无需hook的情况
            val mCallingIdentity =
                mCallingIdentityField.get(chain.thisObject) as ThreadLocal<*>
            val uid = identityClass.getDeclaredField("uid").get(mCallingIdentity.get()) as Int?
            log(
                Log.INFO,
                "SRX",
                "start hook query internal with uid=$uid ,originParam=${chain.args.joinToString { it?.toString() ?: "null" }}"
            )

            //应用不需要重定向，跳过处理
            val config = configSnapshot.get().byUid[uid] ?: return@intercept chain.proceed()
            try {
                if (chain.args[4] == true) {
                    log(
                        Log.INFO,
                        "SRX",
                        "query internal bypass hook because of forSelf==true"
                    )
                    return@intercept chain.proceed()
                }
                //如果是picker方式访问，无需过滤结果
                val callingPackageAllowedHidden =
                    isCallingPackageAllowedHiddenMethod(chain.thisObject)
                val isCallerPhotoPicker = isCallerPhotoPickerMethod(chain.thisObject)

                log(
                    Log.INFO,
                    "SRX",
                    "query internal uid=$uid allowHidden=$callingPackageAllowedHidden,isPhotoPicker=$isCallerPhotoPicker"
                )
                val isPickerUri =
                    isPickerUriMethod(chain.thisObject, chain.args[0]) as Boolean
                if (isPickerUri) {
                    log(
                        Log.INFO,
                        "SRX",
                        "query internal bypass hook is picker uri ${chain.args[0]}"
                    )
                    return@intercept chain.proceed()
                }
                val table = localUriMatcherMatchMethod(
                    providerUriMatcherField.get(chain.thisObject),
                    chain.args[0],
                    callingPackageAllowedHidden,
                    isCallerPhotoPicker
                ) as Int

                //media scan\media grants\fs id\version\picker internal v2\picker internal\
                if (table == 500 || table == 1000 || table == 600 || table == 601 || table == 908 || listOf(
                        902,
                        903,
                        904,
                        905
                    ).contains(table)
                ) {
                    log(
                        Log.INFO,
                        "SRX",
                        "query internal bypass hook is other uri ${chain.args[0]}-$table"
                    )
                    return@intercept chain.proceed()
                }
            } catch (e: Exception) {
                log(Log.ERROR, "SRX", "failed skip replace with exception", e)
            }
            //endregion

            //region 2.替换方法参数
            val originProjection = chain.args[1] as Array<String>?
            val newArg = chain.args.toTypedArray()
            try {
                if (!originProjection.isNullOrEmpty() && !originProjection.contains(MediaStore.MediaColumns.DATA)) {
                    log(Log.INFO, "SRX", "query internal replace projection _data not found")
                    newArg.apply {
                        this[1] = arrayOf(*originProjection, MediaStore.MediaColumns.DATA)
                    }
                }
                val queryArg = chain.args[2] as Bundle?
                queryArg?.let {
                    queryArg.getString(ContentResolver.QUERY_ARG_SQL_SELECTION)?.let { selection ->
                        val selectionArgs =
                            queryArg.getStringArray(ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS)
                                ?: emptyArray()

                        val expression = CCJSqlParserUtil.parseCondExpression(
                            bindSelection(
                                selection,
                                *selectionArgs
                            )
                        )
                        queryArg.remove(ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS)
                        queryArg.putString(
                            ContentResolver.QUERY_ARG_SQL_SELECTION, expression.accept(
                                SqlPathReplacer(listOf("relative_path", "_data")) { originPath ->
                                    PathConverter.toFs(getUserId(uid ?: 0), config, originPath)
                                }, null
                            ).toString()
                        )
                    }
                }
                log(
                    Log.INFO,
                    "SRX",
                    "replaced query internal param=<${newArg.joinToString { it?.toString() ?: "null" }}>"
                )
            } catch (e: Exception) {
                log(
                    Log.ERROR,
                    "SRX",
                    "failed set new projection with exception origin=${(chain.args[1] as Array<*>?)?.joinToString { it.toString() }}",
                    e
                )
            }
            //endregion
            val result = chain.proceedWith(chain.thisObject, newArg) as Cursor?
            //region 3.拦截替换结果
            try {
                return@intercept if (configSnapshot.get().byUid[uid] != null) {
                    FilteredWrappedCursor.wrap(result, originProjection) { originPath ->
                        PathConverter.toApp(getUserId(uid ?: 0), config, originPath)
                    }
                } else {
                    result
                }
            } catch (e: Exception) {
                log(Log.ERROR, "SRX", "failed hook query with exception", e)
            }
            log(
                Log.INFO,
                "SRX",
                "param=<${newArg.joinToString { it?.toString() ?: "null" }}>,filtered result = ${result?.dumpContent()}"
            )
            //endregion
            return@intercept result
        }
        //endregion
    }

    @OptIn(ExperimentalSerializationApi::class)
    //TODO:处理多个package相同uid时，规则合并问题
    private fun reloadConfig(context: Context, version: Long) {
        openRemoteFile(ConfigConstants.CONFIG_FILE).use { descriptor ->
            ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { inputStream ->
                val userConfig = Json.decodeFromStream<UserConfig>(inputStream)
                val packageConfigs = userConfig.packageConfigs.filter { it.enabled }
                if (userConfig.enabled) {
                    val pm = context.packageManager
                    configSnapshot.set(
                        ConfigSnapshot(
                            version,
                            packageConfigs.associateBy { it.packageName },
                            packageConfigs.associate {
                                val uid = pm.getPackageUid(
                                    it.packageName,
                                    PackageManager.MATCH_UNINSTALLED_PACKAGES
                                )
                                uid to RuntimeConfig(
                                    uid,
                                    pm.getNameForUid(uid) ?: it.packageName,
                                    it.dirConfigs
                                )
                            }
                        ))
                    log(Log.INFO, "SRX", "reload config ${configSnapshot.get()}")
                }
            }
        }
    }

    private class SqlPathReplacer(
        private val columnNames: List<String>,
        private val replaceMethod: (originPath: String) -> List<String>
    ) : ExpressionDeParser() {
        override fun <S> visit(equalsTo: EqualsTo?, context: S): StringBuilder {
            val left = equalsTo?.leftExpression
            val right = equalsTo?.rightExpression
            if (left is Column && columnNames.contains(left.columnName)) {
                builder.append("(")
                if (right is StringValue) {
                    replaceMethod(right.value).foldIndexed(
                        builder,
                        operation = { index, builder, replaced ->
                            if (index >= 1)
                                builder.append(" OR ")
                            super.visit(EqualsTo(left, StringValue(replaced)), context)
                            builder
                        })
                }
                builder.append(")")
                return builder
            } else {
                return super.visit(equalsTo, context)
            }
        }

        override fun <S> visit(
            likeExpression: LikeExpression?,
            context: S
        ): java.lang.StringBuilder {
            val left = likeExpression?.leftExpression
            val right = likeExpression?.rightExpression
            if (left is Column && columnNames.contains(left.columnName)) {
                builder.append("(")
                if (right is StringValue) {
                    replaceMethod(right.value).foldIndexed(
                        builder,
                        operation = { index, builder, replaced ->
                            if (index >= 1)
                                if (likeExpression.isNot) {
                                    builder.append(" AND ")
                                } else {
                                    builder.append(" OR ")
                                }
                            super.visit(EqualsTo(left, StringValue(replaced)), context)
                            builder
                        })
                }
                builder.append(")")
                return builder
            } else {
                return super.visit(likeExpression, context)
            }
        }
    }


    private fun Cursor.dumpContent(): List<String> {
        val result = mutableListOf<String>()
        moveToPosition(-1)
        while (moveToNext()) {
            result.add(columnNames.mapIndexed { index, name ->
                "${name}:${
                    when (getType(index)) {
                        Cursor.FIELD_TYPE_BLOB -> "blob"
                        Cursor.FIELD_TYPE_NULL -> "null"
                        Cursor.FIELD_TYPE_FLOAT -> "f"
                        Cursor.FIELD_TYPE_STRING -> "s"
                        Cursor.FIELD_TYPE_INTEGER -> "i"
                        else -> "unknown"
                    }
                }:${
                    when (getType(index)) {
                        Cursor.FIELD_TYPE_BLOB -> Base64.encode(
                            getBlobOrNull(index),
                            Base64.DEFAULT
                        )

                        Cursor.FIELD_TYPE_NULL -> "null"
                        Cursor.FIELD_TYPE_FLOAT -> getFloatOrNull(index)
                        Cursor.FIELD_TYPE_STRING -> getStringOrNull(index)
                        Cursor.FIELD_TYPE_INTEGER -> getIntOrNull(index)
                        else -> "unknown"
                    }
                }"
            }.joinToString(separator = ";") { it })
        }
        moveToPosition(-1)
        return result
    }

    companion object {
        fun getUserId(uid: Int): Int {
            return uid / 100000
        }

        fun bindSelection(
            selection: String,
            vararg selectionArgs: Any?
        ): String {
            // If no arguments provided, so we can't bind anything
            if (selectionArgs.isEmpty()) return selection
            // If no bindings requested, so we can shortcut
            if (selection.indexOf('?') == -1) return selection

            // Track the chars immediately before and after each bind request, to
            // decide if it needs additional whitespace added
            var before = ' '
            var after = ' '

            var argIndex = 0
            val len = selection.length
            val res = java.lang.StringBuilder(len)
            var i = 0
            while (i < len) {
                var c = selection[i++]
                if (c == '?') {
                    // Assume this bind request is guarded until we find a specific
                    // trailing character below
                    after = ' '

                    // Sniff forward to see if the selection is requesting a
                    // specific argument index
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

                    // Manually bind the argument into the selection, adding
                    // whitespace when needed for clarity
                    val arg: Any = selectionArgs[argIndex++]!!
                    if (before != ' ' && before != '=') res.append(' ')
                    when (getTypeOfObject(arg)) {
                        Cursor.FIELD_TYPE_NULL -> res.append("NULL")
                        Cursor.FIELD_TYPE_INTEGER -> res.append((arg as Number).toLong())
                        Cursor.FIELD_TYPE_FLOAT -> res.append((arg as Number).toDouble())
                        Cursor.FIELD_TYPE_BLOB -> throw IllegalArgumentException("Blobs not supported")
                        Cursor.FIELD_TYPE_STRING -> if (arg is Boolean) {
                            // Provide compatibility with legacy applications which may pass
                            // Boolean values in bind args.
                            res.append(if (arg) 1 else 0)
                        } else {
                            res.append('\'')
                            res.append(arg.toString())
                            res.append('\'')
                        }

                        else -> if (arg is Boolean) {
                            res.append(if (arg) 1 else 0)
                        } else {
                            res.append('\'')
                            res.append(arg.toString())
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

        fun getTypeOfObject(obj: Any?): Int {
            return when (obj) {
                null -> {
                    Cursor.FIELD_TYPE_NULL
                }

                is ByteArray -> {
                    Cursor.FIELD_TYPE_BLOB
                }

                is Float, is Double -> {
                    Cursor.FIELD_TYPE_FLOAT
                }

                is Long, is Int, is Short, is Byte -> {
                    Cursor.FIELD_TYPE_INTEGER
                }

                else -> {
                    Cursor.FIELD_TYPE_STRING
                }
            }
        }
    }
}

