import Foundation

/// Values taken from the "Read on reMarkable" Chrome extension (v2.3.0) build.
enum Config {
    static let auth0Domain = URL(string: "https://auth.remarkable.com")!
    static let auth0ClientID = "0vclMaavbsMj0fb5Mquyesq33EAfBZdq"
    static let audience = "https://web.import.cloud.remarkable.com"
    static let scope = "openid profile email offline_access"

    // Auth0 only accepts the extension's registered callback. The redirect never
    // loads: the web authentication session intercepts the scheme.
    static let redirectScheme = "chrome-extension"
    static let redirectURI = "chrome-extension://bfhkfdnddlhfippjbflipboognpdpoeh/callback.html"

    // The extension falls back to internal.cloud.remarkable.com, which isn't publicly
    // routable (404); use its configured default tectonic instead.
    static let defaultTectonic = "eu"
    static let tectonicURLTemplate = "https://web.{}.tectonic.remarkable.com"
    static let tectonicClaim = "https://auth.remarkable.com/tectonic"

    /// Sent as `rM-Source` on uploads.
    static let appName = "RoR-Browser"

    static func apiURL(tectonic: String?) -> URL {
        var t = tectonic?.trimmingCharacters(in: .whitespaces) ?? ""
        if t.isEmpty { t = defaultTectonic }
        return URL(string: tectonicURLTemplate.replacingOccurrences(of: "{}", with: t))!
    }
}
