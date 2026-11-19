#if os(macOS)
import AppKit
import SwiftUI
import Testing
@testable import DulcetKit

/// A Mac song row's More is quiet: its glyph shows only on hover, focus and the current song.
/// The menu itself must stay in the accessibility tree on every row, or VoiceOver and keyboard
/// users cannot reach a row's actions (run 44454013853: a resting row exposed no More).
@Suite(.serialized)
@MainActor
struct MacTrackRowMoreAccessibilityTests {
    @Test func aRestingRowKeepsItsMoreInTheAccessibilityTree() throws {
        let store = DulcetPresentationStore(source: DulcetDeterministicDataSource())
        let track = DulcetTrack(
            id: .init(providerInstanceID: "server", rawID: "resting"),
            title: "Resting Row",
            credits: [],
            albumTitle: "Album",
            duration: .seconds(180),
            mediaSourceID: nil,
            artwork: DulcetArtwork(seed: "resting", palette: .indigoCoral)
        )
        let row = DulcetTrackRow(track: track, showAlbum: false, index: 1, surface: .window,
                                 showsArtwork: false, onActivate: {}, onDownload: {})
            .environment(store)
            .frame(width: 640)
        let nodes = moreNodes(hosting: row)
        print("MAC MORE AX PROBE os=\(ProcessInfo.processInfo.operatingSystemVersionString) label=production nodes=\(nodes)")
        #expect(nodes.count == 1, "One More beside a resting row; found \(nodes)")
    }

    private func moreNodes<Content: View>(hosting content: Content) -> [String] {
        let enhancedUI = NSAccessibility.Attribute(rawValue: "AXEnhancedUserInterface")
        let previous = NSApplication.shared.accessibilityAttributeValue(enhancedUI) ?? false
        NSApplication.shared.accessibilitySetValue(true, forAttribute: enhancedUI)
        defer { NSApplication.shared.accessibilitySetValue(previous, forAttribute: enhancedUI) }
        let hosting = NSHostingView(rootView: content)
        hosting.frame = NSRect(x: 0, y: 0, width: 640, height: 80)
        let window = NSWindow(contentRect: hosting.frame, styleMask: [.titled], backing: .buffered, defer: false)
        window.isReleasedWhenClosed = false
        window.contentView = hosting
        window.orderFront(nil)
        defer { window.close() }
        hosting.layoutSubtreeIfNeeded()
        RunLoop.main.run(until: Date().addingTimeInterval(0.5))
        guard let root = window.contentView?.superview else { return [] }
        var found: [String] = []
        var visited = Set<ObjectIdentifier>()
        var pending: [Any] = [root]
        while let current = pending.popLast() {
            let object = current as AnyObject
            guard visited.insert(ObjectIdentifier(object)).inserted else { continue }
            var children = value("accessibilityChildren", of: current) as? [Any] ?? []
            if let view = current as? NSView { children.append(contentsOf: view.subviews) }
            if value("accessibilityIdentifier", of: current) as? String == "dulcet.track.more" {
                found.append("\(type(of: current)):\(value("accessibilityLabel", of: current) as? String ?? "-")")
            }
            pending.append(contentsOf: children)
        }
        return found
    }

    private func value(_ name: String, of element: Any) -> Any? {
        guard let object = element as? NSObject else { return nil }
        let selector = NSSelectorFromString(name)
        guard object.responds(to: selector) else { return nil }
        return object.perform(selector)?.takeUnretainedValue()
    }

    // MARK: - Which hidden-glyph labels the platform keeps (diagnostic, prints per OS)

    enum Label: String, CaseIterable {
        case visible, opacityZero, clearFrame, conditionalGlyph, tinyOpacity
    }

    private struct Probe: View {
        let label: Label
        var body: some View {
            HStack {
                Text("Track")
                Menu { Button("Download") {} } label: { content }
                    .menuStyle(.button).buttonStyle(.plain).menuIndicator(.hidden)
                    .fixedSize()
                    .accessibilityLabel("More")
                    .accessibilityIdentifier("dulcet.track.more")
            }
        }
        @ViewBuilder var content: some View {
            let glyph = Image(systemName: "ellipsis").frame(width: 24, height: 24).contentShape(Rectangle())
            switch label {
            case .visible: glyph
            case .opacityZero: glyph.opacity(0)
            case .clearFrame: Color.clear.frame(width: 24, height: 24).contentShape(Rectangle())
            case .conditionalGlyph:
                ZStack { Color.clear; if false { Image(systemName: "ellipsis") } }
                    .frame(width: 24, height: 24).contentShape(Rectangle())
            case .tinyOpacity: glyph.opacity(0.001)
            }
        }
    }

    @Test func hiddenGlyphLabelsByPlatform() {
        let os = ProcessInfo.processInfo.operatingSystemVersionString
        for label in Label.allCases {
            print("MAC MORE AX PROBE os=\(os) label=\(label.rawValue) nodes=\(moreNodes(hosting: Probe(label: label)).count)")
        }
        #expect(moreNodes(hosting: Probe(label: .visible)).count == 1, "control: a visible label is exposed")
    }
}
#endif
