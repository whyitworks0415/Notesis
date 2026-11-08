package com.notesis

import android.graphics.Bitmap
import android.graphics.Color
import android.os.Looper
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CanvasImagePreviewsTest {
    @get:Rule val temp = TemporaryFolder()

    private fun picture(name: String, color: Int): File {
        val file = temp.newFile(name)
        val bitmap = Bitmap.createBitmap(256, 256, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(color)
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
        return file
    }

    private fun awaitBitmap(lookup: () -> Bitmap?): Bitmap {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (System.nanoTime() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            lookup()?.let { return it }
            Thread.sleep(10)
        }
        error("Image preview did not finish")
    }

    @Test fun drawRequestsNeverWaitForFileWorkAndOriginalsKeepTheirResolution() {
        val photo = picture("photo.png", Color.RED)
        val entered = CountDownLatch(1)
        val allow = CountDownLatch(1)
        val onMain = AtomicBoolean(false)
        var notifications = 0
        val previews = CanvasImagePreviews(64 * 1024, file = {
            onMain.set(Looper.myLooper() == Looper.getMainLooper())
            entered.countDown()
            check(allow.await(10, TimeUnit.SECONDS))
            photo
        }, onReady = { notifications++ })
        try {
            // The worker cannot finish until after this call has returned.
            assertNull(previews.image("photo"))
            assertTrue(entered.await(10, TimeUnit.SECONDS))
            assertFalse(onMain.get())
            allow.countDown()
            val small = awaitBitmap { previews.image("photo") }
            assertTrue(small.allocationByteCount <= 32 * 1024)
            assertEquals(Color.RED, small.getPixel(0, 0))
            val original = previews.originalImage("photo")!!
            assertEquals(256, original.width)
            assertEquals(256, original.height)
            original.recycle()
            shadowOf(Looper.getMainLooper()).idle()
            assertTrue(notifications > 0)
            previews.close()
            assertNull(previews.image("photo"))
        } finally { allow.countDown(); previews.close() }
    }

    @Test fun imageAndPaperIdsAndDifferentNotesDoNotSharePixels() {
        val red = picture("red.png", Color.RED)
        val blue = picture("blue.png", Color.BLUE)
        val first = CanvasImagePreviews(1024 * 1024,
            file = { if (it.startsWith("t:")) blue else red }, onReady = {})
        try {
            assertEquals(Color.RED, awaitBitmap { first.image("same-id") }.getPixel(0, 0))
            assertEquals(Color.BLUE, awaitBitmap { first.template("same-id") }.getPixel(0, 0))
            first.close()
            CanvasImagePreviews(1024 * 1024, file = { blue }, onReady = {}).use { second ->
                assertEquals(Color.BLUE, awaitBitmap { second.image("same-id") }.getPixel(0, 0))
            }
        } finally { first.close() }
    }
}
