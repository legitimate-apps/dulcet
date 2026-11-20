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
    @ScaledMetric(relativeTo: .body) private var iconColumnWidth = DulcetMetrics.listIconColumnWidth
    let title: String
    let symbol: String
    let route: DulcetReaderRoute
    let identifier: String
    var usesTVFormStyle = false

    var body: some View {
#if os(tvOS)
        if usesTVFormStyle {
            NavigationLink(value: route) {
                DulcetTVFormRowLabel(title: title, systemImage: symbol, trailing: .disclosure)
            }
            .buttonStyle(DulcetTVFocusedRowButtonStyle(placement: .form))
            .accessibilityIdentifier(identifier)
        } else {
            NavigationLink(value: route) {
                HStack(spacing: DulcetSpacing.md) {
                    Image(systemName: symbol).frame(width: iconColumnWidth)
                    Text(title)
                    Spacer(minLength: 0)
                }
                .font(.callout)
                .padding(.horizontal, DulcetSpacing.md)
                .padding(.vertical, DulcetSpacing.sm)
            }
            .accessibilityIdentifier(identifier)
        }
#else
        NavigationLink(value: DulcetStackRoute.reader(route)) {
            Label {
                Text(title).fixedSize(horizontal: false, vertical: true)
            } icon: {
                Image(systemName: symbol)
                    .frame(width: iconColumnWidth, alignment: .leading)
                    .dulcetForeground(.accentIconOnWindow)
            }
#if os(iOS)
            .labelStyle(DulcetStackedAtAccessibilitySizesLabelStyle())
#endif
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
    var hidden = false
    func body(content: Content) -> some View {
        if hidden {
            content
        } else if #available(iOS 26.0, macOS 26.0, tvOS 26.0, *) {
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
    /// `hidden` while the page shows a `DulcetStateMessage` that already states the account's
    /// condition and offers its retry, so the page says it once.
    func dulcetAccountStatusBar(hidden: Bool = false) -> some View { modifier(DulcetAccountStatusBar(hidden: hidden)) }
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

/// The one status line a destination shows under its title while it has content but is offline,
/// stale, or failed to refresh: a glyph and one message in a `Label`, with "Try Again" inline
/// right after the text as a small bordered button. It has no padding of its own: the page
/// places it on its content margin (`dulcetContentMarginPadding()` outside a scroll view),
/// `DulcetSpacing.xs` under the title and `DulcetSpacing.lg` above the first row.
///
/// A page with no content shows a `DulcetStateMessage` instead, never this alone.
///
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
                VStack(alignment: .leading, spacing: DulcetSpacing.xs) {
                    statusLabel
                    retryButton
                }
            } else {
                HStack(alignment: .firstTextBaseline, spacing: DulcetSpacing.sm) {
                    statusLabel
                    retryButton
                }
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .accessibilityElement(children: .contain)
        .accessibilityIdentifier(identifier)
    }

    private var statusLabel: some View {
        Label(title, systemImage: symbol)
#if os(tvOS)
            .font(.callout)
#else
            .font(.subheadline)
#endif
            .dulcetForeground(.secondaryTextOnWindow)
            .fixedSize(horizontal: false, vertical: true)
    }

    @ViewBuilder private var retryButton: some View {
        if let retry {
            Button(DulcetStrings.tryAgain, action: retry)
#if os(tvOS)
                .buttonStyle(DulcetTVActionButtonStyle())
#else
                .buttonStyle(.bordered)
                .controlSize(.small)
#endif
                .disabled(!retryEnabled)
                .accessibilityIdentifier(retryIdentifier)
        }
    }
}

extension View {
    /// Pads the view horizontally by the content margin for the width it is given. For content
    /// outside a scroll view -- a bar over the page, a status line -- that must share the page's
    /// leading edge. Scroll content uses `.contentMargins(.horizontal, ..., for: .scrollContent)`.
    func dulcetContentMarginPadding() -> some View { modifier(DulcetContentMarginPadding()) }
}

private struct DulcetContentMarginPadding: ViewModifier {
    @State private var width: CGFloat = 0
    func body(content: Content) -> some View {
        content
            .padding(.horizontal, DulcetMetrics.contentMargin(forWidth: width))
            .onGeometryChange(for: CGFloat.self) { $0.size.width } action: { width = $0 }
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
            let symbol = Image(systemName: "chevron.forward")
            let chevron = Text(symbol)
                .font(.subheadline.weight(.semibold))
                .dulcetPromptForeground(.secondaryTextOnWindow)
            let heading: LocalizedStringKey = "\(title)\u{00A0}\(chevron)"
            Text(heading)
                .font(.title2.bold())
                .dulcetForeground(.primaryTextOnWindow)
                .fixedSize(horizontal: false, vertical: true)
                .frame(maxWidth: .infinity, alignment: .leading)
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
        // Match the measured scroll or list content, including the narrow iPad split column.
        let available = contentWidth ?? max(0, width - 2 * DulcetLibraryMetrics.horizontalInset(forWidth: width))
        let layout = Self.geometry(available: available, accessibility: textSize.isAccessibilitySize)
        let size = layout.tileWidth
#if os(macOS)
        let columns = !textSize.isAccessibilitySize && available >= DulcetMetrics.gridTileMinimumWidth
            ? [GridItem(.adaptive(minimum: DulcetMetrics.gridTileMinimumWidth),
                        spacing: DulcetMetrics.readerTileGutter, alignment: .topLeading)]
            : layout.gridItems
#else
        let columns = layout.gridItems
#endif
        let rowSpacing = DulcetMetrics.readerTileGutter
        LazyVGrid(columns: columns, alignment: .leading, spacing: rowSpacing) {
            ForEach(Array(items.enumerated()), id: \.element.id) { index, item in
                tile(item, index, size)
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .onGeometryChange(for: CGFloat.self) { proxy in proxy.size.width } action: { value in
            if value.isFinite { contentWidth = max(0, value) }
        }
    }

    static func geometry(available: CGFloat, accessibility: Bool) -> DulcetResponsiveGridLayout.AlbumGeometry {
#if os(tvOS)
        let gutter = DulcetMetrics.readerTileGutter
        let tileWidth = min(available, DulcetMetrics.largeShelfTileWidth)
        let count = max(1, Int((available + gutter) / (DulcetMetrics.largeShelfTileWidth + gutter)))
        return .init(columns: count, tileWidth: tileWidth, spacing: gutter)
#else
        return DulcetResponsiveGridLayout.albums(
            availableWidth: available, compact: available < DulcetLibraryMetrics.compactWidthThreshold,
            accessibility: accessibility, spacing: DulcetMetrics.readerTileGutter,
            regularMinimum: DulcetMetrics.gridTileMinimumWidth,
            compactMinimum: DulcetMetrics.gridTileMinimumWidth
        )
#endif
    }
}

#endif
