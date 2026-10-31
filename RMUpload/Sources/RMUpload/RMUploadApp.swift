import SwiftUI

@main
struct RMUploadApp: App {
    @State private var auth = AuthService()
    @State private var queue = UploadQueue()

    var body: some Scene {
        WindowGroup {
            ContentView()
                .environment(auth)
                .environment(queue)
                .onOpenURL { url in
                    if url.isFileURL {
                        // Files shared from other apps ("Open in RMUpload").
                        queue.add(urls: [url])
                    } else if url.scheme == "rmupload", url.host() == "add",
                              let page = URLComponents(url: url, resolvingAgainstBaseURL: false)?
                                  .queryItems?.first(where: { $0.name == "url" })?.value,
                              let pageURL = UploadQueue.webURL(from: page) {
                        // Webpages handed over by the share extension.
                        queue.addWebpage(pageURL)
                    }
                }
        }
    }
}
