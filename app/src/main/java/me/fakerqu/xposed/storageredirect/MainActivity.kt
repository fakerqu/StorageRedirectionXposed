package me.fakerqu.xposed.storageredirect

import android.os.Bundle
import android.os.ParcelFileDescriptor
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.compose.LifecycleResumeEffect
import io.github.libxposed.service.XposedService
import io.github.libxposed.service.XposedServiceHelper
import me.fakerqu.xposed.storageredirect.config.ConfigConstants
import top.yukonga.miuix.kmp.basic.Text
import java.io.File

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            LifecycleResumeEffect(Unit) {
                XposedServiceHelper.registerListener(object :
                    XposedServiceHelper.OnServiceListener {
                    override fun onServiceBind(service: XposedService) {
                        ParcelFileDescriptor.AutoCloseOutputStream(
                            service.openRemoteFile(
                                ConfigConstants.CONFIG_FILE
                            )
                        ).use {
                            val writer = it.writer()
                            writer.write(
                                """
                                {
                                    "userId":0,
                                    "enabled":true,
                                    "packageConfigs":[
                                        {
                                            "packageName":"me.fakerqu.test.storageredirect",
                                            "enabled":true,
                                            "dirConfigs":[
                                                {"relativePath":"DCIM","enabled":true,"mode":"r"}
                                            ]
                                        }
                                    ]
                                }
                            """.trimIndent()
                            )
                            writer.flush()
                        }
                    }

                    override fun onServiceDied(service: XposedService) {
                    }
                })

                onPauseOrDispose { }
            }
        }
    }
}