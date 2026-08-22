import DulcetCore
import DulcetKit
import SwiftUI

@main
struct DulcetMacApp: App {
    @State private var presentation = DulcetPresentationStore(
        source: DulcetDeterministicDataSource(initialState: .libraryBrowse)
    )

    var body: some Scene {
        WindowGroup {
            DulcetRootView(store: presentation)
                .frame(minWidth: 900, minHeight: 600)
        }
        .windowToolbarStyle(.expanded)
        .defaultSize(width: 1180, height: 760)
    }
}
