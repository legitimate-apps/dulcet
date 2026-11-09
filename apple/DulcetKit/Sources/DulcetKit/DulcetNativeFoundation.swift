#if os(macOS) || os(iOS) || os(tvOS)
import SwiftUI
#if os(iOS) && DEBUG
import UIKit
#endif

#if os(tvOS)
private struct DulcetTVArrivalScopeKey: EnvironmentKey {
    static let defaultValue: Namespace.ID? = nil
}
extension EnvironmentValues {
    var dulcetTVArrivalScope: Namespace.ID? {
        get { self[DulcetTVArrivalScopeKey.self] }
        set { self[DulcetTVArrivalScopeKey.self] = newValue }
    }
}
#endif

#if DEBUG
@MainActor enum DulcetNativeTrace {
    static func append(_ line: String, file: String) {
        let url = FileManager.default.temporaryDirectory.appendingPathComponent(file)
        if !FileManager.default.fileExists(atPath: url.path) {
            FileManager.default.createFile(atPath: url.path, contents: nil)
        }
        guard let handle = try? FileHandle(forWritingTo: url) else { return }
        defer { try? handle.close() }
        _ = try? handle.seekToEnd()
        try? handle.write(contentsOf: Data((line + "\n").utf8))
    }
#if os(tvOS)
    static func arrival(_ message: @autoclosure () -> String) {
        guard ProcessInfo.processInfo.arguments.contains("-dulcet-debug-scrobble-delivery-marker") else { return }
        append("DULCET TV ARRIVAL " + message(), file: "dulcet-tv-arrival.log")
    }
#endif
}
#endif

#if os(iOS) && DEBUG
/// A finite, opt-in layer census for simulator diagnosis. It observes animations without
/// changing their speed, lifetime or XCTest's idle policy.
@MainActor enum DulcetSearchAnimationCensus {
    private static var recordedQuery = false
    private static var recordedSubmit = false
    static func recordIfRequested(submitted: Bool = false) {
        guard ProcessInfo.processInfo.arguments.contains("-dulcet-debug-search-animation-census") else { return }
        if submitted {
            guard !recordedSubmit else { return }
            recordedSubmit = true
        } else {
            guard !recordedQuery else { return }
            recordedQuery = true
        }
        let trigger = submitted ? "submit" : "query"
        for delay in [2.0, 6.0, 10.0, 20.0] {
            DispatchQueue.main.asyncAfter(deadline: .now() + delay) { record(delay, trigger: trigger) }
        }
    }
    private static func record(_ delay: Double, trigger: String) {
        var count = 0
        func walk(_ layer: CALayer, path: String) {
            let owner = layer.delegate.map { String(describing: type(of: $0)) } ?? String(describing: type(of: layer))
            let location = path + "/" + owner
            for key in layer.animationKeys() ?? [] {
                guard let animation = layer.animation(forKey: key) else { continue }
                count += 1
                DulcetNativeTrace.append("DULCET SEARCH ANIMATION trigger=\(trigger) sample=\(delay) path=\(location) key=\(key) frame=\(layer.frame) layerSpeed=\(layer.speed) animation=\(animation)", file: "dulcet-search-animation-census.log")
            }
            for child in layer.sublayers ?? [] { walk(child, path: location) }
        }
        for scene in UIApplication.shared.connectedScenes.compactMap({ $0 as? UIWindowScene }) {
            for window in scene.windows { walk(window.layer, path: "window") }
        }
        DulcetNativeTrace.append("DULCET SEARCH ANIMATION END trigger=\(trigger) sample=\(delay) count=\(count)", file: "dulcet-search-animation-census.log")
    }
}
#endif

private struct DulcetShellSearchFieldKey: EnvironmentKey {
    static let defaultValue = false
}

extension EnvironmentValues {
    var dulcetShellSearchField: Bool {
        get { self[DulcetShellSearchFieldKey.self] }
        set { self[DulcetShellSearchFieldKey.self] = newValue }
    }
}

enum DulcetStackRoute: Hashable {
    case reader(DulcetReaderRoute)
    case library(DulcetLibraryRoute)
    case discover(DulcetDiscoverHomeRoute)
}

struct DulcetReaderNavigationLink: View {
    let title: String
    let symbol: String
    let route: DulcetReaderRoute
    let identifier: String
    var body: some View {
#if os(tvOS)
        NavigationLink(value: route) {
            HStack(spacing: 24) {
                Image(systemName: symbol).frame(width: 40)
                Text(title)
                Spacer(minLength: 0)
            }
            .font(.callout)
            .padding(.horizontal, 24)
            .padding(.vertical, 16)
        }
            .accessibilityIdentifier(identifier)
#else
        NavigationLink(value: DulcetStackRoute.reader(route)) {
            Label { Text(title) } icon: {
                Image(systemName: symbol).dulcetForeground(.accentIconOnWindow)
            }
        }
        .accessibilityIdentifier(identifier)
#endif
    }
}

private struct DulcetOpenAccountKey: EnvironmentKey {
    static let defaultValue: @MainActor () -> Void = {}
}

private struct DulcetCloseAccountKey: EnvironmentKey {
    static let defaultValue: (@MainActor () -> Void)? = nil
}

struct DulcetAccountToolbar: ViewModifier {
    var isEnabled = true
    @Environment(\.dulcetOpenAccount) private var open
    func body(content: Content) -> some View {
#if os(iOS)
        content.toolbar {
            if isEnabled { ToolbarItem(placement: .topBarTrailing) {
                Button(action: open) { Image(systemName: "person.crop.circle") }
                    .accessibilityLabel(DulcetStrings.accountSection)
                    .accessibilityIdentifier("dulcet.account.open")
            } }
        }
#else
        content
#endif
    }
}


private struct DulcetUserScrollAwayFromTopKey: EnvironmentKey {
    static let defaultValue: @MainActor (Bool) -> Void = { _ in }
}

extension EnvironmentValues {
    var dulcetUserScrollAwayFromTop: @MainActor (Bool) -> Void {
        get { self[DulcetUserScrollAwayFromTopKey.self] }
        set { self[DulcetUserScrollAwayFromTopKey.self] = newValue }
    }
}

/// Native scroll edges veil content under system chrome. Only a person's scrolling may
/// minimize the tab bar; a push or a restored offset must arrive with all destinations visible.
struct DulcetSystemScrollChrome: ViewModifier {
    @Environment(\.dulcetUserScrollAwayFromTop) private var userScrolled
    @State private var interacting = false
    @State private var atTop = true

    func body(content: Content) -> some View {
#if os(iOS)
        if #available(iOS 26.0, *) {
            content
                .scrollEdgeEffectStyle(.soft, for: .bottom)
                .onScrollPhaseChange { _, phase in
                    interacting = phase == .interacting
                    if interacting && !atTop { userScrolled(true) }
                }
                .onScrollGeometryChange(for: Bool.self) { geometry in
                    geometry.contentOffset.y + geometry.contentInsets.top <= 1
                } action: { _, top in
                    atTop = top
                    if top { userScrolled(false) }
                    else if interacting { userScrolled(true) }
                }
        } else { content }
#else
        content
#endif
    }
}

/// The account's status (offline, connection failed) as a bar over the page's top edge. On the
/// 26 systems content starts below it and scrolls under it with the native scroll-edge effect;
/// earlier systems inset the page the same way, under an opaque status.
private struct DulcetAccountStatusBar: ViewModifier {
    func body(content: Content) -> some View {
        if #available(iOS 26.0, macOS 26.0, tvOS 26.0, *) {
            content.safeAreaBar(edge: .top, spacing: 0) { DulcetReaderAccountBanner(opaque: false) }
        } else {
            content.safeAreaInset(edge: .top, spacing: 0) { DulcetReaderAccountBanner() }
        }
    }
}

/// A horizontal shelf inside a page with side insets: the scroll view spans the page's full
/// width and keeps the inset as a content margin, so the first tile lines up with the page and
/// later tiles scroll off the screen edge instead of stopping short of it. Clipping is off so
/// tiles also pass through a television's side safe area and a focused tile's lift is whole.
struct DulcetEdgeToEdgeShelf: ViewModifier {
    let inset: CGFloat
    init(inset: CGFloat) { self.inset = inset }
    init(pageWidth: CGFloat) { inset = DulcetLibraryMetrics.horizontalInset(forWidth: pageWidth) }
    func body(content: Content) -> some View {
        content
            .contentMargins(.horizontal, inset, for: .scrollContent)
            .scrollClipDisabled()
            .padding(.horizontal, -inset)
    }
}

extension View {
    func dulcetAccountStatusBar() -> some View { modifier(DulcetAccountStatusBar()) }
}

struct DulcetAccountSheetClose: ViewModifier {
    @Environment(\.dulcetCloseAccount) private var close
    /// A page pushed inside the Account sheet: Back holds the leading edge alone, so Close moves
    /// to the trailing edge and the title stays centred between them.
    var pushed = false
    private var placement: ToolbarItemPlacement {
#if os(iOS)
        // Not `.confirmationAction`: it draws the item as the sheet's prominent, filled action.
        pushed ? .topBarTrailing : .cancellationAction
#else
        pushed ? .confirmationAction : .cancellationAction
#endif
    }
    func body(content: Content) -> some View {
        content.toolbar {
            if let close {
                ToolbarItem(placement: placement) {
                    Button(DulcetStrings.close, systemImage: "xmark", action: close)
                        .labelStyle(.iconOnly)
                        // The sheet's confirming action alone carries the accent.
                        .tint(.primary)
                        .accessibilityIdentifier("dulcet.account.close")
                }
            }
        }
#if os(iOS)
        .navigationBarTitleDisplayMode(.inline)
#endif
    }
}

extension EnvironmentValues {
    var dulcetCloseAccount: (@MainActor () -> Void)? {
        get { self[DulcetCloseAccountKey.self] }
        set { self[DulcetCloseAccountKey.self] = newValue }
    }

    var dulcetOpenAccount: @MainActor () -> Void {
        get { self[DulcetOpenAccountKey.self] }
        set { self[DulcetOpenAccountKey.self] = newValue }
    }
}

extension View {
    @ViewBuilder func dulcetHideListRowSeparator() -> some View {
#if os(tvOS)
        self
#else
        self.listRowSeparator(.hidden)
#endif
    }

    @ViewBuilder func dulcetReaderFreshness(_ freshness: DulcetReaderFreshness?) -> some View {
        if let freshness, let detail = DulcetReaderCopy.freshnessLine(freshness) {
            self.accessibilityCustomContent(Text(DulcetStrings.readerFreshness), Text(detail))
        } else { self }
    }
}

/// Destination status is ordinary content, so native bars retain their scroll-edge effects.
struct DulcetDestinationStatus: View {
    @Environment(\.dynamicTypeSize) private var dynamicTypeSize
    let title: String
    var symbol = "wifi.slash"
    var identifier = "dulcet.destination.status"
    var retryIdentifier = "dulcet.destination.retry"
    var retryEnabled = true
    var retry: (() -> Void)?
    var body: some View {
        Group {
            if dynamicTypeSize.isAccessibilitySize {
                VStack(alignment: .leading, spacing: 8) {
                    statusLabel
                    retryButton
                }
                .frame(maxWidth: .infinity, alignment: .leading)
            } else {
                HStack(alignment: .firstTextBaseline, spacing: 8) {
                    statusLabel
                    Spacer(minLength: 8)
                    retryButton
                }
            }
        }
        .accessibilityElement(children: .contain)
        .accessibilityIdentifier(identifier)
    }

    private var statusLabel: some View {
        Label(title, systemImage: symbol)
            .font(.footnote)
            .dulcetForeground(.secondaryTextOnWindow)
            .fixedSize(horizontal: false, vertical: true)
    }

    @ViewBuilder private var retryButton: some View {
        if let retry {
            Button(DulcetStrings.tryAgain, action: retry)
                .buttonStyle(.borderless)
                .disabled(!retryEnabled)
                .accessibilityIdentifier(retryIdentifier)
        }
    }
}

struct DulcetShelfHeader: View {
    let title: String
    var identifier: String?
    let onSeeAll: () -> Void
    var body: some View {
#if os(tvOS)
        // A heading, not a row: focus covers the title's own width, and the tiles below keep the
        // strongest focus on the page. The system's borderless style lifts only the chevron.
        HStack {
            Button(action: onSeeAll) {
                HStack(alignment: .firstTextBaseline, spacing: DulcetSpacing.xs) {
                    Text(title).font(.title3.weight(.semibold))
                    Image(systemName: "chevron.forward").font(.callout.weight(.semibold))
                        .dulcetForeground(.secondaryTextOnWindow)
                }
            }
            .buttonStyle(DulcetTVHeadingButtonStyle())
            .modifier(DulcetShelfHeaderAccessibility(title: title, identifier: identifier))
            Spacer(minLength: 0)
        }
        .padding(.top, DulcetSpacing.sm)
        .padding(.bottom, DulcetSpacing.xs)
#else
        Button(action: onSeeAll) {
            HStack(alignment: .firstTextBaseline) {
                Text(title).font(.title2.bold()).dulcetForeground(.primaryTextOnWindow)
                Image(systemName: "chevron.forward").font(.subheadline.weight(.semibold))
                    .dulcetForeground(.secondaryTextOnWindow)
                Spacer(minLength: 8)
            }
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .modifier(DulcetShelfHeaderAccessibility(title: title, identifier: identifier))
#endif
    }
}

#if os(tvOS)
/// A plain heading at rest, a light capsule around just its words on focus. The negative margin
/// keeps the words on the page's inset, aligned with the tiles below.
private struct DulcetTVHeadingButtonStyle: ButtonStyle {
    func makeBody(configuration: Configuration) -> some View { Label(configuration: configuration) }
    private struct Label: View {
        let configuration: Configuration
        @Environment(\.isFocused) private var focused
        @Environment(\.colorScheme) private var pageScheme
        var body: some View {
            configuration.label
                // Dark words on the light capsule when focused; the page's own at rest.
                .environment(\.colorScheme, focused ? .light : pageScheme)
                .padding(.horizontal, DulcetSpacing.sm)
                .padding(.vertical, DulcetSpacing.xxs)
                .background(focused ? Color.white : Color.clear, in: Capsule())
                .padding(.horizontal, -DulcetSpacing.sm)
                .scaleEffect(focused ? 1.04 : 1, anchor: .leading)
                .animation(.easeOut(duration: 0.16), value: focused)
        }
    }
}
#endif

private struct DulcetShelfHeaderAccessibility: ViewModifier {
    let title: String
    let identifier: String?
    func body(content: Content) -> some View {
        content
        .accessibilityLabel(DulcetStrings.readerRowAccessibility(title, DulcetStrings.readerSeeAll))
        .accessibilityIdentifier(identifier ?? "dulcet.shelf.seeAll")
        .accessibilityAddTraits(.isHeader)
    }
}

struct DulcetPlaybackSettingsPage: View {
    @Bindable var store: DulcetPresentationStore
    var body: some View {
        sections
            .dulcetNavigationTitle(DulcetStrings.playbackSettings, keepsTVNavigationBar: true)
            .accessibilityIdentifier("dulcet.settings.playback")
            .modifier(DulcetAccountSheetClose(pushed: true))
    }

    @ViewBuilder
    private var sections: some View {
#if os(tvOS)
        DulcetTVFormPage {
            DulcetStreamingQualitySection(store: store)
            DulcetCrossfadeSection(store: store)
        }
#else
        Form {
            DulcetStreamingQualitySection(store: store, inForm: true)
            DulcetCrossfadeSection(store: store, inForm: true)
        }
#if os(macOS)
        .formStyle(.grouped)
        .fixedSize(horizontal: false, vertical: true)
#endif
#endif
    }
}

struct DulcetDownloadsStoragePage: View {
    @Bindable var store: DulcetPresentationStore
    var body: some View {
        Form { DulcetOfflineLibrarySection(store: store, inForm: true) }
#if os(macOS)
            .formStyle(.grouped)
            .fixedSize(horizontal: false, vertical: true)
#endif
            .navigationTitle(DulcetStrings.downloadsStorage)
            .accessibilityIdentifier("dulcet.settings.downloadsStorage")
            .modifier(DulcetAccountSheetClose(pushed: true))
    }
}
/// Shared collection geometry for reader and My Library grids.
struct DulcetArtworkGrid<Content: View>: View {
    @Environment(\.dynamicTypeSize) private var textSize
    @State private var contentWidth: CGFloat?
    let items: [DulcetReaderItem]
    let width: CGFloat
    @ViewBuilder let tile: (DulcetReaderItem, Int, CGFloat) -> Content
    var body: some View {
        // List rows and scrolling pages have different insets. Start with the page proposal,
        // then use this full-width frame's actual proposal, independent of cover sizes.
        let available = contentWidth ?? max(0, width - 2 * DulcetLibraryMetrics.horizontalInset(forWidth: width))
#if os(tvOS)
        // A lifted tile grows about 6.5 percent per side, so the gutter leaves its neighbour a gap
        // and the rows leave room between its moved caption and the next row's art.
        let gutter = DulcetSpacing.xl
        let count = max(1, Int(available / 280))
        let size = max(40, (available - CGFloat(count - 1) * gutter) / CGFloat(count))
        let columns = Array(repeating: GridItem(.flexible(), spacing: gutter, alignment: .topLeading), count: count)
        let rowSpacing = DulcetSpacing.xl + DulcetSpacing.xs
#else
        let grid = DulcetResponsiveGridLayout.albums(
            availableWidth: available, compact: width < 600, accessibility: textSize.isAccessibilitySize,
            regularMinimum: DulcetResponsiveGridLayout.platformRegularMinimum
        )
        let size = grid.tileWidth
        let columns = grid.gridItems
        let rowSpacing = DulcetSpacing.lg
#endif
        LazyVGrid(columns: columns, alignment: .leading, spacing: rowSpacing) {
            ForEach(Array(items.enumerated()), id: \.element.id) { index, item in
                tile(item, index, size)
            }
        }
#if !os(tvOS)
        .frame(maxWidth: .infinity, alignment: .leading)
        .onGeometryChange(for: CGFloat.self) { proxy in proxy.size.width } action: { value in
            if value.isFinite { contentWidth = max(0, value) }
        }
#endif
    }
}

/// Library and metadata album headers use the same hierarchy and responsive arrangement.
struct DulcetAlbumHeaderLayout<Artwork: View, Identity: View, Actions: View>: View {
    let compact: Bool
    @ViewBuilder let artwork: () -> Artwork
    @ViewBuilder let identity: () -> Identity
    @ViewBuilder let actions: () -> Actions
    var body: some View {
        if compact {
            VStack(spacing: DulcetSpacing.md) {
                artwork()
                identity()
                actions().frame(maxWidth: 440)
            }
            .frame(maxWidth: .infinity)
        } else {
            HStack(alignment: .top, spacing: DulcetSpacing.lg) {
                artwork()
                VStack(alignment: .leading, spacing: DulcetSpacing.md) {
                    identity()
                    actions().frame(maxWidth: 400)
                }
                .frame(maxWidth: .infinity, alignment: .leading)
                .layoutPriority(1)
            }
        }
    }
}
#endif
