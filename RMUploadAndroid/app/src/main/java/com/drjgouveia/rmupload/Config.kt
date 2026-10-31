package com.drjgouveia.rmupload

/** Values taken from the "Read on reMarkable" Chrome extension (v2.3.0) build. */
object Config {
    const val AUTH0_DOMAIN = "https://auth.remarkable.com"
    const val AUTH0_CLIENT_ID = "0vclMaavbsMj0fb5Mquyesq33EAfBZdq"
    const val AUDIENCE = "https://web.import.cloud.remarkable.com"
    const val SCOPE = "openid profile email offline_access"

    // Auth0 only accepts the extension's registered callback. The redirect never
    // loads: the login WebView intercepts the scheme.
    const val REDIRECT_SCHEME = "chrome-extension"
    const val REDIRECT_URI = "chrome-extension://bfhkfdnddlhfippjbflipboognpdpoeh/callback.html"

    // The extension falls back to internal.cloud.remarkable.com, which isn't publicly
    // routable (404); use its configured default tectonic instead.
    private const val DEFAULT_TECTONIC = "eu"
    private const val TECTONIC_URL_TEMPLATE = "https://web.{}.tectonic.remarkable.com"
    const val TECTONIC_CLAIM = "https://auth.remarkable.com/tectonic"

    /** Sent as `rM-Source` on uploads. */
    const val APP_NAME = "RoR-Browser"

    fun apiURL(tectonic: String?): String {
        val t = tectonic?.trim().orEmpty().ifEmpty { DEFAULT_TECTONIC }
        return TECTONIC_URL_TEMPLATE.replace("{}", t)
    }
}
