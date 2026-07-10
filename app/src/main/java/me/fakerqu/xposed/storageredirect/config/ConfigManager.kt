package me.fakerqu.xposed.storageredirect.config

import android.os.ParcelFileDescriptor
import io.github.libxposed.service.XposedService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromStream
import me.fakerqu.xposed.storageredirect.XposedServiceManager
import me.fakerqu.xposed.storageredirect.config.model.DirConfig
import me.fakerqu.xposed.storageredirect.config.model.PackageConfig
import me.fakerqu.xposed.storageredirect.config.model.UserConfig
import androidx.core.content.edit

/**
 * LSP 配置文件管理工具类
 *
 * 负责对远程 [ConfigConstants.CONFIG_FILE]（config.json）进行读取与写入，
 * 并在内存中维护当前 [UserConfig] 快照，供 UI 层直接查询。
 *
 * 单应用配置的 CRUD 通过以下方法完成：
 * - [getPackageConfig]          查询单个应用的配置
 * - [upsertPackageConfig]       新增 / 更新单个应用的配置
 * - [removePackageConfig]       删除单个应用的配置
 * - [setPackageEnabled]         快速开关单个应用的配置
 * - [addDirConfig]              为指定应用添加目录规则
 * - [updateDirConfig]           修改指定应用的目录规则
 * - [removeDirConfig]           删除指定应用的目录规则
 *
 * 所有写操作都会在 [Dispatchers.IO] 上异步持久化到远程文件。
 *
 * 使用前需确保 [XposedServiceManager] 已完成绑定（[XposedServiceManager.isHooked] == true）。
 */
object ConfigManager {

    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private val _currentConfig = MutableStateFlow<UserConfig?>(null)

    /** 当前内存中的配置快照，null 表示尚未加载或服务未绑定 */
    val currentConfig: StateFlow<UserConfig?> = _currentConfig.asStateFlow()

    // ========================= 查询 =========================

    /**
     * 列出所有已配置的应用的包名集合。
     *
     * 如果尚未加载过配置，会先调用 [reload]。
     * @return 已配置的包名 Set，如果服务未绑定或配置为空则返回空集合
     */
    suspend fun getConfiguredPackageNames(): Set<String> = withContext(Dispatchers.IO) {
        if (_currentConfig.value == null) {
            runCatching { reload() }
        }
        _currentConfig.value
            ?.packageConfigs
            ?.map { it.packageName }
            ?.toSet()
            ?: emptySet()
    }

    /**
     * 列出所有配置启用的应用的包名集合。
     *
     * 如果尚未加载过配置，会先调用 [reload]。
     * @return 已配置的包名 Set，如果服务未绑定或配置为空则返回空集合
     */
    suspend fun getConfigEnabledPackageNames(): Set<String> = withContext(Dispatchers.IO) {
        if (_currentConfig.value == null) {
            runCatching { reload() }
        }
        _currentConfig.value
            ?.packageConfigs
            ?.filter { it.enabled }
            ?.map { it.packageName }
            ?.toSet()
            ?: emptySet()
    }

    // ========================= 整体加载 / 保存 =========================

    /**
     * 从远程文件重新加载配置到内存。
     * @throws IllegalStateException 如果 XposedService 未绑定
     * @throws Exception 如果文件读取或反序列化失败
     */
    @OptIn(ExperimentalSerializationApi::class)
    suspend fun reload() = withContext(Dispatchers.IO) {
        val service = requireService()
        _currentConfig.value = runCatching {
            service.openRemoteFile(ConfigConstants.CONFIG_FILE).use { pfd ->
                ParcelFileDescriptor.AutoCloseInputStream(pfd).use { input ->
                    json.decodeFromStream<UserConfig>(input)
                }
            }
        }.getOrElse {
            // 配置文件不存在或格式错误时使用默认空配置
            UserConfig(userId = 0, enabled = true, packageConfigs = emptyList())
        }
    }

    /**
     * 将当前内存中的配置写入远程文件，并 bump 配置版本号以通知 Hook 端重新加载。
     *
     * 版本号存储在远程 SharedPreferences（[ConfigConstants.CONFIG_SHARED_PREFERENCE]）的
     * [ConfigConstants.CONFIG_VERSION_KEY] 键中，每次 [save] 后递增 +1。
     *
     * @throws IllegalStateException 如果 XposedService 未绑定
     * @throws Exception 如果文件写入失败
     */
    @OptIn(ExperimentalSerializationApi::class)
    private suspend fun save() = withContext(Dispatchers.IO) {
        val service = requireService()
        val config = _currentConfig.value ?: error("ConfigManager.save() called before load()")
        // 先编码为 byte[]，确保知道确切的写入长度
        val jsonBytes = json.encodeToString(UserConfig.serializer(), config).toByteArray()
        service.openRemoteFile(ConfigConstants.CONFIG_FILE).use { pfd ->
            val output = ParcelFileDescriptor.AutoCloseOutputStream(pfd)
            output.write(jsonBytes)
            output.flush()
            // 先截断文件到实际写入长度，再关闭流
            // （关闭 AutoCloseOutputStream 会同时关闭 PFD，必须在关闭前截断）
            runCatching {
                android.system.Os.ftruncate(pfd.fileDescriptor, jsonBytes.size.toLong())
            }
            output.close()
        }
        // Bump 版本号，通知 Hook 端重新加载配置
        val prefs = service.getRemotePreferences(ConfigConstants.CONFIG_SHARED_PREFERENCE)
        val currentVersion = prefs.getLong(ConfigConstants.CONFIG_VERSION_KEY, 0L)
        prefs.edit { putLong(ConfigConstants.CONFIG_VERSION_KEY, currentVersion + 1) }
    }

    // ========================= 单应用配置 CRUD =========================

    /**
     * 查询指定应用的配置。
     * @return 该应用的 [PackageConfig]，若不存在则返回 null
     */
    fun getPackageConfig(packageName: String): PackageConfig? {
        return _currentConfig.value?.packageConfigs?.find { it.packageName == packageName }
    }

    /**
     * 新增或更新单个应用的配置。
     *
     * 如果 [config] 的 [PackageConfig.packageName] 已存在则替换，否则追加。
     * 写入后自动持久化。
     */
    suspend fun upsertPackageConfig(config: PackageConfig) {
        updateConfig { current ->
            val list = current.packageConfigs.toMutableList()
            val index = list.indexOfFirst { it.packageName == config.packageName }
            if (index >= 0) {
                list[index] = config
            } else {
                list.add(config)
            }
            current.copy(packageConfigs = list)
        }
    }

    /**
     * 删除指定应用的配置。
     * @return true 如果配置存在且已被删除
     */
    suspend fun removePackageConfig(packageName: String): Boolean {
        var removed = false
        updateConfig { current ->
            val list = current.packageConfigs.toMutableList()
            removed = list.removeAll { it.packageName == packageName }
            current.copy(packageConfigs = list)
        }
        return removed
    }

    /**
     * 快速开关指定应用的配置，不影响其 [PackageConfig.dirConfigs]。
     * 如果该应用尚无配置记录，则不会创建新记录。
     * @return true 如果成功切换（应用配置存在）
     */
    suspend fun setPackageEnabled(packageName: String, enabled: Boolean): Boolean {
        var changed = false
        updateConfig { current ->
            val list = current.packageConfigs.map { pc ->
                if (pc.packageName == packageName) {
                    changed = true
                    pc.copy(enabled = enabled)
                } else {
                    pc
                }
            }
            current.copy(packageConfigs = list)
        }
        return changed
    }

    // ========================= 单应用目录规则 CRUD =========================

    /**
     * 为指定应用添加一条目录规则。
     * 如果该应用尚无配置记录，会自动创建一个（enabled = true）。
     */
    suspend fun addDirConfig(packageName: String, dirConfig: DirConfig) {
        updateConfig { current ->
            val list = current.packageConfigs.toMutableList()
            val index = list.indexOfFirst { it.packageName == packageName }

            if (index >= 0) {
                val pc = list[index]
                list[index] = pc.copy(dirConfigs = pc.dirConfigs + dirConfig)
            } else {
                list.add(PackageConfig(packageName, enabled = true, dirConfigs = listOf(dirConfig)))
            }
            current.copy(packageConfigs = list)
        }
    }

    /**
     * 更新指定应用中匹配 [relativePath] 的目录规则。
     * @return true 如果找到并更新了匹配的规则
     */
    suspend fun updateDirConfig(packageName: String, relativePath: String, newConfig: DirConfig): Boolean {
        var changed = false
        updateConfig { current ->
            val list = current.packageConfigs.map { pc ->
                if (pc.packageName == packageName) {
                    val dirs = pc.dirConfigs.map { dc ->
                        if (dc.relativePath == relativePath) {
                            changed = true
                            newConfig
                        } else {
                            dc
                        }
                    }
                    pc.copy(dirConfigs = dirs)
                } else {
                    pc
                }
            }
            current.copy(packageConfigs = list)
        }
        return changed
    }

    /**
     * 删除指定应用中匹配 [relativePath] 的目录规则。
     * @return true 如果找到并删除了匹配的规则
     */
    suspend fun removeDirConfig(packageName: String, relativePath: String): Boolean {
        var removed = false
        updateConfig { current ->
            val list = current.packageConfigs.map { pc ->
                if (pc.packageName == packageName) {
                    val originalSize = pc.dirConfigs.size
                    val dirs = pc.dirConfigs.filterNot { it.relativePath == relativePath }
                    removed = dirs.size < originalSize
                    pc.copy(dirConfigs = dirs)
                } else {
                    pc
                }
            }
            current.copy(packageConfigs = list)
        }
        return removed
    }

    // ========================= 内部工具 =========================

    /**
     * 在内存中修改配置，然后持久化到远程文件。
     * 如果尚未加载过配置，会先调用 [reload]。
     */
    /**
     * 在内存中修改配置，然后在 [Dispatchers.IO] 上持久化到远程文件。
     * 如果尚未加载过配置，会先调用 [reload]。
     */
    private suspend inline fun updateConfig(crossinline block: (UserConfig) -> UserConfig) {
        if (_currentConfig.value == null) {
            reload()
        }
        val oldConfig = _currentConfig.value ?: UserConfig(
            userId = 0, enabled = true, packageConfigs = emptyList()
        )
        val newConfig = block(oldConfig)
        _currentConfig.value = newConfig
        save()
    }

    private fun requireService(): XposedService {
        return XposedServiceManager.service.value
            ?: error("XposedService is not bound")
    }
}
