package android.print

import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import java.io.File

/**
 * Writes what a [PrintDocumentAdapter] prints - here a WebView's - straight to
 * [file], without the system's print screen. The callbacks' constructors are
 * hidden from apps, but public in the framework, so a class in this package
 * can make them. Where a device refuses that, the caller falls back to the
 * system print screen and its Save as PDF.
 */
object NotesisPdfPrint {
    fun write(adapter: PrintDocumentAdapter, attributes: PrintAttributes, file: File, done: (Boolean) -> Unit) {
        val cancel = CancellationSignal()
        adapter.onStart()
        adapter.onLayout(null, attributes, cancel, object : PrintDocumentAdapter.LayoutResultCallback() {
            override fun onLayoutFinished(info: PrintDocumentInfo?, changed: Boolean) {
                val descriptor = try {
                    ParcelFileDescriptor.open(file,
                        ParcelFileDescriptor.MODE_CREATE or ParcelFileDescriptor.MODE_TRUNCATE or ParcelFileDescriptor.MODE_READ_WRITE)
                } catch (error: Exception) {
                    adapter.onFinish()
                    done(false)
                    return
                }
                adapter.onWrite(arrayOf(PageRange.ALL_PAGES), descriptor, cancel, object : PrintDocumentAdapter.WriteResultCallback() {
                    override fun onWriteFinished(pages: Array<out PageRange>?) = finish(true)
                    override fun onWriteFailed(error: CharSequence?) = finish(false)
                    override fun onWriteCancelled() = finish(false)

                    private fun finish(ok: Boolean) {
                        runCatching { descriptor.close() }
                        adapter.onFinish()
                        done(ok)
                    }
                })
            }

            override fun onLayoutFailed(error: CharSequence?) {
                adapter.onFinish()
                done(false)
            }

            override fun onLayoutCancelled() {
                adapter.onFinish()
                done(false)
            }
        }, null)
    }
}
