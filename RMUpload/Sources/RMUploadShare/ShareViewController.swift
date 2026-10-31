import UIKit
import UniformTypeIdentifiers

/// Receives a webpage from the share sheet and hands it to the app via `rmupload://add?url=…`,
/// where it's rendered to PDF with the user's login.
@objc(ShareViewController)
final class ShareViewController: UIViewController {
    override func viewDidAppear(_ animated: Bool) {
        super.viewDidAppear(animated)
        Task { @MainActor in
            if let page = await sharedURL(),
               var components = URLComponents(string: "rmupload://add") {
                components.queryItems = [URLQueryItem(name: "url", value: page.absoluteString)]
                openHostApp(components.url!)
            }
            extensionContext?.completeRequest(returningItems: nil)
        }
    }

    private func sharedURL() async -> URL? {
        let providers = (extensionContext?.inputItems as? [NSExtensionItem])?
            .flatMap { $0.attachments ?? [] } ?? []
        for provider in providers where provider.hasItemConformingToTypeIdentifier(UTType.url.identifier) {
            if let url = try? await provider.loadItem(forTypeIdentifier: UTType.url.identifier) as? URL,
               ["http", "https"].contains(url.scheme?.lowercased()) {
                return url
            }
        }
        return nil
    }

    // Extensions can't use UIApplication.shared; reach the app through the responder chain.
    private func openHostApp(_ url: URL) {
        var responder: UIResponder? = self
        while let current = responder {
            if let app = current as? UIApplication {
                app.open(url)
                return
            }
            responder = current.next
        }
    }
}
