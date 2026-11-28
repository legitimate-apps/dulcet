#if os(macOS) || os(iOS) || os(tvOS)
import SwiftUI

// The library the reader draws (spec §16.18, the Apple paragraph), on every Apple destination.
//
// Each screen opens one window while it is visible and closes it when it is not. What a screen
// says about its content -- how old it is, whether the list is whole, what cannot play offline --
// comes from `DulcetReaderCopy`, so no two screens word the same state differently. "Try Again"
// is always the reconnect, never a screen's own re-read (§16.14).

private struct DulcetAccountMessageShownKey: EnvironmentKey {
    static let defaultValue = false
}

extension EnvironmentValues {
    var dulcetAccountMessageShown: Bool {
        get { self[DulcetAccountMessageShownKey.self] }
        set { self[DulcetAccountMessageShownKey.self] = newValue }
    }
}

// MARK: - A screen's window

/// Opens a window for `query` while on screen and hands it to `content`.
struct DulcetReaderScreen<Content: View>: View {
    @Environment(DulcetPresentationStore.self) private var store
    @State private var model: DulcetLibraryWindowModel
    @Environment(\.dulcetAccountMessageShown) private var accountMessageShown
    private let content: (DulcetLibraryWindowModel) -> Content
    private let showsDestinationStatus: Bool

    init(query: DulcetLibraryQuery, showsDestinationStatus: Bool = false, @ViewBuilder content: @escaping (DulcetLibraryWindowModel) -> Content) {
        _model = State(initialValue: DulcetLibraryWindowModel(query: query))
        self.content = content
        self.showsDestinationStatus = showsDestinationStatus
    }

    var body: some View {
        dulcetFocusReachable(content(model))
            .environment(\.dulcetAccountMessageShown, accountMessageShown ||
                (showsDestinationStatus && !model.query.isDownloaded &&
                    store.readerConnectionIndicator(serverReadFailed: model.window?.freshness.isConnectionFailure == true) != nil))
            .safeAreaInset(edge: .top, spacing: 0) {
                if showsDestinationStatus {
                    DulcetReaderStatusView(window: model.window, localCollection: model.query.isDownloaded)
#if os(tvOS)
                        .focusSection()
#endif
                }
            }
            .onAppear(perform: open)
            .onDisappear { model.close() }
            // A different account's reader: the rows go, and the screen reads again.
            .onChange(of: store.librarySession?.readerGeneration) { _, _ in open() }
    }

    private func open() {
        guard let session = store.librarySession else { return }
        model.open(in: session)
    }

    /// The status's Try Again sits above and left of a narrow page such as Add to Playlist's rows.
    /// The focus engine moves only to what lies in the pressed direction, so neither Down nor Right
    /// left that button (OBSERVED in the offline chooser). As a section, the page answers from there.
    @ViewBuilder private func dulcetFocusReachable(_ page: Content) -> some View {
#if os(tvOS)
        if showsDestinationStatus { page.focusSection() } else { page }
#else
        page
#endif
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
    @Environment(\.dulcetAccountMessageShown) private var accountMessageShown
    let window: DulcetLibraryWindow?
    var localCollection = false

    var body: some View {
        let plan = DulcetReaderCopy.statusPlan(
            window: window,
            indicator: store.readerConnectionIndicator(serverReadFailed: window?.freshness.isConnectionFailure == true),
            accountMessageShown: accountMessageShown,
            localCollection: localCollection,
            reconnectAvailable: reconnectAvailable)
        if let indicator = plan.indicator {
            DulcetConnectionStatus(indicator: indicator, identifier: "dulcet.reader.status.connection",
                retry: { dulcetReaderRetry(store) }, accountBannerOpaque: true)
        } else if !plan.isEmpty {
            VStack(alignment: .leading, spacing: DulcetSpacing.xxs) {
                ForEach(plan.lines, id: \.self) { line in
                    Text(line)
                        .font(.footnote)
                        .dulcetForeground(.secondaryTextOnWindow)
                        .lineLimit(nil)
                        .fixedSize(horizontal: false, vertical: true)
                }
                if plan.offersRetry { DulcetReaderRetryButton() }
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            .accessibilityElement(children: .contain)
            .padding(.vertical, 8)
            .padding(.horizontal, 16)
            .background(.background, ignoresSafeAreaEdges: [])
            .accessibilityIdentifier("dulcet.reader.status")
        }
    }

    /// Whether a reconnect is worth offering for a stale or partial page: with the network down
    /// there is nothing to retry until it returns.
    private var reconnectAvailable: Bool {
        store.librarySession?.isDeviceOffline != true
    }
}

struct DulcetReaderRetryButton: View {
    @Environment(DulcetPresentationStore.self) private var store

    var body: some View {
        Button(
            DulcetStrings.readerTryAgain,
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
    @Environment(DulcetPresentationStore.self) private var store
    @Environment(\.dulcetAccountMessageShown) private var accountMessageShown
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
            if reason != .gone, !reason.isConnectionFailure, !accountMessageShown,
               store.readerConnectionIndicator() == nil {
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

extension DulcetPresentationStore {
    func readerConnectionIndicator(serverReadFailed: Bool = false) -> DulcetConnectionIndicator? {
        librarySession?.connectionIndicator(connecting: snapshot.accountConnection == .connecting,
            serverReadFailed: serverReadFailed)
    }
}

/// The common connection message. A different or withdrawn cause cancels the pending
/// presentation, and a confirmed loss of the device's path has already waited in the session.
struct DulcetConnectionStatus: View {
    let indicator: DulcetConnectionIndicator?
    var identifier = "dulcet.destination.status"
    var retryIdentifier = "dulcet.reader.retry"
    let retry: () -> Void
    var accountBannerOpaque: Bool? = nil
    var freshness: String? = nil
    @State private var settled: DulcetConnectionIndicator?

    var body: some View {
        ZStack(alignment: .leading) {
            if let indicator, indicator.presentationDelay == .zero || indicator == settled {
                let status = DulcetDestinationStatus(title: indicator.title, symbol: indicator.symbol, identifier: identifier,
                    retryIdentifier: retryIdentifier, retry: indicator.offersRetry ? retry : nil)
                if let accountBannerOpaque {
                    status.modifier(DulcetAccountBannerFrame(opaque: accountBannerOpaque))
                } else { status }
            } else if indicator == nil, let freshness {
                Text(freshness)
                    .font(.caption)
                    .dulcetForeground(.secondaryTextOnWindow)
                    .accessibilityIdentifier(identifier)
            }
        }
        .task(id: indicator) {
            settled = nil
            guard let indicator, indicator.presentationDelay != .zero else { return }
            do {
                try await Task.sleep(for: indicator.presentationDelay)
                if !Task.isCancelled { settled = indicator }
            } catch {
                // The cause cleared or the screen went away.
            }
        }
    }
}

/// Connection state at the top of a landing page, including a failed read by a shelf.
struct DulcetReaderAccountBanner: View {
    @Environment(DulcetPresentationStore.self) private var store
    var opaque = true
    var serverReadFailed = false

    var body: some View {
        let deviceOnly = store.librarySession?.mode == .deviceOnly
        DulcetConnectionStatus(indicator: store.readerConnectionIndicator(serverReadFailed: serverReadFailed),
            identifier: deviceOnly ? "dulcet.reader.deviceOnly" : "dulcet.destination.status",
            retryIdentifier: deviceOnly ? "dulcet.reader.reconnect" : "dulcet.reader.retry",
            retry: { dulcetReaderRetry(store) }, accountBannerOpaque: opaque)
    }
}

/// Multiple shelves can encounter the same failed read. Their landing page owns its one status.
struct DulcetReaderServerFailure: PreferenceKey {
    static let defaultValue = false
    static func reduce(value: inout Bool, nextValue: () -> Bool) { value = value || nextValue() }
}

/// The account banner's place over the page: on the content margin, `DulcetSpacing.xs` above and
/// below, so the page's own top inset brings the first row `DulcetSpacing.lg` under the status.
private struct DulcetAccountBannerFrame: ViewModifier {
    let opaque: Bool
    func body(content: Content) -> some View {
        content
            .padding(.vertical, DulcetSpacing.xs)
            .dulcetContentMarginPadding()
#if os(tvOS)
            .background(Color.dulcetWindow, ignoresSafeAreaEdges: [])
#else
            .background(opaque ? AnyShapeStyle(.background) : AnyShapeStyle(.clear), ignoresSafeAreaEdges: [])
#endif
    }
}

// MARK: - Favourites

private extension View {
    @ViewBuilder
    func dulcetFavouriteChrome(tvAction: Bool) -> some View {
#if os(tvOS)
        if tvAction {
            buttonStyle(DulcetTVActionButtonStyle(shape: .circle))
        } else {
            buttonStyle(.borderless)
        }
#else
        buttonStyle(.borderless)
#endif
    }
}

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
    /// Apple TV: in a detail page's action row, the row's circular control rather than a bare glyph.
    var usesTVActionChrome = false

    var body: some View {
        if let session = store.librarySession {
            let on = session.isFavourite(target.id, published: published)
            let state = session.favouriteState(target)
            Button {
                let accepted = session.toggleFavourite(target, published: published)
                DulcetUIProofMarkers.record("favorite:\(identifier):accepted=\(accepted)")
            } label: {
                ZStack(alignment: .bottomTrailing) {
                    Image(systemName: on ? "heart.fill" : "heart")
                        .font(size)
                        .dulcetForeground(usesTVActionChrome ? .primaryTextOnWindow : .accentIconOnWindow)
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
            .dulcetFavouriteChrome(tvAction: usesTVActionChrome)
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
                minimumSide: minimumSide,
                usesTVActionChrome: Self.usesTVActionChrome
            )
        }
    }

#if os(tvOS)
    private static let usesTVActionChrome = true
#else
    private static let usesTVActionChrome = false
#endif
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
            .accessibilityIdentifier("dulcet.\(target.kind.rawValue).favorite")
        }
    }
}

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
                sourceKind: item.playbackSourceKind,
                sourceID: item.playbackSourceID,
                sourceName: window.header?.displayTitle ?? item.displayTitle
            )
        }
    }

    /// An artist's Play: Top Songs when the server knows them, and otherwise the artist's albums,
    /// so an artist with music always has a Play that works.
    func playArtist(
        id: DulcetProviderItemID, name: String, topSongs: [DulcetTrack], albums: [DulcetReaderItem], shuffle: Bool
    ) {
        if topSongs.isEmpty {
            resolveAndPlayAlbums(albums, shuffle: shuffle, sourceID: id, sourceName: name)
        } else {
            playReaderTracks(topSongs, shuffle: shuffle, sourceKind: .artist, sourceID: id, sourceName: name)
        }
    }

    /// Every track of `albums`, in their order, handed to the player once the last has answered.
    /// What an artist's Play means when the server knows no top songs for them.
    func resolveAndPlayAlbums(
        _ albums: [DulcetReaderItem],
        shuffle: Bool,
        sourceID: DulcetProviderItemID,
        sourceName: String
    ) {
        func read(_ remaining: ArraySlice<DulcetLibraryQuery>, into collected: [DulcetTrack]) {
            guard let query = remaining.first else {
                if !collected.isEmpty {
                    playReaderTracks(collected, shuffle: shuffle, sourceKind: .artist, sourceID: sourceID, sourceName: sourceName)
                }
                return
            }
            librarySession?.resolveTracks(of: query) { tracks, _ in
                read(remaining.dropFirst(), into: collected + tracks)
            }
        }
        read(albums.compactMap(\.trackListQuery)[...], into: [])
    }

    /// An album's tracks, read from the reader, each requested for download unless it is already
    /// on this device or on its way. The same request a track row's Download makes, once per
    /// track, so the controller's own rules (connection, storage, duplicates) still decide.
    func resolveAndDownload(_ item: DulcetReaderItem) {
        guard downloadsEnabled, let query = item.trackListQuery else { return }
        librarySession?.resolveTracks(of: query) { [weak self] tracks, _ in
            guard let self else { return }
            guard !tracks.isEmpty else {
                self.librarySession?.post(DulcetStrings.readerNothingPlayable)
                return
            }
            for track in Self.tracksToDownload(tracks, state: { self.downloadState(for: $0) }) {
                self.requestDownload(track)
            }
        }
    }

    /// The tracks of a list that a Download asks for: those neither on the device nor already
    /// queued or downloading. A failed or interrupted download is asked for again.
    nonisolated static func tracksToDownload(
        _ tracks: [DulcetTrack],
        state: (DulcetTrack) -> DulcetDownloadState
    ) -> [DulcetTrack] {
        tracks.filter { track in
            switch state(track) {
            case .notDownloaded, .cached, .failed, .interrupted: true
            case .queued, .downloading, .downloaded, .stale: false
            }
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
            kind: item.playbackSourceKind,
            id: item.playbackSourceID,
            title: item.displayTitle,
            completion: completion
        )
    }

    func resolveReaderAddition(
        query: DulcetLibraryQuery,
        kind: DulcetPlaybackQueueSourceKind,
        id: DulcetProviderItemID?,
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
        // Using a result ends the typing: the keyboard would sit over the mini player that shows
        // the song has started, or over the page an album or artist opens.
        dismissSearchKeyboard()
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

    /// The Top Result card's Play: a song plays as a tap on its row does, and an album plays from
    /// its first track. An artist has no Play on the card; its tap opens the page.
    func playSearchTopResult(_ row: DulcetReaderSearchRow, in publication: DulcetReaderSearchPublication?) {
        switch row.result.kind {
        case .track, .artist:
            if playsTopResult(row) {
                sendPlaybackControl(.pause)
            } else {
                activateReaderSearchRow(row, in: publication)
            }
        case .album:
            if playsTopResult(row) {
                sendPlaybackControl(.pause)
                return
            }
            dismissSearchKeyboard()
            if row.isUnavailableOffline {
                librarySession?.post(DulcetStrings.readerPlaysOnReconnect)
                return
            }
            resolveAndPlayAlbumRow(row)
        }
    }

    /// What a Top Result's Play stands for: the song itself, or the album played from its first track.
    /// An artist has no Play on the card.
    func topResultTarget(_ row: DulcetReaderSearchRow) -> DulcetPlayTarget? {
        switch row.result.kind {
        case .track: .song(row.id)
        case .album: .collection(.album, row.id)
        case .artist: nil
        }
    }

    /// Its own music is sounding, so the card's Play reads Pause and a press pauses.
    func playsTopResult(_ row: DulcetReaderSearchRow) -> Bool {
        playsTarget(topResultTarget(row))
    }

    private func resolveAndPlayAlbumRow(_ row: DulcetReaderSearchRow) {
        librarySession?.resolveTracks(of: .album(rawID: row.id.rawID)) { [weak self] tracks, window in
            self?.playReaderTracks(
                tracks, sourceKind: .album, sourceID: row.id,
                sourceName: window.header?.displayTitle ?? row.result.displayTitle
            )
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
            .task(id: isReturnTarget) { await claim() }
    }

    private var isReturnTarget: Bool {
        guard let route, let landed = store.readerReturn else { return false }
        return landed.opened == route && landed.page == page && store.readerPath.last == page
    }

    /// A claim made while the pop is still settling can be lost, and the engine then leaves focus on
    /// the page's first control, Sort By (OBSERVED on a loaded simulator, once in two proofs). So it
    /// claims until focus has held here for a moment, then gives up the return, within a bound that
    /// leaves a remote free again if this control can never take focus.
    private func claim() async {
        var held = 0
        for _ in 0..<20 where isReturnTarget && held < 3 {
            focused = true
            try? await Task.sleep(for: .milliseconds(150))
            if Task.isCancelled { return }
            held = focused ? held + 1 : 0
        }
        if isReturnTarget { store.readerReturnFocused() }
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
    var rank: Int? = nil
    /// A page with room beside its tiles lets a long title take a second line.
    var titleLineLimit = 1
    /// The first tile of a page whose sort control comes before it: a remote arrives on the content.
    var takesFocusOnArrival = false
    var showsTVActionsMenu = false
    var showsLibraryQuickAction = false
#if os(tvOS)
    @State private var presentsTVActions = false
#endif

    @State private var menuOpen = false

    private var compactAccessibilityAlbum: Bool {
#if os(iOS)
        dynamicTypeSize.isAccessibilitySize && UIDevice.current.userInterfaceIdiom == .phone
            && item.kind == .album && width > 320
#else
        false
#endif
    }

    var body: some View {
#if os(tvOS)
        if showsTVActionsMenu {
            VStack(alignment: .leading, spacing: DulcetSpacing.xxs) {
                styledAction.dulcetReaderItemContextMenu(item)
                // Full-grid holds are unreliable on tvOS. Keep a normal focusable
                // launcher while Select on the artwork continues to open its detail.
                Button { presentsTVActions = true } label: {
                    Text(DulcetStrings.more).lineLimit(1)
                }
                .confirmationDialog(item.displayTitle, isPresented: $presentsTVActions) {
                    DulcetReaderItemMenuItems(item: item)
                }
                .accessibilityLabel(DulcetStrings.readerRowAccessibility(DulcetStrings.more, item.displayTitle))
                .accessibilityIdentifier("dulcet.library.item.more.\(item.id.rawID)")
            }
            .frame(width: width, alignment: .leading)
        } else {
            styledAction.dulcetReaderItemContextMenu(item)
        }
#else
        styledAction
            .dulcetReaderItemContextMenu(item) { menuOpen = $0 }
            .dulcetReaderQueueDragSource(item)
#if os(iOS)
            .overlay(alignment: .bottomTrailing) {
                if offersLibraryQuickAction, let target = item.favouriteTarget {
                    DulcetMyLibraryButton(target: target, quickAction: true, itemTitle: item.displayTitle)
                }
            }
#endif
#endif
    }

    private var offersLibraryQuickAction: Bool {
#if os(iOS)
        showsLibraryQuickAction && store.myLibraryAvailable
#else
        false
#endif
    }

    private var styledAction: some View {
        tileButton
            .dulcetMediaButtonStyle(hover: .lift)
            .dulcetTakesFocusOnReturn(to: item.route)
            .dulcetTakesFocusOnArrival(isEnabled: takesFocusOnArrival)
            .accessibilityElement(children: .combine)
            .accessibilityLabel(accessibilityLabel)
            .accessibilityIdentifier("dulcet.library.\(item.kind.rawValue)")
    }

    private var tileButton: some View {
        Button {
            if let route = item.route { store.pushReaderPage(route) }
        } label: { tileContent }
    }

    @ViewBuilder private var tileContent: some View {
#if os(iOS)
        if compactAccessibilityAlbum {
            // A full-width cover plus accessibility captions leaves no room for the
            // native drag preview's menu. Use the library's thumbnail-and-text row
            // arrangement; the real source remains the system preview and drag source.
            HStack(alignment: .top, spacing: DulcetSpacing.sm) {
                DulcetArtworkView(artwork: item.artwork, size: 88, role: .cover)
                    .clipShape(RoundedRectangle(cornerRadius: DulcetMetrics.artworkCornerRadius(forSize: 88), style: .continuous))
                tileIdentity
                    // The system can propose a short height while lifting a drag
                    // preview. Keep the complete accessibility caption in the row's
                    // measured height rather than drawing it over the next album.
                    .fixedSize(horizontal: false, vertical: true)
            }
            .padding(DulcetSpacing.xxs)
            .fixedSize(horizontal: false, vertical: true)
            .frame(width: width, alignment: .leading)
            // The system lifts this exact view for both its menu and queue drag. An
            // opaque, composited platter keeps the lifted captions separate from the
            // neighbouring tile; a custom context-menu preview would break queue drag.
            .background(
                menuOpen ? AnyShapeStyle(Color(uiColor: .secondarySystemGroupedBackground)) : AnyShapeStyle(.background),
                in: RoundedRectangle(cornerRadius: 12, style: .continuous)
            )
            .compositingGroup()
            .contentShape(Rectangle())
        } else {
            standardTileContent
        }
#else
        standardTileContent
#endif
    }

    private var standardTileContent: some View {
        VStack(alignment: .leading, spacing: DulcetSpacing.xxs) {
            DulcetArtworkView(artwork: item.artwork, size: width, role: item.kind == .artist ? .artist : .cover)
                .clipShape(item.kind == .artist
                    ? AnyShape(Circle())
                    : AnyShape(RoundedRectangle(cornerRadius: DulcetMetrics.artworkCornerRadius(forSize: width), style: .continuous)))
                .dulcetTVArtworkFocusEffect(circular: item.kind == .artist)
            tileIdentity
            .dulcetTVClearsLiftedArtwork(width: width)
        }
        .frame(width: width, alignment: .leading)
        .contentShape(Rectangle())
    }

    private var tileIdentity: some View {
        VStack(alignment: .leading, spacing: DulcetSpacing.xxs) {
            HStack(alignment: .firstTextBaseline, spacing: DulcetSpacing.xxs) {
#if !os(tvOS)
                if let rank {
                    Text(rank.formatted())
                        .font(.headline.monospacedDigit())
                        .dulcetForeground(.primaryTextOnWindow)
                        .fixedSize()
                        .accessibilityIdentifier("dulcet.reader.rank.\(rank)")
                }
#endif
                Text(item.displayTitle)
                    .font(.subheadline.weight(compactAccessibilityAlbum ? .regular : .medium))
                    .dulcetForeground(.primaryTextOnWindow)
                    .lineLimit(dynamicTypeSize.isAccessibilitySize ? nil : (offersLibraryQuickAction ? 2 : titleLineLimit))
                Spacer(minLength: 0)
            }
#if os(tvOS)
            // A rank leads the second line here: beside the title of a narrow television tile
            // it cost the title its last words.
            if let rank {
                Text(rank.formatted())
                    .font(.caption.weight(.semibold).monospacedDigit())
                    .dulcetForeground(.primaryTextOnWindow)
                    .accessibilityIdentifier("dulcet.reader.rank.\(rank)")
            }
#endif
            if !resolvedSubtitle.isEmpty {
                Text(resolvedSubtitle)
                    .font(.caption)
                    .dulcetForeground(.secondaryTextOnWindow)
                    .lineLimit(dynamicTypeSize.isAccessibilitySize ? nil : 1)
                    .fixedSize(horizontal: false, vertical: true)
            }
        }
        .padding(.trailing, offersLibraryQuickAction ? DulcetMetrics.minimumTouchTarget : 0)
        .frame(minHeight: offersLibraryQuickAction ? DulcetMetrics.minimumTouchTarget : nil, alignment: .center)
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
        let title = rank.map { DulcetStrings.readerRowAccessibility($0.formatted(), item.displayTitle) } ?? item.displayTitle
        let base = resolvedSubtitle.isEmpty ? title : DulcetStrings.readerRowAccessibility(title, resolvedSubtitle)
        guard store.librarySession?.isFavourite(item.id, published: item.isFavourite) == true else { return base }
        return DulcetStrings.readerFavoriteAccessibility(base)
    }
}

/// A row in a list of artists, playlists or genres. Opens its page.
struct DulcetReaderListRow: View {
    @Environment(\.dynamicTypeSize) private var dynamicTypeSize
    @Environment(DulcetPresentationStore.self) private var store
    let item: DulcetReaderItem
    var rank: Int? = nil
#if os(iOS)
    @State private var menuOpen = false
#endif
    /// The first row of a page whose sort control comes before it: a remote arrives on the content.
    var takesFocusOnArrival = false
    /// The thumbnail grows with the text, as Music's rows do, so an accessibility-size row is
    /// not a wall of text beside a dot; the cap keeps a row's art from filling a phone.
    @ScaledMetric(relativeTo: .body) private var scaledArtworkSize = DulcetMetrics.denseRowArtworkSize + 8
#if os(tvOS)
    private var artworkSize: CGFloat { 72 }
    private let minimumRowHeight: CGFloat = 88
#else
    private var artworkSize: CGFloat { min(scaledArtworkSize, 88) }
    private let minimumRowHeight: CGFloat = 44
#endif

    /// Art to words: from across a room Apple TV needs more air between them than a phone does.
    private var labelSpacing: CGFloat {
#if os(tvOS)
        DulcetSpacing.sm
#else
        DulcetSpacing.xs
#endif
    }

    var body: some View {
        HStack(spacing: DulcetSpacing.xs) {
            Button {
                if let route = item.route { store.pushReaderPage(route) }
            } label: {
                HStack(spacing: labelSpacing) {
                    if let rank {
                        DulcetNumberColumn(font: .headline.monospacedDigit()) {
                            Text(rank.formatted()).font(.headline.monospacedDigit()).dulcetForeground(.secondaryTextOnWindow)
                        }
                    }
                    // At accessibility sizes the art takes a line above a full-width title, as a
                    // song row's does: beside it the title fell to a word a line.
                    let identityLayout = dynamicTypeSize.isAccessibilitySize
                        ? AnyLayout(VStackLayout(alignment: .leading, spacing: DulcetSpacing.xs))
                        : AnyLayout(HStackLayout(spacing: labelSpacing))
                    identityLayout {
                    if item.kind != .genre {
                        DulcetArtworkView(artwork: item.artwork, size: artworkSize, role: item.kind == .artist ? .artist : .cover)
                            .clipShape(item.kind == .artist
                                ? AnyShape(Circle())
                                : AnyShape(RoundedRectangle(cornerRadius: DulcetMetrics.artworkCornerRadius(forSize: artworkSize), style: .continuous)))
                    }
                    VStack(alignment: .leading, spacing: 0) {
                        Text(item.displayTitle)
#if os(tvOS)
                            .font(.callout)
#else
                            .font(.body)
#endif
                            .dulcetForeground(.primaryTextOnWindow)
                            .lineLimit(dynamicTypeSize.isAccessibilitySize ? nil : 1)
                        if !subtitle.isEmpty {
                            Text(subtitle)
                                .font(.caption)
                                .dulcetForeground(.secondaryTextOnWindow)
                                .lineLimit(dynamicTypeSize.isAccessibilitySize ? nil : 1)
                        }
                    }
                    }
                    Spacer(minLength: DulcetSpacing.xs)
                    Image(systemName: "chevron.forward")
                        .font(.caption.weight(.semibold))
                        .dulcetForeground(.secondaryTextOnWindow)
                        .accessibilityHidden(true)
                }
                .padding(.vertical, DulcetSpacing.xxs)
#if os(tvOS)
                // Words and art start where Library's rows start, one plate inset in.
                .padding(.leading, DulcetTVFocusedRowButtonStyle.plateInset)
#endif
                .frame(minHeight: minimumRowHeight)
                .contentShape(Rectangle())
            }
            .dulcetRowButtonStyle()
            .dulcetTakesFocusOnArrival(isEnabled: takesFocusOnArrival)
            .dulcetTakesFocusOnReturn(to: item.route)
            .accessibilityElement(children: .combine)
            .accessibilityLabel(accessibilityLabel)
            .accessibilityIdentifier("dulcet.library.\(item.kind.rawValue)")
        }
#if os(iOS)
        // The lifted preview is the row itself (a drag source keeps the system preview), and a
        // row with no background lifted as a bare shadow behind its words. The platter reaches
        // past the words so art and chevron sit inside it, and at rest it is the page's own
        // background, invisible. Lifted, it takes the elevated grouped fill: the page's black
        // under the dimmed dark page read as no platter at all (round 12).
        .padding(.horizontal, Self.platterInset)
        .background(
            menuOpen ? AnyShapeStyle(Color(uiColor: .secondarySystemGroupedBackground)) : AnyShapeStyle(.background),
            in: RoundedRectangle(cornerRadius: 12, style: .continuous)
        )
        .dulcetReaderItemContextMenu(item) { menuOpen = $0 }
        .dulcetReaderQueueDragSource(item)
        .padding(.horizontal, -Self.platterInset)
#else
        .dulcetReaderItemContextMenu(item)
        .dulcetReaderQueueDragSource(item)
#endif
    }

#if os(iOS)
    private static let platterInset: CGFloat = 10
#endif

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

    private var accessibilityLabel: String {
        let title = rank.map { DulcetStrings.readerRowAccessibility($0.formatted(), item.displayTitle) } ?? item.displayTitle
        return subtitle.isEmpty ? title : DulcetStrings.readerRowAccessibility(title, subtitle)
    }
}

/// A track in an album, a playlist, a genre or the favourites. A track that cannot play offline
/// is dimmed and labelled, and tapping it says why rather than doing nothing (§16.14).
struct DulcetReaderTrackRow: View {
    @Environment(\.dynamicTypeSize) private var dynamicTypeSize
    @Environment(DulcetPresentationStore.self) private var store
    let item: DulcetReaderItem
    let index: Int
    var showsIndex = true
    var showsAlbum = true
    var showsArtwork = true
    var showsDuration = true
    var albumArtists: [String] = []
    var showsLibraryQuickAction = false
    /// The row's collection origin, captured in both menu and drag queue actions.
    var queueAddition: DulcetQueueAddition? = nil
    let onPlay: (DulcetTrack) -> Void
    private var metrics = DulcetSongRowMetrics()

    var body: some View {
        if let track = item.playableTrack {
            if item.isUnavailableOffline {
                Button {
                    store.librarySession?.post(DulcetStrings.readerPlaysOnReconnect)
                } label: {
                    row(track)
                }
                .dulcetRowButtonStyle()
                .accessibilityIdentifier("dulcet.reader.track.unavailable")
#if os(tvOS)
                .dulcetTrackContextMenu(track: withDownloadState(track), queueAddition: queueAddition,
                    offersAlbum: showsAlbum, publishedRating: item.rating)
#else
                .contextMenu {
                    if let target = item.favouriteTarget { DulcetMyLibraryButton(target: target, menu: true) }
                }
#endif
#if os(iOS)
                .swipeActions(edge: .leading, allowsFullSwipe: false) {
                    DulcetFavouriteMenuItem(target: .init(kind: .track, id: track.id), published: track.isFavorite)
                        .tint(.accentColor)
                }
#endif
            } else {
                row(track)
#if os(macOS)
                    .dulcetTrackContextMenu(
                        track: withDownloadState(track),
                        onPlay: { onPlay(track) },
                        queueAddition: queueAddition,
                        offersAlbum: showsAlbum,
                        publishedRating: item.rating,
                        onDownload: downloadAction(track)
                    )
#endif
            }
        } else {
            // The server never gave this track's length: listed with the list's chrome, and
            // never handed to the player, so no play action and no bright title.
            unplayableRow
                .contextMenu {
                    if let target = item.favouriteTarget { DulcetMyLibraryButton(target: target, menu: true) }
                }
        }
    }

    /// The heart sits beside the row, except at the accessibility text sizes, where it joins the
    /// row's own meta line under the full-width title. Never inside on a television: a control
    /// nested in a focused row's label cannot take focus of its own there.
    private var showsTrailingHeart: Bool {
#if os(tvOS)
        true
#else
        !dynamicTypeSize.isAccessibilitySize
#endif
    }

    @ViewBuilder
    private var rowHeart: some View {
        if !showsTrailingHeart, let target = item.favouriteTarget {
            DulcetFavouriteButton(target: target, published: item.isFavourite, title: item.displayTitle)
        }
    }

    private var unplayableRow: some View {
        HStack(alignment: .center, spacing: DulcetSpacing.xs) {
            // The same columns as `DulcetTrackRow`, so this row's title lines up with its neighbours.
            if DulcetSongRowMetrics.hasNumberColumn(showsIndex: showsIndex, showsArtwork: showsArtwork) {
                let font: Font = showsArtwork ? .caption.monospacedDigit() : .callout.monospacedDigit()
                ZStack(alignment: .trailing) {
                    if showsIndex {
                        Text(String(index))
                            .font(font)
                            .dulcetForeground(.secondaryTextOnWindow)
                    }
                }
                .frame(minWidth: metrics.indexColumnWidth, alignment: .trailing)
                .accessibilityHidden(true)
            }
            if showsArtwork {
                DulcetArtworkView(artwork: item.artwork, size: metrics.artworkSize, muted: true)
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
                .font(.subheadline.monospacedDigit())
                .dulcetForeground(.secondaryTextOnWindow)
        }
        .padding(.vertical, DulcetMetrics.denseRowVerticalPadding)
        .frame(minHeight: DulcetMetrics.rowMinHeight(artwork: showsArtwork))
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
            track: withDownloadState(track),
            showAlbum: showsAlbum,
            index: index,
            offline: item.isUnavailableOffline,
            // The number column marks the current song in a numbered listing, the artwork in
            // any other, as Music's do.
            isCurrent: store.isNowPlaying(track),
            showsArtwork: showsArtwork,
            showsIndex: showsIndex,
            albumArtists: albumArtists,
            queueAddition: queueAddition,
            onActivate: item.isUnavailableOffline ? nil : { onPlay(track) },
            onDownload: downloadAction(track),
            publishedRating: item.rating,
            // Identify the play control, not the parent shared with its More menu.
            activationIdentifier: "dulcet.reader.track"
        )
        .showingDuration(showsDuration)
        .libraryQuickAction(showsLibraryQuickAction ? item.favouriteTarget : nil)
    }

    /// The track as its download stands now: the reader's rows carry none of their own.
    private func withDownloadState(_ track: DulcetTrack) -> DulcetTrack {
        track.replacingDownloadState(store.downloadState(for: track))
    }

    /// Download is offered wherever a track the device can ask the server for is listed, and
    /// only where this device keeps downloads (spec §14.5).
    private func downloadAction(_ track: DulcetTrack) -> (() -> Void)? {
        guard store.downloadsEnabled, !item.isUnavailableOffline else { return nil }
        return { store.requestDownload(track) }
    }
}

extension View {
    /// Play, Shuffle, Play Next, Add to Queue, Favorite and Go to Artist for an album, a
    /// playlist or an artist. On tvOS the menu opens on a press and hold of the remote's clickpad.
    func dulcetReaderItemContextMenu(
        _ item: DulcetReaderItem,
        onMenuOpenChange: ((Bool) -> Void)? = nil
    ) -> some View {
        modifier(DulcetReaderItemContextMenu(item: item, onMenuOpenChange: onMenuOpenChange))
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
    @Environment(\.dynamicTypeSize) private var dynamicTypeSize
    @Environment(DulcetPresentationStore.self) private var store
    let item: DulcetReaderItem
    /// Told when the menu opens and closes, so a row can dress its own lifted preview.
    var onMenuOpenChange: ((Bool) -> Void)?

    func body(content: Content) -> some View {
#if os(tvOS)
        content.contextMenu { menuItems }
#elseif os(iOS)
        if item.isQueueDraggable {
            // The system preview: an album or a playlist is also a drag source onto the queue,
            // which a custom preview stops from lifting out of the open menu (TRAPS 48). The shape
            // keeps that preview the row itself, lifted in place on a rounded platter: without it
            // a playlist row lifted bare and its artwork slid under the iPad sidebar (round 11).
            content
                .contentShape(.contextMenuPreview, RoundedRectangle(cornerRadius: DulcetMetrics.artworkCornerRadius, style: .continuous))
                .contextMenu {
                    menuItems
                        .onAppear { onMenuOpenChange?(true) }
                        .onDisappear { onMenuOpenChange?(false) }
                }
        } else {
            content.contextMenu {
                menuItems
            } preview: {
                DulcetContextMenuPreview(
                    store: store,
                    artwork: item.artwork,
                    title: item.displayTitle,
                    subtitle: item.kind == .artist ? "" : item.artistName ?? "",
                    role: item.kind == .artist ? .artist : .cover
                )
            }
        }
#else
        content.contextMenu { menuItems }
#endif
    }

    private var menuItems: some View { DulcetReaderItemMenuItems(item: item).dulcetMenuGlyphs() }
}

struct DulcetReaderItemMenuItems: View {
    @Environment(\.dynamicTypeSize) private var dynamicTypeSize
    @Environment(DulcetPresentationStore.self) private var store
    let item: DulcetReaderItem
    /// False on a playlist's own page, whose More carries a Delete that also closes the page.
    var offersPlaylistDeletion = true

    /// A native menu draws at the system's text size, and a toolbar menu's content does not read
    /// it through the environment, so the system's own setting decides here as well.
#if os(iOS)
    private var collapsesPlayActions: Bool {
        dynamicTypeSize.isAccessibilitySize || UIApplication.shared.preferredContentSizeCategory.isAccessibilityCategory
    }
#endif

    @ViewBuilder
    var body: some View {
#if os(iOS)
        if collapsesPlayActions && item.trackListQuery != nil {
            if let target = item.favouriteTarget { DulcetMyLibraryButton(target: target, menu: true) }
            if offersPlayActions {
                Button(DulcetStrings.play, systemImage: "play") { store.resolveAndPlay(item, shuffle: false) }
                Button(DulcetStrings.shuffle, systemImage: "shuffle") { store.resolveAndPlay(item, shuffle: true) }
            }
            Menu { secondaryMenuItems } label: { Label(DulcetStrings.more, systemImage: "ellipsis.circle") }
        } else { fullMenuItems }
#elseif os(macOS)
        if item.kind == .artist {
            Section(item.displayTitle) { fullMenuItems }
        } else {
            fullMenuItems
        }
#else
        fullMenuItems
#endif
    }

    /// Offline, an album or playlist with nothing on this device cannot play: its menu hides play
    /// and queue actions instead of dimming them (§16.14).
    private var offersPlayActions: Bool { item.trackListQuery != nil && !item.isUnavailableOffline }

    @ViewBuilder private var secondaryMenuItems: some View {
        if store.queueEditingEnabled, offersPlayActions {
            Button(DulcetStrings.playNext, systemImage: "text.line.first.and.arrowtriangle.forward") { store.resolveAndQueue(item, next: true) }
            Button(DulcetStrings.addToQueue, systemImage: "text.line.last.and.arrowtriangle.forward") { store.resolveAndQueue(item, next: false) }
        }
        DulcetLibraryPinControl(item: item, menu: true)
        if let target = item.favouriteTarget { DulcetFavouriteMenuItem(target: target, published: item.isFavourite) }
        if item.kind == .album, store.downloadsEnabled, !item.isUnavailableOffline {
            Button(DulcetStrings.download, systemImage: "arrow.down.circle") { store.resolveAndDownload(item) }
                .accessibilityIdentifier("dulcet.album.download")
        }
        DulcetPlaylistDownloadMenuItem(item: item)
        if item.kind == .album, let artistID = item.artistID {
            Button(DulcetStrings.goToArtist, systemImage: "music.mic") { store.showReaderPage(.artist(artistID)) }
        }
        DulcetPlaylistItemMenuItems(item: item, offersDeletion: offersPlaylistDeletion)
    }

    /// Grouped as Music's album menu is, a divider between each: play, queue, keep and mark
    /// (pin, library, favorite, download), then go elsewhere (artist, playlists).
    @ViewBuilder
    private var fullMenuItems: some View {
        if offersPlayActions {
            Section {
                Button(DulcetStrings.play, systemImage: "play") { store.resolveAndPlay(item, shuffle: false) }
                Button(DulcetStrings.shuffle, systemImage: "shuffle") { store.resolveAndPlay(item, shuffle: true) }
            }
            if store.queueEditingEnabled {
                Section {
                    Button(DulcetStrings.playNext, systemImage: "text.line.first.and.arrowtriangle.forward") {
                        store.resolveAndQueue(item, next: true)
                    }
                    Button(DulcetStrings.addToQueue, systemImage: "text.line.last.and.arrowtriangle.forward") {
                        store.resolveAndQueue(item, next: false)
                    }
                }
            }
        }
        Section {
            DulcetLibraryPinControl(item: item, menu: true)
            if let target = item.favouriteTarget {
                DulcetMyLibraryButton(target: target, menu: true)
                DulcetFavouriteMenuItem(target: target, published: item.isFavourite)
            }
            if item.kind == .album, store.downloadsEnabled, !item.isUnavailableOffline {
                // Music's album menu offers Download; the same per-track requests a row makes.
                Button(DulcetStrings.download, systemImage: "arrow.down.circle") { store.resolveAndDownload(item) }
                    .accessibilityIdentifier("dulcet.album.download")
            }
            DulcetPlaylistDownloadMenuItem(item: item)
        }
        Section {
            if item.kind == .album, let artistID = item.artistID {
                Button(DulcetStrings.goToArtist, systemImage: "music.mic") {
                    store.showReaderPage(.artist(artistID))
                }
            }
            DulcetPlaylistItemMenuItems(item: item, offersDeletion: offersPlaylistDeletion)
        }
    }
}

// MARK: - Grids and lists

/// A window's items as a grid of tiles, extending as it scrolls.
struct DulcetReaderGrid: View {
    let model: DulcetLibraryWindowModel
    let width: CGFloat
    /// The tiles' line under the title, when the item's own would repeat the page's; nil leaves
    /// each item's own line.
    var tileSubtitle: ((DulcetReaderItem) -> String)? = nil
    var showsLibraryQuickActions = false

    var body: some View {
        DulcetArtworkGrid(items: model.window?.items ?? [], width: width) { item, index, size in
            DulcetReaderTile(item: item, width: size, subtitle: tileSubtitle?(item),
                rank: model.query == .albums(.frequent) ? index + 1 : nil,
                takesFocusOnArrival: index == 0, showsLibraryQuickAction: showsLibraryQuickActions)
                .dulcetReaderRow(index, in: model)
        }
    }

}

struct DulcetReaderEmptyState {
    let title: String
    let hint: String
    let systemImage: String
}

/// The body of a list screen: its status, then its rows, or the statement in their place.
struct DulcetReaderListBody<Rows: View>: View {
    let model: DulcetLibraryWindowModel
    var subject: DulcetReaderCopy.Subject = .list
    /// A list that only ever shows its first so many rows -- an artist's Top Songs -- never offers
    /// Load More: more rows would not be drawn, and the device fallback behind Top Songs reports
    /// an open end it never reads past.
    var displayLimit: Int? = nil
    /// What an empty list says in place of the plain statement.
    var emptyState: DulcetReaderEmptyState? = nil
    @ViewBuilder let rows: (DulcetLibraryWindow) -> Rows

    /// Whether the screen offers Load More under its rows.
    static func offersLoadMore(_ window: DulcetLibraryWindow, displayLimit: Int?) -> Bool {
        displayLimit == nil && window.hasContent && window.growsAtEnd
            && !window.freshness.isOffline && window.freshness != .loading
    }

    var body: some View {
        VStack(alignment: .leading, spacing: DulcetSpacing.sm) {
            if let window = model.window {
                if case let .unavailable(reason) = window.freshness, window.items.isEmpty {
                    DulcetReaderUnavailableView(reason: reason, subject: subject)
                } else if window.items.isEmpty, window.freshness != .loading, window.itemsState != .loading {
                    Group {
                        if let emptyState {
                            DulcetStateMessage(symbol: emptyState.systemImage, title: emptyState.title,
                                               message: emptyState.hint)
                                .embeddedInScrollingPage()
                        } else {
                            DulcetStateMessage(symbol: "music.note", title: DulcetStrings.readerEmptyList)
                                .embeddedInScrollingPage()
                        }
                    }
#if os(tvOS)
                    .focusable()
#endif
                } else {
                    rows(window)
                    if window.freshness == .loading || window.itemsState == .loading {
                        ProgressView()
                            .frame(maxWidth: .infinity)
                            .padding(.vertical, DulcetSpacing.sm)
                            .accessibilityLabel(DulcetStrings.loadingMore)
                    }
                }
                if Self.offersLoadMore(window, displayLimit: displayLimit) {
                    Button(DulcetStrings.libraryLoadMore) { model.loadMore() }
                        .accessibilityIdentifier("dulcet.library.loadMore")
                }
            } else {
                ProgressView()
                    .frame(maxWidth: .infinity)
                    .padding(.vertical, DulcetSpacing.lg)
            }
        }
        .dulcetReaderFreshness(model.window?.freshness)
    }
}

#if os(iOS)
/// `DulcetReaderListBody` without a wrapping `VStack`, so each row emitted by `rows` stays its
/// own `List` cell and receives native `swipeActions`.
struct DulcetReaderListRows<Rows: View>: View {
    let model: DulcetLibraryWindowModel
    var subject: DulcetReaderCopy.Subject = .list
    var displayLimit: Int? = nil
    var emptyState: DulcetReaderEmptyState? = nil
    @ViewBuilder let rows: (DulcetLibraryWindow) -> Rows

    var body: some View {
        if let window = model.window {
            if case let .unavailable(reason) = window.freshness, window.items.isEmpty {
                DulcetReaderUnavailableView(reason: reason, subject: subject)
            } else if window.items.isEmpty, window.freshness != .loading, window.itemsState != .loading {
                if let emptyState {
                    DulcetStateMessage(symbol: emptyState.systemImage, title: emptyState.title,
                                       message: emptyState.hint)
                        .embeddedInScrollingPage()
                } else {
                    DulcetStateMessage(symbol: "music.note", title: DulcetStrings.readerEmptyList)
                        .embeddedInScrollingPage()
                }
            } else {
                rows(window)
                if window.freshness == .loading || window.itemsState == .loading {
                    ProgressView()
                        .frame(maxWidth: .infinity)
                        .padding(.vertical, DulcetSpacing.sm)
                        .accessibilityLabel(DulcetStrings.loadingMore)
                }
            }
            if DulcetReaderListBody<EmptyView>.offersLoadMore(window, displayLimit: displayLimit) {
                Button(DulcetStrings.libraryLoadMore) { model.loadMore() }
                    .buttonStyle(.borderless)
                    .padding(.top, DulcetSpacing.xs)
                    .accessibilityIdentifier("dulcet.library.loadMore")
            }
        } else {
            ProgressView()
                .frame(maxWidth: .infinity)
                .padding(.vertical, DulcetSpacing.lg)
        }
    }
}
#endif

/// The controls above a `DulcetReaderListPage`'s rows. A `List` spaces none of its cells, so on
/// iOS they share one cell stacked with the page's section gap; elsewhere they join the page's
/// own stack unchanged.
struct DulcetReaderListHeader<Content: View>: View {
    @ViewBuilder let content: Content

    var body: some View {
#if os(iOS)
        VStack(alignment: .leading, spacing: DulcetSpacing.md) { content }
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(.bottom, DulcetSpacing.md)
#else
        content
#endif
    }
}

/// A song-row page hosted in a plain `List` on iOS so row `swipeActions` reach each song, and
/// forwarding to `DulcetReaderPage` on macOS and tvOS.
struct DulcetReaderListPage<Content: View>: View {
    let title: String
    var keepsTVNavigationBar = true
    var titleInHeader = false
    var sectionSpacing: CGFloat = DulcetSpacing.md
    var freshness: DulcetReaderFreshness? = nil
    @State private var titleAboveViewport = false
    @ViewBuilder let content: (CGFloat) -> Content

    var body: some View {
#if os(iOS)
        let page = GeometryReader { geometry in
            let inset = DulcetLibraryMetrics.horizontalInset(forWidth: geometry.size.width)
            List {
                content(geometry.size.width)
                    .listRowInsets(EdgeInsets(top: 0, leading: inset, bottom: 0, trailing: inset))
                    .listRowSeparator(.hidden)
                    .listRowBackground(Color.clear)
            }
            .listStyle(.plain)
            .scrollContentBackground(.hidden)
            .environment(\.defaultMinListRowHeight, 0)
            .contentMargins(.vertical, DulcetSpacing.md, for: .scrollContent)
            .modifier(DulcetSystemScrollChrome())
            .coordinateSpace(name: "dulcet.detail.scroll")
            .onPreferenceChange(DulcetDetailTitleBottom.self) { bottom in
                if bottom.isFinite {
                    titleAboveViewport = bottom < 0
                }
            }
            .dulcetReaderFreshness(freshness)
        }
        .background(Color.dulcetWindow)
        .dulcetForeground(.primaryTextOnWindow)
        if titleInHeader {
            page.dulcetDetailNavigationTitle(title, visible: titleAboveViewport)
        } else {
            page.dulcetNavigationTitle(title, keepsTVNavigationBar: keepsTVNavigationBar)
        }
#else
        DulcetReaderPage(
            title: title,
            keepsTVNavigationBar: keepsTVNavigationBar,
            titleInHeader: titleInHeader,
            sectionSpacing: sectionSpacing,
            content: content
        )
#endif
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
    var titleInHeader = false
    /// The gap the page puts between its sections. A page whose sections can be empty (Listen
    /// Now hides a shelf with nothing in it) passes zero and spaces its visible sections itself,
    /// since a stack spaces an empty child like any other.
    var sectionSpacing: CGFloat = DulcetSpacing.md
    @State private var titleAboveViewport = false
#if os(tvOS)
    @State private var tvContentScrolled = false
#endif
    @ViewBuilder let content: (CGFloat) -> Content

    var body: some View {
        let page = GeometryReader { geometry in
            ScrollView {
                VStack(alignment: .leading, spacing: sectionSpacing) {
                    content(geometry.size.width)
                }
                .padding(.horizontal, DulcetLibraryMetrics.horizontalInset(forWidth: geometry.size.width))
                .padding(.vertical, DulcetSpacing.md)
                .frame(maxWidth: .infinity, alignment: .leading)
#if os(tvOS)
                // Down from a control in the navigation bar (Albums' Sort) found nothing in this
                // scroll view, even directly beneath it: the focus engine made no move at all, every
                // time (run 42395056269, 0 of 80 presses, both Apple TV simulators). As a focus
                // section the content is a target the bar can move into (42399996061, 20 of 20).
                .focusSection()
#endif
            }
            .modifier(DulcetSystemScrollChrome())
#if os(tvOS)
            .modifier(DulcetTVScrolledAwayReader(scrolled: $tvContentScrolled))
#endif
            .coordinateSpace(name: "dulcet.detail.scroll")
            .onPreferenceChange(DulcetDetailTitleBottom.self) { bottom in
                titleAboveViewport = bottom < 0
            }
        }
        .background(Color.dulcetWindow)
        .dulcetForeground(.primaryTextOnWindow)
#if os(tvOS)
        page.dulcetNavigationTitle(title, keepsTVNavigationBar: keepsTVNavigationBar, tvTitleScrolledAway: tvContentScrolled)
#else
        if titleInHeader {
            page.dulcetDetailNavigationTitle(title, visible: titleAboveViewport)
        } else {
            page.dulcetNavigationTitle(title, keepsTVNavigationBar: keepsTVNavigationBar)
        }
#endif
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
        case .byYear: DulcetStrings.libraryReleaseDate
        }
    }

    /// The orders the Albums sort picker offers.
    static let sortChoices: [DulcetAlbumListType] = [
        .alphabeticalByName, .alphabeticalByArtist, .byYear, .newest, .recent, .frequent, .highest, .random,
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
    @State private var serverReadFailed = false
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
        GeometryReader { geometry in
            List {
                Section {
                    ForEach(DulcetLibrarySection.allCases.filter { $0 != .home && $0 != .favourites && $0 != .playlists }) { section in
                        DulcetReaderNavigationLink(title: section.title, symbol: section.symbolName,
                            route: .section(section), identifier: "dulcet.reader.section.\(section.rawValue)")
                    }
                    DulcetReaderNavigationLink(title: DulcetStrings.librarySongs, symbol: "music.note",
                        route: .songs, identifier: "dulcet.library.songs")
                }
                ForEach([DulcetHomeRow.recentlyAdded, .mostPlayed]) { row in
                    DulcetReaderShelf(row: row, width: geometry.size.width)
                        .dulcetHideListRowSeparator()
                }
            }
            .listStyle(.plain)
            .modifier(DulcetSystemScrollChrome())
#if os(macOS)
            .environment(\.defaultMinListRowHeight, 36)
#endif
        }
        .onPreferenceChange(DulcetReaderServerFailure.self) { serverReadFailed = $0 }
        .dulcetAccountStatusBar(serverReadFailed: serverReadFailed)
        .dulcetNavigationTitle(DulcetStrings.browseSection, keepsTVNavigationBar: true)
#if !os(tvOS)
        .refreshable { store.librarySession?.reconnect() }
#endif
    }

}

/// One row of a phone's Library list.
struct DulcetReaderHomeLink: View {
    let title: String
    let symbolName: String
    let identifier: String
    let action: () -> Void
    /// The icon's column grows with the label's text style, so a scaled symbol never reaches
    /// into the title at the accessibility sizes.
    @ScaledMetric(relativeTo: .title3) private var iconColumnWidth = 28

    var body: some View {
        Button(action: action) {
            HStack(spacing: DulcetSpacing.xs) {
                Image(systemName: symbolName)
                    .font(.title3)
                    .frame(width: iconColumnWidth)
                    .dulcetForeground(.accentIconOnWindow)
                    .accessibilityHidden(true)
                Text(title)
                    .font(.title3)
                    // A one-word label ("Downloaded") breaks mid-word at the accessibility
                    // sizes; keep it whole and let it shrink a little instead. Labels with
                    // spaces still wrap at word boundaries.
                    .lineLimit(Self.keepsWhole(title) ? 1 : nil)
                    .minimumScaleFactor(Self.keepsWhole(title) ? 0.7 : 1)
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

    /// A label with no word boundary is kept on one line rather than hyphenated.
    nonisolated static func keepsWhole(_ title: String) -> Bool {
        !title.contains(where: \.isWhitespace)
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
            VStack(alignment: .leading, spacing: 0) {
            if let window = model.window, window.hasContent {
                VStack(alignment: .leading, spacing: DulcetSpacing.xs) {
                    DulcetShelfHeader(title: row.title,
                        identifier: "dulcet.reader.home.\(row.rawValue).all") {
                        store.pushReaderPage(row.fullList)
                    }
                    ScrollView(.horizontal, showsIndicators: false) {
                        LazyHStack(alignment: .top, spacing: DulcetLibraryMetrics.shelfTileSpacing) {
                            ForEach(Array(window.items.enumerated()), id: \.element.id) { index, item in
                                DulcetReaderTile(item: item, width: tileWidth,
                                    rank: row == .mostPlayed ? index + 1 : nil)
                                    .dulcetReaderRow(index, in: model)
                            }
                        }
#if os(tvOS)
                        .padding(.vertical, DulcetSpacing.sm)
#endif
                    }
                    // The list row holds the shelf to its inset; tiles scroll on past it to the
                    // screen's edges instead of being cut there.
                    .scrollClipDisabled()
#if os(tvOS)
                    .focusSection()
#endif
                    if window.freshness == .loading, !window.hasContent {
                        ProgressView().frame(maxWidth: .infinity)
                    }
                }
                .accessibilityElement(children: .contain)
                .accessibilityIdentifier("dulcet.reader.home.\(row.rawValue)")
                .dulcetReaderFreshness(window.freshness)
            } else if model.window == nil {
                ProgressView()
                    .frame(maxWidth: .infinity, minHeight: 60)
            }
            }
            .preference(key: DulcetReaderServerFailure.self, value: model.window?.freshness.isConnectionFailure == true)
        }
    }

    private var tileWidth: CGFloat {
#if os(tvOS)
        240
#else
        // The accessibility floor is wider than the words a tile's title carries: a narrower tile
        // wrapped them inside the word.
        dynamicTypeSize.isAccessibilitySize ? 260 : (width < DulcetLibraryMetrics.compactWidthThreshold ? 140 : 170)
#endif
    }
}

/// Albums, in the order the person chose.
struct DulcetReaderAlbumsView: View {
    @Environment(DulcetPresentationStore.self) private var store

    private var sortRaw: Binding<String> {
        Binding(get: { store.librarySession?.libraryDepth.sorts["albums"] ?? "title" },
                set: { store.librarySession?.setLibraryCategory("albums", sort: $0) })
    }

    private var sort: DulcetAlbumListType {
        switch sortRaw.wrappedValue {
        case "artist": .alphabeticalByArtist
        case "releaseDate": .byYear
        case "recentlyAdded": .newest
        case "mostPlayed": .frequent
        default: .alphabeticalByName
        }
    }

    private var title: String {
#if os(tvOS)
        DulcetStrings.readerAlbums
#else
        store.myLibraryAvailable ? DulcetStrings.serverAlbums : DulcetStrings.readerAlbums
#endif
    }

    var body: some View {
        DulcetReaderAlbumGridView(type: sort, title: title, category: "albums")
            .id(sort)
    }
}

/// One album list as a grid, with a sort picker when `sort` is given.
struct DulcetReaderAlbumGridView: View {
    @Environment(\.dynamicTypeSize) private var dynamicTypeSize
    @State private var showingSort = false
    let type: DulcetAlbumListType
    let title: String
    var sort: Binding<String>?
    var category: String?
    /// The Albums section's root keeps Apple TV's navigation bar; a pushed "See All" hides it.
    var keepsTVNavigationBar = true

    var body: some View {
        DulcetReaderScreen(query: .albums(type), showsDestinationStatus: true) { model in
            DulcetReaderPage(title: title, keepsTVNavigationBar: keepsTVNavigationBar) { width in
#if os(tvOS)
                // A menu in Apple TV's navigation bar takes focus and never opens (the tour pressed
                // it three times on a tvOS 27 simulator); the same control in the page does.
                if let category { DulcetLibraryCategoryControls(category: category, albumSort: true) }
#endif
                if let window = model.window, window.freshness == .loading,
                   window.order == .localView, window.hasContent {
                    Text(DulcetStrings.loadingServerAlbums)
                        .font(.footnote)
                        .dulcetForeground(.secondaryTextOnWindow)
                        .accessibilityIdentifier("dulcet.reader.albums.preview")
                }
                DulcetReaderListBody(model: model) { _ in
                    DulcetReaderGrid(model: model, width: width, showsLibraryQuickActions: true)
                }
            }
#if !os(tvOS)
            .toolbar {
                if let category {
                    ToolbarItem(placement: .primaryAction) {
                        Menu { DulcetLibraryCategoryControls(category: category, albumSort: true, menuPresentation: true) }
                        label: { Label(DulcetStrings.readerSortBy, systemImage: "arrow.up.arrow.down") }
                        .accessibilityIdentifier("dulcet.library.\(category).options")
                    }
                } else if let sort {
                    ToolbarItem(placement: .primaryAction) { sortPicker(sort) }
                }
            }
#endif
        }
    }

    private func sortPicker(_ sort: Binding<String>) -> some View {
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

}

/// Artists, genres or playlists: a list of rows opening their pages.
struct DulcetReaderSimpleListView: View {
    @Environment(DulcetPresentationStore.self) private var store
    let query: DulcetLibraryQuery
    let title: String

    var body: some View {
        DulcetReaderScreen(query: query, showsDestinationStatus: true) { model in
            DulcetReaderPage(title: title) { _ in
                if query == .artists { DulcetLibraryCategoryControls(category: "artists", offersArtist: false) }
                DulcetReaderListBody(model: model) { window in
                    LazyVStack(alignment: .leading, spacing: 0) {
                        ForEach(Array(window.items.enumerated()), id: \.element.id) { index, item in
                            DulcetReaderListRow(item: item, takesFocusOnArrival: index == 0)
                                .dulcetReaderRow(index, in: model)
                            DulcetTrackListSeparator()
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
        DulcetReaderScreen(query: .favourites, showsDestinationStatus: true) { model in
            if let window = model.window, window.items.isEmpty,
               window.freshness != .loading, window.itemsState != .loading,
               !isUnavailable(window.freshness) {
                DulcetStateMessage(symbol: "heart", title: DulcetStrings.readerNoFavorites,
                                   message: DulcetStrings.readerAddFavoritesHint)
#if os(tvOS)
                    // With nothing focusable, focus falls to the section bar, where Menu leaves
                    // the app and this pushed page can never be left.
                    .focusable()
#endif
                    .background(Color.dulcetWindow)
                    .dulcetForeground(.primaryTextOnWindow)
                    .dulcetNavigationTitle(DulcetStrings.readerFavorites, keepsTVNavigationBar: true)
                    .dulcetReaderFreshness(window.freshness)
            } else {
                DulcetReaderListPage(title: DulcetStrings.readerFavorites, freshness: model.window?.freshness) { width in
#if os(iOS)
                    DulcetReaderListRows(model: model) { window in
                        favouritesRows(window, model: model)
                    }
#else
                    DulcetReaderListBody(model: model) { window in
                        favouritesRows(window, model: model)
                    }
#endif
                }
            }
        }
    }

    @ViewBuilder
    private func favouritesRows(_ window: DulcetLibraryWindow, model: DulcetLibraryWindowModel) -> some View {
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
                LazyHStack(alignment: .top, spacing: favouriteTileSpacing) {
                    ForEach(albums, id: \.element.id) { index, item in
                        DulcetReaderTile(item: item, width: favouriteTileWidth).dulcetReaderRow(index, in: model)
                    }
                }
#if os(tvOS)
                // Room for the lift and the caption it moves.
                .padding(.vertical, DulcetSpacing.md)
#endif
            }
            .scrollClipDisabled()
        }
        if !tracks.isEmpty {
            header(DulcetStrings.readerSongs)
            let playable = tracks.compactMap(\.element.playableTrack).filter { $0.availability == .playable }
            ForEach(Array(tracks.enumerated()), id: \.element.element.id) { position, entry in
                DulcetReaderTrackRow(item: entry.element, index: position + 1, showsIndex: false) { track in
                    store.playReaderTracks(
                        playable,
                        startingAt: track,
                        sourceKind: .library,
                        sourceID: nil,
                        sourceName: DulcetStrings.readerFavorites
                    )
                }
                .dulcetReaderRow(entry.offset, in: model)
                .dulcetSongRowSeparator(above: position > 0, songRowShowingIndex: false, artwork: true)
            }
        }
    }

    private func isUnavailable(_ freshness: DulcetReaderFreshness) -> Bool {
        if case .unavailable = freshness { return true }
        return false
    }

#if os(tvOS)
    /// The size of every other shelf tile on Apple TV.
    private let favouriteTileWidth: CGFloat = 300
    private let favouriteTileSpacing = DulcetSpacing.lg
#else
    private let favouriteTileWidth: CGFloat = 150
    private let favouriteTileSpacing = DulcetSpacing.sm
#endif

    private func header(_ title: String) -> some View {
        Text(title)
#if os(tvOS)
            // The size of every other shelf's heading, under the page's own title.
            .font(.title3.weight(.semibold))
#else
            .font(.title2.weight(.semibold))
#endif
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
#if !os(tvOS)
        case .wishlist: DulcetWishlistDestinationView()
        case let .discover(destination): DulcetDiscoverReaderDestinationView(destination: destination)
#endif
        case .songs: DulcetLibrarySongsPage(downloaded: false)
        case .downloaded: DulcetDownloadedPage()
        case let .listenNowShelf(shelf): DulcetListenNowShelfPage(shelf: shelf)
        case let .section(section): DulcetReaderSectionView(section: section)
        case let .album(id): DulcetReaderTrackListPage(query: .album(rawID: id.rawID), kind: .album, id: id)
        case let .playlist(id): DulcetPlaylistPage(id: id)
        case let .artist(id): DulcetReaderArtistPage(id: id)
        case let .genre(name): DulcetReaderTrackListPage(query: .songsByGenre(name), kind: .genre, id: nil, genreName: name)
        case let .albumList(type): DulcetReaderAlbumGridView(type: type, title: type.title)
        case let .myLibrarySection(section): DulcetMyLibrarySectionPage(section: section)
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
        DulcetReaderScreen(query: query, showsDestinationStatus: true) { model in
#if os(tvOS)
            // A television detail page uses the screen's width: art and actions on the left, the
            // tracks scrolling on the right. The accessibility text sizes keep the stacked page,
            // where the columns could not breathe.
            if !dynamicTypeSize.isAccessibilitySize {
                wideAlbumPage(model)
            } else {
                standardPage(model)
            }
#else
            standardPage(model)
#endif
        }
    }

    private func standardPage(_ model: DulcetLibraryWindowModel) -> some View {
        DulcetReaderListPage(
            title: title(model.window),
            keepsTVNavigationBar: !(kind == .album && model.window?.header != nil),
            titleInHeader: kind == .album && model.window?.header != nil,
            freshness: model.window?.freshness
        ) { width in
            if let window = model.window {
                header(window, width: width)
#if os(iOS)
                    .padding(.bottom, DulcetSpacing.md)
#endif
                let subject: DulcetReaderCopy.Subject = window.header == nil ? (kind == .album ? .album : .list) : .albumTracks
#if os(iOS)
                DulcetReaderListRows(model: model, subject: subject) { window in
                    trackRows(window, model: model)
                }
#else
                DulcetReaderListBody(model: model, subject: subject) { window in
                    tracks(window, model: model)
                }
#endif
            } else {
                ProgressView().frame(maxWidth: .infinity, minHeight: 120)
            }
        }
#if os(iOS)
        .toolbar { pageToolbar(model.window?.header) }
#endif
    }

#if os(tvOS)
    private func wideAlbumPage(_ model: DulcetLibraryWindowModel) -> some View {
        DulcetTVDetailColumns(title: title(model.window), leadingColumnWidth: 520) {
            if let window = model.window {
                wideAlbumHeader(window)
            } else {
                ProgressView().frame(maxWidth: .infinity, minHeight: 120)
            }
        } content: { _ in
            if let window = model.window {
                let subject: DulcetReaderCopy.Subject = window.header == nil ? (kind == .album ? .album : .list) : .albumTracks
                DulcetReaderListBody(model: model, subject: subject) { window in
                    tracks(window, model: model)
                }
            }
        }
    }

    /// The left column: artwork, the page's name and artists, the metadata line and the actions.
    private func wideAlbumHeader(_ window: DulcetLibraryWindow) -> some View {
        let item = window.header
        let playable = window.playableTracks
        return DulcetTVDetailHeader(title: title(window), titleIdentifier: "dulcet.\(kind.rawValue).title") {
            if let item, kind != .genre {
                DulcetArtworkView(artwork: item.artwork, size: DulcetTVDetailMetrics.artworkSize)
            }
        } detail: {
            if let item, !item.credits.isEmpty {
                DulcetArtistLink(credits: item.credits, font: .title3)
            }
            if let detail = detailLine(item, window: window), !detail.isEmpty {
                Text(detail)
                    .font(.subheadline)
                    .dulcetForeground(.secondaryTextOnWindow)
                    .lineLimit(nil)
            }
        } actions: {
            Button {
                store.pressPlay(playTarget) {
                    store.playReaderTracks(playable, sourceKind: sourceKind, sourceID: id, sourceName: title(window))
                }
            } label: { DulcetPlayLabel(isPlaying: store.playsTarget(playTarget)) }
                .fixedSize()
                .disabled(playable.isEmpty)
                .dulcetTakesFocusOnArrival(isEnabled: !playable.isEmpty)
                .accessibilityIdentifier("dulcet.\(kind.rawValue).play")
            Button {
                store.playReaderTracks(playable, shuffle: true, sourceKind: sourceKind, sourceID: id, sourceName: title(window))
            } label: { Label(DulcetStrings.shuffle, systemImage: "shuffle") }
                .fixedSize()
                .disabled(playable.isEmpty)
                .accessibilityIdentifier("dulcet.\(kind.rawValue).shuffle")
            if let item {
                DulcetTVDetailMoreMenu(identifier: "dulcet.\(kind.rawValue).more") {
                    DulcetReaderItemMenuItems(item: item)
                }
            }
        }
    }

    /// More as a page control: a television reader page hides its navigation bar, so a toolbar
    /// item would leave its actions out of reach.
    private func pageMoreMenu(_ item: DulcetReaderItem) -> some View {
        Menu { DulcetReaderItemMenuItems(item: item) }
        label: { Image(systemName: "ellipsis") }
            .menuStyle(.button)
            .buttonBorderShape(.circle)
            .accessibilityLabel(DulcetStrings.more)
            .accessibilityIdentifier("dulcet.\(kind.rawValue).more")
    }
#endif

    private func title(_ window: DulcetLibraryWindow?) -> String {
        genreName ?? window?.header?.displayTitle ?? ""
    }

    /// An album's numbers are its running order. A genre on a television lists tracks as a playlist
    /// does, with art and no numbers.
    private static func numbersTracks(_ kind: DulcetReaderItemKind) -> Bool {
#if os(tvOS)
        kind != .genre
#else
        true
#endif
    }

    private var sourceKind: DulcetPlaybackQueueSourceKind {
        switch kind {
        case .album: .album
        case .playlist: .playlist
        default: .library
        }
    }

    /// What this page's Play stands for, so it shows Pause while the page's own music plays.
    private var playTarget: DulcetPlayTarget? {
        guard sourceKind != .library, let id else { return nil }
        return .collection(sourceKind, id)
    }

    @ViewBuilder
    private func header(_ window: DulcetLibraryWindow, width: CGFloat) -> some View {
        let accessibilitySize = dynamicTypeSize.isAccessibilitySize
        let arrangement = DulcetDetailHeaderArrangement.forWidth(width, accessibilitySize: accessibilitySize)
        let item = window.header
        DulcetDetailHeader(
            // A genre's name is the navigation bar's large title.
            title: kind == .genre ? nil : (genreName ?? item?.displayTitle ?? String()),
            titleIdentifier: "dulcet.\(kind.rawValue).title",
            metadata: detailLine(item, window: window),
            arrangement: arrangement,
            artworkSize: arrangement.artworkSize(width: width, accessibilitySize: accessibilitySize)
        ) { size in
            if let item, kind != .genre {
                DulcetArtworkView(artwork: item.artwork, size: size)
            }
        } credit: {
            if let item, !item.credits.isEmpty {
                DulcetArtistLink(credits: item.credits, font: .title3)
            }
        } accessory: {
            EmptyView()
        } actions: {
#if os(tvOS)
            // Apple TV hides a pushed page's navigation bar, so More is a page control here.
            VStack(spacing: DulcetSpacing.xs) {
                playbackActions(window)
                if let item { pageMoreMenu(item).buttonStyle(.bordered) }
            }
#else
            playbackActions(window)
#endif
        }
#if os(macOS)
        .toolbar { pageToolbar(item) }
#endif
    }

#if !os(tvOS)
    /// The page's More. On iOS the header is a `List` cell, and a cell scrolled out of the list
    /// takes its toolbar items with it, so the page carries them instead of the header.
    @ToolbarContentBuilder
    private func pageToolbar(_ item: DulcetReaderItem?) -> some ToolbarContent {
        if let item {
#if os(iOS)
            if kind == .album, store.myLibraryAvailable, let target = item.favouriteTarget {
                ToolbarItem(placement: .primaryAction) {
                    DulcetMyLibraryButton(target: target, quickAction: true, itemTitle: item.displayTitle)
                }
            }
#endif
            ToolbarItem(placement: .primaryAction) {
                Menu { DulcetReaderItemMenuItems(item: item) }
                label: { Label(DulcetStrings.more, systemImage: "ellipsis") }
                    .accessibilityIdentifier("dulcet.\(kind.rawValue).more")
            }
        }
    }
#endif

    private func playbackActions(_ window: DulcetLibraryWindow) -> some View {
        DulcetEqualWidthActions {
            playButton(window, fills: true)
        } secondary: {
            shuffleButton(window, fills: true)
        }
    }

    private func playButton(_ window: DulcetLibraryWindow, fills: Bool) -> some View {
        let playable = window.playableTracks
        return DulcetHeaderPlayAction(isEnabled: !playable.isEmpty, fillsWidth: fills, isPlaying: store.playsTarget(playTarget)) {
            store.pressPlay(playTarget) {
                store.playReaderTracks(playable, sourceKind: sourceKind, sourceID: id, sourceName: title(window))
            }
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
                .lineLimit(1)
                .frame(maxWidth: fills ? .infinity : nil)
        }
        .buttonStyle(.bordered)
        .disabled(playable.isEmpty)
        .accessibilityIdentifier("dulcet.\(kind.rawValue).shuffle")
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
        LazyVStack(alignment: .leading, spacing: 0) {
            trackRows(window, model: model)
        }
    }

    @ViewBuilder
    private func trackRows(_ window: DulcetLibraryWindow, model: DulcetLibraryWindowModel) -> some View {
        let playable = window.playableTracks
        let discs = Set(window.items.compactMap(\.discNumber))
        let albumArtists = window.header?.artistName.map { [$0] } ?? []
        ForEach(Array(window.items.enumerated()), id: \.element.id) { index, item in
            let startsDisc = kind == .album && discs.count > 1
                && item.discNumber != nil
                && (index == 0 || window.items[index - 1].discNumber != item.discNumber)
            if startsDisc, let disc = item.discNumber {
                Text(DulcetStrings.discTitle(disc))
                    .font(.headline)
                    .dulcetForeground(.secondaryTextOnWindow)
                    .padding(.top, DulcetSpacing.sm)
                    .accessibilityAddTraits(.isHeader)
            }
            DulcetReaderTrackRow(
                item: item,
                index: window.leadingOffset + index + 1,
                showsIndex: Self.numbersTracks(kind),
                showsAlbum: kind != .album,
                showsArtwork: kind != .album,
                albumArtists: albumArtists,
                showsLibraryQuickAction: kind == .album || kind == .genre
            ) { track in
                store.playReaderTracks(playable, startingAt: track, sourceKind: sourceKind, sourceID: id, sourceName: title(window))
            }
            .dulcetReaderRow(index, in: model)
            // Between rows only: none above the first row, and none right below a disc's title.
            .dulcetSongRowSeparator(
                above: index > 0 && !startsDisc,
                songRowShowingIndex: Self.numbersTracks(kind),
                artwork: kind != .album
            )
        }
    }
}

/// An artist: a header, then the artist's albums.
struct DulcetReaderArtistPage: View {
    @Environment(\.dynamicTypeSize) private var dynamicTypeSize
    @Environment(DulcetPresentationStore.self) private var store
#if os(iOS)
    @Environment(\.horizontalSizeClass) private var horizontalSizeClass
#endif
    let id: DulcetProviderItemID

    /// The artist's Play shows Pause while music played from this artist is sounding.
    private var artistTarget: DulcetPlayTarget { .collection(.artist, id) }

    var body: some View {
#if os(tvOS)
        if dynamicTypeSize.isAccessibilitySize {
            stackedPage
        } else {
            DulcetTVArtistDetail(id: id)
        }
#else
        stackedPage
#endif
    }

    private var stackedPage: some View {
        DulcetReaderScreen(query: .artist(rawID: id.rawID), showsDestinationStatus: true) { model in
          DulcetReaderScreen(query: .artistTopSongs(rawID: id.rawID)) { topSongs in
#if os(iOS)
            // A phone's artist page opens on the artist's picture, edge to edge, as Music's does.
            // A regular-width iPad keeps the shared detail header beside its wide columns.
            if horizontalSizeClass == .compact {
                heroPage(model: model, topSongs: topSongs)
            } else {
                readerPage(model: model, topSongs: topSongs)
            }
#else
            readerPage(model: model, topSongs: topSongs)
#endif
          }
        }
    }

#if os(iOS)
    private func heroPage(model: DulcetLibraryWindowModel, topSongs: DulcetLibraryWindowModel) -> some View {
        DulcetArtistHeroPage(title: model.window?.header?.displayTitle ?? "") { width, height, barBottom in
            if let header = model.window?.header {
                let albums = model.window?.items.filter { $0.kind == .album } ?? []
                let playable = topSongs.window?.playableTracks ?? []
                let hasMusic = !playable.isEmpty || !albums.isEmpty
                DulcetArtistHero(
                    title: header.displayTitle,
                    metadata: DulcetArtistDiscography.albumCountLabel(albums),
                    artwork: header.artwork,
                    fallback: DulcetArtistHeroLayout.newestAlbum(albums)?.artwork,
                    height: height,
                    barBottom: barBottom
                ) {
                    DulcetArtistHeroActions(isEnabled: hasMusic, isPlaying: store.playsTarget(artistTarget)) {
                        store.pressPlay(artistTarget) {
                            store.playArtist(id: id, name: header.displayTitle, topSongs: playable, albums: albums, shuffle: false)
                        }
                    } shuffle: {
                        store.playArtist(id: id, name: header.displayTitle, topSongs: playable, albums: albums, shuffle: true)
                    }
                }
                .toolbar {
                    ToolbarItem(placement: .primaryAction) {
                        Menu(DulcetStrings.more, systemImage: "ellipsis") { DulcetReaderItemMenuItems(item: header) }
                            .accessibilityLabel(DulcetStrings.more)
                            .accessibilityIdentifier("dulcet.artist.more")
                    }
                }
            } else {
                // The bar's own space while the artist loads, so the spinner is not under it.
                Color.clear.frame(height: barBottom)
            }
        } content: { width in
            if let window = model.window {
                DulcetArtistTopSongsSection(id: id, model: topSongs)
                    .accessibilityIdentifier("dulcet.artist.topSongs")
                DulcetReaderListBody(model: model, subject: window.header == nil ? .list : .albumTracks) { _ in
                    DulcetArtistDiscography(items: window.items, width: width)
                }
            } else {
                ProgressView().frame(maxWidth: .infinity, minHeight: 120) // design-token: allow the shared artist page's loading placeholder
            }
        }
    }
#endif

    private func readerPage(model: DulcetLibraryWindowModel, topSongs: DulcetLibraryWindowModel) -> some View {
            DulcetReaderPage(title: model.window?.header?.displayTitle ?? "", keepsTVNavigationBar: false, titleInHeader: true) { width in
                if let window = model.window {
                    if let header = window.header {
                        artistHeader(
                            header,
                            width: width,
                            playable: topSongs.window?.playableTracks ?? [],
                            albums: window.items.filter { $0.kind == .album }
                        )
#if os(tvOS)
                        // The actions sit past the last album tile, so Up from the grid finds them
                        // only when the whole header is a section.
                        .focusSection()
#endif
                    }
                    DulcetArtistTopSongsSection(id: id, model: topSongs)
                        .accessibilityIdentifier("dulcet.artist.topSongs")
                    DulcetReaderListBody(model: model, subject: window.header == nil ? .list : .albumTracks) { _ in
                        DulcetArtistDiscography(items: window.items, width: width)
                    }
                } else {
                    ProgressView().frame(maxWidth: .infinity, minHeight: 120)
                }
            }
    }

    /// The shared detail header: the artist's circle, name and album count, then Play and Shuffle.
    /// Pin, Add to Library and Favorite are in the toolbar's More, as an album's are.
    private func artistHeader(
        _ header: DulcetReaderItem, width: CGFloat, playable: [DulcetTrack], albums: [DulcetReaderItem]
    ) -> some View {
        let accessibilitySize = dynamicTypeSize.isAccessibilitySize
        let arrangement = DulcetDetailHeaderArrangement.forWidth(width, accessibilitySize: accessibilitySize)
        let hasMusic = !playable.isEmpty || !albums.isEmpty
        return DulcetDetailHeader(
            title: header.displayTitle,
            titleIdentifier: "dulcet.artist.title",
            metadata: DulcetArtistDiscography.albumCountLabel(albums),
            arrangement: arrangement,
            artworkSize: arrangement.artworkSize(width: width, accessibilitySize: accessibilitySize)
        ) { size in
            DulcetArtworkView(artwork: header.artwork, size: size, role: .artist)
                .clipShape(Circle())
        } actions: {
#if os(tvOS)
            // Apple TV hides a pushed page's navigation bar, so the membership controls stay on
            // the page at the accessibility sizes this stacked page is drawn for.
            VStack(spacing: DulcetSpacing.xs) {
                playbackActions(header, playable: playable, albums: albums, hasMusic: hasMusic)
                membership(header, hasMusic: hasMusic).frame(maxWidth: .infinity)
            }
#else
            playbackActions(header, playable: playable, albums: albums, hasMusic: hasMusic)
#endif
        }
#if !os(tvOS)
        .toolbar {
            ToolbarItem(placement: .primaryAction) {
                // Built from a title, as the playlist page's is: a Mac menu with a custom label
                // reached accessibility as no element at all.
                Menu(DulcetStrings.more, systemImage: "ellipsis") { DulcetReaderItemMenuItems(item: header) }
                    .accessibilityLabel(DulcetStrings.more)
                    .accessibilityIdentifier("dulcet.artist.more")
            }
        }
#endif
    }

    private func playbackActions(
        _ header: DulcetReaderItem, playable: [DulcetTrack], albums: [DulcetReaderItem], hasMusic: Bool
    ) -> some View {
        DulcetEqualWidthActions {
            DulcetHeaderPlayAction(isEnabled: hasMusic, fillsWidth: true, isPlaying: store.playsTarget(artistTarget)) {
                store.pressPlay(artistTarget) {
                    store.playArtist(id: id, name: header.displayTitle, topSongs: playable, albums: albums, shuffle: false)
                }
            }
            .dulcetTakesFocusOnArrival(isEnabled: hasMusic)
            .accessibilityIdentifier("dulcet.artist.play")
        } secondary: {
            Button {
                store.playArtist(id: id, name: header.displayTitle, topSongs: playable, albums: albums, shuffle: true)
            } label: {
                Label(DulcetStrings.shuffle, systemImage: "shuffle")
                    .lineLimit(1)
                    .frame(maxWidth: .infinity)
            }
            .buttonStyle(.bordered)
            .disabled(!hasMusic)
            .accessibilityIdentifier("dulcet.artist.shuffle")
        }
    }

#if os(tvOS)
    private func membership(_ header: DulcetReaderItem, hasMusic: Bool) -> some View {
        HStack(spacing: DulcetSpacing.md) {
            DulcetLibraryPinControl(item: header, size: .title3)
            if let target = header.favouriteTarget {
                DulcetMyLibraryButton(target: target)
                DulcetFavouriteButton(
                    target: target,
                    published: header.isFavourite,
                    title: header.displayTitle,
                    size: .title3,
                    identifier: "dulcet.artist.favorite"
                )
                .dulcetTakesFocusOnArrival(isEnabled: !hasMusic)
            }
        }
    }
#endif
}

#if os(tvOS)
/// An artist on Apple TV, in the same columns as an album. Play and Shuffle start the artist's Top
/// Songs, the one list of the artist's tracks the page holds.
private struct DulcetTVArtistDetail: View {
    @Environment(DulcetPresentationStore.self) private var store
    let id: DulcetProviderItemID

    private var artistTarget: DulcetPlayTarget { .collection(.artist, id) }

    var body: some View {
        DulcetReaderScreen(query: .artist(rawID: id.rawID), showsDestinationStatus: true) { model in
            DulcetReaderScreen(query: .artistTopSongs(rawID: id.rawID)) { topSongs in
                DulcetTVDetailColumns(title: model.window?.header?.displayTitle ?? "") {
                    if let header = model.window?.header {
                        artistHeader(
                            header,
                            playable: topSongs.window?.playableTracks ?? [],
                            albums: model.window?.items.filter { $0.kind == .album } ?? [],
                            isSettled: isSettled(model) && isSettled(topSongs)
                        )
                    } else {
                        ProgressView().frame(maxWidth: .infinity, minHeight: 120)
                    }
                } content: { columnWidth in
                    DulcetArtistTopSongsSection(id: id, model: topSongs)
                        .accessibilityIdentifier("dulcet.artist.topSongs")
                    if let window = model.window {
                        DulcetReaderListBody(model: model, subject: window.header == nil ? .list : .albumTracks) { _ in
                            DulcetArtistDiscography(items: window.items, width: columnWidth, contentWidth: columnWidth)
                        }
                    }
                }
            }
        }
    }

    private func play(
        _ header: DulcetReaderItem, playable: [DulcetTrack], albums: [DulcetReaderItem], shuffle: Bool
    ) {
        store.playArtist(id: id, name: header.displayTitle, topSongs: playable, albums: albums, shuffle: shuffle)
    }

    private func isSettled(_ model: DulcetLibraryWindowModel) -> Bool {
        guard let window = model.window else { return false }
        return window.freshness != .loading && window.itemsState != .loading
    }

    private func artistHeader(_ header: DulcetReaderItem, playable: [DulcetTrack], albums: [DulcetReaderItem], isSettled: Bool) -> some View {
        let hasMusic = !playable.isEmpty || !albums.isEmpty
        return DulcetTVDetailHeader(title: header.displayTitle, titleIdentifier: "dulcet.artist.title") {
            DulcetArtworkView(artwork: header.artwork, size: DulcetTVDetailMetrics.artworkSize, role: .artist)
                .clipShape(Circle())
        } detail: {
            if let count = DulcetArtistDiscography.albumCountLabel(albums) {
                Text(count)
                    .font(.subheadline)
                    .dulcetForeground(.secondaryTextOnWindow)
            }
        } actions: {
            Button {
                store.pressPlay(artistTarget) {
                    play(header, playable: playable, albums: albums, shuffle: false)
                }
            } label: { DulcetPlayLabel(isPlaying: store.playsTarget(artistTarget)) }
                .disabled(!hasMusic)
                .dulcetTakesFocusOnArrival(isEnabled: hasMusic)
                .accessibilityIdentifier("dulcet.artist.play")
            Button {
                play(header, playable: playable, albums: albums, shuffle: true)
            } label: { Label(DulcetStrings.shuffle, systemImage: "shuffle") }
                .disabled(!hasMusic)
                .accessibilityIdentifier("dulcet.artist.shuffle")
            // Favorite lives in More, as it does on an album and a playlist.
            DulcetTVDetailMoreMenu(identifier: "dulcet.artist.more") {
                DulcetReaderItemMenuItems(item: header)
            }
            // An artist with nothing to play still opens on a control that works. Only once both
            // reads have answered: the header comes first, and claiming here then would leave focus
            // on More when the albums arrive and Play can take it.
            .dulcetTakesFocusOnArrival(isEnabled: !hasMusic && isSettled)
        }
    }
}
#endif

// MARK: - The Library root

/// The Library destination while the reader draws it: the section at the stack's root.
struct DulcetReaderLibraryRoot: View {
    @Environment(DulcetPresentationStore.self) private var store
#if os(iOS)
    @Environment(\.horizontalSizeClass) private var horizontalSizeClass
#endif

    var body: some View {
        DulcetReaderHomeView()
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
    @Environment(\.dulcetShellSearchField) private var shellSearchField
    @Bindable var store: DulcetPresentationStore
    @State private var model = DulcetReaderSearchModel()

    @State private var scope = DulcetDiscoverSearchScope.server
    var body: some View {
#if os(iOS) || os(macOS)
        VStack(spacing: 0) {
            if store.librarySession?.discoverPublication?.access.discoverAvailable == true {
                Picker(DulcetStrings.discoverScope, selection: $scope) {
                    Text(DulcetStrings.discoverYourServer).tag(DulcetDiscoverSearchScope.server)
                    Text(DulcetStrings.discoverEverywhere).tag(DulcetDiscoverSearchScope.everywhere)
                }.pickerStyle(.segmented).padding(DulcetSpacing.sm).accessibilityIdentifier("discover.scope")
            }
            if scope == .everywhere, let session = store.librarySession, let reader = session.discover,
               session.discoverPublication?.access.discoverAvailable == true {
                DulcetDiscoverSearchView(reader: reader, openLibrary: store.openDiscoverLibrary,
                    query: shellSearchField ? $store.searchQuery : nil).id(session.readerGeneration)
            } else { serverSearch }
        }
        .onChange(of: store.librarySession?.readerGeneration) { _, _ in scope = .server }
        .onChange(of: store.librarySession?.discoverPublication?.access.discoverAvailable) { _, available in
            if available != true { scope = .server }
        }
#else
        serverSearch
#endif
    }

    private var serverSearch: some View {
        DulcetSearchView(
            recentSearches: store.librarySession?.libraryDepth.recentSearches ?? [],
            onRememberSearch: { store.librarySession?.rememberSearch($0) },
            onClearRecentSearches: { store.librarySession?.clearRecentSearches() },
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
                isSettlingConnection: store.librarySession?.isSettlingConnection == true,
                onActivate: { row in store.activateReaderSearchRow(row, in: model.current) }
            )
        )
        .safeAreaInset(edge: .top, spacing: 0) {
            if !store.searchQuery.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
                let deviceOnly = store.librarySession?.mode == .deviceOnly
                DulcetConnectionStatus(
                    indicator: store.readerConnectionIndicator(serverReadFailed: model.current?.scope.isConnectionFailure == true),
                    identifier: "dulcet.search.connection",
                    retryIdentifier: deviceOnly ? "dulcet.reader.reconnect" : "dulcet.reader.retry",
                    retry: { model.retry { dulcetReaderRetry(store) } }, accountBannerOpaque: true)
            }
        }
        .onAppear(perform: open)
        .onDisappear { model.close() }
        .onChange(of: store.searchQuery) { _, query in if scope == .server { model.updateQuery(query) } }
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

#if os(iOS) || os(macOS)
extension DulcetPresentationStore {
    func openDiscoverLibrary(_ reference: DulcetDiscoverLibraryRef) {
        guard let session = librarySession, let account = session.account,
              reference.providerInstanceId == account.providerInstanceID else { return }
        let id = DulcetProviderItemID(providerInstanceID: reference.providerInstanceId, rawID: reference.id)
        discoverLibraryLookupID = nil
        discoverLibraryLookup?.cancel(); discoverLibraryLookup = nil
        switch reference.type {
        case "album":
            DulcetUIProofMarkers.record("discover-library:\(id.providerInstanceID):album:\(id.rawID)")
            showReaderPage(.album(id))
        case "artist":
            DulcetUIProofMarkers.record("discover-library:\(id.providerInstanceID):artist:\(id.rawID)")
            showReaderPage(.artist(id))
        case "song":
            (session.reader as? any DulcetMyLibraryReading)?.refreshMyLibrary()
            if let track = session.myLibrary.songs.first(where: { $0.id == id })?.playableTrack {
                DulcetUIProofMarkers.record("discover-play:\(track.id.providerInstanceID):\(track.id.rawID)")
                playReaderTracks([track], sourceKind: .library, sourceID: nil, sourceName: DulcetStrings.library)
                return
            }
            guard let lookup = session.reader as? any DulcetLibrarySongLookingUp else {
                session.post(DulcetStrings.discoverLookupUnavailable); return
            }
            let generation = session.readerGeneration
            let requestID = UUID()
            discoverLibraryLookupID = requestID
            discoverLibraryLookup = lookup.lookupLibrarySong(reference) { [weak self, weak session] item in
                guard let self, let session, session === self.librarySession,
                      generation == session.readerGeneration, self.discoverLibraryLookupID == requestID else { return }
                self.discoverLibraryLookupID = nil
                self.discoverLibraryLookup = nil
                guard let item, item.id == id, !item.isUnavailableOffline, let track = item.playableTrack else {
                    session.post(DulcetStrings.discoverLookupUnavailable); return
                }
                DulcetUIProofMarkers.record("discover-play:\(track.id.providerInstanceID):\(track.id.rawID)")
                self.playReaderTracks([track], sourceKind: .library, sourceID: nil, sourceName: DulcetStrings.library)
            }
        default: session.post(DulcetStrings.discoverLookupUnavailable)
        }
    }
}
#endif
