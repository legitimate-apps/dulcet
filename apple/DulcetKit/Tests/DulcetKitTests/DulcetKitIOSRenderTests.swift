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

    // Account leaves the content tabs. This source maps Listen Now to its Library
    // snapshot; the live-shell UI proofs exercise the actual Listen Now destination.
    #expect(store.selectedDestination == .library)
    #expect(store.snapshot.state == .libraryBrowse)
    #expect(controller.view.bounds.size == CGSize(width: 393, height: 852))
    #expect(controller.view.window === window)
    #expect(window.rootViewController === controller)
    window.isHidden = true
}

@Test @MainActor
func libraryRefreshStatusLeavesRoomForContentAtLargestText() {
    let library = DulcetMyLibrarySnapshot(availability: .available, syncState: "cached", error: .unreachable)
    let status = DulcetMyLibraryStatusLines(library: library, onRetry: {})
        .dynamicTypeSize(.accessibility5)
    let controller = UIHostingController(rootView: status)
    controller.loadViewIfNeeded()
    let size = controller.sizeThatFits(in: CGSize(width: 402, height: 874))
    // The regression filled most of the phone with a wrapped heading beside Retry.
    // Both remain full-size and readable while leaving the majority for the destination.
    #expect(size.height > 44)
    #expect(size.height < 220)
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
