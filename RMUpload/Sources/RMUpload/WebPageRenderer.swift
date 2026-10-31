import UIKit
import WebKit

enum RenderError: LocalizedError {
    case load(String)
    case empty

    var errorDescription: String? {
        switch self {
        case .load(let msg): "Couldn't load page: \(msg)"
        case .empty: "The page rendered no content."
        }
    }
}

/// Loads a webpage off-screen and prints it into a paginated PDF.
@MainActor
final class WebPageRenderer: NSObject, WKNavigationDelegate {
    // 3:4 like the reMarkable screen, A4 width.
    private static let pageSize = CGSize(width: 595, height: 793)
    private static let margin: CGFloat = 36

    private var continuation: CheckedContinuation<Void, Error>?

    private struct Article: Decodable {
        let title: String
        let html: String
    }

    /// With `readerMode`, keeps only the article text and images (like the Chrome extension),
    /// falling back to the full page when no article is found.
    static func renderPDF(url: URL, readerMode: Bool) async throws -> (title: String, data: Data) {
        try await WebPageRenderer().render(url, readerMode: readerMode)
    }

    private func render(_ url: URL, readerMode: Bool) async throws -> (title: String, data: Data) {
        let webView = WKWebView(frame: CGRect(origin: .zero, size: Self.pageSize))
        webView.navigationDelegate = self
        // Print formatters only lay out reliably when the web view is in a window.
        webView.alpha = 0.01
        let window = UIApplication.shared.connectedScenes
            .compactMap { ($0 as? UIWindowScene)?.keyWindow }
            .first
        window?.insertSubview(webView, at: 0)
        defer { webView.removeFromSuperview() }

        try await load { webView.load(URLRequest(url: url)) }
        // Give lazy-loaded images and late layout a moment to settle.
        try await Task.sleep(for: .seconds(1.5))

        var title = webView.title.flatMap { $0.isEmpty ? nil : $0 } ?? url.host() ?? "Webpage"
        if readerMode, let article = await extractArticle(from: webView) {
            title = article.title
            try await load { webView.loadHTMLString(article.html, baseURL: url) }
            try await Task.sleep(for: .seconds(1.5))
        }

        let paper = CGRect(origin: .zero, size: Self.pageSize)
        let renderer = UIPrintPageRenderer()
        renderer.addPrintFormatter(webView.viewPrintFormatter(), startingAtPageAt: 0)
        renderer.setValue(NSValue(cgRect: paper), forKey: "paperRect")
        renderer.setValue(NSValue(cgRect: paper.insetBy(dx: Self.margin, dy: Self.margin)), forKey: "printableRect")

        let pages = renderer.numberOfPages
        guard pages > 0 else { throw RenderError.empty }

        let data = NSMutableData()
        UIGraphicsBeginPDFContextToData(data, paper, nil)
        renderer.prepare(forDrawingPages: NSRange(location: 0, length: pages))
        for page in 0..<pages {
            UIGraphicsBeginPDFPage()
            renderer.drawPage(at: page, in: UIGraphicsGetPDFContextBounds())
        }
        UIGraphicsEndPDFContext()

        return (title, data as Data)
    }

    private func load(_ start: () -> Void) async throws {
        try await withCheckedThrowingContinuation { continuation in
            self.continuation = continuation
            start()
        }
    }

    private func extractArticle(from webView: WKWebView) async -> Article? {
        guard let scriptURL = Bundle.module.url(forResource: "reader", withExtension: "js"),
              let script = try? String(contentsOf: scriptURL, encoding: .utf8),
              // Return "" rather than null: a nil result makes evaluateJavaScript throw.
              let json = try? await webView.evaluateJavaScript(script + "\n__rmuploadArticle() || \"\";") as? String,
              !json.isEmpty
        else { return nil }
        return try? JSONDecoder().decode(Article.self, from: Data(json.utf8))
    }

    private func finish(_ error: Error?) {
        guard let continuation else { return }
        self.continuation = nil
        if let error {
            continuation.resume(throwing: RenderError.load(error.localizedDescription))
        } else {
            continuation.resume()
        }
    }

    func webView(_ webView: WKWebView, didFinish navigation: WKNavigation!) {
        finish(nil)
    }

    func webView(_ webView: WKWebView, didFail navigation: WKNavigation!, withError error: Error) {
        finish(error)
    }

    func webView(_ webView: WKWebView, didFailProvisionalNavigation navigation: WKNavigation!, withError error: Error) {
        finish(error)
    }
}
