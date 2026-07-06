package me.fakerqu.xposed.storageredirect.hook.query

import android.annotation.SuppressLint
import android.content.ContentResolver
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import android.os.CancellationSignal
import android.provider.MediaStore
import io.github.libxposed.api.XposedInterface
import me.fakerqu.xposed.storageredirect.hook.core.HookContext
import me.fakerqu.xposed.storageredirect.hook.core.HookUtils
import me.fakerqu.xposed.storageredirect.hook.redirect.OverlayHelper
import me.fakerqu.xposed.storageredirect.hook.redirect.PathConverter
import me.fakerqu.xposed.storageredirect.hook.util.CursorDebug
import me.fakerqu.xposed.storageredirect.hook.util.FilteredWrappedCursor
import me.fakerqu.xposed.storageredirect.hook.util.SelectionBinder
import me.fakerqu.xposed.storageredirect.hook.util.SqlPathReplacer
import net.sf.jsqlparser.parser.CCJSqlParserUtil

/**
 * Hook: MediaProvider.queryInternal
 *
 * 核心查询方法，所有 MediaStore 查询均会走到此处。
 *
 * 流程（链式阶段）：
 * 1. [shouldSkip]        — 判断是否需要跳过 hook（forSelf / pickerUri / 特殊 table）
 * 2. [rewriteArgs] — 为 projection 增补 _data 列
 * 3. [rewriteSelection]  — 重写 SQL WHERE 中的路径列
 * 4. [filterResult]      — 包装返回 Cursor，过滤并还原路径
 */
class QueryHook(private val ctx: HookContext) {

    fun install() {
        try {
            val method = ctx.reflection.mediaProviderClass.getDeclaredMethod(
                "queryInternal",
                Uri::class.java,
                Array<String>::class.java,
                Bundle::class.java,
                CancellationSignal::class.java,
                Boolean::class.javaPrimitiveType,
            )
            ctx.hookMethod(method) { chain -> intercept(chain) }
        } catch (e: NoSuchMethodException) {
            ctx.warn("queryInternal not found", e)
        }
    }

    // ---- 主拦截入口 ----

    private fun intercept(chain: XposedInterface.Chain): Any? {
        val uid = ctx.reflection.getCallingUid(chain.thisObject)
        val config = ctx.configFor(uid) ?: return chain.proceed()
        ctx.info("queryInternal start uid=$uid")

        // Phase 1: 检查是否跳过
        if (shouldSkip(chain)) return chain.proceed()

        // Phase 2 & 3: 重写参数
        val originProjection = chain.args[1] as Array<String>?
        val newArgs = rewriteArgs(chain, uid, config, originProjection)

        // 执行查询
        val result = chain.proceedWith(chain.thisObject, newArgs) as Cursor?

        // Phase 4: 过滤结果
        return filterResult(result, originProjection, uid, config)
    }

    // ---- Phase 1: Skip check ----

    private fun shouldSkip(chain: XposedInterface.Chain): Boolean {
        try {
            // forSelf == true → 跳过
            if (chain.args[4] == true) {
                ctx.info("queryInternal bypass: forSelf==true")
                return true
            }

            val callingPackageAllowedHidden =
                ctx.reflection.isCallingPackageAllowedHiddenMethod(chain.thisObject)
            val isCallerPhotoPicker = ctx.reflection.isCallerPhotoPickerMethod(chain.thisObject)

            // picker URI → 跳过
            val isPickerUri =
                ctx.reflection.isPickerUriMethod(chain.thisObject, chain.args[0]) as Boolean
            if (isPickerUri) {
                ctx.info("queryInternal bypass: picker uri ${chain.args[0]}")
                return true
            }

            // 特殊 table → 跳过
            val table = ctx.reflection.matchUriMethod(
                ctx.reflection.mUriMatcherField.get(chain.thisObject),
                chain.args[0],
                callingPackageAllowedHidden,
                isCallerPhotoPicker,
            ) as Int

            if (table in SKIP_TABLES) {
                ctx.info("queryInternal bypass: other uri ${chain.args[0]}-$table")
                return true
            }
        } catch (e: Exception) {
            ctx.error("queryInternal skip check failed", e)
        }
        return false
    }

    // ---- Phase 2 & 3: Rewrite args ----

    @SuppressLint("SdCardPath")
    private fun rewriteArgs(
        chain: XposedInterface.Chain,
        uid: Int,
        config: me.fakerqu.xposed.storageredirect.config.model.RuntimeConfig,
        originProjection: Array<String>?,
    ): Array<Any?> {
        val newArgs = chain.args.toTypedArray()

        try {
            // Phase 2: 增补 _data 列
            if (!originProjection.isNullOrEmpty() && !originProjection.contains(MediaStore.MediaColumns.DATA)) {
                ctx.info("queryInternal: append _data to projection")
                newArgs[1] = arrayOf(*originProjection, MediaStore.MediaColumns.DATA)
            }

            // Phase 3: 重写 SQL selection
            rewriteSelection(chain.args[2] as Bundle?, uid, config)
        } catch (e: Exception) {
            ctx.error("queryInternal rewrite args failed", e)
        }

        return newArgs
    }

    private fun rewriteSelection(
        queryArg: Bundle?,
        uid: Int,
        config: me.fakerqu.xposed.storageredirect.config.model.RuntimeConfig,
    ) {
        queryArg ?: return
        val selection = queryArg.getString(ContentResolver.QUERY_ARG_SQL_SELECTION) ?: return

        val selectionArgs =
            queryArg.getStringArray(ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS) ?: emptyArray()

        val expression = CCJSqlParserUtil.parseCondExpression(
            SelectionBinder.bind(selection, *selectionArgs)
        )
        queryArg.remove(ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS)
        queryArg.putString(
            ContentResolver.QUERY_ARG_SQL_SELECTION,
            expression.accept(
                SqlPathReplacer(listOf("relative_path", "_data")) { originPath ->
                    PathConverter.toFs(HookUtils.getUserId(uid), config, originPath)
                }, null
            ).toString()
        )
    }

    // ---- Phase 4: Filter result ----

    private fun filterResult(
        result: Cursor?,
        originProjection: Array<String>?,
        uid: Int,
        config: me.fakerqu.xposed.storageredirect.config.model.RuntimeConfig,
    ): Cursor? {
        return try {
            if (ctx.configFor(uid) != null) {
                val userId = HookUtils.getUserId(uid)
                val wrapped = FilteredWrappedCursor.wrap(result, originProjection) { originPath ->
                    PathConverter.toApp(
                        userId, config, originPath,
                        fileExists = { directPath -> java.io.File(directPath).exists() },
                        isWhiteouted = { origin ->
                            OverlayHelper.isWhiteouted(
                                userId, config, origin
                            )
                        },
                    )
                }
                ctx.info("queryInternal result: ${CursorDebug.dump(wrapped)}")
                wrapped
            } else {
                result
            }
        } catch (e: Exception) {
            ctx.error("queryInternal filter result failed", e)
            result
        }
    }

    companion object {
        /** 不需要处理的 table ID 集合 */
        private val SKIP_TABLES = setOf(500, 1000, 600, 601, 908, 902, 903, 904, 905)
    }
}
