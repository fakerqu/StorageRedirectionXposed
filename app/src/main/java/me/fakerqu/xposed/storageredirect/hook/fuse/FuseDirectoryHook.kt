package me.fakerqu.xposed.storageredirect.hook.fuse

import android.annotation.SuppressLint
import io.github.libxposed.api.XposedInterface
import me.fakerqu.xposed.storageredirect.config.model.DirMode
import me.fakerqu.xposed.storageredirect.hook.core.HookContext
import me.fakerqu.xposed.storageredirect.hook.core.HookUtils
import me.fakerqu.xposed.storageredirect.hook.redirect.PathConverter
import java.io.File

/**
 * Hook: MediaProvider.getFilesInDirectoryForFuse(String path, int uid)
 *
 * FUSE readdir 回调，返回 String[] 文件名列表。
 *
 * 根据 mode 决定行为：
 * - w 模式：透传
 * - n 模式：从 Upper 层读取（根目录过滤 NONE 子目录 + 合并 Upper 文件）
 * - r 模式：合并 Lower + Upper 文件名，过滤 whiteout
 */
class FuseDirectoryHook(private val ctx: HookContext) {

    fun install() {
        try {
            val method = ctx.reflection.mediaProviderClass.getDeclaredMethod(
                "getFilesInDirectoryForFuse",
                String::class.java,
                Int::class.javaPrimitiveType,
            )
            ctx.hookMethod(method) { chain -> intercept(chain) }
        } catch (e: NoSuchMethodException) {
            ctx.warn("getFilesInDirectoryForFuse not found", e)
        }
    }

    private fun intercept(chain: XposedInterface.Chain): Any? {
        val path = chain.args[0] as String
        val uid = chain.args[1] as Int
        val config = ctx.configFor(uid) ?: return chain.proceed()

        val userId = HookUtils.getUserId(uid)
        if (!PathConverter.pathNeedRedirect(userId, path)) return chain.proceed()

        val mode = PathConverter.resolveMode(config, userId, path)
        ctx.info("getFilesInDirectoryForFuse path=$path uid=$uid mode=$mode")

        return try {
            when (mode) {
                DirMode.WRITE -> chain.proceed()
                DirMode.NONE -> handleNoneMode(chain, userId, config, path)
                DirMode.READ -> handleReadMode(chain, userId, config, path)
            }
        } catch (e: Exception) {
            ctx.error("getFilesInDirectoryForFuse failed", e)
            chain.proceed()
        }
    }

    // ---- NONE mode ----

    @SuppressLint("SdCardPath")
    private fun handleNoneMode(
        chain: XposedInterface.Chain,
        userId: Int,
        config: me.fakerqu.xposed.storageredirect.config.model.RuntimeConfig,
        path: String,
    ): Array<String> {
        val relativePath = PathConverter.toRelativePath(userId, path)

        return if (relativePath.isEmpty()) {
            // 根目录：过滤 NONE 子目录 + 合并 Upper 层文件
            filterRootDirectory(chain, userId, config, path)
        } else {
            // NONE 子目录：直接从 Upper 层读取
            readUpperDirectory(userId, config, path)
        }
    }

    private fun filterRootDirectory(
        chain: XposedInterface.Chain,
        userId: Int,
        config: me.fakerqu.xposed.storageredirect.config.model.RuntimeConfig,
        path: String,
    ): Array<String> {
        val originalNames = (chain.proceed() as? Array<*>)
            ?.filterIsInstance<String>()
            ?: emptyList()

        val basePath = path.trimEnd('/')
        val filtered = originalNames.filter { name ->
            val childPath = "$basePath/$name"
            val childMode = PathConverter.resolveMode(config, userId, childPath)
            childMode != DirMode.NONE
        }.toMutableSet()

        // 合并 Upper 层文件
        val upperPath = PathConverter.getUpperPath(userId, config, path)
        val upperDir = File(upperPath)
        if (upperDir.exists()) {
            upperDir.listFiles()?.forEach { f ->
                if (!f.name.startsWith(".wh.")) filtered.add(f.name)
            }
        }

        ctx.info("getFilesInDirectoryForFuse root: original=${originalNames.size}, filtered=${filtered.size}")
        return filtered.toTypedArray()
    }

    private fun readUpperDirectory(
        userId: Int,
        config: me.fakerqu.xposed.storageredirect.config.model.RuntimeConfig,
        path: String,
    ): Array<String> {
        val upperPath = PathConverter.getUpperPath(userId, config, path)
        val upperDir = File(upperPath)
        return if (upperDir.exists()) {
            val names = upperDir.listFiles()
                ?.map { it.name }
                ?.filter { !it.startsWith(".wh.") }
                ?: emptyList()
            ctx.info("getFilesInDirectoryForFuse n-mode fs: upper=$upperPath, count=${names.size}")
            names.toTypedArray()
        } else {
            emptyArray()
        }
    }

    // ---- READ mode ----

    private fun handleReadMode(
        chain: XposedInterface.Chain,
        userId: Int,
        config: me.fakerqu.xposed.storageredirect.config.model.RuntimeConfig,
        path: String,
    ): Array<String> {
        // 1. Lower 层文件名（FUSE 原始方法）
        val lowerNames = (chain.proceed() as? Array<*>)
            ?.filterIsInstance<String>()
            ?: emptyList()

        // 2. Upper 层文件名
        val upperPath = PathConverter.getUpperPath(userId, config, path)
        val upperDir = File(upperPath)
        val upperNames = if (upperDir.exists()) {
            upperDir.listFiles()?.map { it.name } ?: emptyList()
        } else {
            emptyList()
        }

        // 3. 提取 whiteout 名单
        val whiteoutNames = upperNames
            .filter { it.startsWith(".wh.") }
            .map { it.removePrefix(".wh.") }
            .toSet()

        // 4. Lower 层物理条目（判断 Upper 条目是否独有）
        val lowerDirectPath = PathConverter.toDirectPath(userId, path)
        val lowerDir = File(lowerDirectPath)
        val lowerPhysicalNames = if (lowerDir.exists()) {
            lowerDir.listFiles()?.map { it.name }?.toSet() ?: emptySet()
        } else {
            emptySet()
        }

        // 5. 合并
        val result = LinkedHashSet<String>()
        for (name in lowerNames) {
            if (name !in whiteoutNames) result.add(name)
        }
        for (name in upperNames) {
            if (!name.startsWith(".wh.") && name !in lowerPhysicalNames && name !in result) {
                result.add(name)
            }
        }

        ctx.info("getFilesInDirectoryForFuse r-mode: lower=${lowerNames.size}, upper=${upperNames.size}, whiteouts=${whiteoutNames.size}, merged=${result.size}")
        return result.toTypedArray()
    }
}
