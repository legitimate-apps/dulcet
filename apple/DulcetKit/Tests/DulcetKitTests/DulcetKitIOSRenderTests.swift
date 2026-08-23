#if os(iOS)
import SwiftUI
import Testing
import UIKit
@testable import DulcetKit

@Test @MainActor
func accountConnectRootLoadsForIOS() {
    let store = DulcetPresentationStore(
        source: DulcetDeterministicDataSource(initialState: .accountConnectIdle)
    )
    let controller = UIHostingController(rootView: DulcetRootView(store: store))
    let window = UIWindow(frame: CGRect(x: 0, y: 0, width: 393, height: 852))
    window.rootViewController = controller
    window.makeKeyAndVisible()
    controller.loadViewIfNeeded()
    controller.view.frame = CGRect(x: 0, y: 0, width: 393, height: 852)
    controller.view.layoutIfNeeded()

    #expect(store.snapshot.state == .accountConnectIdle)
    #expect(controller.view.bounds.size == CGSize(width: 393, height: 852))
    #expect(controller.view.window === window)
    #expect(window.rootViewController === controller)
    window.isHidden = true
}

@Test
func adaptiveIOSAccentResolvesForLightAndDarkAppearances() {
    let light = DulcetContrastColor.accent.resolvedColor(
        with: UITraitCollection(userInterfaceStyle: .light)
    )
    let dark = DulcetContrastColor.accent.resolvedColor(
        with: UITraitCollection(userInterfaceStyle: .dark)
    )

    #expect(light != dark)
}
#endif
