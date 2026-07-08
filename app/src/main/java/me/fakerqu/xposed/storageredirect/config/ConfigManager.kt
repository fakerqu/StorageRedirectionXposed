package me.fakerqu.xposed.storageredirect.config

import android.os.ParcelFileDescriptor
import io.github.libxposed.service.XposedService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromStream
import kotlinx.serialization.json.encodeToStream
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
 * 所有写操作都会同步持久化到远程文件。
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
    fun getConfiguredPackageNames(): Set<String> {
        if (_currentConfig.value == null) {
            runCatching { reload() }
        }
        return _currentConfig.value
            ?.packageConfigs
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
    fun reload() {
        val service = requireService()
        service.openRemoteFile(ConfigConstants.CONFIG_FILE).use { pfd ->
            ParcelFileDescriptor.AutoCloseInputStream(pfd).use { input ->
                _currentConfig.value = json.decodeFromStream<UserConfig>(input)
            }
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
    private fun save() {
        val service = requireService()
        val config = _currentConfig.value ?: error("ConfigManager.save() called before load()")
        service.openRemoteFile(ConfigConstants.CONFIG_FILE).use { pfd ->
            ParcelFileDescriptor.AutoCloseOutputStream(pfd).use { output ->
                json.encodeToStream(config, output)
            }
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
    fun upsertPackageConfig(config: PackageConfig) {
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
    fun removePackageConfig(packageName: String): Boolean {
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
    fun setPackageEnabled(packageName: String, enabled: Boolean): Boolean {
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
    fun addDirConfig(packageName: String, dirConfig: DirConfig) {
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
    fun updateDirConfig(packageName: String, relativePath: String, newConfig: DirConfig): Boolean {
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
    fun removeDirConfig(packageName: String, relativePath: String): Boolean {
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
    private inline fun updateConfig(block: (UserConfig) -> UserConfig) {
        if (_currentConfig.value == null) {
            reload()
        }
        val oldConfig = _currentConfig.value ?: error("Failed to load config")
        val newConfig = block(oldConfig)
        _currentConfig.value = newConfig
        save()
    }

    private fun requireService(): XposedService {
        return XposedServiceManager.service.value
            ?: error("XposedService is not bound")
    }
}
