import DulcetKit
import SwiftUI

@main
struct DulcetSignedIPhoneApp: App {
    @State private var presentation: DulcetPresentationStore
    @State private var probe: DulcetSignedIPhoneAccountProbe
    private let composition: DulcetiOSProductionComposition

    init() {
        let probe = DulcetSignedIPhoneAccountProbe()
        let composition = DulcetAppleProduction.makeIOSComposition()
        self.composition = composition
        _presentation = State(initialValue: composition.store)
        _probe = State(initialValue: probe)
        probe.observe(composition.store)
    }

    var body: some Scene {
        WindowGroup {
            DulcetRootView(store: presentation)
                .overlay(alignment: .bottom) {
                    Text(probe.text).font(.caption2.monospaced())
                        .allowsHitTesting(false)
                        .accessibilityIdentifier(DulcetSignedIPhoneAccountProbe.markerID)
                }
        }
    }
}
