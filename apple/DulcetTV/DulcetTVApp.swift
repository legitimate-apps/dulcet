import DulcetKit
import Foundation
import SwiftUI

@main
struct DulcetTVApp: App {
    @State private var presentation: DulcetPresentationStore

    init() {
        let store = DulcetAppleProduction.makePresentationStore()
#if DEBUG
        let arguments = ProcessInfo.processInfo.arguments
        // Published disposable credentials only: launch arguments appear in public test logs.
        // Restrict this account hook to the disposable loopback fixture, and compile it out
        // of Release. It supplies an account and nothing else: no destination, no query, no
        // playback state. A UI control that needs a section reaches it the way a person does,
        // through the section bar, so no hook can stand in for navigation that does not work.
        if arguments.contains("-dulcet-debug-connect-account"),
           let serverURL = Self.value("-dulcet-debug-account-server-url", in: arguments),
           serverURL == "http://127.0.0.1:4533",
           let username = Self.value("-dulcet-debug-account-username", in: arguments),
           let password = Self.value("-dulcet-debug-account-password", in: arguments) {
            store.accountServerURL = serverURL
            store.accountUsername = username
            store.accountPassword = password
            store.accountAllowLocalHTTP = true
            store.submitAccountConnection()
        }
#endif
        _presentation = State(initialValue: store)
    }

#if DEBUG
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
        }
    }
}
