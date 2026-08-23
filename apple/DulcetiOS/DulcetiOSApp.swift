import DulcetKit
import SwiftUI

@main
struct DulcetiOSApp: App {
    @State private var presentation = DulcetAppleProduction.makePresentationStore()

    var body: some Scene {
        WindowGroup {
            DulcetRootView(store: presentation)
        }
    }
}
