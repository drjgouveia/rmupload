package com.drjgouveia.rmupload

import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

sealed class UploadError(message: String) : Exception(message) {
    class Unauthorized : UploadError("Not authorized. Please log in again.")
    class Http(status: Int, msg: String) : UploadError("Upload failed ($status): $msg")
}

/** Port of `DocumentStorage.importFile` from `@remarkable/document-storage`. */
object RemarkableClient {
    suspend fun importFile(
        name: String,
        contentType: String,
        data: ByteArray,
        convertToNotebook: Boolean,
        accessToken: String,
        tectonic: String?,
    ) = withContext(Dispatchers.IO) {
        val meta = JSONObject().put("parent", "").put("file_name", name)
        if (convertToNotebook) {
            meta.put("orientation", "portrait").put("convert", true)
        }
        val metaHeader = Base64.encodeToString(meta.toString().toByteArray(), Base64.NO_WRAP)

        val conn = URL("${Config.apiURL(tectonic)}/import/v1/files").openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.setFixedLengthStreamingMode(data.size)
            conn.setRequestProperty("Authorization", "Bearer $accessToken")
            conn.setRequestProperty("rM-Source", Config.APP_NAME)
            conn.setRequestProperty("rM-Meta", metaHeader)
            conn.setRequestProperty("Content-Type", contentType)
            conn.outputStream.use { it.write(data) }

            val status = conn.responseCode
            if (status !in listOf(200, 201, 304)) {
                if (status == 401) throw UploadError.Unauthorized()
                val body = conn.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
                val message = runCatching { JSONObject(body).optStringOrNull("message") }.getOrNull()
                throw UploadError.Http(status, message ?: body)
            }
        } finally {
            conn.disconnect()
        }
    }
}
