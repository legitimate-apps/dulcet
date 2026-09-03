import DulcetKit
import SwiftUI

@main
struct DulcetMacApp: App {
    @State private var presentation: DulcetPresentationStore
    private let downloadController: DulcetCoreDownloadController?

    init() {
        let composition = DulcetMacProduction.makeComposition()
        _presentation = State(initialValue: composition.store)
        downloadController = composition.downloads
    }

    var body: some Scene {
        WindowGroup {
            DulcetMacProduction.makeRootView(store: presentation)
        }
        .defaultSize(width: 1180, height: 760)
        .commands {
            DulcetPlaybackCommands(store: presentation)
        }
        .backgroundTask(.urlSession(
            DulcetCoreDownloadController.productionBackgroundSessionIdentifier
        )) {
            await downloadController?.handleBackgroundSessionEvents()
        }
    }
}

/// The single production composition root shared by the application and its app-hosted control.
@MainActor
enum DulcetMacProduction {
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
