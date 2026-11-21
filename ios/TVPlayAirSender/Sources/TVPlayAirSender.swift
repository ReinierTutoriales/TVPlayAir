import GoogleCast
import SwiftUI
import UIKit

@main
struct TVPlayAirSender: App {
    @UIApplicationDelegateAdaptor(SenderAppDelegate.self) private var delegate

    var body: some Scene {
        WindowGroup {
            SenderView()
        }
    }
}

final class SenderAppDelegate: NSObject, UIApplicationDelegate {
    static var receiverID: String? {
        guard let value = Bundle.main.object(forInfoDictionaryKey: "CastReceiverAppID") as? String,
              value.range(of: #"^[A-F0-9]{8}$"#, options: .regularExpression) != nil else {
            return nil
        }
        return value
    }

    func application(
        _ application: UIApplication,
        didFinishLaunchingWithOptions options: [UIApplication.LaunchOptionsKey: Any]? = nil
    ) -> Bool {
        guard let id = Self.receiverID else { return true }
        let castOptions = GCKCastOptions(discoveryCriteria: GCKDiscoveryCriteria(applicationID: id))
        let launchOptions = GCKLaunchOptions()
        launchOptions.androidReceiverCompatible = true
        castOptions.launchOptions = launchOptions
        GCKCastContext.setSharedInstanceWith(castOptions)
        GCKCastContext.sharedInstance().useDefaultExpandedMediaControls = true
        return true
    }
}

private struct CastButton: UIViewRepresentable {
    func makeUIView(context: Context) -> GCKUICastButton {
        GCKUICastButton(frame: .zero)
    }

    func updateUIView(_ uiView: GCKUICastButton, context: Context) {}
}

private struct SenderView: View {
    @State private var address = ""
    @State private var autoplay = true
    @State private var status = ""

    var body: some View {
        NavigationStack {
            Form {
                if SenderAppDelegate.receiverID != nil {
                    TextField("URL del medio", text: $address)
                        .keyboardType(.URL)
                        .textInputAutocapitalization(.never)
                        .autocorrectionDisabled()
                    Toggle("Reproducir al enviar", isOn: $autoplay)
                    Button("Enviar a la TV", action: send)
                    Button("Abrir controles de reproducción") {
                        guard GCKCastContext.sharedInstance().sessionManager.currentCastSession != nil else {
                            status = "Conéctate primero a TVPlayAir con el botón Cast."
                            return
                        }
                        GCKCastContext.sharedInstance().presentDefaultExpandedMediaControls()
                    }
                    if !status.isEmpty {
                        Text(status).foregroundStyle(.secondary)
                    }
                } else {
                    Text("Configura el ID del receptor Google Cast para conectar con TVPlayAir.")
                }
            }
            .navigationTitle("TVPlayAir")
            .toolbar {
                if SenderAppDelegate.receiverID != nil {
                    ToolbarItem(placement: .navigationBarTrailing) {
                        CastButton()
                            .frame(width: 44, height: 44)
                            .accessibilityLabel("Google Cast")
                    }
                }
            }
        }
    }

    private func send() {
        guard let url = URL(string: address.trimmingCharacters(in: .whitespacesAndNewlines)),
              let scheme = url.scheme?.lowercased(),
              ["http", "https"].contains(scheme),
              let host = url.host, !host.isEmpty, url.user == nil else {
            status = "Introduce una URL HTTP o HTTPS válida."
            return
        }
        guard let client = GCKCastContext.sharedInstance().sessionManager.currentCastSession?.remoteMediaClient else {
            status = "Conéctate primero a TVPlayAir con el botón Cast."
            return
        }
        let media = GCKMediaInformationBuilder(contentURL: url)
        media.streamType = .none
        switch url.pathExtension.lowercased() {
        case "m3u8": media.contentType = "application/x-mpegURL"
        case "mpd": media.contentType = "application/dash+xml"
        case "mp4": media.contentType = "video/mp4"
        case "mp3": media.contentType = "audio/mpeg"
        default: break
        }
        let request = GCKMediaLoadRequestDataBuilder()
        request.mediaInformation = media.build()
        request.autoplay = NSNumber(value: autoplay)
        _ = client.loadMedia(with: request.build())
        status = "Solicitud enviada."
    }
}
