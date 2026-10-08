import DulcetKit
import SwiftUI

@main
struct DulcetMacApp: App {
    @State private var presentation: DulcetPresentationStore
    private let downloadController: DulcetCoreDownloadController?
#if DEBUG
    @State private var downloadHandoff: DulcetDebugDownloadHandoff?
#endif

    init() {
        let composition = DulcetMacProduction.makeComposition()
        _presentation = State(initialValue: composition.store)
        downloadController = composition.downloads
#if DEBUG
        // A read-only observation seam for the app-hosted relaunch proof. The test
        // observes this launch's composition; it supplies no account at launch.
        DulcetMacProduction.recordLaunchedStore(composition.store)
        // The killed-process download proof launches this app as a process of its own, so the
        // account and the download it asks for arrive as launch arguments, and only with the
        // proof's namespace active.
        _downloadHandoff = State(initialValue: DulcetDebugDownloadHandoff.start(
            controller: composition.downloads,
            store: composition.store
        ))
        if DulcetDownloadHandoffProbe.namespace != nil {
            DulcetDebugLaunchAccount.connect(composition.store)
        }
#endif
    }

    var body: some Scene {
        WindowGroup {
            DulcetMacProduction.makeRootView(store: presentation)
#if DEBUG
                .overlay(alignment: .bottomTrailing) {
                    if let downloadHandoff {
                        DulcetDebugDownloadHandoffOverlay(probe: downloadHandoff)
                    }
                }
#endif
        }
        .defaultSize(width: 1180, height: 760)
        .commands {
            DulcetPlaybackCommands(store: presentation)
            DulcetLibraryCommands(store: presentation)
        }
        .backgroundTask(.urlSession(
            DulcetCoreDownloadController.productionBackgroundSessionIdentifier
        )) {
#if DEBUG
            DulcetDebugDownloadHandoff.recordBackgroundEvents()
#endif
            await downloadController?.handleBackgroundSessionEvents()
        }
        // Dulcet > Settings…: the streaming quality (spec §12.5), also on the Connection screen.
        Settings {
            DulcetSettingsView(store: presentation)
        }
    }
}

/// The single production composition root shared by the application and its app-hosted control.
@MainActor
enum DulcetMacProduction {
#if DEBUG
    private(set) static weak var launchedPresentationStore: DulcetPresentationStore?

    static func recordLaunchedStore(_ store: DulcetPresentationStore) {
        launchedPresentationStore = store
    }
#endif
    static func makeComposition() -> DulcetMacProductionComposition {
        DulcetAppleProduction.makeMacComposition()
    }

    static func makePresentationStore() -> DulcetPresentationStore {
        makeComposition().store
    }

    static func makeRootView(store: DulcetPresentationStore) -> some View {
        DulcetRootView(store: store)
            .frame(minWidth: 900, minHeight: 600)
    }
}
