import DulcetKit
import Foundation
import SwiftUI

#if DEBUG
/// Text the UI proofs read to learn that a scrobble REACHED the server. The threshold is visible
/// in Now Playing; delivery is not, and a proof that returns on the threshold alone lets the test
/// runner kill the app before the request leaves (or before the accumulator, which counts from
/// the first sampled position, has crossed at all). Enabled only by its launch argument.
@Observable
final class DulcetDebugScrobbleDeliveryMarker {
    static let launchArgument = "-dulcet-debug-scrobble-delivery-marker"
    static let accessibilityIdentifier = "dulcet.debug.scrobble-delivery"

    var text = "dulcet-scrobble awaiting-report"

    func record(_ report: DulcetScrobbleDeliveryReport) {
        text = "dulcet-scrobble"
            + " persisted=\(report.submittedPlaysPersisted)"
            + " delivered=\(report.submittedPlaysDelivered)"
            + " pending=\(report.submittedPlaysPending)"
            + " failures=\(report.submittedPlayFailedAttempts)"
            + " now-playing=\(report.nowPlayingSent)"
            + " now-playing-dropped=\(report.nowPlayingDropped)"
    }
}
#endif

@main
struct DulcetiOSApp: App {
    @State private var presentation: DulcetPresentationStore
    private let downloadController: DulcetCoreDownloadController?
#if DEBUG
    @State private var scrobbleDeliveryMarker: DulcetDebugScrobbleDeliveryMarker?
#endif

    init() {
#if DEBUG
        let arguments = ProcessInfo.processInfo.arguments
        if arguments.contains("-dulcet-account-connect-layout-fixture") {
            _presentation = State(initialValue: DulcetPresentationStore(
                source: DulcetDeterministicDataSource(initialState: .accountConnectIdle)
            ))
            downloadController = nil
            return
        }

        let composition = DulcetAppleProduction.makeIOSComposition()
        let presentation = composition.store
        downloadController = composition.downloads
        if arguments.contains(DulcetDebugScrobbleDeliveryMarker.launchArgument) {
            let marker = DulcetDebugScrobbleDeliveryMarker()
            composition.playbackController.setScrobbleDeliveryHandler { report in
                marker.record(report)
            }
            _scrobbleDeliveryMarker = State(initialValue: marker)
        }
        // 🚨 DISPOSABLE CANARY CREDENTIALS ONLY. Never point this at a real server.
        //
        // Launch arguments are not secret: xcodebuild echoes them, XCUITest records
        // `app.launchArguments` in its transcript, and CI uploads that transcript as a public
        // artifact on a failing run. The password passed here therefore ends up in a log someone
        // may later attach to an issue.
        //
        // That is acceptable for the conformance environment, whose credential is a published
        // constant for a server destroyed with the runner. It is NOT acceptable for a personal or
        // production instance, and this hook must never be used against one -- which is also why
        // it is compiled out entirely below.
        if arguments.contains("-dulcet-debug-connect-account"),
           let serverURL = Self.launchArgumentValue(
               "-dulcet-debug-account-server-url",
               in: arguments
           ),
           let username = Self.launchArgumentValue(
               "-dulcet-debug-account-username",
               in: arguments
           ),
           let password = Self.launchArgumentValue(
               "-dulcet-debug-account-password",
               in: arguments
           ) {
            presentation.accountServerURL = serverURL
            presentation.accountUsername = username
            presentation.accountPassword = password
            presentation.accountAllowLocalHTTP = serverURL.lowercased().hasPrefix("http://")
            presentation.submitAccountConnection()
        }
        _presentation = State(initialValue: presentation)
#else
        let composition = DulcetAppleProduction.makeIOSComposition()
        _presentation = State(initialValue: composition.store)
        downloadController = composition.downloads
#endif
    }

#if DEBUG
    private static func launchArgumentValue(
        _ flag: String,
        in arguments: [String]
    ) -> String? {
        guard let flagIndex = arguments.firstIndex(of: flag),
              arguments.indices.contains(flagIndex + 1) else { return nil }
        let value = arguments[flagIndex + 1].trimmingCharacters(in: .whitespacesAndNewlines)
        return value.isEmpty ? nil : value
    }
#endif

    var body: some Scene {
        WindowGroup {
            DulcetRootView(store: presentation)
#if DEBUG
                .overlay(alignment: .top) {
                    if let scrobbleDeliveryMarker {
                        Text(scrobbleDeliveryMarker.text)
                            .font(.caption2.monospaced())
                            .padding(.horizontal, 6)
                            .background(.thinMaterial, in: Capsule())
                            .accessibilityIdentifier(
                                DulcetDebugScrobbleDeliveryMarker.accessibilityIdentifier
                            )
                    }
                }
#endif
        }
        .backgroundTask(.urlSession(
            DulcetCoreDownloadController.productionBackgroundSessionIdentifier
        )) {
            await downloadController?.handleBackgroundSessionEvents()
        }
    }
}
