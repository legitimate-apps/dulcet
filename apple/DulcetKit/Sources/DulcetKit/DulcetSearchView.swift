#if os(macOS) || os(iOS) || os(tvOS)
import SwiftUI

struct DulcetSearchView: View {
#if os(macOS)
    @State private var selectedResultID: DulcetSearchResult.ID?
#endif
#if os(iOS)
    @FocusState private var searchFieldFocused: Bool
    @Environment(DulcetPresentationStore.self) private var store
#endif
    let snapshot: DulcetSnapshot
    @Binding var searchQuery: String
    let onLoadMore: (DulcetSearchResultKind) -> Void
    let onRetry: () -> Void
    let onActivateResult: (DulcetSearchResult.ID) -> Void
    /// A search command asked for the field's focus; `onFocusRequestHandled` clears the request.
    var focusRequested = false
    var onFocusRequestHandled: () -> Void = {}
    /// The reader's search, when the reader draws this screen (§16.15): rows from the first
    /// character, labelled with where they came from.
    var reader: DulcetReaderSearchContent?

    private enum Phase {
        case idle
        case loading
        case results
        case empty
        case error
        /// The reader's server search failed and nothing on this device matches (§16.15).
        case readerFailed(DulcetReaderErrorKind)
        /// Offline, and nothing this device has seen matches.
        case readerOffline(DulcetReaderSeenCounts?)
    }

    private var phase: Phase {
        guard let reader else {
            return switch snapshot.state {
            case .searchLoading: .loading
            case .searchResults: .results
            case .searchEmpty: .empty
            case .searchError: .error
            default: .idle
            }
        }
        return switch reader.presentation {
        case .idle: .idle
        case .waiting: .loading
        case .rows: .results
        case .noMatches: .empty
        case let .failed(kind): .readerFailed(kind)
        case let .offlineNoMatches(seen): .readerOffline(seen)
        }
    }

    private var results: [DulcetSearchResult] {
        reader.map { $0.publication?.rows.map(\.result) ?? [] } ?? snapshot.searchResults
    }

    private func readerRow(_ id: DulcetSearchResult.ID) -> DulcetReaderSearchRow? {
        reader?.publication?.rows.first { $0.id == id }
    }

    private func activate(_ id: DulcetSearchResult.ID) {
        if let reader, let row = readerRow(id) {
            reader.onActivate(row)
        } else {
            onActivateResult(id)
        }
    }

    var body: some View {
        VStack(alignment: .leading, spacing: DulcetSpacing.sm) {
            VStack(alignment: .leading, spacing: DulcetSpacing.xs) {
#if !os(iOS)
                // On iOS the navigation bar's title already says it.
                Text(DulcetStrings.searchTitle)
                    .font(.title.weight(.semibold))
                    .accessibilityAddTraits(.isHeader)
#endif
#if os(iOS)
                // The platform's filled search field: a magnifier on the control fill, as
                // `.searchable` draws it, in the page's own layout rather than the navigation bar.
                HStack(spacing: DulcetSpacing.xxs) {
                    Image(systemName: "magnifyingglass")
                        .font(.callout)
                        .dulcetForeground(.secondaryTextOnControl)
                        .accessibilityHidden(true)
                    TextField(DulcetStrings.searchPrompt, text: $searchQuery)
                        .textFieldStyle(.plain)
                        // A search field takes the platform's search submit key and resigns on
                        // submit, so the keyboard clears the results without a second gesture.
                        .focused($searchFieldFocused)
                        .submitLabel(.search)
                        .onSubmit { searchFieldFocused = false }
                        .onAppear(perform: takeRequestedFocus)
                        .onChange(of: focusRequested) { _, _ in takeRequestedFocus() }
                        .accessibilityLabel(DulcetStrings.searchPrompt)
                        .accessibilityIdentifier("dulcet.search.field")
                }
                .padding(.horizontal, DulcetSpacing.xs)
                .padding(.vertical, 7)
                .background(Color.dulcetControl, in: RoundedRectangle(cornerRadius: 10, style: .continuous))
#else
                TextField(DulcetStrings.searchPrompt, text: $searchQuery)
                    .dulcetSearchFieldStyle()
#if os(macOS)
                    // The small AppKit field's SwiftUI ideal height differs from the hosted
                    // NSTextField's runtime intrinsic height. Use the native regular metric so
                    // the header priority below can preserve the control's actual ideal size.
                    .controlSize(.regular)
#else
                    .controlSize(.small)
#endif
                    // Deliberately no `.focused` binding on the Mac. MEASURED in the hosted
                    // search proof: with one attached -- even never set -- Return on a selected
                    // result row stopped reaching the table's primary action, so activating a
                    // result from the keyboard silently did nothing.
                    .accessibilityLabel(DulcetStrings.searchPrompt)
                    .accessibilityIdentifier("dulcet.search.field")
#endif
                Text(reader == nil ? DulcetStrings.searchSummary : DulcetStrings.readerSearchSummary)
                    .font(.caption)
                    .dulcetForeground(.secondaryTextOnWindow)
                    .lineLimit(nil)
            }
#if os(macOS)
            // Allocate the header before the flexible result surface, preserving its native field.
            // A vertical fixedSize here also preserves the header's wrapped ideal height during
            // NavigationSplitView's zero-width minimum-size probe. That inflated the navigation
            // minimum above the window height and clipped the first table row out of existence.
            .layoutPriority(1)
#endif

            searchContent
                .frame(maxWidth: .infinity, maxHeight: .infinity)
        }
        .padding(DulcetSpacing.lg)
        .background(Color.dulcetWindow)
        .dulcetForeground(.primaryTextOnWindow)
        .dulcetNavigationTitle(DulcetStrings.search)
    }

#if os(iOS)
    private func takeRequestedFocus() {
        guard focusRequested else { return }
        searchFieldFocused = true
        onFocusRequestHandled()
    }
#endif

    @ViewBuilder
    private var searchContent: some View {
        switch phase {
        case .loading:
            VStack(spacing: DulcetSpacing.sm) {
                ProgressView()
                Text(DulcetStrings.searchLoading)
                    .dulcetForeground(.secondaryTextOnWindow)
            }
        case .results:
            resultsTable
        case .empty:
            searchMessage(
                symbol: "magnifyingglass",
                title: DulcetStrings.searchEmptyTitle,
                body: DulcetStrings.searchEmptyBody
            )
        case .error:
            VStack(spacing: DulcetSpacing.md) {
                searchMessage(
                    symbol: "exclamationmark.magnifyingglass",
                    title: DulcetStrings.searchErrorTitle,
                    body: DulcetStrings.searchErrorBody
                )
                DulcetProminentAction(DulcetStrings.searchRetry, action: onRetry)
            }
        case let .readerFailed(kind):
            // A failure is never drawn as "no matches": the server was not heard from.
            VStack(spacing: DulcetSpacing.md) {
                searchMessage(
                    symbol: "exclamationmark.magnifyingglass",
                    title: DulcetStrings.searchErrorTitle,
                    body: DulcetStrings.readerSearchFailedBody(DulcetStrings.readerErrorPhrase(kind))
                )
                DulcetProminentAction(DulcetStrings.searchRetry, action: onRetry)
            }
            .accessibilityIdentifier("dulcet.search.failed")
        case let .readerOffline(seen):
            searchMessage(
                symbol: "wifi.slash",
                title: DulcetStrings.readerSearchOfflineEmptyTitle,
                body: DulcetReaderCopy.searchScopeLabel(.deviceOffline(seen)) ?? DulcetStrings.readerSearchScopeDevice
            )
        case .idle:
            searchMessage(
                symbol: "magnifyingglass",
                title: DulcetStrings.searchIdleTitle,
                body: reader == nil ? DulcetStrings.searchIdleBody : DulcetStrings.readerSearchIdleBody
            )
        }
    }

    private var resultsTable: some View {
        VStack(alignment: .leading, spacing: DulcetSpacing.sm) {
            HStack(alignment: .firstTextBaseline, spacing: DulcetSpacing.xs) {
                Text(DulcetStrings.bestMatches)
                    .font(.headline)
                    .accessibilityAddTraits(.isHeader)
                Text(DulcetStrings.searchResultCount(results.count))
                    .font(.caption)
                    .dulcetForeground(.secondaryTextOnWindow)
            }
            if let label = DulcetReaderCopy.searchScopeLabel(reader?.publication?.scope) {
                Label(label, systemImage: "iphone")
                    .font(.caption.weight(.medium))
                    .dulcetForeground(.secondaryTextOnWindow)
                    .lineLimit(nil)
                    .accessibilityIdentifier("dulcet.search.scope")
            }

#if os(macOS)
            Table(results, selection: $selectedResultID) {
                TableColumn(DulcetStrings.resultColumn) { result in
                    DulcetSearchResultIdentity(
                        result: result,
                        rank: results.firstIndex(of: result),
                        readerRow: readerRow(result.id)
                    )
                }
                .width(min: 320, ideal: 620)

                TableColumn(DulcetStrings.typeColumn) { result in
                    Text(result.kind.displayTitle)
                        .dulcetForeground(.secondaryTextOnWindow)
                }
                .width(min: 72, ideal: 90, max: 120)
            }
            .alternatingRowBackgrounds(.disabled)
            .contextMenu(forSelectionType: DulcetSearchResult.ID.self) { selection in
                if let id = selection.first,
                   let result = results.first(where: { $0.id == id }) {
                    DulcetSearchResultMenuItems(result: result, readerRow: readerRow(id)) {
                        selectedResultID = id
                        activate(id)
                    }
                }
            } primaryAction: { selection in
                guard let id = selection.first else { return }
                selectedResultID = id
                activate(id)
            }
#else
            // Selection is the macOS Table's idiom; touch and the remote share the library's
            // single-activation idiom instead -- one press on a row routes its semantic action
            // (play a track, open an album or artist) through onActivateResult.
            ScrollView {
                LazyVStack(spacing: DulcetSpacing.sm) {
                    ForEach(Array(results.enumerated()), id: \.element.id) { index, result in
                        Button {
                            activate(result.id)
                        } label: {
                            HStack(spacing: DulcetSpacing.md) {
                                DulcetSearchResultIdentity(
                                    result: result,
                                    rank: index,
                                    readerRow: readerRow(result.id)
                                )
                                Spacer(minLength: DulcetSpacing.md)
                                Text(result.kind.displayTitle)
                                    .font(.callout.weight(.semibold))
                                    .dulcetForeground(.secondaryTextOnWindow)
                            }
                            .padding(DulcetSpacing.sm)
                            .contentShape(Rectangle())
                        }
                        .dulcetRowButtonStyle()
                        // A button flattens its children into one accessibility element, so the
                        // stable rank identifier lives on the button here; on macOS it stays on
                        // the row's title text inside the Table.
                        .accessibilityIdentifier("dulcet.search.result.\(index)")
                        .accessibilityLabel(DulcetStrings.searchResultAccessibility(
                            title: result.title,
                            subtitle: result.subtitle,
                            kind: result.kind.displayTitle
                        ))
#if os(iOS)
                        // The system preview: the row is also a drag source onto the queue, which a
                        // custom preview stops from lifting out of the open menu (as for a track's).
                        .contextMenu {
                            DulcetSearchResultMenuItems(result: result, readerRow: readerRow(result.id)) {
                                activate(result.id)
                            }
                        }
                        .modifier(DulcetSearchRowQueueDrag(
                            result: result,
                            isUnavailableOffline: readerRow(result.id)?.isUnavailableOffline == true
                        ))
#endif
                    }
                }
            }
#if os(iOS)
            // `automatic` would already dismiss on scroll here - it only keeps the keyboard for
            // a TextEditor, and this is plain scrollable content. What `interactively` adds is that
            // the keyboard tracks the drag itself, so a partial drag can be reversed to cancel the
            // dismissal instead of committing to it the moment the scroll begins.
            .scrollDismissesKeyboard(.interactively)
#endif
#endif

            HStack(spacing: DulcetSpacing.xs) {
                ForEach(pagedKinds, id: \.rawValue) { kind in
                    Button(loadMoreTitle(for: kind)) {
                        onLoadMore(kind)
                    }
                    .disabled(snapshot.searchLoadingMoreKind != nil)
                }
                if snapshot.searchLoadingMoreKind != nil {
                    ProgressView()
                        .controlSize(.small)
                    Text(DulcetStrings.loadingMore)
                        .font(.caption)
                        .dulcetForeground(.secondaryTextOnWindow)
                }
            }
        }
    }

    private var pagedKinds: [DulcetSearchResultKind] {
        // The reader's search has no per-kind paging (§16.15): what it holds is what it shows.
        guard reader == nil else { return [] }
        return [.track, .album, .artist].filter(snapshot.searchHasMoreKinds.contains)
    }

    private func loadMoreTitle(for kind: DulcetSearchResultKind) -> String {
        switch kind {
        case .track: DulcetStrings.loadMoreTracks
        case .album: DulcetStrings.loadMoreAlbums
        case .artist: DulcetStrings.loadMoreArtists
        }
    }

    private func searchMessage(symbol: String, title: String, body: String) -> some View {
        VStack(spacing: DulcetSpacing.sm) {
            Image(systemName: symbol)
                .font(.system(size: 36, weight: .medium))
                .dulcetForeground(.secondaryTextOnWindow)
                .accessibilityHidden(true)
            Text(title)
                .font(.title2.weight(.semibold))
                .accessibilityAddTraits(.isHeader)
            Text(body)
                .dulcetForeground(.secondaryTextOnWindow)
                .multilineTextAlignment(.center)
                .lineLimit(nil)
        }
        .frame(maxWidth: 440)
    }
}

#if os(iOS) || os(macOS)
/// A track in the results drags as itself; an album drags whole, its tracks read when it is
/// dropped. An artist has no tracks of its own and lifts a card that says so. Chosen by the
/// result's kind, which a row never changes.
private struct DulcetSearchRowQueueDrag: ViewModifier {
    @Environment(DulcetPresentationStore.self) private var store
    let result: DulcetSearchResult
    let isUnavailableOffline: Bool

    @ViewBuilder
    func body(content: Content) -> some View {
        if result.kind == .album {
            content.dulcetDeferredQueueDragSource(
                store: store,
                artwork: result.artwork,
                title: result.title,
                isEnabled: !isUnavailableOffline
            ) { completion in
                store.resolveReaderAddition(
                    query: .album(rawID: result.id.rawID),
                    kind: .album,
                    id: result.id,
                    title: result.title,
                    completion: completion
                )
            }
        } else {
            content.dulcetQueueDragSource(
                store: store,
                artwork: result.artwork,
                title: result.title,
                isEnabled: result.playableTrack != nil && !isUnavailableOffline
            ) { result.playableTrack.map(DulcetQueueAddition.searchResult) }
        }
    }
}

/// A search result's context menu: its own activation (play a track, open an album or artist),
/// then Go to Album and Go to Artist where the library has those pages.
private struct DulcetSearchResultMenuItems: View {
    @Environment(DulcetPresentationStore.self) private var store
    let result: DulcetSearchResult
    var readerRow: DulcetReaderSearchRow?
    let onActivate: () -> Void

    var body: some View {
        switch result.kind {
        case .track:
            Button(DulcetStrings.play, systemImage: "play", action: onActivate)
            if let track = result.playableTrack, readerRow?.isUnavailableOffline != true {
                DulcetQueueInsertionMenuItems(addition: .searchResult(track))
            }
            if let track = result.playableTrack, let albumID = store.libraryAlbumID(for: track) {
                Button(DulcetStrings.goToAlbum, systemImage: "square.stack") {
                    store.showAlbum(albumID)
                }
            }
        case .album:
            Button(DulcetStrings.goToAlbum, systemImage: "square.stack", action: onActivate)
        case .artist:
            Button(DulcetStrings.goToArtist, systemImage: "music.mic", action: onActivate)
        }
        if let readerRow, let target = readerRow.favouriteTarget {
            DulcetFavouriteMenuItem(target: target, published: readerRow.isFavourite)
        }
        if result.kind != .artist {
            ForEach(artistTargets, id: \.id) { target in
                Button(
                    artistTargets.count == 1 ? DulcetStrings.goToArtist : target.name,
                    systemImage: "music.mic"
                ) {
                    store.showArtist(target.id)
                }
            }
        }
    }

    private var artistTargets: [(name: String, id: DulcetProviderItemID)] {
        var seen = Set<DulcetProviderItemID>()
        return result.credits.compactMap { credit in
            guard let id = store.libraryArtistID(for: credit), seen.insert(id).inserted else {
                return nil
            }
            return (credit.name, id)
        }
    }
}
#endif

private extension View {
    @ViewBuilder
    func dulcetSearchFieldStyle() -> some View {
#if os(tvOS)
        textFieldStyle(.plain)
            .padding(.horizontal, DulcetSpacing.md)
            .padding(.vertical, DulcetSpacing.sm)
            .background(Color.dulcetControl, in: RoundedRectangle(cornerRadius: 14))
#else
        textFieldStyle(.roundedBorder)
#endif
    }
}

private struct DulcetSearchResultIdentity: View {
    @Environment(\.dynamicTypeSize) private var dynamicTypeSize
    let result: DulcetSearchResult
    let rank: Int?
    var readerRow: DulcetReaderSearchRow?

    private var unavailableOffline: Bool { readerRow?.isUnavailableOffline == true }

    var body: some View {
        HStack(alignment: .center, spacing: DulcetSpacing.xs) {
            DulcetArtworkView(
                artwork: result.artwork,
                size: DulcetMetrics.denseRowArtworkSize,
                muted: unavailableOffline
            )

            VStack(alignment: .leading, spacing: 0) {
                Text(result.title)
                    .font(.callout.weight(.medium))
                    .dulcetForeground(.primaryTextOnWindow)
                    .lineLimit(dynamicTypeSize.isAccessibilitySize ? nil : 1)
#if os(macOS)
                    // iOS/tvOS rows are buttons, which absorb child identifiers into the row's
                    // own accessibility element; there the button carries this identifier.
                    .accessibilityIdentifier(rank.map { "dulcet.search.result.\($0)" } ?? "")
#endif
                if !result.subtitle.isEmpty {
                    Text(result.subtitle)
                        .font(.caption)
                        .dulcetForeground(.secondaryTextOnWindow)
                        .lineLimit(dynamicTypeSize.isAccessibilitySize ? nil : 1)
                }
                if let readerRow, readerRow.source == .device || unavailableOffline {
                    Text(unavailableOffline
                        ? DulcetStrings.readerNotAvailableOffline
                        : DulcetStrings.readerSearchRowDevice)
                        .font(.caption2.weight(.medium))
                        .dulcetForeground(.secondaryTextOnWindow)
                        .lineLimit(nil)
                }
            }
            if let readerRow, readerRow.favouriteTarget != nil {
                DulcetFavouriteIndicator(id: readerRow.id, published: readerRow.isFavourite)
            }
        }
        .accessibilityLabel(accessibilityLabel)
    }

    private var accessibilityLabel: String {
        var label = DulcetStrings.searchResultAccessibility(
            title: result.title,
            subtitle: result.subtitle,
            kind: result.kind.displayTitle
        )
        if unavailableOffline {
            label += ", " + DulcetStrings.readerNotAvailableOffline
        } else if readerRow?.source == .device {
            label += ", " + DulcetStrings.readerSearchRowDevice
        }
        return label
    }
}

/// What the reader's search shows, and what activating a row does.
struct DulcetReaderSearchContent {
    let query: String
    let publication: DulcetReaderSearchPublication?
    let onActivate: (DulcetReaderSearchRow) -> Void

    /// What the screen says for the reader's search (§16.15).
    enum Presentation: Equatable {
        /// Nothing typed.
        case idle
        /// No row yet, and the server's answer is still coming.
        case waiting
        /// Rows to draw; their scope label says where they came from.
        case rows
        /// The server answered, and nothing matches.
        case noMatches
        /// The server's search failed (a timeout included) and nothing on this device matches.
        case failed(DulcetReaderErrorKind)
        /// Offline, and nothing this device has seen matches.
        case offlineNoMatches(DulcetReaderSeenCounts?)
    }

    var presentation: Presentation {
        let trimmed = query.trimmingCharacters(in: .whitespacesAndNewlines)
        if trimmed.isEmpty { return .idle }
        guard let publication else { return .waiting }
        if !publication.rows.isEmpty { return .rows }
        switch publication.scope {
        case .deviceWhileServerPending:
            // Nothing on the device yet; the server's answer is still coming for a query it is
            // asked. The core bounds this wait (LibrarySearchConfig.serverAnswerDeadlineMillis).
            return trimmed.count >= 2 ? .waiting : .noMatches
        case .serverAndDevice:
            return .noMatches
        case let .deviceServerFailed(kind, _):
            return .failed(kind)
        case let .deviceOffline(seen):
            return .offlineNoMatches(seen)
        }
    }
}

extension DulcetSearchResultKind {
    var displayTitle: String {
        switch self {
        case .track: DulcetStrings.track
        case .album: DulcetStrings.album
        case .artist: DulcetStrings.artist
        }
    }
}
#endif
