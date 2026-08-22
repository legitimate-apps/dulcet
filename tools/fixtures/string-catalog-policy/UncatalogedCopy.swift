import SwiftUI

struct UncatalogedCopyFixture: View {
    var body: some View {
        VStack {
            Text("Fixture copy outside the catalogue")
            Text(verbatim: "127.0.0.1")
            Label(DulcetStrings.library, systemImage: "music.note")
        }
        .help("Fixture help outside the catalogue")
        .accessibilityIdentifier("uncataloged-copy-fixture")
    }
}
