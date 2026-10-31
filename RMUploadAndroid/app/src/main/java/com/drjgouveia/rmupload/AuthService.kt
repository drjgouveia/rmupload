package com.drjgouveia.rmupload

import android.content.Context
import android.net.Uri
import android.util.Base64
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest
import java.security.SecureRandom

data class Session(
    val accessToken: String,
    val refreshToken: String?,
    val expiresAt: Long,
    val email: String?,
    val tectonic: String?,
) {
    fun toJson(): String = JSONObject()
        .put("accessToken", accessToken)
        .put("refreshToken", refreshToken)
        .put("expiresAt", expiresAt)
        .put("email", email)
        .put("tectonic", tectonic)
        .toString()

    companion object {
        fun fromJson(json: String): Session = JSONObject(json).let {
            Session(
                accessToken = it.getString("accessToken"),
                refreshToken = it.optStringOrNull("refreshToken"),
                expiresAt = it.getLong("expiresAt"),
                email = it.optStringOrNull("email"),
                tectonic = it.optStringOrNull("tectonic"),
            )
        }
    }
}

sealed class AuthError(message: String) : Exception(message) {
    class NotLoggedIn : AuthError("Please log in again.")
    class BadCallback(msg: String) : AuthError("Login failed: $msg")
    class TokenRequest(val body: String) : AuthError("Token request failed: $body")
}

/** State of one login attempt: the URL to open and what the callback must match. */
class PendingLogin(val url: String, val state: String, val verifier: String)

/** Auth0 authorization code + PKCE flow, mirroring what auth0-spa-js does in the extension. */
class AuthService(context: Context) {
    private val store = SecureStore(context)
    private val refreshLock = Mutex()

    var session by mutableStateOf(store.load(SESSION_KEY)?.let { runCatching { Session.fromJson(it) }.getOrNull() })
        private set

    val isLoggedIn: Boolean get() = session != null

    fun beginLogin(): PendingLogin {
        val verifier = randomString()
        val challenge = base64URL(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray()))
        val state = randomString()

        val url = Uri.parse(Config.AUTH0_DOMAIN).buildUpon()
            .appendPath("authorize")
            .appendQueryParameter("client_id", Config.AUTH0_CLIENT_ID)
            .appendQueryParameter("response_type", "code")
            .appendQueryParameter("response_mode", "query")
            .appendQueryParameter("redirect_uri", Config.REDIRECT_URI)
            .appendQueryParameter("scope", Config.SCOPE)
            .appendQueryParameter("audience", Config.AUDIENCE)
            .appendQueryParameter("state", state)
            .appendQueryParameter("nonce", randomString())
            .appendQueryParameter("code_challenge", challenge)
            .appendQueryParameter("code_challenge_method", "S256")
            .build()
            .toString()
        return PendingLogin(url, state, verifier)
    }

    suspend fun completeLogin(callback: Uri, pending: PendingLogin) {
        callback.getQueryParameter("error")?.let { error ->
            throw AuthError.BadCallback(callback.getQueryParameter("error_description") ?: error)
        }
        if (callback.getQueryParameter("state") != pending.state) throw AuthError.BadCallback("state mismatch")
        val code = callback.getQueryParameter("code") ?: throw AuthError.BadCallback("missing code")

        requestToken(
            mapOf(
                "grant_type" to "authorization_code",
                "client_id" to Config.AUTH0_CLIENT_ID,
                "code" to code,
                "code_verifier" to pending.verifier,
                "redirect_uri" to Config.REDIRECT_URI,
            )
        )
    }

    fun logout() {
        session = null
        store.delete(SESSION_KEY)
    }

    /** Returns a valid access token, redeeming the refresh token when the cached one is (nearly) expired. */
    suspend fun accessToken(forceRefresh: Boolean = false): String = refreshLock.withLock {
        val current = session ?: throw AuthError.NotLoggedIn()
        if (!forceRefresh && current.expiresAt - System.currentTimeMillis() > 60_000) {
            return current.accessToken
        }
        val refreshToken = current.refreshToken ?: run {
            logout()
            throw AuthError.NotLoggedIn()
        }
        try {
            requestToken(
                mapOf(
                    "grant_type" to "refresh_token",
                    "client_id" to Config.AUTH0_CLIENT_ID,
                    "refresh_token" to refreshToken,
                    "redirect_uri" to Config.REDIRECT_URI,
                )
            )
        } catch (e: AuthError.TokenRequest) {
            if ("invalid_grant" in e.body) {
                logout()
                throw AuthError.NotLoggedIn()
            }
            throw e
        }
        session!!.accessToken
    }

    private suspend fun requestToken(params: Map<String, String>) {
        val form = params.entries.joinToString("&") { (k, v) ->
            "${URLEncoder.encode(k, "UTF-8")}=${URLEncoder.encode(v, "UTF-8")}"
        }
        val (status, body) = withContext(Dispatchers.IO) {
            val conn = URL("${Config.AUTH0_DOMAIN}/oauth/token").openConnection() as HttpURLConnection
            try {
                conn.requestMethod = "POST"
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
                conn.outputStream.use { it.write(form.toByteArray()) }
                val status = conn.responseCode
                val stream = if (status in 200..299) conn.inputStream else conn.errorStream
                status to (stream?.bufferedReader()?.use { it.readText() } ?: "")
            } finally {
                conn.disconnect()
            }
        }
        if (status != 200) throw AuthError.TokenRequest(body)

        val token = JSONObject(body)
        val claims = token.optStringOrNull("id_token")?.let(::decodeJWT) ?: JSONObject()
        val previous = session

        val new = Session(
            accessToken = token.getString("access_token"),
            // Auth0 rotates refresh tokens; keep the old one if none was returned.
            refreshToken = token.optStringOrNull("refresh_token") ?: previous?.refreshToken,
            expiresAt = System.currentTimeMillis() + (token.getDouble("expires_in") * 1000).toLong(),
            email = claims.optStringOrNull("email") ?: previous?.email,
            tectonic = claims.optStringOrNull(Config.TECTONIC_CLAIM) ?: previous?.tectonic,
        )
        session = new
        store.save(SESSION_KEY, new.toJson())
    }

    private companion object {
        const val SESSION_KEY = "session"

        fun decodeJWT(jwt: String): JSONObject? {
            val parts = jwt.split(".")
            if (parts.size < 2) return null
            return runCatching {
                JSONObject(String(Base64.decode(parts[1], Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)))
            }.getOrNull()
        }

        fun randomString(): String = base64URL(ByteArray(32).also { SecureRandom().nextBytes(it) })

        fun base64URL(data: ByteArray): String =
            Base64.encodeToString(data, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
    }
}

fun JSONObject.optStringOrNull(name: String): String? =
    if (isNull(name)) null else optString(name).takeIf { it.isNotEmpty() }
