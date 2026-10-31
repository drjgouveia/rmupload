import Foundation
import Observation
import UniformTypeIdentifiers

struct UploadItem: Identifiable {
    enum Status: Equatable {
        case rendering
        case pending
        case uploading
        case done
        case failed(String)
    }

    let id = UUID()
    var name: String
    let contentType: UTType
    var data: Data
    var status: Status = .pending

    var isEPUB: Bool { contentType.conforms(to: .epub) }
}

@MainActor
@Observable
final class UploadQueue {
    static let supportedTypes: [UTType] = [.pdf, .epub]

    var items: [UploadItem] = []
    var convertEPUBToNotebook = false
    var readerMode = true
    var lastError: String?

    var isUploading: Bool { items.contains { $0.status == .uploading } }
    var hasPending: Bool { items.contains { $0.isUploadable } }

    /// Copies the file contents into memory so security-scoped and Inbox URLs can be released.
    func add(urls: [URL]) {
        for url in urls {
            let scoped = url.startAccessingSecurityScopedResource()
            defer { if scoped { url.stopAccessingSecurityScopedResource() } }

            guard let type = UTType(filenameExtension: url.pathExtension),
                  Self.supportedTypes.contains(where: type.conforms(to:))
            else {
                lastError = "\(url.lastPathComponent): only PDF and EPUB files are supported."
                continue
            }
            do {
                let data = try Data(contentsOf: url)
                items.append(UploadItem(name: url.lastPathComponent, contentType: type, data: data))
            } catch {
                lastError = "\(url.lastPathComponent): \(error.localizedDescription)"
            }
            // Files handed over via "Open in" land in our Inbox; don't let them pile up.
            if url.path.contains("/Documents/Inbox/") {
                try? FileManager.default.removeItem(at: url)
            }
        }
    }

    /// Queues a webpage, rendering it to PDF in the background.
    func addWebpage(_ url: URL) {
        let item = UploadItem(name: url.host() ?? url.absoluteString, contentType: .pdf, data: Data(), status: .rendering)
        items.append(item)
        Task {
            do {
                let page = try await WebPageRenderer.renderPDF(url: url, readerMode: readerMode)
                guard let index = items.firstIndex(where: { $0.id == item.id }) else { return }
                items[index].name = Self.fileName(for: page.title)
                items[index].data = page.data
                items[index].status = .pending
            } catch {
                items.removeAll { $0.id == item.id }
                lastError = "\(url.absoluteString): \(error.localizedDescription)"
            }
        }
    }

    /// Accepts "example.com/article" as well as full http(s) URLs.
    static func webURL(from text: String) -> URL? {
        var text = text.trimmingCharacters(in: .whitespacesAndNewlines)
        if !text.contains("://") { text = "https://" + text }
        guard let url = URL(string: text), ["http", "https"].contains(url.scheme?.lowercased()),
              url.host()?.isEmpty == false
        else { return nil }
        return url
    }

    func remove(at offsets: IndexSet) {
        items.remove(atOffsets: offsets)
    }

    func clearFinished() {
        items.removeAll { $0.status == .done }
    }

    func uploadAll(auth: AuthService) async {
        for index in items.indices where items[index].isUploadable {
            items[index].status = .uploading
            do {
                try await upload(items[index], auth: auth)
                items[index].status = .done
            } catch {
                items[index].status = .failed(error.localizedDescription)
                if case AuthError.notLoggedIn = error { break }
            }
        }
    }

    private func upload(_ item: UploadItem, auth: AuthService) async throws {
        let mime = item.contentType.preferredMIMEType ?? (item.isEPUB ? "application/epub+zip" : "application/pdf")
        let convert = item.isEPUB && convertEPUBToNotebook

        func send(_ token: String) async throws {
            try await RemarkableClient.importFile(
                name: item.name,
                contentType: mime,
                data: item.data,
                convertToNotebook: convert,
                accessToken: token,
                tectonic: auth.session?.tectonic
            )
        }

        do {
            try await send(try await auth.accessToken())
        } catch UploadError.unauthorized {
            try await send(try await auth.accessToken(forceRefresh: true))
        }
    }

    private static func fileName(for title: String) -> String {
        let cleaned = title
            .components(separatedBy: CharacterSet(charactersIn: "/\\:"))
            .joined(separator: "-")
            .trimmingCharacters(in: .whitespacesAndNewlines)
        return (cleaned.isEmpty ? "Webpage" : String(cleaned.prefix(120))) + ".pdf"
    }
}

private extension UploadItem {
    var isUploadable: Bool {
        switch status {
        case .pending, .failed: true
        case .rendering, .uploading, .done: false
        }
    }
}
