package me.fakerqu.xposed.storageredirect.hook.util

import net.sf.jsqlparser.expression.StringValue
import net.sf.jsqlparser.expression.operators.relational.EqualsTo
import net.sf.jsqlparser.expression.operators.relational.LikeExpression
import net.sf.jsqlparser.schema.Column
import net.sf.jsqlparser.util.deparser.ExpressionDeParser

/**
 * SQL WHERE 子句中路径列重写器。
 *
 * 遍历 AST 中的 EqualsTo / LikeExpression 节点，
 * 对指定列（如 `_data`、`relative_path`）的值调用 [replaceMethod] 进行替换，
 * 将单条条件展开为多条 OR / AND 条件。
 *
 * @param columnNames  需要拦截的列名
 * @param replaceMethod 接收原始路径，返回替换后的路径列表
 */
class SqlPathReplacer(
    private val columnNames: List<String>,
    private val replaceMethod: (originPath: String) -> List<String>,
) : ExpressionDeParser() {

    override fun <S> visit(equalsTo: EqualsTo?, context: S): StringBuilder {
        val left = equalsTo?.leftExpression
        val right = equalsTo?.rightExpression
        if (left is Column && columnNames.contains(left.columnName)) {
            builder.append("(")
            if (right is StringValue) {
                replaceMethod(right.value).foldIndexed(builder) { index, builder, replaced ->
                    if (index >= 1) builder.append(" OR ")
                    super.visit(EqualsTo(left, StringValue(replaced)), context)
                    builder
                }
            }
            builder.append(")")
            return builder
        }
        return super.visit(equalsTo, context)
    }

    override fun <S> visit(
        likeExpression: LikeExpression?,
        context: S,
    ): java.lang.StringBuilder {
        val left = likeExpression?.leftExpression
        val right = likeExpression?.rightExpression
        if (left is Column && columnNames.contains(left.columnName)) {
            builder.append("(")
            if (right is StringValue) {
                replaceMethod(right.value).foldIndexed(builder) { index, builder, replaced ->
                    if (index >= 1) {
                        if (likeExpression.isNot) builder.append(" AND ")
                        else builder.append(" OR ")
                    }
                    super.visit(EqualsTo(left, StringValue(replaced)), context)
                    builder
                }
            }
            builder.append(")")
            return builder
        }
        return super.visit(likeExpression, context)
    }
}
