#if os(macOS)
import AppKit
import SwiftUI
import Testing
@testable import DulcetKit

/// Exercises the native Table cell hosts, not just Search's value projection. In particular,
/// artwork and favourite indicators must receive the store supplied by the production root.
@Suite(.serialized)
@MainActor
struct MacGroupedSearchHostingTests {
    @Test(arguments: [false, true])
    func readerResultsRenderThroughTheRootAndNativeTable(startWithResults: Bool) async throws {
        let source = GroupedSearchHostingSource(startWithResults: startWithResults)
        let store = DulcetPresentationStore(source: source)
        let hosting = NSHostingView(rootView: DulcetRootView(store: store))
        let window = NSWindow(
            contentRect: NSRect(x: 0, y: 0, width: 1_180, height: 760),
            styleMask: [.titled, .closable, .resizable], backing: .buffered, defer: false
        )
        window.isReleasedWhenClosed = false
        window.contentView = hosting
        window.makeKeyAndOrderFront(nil)
        defer {
            window.close()
            source.librarySession?.close()
        }

        // Do not add .environment(store) to the test host: the actual root owns injection.
        try await settle(hosting)
        if !startWithResults {
            #expect(nativeTables(in: hosting).filter { $0.numberOfColumns == 2 }.allSatisfy { $0.numberOfRows == 0 })
            store.searchQuery = "Dulcet"
        }
        for _ in 0..<100 {
            try await settle(hosting)
            if nativeTables(in: hosting).contains(where: { $0.numberOfRows == 15 }),
               !source.artworkRequests.isEmpty { break }
        }

        let table = try #require(nativeTables(in: hosting).first { $0.numberOfRows == 15 },
            "Six results of each kind must render five per group in the native results Table")
        #expect(table.numberOfColumns == 2)
        let firstCell = try #require(table.view(atColumn: 0, row: 0, makeIfNecessary: true))
        #expect(firstCell.bounds.width > 0)
        #expect(firstCell.bounds.height > 0)
        #expect(source.artworkRequests.contains("cover:artist:0"),
            "The first grouped artist cell must actually run the artwork view through the root store")

        // Force the final group into native cell hosts as well, rather than passing with only
        // the first screenful instantiated. Every reader row has a favourite target.
        table.scrollRowToVisible(14)
        for _ in 0..<100 {
            try await settle(hosting)
            if source.artworkRequests.contains("cover:track:4") { break }
        }
        #expect(table.view(atColumn: 0, row: 14, makeIfNecessary: true) != nil)
        #expect(source.artworkRequests.contains("cover:track:4"),
            "Scrolling must render the final Songs preview row with its real artwork consumer")
        #expect(source.reader.queries.contains("Dulcet"))
    }

    private func settle(_ hosting: NSView) async throws {
        hosting.layoutSubtreeIfNeeded()
        hosting.displayIfNeeded()
        // Yield the main actor so native cell hosting and SwiftUI transactions can complete.
        try await Task.sleep(for: .milliseconds(20))
    }

    private func nativeTables(in view: NSView) -> [NSTableView] {
        (view as? NSTableView).map { [$0] } ?? view.subviews.flatMap(nativeTables)
    }
}

@MainActor
private final class GroupedSearchHostingSource: DulcetDataSource, DulcetLibrarySessionProviding, DulcetArtworkLoading {
    let reader: GroupedSearchHostingReader
    let librarySession: DulcetLibrarySession?
    private(set) var currentSnapshot: DulcetSnapshot
    private var snapshotHandler: (@MainActor (DulcetSnapshot) -> Void)?
    private(set) var artworkRequests: Set<String> = []

    init(startWithResults: Bool) {
        currentSnapshot = Self.snapshot(query: startWithResults ? "Dulcet" : "")
        let reader = GroupedSearchHostingReader()
        self.reader = reader
        let session = DulcetLibrarySession(factory: reader)
        librarySession = session
        session.open(account: DulcetLibraryReaderAccount(
            providerInstanceID: "grouped-search-hosting", normalizedServerURL: "https://music.example.invalid",
            username: "fixture", password: "fixture", allowLocalHTTP: false
        ), mode: .connected)
    }

    func setSnapshotHandler(_ handler: @escaping @MainActor (DulcetSnapshot) -> Void) { snapshotHandler = handler }

    func send(_ action: DulcetPresentationAction) {
        guard case let .updateSearchQuery(query) = action else { return }
        currentSnapshot = Self.snapshot(query: query)
        snapshotHandler?(currentSnapshot)
    }

    func loadArtwork(
        _ reference: DulcetArtworkReference, sizeBucket: DulcetArtworkSizeBucket,
        completion: @escaping @MainActor (DulcetArtworkFetchOutcome) -> Void
    ) -> (any DulcetArtworkFetchOperation)? {
        artworkRequests.insert(reference.artworkKey)
        completion(.unavailable)
        return nil
    }

    private static func snapshot(query: String) -> DulcetSnapshot {
        DulcetSnapshot(
            state: query.isEmpty ? .searchIdle : .searchResults, selectedDestination: .search,
            accountConnected: true, connectivity: .online(serverName: "Fixture"),
            albums: [], looseTracks: [], recentlyAddedTracks: [], searchQuery: query,
            searchResults: query.isEmpty ? [] : GroupedSearchHostingReader.rows.map(\.result),
            captureDate: Date(timeIntervalSince1970: 0)
        )
    }
}

@MainActor
private final class GroupedSearchHostingReader: DulcetLibraryReaderMaking, DulcetLibraryReading {
    private(set) var queries: [String] = []

    // Deliberately put Songs first in the publication: the UI must group Artists first and
    // cap each group at five, while still retaining all six entries for See All.
    static var rows: [DulcetReaderSearchRow] {
        ["track", "album", "artist"].flatMap { kind in
            (0..<6).map { index in
                DulcetReaderSearchRow(
                    kind: kind, providerInstanceID: "grouped-search-hosting", rawID: "\(kind):\(index)",
                    title: "Dulcet \(kind) \(index)", credits: [], albumTitle: nil, year: 2026,
                    durationMilliseconds: kind == "track" ? 180_000 : nil,
                    discNumber: nil, trackNumber: nil, sourceContainer: nil, mediaSourceID: nil,
                    artworkKey: "cover:\(kind):\(index)", source: "server", favourite: true,
                    rating: nil, playability: nil
                )!
            }
        }
    }

    func makeReader(account: DulcetLibraryReaderAccount, foreground: Bool) -> any DulcetLibraryReading { self }
    func subscribeSearch(
        onPublication: @escaping @MainActor (DulcetReaderSearchPublication) -> Void
    ) -> any DulcetLibrarySearchSubscribing {
        GroupedSearchHostingSubscription { [weak self] query in
            self?.queries.append(query)
            onPublication(DulcetReaderSearchPublication(
                query: query, sequence: 1, scope: .serverAndDevice, rows: query.isEmpty ? [] : Self.rows
            ))
        }
    }
    func subscribeWindow(
        _ query: DulcetLibraryQuery, onPublication: @escaping @MainActor (DulcetLibraryWindow) -> Void
    ) -> any DulcetLibraryWindowSubscribing { GroupedSearchHostingSubscription { _ in } }
    func setFavourite(_ target: DulcetFavouriteTarget, favourite: Bool) -> Bool { true }
    func setRating(_ target: DulcetFavouriteTarget, rating: Int) -> Bool { true }
    func subscribeFavouriteOutcomes(
        _ handler: @escaping @MainActor (DulcetFavouriteOutcome) -> Void
    ) -> any DulcetLibraryReaderCancellable { GroupedSearchHostingSubscription { _ in } }
    func pendingChangeCount(
        completion: @escaping @MainActor (DulcetPendingChanges) -> Void
    ) -> any DulcetLibraryReaderCancellable {
        completion(DulcetPendingChanges(count: 0))
        return GroupedSearchHostingSubscription { _ in }
    }
    func connect(
        completion: @escaping @MainActor (DulcetReaderConnection) -> Void
    ) -> any DulcetLibraryReaderCancellable {
        completion(DulcetReaderConnection(epochKnown: true, serverReportsNoEpoch: false,
            discardedPendingChanges: 0, errorKind: nil))
        return GroupedSearchHostingSubscription { _ in }
    }
    func reconnect(
        completion: @escaping @MainActor (DulcetReaderConnection) -> Void
    ) -> any DulcetLibraryReaderCancellable { connect(completion: completion) }
    func setupFailed(completion: @escaping @MainActor (Bool) -> Void) -> any DulcetLibraryReaderCancellable {
        completion(false)
        return GroupedSearchHostingSubscription { _ in }
    }
    func setOnline(_ reachable: Bool) {}
    func setForeground(_ foreground: Bool) {}
    func setNetworkConstrained(_ constrained: Bool) {}
    func close(completion: @escaping @MainActor () -> Void) { completion() }
}

@MainActor
private final class GroupedSearchHostingSubscription:
    DulcetLibrarySearchSubscribing, DulcetLibraryWindowSubscribing, DulcetLibraryReaderCancellable {
    private var update: (@MainActor (String) -> Void)?
    init(update: @escaping @MainActor (String) -> Void) { self.update = update }
    func updateQuery(_ text: String) { update?(text) }
    func refresh() {}
    func loadMore() {}
    func loadBefore() {}
    func setViewport(first: Int, last: Int) {}
    func close() { update = nil }
    func cancel() { close() }
}
#endif
