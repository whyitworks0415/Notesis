package com.notesis

import android.app.Instrumentation
import android.content.Intent
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.io.File

internal fun Instrumentation.checkReiconUi() {
    val icons = Reicons::class.java.declaredMethods.filter {
        it.name.startsWith("get") && it.returnType == ImageVector::class.java
    }.sortedBy { it.name }.map { it.name.removePrefix("get") to (it.invoke(Reicons) as ImageVector) }
    check(icons.size == 61)
    check(icons.all { it.second.name.startsWith("Reicon.") && it.second.root.size > 0 })
    val activity = startActivitySync(Intent(targetContext, MainActivity::class.java)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
    runOnMainSync { activity.window.addFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE) }
    try {
        runOnMainSync {
            activity.setContent {
                MaterialTheme {
                    Surface(Modifier.fillMaxSize()) {
                        Column(Modifier.padding(24.dp)) {
                            icons.chunked(8).forEach { row ->
                                Row(Modifier.fillMaxWidth().weight(1f)) {
                                    row.forEach { (label, icon) ->
                                        Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                                            Icon(icon, label, Modifier.size(32.dp))
                                            Text(label, fontSize = 10.sp)
                                        }
                                    }
                                    repeat(8 - row.size) { Spacer(Modifier.weight(1f)) }
                                }
                            }
                        }
                    }
                }
            }
        }
        waitForIdleSync(); Thread.sleep(300)
        uiAutomation.takeScreenshot()?.let { bitmap ->
            File(targetContext.filesDir, "reicon-gallery.png").outputStream().use {
                bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
            }
            bitmap.recycle()
        }
    } finally { runOnMainSync { activity.finish() } }
}
