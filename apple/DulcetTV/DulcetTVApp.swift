import DulcetKit
import Foundation
import SwiftUI

@main
struct DulcetTVApp: App {
    @State private var presentation: DulcetPresentationStore
#if DEBUG
    @State private var scrobbleDeliveryMarker: DulcetDebugScrobbleDeliveryMarker?
#endif

    init() {
        let composition = DulcetAppleProduction.makeTVComposition()
        let store = composition.store
#if DEBUG
        let arguments = ProcessInfo.processInfo.arguments
        if arguments.contains(DulcetDebugScrobbleDeliveryMarker.launchArgument) {
            let marker = DulcetDebugScrobbleDeliveryMarker()
            composition.playbackController.setScrobbleDeliveryHandler { report in
                marker.record(report)
            }
            _scrobbleDeliveryMarker = State(initialValue: marker)
        }
        // Published disposable credentials only: launch arguments appear in public test logs.
        // Restrict this account hook to a disposable server on the loopback interface -- the
        // conformance fixture, or the design tour's own server on another port -- and compile it
        // out of Release. It supplies an account and nothing else: no destination, no query, no
        // playback state. A UI control that needs a section reaches it the way a person does,
        // through the section bar, so no hook can stand in for navigation that does not work.
        if arguments.contains("-dulcet-debug-connect-account"),
           let serverURL = Self.value("-dulcet-debug-account-server-url", in: arguments),
           Self.isLoopbackFixture(serverURL),
           let username = Self.value("-dulcet-debug-account-username", in: arguments),
           let password = Self.value("-dulcet-debug-account-password", in: arguments) {
            store.accountServerURL = serverURL
            store.accountUsername = username
            store.accountPassword = password
            store.accountAllowLocalHTTP = URLComponents(string: serverURL)?.scheme == "http"
            store.submitAccountConnection()
        }
#endif
        _presentation = State(initialValue: store)
    }

#if DEBUG
    /// Disposable loopback HTTP/HTTPS listeners may have the server's configured base prefix.
    private static func isLoopbackFixture(_ address: String) -> Bool {
        guard let components = URLComponents(string: address),
              let scheme = components.scheme, let host = components.host,
              let port = components.port, (1...65535).contains(port) else { return false }
        return ["http", "https"].contains(scheme)
            && ["127.0.0.1", "localhost"].contains(host)
            && components.query == nil && components.fragment == nil
            && components.user == nil && components.password == nil
    }

    private static func value(_ flag: String, in arguments: [String]) -> String? {
        guard let index = arguments.firstIndex(of: flag),
              arguments.indices.contains(index + 1),
              !arguments[index + 1].isEmpty else { return nil }
        return arguments[index + 1]
    }
#endif

    var body: some Scene {
        WindowGroup {
            DulcetRootView(store: presentation)
#if DEBUG
                .overlay(alignment: .top) {
                    if let scrobbleDeliveryMarker {
                        // Read-only evidence for the playback proof; never focusable.
                        Text(scrobbleDeliveryMarker.text)
                            .font(.caption2.monospaced())
                            .padding(.horizontal, 6)
                            .background(.thinMaterial, in: Capsule())
                            .allowsHitTesting(false)
                            .accessibilityIdentifier(
                                DulcetDebugScrobbleDeliveryMarker.accessibilityIdentifier
                            )
                    }
                }
#endif
        }
    }
}
