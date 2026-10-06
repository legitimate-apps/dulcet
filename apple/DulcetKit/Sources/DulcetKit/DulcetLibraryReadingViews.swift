#if os(macOS) || os(iOS) || os(tvOS)
import SwiftUI

// The library the reader draws (spec §16.18, the Apple paragraph), on every Apple destination.
//
// Each screen opens one window while it is visible and closes it when it is not. What a screen
// says about its content -- how old it is, whether the list is whole, what cannot play offline --
// comes from `DulcetReaderCopy`, so no two screens word the same state differently. "Try Again"
// is always the reconnect, never a screen's own re-read (§16.14).

// MARK: - A screen's window

/// Opens a window for `query` while on screen and hands it to `content`.
struct DulcetReaderScreen<Content: View>: View {
    @Environment(DulcetPresentationStore.self) private var store
    @State private var model: DulcetLibraryWindowModel
    private let content: (DulcetLibraryWindowModel) -> Content

    init(query: DulcetLibraryQuery, @ViewBuilder content: @escaping (DulcetLibraryWindowModel) -> Content) {
        _model = State(initialValue: DulcetLibraryWindowModel(query: query))
        self.content = content
    }

    var body: some View {
        content(model)
            .onAppear(perform: open)
            .onDisappear { model.close() }
            // A different account's reader: the rows go, and the screen reads again.
            .onChange(of: store.librarySession?.readerGeneration) { _, _ in open() }
    }

    private func open() {
        guard let session = store.librarySession else { return }
        model.open(in: session)
    }
}

extension View {
    /// Reports a row's visibility, which is how a window learns its viewport and extends.
    func dulcetReaderRow(_ index: Int, in model: DulcetLibraryWindowModel) -> some View {
        onAppear { model.rowAppeared(index) }
            .onDisappear { model.rowDisappeared(index) }
    }
}

// MARK: - What a screen says about itself

/// Retry for any reader screen: the reconnect for a connected account; for one not connected in
/// this launch, the account's Reconnect.
@MainActor
func dulcetReaderRetry(_ store: DulcetPresentationStore) {
    guard let session = store.librarySession else { return }
    if session.mode == .deviceOnly {
        store.submitAccountConnection()
    } else {
        session.reconnect()
    }
}

/// The lines above a list: its age and why, whether it is whole, and whether it is sorted on
/// this device. Nothing at all for live, complete content.
struct DulcetReaderStatusView: View {
    @Environment(DulcetPresentationStore.self) private var store
    let window: DulcetLibraryWindow?

    var body: some View {
        if let window, !lines(window).isEmpty || offersRetry(window) {
            VStack(alignment: .leading, spacing: DulcetSpacing.xxs) {
                ForEach(lines(window), id: \.self) { line in
                    Text(line)
                        .font(.footnote)
                        .dulcetForeground(.secondaryTextOnWindow)
                        .lineLimit(nil)
                        .fixedSize(horizontal: false, vertical: true)
                }
                if offersRetry(window) {
                    DulcetReaderRetryButton()
                }
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            .accessibilityElement(children: .contain)
            .accessibilityIdentifier("dulcet.reader.status")
        }
    }

    private func lines(_ window: DulcetLibraryWindow) -> [String] {
        [
            DulcetReaderCopy.freshnessLine(window.freshness),
            DulcetReaderCopy.coverageLine(window),
            DulcetReaderCopy.orderLine(window),
        ].compactMap { $0 }
    }

    private func offersRetry(_ window: DulcetLibraryWindow) -> Bool {
        guard window.freshness.offersRetry else { return false }
        // Offline with the network down there is nothing to retry until it returns.
        if case .cached(.offline, _) = window.freshness,
           store.librarySession?.mode == .connected,
           store.librarySession?.connectionFailure == nil {
            return false
        }
        return true
    }
}

struct DulcetReaderRetryButton: View {
    @Environment(DulcetPresentationStore.self) private var store

    var body: some View {
        let deviceOnly = store.librarySession?.mode == .deviceOnly
        Button(
            deviceOnly ? DulcetStrings.reconnect : DulcetStrings.readerTryAgain,
            systemImage: "arrow.clockwise"
        ) {
            dulcetReaderRetry(store)
        }
#if os(tvOS)
        .buttonStyle(.bordered)
#else
        .buttonStyle(.bordered)
        .controlSize(.small)
#endif
        .disabled(store.snapshot.accountConnection == .connecting)
        .accessibilityIdentifier("dulcet.reader.retry")
    }
}

/// What a screen shows instead of content it does not have -- never a spinner (§16.14).
struct DulcetReaderUnavailableView: View {
    let reason: DulcetReaderUnavailableReason
    let subject: DulcetReaderCopy.Subject

    var body: some View {
        VStack(spacing: DulcetSpacing.sm) {
            Image(systemName: reason == .gone ? "questionmark.folder" : "icloud.slash")
                .font(.system(size: 32, weight: .medium))
                .dulcetForeground(.secondaryTextOnWindow)
                .accessibilityHidden(true)
            Text(DulcetReaderCopy.unavailableLine(reason, subject: subject))
                .font(.body)
                .multilineTextAlignment(.center)
                .lineLimit(nil)
                .fixedSize(horizontal: false, vertical: true)
                .dulcetForeground(.primaryTextOnWindow)
            if reason != .gone {
                DulcetReaderRetryButton()
            }
        }
        .padding(DulcetSpacing.lg)
        .frame(maxWidth: 480)
        .frame(maxWidth: .infinity)
        .accessibilityElement(children: .contain)
        .accessibilityIdentifier("dulcet.reader.unavailable")
    }
}

/// The account's own state, stated once at the top of the library: not connected in this launch,
/// connecting, or kept offline by a failed reconnect; and a server that never says when it
/// changed.
struct DulcetReaderAccountBanner: View {
    @Environment(DulcetPresentationStore.self) private var store

    var body: some View {
        let session = store.librarySession
        VStack(alignment: .leading, spacing: DulcetSpacing.xs) {
            if session?.mode == .deviceOnly {
                deviceOnly
            } else if let line = DulcetReaderCopy.connectionLine(session?.connectionFailure) {
                HStack(alignment: .firstTextBaseline, spacing: DulcetSpacing.xs) {
                    Text(line)
                        .font(.callout)
                        .dulcetForeground(.primaryTextOnWindow)
                        .lineLimit(nil)
                        .fixedSize(horizontal: false, vertical: true)
                    Spacer(minLength: 0)
                    DulcetReaderRetryButton()
                }
            }
            if session?.serverReportsNoEpoch == true {
                Text(DulcetStrings.readerNoEpoch)
                    .font(.footnote)
                    .dulcetForeground(.secondaryTextOnWindow)
                    .lineLimit(nil)
                    .fixedSize(horizontal: false, vertical: true)
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }

    @ViewBuilder
    private var deviceOnly: some View {
        let serverName = savedServerName
        VStack(alignment: .leading, spacing: DulcetSpacing.xxs) {
            Text(DulcetStrings.readerDeviceOnlyTitle)
                .font(.headline)
                .dulcetForeground(.primaryTextOnWindow)
                .accessibilityAddTraits(.isHeader)
            Text(DulcetStrings.readerDeviceOnlyBody(serverName))
                .font(.callout)
                .dulcetForeground(.secondaryTextOnWindow)
                .lineLimit(nil)
                .fixedSize(horizontal: false, vertical: true)
            if case let .failed(failure) = store.snapshot.accountConnection {
                Text(failure.message)
                    .font(.callout)
                    .dulcetForeground(.primaryTextOnWindow)
                    .lineLimit(nil)
                    .fixedSize(horizontal: false, vertical: true)
            }
            HStack(spacing: DulcetSpacing.xs) {
                DulcetProminentAction(
                    DulcetStrings.reconnect,
                    systemImage: "arrow.clockwise",
                    isEnabled: store.snapshot.accountConnection != .connecting
                ) {
                    store.submitAccountConnection()
                }
                .accessibilityLabel(DulcetStrings.reconnectToServer(serverName))
                .accessibilityIdentifier("dulcet.reader.reconnect")
                if store.snapshot.accountConnection == .connecting {
                    ProgressView()
                        .accessibilityLabel(DulcetStrings.connecting)
                }
            }
            .padding(.top, DulcetSpacing.xxs)
        }
        .padding(DulcetSpacing.sm)
        .background(Color.dulcetControl, in: RoundedRectangle(cornerRadius: 12, style: .continuous))
        .accessibilityElement(children: .contain)
        .accessibilityIdentifier("dulcet.reader.deviceOnly")
    }

    private var savedServerName: String {
        switch store.snapshot.accountConnection {
        case let .saved(serverName): serverName
        case let .failed(failure): failure.serverName
        case let .connected(account): account.serverName
        case .idle, .connecting: DulcetStrings.readerSearchScopeServer
        }
    }
}

// MARK: - Favourites

/// The heart on a row, a header or the player (§16.20): what the person tapped while it is
/// being sent, and a mark while a change is kept unsent.
struct DulcetFavouriteButton: View {
    @Environment(DulcetPresentationStore.self) private var store
    let target: DulcetFavouriteTarget
    let published: Bool?
    var title: String = ""
    var size: Font = .body
    var identifier = "dulcet.reader.favorite"
    /// The smallest side of the control's hit area.
    var minimumSide: CGFloat = 32

    var body: some View {
        if let session = store.librarySession {
            let on = session.isFavourite(target.id, published: published)
            let state = session.favouriteState(target)
            Button {
                session.toggleFavourite(target, published: published)
            } label: {
                ZStack(alignment: .bottomTrailing) {
                    Image(systemName: on ? "heart.fill" : "heart")
                        .font(size)
                        .dulcetForeground(.accentIconOnWindow)
                    // Always present, hidden while settled: inserting it into the label on a
                    // press took accessibility focus off the focused heart on Apple TV while the
                    // focus effect stayed drawn on it (OBSERVED on a tvOS 26.5 simulator), so an
                    // assistive reader lost the control the person had just pressed.
                    Image(systemName: state == .settled || state == .pending ? "clock" : "exclamationmark.circle.fill")
                        .font(.caption2.weight(.bold))
                        .dulcetForeground(.secondaryTextOnWindow)
                        .offset(x: 6, y: 4)
                        .opacity(state == .settled ? 0 : 1)
                        .accessibilityHidden(true)
                }
                .frame(minWidth: minimumSide, minHeight: minimumSide)
                .contentShape(Rectangle())
            }
            .buttonStyle(.borderless)
#if os(macOS)
            .help(on ? DulcetStrings.unfavorite : DulcetStrings.favorite)
#endif
            .accessibilityLabel(on ? DulcetStrings.unfavorite : DulcetStrings.favorite)
            .accessibilityValue(stateDescription(state, title: title, on: on))
            .accessibilityIdentifier(identifier)
        }
    }

    private func stateDescription(_ state: DulcetFavouriteChangeState, title: String, on: Bool) -> String {
        switch state {
        case .settled: on && !title.isEmpty ? DulcetStrings.readerFavoriteAccessibility(title) : ""
        case .pending: DulcetStrings.readerFavoritePending
        case .held: DulcetStrings.readerFavoriteHeld
        }
    }
}

/// The playing track's heart (§16.20): the macOS toolbar's, and the one in the player's own
/// controls on iPhone, iPad and Apple TV. It is the rows' heart, through the same session, so a
/// pending, held or saved change reads the same here as on the track's row. Nothing is drawn while
/// the reader does not hold the account the track came from.
struct DulcetNowPlayingFavouriteButton: View {
    @Environment(DulcetPresentationStore.self) private var store
    let track: DulcetTrack
    var size: Font = .body
    var minimumSide: CGFloat = 32

    static let identifier = "dulcet.now-playing.favorite"

    var body: some View {
        if let favourite = store.nowPlayingFavourite(for: track) {
            DulcetFavouriteButton(
                target: favourite.target,
                published: favourite.published,
                title: track.title,
                size: size,
                identifier: Self.identifier,
                minimumSide: minimumSide
            )
        }
    }
}

/// A read-only heart for a row whose own button would crowd it: shown only when it is a favourite.
struct DulcetFavouriteIndicator: View {
    @Environment(DulcetPresentationStore.self) private var store
    let id: DulcetProviderItemID
    let published: Bool?

    var body: some View {
        if store.librarySession?.isFavourite(id, published: published) == true {
            Image(systemName: "heart.fill")
                .font(.caption)
                .dulcetForeground(.accentIconOnWindow)
                .accessibilityLabel(DulcetStrings.readerFavoriteOn)
        }
    }
}

#if !os(tvOS)
/// Favorite / Remove Favorite in a context menu.
struct DulcetFavouriteMenuItem: View {
    @Environment(DulcetPresentationStore.self) private var store
    let target: DulcetFavouriteTarget
    let published: Bool?

    var body: some View {
        if let session = store.librarySession {
            let on = session.isFavourite(target.id, published: published)
            Button(on ? DulcetStrings.unfavorite : DulcetStrings.favorite, systemImage: on ? "heart.slash" : "heart") {
                session.setFavourite(target, favourite: !on)
            }
        }
    }
}
#endif

// MARK: - Playing what a screen shows

extension DulcetPresentationStore {
    /// Plays `tracks` from `start`, attributed to where they came from.
    func playReaderTracks(
        _ tracks: [DulcetTrack],
        startingAt start: DulcetTrack? = nil,
        shuffle: Bool = false,
        sourceKind: DulcetPlaybackQueueSourceKind,
        sourceID: DulcetProviderItemID?,
        sourceName: String
    ) {
        guard !tracks.isEmpty else {
            librarySession?.post(DulcetStrings.readerNothingPlayable)
            return
        }
        playTracks(DulcetPlaybackQueueIntent(
            tracks: tracks,
            sourceKind: sourceKind,
            sourceID: sourceID,
            sourceDisplayName: sourceName,
            startIndex: shuffle ? nil : (start.flatMap { track in tracks.firstIndex { $0.id == track.id } } ?? 0),
            shuffle: shuffle
        ))
    }

    /// An album's or a playlist's tracks, read from the reader, then played or queued.
    func resolveAndPlay(_ item: DulcetReaderItem, shuffle: Bool) {
        guard let query = item.trackListQuery else { return }
        librarySession?.resolveTracks(of: query) { [weak self] tracks, window in
            self?.playReaderTracks(
                tracks,
                shuffle: shuffle,
                sourceKind: item.kind == .playlist ? .playlist : .album,
                sourceID: item.id,
                sourceName: window.header?.displayTitle ?? item.displayTitle
            )
        }
    }

    /// An album's or a playlist's tracks, read from the reader and wrapped as an addition to the
    /// queue, in the order the reader lists them. A list with nothing playable says so and
    /// completes with nil.
    func resolveReaderAddition(
        of item: DulcetReaderItem,
        completion: @escaping @MainActor (DulcetQueueAddition?) -> Void
    ) {
        guard let query = item.trackListQuery else { return }
        resolveReaderAddition(
            query: query,
            kind: item.kind == .playlist ? .playlist : .album,
            id: item.id,
            title: item.displayTitle,
            completion: completion
        )
    }

    func resolveReaderAddition(
        query: DulcetLibraryQuery,
        kind: DulcetPlaybackQueueSourceKind,
        id: DulcetProviderItemID,
        title: String,
        completion: @escaping @MainActor (DulcetQueueAddition?) -> Void
    ) {
        librarySession?.resolveTracks(of: query) { [weak self] tracks, window in
            guard let self else { return }
            guard !tracks.isEmpty else {
                self.librarySession?.post(DulcetStrings.readerNothingPlayable)
                completion(nil)
                return
            }
            completion(DulcetQueueAddition(
                tracks: tracks,
                sourceKind: kind,
                sourceID: id,
                sourceDisplayName: window.header?.displayTitle ?? title
            ))
        }
    }

    func resolveAndQueue(_ item: DulcetReaderItem, next: Bool) {
        resolveReaderAddition(of: item) { [weak self] addition in
            guard let addition else { return }
            self?.editQueue(next ? .playNext(addition) : .playLater(addition))
        }
    }

    /// A search row: a track plays with the search's other playable tracks; an album or an
    /// artist opens its page.
    func activateReaderSearchRow(_ row: DulcetReaderSearchRow, in publication: DulcetReaderSearchPublication?) {
        switch row.result.kind {
        case .track:
            guard !row.isUnavailableOffline, let track = row.result.playableTrack else {
                librarySession?.post(DulcetStrings.readerPlaysOnReconnect)
                return
            }
            let tracks = (publication?.rows ?? [row])
                .filter { !$0.isUnavailableOffline }
                .compactMap(\.result.playableTrack)
            playReaderTracks(
                tracks,
                startingAt: track,
                sourceKind: .search,
                sourceID: nil,
                sourceName: DulcetStrings.search
            )
        case .album:
            showReaderPage(.album(row.id))
        case .artist:
            showReaderPage(.artist(row.id))
        }
    }
}

extension DulcetReaderItem {
    /// The window holding this item's tracks, for an album or a playlist.
    var trackListQuery: DulcetLibraryQuery? {
        switch kind {
        case .album: .album(rawID: id.rawID)
        case .playlist: .playlist(rawID: id.rawID)
        case .artist, .track, .genre: nil
        }
    }

    /// The page this item opens.
    var route: DulcetReaderRoute? {
        switch kind {
        case .album: .album(id)
        case .artist: .artist(id)
        case .playlist: .playlist(id)
        case .genre: .genre(displayTitle)
        case .track: nil
        }
    }

    /// The line under an album's title.
    var albumSubtitle: String {
        [artistName, year.map(String.init)].compactMap { $0 }.filter { !$0.isEmpty }
            .joined(separator: " \u{00B7} ")
    }
}

// MARK: - Rows and tiles

private struct DulcetReaderPageKey: EnvironmentKey {
    static let defaultValue: DulcetReaderRoute? = nil
}

extension EnvironmentValues {
    /// The Library page a view is drawn on: the route that pushed it, or nil for the section at
    /// the stack's root.
    var dulcetReaderPage: DulcetReaderRoute? {
        get { self[DulcetReaderPageKey.self] }
        set { self[DulcetReaderPageKey.self] = newValue }
    }
}

extension View {
    /// On a remote, this control takes focus when Back returns to its page from the page it
    /// opened (``DulcetPresentationStore/readerReturn``). Elsewhere it does nothing.
    func dulcetTakesFocusOnReturn(to route: DulcetReaderRoute?) -> some View {
#if os(tvOS)
        modifier(DulcetReturnFocus(route: route))
#else
        self
#endif
    }
}

extension View {
    /// On a remote, this control takes focus when its page has just been opened and nothing on
    /// it holds focus yet (``DulcetPresentationStore/readerArrival``), once `isEnabled` -- a
    /// disabled control cannot hold focus. Elsewhere it does nothing.
    func dulcetTakesFocusOnArrival(isEnabled: Bool = true) -> some View {
#if os(tvOS)
        modifier(DulcetArrivalFocusClaim(isEnabled: isEnabled))
#else
        self
#endif
    }
}

#if os(tvOS)
/// A page opened on a remote puts focus on its first control, as the platform's own Music app
/// does. A page publishes its content after it is pushed; when the focus engine settles first it
/// finds nothing on the page and leaves focus on the app's section bar -- where Back does not go
/// back -- and nothing moves it again when the content arrives. Whether it settles first depends
/// on the host's speed (OBSERVED on a loaded simulator, not on an idle one).
private struct DulcetArrivalFocusClaim: ViewModifier {
    @Environment(DulcetPresentationStore.self) private var store
    @Environment(\.dulcetReaderPage) private var page
    let isEnabled: Bool
    @FocusState private var focused: Bool

    func body(content: Content) -> some View {
        content
            .focused($focused)
            .onAppear(perform: claim)
            .onChange(of: isEnabled) { _, _ in claim() }
            .onChange(of: focused) { _, isFocused in
                if isFocused, isArrivalTarget { store.readerArrivalFocused() }
            }
    }

    private var isArrivalTarget: Bool {
        guard let page else { return false }
        return store.readerArrival == page && store.readerPath.last == page
    }

    private func claim() {
        guard isEnabled, isArrivalTarget else { return }
        focused = true
    }
}

/// Back on a remote puts focus on the item that opened the page left, as in the platform's own
/// Music app. Without this the focus engine places it on the first control of the page that
/// returns -- the Library's section bar -- and the person has lost their place in the grid.
/// The page is matched as well as the route, because the pages under it in the stack stay drawn
/// and may hold an item for the same route.
private struct DulcetReturnFocus: ViewModifier {
    @Environment(DulcetPresentationStore.self) private var store
    @Environment(\.dulcetReaderPage) private var page
    let route: DulcetReaderRoute?
    @FocusState private var focused: Bool

    func body(content: Content) -> some View {
        content
            .focused($focused)
            .onAppear(perform: claim)
            .onChange(of: store.readerReturn) { _, _ in claim() }
            .onChange(of: focused) { _, isFocused in
                if isFocused, isReturnTarget { store.readerReturnFocused() }
            }
    }

    private var isReturnTarget: Bool {
        guard let route, let landed = store.readerReturn else { return false }
        return landed.opened == route && landed.page == page && store.readerPath.last == page
    }

    private func claim() {
        guard isReturnTarget else { return }
        focused = true
    }
}
#endif

/// An album, artist or playlist on a grid or a shelf. Opens its page.
struct DulcetReaderTile: View {
    @Environment(\.dynamicTypeSize) private var dynamicTypeSize
    @Environment(DulcetPresentationStore.self) private var store
    let item: DulcetReaderItem
    let width: CGFloat
    /// The line under the title, when the grid's own would repeat where these are: on an
    /// artist's page the artist's name is the page's, so the year alone carries it. Nil draws
    /// the item's own line.
    var subtitle: String? = nil

    var body: some View {
        Button {
            if let route = item.route { store.pushReaderPage(route) }
        } label: {
            VStack(alignment: .leading, spacing: DulcetSpacing.xxs) {
                DulcetArtworkView(artwork: item.artwork, size: width)
                    .clipShape(item.kind == .artist
                        ? AnyShape(Circle())
                        : AnyShape(RoundedRectangle(cornerRadius: DulcetMetrics.artworkCornerRadius, style: .continuous)))
                HStack(alignment: .firstTextBaseline, spacing: DulcetSpacing.xxs) {
                    Text(item.displayTitle)
                        .font(.subheadline.weight(.medium))
                        .dulcetForeground(.primaryTextOnWindow)
                        .lineLimit(dynamicTypeSize.isAccessibilitySize ? nil : 1)
                    Spacer(minLength: 0)
                    DulcetFavouriteIndicator(id: item.id, published: item.isFavourite)
                }
                if !resolvedSubtitle.isEmpty {
                    Text(resolvedSubtitle)
                        .font(.caption)
                        .dulcetForeground(.secondaryTextOnWindow)
                        .lineLimit(dynamicTypeSize.isAccessibilitySize ? nil : 1)
                }
            }
            .frame(width: width, alignment: .leading)
            .contentShape(Rectangle())
        }
        .dulcetMediaButtonStyle(hover: .lift)
        .dulcetTakesFocusOnReturn(to: item.route)
        .accessibilityElement(children: .combine)
        .accessibilityLabel(accessibilityLabel)
        .accessibilityIdentifier("dulcet.library.\(item.kind.rawValue)")
        .dulcetReaderItemContextMenu(item)
        .dulcetReaderQueueDragSource(item)
    }

    private var resolvedSubtitle: String {
        subtitle ?? defaultSubtitle
    }

    private var defaultSubtitle: String {
        switch item.kind {
        case .album: item.albumSubtitle
        case .artist: item.albumCount.map { DulcetStrings.readerCount(.albums, $0) } ?? ""
        case .playlist: item.songCount.map { DulcetStrings.readerCount(.tracks, $0) } ?? ""
        case .genre: DulcetStrings.readerGenreSummary(albums: item.albumCount, songs: item.songCount)
        case .track: DulcetStrings.artistNames(item.artistName.map { [$0] } ?? [])
        }
    }

    private var accessibilityLabel: String {
        let base = resolvedSubtitle.isEmpty ? item.displayTitle : DulcetStrings.readerRowAccessibility(item.displayTitle, resolvedSubtitle)
        guard store.librarySession?.isFavourite(item.id, published: item.isFavourite) == true else { return base }
        return DulcetStrings.readerFavoriteAccessibility(base)
    }
}

/// A row in a list of artists, playlists or genres. Opens its page.
struct DulcetReaderListRow: View {
    @Environment(\.dynamicTypeSize) private var dynamicTypeSize
    @Environment(DulcetPresentationStore.self) private var store
    let item: DulcetReaderItem

    var body: some View {
        HStack(spacing: DulcetSpacing.xs) {
            Button {
                if let route = item.route { store.pushReaderPage(route) }
            } label: {
                HStack(spacing: DulcetSpacing.xs) {
                    if item.kind != .genre {
                        DulcetArtworkView(artwork: item.artwork, size: DulcetMetrics.denseRowArtworkSize + 8)
                            .clipShape(item.kind == .artist
                                ? AnyShape(Circle())
                                : AnyShape(RoundedRectangle(cornerRadius: DulcetMetrics.artworkCornerRadius, style: .continuous)))
                    }
                    VStack(alignment: .leading, spacing: 0) {
                        Text(item.displayTitle)
                            .font(.body)
                            .dulcetForeground(.primaryTextOnWindow)
                            .lineLimit(dynamicTypeSize.isAccessibilitySize ? nil : 1)
                        if !subtitle.isEmpty {
                            Text(subtitle)
                                .font(.caption)
                                .dulcetForeground(.secondaryTextOnWindow)
                                .lineLimit(dynamicTypeSize.isAccessibilitySize ? nil : 1)
                        }
                    }
                    Spacer(minLength: DulcetSpacing.xs)
                    Image(systemName: "chevron.forward")
                        .font(.caption.weight(.semibold))
                        .dulcetForeground(.secondaryTextOnWindow)
                        .accessibilityHidden(true)
                }
                .padding(.vertical, DulcetSpacing.xxs)
                .frame(minHeight: 44)
                .contentShape(Rectangle())
            }
            .dulcetRowButtonStyle()
            .dulcetTakesFocusOnReturn(to: item.route)
            .accessibilityElement(children: .combine)
            .accessibilityLabel(subtitle.isEmpty ? item.displayTitle : DulcetStrings.readerRowAccessibility(item.displayTitle, subtitle))
            .accessibilityIdentifier("dulcet.library.\(item.kind.rawValue)")
            if let target = item.favouriteTarget {
                DulcetFavouriteButton(target: target, published: item.isFavourite, title: item.displayTitle)
            }
        }
        .dulcetReaderItemContextMenu(item)
        .dulcetReaderQueueDragSource(item)
    }

    private var subtitle: String {
        switch item.kind {
        case .artist: item.albumCount.map { DulcetStrings.readerCount(.albums, $0) } ?? ""
        case .playlist:
            // Whose it is when not the person's, and what has yet to reach the server (§18.6).
            [item.songCount.map { DulcetStrings.readerCount(.tracks, $0) }, DulcetPlaylistPresentation.status(of: item)]
                .compactMap { $0 }.filter { !$0.isEmpty }.joined(separator: " \u{00B7} ")
        case .genre: DulcetStrings.readerGenreSummary(albums: item.albumCount, songs: item.songCount)
        case .album: item.albumSubtitle
        case .track: ""
        }
    }
}

/// A track in an album, a playlist, a genre or the favourites. A track that cannot play offline
/// is dimmed and labelled, and tapping it says why rather than doing nothing (§16.14).
struct DulcetReaderTrackRow: View {
    @Environment(\.dynamicTypeSize) private var dynamicTypeSize
    @Environment(DulcetPresentationStore.self) private var store
    let item: DulcetReaderItem
    let index: Int
    var showsAlbum = true
    var showsArtwork = true
    var albumArtists: [String] = []
    let onPlay: (DulcetTrack) -> Void

    var body: some View {
        HStack(spacing: DulcetSpacing.xxs) {
            if let track = item.playableTrack {
                if item.isUnavailableOffline {
                    Button {
                        store.librarySession?.post(DulcetStrings.readerPlaysOnReconnect)
                    } label: {
                        row(track)
                    }
                    .dulcetRowButtonStyle()
                    .accessibilityIdentifier("dulcet.reader.track.unavailable")
                } else {
                    row(track)
                        .accessibilityIdentifier("dulcet.reader.track")
#if !os(tvOS)
                        .dulcetTrackContextMenu(
                            track: track,
                            onPlay: { onPlay(track) },
                            offersAlbum: showsAlbum,
                            publishedRating: item.rating
                        )
#endif
                }
            } else {
                // The server never gave this track's length: listed with the list's chrome, and
                // never handed to the player, so no play action and no bright title.
                unplayableRow
            }
            if let target = item.favouriteTarget {
                DulcetFavouriteButton(target: target, published: item.isFavourite, title: item.displayTitle)
            }
        }
    }

    private var unplayableRow: some View {
        HStack(alignment: .center, spacing: DulcetSpacing.xs) {
            Text(String(index))
                .font(showsArtwork ? .caption.monospacedDigit() : .callout.monospacedDigit())
                .dulcetForeground(.secondaryTextOnWindow)
                .frame(minWidth: 20)
                .accessibilityHidden(true)
            if showsArtwork {
                DulcetArtworkView(artwork: item.artwork, size: DulcetMetrics.denseRowArtworkSize, muted: true)
            }
            VStack(alignment: .leading, spacing: 0) {
                Text(item.displayTitle)
                    .font(showsArtwork ? .callout.weight(.medium) : .body)
                    .dulcetForeground(.secondaryTextOnWindow)
                    .lineLimit(dynamicTypeSize.isAccessibilitySize ? nil : 1)
                if !unplayableSubtitle.isEmpty {
                    Text(unplayableSubtitle)
                        .font(.caption)
                        .dulcetForeground(.secondaryTextOnWindow)
                        .lineLimit(dynamicTypeSize.isAccessibilitySize ? nil : 1)
                }
            }
            Spacer(minLength: DulcetSpacing.xs)
            Text(DulcetStrings.readerTrackDurationUnknown)
                .font(.caption.monospacedDigit())
                .dulcetForeground(.secondaryTextOnWindow)
        }
        .padding(.horizontal, DulcetSpacing.xs)
        .padding(.vertical, DulcetMetrics.denseRowVerticalPadding)
        .frame(minHeight: 44)
        .accessibilityElement(children: .combine)
    }

    /// What the row can still say about the track: its artists, and its album where the list
    /// names albums. Either side may be missing; an empty pair is no line at all.
    private var unplayableSubtitle: String {
        let artists = DulcetStrings.artistNames(item.artistName.map { [$0] } ?? [])
        if showsAlbum, let album = item.albumTitle, !album.isEmpty {
            return artists.isEmpty ? album : DulcetStrings.trackSubtitle(artists: artists, album: album)
        }
        return artists
    }

    private func row(_ track: DulcetTrack) -> some View {
        DulcetTrackRow(
            track: track,
            showAlbum: showsAlbum,
            index: index,
            offline: item.isUnavailableOffline,
            showsArtwork: showsArtwork,
            albumArtists: albumArtists,
            onActivate: item.isUnavailableOffline ? nil : { onPlay(track) }
        )
    }
}

extension View {
    /// Play, Shuffle, Play Next, Add to Queue, Favorite and Go to Artist for an album, a
    /// playlist or an artist. Nothing on tvOS, whose focus engine owns the long press.
    func dulcetReaderItemContextMenu(_ item: DulcetReaderItem) -> some View {
        modifier(DulcetReaderItemContextMenu(item: item))
    }
}

extension DulcetReaderItem {
    /// Whether dragging this item can add tracks to the queue: an album or a playlist, whose
    /// tracks the reader reads on the drop. An artist or a genre has no track list of its own.
    var isQueueDraggable: Bool { trackListQuery != nil }
}

extension View {
    /// Lets an album or a playlist be dragged onto the queue (§3.1), whole and in the order the
    /// reader lists it. iOS and iPadOS only; any other kind of item is left as it is.
    func dulcetReaderQueueDragSource(_ item: DulcetReaderItem) -> some View {
        modifier(DulcetReaderQueueDragSource(item: item))
    }
}

private struct DulcetReaderQueueDragSource: ViewModifier {
    @Environment(DulcetPresentationStore.self) private var store
    let item: DulcetReaderItem

    @ViewBuilder
    func body(content: Content) -> some View {
        // Chosen by kind, which an item never changes, so no tile changes identity under a read.
        if item.isQueueDraggable {
            content.dulcetDeferredQueueDragSource(
                store: store,
                artwork: item.artwork,
                title: item.displayTitle
            ) { completion in
                store.resolveReaderAddition(of: item, completion: completion)
            }
        } else {
            content
        }
    }
}

private struct DulcetReaderItemContextMenu: ViewModifier {
    @Environment(DulcetPresentationStore.self) private var store
    let item: DulcetReaderItem

    func body(content: Content) -> some View {
#if os(tvOS)
        content
#elseif os(iOS)
        if item.isQueueDraggable {
            // The system preview: an album or a playlist is also a drag source onto the queue,
            // which a custom preview stops from lifting out of the open menu (TRAPS 48).
            content.contextMenu { menuItems }
        } else {
            content.contextMenu {
                menuItems
            } preview: {
                DulcetContextMenuPreview(
                    store: store,
                    artwork: item.artwork,
                    title: item.displayTitle,
                    subtitle: item.artistName ?? ""
                )
            }
        }
#else
        content.contextMenu { menuItems }
#endif
    }

#if !os(tvOS)
    @ViewBuilder
    private var menuItems: some View {
        if item.trackListQuery != nil {
            Button(DulcetStrings.play, systemImage: "play") { store.resolveAndPlay(item, shuffle: false) }
            Button(DulcetStrings.shuffle, systemImage: "shuffle") { store.resolveAndPlay(item, shuffle: true) }
            if store.queueEditingEnabled {
                Button(DulcetStrings.playNext, systemImage: "text.line.first.and.arrowtriangle.forward") {
                    store.resolveAndQueue(item, next: true)
                }
                Button(DulcetStrings.addToQueue, systemImage: "text.line.last.and.arrowtriangle.forward") {
                    store.resolveAndQueue(item, next: false)
                }
            }
        }
        if let target = item.favouriteTarget {
            DulcetFavouriteMenuItem(target: target, published: item.isFavourite)
        }
        if item.kind == .album, let artistID = item.artistID {
            Button(DulcetStrings.goToArtist, systemImage: "music.mic") {
                store.showReaderPage(.artist(artistID))
            }
        }
        DulcetPlaylistItemMenuItems(item: item)
    }
#endif
}

// MARK: - Grids and lists

/// A window's items as a grid of tiles, extending as it scrolls.
struct DulcetReaderGrid: View {
    let model: DulcetLibraryWindowModel
    let width: CGFloat
    /// The tiles' line under the title, when the item's own would repeat the page's; nil leaves
    /// each item's own line.
    var tileSubtitle: ((DulcetReaderItem) -> String)? = nil

    var body: some View {
        let insets = DulcetLibraryMetrics.horizontalInset(forWidth: width) * 2
        let minimum = DulcetLibraryMetrics.shelfItemMinimumWidth(forWidth: width)
        let tile = DulcetLibraryMetrics.tileWidth(
            containerWidth: width - insets,
            minimumItemWidth: minimum,
            spacing: DulcetSpacing.sm
        )
        LazyVGrid(
            columns: DulcetResponsiveGridLayout.columns(
                containerWidth: width,
                horizontalInsets: insets,
                minimumItemWidth: minimum,
                spacing: DulcetSpacing.sm,
                alignment: .topLeading
            ),
            alignment: .leading,
            spacing: DulcetSpacing.md
        ) {
            ForEach(Array((model.window?.items ?? []).enumerated()), id: \.element.id) { index, item in
                DulcetReaderTile(item: item, width: tile, subtitle: tileSubtitle?(item))
                    .dulcetReaderRow(index, in: model)
            }
        }
#if os(tvOS)
        // The remote moves focus by geometry, and the tiles fill only the grid's leading
        // columns: a control above them at the trailing edge -- an artist's heart -- has nothing
        // under it, so Down from it went nowhere and the artist's albums could not be reached.
        // A focus section as wide as the page takes a move from anywhere above into the grid.
        .frame(maxWidth: .infinity, alignment: .leading)
        .focusSection()
#endif
    }
}

/// The body of a list screen: its status, then its rows, or the statement in their place.
struct DulcetReaderListBody<Rows: View>: View {
    let model: DulcetLibraryWindowModel
    var subject: DulcetReaderCopy.Subject = .list
    @ViewBuilder let rows: (DulcetLibraryWindow) -> Rows

    var body: some View {
        VStack(alignment: .leading, spacing: DulcetSpacing.sm) {
            if let window = model.window {
                DulcetReaderStatusView(window: window)
                if case let .unavailable(reason) = window.freshness, window.items.isEmpty {
                    DulcetReaderUnavailableView(reason: reason, subject: subject)
                } else if window.items.isEmpty, window.freshness != .loading, window.itemsState != .loading {
                    Text(DulcetStrings.readerEmptyList)
                        .dulcetForeground(.secondaryTextOnWindow)
                        .frame(maxWidth: .infinity)
                        .padding(.vertical, DulcetSpacing.lg)
                } else {
                    rows(window)
                    if window.freshness == .loading || window.itemsState == .loading
                        || (window.coverage == .open && !window.freshness.isOffline) {
                        ProgressView()
                            .frame(maxWidth: .infinity)
                            .padding(.vertical, DulcetSpacing.sm)
                            .accessibilityLabel(DulcetStrings.loadingMore)
                    }
                }
            } else {
                ProgressView()
                    .frame(maxWidth: .infinity)
                    .padding(.vertical, DulcetSpacing.lg)
            }
        }
    }
}

/// A scrolling page with the library's insets.
struct DulcetReaderPage<Content: View>: View {
    let title: String
    /// Apple TV's navigation bar floats its copy of the title over the content, dimmed, under
    /// the app's own section bar. A section root keeps it (tests read the launch section from
    /// it); a pushed page -- an album, a playlist -- already names itself in its own header, and
    /// hides it.
    var keepsTVNavigationBar = true
    @ViewBuilder let content: (CGFloat) -> Content

    var body: some View {
        GeometryReader { geometry in
            ScrollView {
                VStack(alignment: .leading, spacing: DulcetSpacing.md) {
                    content(geometry.size.width)
                }
                .padding(.horizontal, DulcetLibraryMetrics.horizontalInset(forWidth: geometry.size.width))
                .padding(.vertical, DulcetSpacing.md)
                .frame(maxWidth: .infinity, alignment: .leading)
            }
        }
        .background(Color.dulcetWindow)
        .dulcetForeground(.primaryTextOnWindow)
        .dulcetNavigationTitle(title, keepsTVNavigationBar: keepsTVNavigationBar)
    }
}

// MARK: - Sections

extension DulcetLibrarySection {
    var title: String {
        switch self {
        case .home: DulcetStrings.readerHome
        case .albums: DulcetStrings.readerAlbums
        case .artists: DulcetStrings.readerArtists
        case .genres: DulcetStrings.readerGenres
        case .playlists: DulcetStrings.readerPlaylists
        case .favourites: DulcetStrings.readerFavorites
        }
    }

    var symbolName: String {
        switch self {
        case .home: "house"
        case .albums: "square.stack"
        case .artists: "music.mic"
        case .genres: "guitars"
        case .playlists: "music.note.list"
        case .favourites: "heart"
        }
    }
}

extension DulcetHomeRow {
    var title: String {
        switch self {
        case .recentlyAdded: DulcetStrings.readerRecentlyAdded
        case .recentlyPlayed: DulcetStrings.readerRecentlyPlayed
        case .mostPlayed: DulcetStrings.readerMostPlayed
        case .favourites: DulcetStrings.readerFavorites
        }
    }

    var symbolName: String {
        switch self {
        case .recentlyAdded: "clock"
        case .recentlyPlayed: "clock.arrow.circlepath"
        case .mostPlayed: "chart.bar"
        case .favourites: "heart"
        }
    }

    /// Where "See All" goes.
    var fullList: DulcetReaderRoute {
        switch self {
        case .recentlyAdded: .albumList(.newest)
        case .recentlyPlayed: .albumList(.recent)
        case .mostPlayed: .albumList(.frequent)
        case .favourites: .section(.favourites)
        }
    }
}

extension DulcetAlbumListType {
    var title: String {
        switch self {
        case .alphabeticalByName: DulcetStrings.readerSortTitle
        case .alphabeticalByArtist: DulcetStrings.readerSortArtist
        case .newest: DulcetStrings.readerSortRecentlyAdded
        case .recent: DulcetStrings.readerSortRecentlyPlayed
        case .frequent: DulcetStrings.readerSortMostPlayed
        case .highest: DulcetStrings.readerSortTopRated
        case .starred: DulcetStrings.readerSortFavorites
        case .random: DulcetStrings.readerSortRandom
        }
    }

    /// The orders the Albums sort picker offers.
    static let sortChoices: [DulcetAlbumListType] = [
        .alphabeticalByName, .alphabeticalByArtist, .newest, .recent, .frequent, .highest, .random,
    ]
}

/// The screen for one section, at the root of the Library stack.
struct DulcetReaderSectionView: View {
    let section: DulcetLibrarySection

    var body: some View {
        switch section {
        case .home: DulcetReaderHomeView()
        case .albums: DulcetReaderAlbumsView()
        case .artists: DulcetReaderSimpleListView(query: .artists, title: section.title)
        case .genres: DulcetReaderSimpleListView(query: .genres, title: section.title)
        case .playlists: DulcetPlaylistsView()
        case .favourites: DulcetReaderFavouritesView()
        }
    }
}

/// Home: the account's state, the sections (on a compact width, where there is no sidebar), and
/// the four shelves.
struct DulcetReaderHomeView: View {
    @Environment(DulcetPresentationStore.self) private var store
#if os(iOS)
    @Environment(\.horizontalSizeClass) private var horizontalSizeClass
#endif

    private var listsSections: Bool {
#if os(iOS)
        horizontalSizeClass == .compact
#else
        false
#endif
    }

    var body: some View {
        DulcetReaderPage(title: DulcetStrings.library) { width in
            DulcetReaderAccountBanner()
            if listsSections {
                // A phone's Library, as the platform's own music app lays it out: every section as
                // a row, then what was added recently as a grid -- one scroll direction throughout.
                VStack(spacing: 0) {
                    ForEach(DulcetLibrarySection.allCases.filter { $0 != .home }) { section in
                        DulcetReaderHomeLink(
                            title: section.title,
                            symbolName: section.symbolName,
                            identifier: "dulcet.reader.section.\(section.rawValue)"
                        ) {
                            store.pushReaderPage(.section(section))
                        }
                    }
                    ForEach([DulcetHomeRow.recentlyPlayed, .mostPlayed]) { row in
                        DulcetReaderHomeLink(
                            title: row.title,
                            symbolName: row.symbolName,
                            identifier: "dulcet.reader.home.\(row.rawValue).all"
                        ) {
                            store.pushReaderPage(row.fullList)
                        }
                    }
                }
                DulcetReaderRecentlyAddedGrid(width: width)
            } else {
                ForEach(DulcetHomeRow.allCases) { row in
                    DulcetReaderShelf(row: row, width: width)
                }
            }
        }
    }
}

/// One row of a phone's Library list.
struct DulcetReaderHomeLink: View {
    let title: String
    let symbolName: String
    let identifier: String
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            HStack(spacing: DulcetSpacing.xs) {
                Image(systemName: symbolName)
                    .frame(width: 28)
                    .dulcetForeground(.accentIconOnWindow)
                    .accessibilityHidden(true)
                Text(title)
                    .font(.title3)
                    .dulcetForeground(.primaryTextOnWindow)
                Spacer()
                Image(systemName: "chevron.forward")
                    .font(.caption.weight(.semibold))
                    .dulcetForeground(.secondaryTextOnWindow)
                    .accessibilityHidden(true)
            }
            .frame(minHeight: 48)
            .contentShape(Rectangle())
        }
        .dulcetMediaButtonStyle()
        .accessibilityIdentifier(identifier)
        Divider()
    }
}

/// Recently added albums as a grid below a phone's Library list, extended as it scrolls.
struct DulcetReaderRecentlyAddedGrid: View {
    let width: CGFloat

    var body: some View {
        DulcetReaderScreen(query: .homeRow(.recentlyAdded)) { model in
            VStack(alignment: .leading, spacing: DulcetSpacing.xs) {
                Text(DulcetHomeRow.recentlyAdded.title)
                    .font(.title2.weight(.semibold))
                    .accessibilityAddTraits(.isHeader)
                    .padding(.top, DulcetSpacing.sm)
                DulcetReaderListBody(model: model) { _ in
                    DulcetReaderGrid(model: model, width: width)
                }
            }
            .accessibilityElement(children: .contain)
            .accessibilityIdentifier("dulcet.reader.home.recentlyAdded")
        }
    }
}

struct DulcetReaderShelf: View {
    @Environment(\.dynamicTypeSize) private var dynamicTypeSize
    @Environment(DulcetPresentationStore.self) private var store
    let row: DulcetHomeRow
    let width: CGFloat

    var body: some View {
        DulcetReaderScreen(query: .homeRow(row)) { model in
            if let window = model.window, window.hasContent || window.freshness != .live {
                VStack(alignment: .leading, spacing: DulcetSpacing.xs) {
                    HStack(alignment: .firstTextBaseline) {
                        Text(row.title)
                            .font(.title2.weight(.semibold))
                            .accessibilityAddTraits(.isHeader)
                        Spacer()
                        if window.hasContent {
                            Button(DulcetStrings.readerSeeAll) {
                                store.pushReaderPage(row.fullList)
                            }
                            .buttonStyle(.borderless)
                            .accessibilityIdentifier("dulcet.reader.home.\(row.rawValue).all")
                        }
                    }
                    if case let .unavailable(reason) = window.freshness, !window.hasContent {
                        Text(DulcetReaderCopy.unavailableLine(reason, subject: .list))
                            .font(.footnote)
                            .dulcetForeground(.secondaryTextOnWindow)
                            .lineLimit(nil)
                    } else if let line = DulcetReaderCopy.freshnessLine(window.freshness) {
                        Text(line)
                            .font(.footnote)
                            .dulcetForeground(.secondaryTextOnWindow)
                            .lineLimit(nil)
                    }
                    ScrollView(.horizontal, showsIndicators: false) {
                        LazyHStack(alignment: .top, spacing: DulcetSpacing.sm) {
                            ForEach(Array(window.items.enumerated()), id: \.element.id) { index, item in
                                DulcetReaderTile(item: item, width: tileWidth)
                                    .dulcetReaderRow(index, in: model)
                            }
                        }
#if os(tvOS)
                        .padding(.vertical, DulcetSpacing.sm)
#endif
                    }
#if os(tvOS)
                    .focusSection()
#endif
                    if window.freshness == .loading, !window.hasContent {
                        ProgressView().frame(maxWidth: .infinity)
                    }
                }
                .accessibilityElement(children: .contain)
                .accessibilityIdentifier("dulcet.reader.home.\(row.rawValue)")
            } else if model.window == nil {
                ProgressView()
                    .frame(maxWidth: .infinity, minHeight: 60)
            }
        }
    }

    private var tileWidth: CGFloat {
#if os(tvOS)
        240
#else
        dynamicTypeSize.isAccessibilitySize ? 190 : (width < DulcetLibraryMetrics.compactWidthThreshold ? 140 : 170)
#endif
    }
}

/// Albums, in the order the person chose.
struct DulcetReaderAlbumsView: View {
    @AppStorage("dulcet.reader.albumSort") private var sortRaw = DulcetAlbumListType.alphabeticalByName.rawValue

    private var sort: DulcetAlbumListType {
        DulcetAlbumListType(rawValue: sortRaw) ?? .alphabeticalByName
    }

    var body: some View {
        DulcetReaderAlbumGridView(type: sort, title: DulcetStrings.readerAlbums, sort: $sortRaw)
            // A different order is a different list: a new window, read from its start.
            .id(sort)
    }
}

/// One album list as a grid, with a sort picker when `sort` is given.
struct DulcetReaderAlbumGridView: View {
    let type: DulcetAlbumListType
    let title: String
    var sort: Binding<String>?
    /// The Albums section's root keeps Apple TV's navigation bar; a pushed "See All" hides it.
    var keepsTVNavigationBar = true

    var body: some View {
        DulcetReaderScreen(query: .albums(type)) { model in
            DulcetReaderPage(title: title, keepsTVNavigationBar: keepsTVNavigationBar) { width in
                if let sort {
                    Picker(DulcetStrings.readerSortBy, selection: sort) {
                        ForEach(DulcetAlbumListType.sortChoices) { choice in
                            Text(choice.title).tag(choice.rawValue)
                        }
                    }
#if os(tvOS)
                    .pickerStyle(.automatic)
#else
                    .pickerStyle(.menu)
                    .fixedSize()
#endif
                    .accessibilityIdentifier("dulcet.reader.albums.sort")
                }
                DulcetReaderListBody(model: model) { _ in
                    DulcetReaderGrid(model: model, width: width)
                }
            }
        }
    }
}

/// Artists, genres or playlists: a list of rows opening their pages.
struct DulcetReaderSimpleListView: View {
    let query: DulcetLibraryQuery
    let title: String

    var body: some View {
        DulcetReaderScreen(query: query) { model in
            DulcetReaderPage(title: title) { _ in
                DulcetReaderListBody(model: model) { window in
                    LazyVStack(alignment: .leading, spacing: 0) {
                        ForEach(Array(window.items.enumerated()), id: \.element.id) { index, item in
                            DulcetReaderListRow(item: item)
                                .dulcetReaderRow(index, in: model)
                            Divider()
                        }
                    }
                }
            }
        }
    }
}

/// Favourites: artists, albums and tracks the person marked, grouped by kind.
struct DulcetReaderFavouritesView: View {
    @Environment(DulcetPresentationStore.self) private var store

    var body: some View {
        DulcetReaderScreen(query: .favourites) { model in
            DulcetReaderPage(title: DulcetStrings.readerFavorites) { width in
                DulcetReaderListBody(model: model) { window in
                    let items = Array(window.items.enumerated())
                    let artists = items.filter { $0.element.kind == .artist }
                    let albums = items.filter { $0.element.kind == .album }
                    let tracks = items.filter { $0.element.kind == .track }
                    if !artists.isEmpty {
                        header(DulcetStrings.readerArtists)
                        ForEach(artists, id: \.element.id) { index, item in
                            DulcetReaderListRow(item: item).dulcetReaderRow(index, in: model)
                        }
                    }
                    if !albums.isEmpty {
                        header(DulcetStrings.readerAlbums)
                        ScrollView(.horizontal, showsIndicators: false) {
                            LazyHStack(alignment: .top, spacing: DulcetSpacing.sm) {
                                ForEach(albums, id: \.element.id) { index, item in
                                    DulcetReaderTile(item: item, width: 150).dulcetReaderRow(index, in: model)
                                }
                            }
                        }
                    }
                    if !tracks.isEmpty {
                        header(DulcetStrings.readerSongs)
                        let playable = tracks.compactMap(\.element.playableTrack).filter { $0.availability == .playable }
                        ForEach(Array(tracks.enumerated()), id: \.element.element.id) { position, entry in
                            DulcetReaderTrackRow(item: entry.element, index: position + 1) { track in
                                store.playReaderTracks(
                                    playable,
                                    startingAt: track,
                                    sourceKind: .library,
                                    sourceID: nil,
                                    sourceName: DulcetStrings.readerFavorites
                                )
                            }
                            .dulcetReaderRow(entry.offset, in: model)
                        }
                    }
                }
            }
        }
    }

    private func header(_ title: String) -> some View {
        Text(title)
            .font(.title2.weight(.semibold))
            .accessibilityAddTraits(.isHeader)
            .padding(.top, DulcetSpacing.xs)
    }
}

// MARK: - Pages

/// A reader page on the Library stack.
struct DulcetReaderRouteView: View {
    let route: DulcetReaderRoute

    var body: some View {
        switch route {
        case let .section(section): DulcetReaderSectionView(section: section)
        case let .album(id): DulcetReaderTrackListPage(query: .album(rawID: id.rawID), kind: .album, id: id)
        case let .playlist(id): DulcetPlaylistPage(id: id)
        case let .artist(id): DulcetReaderArtistPage(id: id)
        case let .genre(name): DulcetReaderTrackListPage(query: .songsByGenre(name), kind: .genre, id: nil, genreName: name)
        case let .albumList(type): DulcetReaderAlbumGridView(type: type, title: type.title, keepsTVNavigationBar: false)
        }
    }
}

/// An album, a playlist or a genre: a header, then its tracks.
struct DulcetReaderTrackListPage: View {
    @Environment(\.dynamicTypeSize) private var dynamicTypeSize
    @Environment(DulcetPresentationStore.self) private var store
    let query: DulcetLibraryQuery
    let kind: DulcetReaderItemKind
    let id: DulcetProviderItemID?
    var genreName: String?

    var body: some View {
        DulcetReaderScreen(query: query) { model in
            DulcetReaderPage(title: title(model.window), keepsTVNavigationBar: false) { width in
                if let window = model.window {
                    header(window, width: width)
                    let subject: DulcetReaderCopy.Subject = window.header == nil ? (kind == .album ? .album : .list) : .albumTracks
                    DulcetReaderListBody(model: model, subject: subject) { window in
                        tracks(window, model: model)
                    }
                } else {
                    ProgressView().frame(maxWidth: .infinity, minHeight: 120)
                }
            }
        }
    }

    private func title(_ window: DulcetLibraryWindow?) -> String {
        genreName ?? window?.header?.displayTitle ?? ""
    }

    private var sourceKind: DulcetPlaybackQueueSourceKind {
        switch kind {
        case .album: .album
        case .playlist: .playlist
        default: .library
        }
    }

    @ViewBuilder
    private func header(_ window: DulcetLibraryWindow, width: CGFloat) -> some View {
        let compact = width < DulcetLibraryMetrics.compactWidthThreshold
        let artworkSize: CGFloat = compact ? min(260, width - 64) : 220
        let item = window.header
        let layout = compact
            ? AnyLayout(VStackLayout(alignment: .center, spacing: DulcetSpacing.sm))
            : AnyLayout(HStackLayout(alignment: .bottom, spacing: DulcetSpacing.md))
        layout {
            if let item, kind != .genre {
                DulcetArtworkView(artwork: item.artwork, size: artworkSize)
            }
            VStack(alignment: compact ? .center : .leading, spacing: DulcetSpacing.xxs) {
                Text(genreName ?? item?.displayTitle ?? String())
                    .font(.title.weight(.bold))
                    .multilineTextAlignment(compact ? .center : .leading)
                    .lineLimit(nil)
                    .accessibilityAddTraits(.isHeader)
                    .accessibilityIdentifier("dulcet.\(kind.rawValue).title")
                if let item, !item.credits.isEmpty {
                    DulcetArtistLink(credits: item.credits, font: .title3)
                }
                if let detail = detailLine(item, window: window), !detail.isEmpty {
                    Text(detail)
                        .font(.subheadline)
                        .dulcetForeground(.secondaryTextOnWindow)
                        .lineLimit(nil)
                }
                headerActions(window, item: item)
                    .padding(.top, DulcetSpacing.xxs)
            }
            .frame(maxWidth: compact ? .infinity : nil, alignment: compact ? .center : .leading)
        }
        .frame(maxWidth: .infinity, alignment: compact ? .center : .leading)
    }

    /// Play, Shuffle and the heart in one row while their labels fit on one line each; otherwise
    /// -- at the accessibility text sizes on a phone -- stacked, Play and Shuffle full width. A
    /// row squeezed narrower than its labels wraps them a letter per line into buttons taller
    /// than the screen, which push the tracks out of reach.
    private func headerActions(_ window: DulcetLibraryWindow, item: DulcetReaderItem?) -> some View {
        ViewThatFits(in: .horizontal) {
            HStack(spacing: DulcetSpacing.xs) {
                playButton(window, fills: false)
                shuffleButton(window, fills: false)
                favouriteButton(item, window: window)
            }
            .lineLimit(1)
            VStack(spacing: DulcetSpacing.xs) {
                playButton(window, fills: true)
                shuffleButton(window, fills: true)
                favouriteButton(item, window: window)
            }
        }
    }

    private func playButton(_ window: DulcetLibraryWindow, fills: Bool) -> some View {
        let playable = window.playableTracks
        return DulcetProminentAction(
            DulcetStrings.play,
            systemImage: "play.fill",
            isEnabled: !playable.isEmpty,
            fillsWidth: fills
        ) {
            store.playReaderTracks(playable, sourceKind: sourceKind, sourceID: id, sourceName: title(window))
        }
        .dulcetTakesFocusOnArrival(isEnabled: !playable.isEmpty)
        .accessibilityIdentifier("dulcet.\(kind.rawValue).play")
    }

    private func shuffleButton(_ window: DulcetLibraryWindow, fills: Bool) -> some View {
        let playable = window.playableTracks
        return Button {
            store.playReaderTracks(playable, shuffle: true, sourceKind: sourceKind, sourceID: id, sourceName: title(window))
        } label: {
            Label(DulcetStrings.shuffle, systemImage: "shuffle")
                .frame(maxWidth: fills ? .infinity : nil)
        }
        .buttonStyle(.bordered)
        .disabled(playable.isEmpty)
        .accessibilityIdentifier("dulcet.\(kind.rawValue).shuffle")
    }

    @ViewBuilder
    private func favouriteButton(_ item: DulcetReaderItem?, window: DulcetLibraryWindow) -> some View {
        if let item, let target = item.favouriteTarget {
            DulcetFavouriteButton(
                target: target,
                published: item.isFavourite,
                title: item.displayTitle,
                size: .title3,
                identifier: "dulcet.\(kind.rawValue).favorite"
            )
            // A page with nothing to play -- every track without a length -- opens on its heart.
            .dulcetTakesFocusOnArrival(isEnabled: window.playableTracks.isEmpty)
        }
    }

    private func detailLine(_ item: DulcetReaderItem?, window: DulcetLibraryWindow) -> String? {
        var parts: [String] = []
        if let genre = item?.genre, !genre.isEmpty { parts.append(genre) }
        if let year = item?.year { parts.append(String(year)) }
        if let count = item?.songCount ?? window.total {
            parts.append(DulcetStrings.readerCount(.tracks, count))
        }
        if let owner = item?.owner, !owner.isEmpty, kind == .playlist { parts.append(owner) }
        return parts.joined(separator: " \u{00B7} ")
    }

    @ViewBuilder
    private func tracks(_ window: DulcetLibraryWindow, model: DulcetLibraryWindowModel) -> some View {
        let playable = window.playableTracks
        let discs = Set(window.items.compactMap(\.discNumber))
        let albumArtists = window.header?.artistName.map { [$0] } ?? []
        LazyVStack(alignment: .leading, spacing: 0) {
            ForEach(Array(window.items.enumerated()), id: \.element.id) { index, item in
                if kind == .album, discs.count > 1, let disc = item.discNumber,
                   index == 0 || window.items[index - 1].discNumber != disc {
                    Text(DulcetStrings.discTitle(disc))
                        .font(.headline)
                        .dulcetForeground(.secondaryTextOnWindow)
                        .padding(.top, DulcetSpacing.sm)
                        .accessibilityAddTraits(.isHeader)
                }
                DulcetReaderTrackRow(
                    item: item,
                    index: window.leadingOffset + index + 1,
                    showsAlbum: kind != .album,
                    showsArtwork: kind != .album,
                    albumArtists: albumArtists
                ) { track in
                    store.playReaderTracks(playable, startingAt: track, sourceKind: sourceKind, sourceID: id, sourceName: title(window))
                }
                .dulcetReaderRow(index, in: model)
                Divider()
            }
        }
    }
}

/// An artist: a header, then the artist's albums.
struct DulcetReaderArtistPage: View {
    let id: DulcetProviderItemID

    var body: some View {
        DulcetReaderScreen(query: .artist(rawID: id.rawID)) { model in
            DulcetReaderPage(title: model.window?.header?.displayTitle ?? "", keepsTVNavigationBar: false) { width in
                if let window = model.window {
                    if let header = window.header {
                        HStack(alignment: .center, spacing: DulcetSpacing.md) {
                            DulcetArtworkView(artwork: header.artwork, size: 120)
                                .clipShape(Circle())
                            VStack(alignment: .leading, spacing: DulcetSpacing.xxs) {
                                Text(header.displayTitle)
                                    .font(.largeTitle.weight(.bold))
                                    .lineLimit(nil)
                                    .accessibilityAddTraits(.isHeader)
                                if let count = header.albumCount {
                                    Text(DulcetStrings.readerCount(.albums, count))
                                        .font(.subheadline)
                                        .dulcetForeground(.secondaryTextOnWindow)
                                }
                            }
                            Spacer(minLength: 0)
                            if let target = header.favouriteTarget {
                                DulcetFavouriteButton(target: target, published: header.isFavourite, title: header.displayTitle, size: .title2)
                                    .dulcetTakesFocusOnArrival()
                            }
                        }
                    }
                    DulcetReaderListBody(model: model, subject: window.header == nil ? .list : .albumTracks) { _ in
                        DulcetReaderGrid(model: model, width: width) { item in
                            // The page says whose albums these are; the year is the tile's news.
                            item.year.map(String.init) ?? ""
                        }
                    }
                } else {
                    ProgressView().frame(maxWidth: .infinity, minHeight: 120)
                }
            }
        }
    }
}

// MARK: - The Library root

/// The Library destination while the reader draws it: the section at the stack's root.
struct DulcetReaderLibraryRoot: View {
    @Environment(DulcetPresentationStore.self) private var store
#if os(iOS)
    @Environment(\.horizontalSizeClass) private var horizontalSizeClass
#endif

    var body: some View {
#if os(tvOS)
        VStack(alignment: .leading, spacing: 0) {
            DulcetReaderTVSectionBar()
            DulcetReaderSectionView(section: store.librarySection)
                .id(store.librarySection)
        }
#elseif os(iOS)
        // A phone keeps its sections under Home; the sidebar lists them at a regular width.
        DulcetReaderSectionView(section: horizontalSizeClass == .compact ? .home : store.librarySection)
            .id(horizontalSizeClass == .compact ? .home : store.librarySection)
#else
        DulcetReaderSectionView(section: store.librarySection)
            .id(store.librarySection)
#endif
    }
}

#if os(tvOS)
/// The library's sections across the top of Library, under the app's own section bar.
///
/// A launch that opens into Library, or the app bringing Library forward itself, puts remote focus
/// here, on the section showing (``DulcetArrivalFocus``): it is always drawn, whatever the reader
/// has painted yet, and one Up press from it reaches the app's bar.
private struct DulcetReaderTVSectionBar: View {
    @Environment(DulcetPresentationStore.self) private var store
    @Environment(DulcetArrivalFocus.self) private var arrivalFocus: DulcetArrivalFocus?
    @FocusState private var focusedSection: DulcetLibrarySection?

    var body: some View {
        ScrollView(.horizontal, showsIndicators: false) {
            HStack(spacing: DulcetSpacing.xs) {
                ForEach(DulcetLibrarySection.allCases) { section in
                    let selected = section == store.librarySection
                    Button {
                        store.selectLibrarySection(section)
                    } label: {
                        Label(section.title, systemImage: section.symbolName)
                            .font(.callout.weight(selected ? .semibold : .regular))
                    }
                    .buttonStyle(.bordered)
                    .focused($focusedSection, equals: section)
                    .accessibilityIdentifier("dulcet.reader.section.\(section.rawValue)")
                    .accessibilityAddTraits(selected ? .isSelected : [])
                }
            }
            .padding(.horizontal, DulcetSpacing.lg)
            .padding(.vertical, DulcetSpacing.xs)
        }
        .focusSection()
        .onAppear(perform: claimArrivalFocus)
        // The focus engine can place launch focus on the app's bar after this appeared.
        .onChange(of: arrivalFocus?.wantsSectionFocus == true) { _, wants in
            if wants { claimArrivalFocus() }
        }
        .onChange(of: focusedSection) { _, current in
            if current != nil { arrivalFocus?.sectionControlFocused() }
        }
    }

    private func claimArrivalFocus() {
        // Under a pushed page this row is not on screen; the page is the arrival, not the row.
        guard let arrivalFocus, arrivalFocus.pending, store.readerPath.isEmpty else { return }
        focusedSection = store.librarySection
    }
}
#endif

// MARK: - Search

/// Search while the reader draws it: its own subscription, fed the field's text from the first
/// character (§16.15).
struct DulcetReaderSearchScreen: View {
    @Bindable var store: DulcetPresentationStore
    @State private var model = DulcetReaderSearchModel()

    var body: some View {
        DulcetSearchView(
            snapshot: store.snapshot,
            searchQuery: $store.searchQuery,
            onLoadMore: { _ in },
            onRetry: { model.retry { dulcetReaderRetry(store) } },
            onActivateResult: { _ in },
            focusRequested: store.searchFocusRequested,
            onFocusRequestHandled: store.searchFocusRequestHandled,
            reader: DulcetReaderSearchContent(
                query: model.query,
                publication: model.current,
                onActivate: { row in store.activateReaderSearchRow(row, in: model.current) }
            )
        )
        .onAppear(perform: open)
        .onDisappear { model.close() }
        .onChange(of: store.searchQuery) { _, query in model.updateQuery(query) }
        .onChange(of: store.librarySession?.readerGeneration) { _, _ in open() }
    }

    private func open() {
        guard let session = store.librarySession else { return }
        model.open(in: session, query: store.searchQuery)
    }
}

// MARK: - Notices

extension View {
    /// Shows the library session's latest notice briefly: a change that was not saved or is
    /// kept unsent, or why a row did not play.
    func dulcetLibraryNotices(store: DulcetPresentationStore) -> some View {
        modifier(DulcetLibraryNoticeOverlay(store: store))
    }
}

private struct DulcetLibraryNoticeOverlay: ViewModifier {
    @Bindable var store: DulcetPresentationStore

    func body(content: Content) -> some View {
        content.overlay(alignment: .top) {
            if let notice = store.librarySession?.notice {
                Text(notice.message)
                    .font(.callout.weight(.medium))
                    .dulcetForeground(.primaryTextOnRegularMaterial)
                    .multilineTextAlignment(.center)
                    .lineLimit(nil)
                    .padding(.horizontal, DulcetSpacing.md)
                    .padding(.vertical, DulcetSpacing.xs)
                    .background(.regularMaterial, in: Capsule())
                    .padding(.top, DulcetSpacing.sm)
                    .padding(.horizontal, DulcetSpacing.md)
                    .transition(.move(edge: .top).combined(with: .opacity))
                    .onTapGesture { store.librarySession?.dismissNotice(notice.id) }
                    // A tap dismisses it, so VoiceOver must hear it as something to press.
                    .accessibilityAddTraits(.isButton)
                    .accessibilityIdentifier("dulcet.reader.notice")
                    .task(id: notice.id) {
#if !os(tvOS)
                        AccessibilityNotification.Announcement(notice.message).post()
#endif
                        try? await Task.sleep(for: .seconds(4))
                        store.librarySession?.dismissNotice(notice.id)
                    }
            }
        }
        .animation(.default, value: store.librarySession?.notice)
    }
}

// MARK: - Sign-out

extension View {
    /// The offer for changes that have not reached the server (§14.7 step 2).
    func dulcetSignOutOffer(store: DulcetPresentationStore) -> some View {
        modifier(DulcetSignOutOfferDialog(store: store))
    }
}

private struct DulcetSignOutOfferDialog: ViewModifier {
    @Bindable var store: DulcetPresentationStore

    func body(content: Content) -> some View {
        content.alert(
            title,
            isPresented: Binding(
                get: { store.signOutOffer != nil },
                set: { presented in if !presented, store.signOutOffer?.phase != .sending { store.dismissSignOutOffer() } }
            ),
            presenting: store.signOutOffer
        ) { offer in
            switch offer.phase {
            case .sending:
                Button(DulcetStrings.cancel, role: .cancel) { store.dismissSignOutOffer() }
            case .asking(online: true):
                Button(DulcetStrings.readerSignOutSend) { store.sendChangesThenSignOut() }
                Button(DulcetStrings.readerSignOutDiscard, role: .destructive) { store.signOutDiscardingChanges() }
                Button(DulcetStrings.readerSignOutPendingStay, role: .cancel) { store.dismissSignOutOffer() }
            case .asking(online: false), .sendFailed:
                Button(DulcetStrings.readerSignOutDiscard, role: .destructive) { store.signOutDiscardingChanges() }
                Button(DulcetStrings.readerSignOutPendingStay, role: .cancel) { store.dismissSignOutOffer() }
            }
        } message: { offer in
            Text(message(offer))
        }
    }

    private var title: String { DulcetStrings.readerSignOutTitle }

    private func message(_ offer: DulcetSignOutOffer) -> String {
        let count = offer.pendingCount.map { Int(clamping: $0) }
        switch offer.phase {
        case .sending:
            return DulcetStrings.readerSignOutSending
        case .sendFailed:
            let pending = count.map(DulcetStrings.readerSignOutPending) ?? DulcetStrings.readerSignOutPendingUnknown
            return DulcetStrings.readerSignOutSendFailed + " " + pending
        case .asking(online: true):
            return count.map(DulcetStrings.readerSignOutPendingOnline) ?? DulcetStrings.readerSignOutPendingUnknown
        case .asking(online: false):
            return count.map(DulcetStrings.readerSignOutPending) ?? DulcetStrings.readerSignOutPendingUnknown
        }
    }
}
#endif
