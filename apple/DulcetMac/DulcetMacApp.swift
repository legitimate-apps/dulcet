import DulcetKit
import SwiftUI

@main
struct DulcetMacApp: App {
    @State private var presentation = DulcetMacProduction.makePresentationStore()

    var body: some Scene {
        WindowGroup {
            DulcetMacProduction.makeRootView(store: presentation)
        }
        .defaultSize(width: 1180, height: 760)
    }
}

/// The single production composition root shared by the application and its app-hosted control.
@MainActor
enum DulcetMacProduction {
    static func makePresentationStore() -> DulcetPresentationStore {
        DulcetAppleProduction.makePresentationStore()
    }

    static func makeRootView(store: DulcetPresentationStore) -> some View {
        DulcetRootView(store: store)
            .frame(minWidth: 900, minHeight: 600)
    }
}
