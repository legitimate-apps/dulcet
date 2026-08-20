import DulcetCore
import SwiftUI

@main
struct DulcetiOSApp: App {
    var body: some Scene {
        WindowGroup {
            Text(ScaffoldKt.scaffoldName())
                .padding()
        }
    }
}
