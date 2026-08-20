import DulcetCore
import SwiftUI

@main
struct DulcetMacApp: App {
    var body: some Scene {
        WindowGroup {
            Text(ScaffoldKt.scaffoldName())
                .padding()
        }
    }
}
