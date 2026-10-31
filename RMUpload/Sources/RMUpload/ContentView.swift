import AuthenticationServices
import _AuthenticationServices_SwiftUI
import SwiftUI

struct ContentView: View {
    @Environment(AuthService.self) private var auth
    @Environment(UploadQueue.self) private var queue

    var body: some View {
        NavigationStack {
            if auth.isLoggedIn {
                UploadView()
            } else {
                LoginView()
            }
        }
    }
}

private struct LoginView: View {
    @Environment(AuthService.self) private var auth
    @Environment(\.webAuthenticationSession) private var webAuth
    @State private var error: String?
    @State private var busy = false

    var body: some View {
        VStack(spacing: 24) {
            Image(systemName: "doc.badge.arrow.up")
                .font(.system(size: 64))
                .foregroundStyle(.tint)
            Text("Send PDFs and EPUBs to your reMarkable")
                .font(.title2)
                .multilineTextAlignment(.center)
            Button {
                Task {
                    busy = true
                    defer { busy = false }
                    do {
                        try await auth.login(using: webAuth)
                        error = nil
                    } catch let e as ASWebAuthenticationSessionError where e.code == .canceledLogin {
                        // User dismissed the sheet.
                    } catch {
                        self.error = error.localizedDescription
                    }
                }
            } label: {
                Text("Log in to reMarkable")
                    .frame(maxWidth: .infinity)
            }
            .buttonStyle(.borderedProminent)
            .controlSize(.large)
            .disabled(busy)
            if let error {
                Text(error).foregroundStyle(.red).font(.footnote)
            }
        }
        .padding(32)
        .navigationTitle("RMUpload")
    }
}

private struct UploadView: View {
    @Environment(AuthService.self) private var auth
    @Environment(UploadQueue.self) private var queue
    @State private var showImporter = false
    @State private var showURLPrompt = false
    @State private var urlText = ""

    var body: some View {
        @Bindable var queue = queue

        List {
            Section {
                Toggle("Convert EPUBs to notebooks", isOn: $queue.convertEPUBToNotebook)
                Toggle("Reader mode for webpages", isOn: $queue.readerMode)
            } footer: {
                Text("PDFs are always uploaded as-is. Converted EPUBs become editable notebooks. Reader mode keeps only a webpage's article text and images.")
            }

            Section("Files") {
                if queue.items.isEmpty {
                    Text("Add files or webpages here, or use “Share → RMUpload” from any app.")
                        .foregroundStyle(.secondary)
                }
                ForEach(queue.items) { item in
                    ItemRow(item: item)
                }
                .onDelete { queue.remove(at: $0) }
            }
        }
        .navigationTitle("RMUpload")
        .toolbar {
            ToolbarItem(placement: .topBarLeading) {
                Menu {
                    if let email = auth.session?.email {
                        Text(email)
                    }
                    Button("Clear uploaded") { queue.clearFinished() }
                    Button("Log out", role: .destructive) { auth.logout() }
                } label: {
                    Image(systemName: "person.crop.circle")
                }
            }
            ToolbarItem(placement: .topBarTrailing) {
                Menu {
                    Button("Files…", systemImage: "doc") { showImporter = true }
                    Button("Webpage…", systemImage: "globe") { showURLPrompt = true }
                } label: {
                    Image(systemName: "plus")
                }
            }
            ToolbarItem(placement: .bottomBar) {
                Button {
                    Task { await queue.uploadAll(auth: auth) }
                } label: {
                    if queue.isUploading {
                        ProgressView()
                    } else {
                        Label("Send to reMarkable", systemImage: "paperplane")
                    }
                }
                .buttonStyle(.borderedProminent)
                .disabled(queue.isUploading || !queue.hasPending)
            }
        }
        .fileImporter(
            isPresented: $showImporter,
            allowedContentTypes: UploadQueue.supportedTypes,
            allowsMultipleSelection: true
        ) { result in
            switch result {
            case .success(let urls): queue.add(urls: urls)
            case .failure(let error): queue.lastError = error.localizedDescription
            }
        }
        .alert("Send a webpage as PDF", isPresented: $showURLPrompt) {
            TextField("https://…", text: $urlText)
                .keyboardType(.URL)
                .textInputAutocapitalization(.never)
                .autocorrectionDisabled()
            Button("Cancel", role: .cancel) { urlText = "" }
            Button("Add") {
                if let url = UploadQueue.webURL(from: urlText) {
                    queue.addWebpage(url)
                } else {
                    queue.lastError = "“\(urlText)” is not a valid web address."
                }
                urlText = ""
            }
        }
        .alert(
            "Couldn't add file",
            isPresented: Binding(get: { queue.lastError != nil }, set: { if !$0 { queue.lastError = nil } })
        ) {
            Button("OK", role: .cancel) {}
        } message: {
            Text(queue.lastError ?? "")
        }
    }
}

private struct ItemRow: View {
    let item: UploadItem

    var body: some View {
        HStack {
            Image(systemName: item.isEPUB ? "book" : "doc.richtext")
            VStack(alignment: .leading) {
                Text(item.name).lineLimit(1)
                Text(item.status == .rendering
                     ? "Rendering page…"
                     : ByteCountFormatter.string(fromByteCount: Int64(item.data.count), countStyle: .file))
                    .font(.caption)
                    .foregroundStyle(.secondary)
                if case .failed(let message) = item.status {
                    Text(message).font(.caption).foregroundStyle(.red)
                }
            }
            Spacer()
            switch item.status {
            case .pending: EmptyView()
            case .rendering, .uploading: ProgressView()
            case .done: Image(systemName: "checkmark.circle.fill").foregroundStyle(.green)
            case .failed: Image(systemName: "exclamationmark.triangle.fill").foregroundStyle(.orange)
            }
        }
    }
}
