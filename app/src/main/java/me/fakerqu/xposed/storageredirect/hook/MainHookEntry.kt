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
                    // Initialize native hooks FIRST, so reloadConfig can push configs to native
                    try {
                        NativeHook.init(this@MainHookEntry)
                    } catch (e: Exception) {
                        log(Log.ERROR, "SRX", "NativeHook.init failed", e)
                    }

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

        //region getFilesInDirectoryForFuse
        //Android 16: String[] getFilesInDirectoryForFuse(String path, int uid)
        //FUSE readdir 回调此方法，返回 String[] 文件名列表
        hook(
            mediaProvider.getDeclaredMethod(
                "getFilesInDirectoryForFuse",
                String::class.java,
                Int::class.javaPrimitiveType
            )
        ).intercept { chain ->
            hookGetFilesInDirectoryForFuse(chain)
        }
        //endregion

        //region onFileLookupForFuse
        //Android 16: FileLookupResult onFileLookupForFuse(String path, int uid, int forWrite)
        //替代旧版 getFileForFuse，FUSE getattr 回调此方法
        try {
            hook(
                mediaProvider.getDeclaredMethod(
                    "onFileLookupForFuse",
                    String::class.java,
                    Int::class.javaPrimitiveType,
                    Int::class.javaPrimitiveType
                )
            ).intercept { chain ->
                log(Log.INFO, "SRX", "onFileLookupForFuse called: path=${chain.args[0]}, uid=${chain.args[1]}, forWrite=${chain.args[2]}")
                hookOnFileLookupForFuse(chain)
            }
        } catch (e: NoSuchMethodException) {
            log(Log.WARN, "SRX", "onFileLookupForFuse not found", e)
        }
        //endregion

        //region shouldBypassFuseRestrictions
        //对于重定向的应用，保持 false，确保 FUSE 回调 getFilesInDirectoryForFuse
        hook(
            mediaProvider.getDeclaredMethod(
                "shouldBypassFuseRestrictions",
                Boolean::class.javaPrimitiveType,
                String::class.java
            )
        ).intercept { chain ->
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
                val path = chain.args[1] as? String? ?: return@intercept result
                val config = configSnapshot.get().byUid[uid]
                if (config != null && PathConverter.pathNeedRedirect(getUserId(uid ?: 0), path)) {
                    log(
                        Log.INFO,
                        "SRX",
                        "shouldBypassFuseRestrictions force false: path=$path, uid=$uid"
                    )
                    return@intercept false
                }
            } catch (e: Exception) {
                log(Log.ERROR, "SRX", "hook shouldBypassFuseRestrictions failed", e)
            }
            result
        }
        //endregion

        //region isDirAccessAllowedForFuse
        //Android 16: int isDirAccessAllowedForFuse(String path, int uid, int forWrite)
        //返回 int: 0=允许, 非0=拒绝（errno 约定）
        //对于重定向的应用：
        //  - 根目录：允许，getFilesInDirectoryForFuse 会过滤 NONE 子目录
        //  - READ/WRITE/NONE 模式目录：允许（NONE 模式由 getFilesInDirectoryForFuse 返回 Upper 内容）
        try {
            hook(
                mediaProvider.getDeclaredMethod(
                    "isDirAccessAllowedForFuse",
                    String::class.java,
                    Int::class.javaPrimitiveType,
                    Int::class.javaPrimitiveType
                )
            ).intercept { chain ->
                val path = chain.args[0] as? String? ?: return@intercept chain.proceed()
                val uid = chain.args[1] as Int
                try {
                    val config = configSnapshot.get().byUid[uid]
                    if (config != null) {
                        val userId = getUserId(uid)
                        if (PathConverter.pathNeedRedirect(userId, path)) {
                            val mode = PathConverter.resolveMode(config, userId, path)
                            // WRITE/READ: always allow access
                            if (mode == DirMode.WRITE || mode == DirMode.READ) {
                                return@intercept 0
                            }
                            // NONE mode: only allow if upper directory exists
                            if (mode == DirMode.NONE) {
                                val upperPath = PathConverter.getUpperPath(userId, config, path)
                                val upperDir = java.io.File(upperPath)
                                if (upperDir.exists()) {
                                    return@intercept 0
                                }
                                // Upper doesn't exist → deny access
                                log(Log.INFO, "SRX", "isDirAccessAllowedForFuse deny (NONE, no upper): path=$path, uid=$uid")
                                return@intercept 2 // ENOENT
                            }
                        }
                    }
                    return@intercept chain.proceed()
                } catch (e: Exception) {
                    log(Log.ERROR, "SRX", "hook isDirAccessAllowedForFuse failed", e)
                    return@intercept chain.proceed()
                }
            }
        } catch (e: NoSuchMethodException) {
            log(Log.WARN, "SRX", "isDirAccessAllowedForFuse method not found", e)
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
                    val userId = getUserId(uid ?: 0)
                    FilteredWrappedCursor.wrap(result, originProjection) { originPath ->
                        PathConverter.toApp(
                            userId, config, originPath,
                            fileExists = { directPath -> java.io.File(directPath).exists() },
                            isWhiteouted = { origin -> OverlayHelper.isWhiteouted(userId, config, origin) },
                        )
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

        hookInsert(mediaProvider)
        hookDelete(mediaProvider)
        hookUpdate(mediaProvider)
        hookOpenFile(mediaProvider)
    }

    /**
     * hook MediaProvider.getFilesInDirectoryForFuse
     *
     * Android 16: String[] getFilesInDirectoryForFuse(String path, int uid)
     * FUSE readdir 回调此方法，返回文件名数组。
     *
     * 根目录（/sdcard, /storage/emulated/0）：过滤掉 NONE 模式子目录，仅保留有配置的子目录
     * w 模式：透传底层目录列表
     * n 模式：返回 Upper 层目录列表（空则返回空数组）
     * r 模式：合并 Upper + Lower 文件名列表，过滤 whiteout
     */
    @SuppressLint("PrivateApi")
    private fun hookGetFilesInDirectoryForFuse(
        chain: XposedInterface.Chain
    ): Any? {
        val path = chain.args[0] as String
        val uid = chain.args[1] as Int
        val config = configSnapshot.get().byUid[uid] ?: return chain.proceed()

        val userId = getUserId(uid)
        if (!PathConverter.pathNeedRedirect(userId, path)) return chain.proceed()

        val mode = PathConverter.resolveMode(config, userId, path)
        log(Log.INFO, "SRX", "getFilesInDirectoryForFuse path=$path uid=$uid mode=$mode")

        return try {
            when (mode) {
                DirMode.WRITE -> {
                    chain.proceed()
                }

                DirMode.NONE -> {
                    // 判断是否是根目录
                    val relativePath = PathConverter.toRelativePath(userId, path)
                    if (relativePath.isEmpty()) {
                        // 根目录：过滤 NONE 子目录 + 加入 Upper 层文件
                        val originalNames = (chain.proceed() as? Array<*>)
                            ?.mapNotNull { it as? String }
                            ?: emptyList()
                        val basePath = path.trimEnd('/')
                        val filtered = originalNames.filter { name ->
                            val childPath = "$basePath/$name"
                            val childMode = PathConverter.resolveMode(config, userId, childPath)
                            childMode != DirMode.NONE
                        }.toMutableSet()
                        // 从文件系统直接读取 Upper 层目录（不经过 MediaStore）
                        val upperPath = PathConverter.getUpperPath(userId, config, path)
                        val upperDir = java.io.File(upperPath)
                        if (upperDir.exists()) {
                            upperDir.listFiles()?.forEach { f ->
                                if (!f.name.startsWith(".wh.")) {
                                    filtered.add(f.name)
                                }
                            }
                        }
                        log(Log.INFO, "SRX", "getFilesInDirectoryForFuse root: original=${originalNames.size}, filtered=${filtered.size}")
                        filtered.toTypedArray()
                    } else {
                        // NONE 模式子目录：直接从文件系统读取 Upper 层
                        val upperPath = PathConverter.getUpperPath(userId, config, path)
                        val upperDir = java.io.File(upperPath)
                        if (upperDir.exists()) {
                            val names = upperDir.listFiles()
                                ?.map { it.name }
                                ?.filter { !it.startsWith(".wh.") }
                                ?: emptyList()
                            log(Log.INFO, "SRX", "getFilesInDirectoryForFuse n-mode fs: upper=$upperPath, count=${names.size}")
                            names.toTypedArray()
                        } else {
                            emptyArray<String>()
                        }
                    }
                }

                DirMode.READ -> {
                    // r 模式：合并 Upper + Lower 文件名
                    // 1. 获取 Lower 文件名列表（由 FUSE 原始方法返回）
                    val lowerNames = (chain.proceed() as? Array<*>)
                        ?.mapNotNull { it as? String }
                        ?: emptyList()

                    // 2. 获取 Upper 文件名列表（直通路径）
                    val upperPath = PathConverter.getUpperPath(userId, config, path)
                    val upperDir = java.io.File(upperPath)
                    val upperNames = if (upperDir.exists()) {
                        upperDir.listFiles()
                            ?.map { it.name }
                            ?: emptyList()
                    } else {
                        emptyList()
                    }

                    // 3. 合并：FUSE getdents 已能看到 Lower 层物理条目，
                    //    chain.proceed() 是原始方法返回的列表，
                    //    我们只需要补充 Upper 层独有的条目
                    val whiteoutNames = upperNames
                        .filter { it.startsWith(".wh.") }
                        .map { it.removePrefix(".wh.") }
                        .toSet()

                    // 4. 构建 Lower 物理条目集合，用于判断 Upper 条目是否独有
                    val lowerDirectPath = PathConverter.toDirectPath(userId, path)
                    val lowerDir = java.io.File(lowerDirectPath)
                    val lowerPhysicalNames = if (lowerDir.exists()) {
                        lowerDir.listFiles()?.map { it.name }?.toSet() ?: emptySet()
                    } else {
                        emptySet()
                    }

                    val result = LinkedHashSet<String>()
                    // chain.proceed() 的结果经过 MediaStore 过滤，可能不含子目录
                    for (name in lowerNames) {
                        if (name !in whiteoutNames) {
                            result.add(name)
                        }
                    }
                    // 只加 Upper 中非 whiteout 且不在 Lower 物理目录中的条目
                    // Lower 物理目录中的条目已由 FUSE getdents 负责
                    for (name in upperNames) {
                        if (!name.startsWith(".wh.") && name !in lowerPhysicalNames && name !in result) {
                            result.add(name)
                        }
                    }

                    log(Log.INFO, "SRX", "getFilesInDirectoryForFuse r-mode: lower=${lowerNames.size}, upper=${upperNames.size}, whiteouts=${whiteoutNames.size}, merged=${result.size}")
                    result.toTypedArray()
                }
            }
        } catch (e: Exception) {
            log(Log.ERROR, "SRX", "getFilesInDirectoryForFuse failed", e)
            return chain.proceed()
        }
    }

    /**
     * hook MediaProvider.onFileLookupForFuse
     *
     * Android 16: FileLookupResult onFileLookupForFuse(String path, int uid, int forWrite)
     * 替代旧版 getFileForFuse，FUSE getattr 回调此方法。
     *
     * 根据 mode 决定返回哪个层的文件信息：
     * - w 模式：透传原始路径
     * - n 模式：替换为 Upper 路径，先确保 Upper 文件在 MediaStore 中注册
     * - r 模式：Upper 存在用 Upper（先确保注册）；不存在但被 whiteout → null；
     *          Upper 不存在且无 whiteout → 透传原始路径
     */
    @SuppressLint("PrivateApi")
    private fun hookOnFileLookupForFuse(chain: XposedInterface.Chain): Any? {
        val path = chain.args[0] as String
        val uid = chain.args[1] as Int
        val config = configSnapshot.get().byUid[uid] ?: return chain.proceed()

        val userId = getUserId(uid)
        if (!PathConverter.pathNeedRedirect(userId, path)) return chain.proceed()

        val mode = PathConverter.resolveMode(config, userId, path)
        log(Log.INFO, "SRX", "onFileLookupForFuse path=$path uid=$uid mode=$mode")

        return try {
            when (mode) {
                DirMode.WRITE -> {
                    chain.proceed()
                }

                DirMode.NONE -> {
                    // MediaProvider API 需要 FUSE 路径
                    val upperFusePath = PathConverter.getUpperFusePath(userId, config, path)
                    log(Log.INFO, "SRX", "onFileLookupForFuse n-mode upperFusePath=$upperFusePath")
                    ensureFileInMediaStore(chain.thisObject, upperFusePath, uid)
                    chain.proceedWith(chain.thisObject, arrayOf<Any?>(upperFusePath, uid, chain.args[2]))
                }

                DirMode.READ -> {
                    if (OverlayHelper.upperExists(userId, config, path)) {
                        // MediaProvider API 需要 FUSE 路径
                        val upperFusePath = PathConverter.getUpperFusePath(userId, config, path)
                        log(Log.INFO, "SRX", "onFileLookupForFuse r-mode use upper=$upperFusePath")
                        ensureFileInMediaStore(chain.thisObject, upperFusePath, uid)
                        chain.proceedWith(chain.thisObject, arrayOf<Any?>(upperFusePath, uid, chain.args[2]))
                    } else if (OverlayHelper.isWhiteouted(userId, config, path)) {
                        log(Log.INFO, "SRX", "onFileLookupForFuse r-mode whiteouted, return null: $path")
                        null
                    } else {
                        log(Log.INFO, "SRX", "onFileLookupForFuse r-mode use lower: $path")
                        chain.proceed()
                    }
                }
            }
        } catch (e: Exception) {
            log(Log.ERROR, "SRX", "onFileLookupForFuse failed", e)
            return chain.proceed()
        }
    }

    /**
     * 确保 Upper 层文件在 MediaStore 中注册。
     *
     * FUSE readdir 后会通过 onFileLookupForFuse 探测每个文件是否存在。
     * 如果 Upper 层文件不在 MediaStore 中，探测返回 null，文件会被 FUSE 过滤。
     * 调用 insertFileIfNecessaryForFuse 将文件注册到 MediaStore。
     */
    @SuppressLint("PrivateApi")
    private fun ensureFileInMediaStore(mediaProvider: Any, path: String, uid: Int) {
        try {
            val file = java.io.File(path)
            if (!file.exists()) return

            val mediaProviderClass = mediaProvider.javaClass
            val insertMethod = mediaProviderClass.getDeclaredMethod(
                "insertFileIfNecessaryForFuse",
                String::class.java,
                Int::class.javaPrimitiveType
            ).apply { isAccessible = true }

            insertMethod.invoke(mediaProvider, path, uid)
            log(Log.INFO, "SRX", "ensureFileInMediaStore: registered $path")
        } catch (e: Exception) {
            log(Log.WARN, "SRX", "ensureFileInMediaStore failed for $path", e)
        }
    }

    /**
     * hook MediaProvider.insert
     *
     * 文件创建时拦截，根据 mode 决定创建位置：
     * - w 模式：透传
     * - n 模式：修改 RELATIVE_PATH 指向 Upper
     * - r 模式：先移除 whiteout，再修改 RELATIVE_PATH 指向 Upper
     */
    @SuppressLint("PrivateApi")
    private fun hookInsert(mediaProviderClass: Class<*>) {
        // insert(Uri uri, ContentValues values)
        try {
            hook(
                mediaProviderClass.getDeclaredMethod(
                    "insert",
                    Uri::class.java,
                    android.content.ContentValues::class.java
                )
            ).intercept { chain ->
                hookInsertInternal(chain)
            }
        } catch (e: NoSuchMethodException) {
            log(Log.WARN, "SRX", "insert(Uri, ContentValues) not found, trying Bundle variant", e)
            // insert(Uri uri, ContentValues values, Bundle extras) — Android 13+
            try {
                hook(
                    mediaProviderClass.getDeclaredMethod(
                        "insert",
                        Uri::class.java,
                        android.content.ContentValues::class.java,
                        Bundle::class.java
                    )
                ).intercept { chain ->
                    hookInsertInternal(chain)
                }
            } catch (e2: NoSuchMethodException) {
                log(Log.WARN, "SRX", "insert method not found, skipping hook", e2)
            }
        }
    }

    @SuppressLint("PrivateApi")
    private fun hookInsertInternal(chain: XposedInterface.Chain): Any? {
        val uid = getCallingUid(chain.thisObject)
        val config = configSnapshot.get().byUid[uid] ?: return chain.proceed()

        // 无配置，透传

        try {
            val values = chain.args[1] as? android.content.ContentValues ?: return chain.proceed()
            val userId = getUserId(uid)

            // 获取应用请求的相对路径
            val relativePath = values.get(MediaStore.MediaColumns.RELATIVE_PATH) as? String
            val displayName = values.get(MediaStore.MediaColumns.DISPLAY_NAME) as? String

            if (relativePath == null && displayName == null) return chain.proceed()

            // 构建完整路径用于 mode 判断
            val fullPath = buildFullPath(userId, relativePath, displayName)
            if (!PathConverter.pathNeedRedirect(userId, fullPath)) return chain.proceed()

            val mode = PathConverter.resolveMode(config, userId, fullPath)
            log(Log.INFO, "SRX", "insert path=$fullPath mode=$mode")

            return when (mode) {
                DirMode.WRITE -> {
                    // w 模式：透传
                    chain.proceed()
                }

                DirMode.READ, DirMode.NONE -> {
                    // r/n 模式：重定向到 Upper
                    // r 模式需先移除 whiteout
                    if (mode == DirMode.READ) {
                        OverlayHelper.removeWhiteout(userId, config, fullPath)
                    }

                    // 修改 RELATIVE_PATH 指向 Upper
                    val upperRelativePath = buildUpperRelativePath(config, relativePath)
                    if (upperRelativePath != null) {
                        values.put(MediaStore.MediaColumns.RELATIVE_PATH, upperRelativePath)
                        log(Log.INFO, "SRX", "insert redirected RELATIVE_PATH=$upperRelativePath")
                    }
                    chain.proceed()
                }
            }
        } catch (e: Exception) {
            log(Log.ERROR, "SRX", "insert hook failed", e)
            return chain.proceed()
        }
    }

    /**
     * hook MediaProvider.delete
     *
     * 删除保护：
     * - w 模式：创建 whiteout，不删除底层文件，返回 1
     * - r 模式：Upper 有实体删 Upper；Lower 有同名创建 whiteout；返回 1
     * - n 模式：透传（删除 Upper 文件）
     */
    @SuppressLint("PrivateApi")
    private fun hookDelete(mediaProviderClass: Class<*>) {
        try {
            hook(
                mediaProviderClass.getDeclaredMethod(
                    "delete",
                    Uri::class.java,
                    String::class.java,
                    Array<String>::class.java
                )
            ).intercept { chain ->
                hookDeleteInternal(chain)
            }
        } catch (e: NoSuchMethodException) {
            log(Log.WARN, "SRX", "delete method not found, skipping hook", e)
        }
    }

    @SuppressLint("PrivateApi")
    private fun hookDeleteInternal(chain: XposedInterface.Chain): Any? {
        val uid = getCallingUid(chain.thisObject)
        val config = configSnapshot.get().byUid[uid] ?: return chain.proceed()

        // 无配置，透传

        try {
            val uri = chain.args[0] as Uri
            val userId = getUserId(uid)

            // 从 URI 提取文件路径
            // delete 的 selection 可能针对多条记录，这里只处理单条 URI（带 id）的情况
            val dataPath = getDataPathFromUri(chain.thisObject, uri)
            if (dataPath == null || !PathConverter.pathNeedRedirect(userId, dataPath)) return chain.proceed()

            val mode = PathConverter.resolveMode(config, userId, dataPath)
            log(Log.INFO, "SRX", "delete path=$dataPath mode=$mode")

            return when (mode) {
                DirMode.WRITE -> {
                    // w 模式：创建 whiteout，不删除底层文件
                    OverlayHelper.createWhiteout(userId, config, dataPath)
                    // 返回 1 表示删除成功
                    1
                }

                DirMode.READ -> {
                    // r 模式：
                    if (OverlayHelper.upperExists(userId, config, dataPath)) {
                        // Upper 有实体 → 删除 Upper 实体（直通路径绕过 FUSE）
                        val upperPath = PathConverter.getUpperPath(userId, config, dataPath)
                        java.io.File(upperPath).delete()
                    }
                    // 如果 Lower 也存在同名文件 → 创建 whiteout
                    val lowerFile = java.io.File(dataPath)
                    if (lowerFile.exists()) {
                        OverlayHelper.createWhiteout(userId, config, dataPath)
                    }
                    // 返回 1 表示删除成功
                    1
                }

                DirMode.NONE -> {
                    // n 模式：透传（删除 Upper 文件）
                    chain.proceed()
                }
            }
        } catch (e: Exception) {
            log(Log.ERROR, "SRX", "delete hook failed", e)
            return chain.proceed()
        }
    }

    /**
     * hook MediaProvider.update
     *
     * 拦截修改和重命名操作：
     * - 修改 RELATIVE_PATH/DISPLAY_NAME（重命名/移动）：
     *   r 模式先 copy-up，检查跨 mode 限制；w→r 返回 -1 (EACCES)；跨区域返回 -1 (EXDEV)
     * - 其他字段修改：r 模式需先 copy-up
     */
    @SuppressLint("PrivateApi")
    private fun hookUpdate(mediaProviderClass: Class<*>) {
        try {
            hook(
                mediaProviderClass.getDeclaredMethod(
                    "update",
                    Uri::class.java,
                    android.content.ContentValues::class.java,
                    String::class.java,
                    Array<String>::class.java
                )
            ).intercept { chain ->
                hookUpdateInternal(chain)
            }
        } catch (e: NoSuchMethodException) {
            log(Log.WARN, "SRX", "update method not found, skipping hook", e)
        }
    }

    @SuppressLint("PrivateApi")
    private fun hookUpdateInternal(chain: XposedInterface.Chain): Any? {
        val uid = getCallingUid(chain.thisObject)
        val config = configSnapshot.get().byUid[uid] ?: return chain.proceed()

        // 无配置，透传

        try {
            val uri = chain.args[0] as Uri
            val values = chain.args[1] as? android.content.ContentValues ?: return chain.proceed()
            val userId = getUserId(uid)

            val dataPath = getDataPathFromUri(chain.thisObject, uri)
            if (dataPath == null || !PathConverter.pathNeedRedirect(userId, dataPath)) return chain.proceed()

            val sourceMode = PathConverter.resolveMode(config, userId, dataPath)
            log(Log.INFO, "SRX", "update path=$dataPath sourceMode=$sourceMode")

            // 检查是否为重命名/移动操作
            val newRelativePath = values.get(MediaStore.MediaColumns.RELATIVE_PATH) as? String
            val newDisplayName = values.get(MediaStore.MediaColumns.DISPLAY_NAME) as? String

            if (newRelativePath != null || newDisplayName != null) {
                // 重命名/移动操作
                val targetPath = buildFullPath(
                    userId,
                    newRelativePath ?: (dataPath.substringBeforeLast('/').substringAfter("/storage/emulated/$userId/")),
                    newDisplayName ?: dataPath.substringAfterLast('/')
                )

                if (PathConverter.pathNeedRedirect(userId, targetPath)) {
                    val targetMode = PathConverter.resolveMode(config, userId, targetPath)

                    // w → r 禁止
                    if (sourceMode == DirMode.WRITE && targetMode == DirMode.READ) {
                        log(Log.INFO, "SRX", "update w->r denied (EACCES)")
                        return 0
                    }

                    // 跨 mode 区域（仅 r 模式有跨区域限制）
                    if (sourceMode == DirMode.READ && targetMode != DirMode.READ) {
                        log(Log.INFO, "SRX", "update cross-region denied (EXDEV): $sourceMode -> $targetMode")
                        return 0
                    }

                    // r 模式：先 copy-up 源文件
                    if (sourceMode == DirMode.READ) {
                        OverlayHelper.copyUp(userId, config, dataPath)
                        // 修改目标路径指向 Upper
                        val upperRelativePath = buildUpperRelativePath(config, newRelativePath)
                        if (upperRelativePath != null) {
                            values.put(MediaStore.MediaColumns.RELATIVE_PATH, upperRelativePath)
                        }
                    }
                }
            } else {
                // 普通字段修改
                if (sourceMode == DirMode.READ) {
                    // r 模式需先 copy-up
                    OverlayHelper.copyUp(userId, config, dataPath)
                }
            }

            return chain.proceed()
        } catch (e: Exception) {
            log(Log.ERROR, "SRX", "update hook failed", e)
            return chain.proceed()
        }
    }

    /**
     * hook MediaProvider.openFile / openFileCommon
     *
     * 文件描述符打开时拦截：
     * - w 模式：透传
     * - n 模式：确保 Upper 存在，返回 Upper 文件 FD
     * - r 模式：写操作先 copy-up；Upper 存在用 Upper，否则用 Lower
     */
    @SuppressLint("PrivateApi")
    private fun hookOpenFile(mediaProviderClass: Class<*>) {
        // openFile(Uri uri, String mode)
        try {
            hook(
                mediaProviderClass.getDeclaredMethod(
                    "openFile",
                    Uri::class.java,
                    String::class.java
                )
            ).intercept { chain ->
                hookOpenFileInternal(chain)
            }
        } catch (e: NoSuchMethodException) {
            log(Log.WARN, "SRX", "openFile method not found, skipping hook", e)
        }

        // openTypedAssetFile(Uri uri, String mimeTypeFilter, Bundle opts)
        try {
            hook(
                mediaProviderClass.getDeclaredMethod(
                    "openTypedAssetFile",
                    Uri::class.java,
                    String::class.java,
                    Bundle::class.java,
                    CancellationSignal::class.java
                )
            ).intercept { chain ->
                hookOpenFileInternal(chain)
            }
        } catch (e: NoSuchMethodException) {
            log(Log.WARN, "SRX", "openTypedAssetFile method not found, skipping hook", e)
        }
    }

    @SuppressLint("PrivateApi")
    private fun hookOpenFileInternal(chain: XposedInterface.Chain): Any? {
        val uid = getCallingUid(chain.thisObject)
        val config = configSnapshot.get().byUid[uid] ?: return chain.proceed()

        // 无配置，透传

        try {
            val uri = chain.args[0] as Uri
            val userId = getUserId(uid)
            val openMode = (chain.args.getOrNull(1) as? String) ?: "r"

            val dataPath = getDataPathFromUri(chain.thisObject, uri)
            if (dataPath == null || !PathConverter.pathNeedRedirect(userId, dataPath)) return chain.proceed()

            val mode = PathConverter.resolveMode(config, userId, dataPath)
            log(Log.INFO, "SRX", "openFile path=$dataPath mode=$mode openMode=$openMode")

            return when (mode) {
                DirMode.WRITE -> {
                    // w 模式：透传
                    chain.proceed()
                }

                DirMode.READ -> {
                    // r 模式
                    if (openMode.contains("w") || openMode.contains("rw")) {
                        // 写操作 → 先 copy-up
                        OverlayHelper.copyUp(userId, config, dataPath)
                    }
                    // Upper 存在 → 透传（文件已在 Upper，FUSE 层会正确处理）
                    // Upper 不存在 → 透传（读 Lower）
                    chain.proceed()
                }

                DirMode.NONE -> {
                    // n 模式：确保 Upper 目录存在（使用直通路径绕过 FUSE）
                    val upperPath = PathConverter.getUpperPath(userId, config, dataPath)
                    OverlayHelper.ensureUpperDir(upperPath)
                    // 透传，FUSE 层通过 getFileForFuse 已经会重定向到 Upper
                    chain.proceed()
                }
            }
        } catch (e: Exception) {
            log(Log.ERROR, "SRX", "openFile hook failed", e)
            return chain.proceed()
        }
    }

    //region 辅助方法

    /**
     * 从 MediaProvider 的 ThreadLocal 中获取当前调用者的 UID。
     */
    @SuppressLint("PrivateApi")
    private fun getCallingUid(thisObject: Any): Int {
        return try {
            val identityClass =
                thisObject::class.java.classLoader!!.loadClass("com.android.providers.media.LocalCallingIdentity")
            val mCallingIdentityField =
                thisObject::class.java.getDeclaredField("mCallingIdentity").apply {
                    isAccessible = true
                }
            val threadLocal = mCallingIdentityField.get(thisObject) as ThreadLocal<*>
            val identity = threadLocal.get() ?: return -1
            identityClass.getDeclaredField("uid").get(identity) as Int
        } catch (e: Exception) {
            log(Log.ERROR, "SRX", "getCallingUid failed", e)
            -1
        }
    }

    /**
     * 从 URI 查询 _data 路径。
     */
    @SuppressLint("PrivateApi")
    private fun getDataPathFromUri(
        thisObject: Any,
        uri: Uri
    ): String? {
        return try {
            // 通过 query 查询 _data 列
            val queryMethod = thisObject::class.java.getDeclaredMethod(
                "query",
                Uri::class.java,
                Array<String>::class.java,
                Bundle::class.java,
                CancellationSignal::class.java
            )
            queryMethod.isAccessible = true
            val cursor = queryMethod.invoke(
                thisObject,
                uri,
                arrayOf(MediaStore.MediaColumns.DATA),
                null,
                null
            ) as? Cursor
            cursor?.use {
                if (it.moveToFirst()) {
                    it.getString(0)
                } else {
                    null
                }
            }
        } catch (e: Exception) {
            log(Log.ERROR, "SRX", "getDataPathFromUri failed", e)
            null
        }
    }

    /**
     * 根据 relativePath 和 displayName 构建完整路径。
     */
    @SuppressLint("SdCardPath")
    private fun buildFullPath(userId: Int, relativePath: String?, displayName: String?): String {
        val base = "/storage/emulated/$userId"
        val parts = mutableListOf<String>()
        if (!relativePath.isNullOrEmpty()) parts.add(relativePath.trimEnd('/'))
        if (!displayName.isNullOrEmpty()) parts.add(displayName)
        return "$base/${parts.joinToString("/")}"
    }

    /**
     * 构建 Upper 层的 RELATIVE_PATH。
     * 统一映射到 Android/media/<pkg>/sdcard_redirect/ 以获得 MediaStore 支持
     */
    private fun buildUpperRelativePath(config: RuntimeConfig, relativePath: String?): String? {
        if (relativePath == null) return null
        val basePath = PathConverter.getRedirectBase(config)
        return if (relativePath.isNotEmpty()) {
            "$basePath/${relativePath.trimStart('/')}"
        } else {
            "$basePath/"
        }
    }

    //endregion

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

                    // Push all configs to native layer
                    NativeHook.clearAllConfigs()
                    configSnapshot.get().byUid.forEach { (uid, config) ->
                        val userId = getUserId(uid)
                        NativeHook.setUidConfig(uid, config, userId)
                    }
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

