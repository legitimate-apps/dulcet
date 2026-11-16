#if os(macOS)
import AppKit
import SwiftUI
import Testing
@testable import DulcetKit

/// A listing marks the queue's current track in its number column, playing or paused, and says so
/// to VoiceOver. The album page's rows showed a plain number for the track the bar names.
@MainActor
@Test
func theAlbumPageMarksOnlyTheCurrentTrack() async throws {
    _ = NSApplication.shared
    let enhanced = NSAccessibility.Attribute(rawValue: "AXEnhancedUserInterface")
    let previous = NSApp.accessibilityAttributeValue(enhanced) ?? false
    NSApp.accessibilitySetValue(true, forAttribute: enhanced)
    defer { NSApp.accessibilitySetValue(previous, forAttribute: enhanced) }
    let source = DulcetDeterministicDataSource(initialState: .nowPlaying)
    let store = DulcetPresentationStore(source: source)
    let player = try #require(store.snapshot.nowPlaying)
    let album = try #require(store.snapshot.albums.first { $0.tracks.contains { $0.id == player.current.id } })
    #expect(album.tracks.count > 1, "A marker on the only row would not show it picks the current one")
    let hosting = NSHostingView(rootView: DulcetAlbumDetailView(album: album).environment(store))
    hosting.frame = NSRect(x: 0, y: 0, width: 900, height: 900)
    let window = NSWindow(contentRect: hosting.frame, styleMask: [.titled], backing: .buffered, defer: false)
    window.isReleasedWhenClosed = false
    window.contentView = hosting
    window.makeKeyAndOrderFront(nil)
    defer { window.close() }
    var labels: [String] = []
    for _ in 0..<50 {
        hosting.layoutSubtreeIfNeeded()
        try await Task.sleep(for: .milliseconds(40))
        labels = accessibilityLabels(of: hosting)
        if labels.contains(where: { $0.hasPrefix("Current track, ") }) { break }
    }
    let current = labels.filter { $0.hasPrefix("Current track, ") }
    #expect(current.count == 1, "exactly one row is current: \(current)")
    #expect(current.first?.contains(player.current.title) == true, "the marked row is the bar's track: \(current)")
}

@MainActor
private func accessibilityLabels(of root: NSObject) -> [String] {
    func value(_ name: String, _ object: NSObject) -> Any? {
        let selector = NSSelectorFromString(name)
        return object.responds(to: selector) ? object.perform(selector)?.takeUnretainedValue() : nil
    }
    var labels: [String] = []
    var pending = [root]
    var visited: Set<ObjectIdentifier> = []
    while let next = pending.popLast() {
        guard visited.insert(ObjectIdentifier(next)).inserted else { continue }
        if let label = value("accessibilityLabel", next) as? String { labels.append(label) }
        pending.append(contentsOf: value("accessibilityChildren", next) as? [NSObject] ?? [])
        if let view = next as? NSView { pending.append(contentsOf: view.subviews) }
    }
    return labels
}
#endif
