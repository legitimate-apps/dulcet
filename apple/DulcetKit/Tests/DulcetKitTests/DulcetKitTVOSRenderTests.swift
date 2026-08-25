#if os(tvOS)
import SwiftUI
import Testing
import UIKit
@testable import DulcetKit

@Test @MainActor
func accountConnectRootLoadsForTVOS() {
    let store = DulcetPresentationStore(
        source: DulcetDeterministicDataSource(initialState: .accountConnectIdle)
    )
    let controller = UIHostingController(rootView: DulcetRootView(store: store))
    let window = UIWindow(frame: CGRect(x: 0, y: 0, width: 1_920, height: 1_080))
    window.rootViewController = controller
    window.makeKeyAndVisible()
    controller.loadViewIfNeeded()
    controller.view.frame = window.bounds
    controller.view.layoutIfNeeded()

    #expect(store.snapshot.state == .accountConnectIdle)
    #expect(controller.view.bounds.size == CGSize(width: 1_920, height: 1_080))
    #expect(controller.view.window === window)
    #expect(window.rootViewController === controller)
    window.isHidden = true
}

@Test @MainActor
func accountConnectTVOSUsesSystemCredentialTextEntryAndInitialFocus() {
    let store = DulcetPresentationStore(
        source: DulcetDeterministicDataSource(initialState: .accountConnectIdle)
    )
    var observedFocus: DulcetAccountConnectionFocus?
    let controller = UIHostingController(rootView: DulcetAccountConnectionView(
        store: store,
        focusDidChange: { observedFocus = $0 }
    ))
    let window = UIWindow(frame: CGRect(x: 0, y: 0, width: 1_920, height: 1_080))
    window.rootViewController = controller
    window.makeKeyAndVisible()
    controller.loadViewIfNeeded()
    controller.view.frame = window.bounds
    controller.view.layoutIfNeeded()
    RunLoop.main.run(until: Date(timeIntervalSinceNow: 0.1))

    let fields = controller.view.descendants(of: UITextField.self)
    #expect(fields.count == 3)
    #expect(fields.contains { $0.keyboardType == .URL })
    #expect(fields.count { $0.keyboardType == .asciiCapable } == 2)
    #expect(fields.count { $0.isSecureTextEntry } == 1)
    #expect(observedFocus == .serverAddress)
    window.isHidden = true
}

@Test
func adaptiveTVOSAccentResolvesForLightAndDarkAppearances() {
    let light = DulcetContrastColor.accent.resolvedColor(
        with: UITraitCollection(userInterfaceStyle: .light)
    )
    let dark = DulcetContrastColor.accent.resolvedColor(
        with: UITraitCollection(userInterfaceStyle: .dark)
    )

    #expect(light != dark)
}

private extension UIView {
    func descendants<ViewType: UIView>(of type: ViewType.Type) -> [ViewType] {
        subviews.flatMap { child in
            let current = (child as? ViewType).map { [$0] } ?? []
            return current + child.descendants(of: type)
        }
    }
}
#endif
