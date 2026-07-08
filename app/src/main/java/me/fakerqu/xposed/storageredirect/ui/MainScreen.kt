package me.fakerqu.xposed.storageredirect.ui

import android.os.Build
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.HorizontalDivider
import top.yukonga.miuix.kmp.basic.ScrollBehavior
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.basic.Check
import top.yukonga.miuix.kmp.theme.MiuixTheme
import java.io.File

/**
 * 主页内容（不含 Scaffold / TopAppBar，由 HomeScreen 统一提供）
 *
 * @param scrollBehavior 来自 HomeScreen 的滚动行为
 * @param isHooked       LSP 是否已激活
 * @param lspApiVersion  LSP API 版本
 * @param frameworkName  框架名称
 * @param frameworkVersion 框架版本
 */
@Composable
fun MainScreen(
    scrollBehavior: ScrollBehavior,
    isHooked: Boolean,
    lspApiVersion: String,
    frameworkName: String,
    frameworkVersion: String,
) {
    val deviceModel = remember { "${Build.MANUFACTURER} ${Build.MODEL}" }
    val androidVersion = remember { "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})" }
    val kernelVersion = remember { getKernelVersion() }
    val selinuxStatus = remember { getSelinuxStatus() }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .nestedScroll(scrollBehavior.nestedScrollConnection)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Spacer(Modifier.height(4.dp))

        // ========== 状态卡片 ==========
        StatusCard(
            isHooked = isHooked,
            frameworkName = frameworkName,
            frameworkVersion = frameworkVersion,
        )

        // ========== Hook 详情 ==========
        SmallTitle(text = "框架信息")
        Card(modifier = Modifier.fillMaxWidth()) {
            InfoComponent(title = "运行状态", summary = if (isHooked) "已激活" else "未激活")
            HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
            InfoComponent(title = "框架名称", summary = frameworkName)
            HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
            InfoComponent(title = "框架版本", summary = frameworkVersion)
            HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
            InfoComponent(title = "LSP API 版本", summary = lspApiVersion)
        }

        // ========== 设备信息 ==========
        SmallTitle(text = "设备信息")
        Card(modifier = Modifier.fillMaxWidth()) {
            InfoComponent(title = "设备型号", summary = deviceModel)
            HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
            InfoComponent(title = "系统版本", summary = androidVersion)
            HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
            InfoComponent(title = "内核版本", summary = kernelVersion)
            HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
            InfoComponent(title = "SELinux", summary = selinuxStatus)
        }

        Spacer(Modifier.height(16.dp))
    }
}

@Composable
private fun StatusCard(
    isHooked: Boolean,
    frameworkName: String,
    frameworkVersion: String,
) {
    val bgColor = if (isHooked) Color(0xFF1B8F4C) else Color(0xFF8E8E93)
    val dotColor = if (isHooked) Color(0xFF4CD964) else Color(0xFFFF3B30)

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = top.yukonga.miuix.kmp.basic.CardColors(
            color = bgColor,
            contentColor = Color.White,
        ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .background(Color.White.copy(alpha = 0.2f)),
                contentAlignment = Alignment.Center,
            ) {
                Image(
                    imageVector = MiuixIcons.Basic.Check,
                    contentDescription = null,
                    colorFilter = ColorFilter.tint(Color.White),
                    modifier = Modifier.size(28.dp),
                )
            }

            Spacer(Modifier.size(16.dp))

            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(dotColor),
                    )
                    Spacer(Modifier.size(6.dp))
                    Text(
                        text = if (isHooked) "模块已激活" else "模块未激活",
                        color = Color.White,
                        fontSize = MiuixTheme.textStyles.title2.fontSize,
                        fontWeight = FontWeight.Bold,
                    )
                }
                Spacer(Modifier.size(4.dp))
                Text(
                    text = if (isHooked) "$frameworkName $frameworkVersion" else "请通过 LSPosed 启用本模块",
                    color = Color.White.copy(alpha = 0.8f),
                    fontSize = MiuixTheme.textStyles.body2.fontSize,
                )
            }
        }
    }
}

@Composable
private fun InfoComponent(title: String, summary: String) {
    BasicComponent(
        title = title,
        summary = summary,
        modifier = Modifier.fillMaxWidth(),
    )
}

private fun getKernelVersion(): String {
    return runCatching {
        File("/proc/version").bufferedReader().use { it.readLine()?.trim() }
    }.getOrNull() ?: System.getProperty("os.version") ?: "未知"
}

private fun getSelinuxStatus(): String {
    return runCatching {
        ProcessBuilder("getenforce")
            .redirectErrorStream(true)
            .start()
            .inputStream
            .bufferedReader()
            .use { it.readLine()?.trim() }
    }.getOrNull() ?: "未知"
}
