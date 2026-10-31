package com.drjgouveia.rmupload

import android.annotation.SuppressLint
import android.content.Context
import android.print.PdfPrinter
import android.print.PrintAttributes
import android.view.View
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.json.JSONTokener
import java.io.File

/** Loads a webpage off-screen and prints it into a paginated PDF. */
object WebPageRenderer {
    // 3:4 like the reMarkable screen, A4 width (in mils).
    private val attributes = PrintAttributes.Builder()
        .setMediaSize(PrintAttributes.MediaSize("remarkable", "reMarkable", 8264, 11014))
        .setResolution(PrintAttributes.Resolution("pdf", "pdf", 300, 300))
        .setMinMargins(PrintAttributes.Margins(500, 500, 500, 500))
        .build()

    /**
     * With [readerMode], keeps only the article text and images (like the Chrome extension),
     * falling back to the full page when no article is found.
     */
    @SuppressLint("SetJavaScriptEnabled")
    suspend fun renderPDF(context: Context, url: String, readerMode: Boolean): Pair<String, ByteArray> =
        withContext(Dispatchers.Main) {
            var loaded = CompletableDeferred<Unit>()
            val webView = WebView(context).apply {
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView, url: String?) {
                        loaded.complete(Unit)
                    }

                    override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                        if (request.isForMainFrame) {
                            loaded.completeExceptionally(IllegalStateException("Couldn't load page: ${error.description}"))
                        }
                    }
                }
                // Give the off-screen view a phone-sized layout.
                measure(
                    View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(1920, View.MeasureSpec.EXACTLY),
                )
                layout(0, 0, 1080, 1920)
            }
            try {
                webView.loadUrl(url)
                loaded.await()
                // Give lazy-loaded images and late layout a moment to settle.
                delay(1500)

                var title = webView.title?.takeIf { it.isNotBlank() && it != url }
                    ?: android.net.Uri.parse(url).host ?: "Webpage"
                if (readerMode) {
                    extractArticle(context, webView)?.let { (articleTitle, html) ->
                        title = articleTitle
                        loaded = CompletableDeferred()
                        webView.loadDataWithBaseURL(url, html, "text/html", "utf-8", url)
                        loaded.await()
                        delay(1500)
                    }
                }

                val file = File.createTempFile("page", ".pdf", context.cacheDir)
                try {
                    val done = CompletableDeferred<Unit>()
                    PdfPrinter(attributes).print(webView.createPrintDocumentAdapter(title), file) { error ->
                        if (error == null) done.complete(Unit) else done.completeExceptionally(error)
                    }
                    done.await()
                    val data = withContext(Dispatchers.IO) { file.readBytes() }
                    if (data.isEmpty()) throw IllegalStateException("The page rendered no content.")
                    title to data
                } finally {
                    file.delete()
                }
            } finally {
                webView.destroy()
            }
        }

    /** Runs Readability + DOMPurify (assets/reader.js) in the page; null when there's no article. */
    private suspend fun extractArticle(context: Context, webView: WebView): Pair<String, String>? {
        val script = withContext(Dispatchers.IO) {
            context.assets.open("reader.js").bufferedReader().use { it.readText() }
        }
        val result = CompletableDeferred<String?>()
        webView.evaluateJavascript("$script\n__rmuploadArticle() || \"\";") { result.complete(it) }
        // The callback gets the return value JSON-encoded, i.e. a quoted string.
        val json = runCatching { JSONTokener(result.await()).nextValue() as? String }.getOrNull()
        if (json.isNullOrEmpty()) return null
        return runCatching { JSONObject(json).let { it.getString("title") to it.getString("html") } }.getOrNull()
    }
}
