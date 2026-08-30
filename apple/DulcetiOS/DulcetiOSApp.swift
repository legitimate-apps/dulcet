import DulcetKit
import Foundation
import SwiftUI

@main
struct DulcetiOSApp: App {
    @State private var presentation: DulcetPresentationStore

    init() {
#if DEBUG
        if ProcessInfo.processInfo.arguments.contains("-dulcet-account-connect-layout-fixture") {
            _presentation = State(initialValue: DulcetPresentationStore(
                source: DulcetDeterministicDataSource(initialState: .accountConnectIdle)
            ))
            return
        }
#endif
        _presentation = State(initialValue: DulcetAppleProduction.makePresentationStore())
    }

    var body: some Scene {
        WindowGroup {
            DulcetRootView(store: presentation)
        }
    }
}
