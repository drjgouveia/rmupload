import Foundation

enum UploadError: LocalizedError {
    case unauthorized
    case http(Int, String)

    var errorDescription: String? {
        switch self {
        case .unauthorized: "Not authorized. Please log in again."
        case .http(let status, let msg): "Upload failed (\(status)): \(msg)"
        }
    }
}

/// Port of `DocumentStorage.importFile` from `@remarkable/document-storage`.
enum RemarkableClient {
    static func importFile(
        name: String,
        contentType: String,
        data: Data,
        convertToNotebook: Bool,
        accessToken: String,
        tectonic: String?
    ) async throws {
        var meta: [String: Any] = ["parent": "", "file_name": name]
        if convertToNotebook {
            meta["orientation"] = "portrait"
            meta["convert"] = true
        }
        let metaHeader = try JSONSerialization.data(withJSONObject: meta).base64EncodedString()

        let url = Config.apiURL(tectonic: tectonic).appending(path: "import/v1/files")
        var request = URLRequest(url: url)
        request.httpMethod = "POST"
        request.setValue("Bearer \(accessToken)", forHTTPHeaderField: "Authorization")
        request.setValue(Config.appName, forHTTPHeaderField: "rM-Source")
        request.setValue(metaHeader, forHTTPHeaderField: "rM-Meta")
        request.setValue(contentType, forHTTPHeaderField: "Content-Type")

        let (body, response) = try await URLSession.shared.upload(for: request, from: data)
        let status = (response as? HTTPURLResponse)?.statusCode ?? 0
        guard [200, 201, 304].contains(status) else {
            if status == 401 { throw UploadError.unauthorized }
            let message = (try? JSONSerialization.jsonObject(with: body) as? [String: Any])?["message"] as? String
            throw UploadError.http(status, message ?? String(decoding: body, as: UTF8.self))
        }
    }
}
