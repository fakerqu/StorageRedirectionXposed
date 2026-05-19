package me.fakerqu.xposed.storageredirect.hook

import android.annotation.SuppressLint
import me.fakerqu.xposed.storageredirect.config.model.DirMode
import me.fakerqu.xposed.storageredirect.config.model.RuntimeConfig
import java.io.File
import kotlin.io.path.Path
import kotlin.io.path.name
import kotlin.io.path.pathString

object PathConverter {

    const val PATH_FILTER_OUT = "path_filter_out"
    private val mediaPaths =
        listOf(Path("DCIM"), Path("Pictures"), Path("Movies"), Path("Music"), Path("Audio"))
    private val redirectionPath = Path("sdcard_redirect")

    private fun isMediaDir(dir: String): Boolean {
        val dirPath = Path(dir)
        return mediaPaths.any { dirPath.contains(it) }
    }

    private fun isRedirectionDir(dir: String): Boolean {
        val dirPath = Path(dir)
        return redirectionPath.contains(dirPath)
    }

    @SuppressLint("SdCardPath")
    private fun pathNeedRedirect(currentUserId: Int, path: String): Boolean {
        return (path.startsWith("/sdcard") && !path.startsWith("/sdcard/Android"))
                || (path.startsWith("/storage/emulated/$currentUserId") && !path.startsWith("/storage/emulated/$currentUserId/Android"))
    }


    @SuppressLint("SdCardPath")
    fun toFs(currentUserId: Int, config: RuntimeConfig, originPath: String): List<String> {
        if (originPath.startsWith("/data") || !pathNeedRedirect(currentUserId, originPath)) {
            return listOf(originPath)
        }
        val relativePath =
            originPath
                .substringAfter("/sdcard/")
                .substringAfter("/sdcard")
                .substringAfter("/storage/emulated/$currentUserId/")
                .substringAfter("/storage/emulated/$currentUserId")

        val matchedConfig = config.dirConfigs.filter {
            it.enabled && relativePath.startsWith(it.relativePath)
        }.maxByOrNull { it.relativePath.length }
        val replacedPath = if (isMediaDir(originPath)) {
            "Android/media/${config.uidName}/sdcard_redirect/${relativePath}"
        } else {
            "Android/data/${config.uidName}/files/sdcard_redirect/${relativePath}"
        }.trimEnd('/')
        val replacedPaths = when (matchedConfig?.mode) {
            DirMode.WRITE -> listOf(relativePath)
            DirMode.READ -> listOf(relativePath, replacedPath)
            DirMode.NONE, null -> listOf(replacedPath)
        }
        return if (originPath.startsWith("/")) {
            replacedPaths.map { "/storage/emulated/$currentUserId/${it}" }
        } else {
            replacedPaths
        }
    }

    @SuppressLint("SdCardPath")
    fun toApp(currentUserId: Int, config: RuntimeConfig, redirectPath: String): String {
        val cleanPath = redirectPath.trim()
        if (cleanPath.isEmpty()) return cleanPath
        val originFile = Path(redirectPath).normalize()

        //white out文件，直接返回已过滤
        if (originFile.name.startsWith(".wh.")) {
            return PATH_FILTER_OUT
        }
        //并非为重定向后的路径
        if (!isRedirectionDir(redirectPath)) {
            //查一下，原始文件是否存在whiteout，存在则返回filter out
            val whiteOutFilePath = originFile.resolveSibling(".wh.${originFile.name}")
            val whiteOutBase =
                "/storage/emulated/${currentUserId}/Android/data/${config.uidName}/files/sdcard_redirect"
            val whiteOutFile = if (whiteOutFilePath.isAbsolute) {
                File(
                    whiteOutFilePath.pathString.replace(
                        "/sdcard",
                        whiteOutBase
                    ).replace("/storage/emulated/${currentUserId}", whiteOutBase)
                )
            } else {
                Path(whiteOutBase).resolve(whiteOutFilePath).toFile()
            }

            if (whiteOutFile.exists()) {
                return PATH_FILTER_OUT
            }
            return cleanPath
        } else {
            //将重定向路径增加的部分，替换为空
            val originPath =
                if (cleanPath.contains("Android/media/${config.uidName}/sdcard_redirect") || cleanPath.contains(
                        "Android/data/${config.uidName}/files/sdcard_redirect"
                    )
                ) {
                    //重定向路径，转化为原始路径,去除重定向前缀
                    cleanPath
                        .replace("Android/media/${config.uidName}/sdcard_redirect/", "")
                        .replace("Android/media/${config.uidName}/sdcard_redirect", "")
                        .replace("Android/data/${config.uidName}/files/sdcard_redirect/", "")
                        .replace("Android/data/${config.uidName}/files/sdcard_redirect", "")
                } else {
                    //非重定向后路径，不做处理
                    cleanPath
                }
            return originPath
        }
    }
}