import AuthenticationServices
import _AuthenticationServices_SwiftUI
import CryptoKit
import Foundation
import Observation

struct Session: Codable, Sendable {
    var accessToken: String
    var refreshToken: String?
    var expiresAt: Date
    var email: String?
    var tectonic: String?
}

enum AuthError: LocalizedError {
    case notLoggedIn
    case badCallback(String)
    case tokenRequest(String)

    var errorDescription: String? {
        switch self {
        case .notLoggedIn: "Please log in again."
        case .badCallback(let msg): "Login failed: \(msg)"
        case .tokenRequest(let msg): "Token request failed: \(msg)"
        }
    }
}

/// Auth0 authorization code + PKCE flow, mirroring what auth0-spa-js does in the extension.
@MainActor
@Observable
final class AuthService {
    private static let sessionKey = "session"

    private(set) var session: Session?

    var isLoggedIn: Bool { session != nil }

    init() {
        session = Keychain.load(Session.self, key: Self.sessionKey)
    }

    func login(using webAuth: WebAuthenticationSession) async throws {
        let verifier = Self.randomString()
        let challenge = Self.base64URL(Data(SHA256.hash(data: Data(verifier.utf8))))
        let state = Self.randomString()

        var components = URLComponents(url: Config.auth0Domain.appending(path: "authorize"), resolvingAgainstBaseURL: false)!
        components.queryItems = [
            .init(name: "client_id", value: Config.auth0ClientID),
            .init(name: "response_type", value: "code"),
            .init(name: "response_mode", value: "query"),
            .init(name: "redirect_uri", value: Config.redirectURI),
            .init(name: "scope", value: Config.scope),
            .init(name: "audience", value: Config.audience),
            .init(name: "state", value: state),
            .init(name: "nonce", value: Self.randomString()),
            .init(name: "code_challenge", value: challenge),
            .init(name: "code_challenge_method", value: "S256"),
        ]

        let callback = try await webAuth.authenticate(
            using: components.url!,
            callbackURLScheme: Config.redirectScheme,
            preferredBrowserSession: .shared
        )

        let items = URLComponents(url: callback, resolvingAgainstBaseURL: false)?.queryItems ?? []
        func param(_ name: String) -> String? { items.first { $0.name == name }?.value }

        if let error = param("error") {
            throw AuthError.badCallback(param("error_description") ?? error)
        }
        guard param("state") == state else { throw AuthError.badCallback("state mismatch") }
        guard let code = param("code") else { throw AuthError.badCallback("missing code") }

        try await requestToken([
            "grant_type": "authorization_code",
            "client_id": Config.auth0ClientID,
            "code": code,
            "code_verifier": verifier,
            "redirect_uri": Config.redirectURI,
        ])
    }

    func logout() {
        session = nil
        Keychain.delete(key: Self.sessionKey)
    }

    /// Returns a valid access token, redeeming the refresh token when the cached one is (nearly) expired.
    func accessToken(forceRefresh: Bool = false) async throws -> String {
        guard let session else { throw AuthError.notLoggedIn }
        if !forceRefresh, session.expiresAt.timeIntervalSinceNow > 60 {
            return session.accessToken
        }
        guard let refreshToken = session.refreshToken else {
            logout()
            throw AuthError.notLoggedIn
        }
        do {
            try await requestToken([
                "grant_type": "refresh_token",
                "client_id": Config.auth0ClientID,
                "refresh_token": refreshToken,
                "redirect_uri": Config.redirectURI,
            ])
        } catch AuthError.tokenRequest(let msg) where msg.contains("invalid_grant") {
            logout()
            throw AuthError.notLoggedIn
        }
        return self.session!.accessToken
    }

    private struct TokenResponse: Decodable {
        let access_token: String
        let refresh_token: String?
        let id_token: String?
        let expires_in: Double
    }

    private func requestToken(_ params: [String: String]) async throws {
        var request = URLRequest(url: Config.auth0Domain.appending(path: "oauth/token"))
        request.httpMethod = "POST"
        request.setValue("application/x-www-form-urlencoded", forHTTPHeaderField: "Content-Type")
        var form = URLComponents()
        form.queryItems = params.map { URLQueryItem(name: $0.key, value: $0.value) }
        request.httpBody = form.percentEncodedQuery?
            .replacingOccurrences(of: "+", with: "%2B")
            .data(using: .utf8)

        let (data, response) = try await URLSession.shared.data(for: request)
        guard (response as? HTTPURLResponse)?.statusCode == 200 else {
            throw AuthError.tokenRequest(String(decoding: data, as: UTF8.self))
        }
        let token = try JSONDecoder().decode(TokenResponse.self, from: data)
        let claims = token.id_token.flatMap(Self.decodeJWT) ?? [:]

        let new = Session(
            accessToken: token.access_token,
            // Auth0 rotates refresh tokens; keep the old one if none was returned.
            refreshToken: token.refresh_token ?? session?.refreshToken,
            expiresAt: Date().addingTimeInterval(token.expires_in),
            email: claims["email"] as? String ?? session?.email,
            tectonic: claims[Config.tectonicClaim] as? String ?? session?.tectonic
        )
        session = new
        Keychain.save(new, key: Self.sessionKey)
    }

    private static func decodeJWT(_ jwt: String) -> [String: Any]? {
        let parts = jwt.split(separator: ".")
        guard parts.count >= 2 else { return nil }
        var b64 = parts[1].replacingOccurrences(of: "-", with: "+").replacingOccurrences(of: "_", with: "/")
        b64 += String(repeating: "=", count: (4 - b64.count % 4) % 4)
        guard let data = Data(base64Encoded: b64) else { return nil }
        return try? JSONSerialization.jsonObject(with: data) as? [String: Any]
    }

    private static func randomString() -> String {
        var bytes = [UInt8](repeating: 0, count: 32)
        _ = SecRandomCopyBytes(kSecRandomDefault, bytes.count, &bytes)
        return base64URL(Data(bytes))
    }

    private static func base64URL(_ data: Data) -> String {
        data.base64EncodedString()
            .replacingOccurrences(of: "+", with: "-")
            .replacingOccurrences(of: "/", with: "_")
            .replacingOccurrences(of: "=", with: "")
    }
}
