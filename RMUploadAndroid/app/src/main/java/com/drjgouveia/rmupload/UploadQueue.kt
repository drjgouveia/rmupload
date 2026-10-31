package com.drjgouveia.rmupload

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.util.UUID

data class UploadItem(
    val name: String,
    val contentType: String,
    val data: ByteArray,
    val status: Status = Status.Pending,
    val id: String = UUID.randomUUID().toString(),
) {
    sealed interface Status {
        data object Rendering : Status
        data object Pending : Status
        data object Uploading : Status
        data object Done : Status
        data class Failed(val message: String) : Status
    }

    val isEPUB: Boolean get() = contentType == EPUB

    val isUploadable: Boolean get() = status == Status.Pending || status is Status.Failed
}

private const val PDF = "application/pdf"
private const val EPUB = "application/epub+zip"

class UploadQueue(private val context: Context, private val scope: CoroutineScope) {
    companion object {
        val supportedTypes = arrayOf(PDF, EPUB)

        /** Accepts "example.com/article" as well as full http(s) URLs. */
        fun webURL(text: String): String? {
            val trimmed = text.trim()
            val uri = Uri.parse(if ("://" in trimmed) trimmed else "https://$trimmed")
            return uri.takeIf { it.scheme?.lowercase() in listOf("http", "https") && !it.host.isNullOrEmpty() }
                ?.toString()
        }

        /** Pulls the first link out of shared text such as "Article title https://…". */
        fun webURLInText(text: String): String? =
            Regex("""https?://\S+""").find(text)?.value?.let(::webURL)
    }

    val items = mutableStateListOf<UploadItem>()
    var convertEPUBToNotebook by mutableStateOf(false)
    var readerMode by mutableStateOf(true)
    var lastError by mutableStateOf<String?>(null)

    val isUploading: Boolean get() = items.any { it.status == UploadItem.Status.Uploading }
    val hasPending: Boolean get() = items.any { it.isUploadable }

    /** Copies the file contents into memory so the content URI permission can lapse. */
    fun add(resolver: ContentResolver, uris: List<Uri>) {
        for (uri in uris) {
            val name = displayName(resolver, uri) ?: uri.lastPathSegment ?: "Untitled"
            val type = when {
                name.endsWith(".pdf", ignoreCase = true) -> PDF
                name.endsWith(".epub", ignoreCase = true) -> EPUB
                else -> resolver.getType(uri)
            }
            if (type !in supportedTypes) {
                lastError = "$name: only PDF and EPUB files are supported."
                continue
            }
            try {
                val data = resolver.openInputStream(uri)?.use { it.readBytes() }
                    ?: throw IllegalStateException("could not open file")
                items.add(UploadItem(name = name, contentType = type!!, data = data))
            } catch (e: Exception) {
                lastError = "$name: ${e.message}"
            }
        }
    }

    /** Queues a webpage, rendering it to PDF in the background. */
    fun addWebpage(url: String) {
        val item = UploadItem(
            name = Uri.parse(url).host ?: url,
            contentType = PDF,
            data = ByteArray(0),
            status = UploadItem.Status.Rendering,
        )
        items.add(item)
        scope.launch {
            try {
                val (title, data) = WebPageRenderer.renderPDF(context, url, readerMode)
                val index = items.indexOfFirst { it.id == item.id }
                if (index >= 0) {
                    items[index] = items[index].copy(name = fileName(title), data = data, status = UploadItem.Status.Pending)
                }
            } catch (e: Exception) {
                items.removeAll { it.id == item.id }
                lastError = "$url: ${e.message}"
            }
        }
    }

    fun remove(item: UploadItem) {
        items.removeAll { it.id == item.id }
    }

    fun clearFinished() {
        items.removeAll { it.status == UploadItem.Status.Done }
    }

    suspend fun uploadAll(auth: AuthService) {
        for (index in items.indices) {
            if (!items[index].isUploadable) continue
            items[index] = items[index].copy(status = UploadItem.Status.Uploading)
            try {
                upload(items[index], auth)
                items[index] = items[index].copy(status = UploadItem.Status.Done)
            } catch (e: Exception) {
                items[index] = items[index].copy(status = UploadItem.Status.Failed(e.message ?: e.toString()))
                if (e is AuthError.NotLoggedIn) break
            }
        }
    }

    private suspend fun upload(item: UploadItem, auth: AuthService) {
        val convert = item.isEPUB && convertEPUBToNotebook

        suspend fun send(token: String) = RemarkableClient.importFile(
            name = item.name,
            contentType = item.contentType,
            data = item.data,
            convertToNotebook = convert,
            accessToken = token,
            tectonic = auth.session?.tectonic,
        )

        try {
            send(auth.accessToken())
        } catch (e: UploadError.Unauthorized) {
            send(auth.accessToken(forceRefresh = true))
        }
    }

    private fun fileName(title: String): String {
        val cleaned = title.replace(Regex("""[/\\:]"""), "-").trim()
        return cleaned.ifEmpty { "Webpage" }.take(120) + ".pdf"
    }

    private fun displayName(resolver: ContentResolver, uri: Uri): String? =
        runCatching {
            resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0) else null
            }
        }.getOrNull()
}
