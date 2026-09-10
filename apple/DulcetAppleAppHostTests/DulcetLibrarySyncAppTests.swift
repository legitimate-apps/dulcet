import Foundation
import UIKit
import DulcetCore
import XCTest
@testable import DulcetKit
@testable import Dulcet_DEV

@MainActor
final class DulcetLibrarySyncAppTests: XCTestCase {
    private let fixtureUsername = "dulcet-admin"
    private let fixturePassword = "dulcet-ci-canary-password"

#if os(iOS)
    func testLibrarySyncUsesCommittedGenerationsSchedulesRefreshAndReopensOfflineOnIOSSimulator() async throws {
        XCTAssertEqual(Bundle.main.bundleIdentifier, "com.legitimateapps.dulcet.ios.dev")
        XCTAssertEqual(UIDevice.current.userInterfaceIdiom, .phone)
        try await assertLibrarySync(platform: "ios")
    }

    func testLibrarySyncUsesCommittedGenerationsSchedulesRefreshAndReopensOfflineOnIPadOSSimulator() async throws {
        XCTAssertEqual(Bundle.main.bundleIdentifier, "com.legitimateapps.dulcet.ios.dev")
        XCTAssertEqual(UIDevice.current.userInterfaceIdiom, .pad)
        try await assertLibrarySync(platform: "ipados")
    }
#elseif os(tvOS)
    func testLibrarySyncUsesCommittedGenerationsSchedulesRefreshAndReopensOfflineOnTVOSSimulator() async throws {
        XCTAssertEqual(Bundle.main.bundleIdentifier, "com.legitimateapps.dulcet.tvos.dev")
        XCTAssertEqual(UIDevice.current.userInterfaceIdiom, .tv)
        try await assertLibrarySync(platform: "tvos")
    }
#endif

    private func assertLibrarySync(platform: String) async throws {
        let baseURL = try XCTUnwrap(
            ProcessInfo.processInfo.environment["DULCET_CONFORMANCE_BASE_URL"],
            "apple-ci must supply the live disposable conformance fixture URL"
        )
        guard baseURL == "http://127.0.0.1:4533",
              ProcessInfo.processInfo.environment["DULCET_CONFORMANCE_DISPOSABLE"] == "true" else {
            XCTFail("Library sync requires the local disposable fixture")
            throw LibrarySyncTestError.invalidFixture
        }
        let databaseName = "library-sync-\(platform)-\(UUID().uuidString).db"
        let providerInstanceID = "provider-\(UUID().uuidString)"
        let request = DulcetAccountConnectRequest(
            serverURL: baseURL,
            username: fixtureUsername,
            password: fixturePassword,
            allowLocalHTTP: true
        )
        let connector = CompletingAccountConnector()
        let credentials = ProviderInstanceCredentialStore()
        let refreshScheduler = SingleFireMonotonicLibraryRefreshScheduler()
        let library = DulcetCoreLibraryBrowser(databaseName: databaseName)
        let source = DulcetAccountDataSource(
            connector: connector,
            credentialStore: credentials,
            libraryBrowser: library,
            libraryRefreshCadence: .milliseconds(500),
            libraryRefreshScheduler: refreshScheduler,
            providerInstanceIDFactory: { providerInstanceID }
        )
        let store = DulcetPresentationStore(source: source)
        store.accountServerURL = request.serverURL
        store.accountUsername = request.username
        store.accountPassword = request.password
        store.accountAllowLocalHTTP = request.allowLocalHTTP
        store.submitAccountConnection()
        connector.complete(.connected(DulcetConnectedAccountSummary(
            serverName: "Disposable fixture",
            normalizedServerURL: baseURL
        )))
        XCTAssertEqual(credentials.providerInstanceID, providerInstanceID)

        store.selectDestination(.library)
        try await waitUntil(
            timeout: .seconds(90),
            failureMessage: "the production library sync did not publish its committed generation"
        ) {
            library.completedSyncGenerations == [1]
                && store.snapshot.state == .libraryBrowse
        }

        let firstInspector = AppleLibrarySyncClient(
            databaseName: databaseName,
            maximumInFlightPerServer: 4
        )
        let firstCommitted = try XCTUnwrap(firstInspector.readCommitted(
            providerInstanceId: providerInstanceID
        ).snapshot)
        XCTAssertEqual(firstCommitted.generation, 1)
        XCTAssertEqual(library.startedSyncCount, 1)
        XCTAssertEqual(library.displayedCommittedGenerations, [firstCommitted.generation])
        // Why an exact sequence and not just an invariant: within one open a preview can only be
        // delivered before the commit — a preview arriving after the sync has finished is
        // suppressed — and the preview issues a strict SUBSET of the sync's own first three
        // requests while the sync additionally reads every album, the playlists, the starred set,
        // the genres and a stability re-walk before it commits. So the preview losing this race
        // would mean its three requests took longer than all of that, which is a result worth
        // failing on rather than tolerating.
        XCTAssertEqual(library.publicationOrder, ["preview", "committed"])
        // Order-free invariants, so a future reordering cannot quietly retire the control above:
        // every preview is followed by exactly one committed publication, and the generations the
        // person was shown never go backwards.
        XCTAssertEqual(library.deliveredPreviewCount, library.displayedCommittedGenerations.count)
        XCTAssertEqual(
            library.displayedCommittedGenerations,
            library.displayedCommittedGenerations.sorted()
        )
        assertDisplayedLibrary(store.snapshot, equals: firstCommitted.library)
        XCTAssertEqual(refreshScheduler.scheduledCount, 1)
        XCTAssertFalse(firstCommitted.library.albums.isEmpty)
        XCTAssertFalse(firstCommitted.library.albums.flatMap(\.tracks).isEmpty)
        try await waitUntil(
            timeout: .seconds(90),
            failureMessage: "the monotonic scheduled refresh did not commit a second generation"
        ) {
            library.completedSyncGenerations == [1, 2]
                && store.snapshot.state == .libraryBrowse
        }
        let secondCommitted = try XCTUnwrap(firstInspector.readCommitted(
            providerInstanceId: providerInstanceID
        ).snapshot)
        XCTAssertEqual(refreshScheduler.firedCount, 1)
        print("LIBRARY SYNC SCHEDULED REFRESH platform=\(platform) fired=\(refreshScheduler.firedCount)")
        XCTAssertEqual(secondCommitted.generation, 2)
        XCTAssertEqual(library.startedSyncCount, 2)
        XCTAssertEqual(library.displayedCommittedGenerations, [1, 2])
        XCTAssertEqual(
            library.publicationOrder,
            ["preview", "committed", "preview", "committed"]
        )
        // Order-free invariants, so a future reordering cannot quietly retire the control above:
        // every preview is followed by exactly one committed publication, and the generations the
        // person was shown never go backwards.
        XCTAssertEqual(library.deliveredPreviewCount, library.displayedCommittedGenerations.count)
        XCTAssertEqual(
            library.displayedCommittedGenerations,
            library.displayedCommittedGenerations.sorted()
        )
        assertDisplayedLibrary(store.snapshot, equals: secondCommitted.library)
        XCTAssertEqual(refreshScheduler.scheduledCount, 2)

        let unreachableURL = try XCTUnwrap(URL(string: "http://127.0.0.1:1"))
        let unreachableConfiguration = URLSessionConfiguration.ephemeral
        unreachableConfiguration.timeoutIntervalForRequest = 0.5
        let unreachableSession = URLSession(configuration: unreachableConfiguration)
        do {
            _ = try await unreachableSession.data(from: unreachableURL)
            XCTFail("the offline control endpoint unexpectedly accepted a connection")
        } catch {
            let failure = error as NSError
            XCTAssertEqual(failure.domain, NSURLErrorDomain)
            XCTAssertEqual(failure.code, NSURLErrorCannotConnectToHost)
            guard failure.domain == NSURLErrorDomain,
                  failure.code == NSURLErrorCannotConnectToHost else { throw error }
            print("LIBRARY SYNC OFFLINE REFUSAL platform=\(platform) code=\(failure.code)")
        }
        unreachableSession.invalidateAndCancel()

        let offlineRequest = DulcetAccountConnectRequest(
            serverURL: unreachableURL.absoluteString,
            username: fixtureUsername,
            password: fixturePassword,
            allowLocalHTTP: true
        )
        let offlineCredentials = ProviderInstanceCredentialStore(
            persisted: offlineRequest,
            providerInstanceID: try XCTUnwrap(credentials.providerInstanceID)
        )
        let offlineConnector = CompletingAccountConnector()
        let reopenedLibrary = DulcetCoreLibraryBrowser(databaseName: databaseName)
        let reopenedStore = DulcetPresentationStore(source: DulcetAccountDataSource(
            connector: offlineConnector,
            credentialStore: offlineCredentials,
            libraryBrowser: reopenedLibrary
        ))
        XCTAssertEqual(reopenedStore.snapshot.state, .accountSavedDisconnected)
        reopenedStore.selectDestination(.library)

        XCTAssertEqual(reopenedStore.snapshot.state, .libraryBrowse)
        XCTAssertFalse(reopenedStore.snapshot.accountConnected)
        XCTAssertTrue(offlineConnector.requests.isEmpty)
        XCTAssertEqual(reopenedLibrary.startedSyncCount, 0)
        XCTAssertEqual(reopenedLibrary.completedSyncGenerations, [])
        XCTAssertEqual(reopenedLibrary.displayedCommittedGenerations, [2])
        // A saved-account reopen reads the committed library only: no server is contacted, so it
        // publishes once and there is no preview at all.
        XCTAssertEqual(reopenedLibrary.publicationOrder, ["committed"])
        XCTAssertEqual(reopenedLibrary.deliveredPreviewCount, 0)
        assertDisplayedLibrary(reopenedStore.snapshot, equals: secondCommitted.library)

        print(
            "LIBRARY SYNC APP INTEGRATION platform=\(platform)"
                + " sync-generations=1,2 displayed-generations=1,2"
                + " scheduled-refresh-fired=true offline-endpoint=unreachable"
                + " offline-sync-starts=0 offline-displayed-generation=2"
        )
    }

    private func assertDisplayedLibrary(
        _ displayed: DulcetSnapshot,
        equals committed: AppleLibraryBrowseSnapshotDto,
        file: StaticString = #filePath,
        line: UInt = #line
    ) {
        XCTAssertEqual(
            displayed.musicFolders.map(\.id.rawID),
            committed.musicFolders.map(\.rawId),
            file: file,
            line: line
        )
        XCTAssertEqual(
            displayed.musicFolders.map(\.id.providerInstanceID),
            committed.musicFolders.map(\.providerInstanceId),
            file: file,
            line: line
        )
        XCTAssertEqual(
            displayed.artists.map(\.id.rawID),
            committed.artists.map(\.rawId),
            file: file,
            line: line
        )
        XCTAssertEqual(
            displayed.artists.map(\.id.providerInstanceID),
            committed.artists.map(\.providerInstanceId),
            file: file,
            line: line
        )
        XCTAssertEqual(
            displayed.albums.map(\.id.rawID),
            committed.albums.map(\.rawId),
            file: file,
            line: line
        )
        XCTAssertEqual(
            displayed.albums.map(\.id.providerInstanceID),
            committed.albums.map(\.providerInstanceId),
            file: file,
            line: line
        )
        XCTAssertEqual(
            displayed.albums.flatMap(\.tracks).map(\.id.rawID),
            committed.albums.flatMap(\.tracks).map(\.rawId),
            file: file,
            line: line
        )
        XCTAssertEqual(
            displayed.albums.flatMap(\.tracks).map(\.id.providerInstanceID),
            committed.albums.flatMap(\.tracks).map(\.providerInstanceId),
            file: file,
            line: line
        )
    }

    private func waitUntil(
        timeout: Duration,
        failureMessage: @autoclosure () -> String,
        condition: @MainActor () -> Bool
    ) async throws {
        let clock = ContinuousClock()
        let deadline = clock.now.advanced(by: timeout)
        while !condition() {
            if clock.now >= deadline {
                XCTFail(failureMessage())
                throw LibrarySyncTestError.timedOut
            }
            try await Task.sleep(for: .milliseconds(50))
        }
    }

}

private enum LibrarySyncTestError: Error { case invalidFixture, timedOut }

@MainActor
private final class KeyboardTraceAccountOperation: DulcetAccountConnectOperation {
    private(set) var cancelCount = 0

    func cancel() {
        cancelCount += 1
    }
}

@MainActor
private final class CompletingAccountConnector: DulcetAccountConnecting {
    private var completion: (@MainActor (DulcetAccountConnectOutcome) -> Void)?
    private(set) var requests: [DulcetAccountConnectRequest] = []

    func connect(
        _ request: DulcetAccountConnectRequest,
        completion: @escaping @MainActor (DulcetAccountConnectOutcome) -> Void
    ) -> any DulcetAccountConnectOperation {
        requests.append(request)
        self.completion = completion
        return KeyboardTraceAccountOperation()
    }

    func complete(_ outcome: DulcetAccountConnectOutcome) {
        completion?(outcome)
        completion = nil
    }
}

@MainActor
private final class ProviderInstanceCredentialStore: DulcetProviderInstanceCredentialStoring {
    private var persisted: DulcetAccountConnectRequest?
    private(set) var providerInstanceID: String?

    init(
        persisted: DulcetAccountConnectRequest? = nil,
        providerInstanceID: String? = nil
    ) {
        self.persisted = persisted
        self.providerInstanceID = providerInstanceID
    }

    func load() throws -> DulcetAccountConnectRequest? {
        persisted
    }

    func save(_ request: DulcetAccountConnectRequest) throws {
        persisted = request
    }

    func save(
        _ request: DulcetAccountConnectRequest,
        providerInstanceID: String
    ) throws {
        persisted = request
        self.providerInstanceID = providerInstanceID
    }

    func delete() throws {
        persisted = nil
        providerInstanceID = nil
    }
}

@MainActor
private final class SingleFireMonotonicLibraryRefreshScheduler: DulcetLibraryRefreshScheduling {
    private let scheduler = DulcetMonotonicLibraryRefreshScheduler()
    private(set) var scheduledCount = 0
    private(set) var firedCount = 0

    func schedule(
        after delay: Duration,
        action: @escaping @MainActor () -> Void
    ) -> any DulcetLibraryRefreshOperation {
        scheduledCount += 1
        guard scheduledCount == 1 else {
            return InertLibraryRefreshOperation()
        }
        return scheduler.schedule(after: delay) { [self] in
            firedCount += 1
            action()
        }
    }
}

@MainActor
private final class InertLibraryRefreshOperation: DulcetLibraryRefreshOperation {
    func cancel() {}
}
