package com.notesis.benchmark

import android.content.Intent
import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.FrameTimingMetric
import androidx.benchmark.macro.StartupMode
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class InkRenderingBenchmark {
    @get:Rule val rule = MacrobenchmarkRule()
    @Test fun ink500() = exercise(500)
    @Test fun ink1500() = exercise(1500)
    @Test fun ink5000() = exercise(5000)
    @Test fun ink10000() = exercise(10000)
    private fun exercise(count: Int) = rule.measureRepeated(
        packageName = PACKAGE_NAME,
        metrics = listOf(FrameTimingMetric()),
        compilationMode = CompilationMode.Partial(),
        startupMode = StartupMode.WARM,
        iterations = 5,
        setupBlock = {
            startActivityAndWait(Intent().setClassName(PACKAGE_NAME, "com.notesis.InkFixtureActivity")
                .putExtra("strokes", count))
            check(device.wait(Until.hasObject(By.desc("Ink fixture $count")), 120000))
            device.waitForIdle()
        },
    ) {
        val ink = device.findObject(By.desc("Ink fixture $count"))
        ink.pinchOpen(.7f, 1000)
        val width = device.displayWidth; val height = device.displayHeight
        device.swipe(width / 2, height * 3 / 4, width / 2, height / 3, 60) // drag
        device.swipe(width / 2, height * 3 / 4, width / 2, height / 3, 5) // fling
        ink.pinchClose(.7f, 1000)
        repeat(4) { device.findObject(By.desc("Next page")).click() }
        device.waitForIdle()
    }
}
