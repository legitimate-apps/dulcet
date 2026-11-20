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
#if os(tvOS)
                if !isEmptyAndUnfiltered(model.window) {
                    DulcetLibraryCategoryControls(category: "playlists", offersArtist: false)
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
#if !os(tvOS)
            .dulcetLibraryCategoryToolbar("playlists", offersArtist: false)
            .toolbar {
                if store.playlistEditor != nil {
                    ToolbarItem(placement: .primaryAction) {
                        Button { naming = true } label: { Image(systemName: "plus") }
                            .accessibilityLabel(DulcetStrings.playlistNew)
                            .accessibilityIdentifier("dulcet.playlists.new")
                    }
                }
            }
#endif
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
#if os(iOS)
    @State private var editMode = EditMode.inactive
    private var editing: Bool { editMode.isEditing }
#elseif os(macOS)
    @State private var editing = false
#endif
#if !os(tvOS)
    @State private var titleAboveViewport = false
#endif
    @State private var renaming = false
    @State private var confirmingDelete = false

    var body: some View {
        DulcetReaderScreen(query: .playlist(rawID: id.rawID), showsDestinationStatus: true) { model in
            page(model)
                .dulcetPlaylistNamePrompt(
                isPresented: $renaming,
                title: DulcetStrings.playlistRenameTitle,
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
        playlistList(model)
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
                store.playReaderTracks(playable, sourceKind: playlistSourceKind(window), sourceID: playlistSourceID(window), sourceName: item?.displayTitle ?? "")
            } label: { Label(DulcetStrings.play, systemImage: "play.fill") }
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

#if os(tvOS)
    private func stackedPage(_ model: DulcetLibraryWindowModel) -> some View {
        DulcetReaderPage(title: model.window?.header?.displayTitle ?? "", keepsTVNavigationBar: false, titleInHeader: true) { width in
            if let window = model.window {
                // Gone from the server, or never read and offline, with nothing kept: the core has
                // no playlist to name, so there is no title to show and nothing to play -- an empty header and
                // two disabled buttons would only push the statement down. What the page says
                // about a failed edit comes first, then the statement in place of the tracks.
                if window.header != nil || !window.items.isEmpty || !Self.isUnavailable(window) {
                    header(window, width: width)
                }
                awaitingChoiceBanner
                problemBanner
                DulcetReaderListBody(model: model, subject: window.header == nil ? .list : .albumTracks) { window in
                    tracks(window, model: model)
                }
            } else {
                ProgressView().frame(maxWidth: .infinity, minHeight: 120)
            }
        }
    }

#endif

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
        } actions: {
            DulcetEqualWidthActions {
                DulcetHeaderPlayAction(isEnabled: !playable.isEmpty, fillsWidth: true) {
                    store.playReaderTracks(playable, sourceKind: playlistSourceKind(window), sourceID: playlistSourceID(window), sourceName: item?.displayTitle ?? "")
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
    }

#if !os(tvOS)
    /// Use the same list, header and track rows in both modes. Only editable, complete
    /// publications can supply positional edit actions.
    private func playlistList(_ model: DulcetLibraryWindowModel) -> some View {
        let window = model.window
        let edit = window.flatMap { DulcetPlaylistEditContext(window: $0, id: id) }
        return GeometryReader { geometry in
            let inset = DulcetMetrics.contentMargin(forWidth: geometry.size.width)
            List {
                if let window {
                    if window.header != nil || !window.items.isEmpty || !Self.isUnavailable(window) {
                        header(window, width: geometry.size.width)
                            .padding(.vertical, DulcetSpacing.md)
                            .playlistListRow(inset: inset)
                    }
                    awaitingChoiceBanner.playlistListRow(inset: inset)
                    problemBanner.playlistListRow(inset: inset)
                    if window.items.isEmpty {
                        DulcetReaderListBody(model: model, subject: window.header == nil ? .list : .albumTracks) { _ in
                            EmptyView()
                        }
                        .playlistListRow(inset: inset)
                    } else {
                        editableTracks(window, model: model, edit: editing ? edit : nil, inset: inset)
                        if window.freshness == .loading || window.itemsState == .loading {
                            ProgressView()
                                .frame(maxWidth: .infinity)
                                .padding(.vertical, DulcetSpacing.sm)
                                .accessibilityLabel(DulcetStrings.loadingMore)
                                .playlistListRow(inset: inset)
                        }
                    }
                    if DulcetReaderListBody<EmptyView>.offersLoadMore(window, displayLimit: nil) {
                        Button(DulcetStrings.libraryLoadMore) { model.loadMore() }
                            .accessibilityIdentifier("dulcet.library.loadMore")
                            .playlistListRow(inset: inset)
                    }
                } else {
                    ProgressView()
                        .frame(maxWidth: .infinity)
                        .padding(.vertical, DulcetSpacing.lg)
                        .playlistListRow(inset: inset)
                }
            }
            .listStyle(.plain)
            .buttonStyle(.borderless)
            .scrollContentBackground(.hidden)
            .environment(\.defaultMinListRowHeight, 0)
            .modifier(DulcetSystemScrollChrome())
#if os(iOS)
            .environment(\.editMode, $editMode)
#endif
        }
        .background(Color.dulcetWindow)
        .dulcetForeground(.primaryTextOnWindow)
        .coordinateSpace(name: "dulcet.detail.scroll")
        .onPreferenceChange(DulcetDetailTitleBottom.self) { titleAboveViewport = $0 < 0 }
        .dulcetDetailNavigationTitle(window?.header?.displayTitle ?? "", visible: titleAboveViewport)
        .dulcetReaderFreshness(window?.freshness)
        .toolbar {
            if let item = window?.header {
                if item.isEditable, store.playlistEditor != nil {
                    ToolbarItem(placement: .primaryAction) {
                        Button(editing ? DulcetStrings.playlistDone : DulcetStrings.playlistEdit) {
                            setEditing(!editing)
                        }
                        .disabled(!editing && edit == nil)
                        .accessibilityIdentifier(editing ? "dulcet.playlist.done" : "dulcet.playlist.edit")
                    }
                }
                ToolbarItem(placement: .primaryAction) {
                    Menu(DulcetStrings.more, systemImage: "ellipsis") { playlistMenuItems(item) }
                        .accessibilityLabel(DulcetStrings.more)
                        .accessibilityIdentifier("dulcet.playlist.more")
                }
            }
        }
        .onChange(of: edit != nil) { _, canEdit in
            if editing && !canEdit { setEditing(false) }
        }
    }

    private func setEditing(_ value: Bool) {
#if os(iOS)
        withAnimation { editMode = value ? .active : .inactive }
#else
        editing = value
#endif
    }

    /// A playlist may hold the same song twice, so use entry positions and the full edit context.
    private func editableTracks(_ window: DulcetLibraryWindow, model: DulcetLibraryWindowModel,
                                edit: DulcetPlaylistEditContext?, inset: CGFloat) -> some View {
        ForEach(Array(window.items.enumerated()), id: \.offset) { index, item in
            HStack(spacing: DulcetSpacing.xs) {
#if os(macOS)
                if let edit {
                    Button {
                        store.playlistEditor?.perform(edit.remove(index))
                    } label: {
                        Image(systemName: "minus.circle.fill").symbolRenderingMode(.multicolor)
                    }
                    .buttonStyle(.plain)
                    .accessibilityLabel(DulcetStrings.playlistRemove)
                    .accessibilityIdentifier("dulcet.playlist.entry.remove")
                }
#endif
                if let edit {
                    trackRow(item, at: index, in: window, isEditing: true)
                        .accessibilityRepresentation {
                            Text(item.displayTitle)
                                .accessibilityIdentifier("dulcet.playlist.entry")
                                .accessibilityActions {
                                    if index > 0 {
                                        Button(DulcetStrings.playlistMoveUp) { store.playlistEditor?.perform(edit.move(index, to: index - 1)) }
                                    }
                                    if index < window.items.count - 1 {
                                        Button(DulcetStrings.playlistMoveDown) { store.playlistEditor?.perform(edit.move(index, to: index + 1)) }
                                    }
                                    Button(DulcetStrings.playlistRemove) { store.playlistEditor?.perform(edit.remove(index)) }
                                }
                        }
                } else {
                    trackRow(item, at: index, in: window)
                }
            }
            .dulcetSongRowSeparator(above: index > 0, songRowShowingIndex: false, artwork: true)
            .dulcetReaderRow(index, in: model)
            .playlistListRow(inset: inset)
        }
        .onMove { source, destination in
            guard let change = edit?.move(source, toOffset: destination) else { return }
            store.playlistEditor?.perform(change)
        }
        .onDelete { offsets in
            guard let edit else { return }
            store.playlistEditor?.perform(edit.remove(offsets))
        }
        .moveDisabled(edit == nil)
        .deleteDisabled(edit == nil)
    }

    /// The page's More ends with its own Rename and Delete. Its Delete also closes the page.
    @ViewBuilder
    private func playlistMenuItems(_ item: DulcetReaderItem) -> some View {
        DulcetReaderItemMenuItems(item: item, offersPlaylistDeletion: false)
        if item.isEditable, store.playlistEditor != nil {
            Section {
                Button(DulcetStrings.playlistRenameEllipsis, systemImage: "character.cursor.ibeam") { renaming = true }
                    .accessibilityIdentifier("dulcet.playlist.rename")
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

    private func playlistSourceID(_ window: DulcetLibraryWindow) -> DulcetProviderItemID? {
        window.header?.isLocalPlaylist == true ? nil : id
    }

    // MARK: Tracks

#if os(tvOS)
    private func tracks(_ window: DulcetLibraryWindow, model: DulcetLibraryWindowModel) -> some View {
        LazyVStack(alignment: .leading, spacing: 0) {
            ForEach(Array(window.items.enumerated()), id: \.offset) { index, item in
                trackRow(item, at: index, in: window)
                    .dulcetReaderRow(index, in: model)
                    .dulcetSongRowSeparator(above: index > 0, songRowShowingIndex: false, artwork: true)
            }
        }
    }
#endif

    private func trackRow(_ item: DulcetReaderItem, at index: Int, in window: DulcetLibraryWindow,
                          isEditing: Bool = false) -> some View {
        DulcetReaderTrackRow(
            item: item, index: window.leadingOffset + index + 1,
            showsIndex: false,
            queueAddition: item.playableTrack.map {
                DulcetQueueAddition(tracks: [$0], sourceKind: playlistSourceKind(window),
                                    sourceID: playlistSourceID(window), sourceDisplayName: window.header?.displayTitle ?? "")
            },
            isEditing: isEditing
        ) { track in
            store.playReaderTracks(
                window.playableTracks,
                startingAt: track,
                sourceKind: playlistSourceKind(window),
                sourceID: playlistSourceID(window),
                sourceName: window.header?.displayTitle ?? ""
            )
        }
    }
}

#if !os(tvOS)
private extension View {
    func playlistListRow(inset: CGFloat) -> some View {
        listRowInsets(EdgeInsets(top: 0, leading: inset, bottom: 0, trailing: inset))
            .listRowSeparator(.hidden)
            .listRowBackground(Color.clear)
    }
}
#endif

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
        alert(DulcetStrings.playlistDeleteTitle, isPresented: isPresented) {
            Button(DulcetStrings.playlistDelete, role: .destructive, action: onConfirm)
                .accessibilityIdentifier("dulcet.playlist.delete.confirm")
            Button(DulcetStrings.playlistCancel, role: .cancel) {}
        } message: {
            Text(DulcetStrings.playlistDeleteConsequence(name))
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

    private var trimmedName: String { name.trimmingCharacters(in: .whitespacesAndNewlines) }

    func body(content: Content) -> some View {
        content
            .alert(title, isPresented: $isPresented) {
                TextField(DulcetStrings.playlistName, text: $name)
                    .accessibilityIdentifier("dulcet.playlist.name")
                Button(confirm) {
                    guard !trimmedName.isEmpty else { return }
                    onSubmit(trimmedName)
                }
                .disabled(trimmedName.isEmpty)
#if !os(tvOS)
                .keyboardShortcut(.defaultAction)
#endif
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
struct DulcetAddToPlaylistSheet: View {
    @Environment(DulcetPresentationStore.self) private var store
    @Environment(\.dismiss) private var dismiss
    @Environment(\.dynamicTypeSize) private var dynamicTypeSize
    @ScaledMetric(relativeTo: .body) private var scaledArtwork = DulcetMetrics.rowArtworkSize
    @State private var naming = false
    let addition: DulcetPlaylistAddition

    private var artworkSize: CGFloat {
#if os(tvOS)
        DulcetMetrics.rowArtworkSize
#else
        Self.artworkSize(scaled: scaledArtwork)
#endif
    }
    private var iconColumnWidth: CGFloat { Self.iconColumnWidth(artworkSize: artworkSize) }

    static func iconColumnWidth(artworkSize: CGFloat) -> CGFloat {
        max(artworkSize, DulcetMetrics.minimumTouchTarget)
    }

    static func artworkSize(scaled: CGFloat) -> CGFloat {
        min(scaled, DulcetMetrics.stackedRowArtworkSize)
    }

    var body: some View {
#if os(tvOS)
        NavigationStack {
            DulcetReaderScreen(query: .playlists, showsDestinationStatus: true) { model in
                let editable = (model.window?.items ?? []).filter(\.isEditable)
                DulcetTVFormPage {
                    VStack(alignment: .leading, spacing: DulcetSpacing.xs) {
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
                                playlistChoice(item)
                            }
                            .accessibilityIdentifier("dulcet.addToPlaylist.playlist")
                        }
                    }
                    .buttonStyle(DulcetTVFocusedRowButtonStyle())
                    if let window = model.window, editable.isEmpty, window.freshness != .loading {
                        Text(DulcetStrings.playlistNoneEditable)
                            .font(.callout)
                            .dulcetForeground(.secondaryTextOnWindow)
                    }
                }
                .dulcetNavigationTitle(DulcetStrings.playlistAddTo, keepsTVNavigationBar: true)
            }
        }
#else
        NavigationStack {
            DulcetReaderScreen(query: .playlists, showsDestinationStatus: true) { model in
                List {
                    Button { naming = true } label: {
                        Label {
                            Text(DulcetStrings.playlistNewEllipsis)
                                .fixedSize(horizontal: false, vertical: true)
                        } icon: {
                            Image(systemName: "plus")
                                .frame(width: DulcetMetrics.minimumTouchTarget, height: DulcetMetrics.minimumTouchTarget)
                                .background(.quaternary, in: RoundedRectangle(cornerRadius: DulcetMetrics.artworkCornerRadius(forSize: DulcetMetrics.minimumTouchTarget)))
                                .frame(width: iconColumnWidth, alignment: .leading)
                        }
                        .labelStyle(DulcetPlaylistChooserLabelStyle(baseline: dynamicTypeSize.isAccessibilitySize))
                        .frame(maxWidth: .infinity, alignment: .leading)
                        .contentShape(Rectangle())
                    }
                    .accessibilityIdentifier("dulcet.addToPlaylist.new")
                    let editable = (model.window?.items ?? []).filter(\.isEditable)
                    if let window = model.window, editable.isEmpty, window.freshness != .loading {
                        Text(DulcetStrings.playlistNoneEditable).dulcetForeground(.secondaryTextOnWindow)
                    }
                    ForEach(editable) { item in
                        Button {
                            store.playlistEditor?.add(addition, to: item.id.rawID)
                        } label: {
                            playlistChoice(item)
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

    private func playlistChoice(_ item: DulcetReaderItem) -> some View {
        Label {
            VStack(alignment: .leading, spacing: DulcetSpacing.xxs) {
                Text(item.displayTitle)
                    .dulcetForeground(.primaryTextOnWindow)
                    .fixedSize(horizontal: false, vertical: true)
                if let count = item.songCount {
                    Text(DulcetStrings.readerCount(.tracks, count))
                        .font(.caption)
                        .dulcetForeground(.secondaryTextOnWindow)
                }
            }
        } icon: {
            DulcetArtworkView(artwork: item.artwork, size: artworkSize)
                .frame(width: iconColumnWidth, alignment: .leading)
        }
        .labelStyle(DulcetPlaylistChooserLabelStyle(baseline: dynamicTypeSize.isAccessibilitySize))
        .padding(.vertical, DulcetSpacing.xxs)
        .frame(maxWidth: .infinity, minHeight: DulcetMetrics.rowMinHeight(artwork: true), alignment: .leading)
        .contentShape(Rectangle())
    }
}

private struct DulcetPlaylistChooserLabelStyle: LabelStyle {
    let baseline: Bool

    func makeBody(configuration: Configuration) -> some View {
        HStack(alignment: baseline ? .firstTextBaseline : .center, spacing: DulcetSpacing.sm) {
            configuration.icon
                .alignmentGuide(.firstTextBaseline) { $0[VerticalAlignment.center] }
                .accessibilityHidden(true)
            configuration.title
        }
    }
}

#if os(tvOS)
/// New Playlist on the TV: a name, then Create, which makes the playlist holding the addition and
/// closes the chooser.
private struct DulcetNewPlaylistPage: View {
    @Environment(DulcetPresentationStore.self) private var store
    let addition: DulcetPlaylistAddition
    @State private var name = ""
    @FocusState private var createFocused: Bool

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
                    .onSubmit { if !trimmed.isEmpty { createFocused = true } }
            }
            Button {
                store.playlistEditor?.createPlaylist(named: trimmed, with: addition)
            } label: {
                Text(DulcetStrings.playlistCreate)
            }
            .buttonStyle(DulcetTVActionButtonStyle())
            .disabled(trimmed.isEmpty)
            .focused($createFocused)
            .accessibilityIdentifier("dulcet.playlist.name.confirm")
            .padding(.leading, DulcetTVFocusedRowButtonStyle.plateInset)
        }
        .onChange(of: trimmed.isEmpty) { _, empty in
            if !empty { createFocused = true }
        }
        .dulcetNavigationTitle(DulcetStrings.playlistNew, keepsTVNavigationBar: true)
    }
}
#endif
#endif
