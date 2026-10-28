#if os(macOS) || os(iOS) || os(tvOS)
import SwiftUI

#if DEBUG && os(iOS)
import UIKit
import OSLog

/// Diagnostic branch only. Observes public in-process AX APIs after the original test wait.
@MainActor
enum DulcetLiveAXDiagnostic {
    static let variant: String = {
        let args = ProcessInfo.processInfo.arguments
        guard let i = args.firstIndex(of: "-dulcet-debug-live-ax"), args.indices.contains(i + 1) else { return "disabled" }
        return args[i + 1]
    }()
    private static let logger = Logger(subsystem: "com.legitimateapps.dulcet.live-ax-diagnostic", category: "boundary")
    private static var sequence = 0
    private static var installed = false
    static func record(_ detail: String) {
        guard variant != "disabled" else { return }
        sequence += 1
        let message = "LIVE_AX sequence=\(sequence) variant=\(variant) \(detail)"
        logger.notice("\(message, privacy: .public)")
    }
    static func install() {
        guard variant != "disabled", !installed else { return }
        installed = true
        for checkpoint in 1...8 {
            let name = "com.legitimateapps.dulcet.live-ax-diagnostic.dump.\(checkpoint)"
            CFNotificationCenterAddObserver(CFNotificationCenterGetDarwinNotifyCenter(), nil, { _, _, name, _, _ in
                guard let name else { return }
                let text = name.rawValue as String
                let checkpoint = Int(text.split(separator: ".").last ?? "0") ?? 0
                Task { @MainActor in DulcetLiveAXDiagnostic.dump(checkpoint: checkpoint) }
            }, name as CFString, nil, .deliverImmediately)
        }
        record("phase=installed")
    }
    static func contentChanged(_ library: DulcetMyLibrarySnapshot, destination: String, reason: String) {
        record("phase=content reason=\(reason) dest=\(destination) albums=\(library.albums.count) artists=\(library.artists.count) sync=\(library.syncState) error=\(library.error?.rawValue ?? "nil")")
    }
    private static var lastStatus: String?
    static func statusGeometry(_ signature: String, frame: CGRect, event: String, aggregate: Bool) {
        record("phase=status-geometry state=\(signature) event=\(event) aggregate=\(aggregate) frame=\(frame)")
        guard variant == "after-status", signature != lastStatus else { return }
        lastStatus = signature
        // Geometry belongs to the new status view. Post on the following main-queue turn.
        DispatchQueue.main.async {
            record("phase=post-status-layoutChanged state=\(signature) committedFrame=\(frame)")
            UIAccessibility.post(notification: .layoutChanged, argument: nil)
        }
    }
    static func dump(checkpoint: Int) {
        let windows = UIApplication.shared.connectedScenes.compactMap { $0 as? UIWindowScene }.flatMap(\.windows)
        guard let key = windows.first(where: \.isKeyWindow) else {
            record("phase=dump checkpoint=\(checkpoint) error=no-key-window")
            DulcetUIProofMarkers.record("AX_DONE|checkpoint=\(checkpoint)|error=no-key-window")
            return
        }
        record("phase=dump-start checkpoint=\(checkpoint) window=\(key.frame) VoiceOver=\(UIAccessibility.isVoiceOverRunning)")
        var receipts: [String] = []
        // AX first. automationElements is a distinct public tree and is measured separately.
        for plane in ["accessibility", "automation"] {
            var seen = Set<ObjectIdentifier>()
            var albums = 0, artists = 0, realAlbums = 0, realArtists = 0
            var limited = false
            func walk(_ object: NSObject, path: String, depth: Int, edge: String) {
                guard depth < 80, seen.count < 3000 else { limited = true; return }
                guard seen.insert(ObjectIdentifier(object)).inserted else { return }
                let identifier = (object as? any UIAccessibilityIdentification)?.accessibilityIdentifier ?? ""
                let frame = object.accessibilityFrame
                if identifier.hasPrefix("dulcet.library.album") { albums += 1; if frame.width > 0 && frame.height > 0 { realAlbums += 1 } }
                if identifier.hasPrefix("dulcet.library.artist") { artists += 1; if frame.width > 0 && frame.height > 0 { realArtists += 1 } }
                var children: [(NSObject, String)] = []
                let elements = object.accessibilityElements
                let count = object.accessibilityElementCount()
                var local = Set<ObjectIdentifier>()
                func append(_ value: Any, _ source: String) {
                    guard let child = value as? NSObject, local.insert(ObjectIdentifier(child)).inserted else { return }
                    children.append((child, source))
                }
                if plane == "automation", let automation = object.automationElements {
                    for (i, child) in automation.enumerated() { append(child, "automation[\(i)]") }
                } else {
                    for (i, child) in (elements ?? []).enumerated() { append(child, "elements[\(i)]") }
                    if count > 0 && count != NSNotFound && count < 3000 {
                        for i in 0..<count { if let child = object.accessibilityElement(at: i) { append(child, "count[\(i)]") } }
                    }
                }
                // UIKit's ordinary view hierarchy supplies the route to a hosting/container view.
                // Do not treat its descendants as AX children when an explicit AX container exists.
                if children.isEmpty, !object.isAccessibilityElement, let view = object as? UIView {
                    for (i, child) in view.subviews.enumerated() where !child.isHidden && child.alpha > 0 {
                        append(child, "subview[\(i)]")
                    }
                }
                let row: [String: Any] = ["checkpoint": checkpoint, "plane": plane, "path": path, "depth": depth,
                    "edge": edge, "class": String(reflecting: type(of: object)), "identifier": identifier,
                    "label": object.accessibilityLabel ?? "", "value": object.accessibilityValue ?? "",
                    "frame": NSCoder.string(for: frame), "traits": object.accessibilityTraits.rawValue,
                    "isElement": object.isAccessibilityElement, "hidden": object.accessibilityElementsHidden,
                    "elementCount": count == NSNotFound ? -1 : count, "arrayCount": elements?.count ?? -1,
                    "children": children.count]
                if let data = try? JSONSerialization.data(withJSONObject: row, options: [.sortedKeys]), let json = String(data: data, encoding: .utf8) {
                    let receipt = "AX_NODE " + json
                    receipts.append(receipt)
                    record(receipt)
                }
                for (i, child) in children.enumerated() { walk(child.0, path: "\(path)/\(i)", depth: depth + 1, edge: child.1) }
            }
            walk(key, path: "window", depth: 0, edge: "key-window")
            let result = "AX_SUMMARY checkpoint=\(checkpoint) plane=\(plane) nodes=\(seen.count) albums=\(albums) realAlbums=\(realAlbums) artists=\(artists) realArtists=\(realArtists) limited=\(limited)"
            record(result); receipts.append(result)
        }
        // Marker updates happen only AFTER both walks and the unchanged original wait verdict.
        // Reading this overlay cannot affect the original observation being compared.
        for receipt in receipts { DulcetUIProofMarkers.record(receipt) }
        DulcetUIProofMarkers.record("AX_DONE|checkpoint=\(checkpoint)")
        record("phase=dump-complete checkpoint=\(checkpoint)")
    }
}

private struct DulcetStatusGeometry: ViewModifier {
    let signature: String
    var aggregate = false
    func body(content: Content) -> some View {
        content.background(GeometryReader { proxy in
            Color.clear
                .onAppear { report(proxy, event: "appear") }
                .onChange(of: signature) { _, _ in report(proxy, event: "state") }
                .onChange(of: proxy.frame(in: .global)) { _, _ in report(proxy, event: "frame") }
        })
    }
    private func report(_ proxy: GeometryProxy, event: String) {
        DulcetLiveAXDiagnostic.statusGeometry(signature, frame: proxy.frame(in: .global), event: event, aggregate: aggregate)
    }
}
private struct DulcetGridGeometry: ViewModifier {
    let kind: String
    let count: Int
    func body(content: Content) -> some View {
        content.background(GeometryReader { proxy in
            Color.clear
                .onAppear { report(proxy, event: "appear") }
                .onChange(of: proxy.frame(in: .global)) { _, _ in report(proxy, event: "frame") }
        })
    }
    private func report(_ proxy: GeometryProxy, event: String) {
        DulcetLiveAXDiagnostic.record("phase=grid-geometry kind=\(kind) count=\(count) event=\(event) global=\(proxy.frame(in: .global)) content=\(proxy.frame(in: .named("my-library-content")))")
    }
}

#endif

struct DulcetReaderCollectionRoot: View {
    @Environment(DulcetPresentationStore.self) private var store
    let destination: DulcetSidebarDestination
    var body: some View {
        if destination == .library && store.myLibraryAvailable {
            DulcetMyLibraryView()
        } else {
            DulcetReaderLibraryRoot()
                .dulcetNavigationTitle(destination.windowTitle, keepsTVNavigationBar: true)
        }
    }
}

struct DulcetMyLibraryButton: View {
    @Environment(DulcetPresentationStore.self) private var store
    let target: DulcetFavouriteTarget
    var menu = false
    var body: some View {
        if let session = store.librarySession, session.myLibrary.availability == .available {
            // Read the observable publication so a membership change invalidates this control.
            let _ = session.myLibrary
            let membership = session.myLibraryMembership(target)
            let on = membership == .added
            let title = on ? DulcetStrings.removeFromLibrary : DulcetStrings.addToLibrary
            Button { session.toggleMyLibrary(target) } label: {
                if menu { Label(title, systemImage: on ? "minus.circle" : "plus.circle") }
                else { Image(systemName: on ? "checkmark.circle.fill" : "plus.circle") }
            }
            .accessibilityLabel(title)
            .accessibilityIdentifier("dulcet.membership.\(target.kind.rawValue).\(target.id.rawID)")
            if menu && membership == .partial {
                Button(DulcetStrings.removeFromLibrary, systemImage: "minus.circle") {
                    session.setMyLibraryMembership(target, added: false)
                }
            }
        }
    }
}

extension DulcetMyLibrarySection {
    var title: String {
        switch self {
        case .recent: DulcetStrings.recentlyAdded
        case .artists: DulcetStrings.artists
        case .albums: DulcetStrings.albums
        case .songs: DulcetStrings.myLibrarySongs
        case .playlists: DulcetStrings.readerPlaylists
        }
    }

    /// The leading symbol of the section's row or pill, the same vocabulary Browse uses.
    var symbolName: String {
        switch self {
        case .recent: "clock"
        case .artists: "music.mic"
        case .albums: "square.stack"
        case .songs: "music.note"
        case .playlists: "music.note.list"
        }
    }
}

/// The sync status and failure lines a My Library surface states above its content.
struct DulcetMyLibraryStatusLines: View {
    let library: DulcetMyLibrarySnapshot
    let onRetry: () -> Void

    var body: some View {
        if let error = library.error {
            Text(DulcetStrings.myLibraryRefreshFailed(DulcetStrings.readerErrorPhrase(error)))
            Button(DulcetStrings.tryAgain, action: onRetry)
        } else if library.syncState == "syncing" {
            ProgressView()
        } else if library.syncState == "cached", let date = library.lastReadAt {
            Text(date, style: .relative)
                .dulcetForeground(.secondaryTextOnWindow)
        }
    }
}

/// The empty library's invitation; the Browse action reads and acts as a button.
struct DulcetMyLibraryEmptyState: View {
    @Environment(DulcetPresentationStore.self) private var store

    var body: some View {
        VStack(alignment: .leading, spacing: DulcetSpacing.sm) {
            Text(DulcetStrings.myLibraryEmptyTitle).font(.title2.weight(.semibold))
            Text(DulcetStrings.libraryPersonalEmpty).dulcetForeground(.secondaryTextOnWindow)
            Button(DulcetStrings.browseSection) { store.navigate(to: .browse) }
                .buttonStyle(.borderedProminent)
                .controlSize(.large)
#if os(iOS)
                .dulcetForeground(.labelOnAccentFill)
#endif
                .accessibilityIdentifier("dulcet.myLibrary.browse")
        }
    }
}

/// The albums, artists and songs of one My Library section, on the root or a pushed page.
struct DulcetMyLibraryCollection: View {
    @Environment(DulcetPresentationStore.self) private var store
    @Environment(\.dynamicTypeSize) private var dynamicTypeSize
    let library: DulcetMyLibrarySnapshot
    let section: DulcetMyLibrarySection
    let width: CGFloat

    var body: some View {
        switch section {
        case .recent:
            tiles(library.albums)
            tiles(library.artists)
            tracks(library.songs)
        case .albums: tiles(library.albums)
        case .artists: tiles(library.artists)
        case .songs: tracks(library.songs)
        case .playlists: EmptyView()
        }
    }

    private func tiles(_ items: [DulcetReaderItem]) -> some View {
        let tileWidth: CGFloat = dynamicTypeSize.isAccessibilitySize ? 260 : (width < 500 ? 150 : 180)
        return LazyVGrid(columns: [GridItem(.adaptive(minimum: tileWidth), alignment: .topLeading)], alignment: .leading) {
            ForEach(items) { item in DulcetReaderTile(item: item, width: tileWidth) }
        }
#if DEBUG && os(iOS)
        .modifier(DulcetGridGeometry(kind: items.first?.kind.rawValue ?? "empty", count: items.count))
#endif
    }

    private func tracks(_ items: [DulcetReaderItem]) -> some View {
        LazyVStack(alignment: .leading) {
            ForEach(Array(items.enumerated()), id: \.element.id) { index, item in
                DulcetReaderTrackRow(item: item, index: index + 1) { track in
                    store.playReaderTracks(items.compactMap(\.playableTrack), startingAt: track, sourceKind: .library, sourceID: nil, sourceName: DulcetStrings.library)
                }
            }
        }
    }
}

/// A My Library section pushed on the reader's stack: a compact Library root's rows open these.
struct DulcetMyLibrarySectionPage: View {
    @Environment(DulcetPresentationStore.self) private var store
    let section: DulcetMyLibrarySection

    var body: some View {
        Group {
            if section == .playlists {
                DulcetPlaylistsView()
            } else {
                GeometryReader { geometry in
                    ScrollView {
                        VStack(alignment: .leading, spacing: DulcetSpacing.md) {
                            if let library = store.librarySession?.myLibrary {
                                if section != .recent {
                                    DulcetLibraryCategoryControls(category: "myLibrary.\(section.rawValue)", offersArtist: section != .artists)
                                }
                                DulcetMyLibraryStatusLines(library: library, onRetry: refresh)
                                if section == .songs { DulcetMyLibraryShuffle(songs: library.songs) }
                                if library.albums.isEmpty && library.artists.isEmpty && library.songs.isEmpty {
                                    DulcetMyLibraryEmptyState()
                                } else {
                                    DulcetMyLibraryCollection(library: library, section: section, width: geometry.size.width)
                                }
                            }
                        }
                        .padding(DulcetSpacing.md)
                        .frame(maxWidth: .infinity, alignment: .leading)
                    }
#if !os(tvOS)
                    .refreshable { refresh() }
#endif
                }
            }
        }
        .dulcetForeground(.primaryTextOnWindow)
        .dulcetNavigationTitle(section.title, keepsTVNavigationBar: false)
        .accessibilityIdentifier("dulcet.myLibrary.sectionPage.\(section.rawValue)")
        .onAppear { applySort() }
        .onChange(of: store.librarySession?.readerGeneration) { _, _ in applySort() }
    }

    private func applySort() {
        (store.librarySession?.reader as? any DulcetMyLibraryReading)?.setMyLibrarySort(section == .recent ? .recentlyAdded : .alphabetical)
    }
    private func refresh() { (store.librarySession?.reader as? any DulcetMyLibraryReading)?.refreshMyLibrary() }
}

struct DulcetMyLibraryView: View {
    @Environment(DulcetPresentationStore.self) private var store
    private var section: DulcetMyLibrarySection { store.myLibrarySection }
#if os(iOS)
    @Environment(\.horizontalSizeClass) private var horizontalSizeClass
    /// A phone lists the sections as rows that push their pages; a regular width has the sidebar's.
    private var listsSections: Bool { horizontalSizeClass == .compact }
#endif
#if os(tvOS)
    @Environment(DulcetArrivalFocus.self) private var arrivalFocus: DulcetArrivalFocus?
    @FocusState private var focusedSection: DulcetMyLibrarySection?
#endif
    var body: some View {
        root
            .dulcetForeground(.primaryTextOnWindow)
            .dulcetNavigationTitle(DulcetStrings.library, keepsTVNavigationBar: true)
            .accessibilityIdentifier("dulcet.myLibrary")
#if DEBUG && os(iOS)
            .onAppear {
                DulcetLiveAXDiagnostic.install()
                if let library = store.librarySession?.myLibrary {
                    DulcetLiveAXDiagnostic.contentChanged(library, destination: store.selectedDestination.rawValue, reason: "root-appear")
                }
            }
            .onChange(of: store.librarySession?.myLibrary.albums.map(\.id)) { _, _ in
                if let library = store.librarySession?.myLibrary {
                    DulcetLiveAXDiagnostic.contentChanged(library, destination: store.selectedDestination.rawValue, reason: "album-ids-changed")
                }
            }
            .onChange(of: store.selectedDestination) { _, destination in
                DulcetLiveAXDiagnostic.record("phase=destination dest=\(destination.rawValue)")
            }
            .transaction { transaction in
                DulcetLiveAXDiagnostic.record("phase=root-transaction animation=\(String(describing: transaction.animation)) disables=\(transaction.disablesAnimations)")
            }
#endif
#if os(iOS)
            // Refresh belongs to the bar: a label button under the title truncated at the
            // accessibility text sizes, and a plain-text one read as body copy.
            .toolbar {
                ToolbarItem(placement: .primaryAction) {
                    Button { refresh() } label: {
                        Label(DulcetStrings.myLibraryRefresh, systemImage: "arrow.clockwise")
                    }
                    .labelStyle(.iconOnly)
                    .accessibilityLabel(DulcetStrings.myLibraryRefresh)
                    .accessibilityIdentifier("dulcet.myLibrary.refresh")
                }
            }
#endif
            .onAppear {
                applySort()
                refresh()
#if os(tvOS)
                claimArrivalFocus()
#endif
            }
#if os(tvOS)
            .onChange(of: arrivalFocus?.wantsSectionFocus == true) { _, wants in
                if wants { claimArrivalFocus() }
            }
            .onChange(of: focusedSection) { _, current in
                if current != nil { arrivalFocus?.sectionControlFocused() }
            }
#endif
            .onChange(of: section) { _, _ in applySort() }
            .onChange(of: store.librarySession?.readerGeneration) { _, _ in
                applySort()
                refresh()
            }
    }

    @ViewBuilder
    private var root: some View {
#if os(tvOS)
        VStack(alignment: .leading, spacing: DulcetSpacing.sm) {
            sectionBar
            sectionContent(section)
        }
#elseif os(iOS)
        if listsSections {
            compactHome
        } else {
            sectionContent(section)
        }
#else
        sectionContent(section)
#endif
    }

#if os(iOS)
    /// The phone's Library root: every section as a row, then what was added recently as a grid --
    /// the platform's own music app's layout. The rows push their pages onto the stack.
    private var compactHome: some View {
        GeometryReader { geometry in
            ScrollView {
                VStack(alignment: .leading, spacing: DulcetSpacing.md) {
                    Text(DulcetStrings.libraryPersonalTitle).font(.title2.weight(.semibold)).accessibilityAddTraits(.isHeader)
                    Text(DulcetStrings.libraryPersonalHint).font(.subheadline).dulcetForeground(.secondaryTextOnWindow)
                    if store.librarySession?.discoverPublication?.access.showsWishlistDestination == true {
                        DulcetReaderHomeLink(title: DulcetStrings.wishlist, symbolName: "bookmark", identifier: "wishlist.destination") { store.pushReaderPage(.wishlist) }
                    }
                    DulcetLibraryDepthHome(width: geometry.size.width)
                    if let library = store.librarySession?.myLibrary {
                        DulcetMyLibraryShuffle(songs: library.songs)
#if DEBUG
                        if DulcetLiveAXDiagnostic.variant == "fixed-status" {
                            // A concrete container receives the fixed frame once for the whole block.
                            VStack(alignment: .leading, spacing: DulcetSpacing.md) {
                                DulcetMyLibraryStatusLines(library: library, onRetry: refresh)
                            }
                            .frame(maxWidth: .infinity, alignment: .leading)
                            .frame(height: 120, alignment: .topLeading)
                            .modifier(DulcetStatusGeometry(signature: statusSignature(library), aggregate: true))
                        } else if DulcetLiveAXDiagnostic.variant != "status-inset" {
                            DulcetMyLibraryStatusLines(library: library, onRetry: refresh)
                                .modifier(DulcetStatusGeometry(signature: statusSignature(library)))
                        }
#else
                        DulcetMyLibraryStatusLines(library: library, onRetry: refresh)
#endif
                        VStack(spacing: 0) {
                            ForEach(DulcetMyLibrarySection.allCases) { choice in
                                DulcetReaderHomeLink(
                                    title: choice.title,
                                    symbolName: choice.symbolName,
                                    identifier: "dulcet.myLibrary.section.\(choice.rawValue)"
                                ) {
                                    store.pushReaderPage(.myLibrarySection(choice))
                                }
                            }
                        }
                        if library.albums.isEmpty && library.artists.isEmpty && library.songs.isEmpty {
                            DulcetMyLibraryEmptyState()
                        } else {
                            Text(DulcetStrings.recentlyAdded)
                                .font(.title2.weight(.semibold))
                                .accessibilityAddTraits(.isHeader)
#if DEBUG
                            if DulcetLiveAXDiagnostic.variant == "status-identity" {
                                DulcetMyLibraryCollection(library: library, section: .recent, width: geometry.size.width)
                                    .id(statusSignature(library))
                            } else {
                                DulcetMyLibraryCollection(library: library, section: .recent, width: geometry.size.width)
                            }
#else
                            DulcetMyLibraryCollection(library: library, section: .recent, width: geometry.size.width)
#endif
                        }
                    }
                }
#if DEBUG
                .coordinateSpace(name: "my-library-content")
#endif
                .padding(DulcetSpacing.md)
                .frame(maxWidth: .infinity, alignment: .leading)
            }
            .refreshable { refresh() }
#if DEBUG
            .safeAreaInset(edge: .top, spacing: 0) {
                if DulcetLiveAXDiagnostic.variant == "status-inset", let library = store.librarySession?.myLibrary {
                    if library.error != nil || library.syncState == "syncing" || library.syncState == "cached" {
                        VStack(alignment: .leading, spacing: DulcetSpacing.sm) {
                            DulcetMyLibraryStatusLines(library: library, onRetry: refresh)
                        }
                        .padding(DulcetSpacing.sm)
                        .frame(maxWidth: .infinity, alignment: .leading)
                        .background(.bar)
                        .modifier(DulcetStatusGeometry(signature: statusSignature(library), aggregate: true))
                    }
                }
            }
#endif
        }
    }
#if DEBUG
    private func statusSignature(_ library: DulcetMyLibrarySnapshot) -> String {
        library.syncState + "/" + (library.error?.rawValue ?? "nil")
    }
#endif

#endif

    /// The whole Library for one section, chosen by the sidebar or the television's section row.
    @ViewBuilder
    private func sectionContent(_ section: DulcetMyLibrarySection) -> some View {
        if section == .playlists {
            DulcetPlaylistsView()
        } else {
            GeometryReader { geometry in
                ScrollView {
                    VStack(alignment: .leading, spacing: DulcetSpacing.md) {
#if os(macOS)
                        Button(DulcetStrings.myLibraryRefresh, systemImage: "arrow.clockwise") { refresh() }
                            .accessibilityIdentifier("dulcet.myLibrary.refresh")
#endif
                        DulcetLibraryDepthHome(width: geometry.size.width)
                        if section != .recent {
                            DulcetLibraryCategoryControls(category: "myLibrary.\(section.rawValue)", offersArtist: section != .artists)
                        }
                        if let library = store.librarySession?.myLibrary {
                            if section == .songs || section == .recent { DulcetMyLibraryShuffle(songs: library.songs) }
                            DulcetMyLibraryStatusLines(library: library, onRetry: refresh)
                            if library.albums.isEmpty && library.artists.isEmpty && library.songs.isEmpty {
                                DulcetMyLibraryEmptyState()
                            } else {
                                DulcetMyLibraryCollection(library: library, section: section, width: geometry.size.width)
                            }
                        }
                    }
                    .padding(DulcetSpacing.md)
                    .frame(maxWidth: .infinity, alignment: .leading)
                }
#if !os(tvOS)
                .refreshable { refresh() }
#endif
            }
        }
    }

#if os(tvOS)
    /// The sections and Refresh across the top of Library, as the reader's own bar lays them out:
    /// one horizontal, scrollable row of focusable items -- never wrapped pills.
    private var sectionBar: some View {
        ScrollView(.horizontal, showsIndicators: false) {
            HStack(spacing: DulcetSpacing.xs) {
                ForEach(DulcetMyLibrarySection.allCases) { choice in
                    Button {
                        store.selectMyLibrarySection(choice)
                    } label: {
                        Label(choice.title, systemImage: choice.symbolName)
                            .font(.callout.weight(section == choice ? .semibold : .regular))
                    }
                    .buttonStyle(.bordered)
                    .focused($focusedSection, equals: choice)
                    .accessibilityIdentifier("dulcet.myLibrary.section.\(choice.rawValue)")
                    .accessibilityAddTraits(section == choice ? .isSelected : [])
                }
                Button {
                    refresh()
                } label: {
                    Label(DulcetStrings.myLibraryRefresh, systemImage: "arrow.clockwise")
                        .font(.callout)
                }
                .buttonStyle(.bordered)
                .accessibilityIdentifier("dulcet.myLibrary.refresh")
            }
            .padding(.horizontal, DulcetSpacing.lg)
            .padding(.vertical, DulcetSpacing.xs)
        }
        .focusSection()
    }

    private func claimArrivalFocus() {
        guard let arrivalFocus, arrivalFocus.pending, store.readerPath.isEmpty else { return }
        focusedSection = section
    }
#endif

    /// The section whose content the root draws: always Recently Added on a phone, where the rows
    /// push their pages; elsewhere the sidebar's or section row's choice.
    private var shownSection: DulcetMyLibrarySection {
#if os(iOS)
        if listsSections { return .recent }
#endif
        return section
    }

    private func applySort() {
        (store.librarySession?.reader as? any DulcetMyLibraryReading)?.setMyLibrarySort(shownSection == .recent ? .recentlyAdded : .alphabetical)
    }
    private func refresh() { (store.librarySession?.reader as? any DulcetMyLibraryReading)?.refreshMyLibrary() }
}
struct DulcetMyLibraryShuffle: View {
    @Environment(DulcetPresentationStore.self) private var store
    let songs: [DulcetReaderItem]
    var body: some View {
        Button(DulcetStrings.libraryShufflePersonal, systemImage: "shuffle") {
            store.playReaderTracks(songs.compactMap(\.playableTrack).filter { $0.availability == .playable }, shuffle: true,
                                   sourceKind: .library, sourceID: nil, sourceName: DulcetStrings.libraryPersonalTitle)
        }
        .buttonStyle(.bordered)
        .disabled(songs.compactMap(\.playableTrack).allSatisfy { $0.availability != .playable })
        .accessibilityIdentifier("dulcet.myLibrary.shuffleAll")
    }
}
#endif
