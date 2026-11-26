#if os(macOS) || os(iOS) || os(tvOS)
import SwiftUI

// Playlists in the library (spec §18.6): the list with New Playlist, a playlist's page you can play
// and -- for a playlist the person may edit -- rename, delete, reorder and remove from, and "Add to
// Playlist" from a track's or album's context menu. Every edit is in the next publication of every
// screen showing the playlist before any request (the core's overlay); what is still to reach the
// server, and what could not, is said on the playlist itself. Another user's playlist is drawn
// read-only with its owner, with no edit affordance at all. tvOS lists and plays playlists, and adds
// to one or creates one from a track's or album's menu; its playlist page edits nothing.

private extension DulcetPresentationStore {
    var playlistEditor: DulcetPlaylistEditor? { librarySession?.playlists }

    /// Whether this item belongs to the account the session reads as (ids are per server).
    func isSessionItem(_ id: DulcetProviderItemID) -> Bool {
        librarySession?.account?.providerInstanceID == id.providerInstanceID
    }
}

// MARK: - The Playlists section

struct DulcetPlaylistsView: View {
    @Environment(DulcetPresentationStore.self) private var store
    @State private var naming = false

    var body: some View {
        DulcetReaderScreen(query: .playlists, showsDestinationStatus: true) { model in
            DulcetReaderPage(title: DulcetStrings.readerPlaylists) { _ in
                if !isEmptyAndUnfiltered(model.window) {
                    DulcetLibraryCategoryControls(category: "playlists", offersArtist: false)
                }
#if !os(tvOS)
                if store.playlistEditor != nil {
                    Button(DulcetStrings.playlistNewEllipsis, systemImage: "plus") { naming = true }
                        .buttonStyle(.bordered)
                        .accessibilityIdentifier("dulcet.playlists.new")
                }
#endif
                DulcetReaderListBody(model: model, emptyState: playlistsEmptyState) { window in
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
        .dulcetPlaylistNamePrompt(isPresented: $naming, title: DulcetStrings.playlistNew, initial: "", confirm: DulcetStrings.playlistCreate) { name in
            store.playlistEditor?.createPlaylist(named: name, with: nil)
        }
    }

    /// With no playlists at all there is nothing to sort or filter. A filter that matched nothing
    /// keeps its controls, or there would be no way back.
    private func isEmptyAndUnfiltered(_ window: DulcetLibraryWindow?) -> Bool {
#if os(tvOS)
        guard let window, window.items.isEmpty, window.freshness != .loading, window.itemsState != .loading else { return false }
        return (store.librarySession?.libraryDepth.filters["playlists"] ?? "all") == "all"
#else
        false
#endif
    }

    private var playlistsEmptyState: DulcetReaderEmptyState? {
#if os(tvOS)
        guard (store.librarySession?.libraryDepth.filters["playlists"] ?? "all") == "all" else { return nil }
        return DulcetReaderEmptyState(title: DulcetStrings.readerNoPlaylists, hint: DulcetStrings.readerAddPlaylistsHint,
                                      systemImage: "music.note.list")
#else
        nil
#endif
    }
}

// MARK: - A playlist's page

struct DulcetPlaylistPage: View {
    @Environment(DulcetPresentationStore.self) private var store
    let id: DulcetProviderItemID

    var body: some View {
        // A playlist made here and since created on the server reopens under the server's id.
        let rawID = store.playlistEditor?.resolvedID(id.rawID) ?? id.rawID
        DulcetPlaylistPageContent(id: DulcetProviderItemID(providerInstanceID: id.providerInstanceID, rawID: rawID))
            .id(rawID)
    }
}

private struct DulcetPlaylistPageContent: View {
    @Environment(\.dynamicTypeSize) private var dynamicTypeSize
    @Environment(DulcetPresentationStore.self) private var store
    let id: DulcetProviderItemID
    @State private var editing = false
    @State private var renaming = false
    @State private var confirmingDelete = false

    var body: some View {
        DulcetReaderScreen(query: .playlist(rawID: id.rawID), showsDestinationStatus: true) { model in
            Group {
                if editing, let window = model.window, let edit = DulcetPlaylistEditContext(window: window, id: id) {
                    editList(window, context: edit)
                } else {
                    page(model)
                }
            }
            .dulcetPlaylistNamePrompt(
                isPresented: $renaming,
                title: DulcetStrings.playlistRename,
                initial: model.window?.header?.displayTitle ?? "",
                confirm: DulcetStrings.playlistRename
            ) { name in
                DulcetUIProofMarkers.detail("playlist.rename.submit", context: "id=\(id.rawID)")
                store.playlistEditor?.perform(.rename(playlistID: id.rawID, name: name))
            }
            .dulcetPlaylistDeleteConfirmation(name: model.window?.header?.displayTitle, isPresented: $confirmingDelete) {
                store.playlistEditor?.perform(.delete(playlistID: id.rawID)) { result in
                    guard result.record == .pending || result.record == .compactedAway else { return }
                    store.popReader(to: Array(store.readerPath.dropLast()))
                }
            }
        }
    }

    @ViewBuilder
    private func page(_ model: DulcetLibraryWindowModel) -> some View {
#if os(tvOS)
        if !dynamicTypeSize.isAccessibilitySize, let window = model.window,
           window.header != nil || !window.items.isEmpty || !Self.isUnavailable(window) {
            DulcetTVDetailColumns(title: window.header?.displayTitle ?? "") {
                tvHeader(window)
            } content: { _ in
                awaitingChoiceBanner
                problemBanner
                DulcetReaderListBody(model: model, subject: window.header == nil ? .list : .albumTracks) { window in
                    tracks(window, model: model)
                }
            }
        } else {
            stackedPage(model)
        }
#else
        stackedPage(model)
#endif
    }

#if os(tvOS)
    private func tvHeader(_ window: DulcetLibraryWindow) -> some View {
        let item = window.header
        let playable = window.playableTracks
        return DulcetTVDetailHeader(title: item?.displayTitle ?? DulcetStrings.playlistUntitledName,
                                    titleIdentifier: "dulcet.playlist.title") {
            if let item {
                DulcetArtworkView(artwork: item.artwork, size: DulcetTVDetailMetrics.artworkSize)
            }
        } detail: {
            if let detail = detailLine(item, window: window) {
                Text(detail)
                    .font(.subheadline)
                    .dulcetForeground(.secondaryTextOnWindow)
                    .lineLimit(nil)
            }
            if let item, let status = DulcetPlaylistPresentation.status(of: item) {
                Label(status, systemImage: item.isEditable ? "arrow.triangle.2.circlepath" : "person")
                    .font(.caption)
                    .dulcetForeground(.secondaryTextOnWindow)
                    .accessibilityIdentifier("dulcet.playlist.status")
            }
        } actions: {
            Button {
                store.pressPlay(playTarget(window)) {
                    store.playReaderTracks(playable, sourceKind: playlistSourceKind(window), sourceID: playlistSourceID(window), sourceName: item?.displayTitle ?? "")
                }
            } label: { DulcetPlayLabel(isPlaying: store.playsTarget(playTarget(window))) }
                .disabled(playable.isEmpty)
                .dulcetTakesFocusOnArrival(isEnabled: !playable.isEmpty)
                .accessibilityIdentifier("dulcet.playlist.play")
            Button {
                store.playReaderTracks(playable, shuffle: true, sourceKind: playlistSourceKind(window), sourceID: playlistSourceID(window), sourceName: item?.displayTitle ?? "")
            } label: { Label(DulcetStrings.shuffle, systemImage: "shuffle") }
                .disabled(playable.isEmpty)
                .accessibilityIdentifier("dulcet.playlist.shuffle")
            if let item {
                DulcetTVDetailMoreMenu(identifier: "dulcet.playlist.more") {
                    DulcetReaderItemMenuItems(item: item)
                }
                // An empty playlist has nothing to play; its menu is what the page can still do.
                .dulcetTakesFocusOnArrival(isEnabled: playable.isEmpty)
            }
        }
    }
#endif

    private func stackedPage(_ model: DulcetLibraryWindowModel) -> some View {
        DulcetReaderListPage(
            title: model.window?.header?.displayTitle ?? "",
            keepsTVNavigationBar: false,
            titleInHeader: true,
            freshness: model.window?.freshness
        ) { width in
            if let window = model.window {
                // Gone from the server, or never read and offline, with nothing kept: the core has
                // no playlist to name, so there is no title to show and nothing to play -- an empty header and
                // two disabled buttons would only push the statement down. What the page says
                // about a failed edit comes first, then the statement in place of the tracks.
                if window.header != nil || !window.items.isEmpty || !Self.isUnavailable(window) {
                    header(window, width: width)
#if os(iOS)
                        .padding(.bottom, DulcetSpacing.md)
#endif
                }
                awaitingChoiceBanner
                problemBanner
#if os(iOS)
                DulcetReaderListRows(model: model, subject: window.header == nil ? .list : .albumTracks) { window in
                    trackRows(window, model: model)
                }
#else
                DulcetReaderListBody(model: model, subject: window.header == nil ? .list : .albumTracks) { window in
                    tracks(window, model: model)
                }
#endif
            } else {
                ProgressView().frame(maxWidth: .infinity, minHeight: 120)
            }
        }
#if os(iOS)
        .toolbar {
            if let window = model.window { pageToolbar(window) }
        }
#endif
    }

    private static func isUnavailable(_ window: DulcetLibraryWindow) -> Bool {
        if case .unavailable = window.freshness { return true }
        return false
    }

    // MARK: Header

    /// The shared detail header: artwork, name, the count and comment, the sync status, then Play
    /// and Shuffle. Edit is a toolbar button that Done replaces in the same place; Rename, Delete
    /// and the rest of the playlist's actions are in the toolbar's More.
    @ViewBuilder
    private func header(_ window: DulcetLibraryWindow, width: CGFloat) -> some View {
        let accessibilitySize = dynamicTypeSize.isAccessibilitySize
        let arrangement = DulcetDetailHeaderArrangement.forWidth(width, accessibilitySize: accessibilitySize)
        let item = window.header
        let playable = window.playableTracks
        DulcetDetailHeader(
            title: item?.displayTitle ?? DulcetStrings.playlistUntitledName,
            titleIdentifier: "dulcet.playlist.title",
            metadata: detailLine(item, window: window),
            arrangement: arrangement,
            artworkSize: arrangement.artworkSize(width: width, accessibilitySize: accessibilitySize)
        ) { size in
            if let item {
                DulcetArtworkView(artwork: item.artwork, size: size)
            }
        } credit: {
            EmptyView()
        } accessory: {
            if let item, let status = DulcetPlaylistPresentation.status(of: item) {
                Label(status, systemImage: item.isEditable ? "arrow.triangle.2.circlepath" : "person")
                    .font(.caption)
                    .dulcetForeground(.secondaryTextOnWindow)
                    .accessibilityIdentifier("dulcet.playlist.status")
            }
            DulcetPlaylistDownloadStatus(id: id)
        } actions: {
            DulcetEqualWidthActions {
                DulcetHeaderPlayAction(isEnabled: !playable.isEmpty, fillsWidth: true, isPlaying: store.playsTarget(playTarget(window))) {
                    store.pressPlay(playTarget(window)) {
                        store.playReaderTracks(playable, sourceKind: playlistSourceKind(window), sourceID: playlistSourceID(window), sourceName: item?.displayTitle ?? "")
                    }
                }
                .accessibilityIdentifier("dulcet.playlist.play")
            } secondary: {
                Button {
                    store.playReaderTracks(playable, shuffle: true, sourceKind: playlistSourceKind(window), sourceID: playlistSourceID(window), sourceName: item?.displayTitle ?? "")
                } label: {
                    Label(DulcetStrings.shuffle, systemImage: "shuffle")
                        .lineLimit(1)
                        .frame(maxWidth: .infinity)
                }
                .buttonStyle(.bordered)
                .disabled(playable.isEmpty)
                .accessibilityIdentifier("dulcet.playlist.shuffle")
            }
        }
#if os(macOS)
        .toolbar { pageToolbar(window) }
#endif
    }

#if !os(tvOS)
    /// Edit and More. On iOS the header is a `List` cell, and a cell scrolled out of the list
    /// takes its toolbar items with it, so the page carries them instead of the header.
    @ToolbarContentBuilder
    private func pageToolbar(_ window: DulcetLibraryWindow) -> some ToolbarContent {
        let item = window.header
        if let item {
            if item.isEditable, store.playlistEditor != nil {
                ToolbarItem(placement: .primaryAction) {
                    Button(DulcetStrings.playlistEdit) { editing = true }
                        .disabled(DulcetPlaylistEditContext(window: window, id: id) == nil)
                        .accessibilityIdentifier("dulcet.playlist.edit")
#if os(iOS)
                        // In the label colour the header's content gave it when it carried the toolbar.
                        .dulcetForeground(.primaryTextOnWindow)
#endif
                }
            }
            ToolbarItem(placement: .primaryAction) {
                // Built from a title: a Mac menu with a custom label reached accessibility as
                // no element at all. This one is a menu button named More.
                Menu(DulcetStrings.more, systemImage: "ellipsis") {
                    playlistMenuItems(item)
                }
                .accessibilityLabel(DulcetStrings.more)
                .accessibilityIdentifier("dulcet.playlist.more")
            }
        }
    }
#endif

#if !os(tvOS)
    /// The page's More: Rename and Delete for the person's own playlist, around the actions every
    /// playlist's menu offers. Delete here is the page's own, which closes the page once confirmed.
    @ViewBuilder
    private func playlistMenuItems(_ item: DulcetReaderItem) -> some View {
        let editable = item.isEditable && store.playlistEditor != nil
        if editable {
            Section {
                Button(DulcetStrings.playlistRenameEllipsis, systemImage: "character.cursor.ibeam") { renaming = true }
                    .accessibilityIdentifier("dulcet.playlist.rename")
            }
        }
        DulcetReaderItemMenuItems(item: item, offersPlaylistDeletion: false)
        if editable {
            Section {
                Button(DulcetStrings.playlistDelete, systemImage: "trash", role: .destructive) { confirmingDelete = true }
                    .accessibilityIdentifier("dulcet.playlist.delete")
            }
        }
    }
#endif

    private func detailLine(_ item: DulcetReaderItem?, window: DulcetLibraryWindow) -> String? {
        var parts: [String] = []
        if let count = item?.songCount ?? window.total { parts.append(DulcetStrings.readerCount(.tracks, count)) }
        if let comment = item?.comment, !comment.isEmpty { parts.append(comment) }
        return parts.isEmpty ? nil : parts.joined(separator: " \u{00B7} ")
    }

    /// A create in doubt the person said to decide later: its question, asked again from here.
    @ViewBuilder
    private var awaitingChoiceBanner: some View {
        if let editor = store.playlistEditor, editor.awaitingChoice[id.rawID] != nil {
            VStack(alignment: .leading, spacing: DulcetSpacing.xs) {
                Text(DulcetStrings.playlistAwaitingChoice)
                    .font(.callout)
                    .dulcetForeground(.primaryTextOnWindow)
                    .lineLimit(nil)
                Button(DulcetStrings.playlistChoose) { editor.reask(id.rawID) }
                    .buttonStyle(.bordered)
                    .accessibilityIdentifier("dulcet.playlist.choose")
            }
            .padding(DulcetSpacing.sm)
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(.regularMaterial, in: RoundedRectangle(cornerRadius: 12, style: .continuous))
            .accessibilityElement(children: .contain)
            .accessibilityIdentifier("dulcet.playlist.awaitingChoice")
        }
    }

    @ViewBuilder
    private var problemBanner: some View {
        if let editor = store.playlistEditor, let problem = editor.problems[id.rawID] {
            VStack(alignment: .leading, spacing: DulcetSpacing.xs) {
                Text(problem.message)
                    .font(.callout)
                    .dulcetForeground(.primaryTextOnWindow)
                    .lineLimit(nil)
                HStack(spacing: DulcetSpacing.xs) {
                    if let change = problem.withdrawable {
                        Button(DulcetStrings.playlistWithdraw) {
                            editor.perform(.withdraw(playlistID: id.rawID, change: change))
                        }
                        .buttonStyle(.bordered)
                        .accessibilityIdentifier("dulcet.playlist.withdraw")
                    }
                    Button(DulcetStrings.playlistDismiss) { editor.dismissProblem(id.rawID) }
                        .buttonStyle(.bordered)
                }
            }
            .padding(DulcetSpacing.sm)
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(.regularMaterial, in: RoundedRectangle(cornerRadius: 12, style: .continuous))
            .accessibilityElement(children: .contain)
            .accessibilityIdentifier("dulcet.playlist.problem")
        }
    }

    private func playlistSourceKind(_ window: DulcetLibraryWindow) -> DulcetPlaybackQueueSourceKind {
        window.header?.isLocalPlaylist == true ? .library : .playlist
    }

    /// A server playlist's Play shows Pause while it plays. A local playlist is played as the library
    /// (it has no source id of its own), so it cannot tell its music from other library plays.
    private func playTarget(_ window: DulcetLibraryWindow) -> DulcetPlayTarget? {
        window.header?.isLocalPlaylist == true ? nil : .collection(.playlist, id)
    }

    private func playlistSourceID(_ window: DulcetLibraryWindow) -> DulcetProviderItemID? {
        window.header?.isLocalPlaylist == true ? nil : id
    }

    // MARK: Tracks

    @ViewBuilder
    private func tracks(_ window: DulcetLibraryWindow, model: DulcetLibraryWindowModel) -> some View {
        LazyVStack(alignment: .leading, spacing: 0) {
            trackRows(window, model: model)
        }
    }

    @ViewBuilder
    private func trackRows(_ window: DulcetLibraryWindow, model: DulcetLibraryWindowModel) -> some View {
        let playable = window.playableTracks
        // By position: a playlist may hold one song twice (§18.6).
        ForEach(Array(window.items.enumerated()), id: \.offset) { index, item in
            DulcetReaderTrackRow(
                item: item, index: window.leadingOffset + index + 1,
                showsIndex: false,
                queueAddition: item.playableTrack.map {
                    DulcetQueueAddition(tracks: [$0], sourceKind: playlistSourceKind(window),
                                        sourceID: playlistSourceID(window), sourceDisplayName: window.header?.displayTitle ?? "")
                }
            ) { track in
                store.playReaderTracks(
                    playable,
                    startingAt: track,
                    sourceKind: playlistSourceKind(window),
                    sourceID: playlistSourceID(window),
                    sourceName: window.header?.displayTitle ?? ""
                )
            }
            .dulcetReaderRow(index, in: model)
            .dulcetSongRowSeparator(above: index > 0, songRowShowingIndex: false, artwork: true)
        }
    }

    // MARK: Editing

#if os(tvOS)
    private func editList(_ window: DulcetLibraryWindow, context: DulcetPlaylistEditContext) -> some View {
        EmptyView()
    }
#else
    private func editList(_ window: DulcetLibraryWindow, context: DulcetPlaylistEditContext) -> some View {
        List {
            ForEach(Array(window.items.enumerated()), id: \.offset) { index, item in
                HStack(spacing: DulcetSpacing.xs) {
#if os(macOS)
                    Button {
                        store.playlistEditor?.perform(context.remove(index))
                    } label: {
                        Image(systemName: "minus.circle.fill").symbolRenderingMode(.multicolor)
                    }
                    .buttonStyle(.plain)
                    .accessibilityLabel(DulcetStrings.playlistRemove)
                    .accessibilityIdentifier("dulcet.playlist.entry.remove")
#endif
                    VStack(alignment: .leading, spacing: 0) {
                        Text(item.displayTitle)
                            .lineLimit(dynamicTypeSize.isAccessibilitySize ? nil : 1)
                            .fixedSize(horizontal: false, vertical: true)
                        if let artist = item.artistName, !artist.isEmpty {
                            Text(artist)
                                .font(.caption)
                                .dulcetForeground(.secondaryTextOnWindow)
                                .lineLimit(dynamicTypeSize.isAccessibilitySize ? nil : 1)
                        }
                    }
                    Spacer(minLength: 0)
                }
                .accessibilityIdentifier("dulcet.playlist.entry")
                .accessibilityActions {
                    if index > 0 {
                        Button(DulcetStrings.playlistMoveUp) { store.playlistEditor?.perform(context.move(index, to: index - 1)) }
                    }
                    if index < window.items.count - 1 {
                        Button(DulcetStrings.playlistMoveDown) { store.playlistEditor?.perform(context.move(index, to: index + 1)) }
                    }
                    Button(DulcetStrings.playlistRemove) { store.playlistEditor?.perform(context.remove(index)) }
                }
            }
            .onMove { source, destination in
                guard let edit = context.move(source, toOffset: destination) else { return }
                store.playlistEditor?.perform(edit)
            }
            .onDelete { offsets in
                store.playlistEditor?.perform(context.remove(offsets))
            }
        }
#if os(iOS)
        .environment(\.editMode, .constant(.active))
#endif
        .navigationTitle(window.header?.displayTitle ?? DulcetStrings.playlistUntitledName)
        .toolbar {
            ToolbarItem(placement: .confirmationAction) {
                Button(DulcetStrings.playlistDone) { editing = false }
                    .accessibilityIdentifier("dulcet.playlist.done")
            }
        }
    }
#endif
}

/// The view a positional edit is made against: the entry ids of the publication on screen, whole
/// and in order (§18.6). Nil when the list on screen cannot be edited by position: not the
/// person's playlist, its entries not all present, or not a list that starts at the top.
struct DulcetPlaylistEditContext: Equatable {
    let playlistID: String
    let expected: [String]

    init?(window: DulcetLibraryWindow, id: DulcetProviderItemID) {
        guard let header = window.header, header.isEditable,
              window.itemsState == .present, window.leadingOffset == 0,
              window.coverage != .open else { return nil }
        switch window.freshness {
        case .live, .cached: break
        case .loading, .unavailable: return nil
        }
        playlistID = id.rawID
        expected = window.items.map(\.id.rawID)
    }

    func remove(_ index: Int) -> DulcetPlaylistEdit {
        .remove(playlistID: playlistID, indices: [index], expected: expected)
    }

    func remove(_ offsets: IndexSet) -> DulcetPlaylistEdit {
        .remove(playlistID: playlistID, indices: Array(offsets), expected: expected)
    }

    func move(_ from: Int, to: Int) -> DulcetPlaylistEdit {
        .move(playlistID: playlistID, from: from, to: to, expected: expected)
    }

    /// `List.onMove` names the gap to drop into, counted before the row is taken out; the core
    /// names the row's final position. One row moves at a time.
    func move(_ source: IndexSet, toOffset destination: Int) -> DulcetPlaylistEdit? {
        guard source.count == 1, let from = source.first else { return nil }
        let to = destination > from ? destination - 1 : destination
        guard to != from else { return nil }
        return move(from, to: to)
    }
}

// MARK: - Context menus

/// Delete for the person's own playlist -- confirmed first, by the same dialog as its page -- and
/// Add to Playlist for an album. Rename is on the playlist's page. Nothing for another user's
/// playlist, which has no edit affordance at all (§18.6).
struct DulcetPlaylistItemMenuItems: View {
    @Environment(DulcetPresentationStore.self) private var store
    let item: DulcetReaderItem
    var offersDeletion = true

    var body: some View {
        if let editor = store.playlistEditor, store.isSessionItem(item.id) {
            switch item.kind {
            case .album:
                Button(DulcetStrings.playlistAddToEllipsis, systemImage: "text.badge.plus") {
                    editor.addition = .album(item.id.rawID, title: item.displayTitle)
                }
            case .playlist where item.isEditable && offersDeletion:
                Button(DulcetStrings.playlistDelete, systemImage: "trash", role: .destructive) {
                    // Recorded only once the person confirms (dulcetPlaylistSheets).
                    editor.requestDeletion(of: item.id.rawID, named: item.displayTitle)
                }
                .accessibilityIdentifier("dulcet.playlist.delete")
            default:
                EmptyView()
            }
        }
    }
}

/// Download or Remove Download for a playlist (spec §14.5.2): its songs stay on this device and
/// follow its later edits. Removing keeps any song downloaded on its own.
struct DulcetPlaylistDownloadMenuItem: View {
    @Environment(DulcetPresentationStore.self) private var store
    let item: DulcetReaderItem

    var body: some View {
        if item.kind == .playlist, store.offersPlaylistDownloads {
            if store.playlistDownload(for: item.id)?.wanted == true {
                Button(DulcetStrings.removeDownload, systemImage: "minus.circle") {
                    store.setPlaylistDownload(item.id, wanted: false)
                }
                .accessibilityIdentifier("dulcet.playlist.removeDownload")
            } else {
                Button(DulcetStrings.download, systemImage: "arrow.down.circle") {
                    store.setPlaylistDownload(item.id, wanted: true)
                }
                .accessibilityIdentifier("dulcet.playlist.download")
            }
        }
    }
}

/// A downloaded playlist's state under its header: how far its download has come, or that it is
/// all here. Nothing while the playlist is not downloaded.
struct DulcetPlaylistDownloadStatus: View {
    @Environment(DulcetPresentationStore.self) private var store
    let id: DulcetProviderItemID

    var body: some View {
        if let download = store.playlistDownload(for: id), download.wanted, download.songCount > 0 || !download.songsKnown {
            Group {
                if download.isComplete {
                    Label(DulcetStrings.downloaded, systemImage: "arrow.down.circle.fill")
                } else if download.pendingCount > 0 || !download.songsKnown {
                    Label {
                        Text(download.songsKnown
                             ? DulcetStrings.playlistDownloading(download.downloadedCount, of: download.songCount)
                             : DulcetStrings.downloading)
                    } icon: {
                        DulcetPlaylistDownloadRing(fraction: download.songCount == 0 ? 0
                            : Double(download.downloadedCount) / Double(download.songCount))
                    }
                } else {
                    // Wanted, not all here, nothing moving: waiting for a connection.
                    Label(DulcetStrings.playlistDownloadWaiting, systemImage: "arrow.down.circle.dotted")
                }
            }
            .font(.caption)
            .dulcetForeground(.secondaryTextOnWindow)
            .accessibilityElement(children: .combine)
            .accessibilityIdentifier("dulcet.playlist.downloadStatus")
        }
    }
}

private struct DulcetPlaylistDownloadRing: View {
    let fraction: Double
    var body: some View {
        ZStack {
            Circle().stroke(.tertiary, lineWidth: 2)
            Circle().trim(from: 0, to: max(0.04, min(1, fraction)))
                .stroke(.tint, style: StrokeStyle(lineWidth: 2, lineCap: .round))
                .rotationEffect(.degrees(-90))
        }
        // The same glyph size as a song row's download badge.
        .frame(width: DulcetMetrics.songRowStatusGlyphSize, height: DulcetMetrics.songRowStatusGlyphSize)
        .animation(.easeInOut(duration: 0.3), value: fraction)
    }
}

/// "Add to Playlist…" for one track.
struct DulcetAddTrackToPlaylistMenuItem: View {
    @Environment(DulcetPresentationStore.self) private var store
    let track: DulcetTrack

    var body: some View {
        if let editor = store.playlistEditor, store.isSessionItem(track.id) {
            Button(DulcetStrings.playlistAddToEllipsis, systemImage: "text.badge.plus") {
                editor.addition = .songs([track.id.rawID], title: track.title)
            }
            .accessibilityIdentifier("dulcet.track.addToPlaylist")
        }
    }
}

// MARK: - The chooser and the questions

extension View {
    /// The Add to Playlist chooser and a create-in-doubt's question, wherever they are asked from.
    func dulcetPlaylistSheets(store: DulcetPresentationStore) -> some View {
        modifier(DulcetPlaylistSheets(store: store))
    }

    /// The one confirmation every playlist deletion goes through: its page's and its context menu's.
    /// An alert, not a confirmation dialog: the context menu's is asked from the root, where a
    /// confirmation dialog on macOS traps on the store's environment (the window-title test).
    func dulcetPlaylistDeleteConfirmation(name: String?, isPresented: Binding<Bool>, onConfirm: @escaping () -> Void) -> some View {
        alert(DulcetStrings.playlistDeleteQuestion(name), isPresented: isPresented) {
            Button(DulcetStrings.playlistDelete, role: .destructive, action: onConfirm)
                .accessibilityIdentifier("dulcet.playlist.delete.confirm")
            Button(DulcetStrings.playlistCancel, role: .cancel) {}
        } message: {
            Text(DulcetStrings.playlistDeleteConfirm)
        }
    }

    /// A name prompt: New Playlist, Rename.
    func dulcetPlaylistNamePrompt(
        isPresented: Binding<Bool>,
        title: String,
        initial: String,
        confirm: String,
        onSubmit: @escaping (String) -> Void
    ) -> some View {
        modifier(DulcetPlaylistNamePrompt(isPresented: isPresented, title: title, initial: initial, confirm: confirm, onSubmit: onSubmit))
    }
}

private struct DulcetPlaylistNamePrompt: ViewModifier {
    @Binding var isPresented: Bool
    let title: String
    let initial: String
    let confirm: String
    let onSubmit: (String) -> Void
    @State private var name = ""

    func body(content: Content) -> some View {
        content
            .alert(title, isPresented: $isPresented) {
                TextField(DulcetStrings.playlistName, text: $name)
                    .accessibilityIdentifier("dulcet.playlist.name")
                Button(confirm) {
                    let trimmed = name.trimmingCharacters(in: .whitespacesAndNewlines)
                    guard !trimmed.isEmpty else { return }
                    onSubmit(trimmed)
                }
                .accessibilityIdentifier("dulcet.playlist.name.confirm")
                Button(DulcetStrings.playlistCancel, role: .cancel) {}
            }
            .onChange(of: isPresented) { _, presented in
                if presented { name = initial }
            }
    }
}

private struct DulcetPlaylistSheets: ViewModifier {
    @Bindable var store: DulcetPresentationStore

    func body(content: Content) -> some View {
        content
            .dulcetPlaylistAdditionPresentation(item: Binding(
                get: { store.librarySession?.playlists?.addition },
                set: { store.librarySession?.playlists?.addition = $0 }
            )) { addition in
                DulcetAddToPlaylistSheet(addition: addition)
                    .environment(store)
            }
            .dulcetPlaylistDeleteConfirmation(name: store.librarySession?.playlists?.deletionNameToConfirm, isPresented: Binding(
                get: { store.librarySession?.playlists?.deletionToConfirm != nil },
                set: { if !$0 { store.librarySession?.playlists?.cancelDeletion() } }
            )) {
                store.librarySession?.playlists?.confirmDeletion()
            }
            .alert(
                questionTitle,
                isPresented: Binding(
                    get: { store.librarySession?.playlists?.question != nil },
                    // Every button answers or dismisses its own question; dismissing here too
                    // would drop the next queued one, which is already current by then.
                    set: { _ in }
                ),
                presenting: store.librarySession?.playlists?.question
            ) { question in
                ForEach(DulcetPlaylistPresentation.choices(for: question), id: \.self) { choice in
                    button(for: choice, of: question)
                }
            } message: { question in
                Text(DulcetPlaylistPresentation.message(for: question))
            }
    }

    @ViewBuilder
    private func button(for choice: DulcetPlaylistQuestionChoice, of question: DulcetPlaylistQuestion) -> some View {
        let editor = store.librarySession?.playlists
        switch choice {
        case let .adopt(candidate):
            Button(DulcetStrings.playlistItIsMine) { editor?.answer(question, choosing: candidate) }
        case let .delete(candidate):
            Button(DulcetStrings.playlistDeleteFromServer, role: .destructive) { editor?.answer(question, choosing: candidate) }
        case .createAgain:
            Button(DulcetStrings.playlistNoneOfThese) { editor?.answer(question, choosing: nil) }
        case .decideLater:
            Button(DulcetStrings.playlistDecideLater, role: .cancel) { editor?.dismissQuestion(question) }
        case .keep:
            Button(DulcetStrings.playlistKeepIt, role: .cancel) { editor?.dismissQuestion(question) }
        }
    }

    private var questionTitle: String {
        switch store.librarySession?.playlists?.question?.kind {
        case .deleteMaybeCreated: DulcetStrings.playlistMaybeCreatedTitle
        default: DulcetStrings.playlistWhichIsYoursTitle
        }
    }
}

private extension View {
    /// A sheet, except on Apple TV: the system's sheet there is a small card that clips a focused
    /// row against its own edges and leaves the page it covers legible behind it. Menu closes it.
    @ViewBuilder
    func dulcetPlaylistAdditionPresentation<Item: Identifiable, Sheet: View>(
        item: Binding<Item?>, @ViewBuilder content: @escaping (Item) -> Sheet
    ) -> some View {
#if os(tvOS)
        fullScreenCover(item: item, content: content)
#else
        sheet(item: item, content: content)
#endif
    }
}

/// The playlists the person can add to, and New Playlist, for one addition.
private struct DulcetAddToPlaylistSheet: View {
    @Environment(DulcetPresentationStore.self) private var store
    @Environment(\.dismiss) private var dismiss
    @State private var naming = false
    let addition: DulcetPlaylistAddition

    var body: some View {
#if os(tvOS)
        NavigationStack {
            DulcetReaderScreen(query: .playlists, showsDestinationStatus: true) { model in
                let editable = (model.window?.items ?? []).filter(\.isEditable)
                DulcetTVFormPage {
                    DulcetTVFormSection {
                        // A page of its own rather than the name alert: the TV's keyboard is a
                        // screen of its own, entered from a field the remote can reach.
                        NavigationLink {
                            DulcetNewPlaylistPage(addition: addition)
                        } label: {
                            DulcetTVFormRowLabel(title: DulcetStrings.playlistNew, systemImage: "plus", trailing: .disclosure)
                        }
                        .accessibilityIdentifier("dulcet.addToPlaylist.new")
                        ForEach(editable) { item in
                            Button {
                                store.playlistEditor?.add(addition, to: item.id.rawID)
                            } label: {
                                HStack(spacing: DulcetSpacing.sm) {
                                    DulcetArtworkView(artwork: item.artwork, size: 72)
                                    VStack(alignment: .leading, spacing: 0) {
                                        Text(item.displayTitle)
                                            .font(.callout)
                                            .dulcetForeground(.primaryTextOnWindow)
                                            .lineLimit(2)
                                        if let count = item.songCount {
                                            Text(DulcetStrings.readerCount(.tracks, count))
                                                .font(.caption)
                                                .dulcetForeground(.secondaryTextOnWindow)
                                        }
                                    }
                                }
                                .padding(.vertical, DulcetSpacing.xxs)
                                .frame(maxWidth: .infinity, alignment: .leading)
                                .contentShape(Rectangle())
                            }
                            .accessibilityIdentifier("dulcet.addToPlaylist.playlist")
                        }
                    }
                    if let window = model.window, editable.isEmpty, window.freshness != .loading {
                        Text(DulcetStrings.playlistNoneEditable)
                            .font(.callout)
                            .dulcetForeground(.secondaryTextOnWindow)
                            .padding(.horizontal, DulcetTVFocusedRowButtonStyle.plateInset)
                    }
                }
                .dulcetNavigationTitle(DulcetStrings.playlistAddTo, keepsTVNavigationBar: true)
            }
        }
#else
        NavigationStack {
            DulcetReaderScreen(query: .playlists, showsDestinationStatus: true) { model in
                List {
                    Button(DulcetStrings.playlistNewEllipsis, systemImage: "plus") { naming = true }
                        .accessibilityIdentifier("dulcet.addToPlaylist.new")
                    let editable = (model.window?.items ?? []).filter(\.isEditable)
                    if let window = model.window, editable.isEmpty, window.freshness != .loading {
                        Text(DulcetStrings.playlistNoneEditable).dulcetForeground(.secondaryTextOnWindow)
                    }
                    ForEach(editable) { item in
                        Button {
                            store.playlistEditor?.add(addition, to: item.id.rawID)
                        } label: {
                            HStack(spacing: DulcetSpacing.sm) {
                                DulcetArtworkView(artwork: item.artwork, size: DulcetMetrics.denseRowArtworkSize)
                                VStack(alignment: .leading, spacing: DulcetSpacing.xxs) {
                                    Text(item.displayTitle)
                                        .dulcetForeground(.primaryTextOnWindow)
                                        .lineLimit(nil)
                                    if let count = item.songCount {
                                        Text(DulcetStrings.readerCount(.tracks, count)).font(.caption).dulcetForeground(.secondaryTextOnWindow)
                                    }
                                }
                            }
                            .padding(.vertical, DulcetSpacing.xxs)
                            .frame(maxWidth: .infinity, alignment: .leading)
                            .contentShape(Rectangle())
                        }
#if os(macOS)
                        .buttonStyle(.plain)
#endif
                        .accessibilityIdentifier("dulcet.addToPlaylist.playlist")
                    }
                }
            }
            .navigationTitle(DulcetStrings.playlistAddTo)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button(DulcetStrings.playlistCancel) {
                        store.playlistEditor?.addition = nil
                        dismiss()
                    }
                }
            }
        }
#if os(macOS)
        .frame(minWidth: 360, minHeight: 420)
#endif
        .dulcetPlaylistNamePrompt(isPresented: $naming, title: DulcetStrings.playlistNew, initial: "", confirm: DulcetStrings.playlistCreate) { name in
            store.playlistEditor?.createPlaylist(named: name, with: addition)
        }
#endif
    }
}

#if os(tvOS)
/// New Playlist on the TV: a name, then Create, which makes the playlist holding the addition and
/// closes the chooser.
private struct DulcetNewPlaylistPage: View {
    @Environment(DulcetPresentationStore.self) private var store
    let addition: DulcetPlaylistAddition
    @State private var name = ""

    var body: some View {
        let trimmed = name.trimmingCharacters(in: .whitespacesAndNewlines)
        DulcetTVFormPage {
            VStack(alignment: .leading, spacing: DulcetSpacing.xs) {
                Text(DulcetStrings.playlistName)
                    .font(.headline)
                    .dulcetForeground(.secondaryTextOnWindow)
                    .padding(.horizontal, DulcetTVFocusedRowButtonStyle.plateInset)
                DulcetTVTextField(label: DulcetStrings.playlistName, text: $name)
                    .accessibilityIdentifier("dulcet.playlist.name")
            }
            Button {
                store.playlistEditor?.createPlaylist(named: trimmed, with: addition)
            } label: {
                Text(DulcetStrings.playlistCreate)
                    .frame(minWidth: 240)
            }
                .buttonStyle(DulcetTVActionButtonStyle())
                .disabled(trimmed.isEmpty)
                .accessibilityIdentifier("dulcet.playlist.name.confirm")
                .frame(maxWidth: .infinity)
        }
        .dulcetNavigationTitle(DulcetStrings.playlistNew, keepsTVNavigationBar: true)
    }
}
#endif
#endif
