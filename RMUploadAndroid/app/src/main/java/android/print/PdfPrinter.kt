package android.print

import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import java.io.File

/**
 * Drives a [PrintDocumentAdapter] straight into a file, without the system print dialog.
 * Lives in `android.print` because the result callbacks have package-private constructors.
 */
class PdfPrinter(private val attributes: PrintAttributes) {
    fun print(adapter: PrintDocumentAdapter, file: File, onDone: (Throwable?) -> Unit) {
        adapter.onStart()
        adapter.onLayout(null, attributes, null, object : PrintDocumentAdapter.LayoutResultCallback() {
            override fun onLayoutFinished(info: PrintDocumentInfo, changed: Boolean) {
                val fd = ParcelFileDescriptor.open(
                    file,
                    ParcelFileDescriptor.MODE_CREATE or ParcelFileDescriptor.MODE_TRUNCATE or ParcelFileDescriptor.MODE_READ_WRITE,
                )
                adapter.onWrite(arrayOf(PageRange.ALL_PAGES), fd, CancellationSignal(), object : PrintDocumentAdapter.WriteResultCallback() {
                    override fun onWriteFinished(pages: Array<PageRange>) {
                        fd.close()
                        adapter.onFinish()
                        onDone(null)
                    }

                    override fun onWriteFailed(error: CharSequence?) {
                        fd.close()
                        adapter.onFinish()
                        onDone(IllegalStateException(error?.toString() ?: "PDF write failed"))
                    }

                    override fun onWriteCancelled() {
                        fd.close()
                        adapter.onFinish()
                        onDone(IllegalStateException("PDF write cancelled"))
                    }
                })
            }

            override fun onLayoutFailed(error: CharSequence?) {
                adapter.onFinish()
                onDone(IllegalStateException(error?.toString() ?: "PDF layout failed"))
            }

            override fun onLayoutCancelled() {
                adapter.onFinish()
                onDone(IllegalStateException("PDF layout cancelled"))
            }
        }, null)
    }
}
