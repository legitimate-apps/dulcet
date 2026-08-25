import DulcetKit
import SwiftUI

@main
struct DulcetTVApp: App {
    @State private var presentation = DulcetAppleProduction.makePresentationStore()

    var body: some Scene {
        WindowGroup {
            DulcetRootView(store: presentation)
        }
    }
}
