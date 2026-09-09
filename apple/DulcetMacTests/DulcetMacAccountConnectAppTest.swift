import AppKit
import DulcetCore
import SwiftUI
import XCTest
@testable import DulcetKit
@testable import DulcetMac

@MainActor
final class DulcetMacAccountConnectAppTest: XCTestCase {
    private let activeAccountKey = "com.legitimateapps.dulcet.active-account-id"
    private let fixtureUsername = "dulcet-admin"
    private let fixturePassword = "dulcet-ci-canary-password"

    // Bounded CONF-09b contribution: real input failure, not all declared states.
    func accountInputFailureCrossesProductionConnectorIntoStore() async throws {
        XCTAssertEqual(Bundle.main.bundleIdentifier, "com.legitimateapps.dulcet.dev")
        let credentials = SearchMemoryCredentialStore()
        let store = DulcetPresentationStore(source: DulcetAccountDataSource(
            connector: DulcetCoreAccountConnector(), credentialStore: credentials
        ))
        XCTAssertEqual(store.snapshot.state, .accountConnectIdle)
        store.accountServerURL = "https://"
        store.accountUsername = "listener"
        store.accountPassword = "fixture-password"
        store.submitAccountConnection()
        XCTAssertEqual(store.snapshot.state, .accountConnecting)
        defer { store.cancelAccountConnection() }

        // AccountConnector rejects the malformed URL, the Kotlin facade dispatches the error,
        // and DulcetCoreAccountConnector must forward it to the production presentation source.
        try await waitUntil(
            timeout: .seconds(5),
            failureMessage: "Production failure forwarding did not leave connecting: \(store.snapshot.state)"
        ) { store.snapshot.state != .accountConnecting }
        XCTAssertEqual(store.snapshot.state, .accountErrorInput)
        guard case let .failed(failure) = store.snapshot.accountConnection else {
            XCTFail("The production connector did not deliver its input failure")
            return
        }
        XCTAssertEqual(failure.kind, .invalidServerURL)
        XCTAssertFalse(store.snapshot.accountConnected)
        XCTAssertEqual(credentials.credentialGeneration, 0)
    }

    func searchQueryRanksAndActivatesTrackThroughHostedAppUI() async throws {
        let baseURL = try XCTUnwrap(
            ProcessInfo.processInfo.environment["DULCET_CONFORMANCE_BASE_URL"],
            "apple-ci must supply the live conformance fixture URL"
        )
        let disposable = ProcessInfo.processInfo.environment["DULCET_CONFORMANCE_DISPOSABLE"]
        guard baseURL == "http://127.0.0.1:4533", disposable == "true" else {
            XCTFail("Search fixture refused: baseURL=\(baseURL.debugDescription), disposable=\(String(describing: disposable)); expected disposable loopback http://127.0.0.1:4533")
            throw SearchHostedAppTestError.invalidFixture
        }

        let playback = SearchIntentPlaybackController()
        let source = DulcetAccountDataSource(
            connector: DulcetCoreAccountConnector(),
            credentialStore: SearchMemoryCredentialStore(),
            serverSearch: DulcetCoreServerSearch(),
            playbackController: playback,
            providerInstanceIDFactory: { "macos-search-ui-fixture" }
        )
        let store = DulcetPresentationStore(source: source)
        store.accountServerURL = baseURL
        store.accountUsername = fixtureUsername
        store.accountPassword = fixturePassword
        store.accountAllowLocalHTTP = true
        store.submitAccountConnection()
        try await waitUntil(
            timeout: .seconds(20),
            failureMessage: "Disposable account connected=\(store.snapshot.accountConnected) state=\(store.snapshot.state)"
        ) {
            store.snapshot.accountConnected
        }

        // SwiftUI materializes its accessibility nodes only when accessibility is requested.
        // Restore the application-wide flag so this control does not affect sibling tests.
        let enhancedUI = NSAccessibility.Attribute(rawValue: "AXEnhancedUserInterface")
        let previousEnhancedUI = NSApp.accessibilityAttributeValue(enhancedUI) ?? false
        NSApp.accessibilitySetValue(true, forAttribute: enhancedUI)
        defer { NSApp.accessibilitySetValue(previousEnhancedUI, forAttribute: enhancedUI) }
        let hostingView = NSHostingView(rootView: DulcetMacProduction.makeRootView(store: store))
        hostingView.frame = NSRect(x: 0, y: 0, width: 1180, height: 760)
        let window = NSWindow(
            contentRect: hostingView.frame,
            styleMask: [.titled, .closable, .resizable],
            backing: .buffered,
            defer: false
        )
        window.isReleasedWhenClosed = false
        window.contentView = hostingView
        window.makeKeyAndOrderFront(nil)
        defer { window.close() }
        hostingView.layoutSubtreeIfNeeded()

        let searchDestination = try await accessibilityElement(
            identifiedBy: "dulcet.sidebar.search",
            in: hostingView,
            timeout: .seconds(5)
        )
        _ = try selectAccessibilityTableRow(searchDestination, in: window)
        try await waitUntil(
            timeout: .seconds(5),
            failureMessage: "AX selection dulcet.sidebar.search: destination=\(store.selectedDestination), expected search"
        ) {
            store.selectedDestination == .search
        }

        hostingView.layoutSubtreeIfNeeded()
        let searchFieldElement = try await accessibilityElement(
            identifiedBy: "dulcet.search.field",
            in: hostingView,
            timeout: .seconds(5)
        )
        // Focus the actual AppKit field resolved from the app's accessibility identifier.
        // Never set its value or call the presentation store's search API.
        let searchField = try XCTUnwrap(
            (searchFieldElement as? NSTextField) ?? (searchFieldElement as? NSCell)?.controlView as? NSTextField,
            "dulcet.search.field must resolve to NSTextField; observed \(type(of: searchFieldElement))"
        )
        XCTAssertTrue(window.makeFirstResponder(searchField),
            "dulcet.search.field rejected focus; responder=\(String(describing: window.firstResponder))")
        XCTAssertTrue(searchField.currentEditor() === window.firstResponder,
            "dulcet.search.field editor=\(String(describing: searchField.currentEditor())) responder=\(String(describing: window.firstResponder))")
        // A query matching exactly one row cannot separate rank from arity: with a single result,
        // "rank zero" and "the only row" are the same assertion, and so are "activate the row that
        // was pressed" and "activate results[0]". This query matches four rows and places the
        // canary at a non-zero rank, so both distinctions become observable.
        let query = "Threshold"
        let canaryTitle = "UI Playback Canary"
        let canaryRank = 2
        // The rendered order is a product contract, not a server one: results are ranked by match
        // quality first, then by kind with tracks ahead of albums, then by the order the server
        // returned them. The server lists the matching album ahead of every track; the app does
        // not. Asserting the whole order makes any drift in either fail here, naming what it
        // observed, rather than silently relocating the canary.
        let rankedLabels = [
            "Thirty One Seconds, Dulcet Fixtures · Threshold Boundary, Track",
            "Twenty Nine Seconds, Dulcet Fixtures · Threshold Boundary, Track",
            "UI Playback Canary, Dulcet Fixtures · Threshold Boundary, Track",
            "Threshold Boundary, Dulcet Fixtures, Album",
        ]
        let rankedTitles = [
            "Thirty One Seconds",
            "Twenty Nine Seconds",
            "UI Playback Canary",
            "Threshold Boundary",
        ]
        // Only tracks are playable, so the activated queue is the ranked list without its album
        // row. The canary's position in that queue and its rendered rank are separate facts and
        // are asserted separately.
        let queueTitles = ["Thirty One Seconds", "Twenty Nine Seconds", "UI Playback Canary"]
        let canaryQueueIndex = 2
        // Apply the constrained frame while search is idle. Applying this same frame after
        // loading retains already-measured rows and misses the minimum-height regression.
        window.setFrame(NSRect(x: 0, y: 60, width: 1024, height: 677), display: true)
        hostingView.layoutSubtreeIfNeeded()
        reportSearchRealization(root: hostingView, phase: "before-typing")
        try sendText(query, to: window)
        try await waitUntil(
            timeout: .seconds(5),
            failureMessage: "dulcet.search.field typed=\(query.debugDescription) bound=\(store.searchQuery.debugDescription) native=\(searchField.stringValue.debugDescription)"
        ) {
            store.searchQuery == query
        }
        XCTAssertEqual(
            accessibilityValue(searchFieldElement) as? String,
            query,
            "dulcet.search.field AX value after NSApp.sendEvent"
        )

        try await waitUntil(
            timeout: .seconds(20),
            failureMessage: "Search state=\(store.snapshot.state) resultCount=\(store.snapshot.searchResults.count) titles=\(store.snapshot.searchResults.map(\.title)); expected \(rankedTitles)"
        ) {
            store.snapshot.state == .searchResults
                && store.snapshot.searchResults.map(\.title) == rankedTitles
        }
        XCTAssertEqual(store.snapshot.searchResults.map(\.title), rankedTitles,
            "Rendered search rank order")
        XCTAssertEqual(store.snapshot.searchResults.count, rankedTitles.count,
            "Exact fixture query result count")
        hostingView.layoutSubtreeIfNeeded()
        reportSearchRealization(root: hostingView, phase: "results-without-recovery")
        let navigationSplit = try XCTUnwrap(
            accessibilityDescendants(in: hostingView).compactMap { $0 as? NSSplitView }.first,
            "The production navigation split must exist"
        )
        let navigationRect = navigationSplit.convert(navigationSplit.bounds, to: hostingView)
        XCTAssertGreaterThanOrEqual(navigationRect.minY, hostingView.bounds.minY,
            "Navigation must not center an oversized minimum height above the hosting root")
        XCTAssertLessThanOrEqual(navigationRect.maxY, hostingView.bounds.maxY,
            "Navigation must fit the hosting root before any result lookup")
        // Every rank is resolved by its own identifier and checked against the row that belongs
        // there. A view that stamped one constant identifier on every row would satisfy rank zero
        // and then fail to produce rank one at all.
        var rankedElements: [Any] = []
        for rank in rankedLabels.indices {
            let element = try await accessibilityElement(
                identifiedBy: "dulcet.search.result.\(rank)",
                in: hostingView,
                timeout: .seconds(5)
            )
            XCTAssertEqual(
                accessibilityLabel(element),
                rankedLabels[rank],
                "dulcet.search.result.\(rank) rendered accessibility text (title, credits, album, kind)"
            )
            rankedElements.append(element)
        }
        XCTAssertNotEqual(
            accessibilityLabel(rankedElements[0]),
            rankedLabels[canaryRank],
            "The canary must not render at rank zero, or this proof cannot tell rank from arity"
        )

        let canaryResult = try XCTUnwrap(
            store.snapshot.searchResults.indices.contains(canaryRank)
                ? store.snapshot.searchResults[canaryRank]
                : nil,
            "Search titles=\(store.snapshot.searchResults.map(\.title)); missing rank \(canaryRank)"
        )
        XCTAssertEqual(canaryResult.title, canaryTitle, "Rank \(canaryRank) identity")
        let canaryID = canaryResult.id
        XCTAssertEqual(playback.queueReplacementCount, 0,
            "Rendering the search results must not start a queue before activation")
        let resultTable = try selectAccessibilityTableRow(rankedElements[canaryRank], in: window)
        XCTAssertEqual(resultTable.numberOfRows, rankedTitles.count, "Rendered search table row count")
        XCTAssertEqual(resultTable.selectedRow, canaryRank,
            "Selecting dulcet.search.result.\(canaryRank) must select that rank's row, not another")
        XCTAssertTrue(window.makeFirstResponder(resultTable),
            "dulcet.search.result.\(canaryRank) table rejected focus; responder=\(String(describing: window.firstResponder))")
        try sendKey(.returnKey, to: window)
        try await waitUntil(
            timeout: .seconds(10),
            failureMessage: "Return on dulcet.search.result.\(canaryRank): queueReplacements=\(playback.queueReplacementCount) state=\(store.snapshot.state) nowPlaying=\(String(describing: store.snapshot.nowPlaying?.current.title)); expected one search queue and \(canaryTitle)"
        ) {
            store.snapshot.state == .nowPlaying
                && store.snapshot.nowPlaying?.current.title == canaryTitle
        }

        XCTAssertEqual(playback.queueReplacementCount, 1,
            "Return on rank \(canaryRank) must replace/play exactly one queue")
        let intent = try XCTUnwrap(playback.lastIntent,
            "Return on dulcet.search.result.\(canaryRank): no playback intent; queueReplacements=\(playback.queueReplacementCount)")
        XCTAssertEqual(intent.sourceKind, .search, "Activated queue source kind")
        XCTAssertNil(intent.sourceID, "Search queue must not carry a container source ID")
        XCTAssertEqual(intent.sourceDisplayName, "Search", "Activated queue source display name")
        XCTAssertEqual(intent.tracks.map(\.title), queueTitles,
            "Activated queue must be every playable result in rendered rank order")
        // A start index of zero here would mean activation played the first result rather than
        // the row that was pressed. That is the whole point of pressing a non-zero rank.
        let startIndex = try XCTUnwrap(intent.startIndex,
            "Activated queue carries no start index; titles=\(intent.tracks.map(\.title))")
        XCTAssertEqual(startIndex, canaryQueueIndex,
            "Activated queue must start at the pressed row, not at rank zero")
        let startedTrack = try XCTUnwrap(
            intent.tracks.indices.contains(startIndex) ? intent.tracks[startIndex] : nil,
            "Activated queue startIndex=\(startIndex) titles=\(intent.tracks.map(\.title))"
        )
        XCTAssertEqual(startedTrack.id, canaryID, "Queue identity at the start index must be the pressed row")
        XCTAssertEqual(startedTrack.title, canaryTitle, "Activated queue start title")
        hostingView.layoutSubtreeIfNeeded()
        let nowPlayingTitle = try await accessibilityElement(
            identifiedBy: "dulcet.now-playing.title",
            in: hostingView,
            timeout: .seconds(5)
        )
        XCTAssertEqual(accessibilityLabel(nowPlayingTitle), canaryTitle, "dulcet.now-playing.title rendered text")
        print(
            "MACOS SEARCH UI OBSERVED query=typed ranks=\(rankedTitles)"
                + " result-count=\(resultTable.numberOfRows) activated-rank=\(canaryRank) activation=return"
                + " queue=\(intent.tracks.map(\.title)) start-index=\(startIndex)"
                + " queue-replacements=\(playback.queueReplacementCount) source=search now-playing=\(canaryTitle)"
        )
    }

    func accountConnectKeyboardTraversalFocusRestorationAndPrimaryAction() async throws {
        guard try await assertDefaultActionShortcutBisection() else {
            return
        }

        let request = DulcetAccountConnectRequest(
            serverURL: "https://music.example.invalid",
            username: "listener",
            password: "fixture-password",
            allowLocalHTTP: true
        )
        let connector = KeyboardTraceAccountConnector()
        let source = DulcetAccountDataSource(
            connector: connector,
            initialRequest: request
        )
        let store = DulcetPresentationStore(source: source)
        var observedFocus: DulcetAccountConnectionFocus?
        var focusTrace: [DulcetAccountConnectionFocus] = []
        let hostingView = NSHostingView(rootView: DulcetAccountConnectionView(
            store: store,
            focusDidChange: {
                observedFocus = $0
                if let focus = $0, focusTrace.last != focus {
                    focusTrace.append(focus)
                }
            }
        ))
        hostingView.frame = NSRect(x: 0, y: 0, width: 800, height: 650)
        let window = NSWindow(
            contentRect: hostingView.frame,
            styleMask: [.titled, .closable, .resizable],
            backing: .buffered,
            defer: false
        )
        window.isReleasedWhenClosed = false
        window.contentView = hostingView
        window.makeKeyAndOrderFront(nil)
        defer { window.close() }
        hostingView.layoutSubtreeIfNeeded()
        XCTAssertTrue(
            NSApplication.shared.isFullKeyboardAccessEnabled,
            "apple-ci must enable macOS Full Keyboard Access for all-control traversal"
        )

        try await waitUntil(
            timeout: .seconds(5),
            failureMessage: "the account-connect surface did not initially focus Server Address"
        ) {
            observedFocus == .serverAddress
        }

        for expected in [
            DulcetAccountConnectionFocus.username,
            .password,
            .allowLocalHTTP,
            .primaryAction,
        ] {
            try sendKey(.tab, to: window)
            try await waitUntil(
                timeout: .seconds(2),
                failureMessage: "Tab did not move account-connect focus to \(expected.rawValue)"
            ) {
                observedFocus == expected
            }
        }

        for expected in [
            DulcetAccountConnectionFocus.allowLocalHTTP,
            .password,
            .username,
            .serverAddress,
        ] {
            try sendKey(.tab, modifiers: .shift, to: window)
            try await waitUntil(
                timeout: .seconds(2),
                failureMessage: "Shift-Tab did not move account-connect focus to \(expected.rawValue)"
            ) {
                observedFocus == expected
            }
        }
        for expected in [
            DulcetAccountConnectionFocus.username,
            .password,
            .allowLocalHTTP,
            .primaryAction,
        ] {
            try sendKey(.tab, to: window)
            try await waitUntil(
                timeout: .seconds(2),
                failureMessage: "Tab did not return account-connect focus to \(expected.rawValue)"
            ) {
                observedFocus == expected
            }
        }

        try sendKey(.returnKey, to: window)
        try await waitUntil(
            timeout: .seconds(2),
            failureMessage:
                "Return state mismatch; expected=\(DulcetPresentationState.accountConnecting.rawValue)"
                + " actual=\(store.snapshot.state.rawValue)"
        ) {
            store.snapshot.state == .accountConnecting
        }
        try await waitUntil(
            timeout: .seconds(2),
            failureMessage:
                "Return request mismatch; expected=\(requestDiagnostic([request]))"
                + " actual=\(requestDiagnostic(connector.requests))"
        ) {
            connector.requests == [request]
        }
        try await waitUntil(
            timeout: .seconds(2),
            failureMessage:
                "Return focus mismatch; expected=\(DulcetAccountConnectionFocus.primaryAction.rawValue)"
                + " actual=\(observedFocus?.rawValue ?? "nil")"
        ) {
            observedFocus == .primaryAction
        }

        try sendKey(.escape, to: window)
        try await waitUntil(
            timeout: .seconds(2),
            failureMessage:
                "Escape did not invoke the container's connecting-state Cancel behavior"
        ) {
            store.snapshot.state == .accountConnectIdle
                && connector.operation.cancelCount == 1
                && connector.requests == [request]
                && observedFocus == .primaryAction
        }

        // Exact equality rejects any transient repair to another control after Return. The single
        // primaryAction entry spans Connect and Cancel; the Escape assertion above proves that the
        // container command cancels without another submission while that focus identity persists.
        let expectedTrace: [DulcetAccountConnectionFocus] = [
            .serverAddress,
            .username,
            .password,
            .allowLocalHTTP,
            .primaryAction,
            .allowLocalHTTP,
            .password,
            .username,
            .serverAddress,
            .username,
            .password,
            .allowLocalHTTP,
            .primaryAction,
        ]
        XCTAssertEqual(focusTrace, expectedTrace)
        print(
            "ACCOUNT CONNECT KEYBOARD TRACE focus="
                + focusTrace.map(\.rawValue).joined(separator: ">")
                + " actions=return:connect>escape:cancel"
        )
    }

    func accountConnectDoubleReturnKeepsSingleConnectionActive() async throws {
        let request = DulcetAccountConnectRequest(
            serverURL: "https://music.example.invalid",
            username: "listener",
            password: "fixture-password",
            allowLocalHTTP: true
        )
        let connector = KeyboardTraceAccountConnector()
        let source = DulcetAccountDataSource(
            connector: connector,
            initialRequest: request
        )
        let store = DulcetPresentationStore(source: source)
        var observedFocus: DulcetAccountConnectionFocus?
        let hostingView = NSHostingView(rootView: DulcetAccountConnectionView(
            store: store,
            focusDidChange: { observedFocus = $0 }
        ))
        hostingView.frame = NSRect(x: 0, y: 0, width: 800, height: 650)
        let window = NSWindow(
            contentRect: hostingView.frame,
            styleMask: [.titled, .closable, .resizable],
            backing: .buffered,
            defer: false
        )
        window.isReleasedWhenClosed = false
        window.contentView = hostingView
        window.makeKeyAndOrderFront(nil)
        defer { window.close() }
        hostingView.layoutSubtreeIfNeeded()
        XCTAssertTrue(
            NSApplication.shared.isFullKeyboardAccessEnabled,
            "apple-ci must enable macOS Full Keyboard Access for all-control traversal"
        )

        try await waitUntil(
            timeout: .seconds(5),
            failureMessage: "the double-Return control did not initially focus Server Address"
        ) {
            observedFocus == .serverAddress
        }

        for expected in [
            DulcetAccountConnectionFocus.username,
            .password,
            .allowLocalHTTP,
            .primaryAction,
        ] {
            try sendKey(.tab, to: window)
            try await waitUntil(
                timeout: .seconds(2),
                failureMessage: "Tab did not move double-Return focus to \(expected.rawValue)"
            ) {
                observedFocus == expected
            }
        }

        // Keep the activations back-to-back: the control exercises the real event path without
        // waiting for SwiftUI to settle the connecting presentation between the two key presses.
        try sendKey(.returnKey, to: window)
        try sendKey(.returnKey, to: window)
        try await Task.sleep(for: .milliseconds(100))

        XCTAssertEqual(
            store.snapshot.state,
            .accountConnecting,
            "a second Return must leave the submitted connection active"
        )
        XCTAssertEqual(
            connector.requests,
            [request],
            "a second Return must not submit a replacement connection; actual=\(requestDiagnostic(connector.requests))"
        )
        XCTAssertEqual(
            connector.operation.cancelCount,
            0,
            "a second Return must not cancel the connection it just started"
        )
        XCTAssertEqual(
            observedFocus,
            .primaryAction,
            "the primary action must retain focus across both Return activations"
        )

        try sendKey(.escape, to: window)
        try await waitUntil(
            timeout: .seconds(2),
            failureMessage: "Escape did not cancel the surviving double-Return connection exactly once"
        ) {
            store.snapshot.state == .accountConnectIdle
                && connector.operation.cancelCount == 1
                && connector.requests == [request]
                && observedFocus == .primaryAction
        }

        print(
            "ACCOUNT CONNECT DOUBLE RETURN actions=return:connect>return:ignored>escape:cancel"
                + " requests=1 cancellations=1"
        )
    }

    func connectSuccessCrossesLiveKotlinFacadeIntoPersistenceFailureState() async throws {
        let baseURL = try XCTUnwrap(
            ProcessInfo.processInfo.environment["DULCET_CONFORMANCE_BASE_URL"],
            "apple-ci must supply the live conformance fixture URL"
        )
        let request = DulcetAccountConnectRequest(
            serverURL: baseURL,
            username: fixtureUsername,
            password: fixturePassword,
            allowLocalHTTP: true
        )
        UserDefaults.standard.removeObject(forKey: activeAccountKey)
        defer { UserDefaults.standard.removeObject(forKey: activeAccountKey) }
        let credentialStore = DulcetKeychainCredentialStore()

        let store = DulcetMacProduction.makePresentationStore()
        store.accountServerURL = request.serverURL
        store.accountUsername = request.username
        store.accountPassword = request.password
        store.accountAllowLocalHTTP = request.allowLocalHTTP

        store.submitAccountConnection()
        XCTAssertEqual(store.snapshot.state, .accountConnecting)

        try await waitUntil(
            timeout: .seconds(20),
            failureMessage: "the live account connection did not complete before the test deadline"
        ) {
            store.snapshot.state != .accountConnecting
        }

        XCTAssertEqual(store.snapshot.state, .accountErrorPersistence)
        XCTAssertFalse(store.snapshot.accountConnected)
        guard case let .failed(failure) = store.snapshot.accountConnection else {
            return XCTFail("the live success did not reach the production persistence boundary")
        }
        XCTAssertEqual(failure.kind, .credentialPersistenceFailed)
        guard case let .connectionFailed(.account(connectivityFailure)) =
            store.snapshot.connectivity else {
            return XCTFail("the persistence failure did not reach production connectivity state")
        }
        XCTAssertEqual(connectivityFailure.kind, .credentialPersistenceFailed)
        XCTAssertNil(UserDefaults.standard.string(forKey: activeAccountKey))
        XCTAssertNil(try credentialStore.load())

        var snapshotDump = ""
        dump(store.snapshot, to: &snapshotDump)
        let diagnosticStrings = [
            String(describing: store.snapshot),
            String(reflecting: store.snapshot),
            snapshotDump,
        ]
        for value in [request.serverURL, request.username, request.password] {
            XCTAssertTrue(
                diagnosticStrings.allSatisfy { !$0.contains(value) },
                "a credential-bearing value escaped snapshot redaction"
            )
        }
    }

    func librarySyncUsesCommittedGenerationsSchedulesRefreshAndReopensOffline() async throws {
        let baseURL = try XCTUnwrap(
            ProcessInfo.processInfo.environment["DULCET_CONFORMANCE_BASE_URL"],
            "apple-ci must supply the live disposable conformance fixture URL"
        )
        let databaseName = "library-sync-app-\(UUID().uuidString).db"
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
        // The fast preview is what removes the wait; asserting only the committed result would
        // pass whether or not it ran. Within one open a preview can only arrive before the
        // commit, so an out-of-order pair means the preview lost its race and was published on
        // top of the committed library.
        // Why an exact sequence and not just an invariant. Within one open a preview can only be
        // delivered before the commit, because a late one is discarded at both ends. That the
        // preview WINS is a race assertion, not a guarantee: it issues far fewer requests than
        // the sync, but on a SEPARATE HTTP client, and this repository has measured a 30.3 s
        // loopback stall — so one stalled preview request could lose to an entire sync. Keeping
        // the assertion is deliberate: the preview losing is the feature not working, which is
        // worth failing on rather than tolerating.
        XCTAssertEqual(library.publicationOrder, ["preview", "committed"])
        // Order-free invariant, so a future reordering cannot quietly retire the control above.
        // This is a PAIRING, not a count: it rejects ["committed", "preview"] — which a count
        // equality accepts — and it requires at least one pair, so it cannot pass vacuously.
        XCTAssertFalse(library.publicationOrder.isEmpty)
        XCTAssertEqual(
            stride(from: 0, to: library.publicationOrder.count, by: 2).map {
                Array(library.publicationOrder[$0 ..< min($0 + 2, library.publicationOrder.count)])
            }.filter { $0 != ["preview", "committed"] },
            []
        )
        assertDisplayedLibrary(store.snapshot, equals: firstCommitted.library)
        // The grid must not reshuffle when the committed publication replaces the preview. The
        // two came from different sources with different collations and nothing compared them.
        XCTAssertEqual(
            library.previewAlbumOrder,
            firstCommitted.library.albums.map(\.rawId)
        )
        XCTAssertEqual(refreshScheduler.scheduledCount, 1)
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
        XCTAssertEqual(secondCommitted.generation, 2)
        XCTAssertEqual(library.startedSyncCount, 2)
        XCTAssertEqual(library.displayedCommittedGenerations, [1, 2])
        XCTAssertEqual(
            library.publicationOrder,
            ["preview", "committed", "preview", "committed"]
        )
        // Order-free invariant, so a future reordering cannot quietly retire the control above.
        // This is a PAIRING, not a count: it rejects ["committed", "preview"] — which a count
        // equality accepts — and it requires at least one pair, so it cannot pass vacuously.
        XCTAssertFalse(library.publicationOrder.isEmpty)
        XCTAssertEqual(
            stride(from: 0, to: library.publicationOrder.count, by: 2).map {
                Array(library.publicationOrder[$0 ..< min($0 + 2, library.publicationOrder.count)])
            }.filter { $0 != ["preview", "committed"] },
            []
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
            // The saved-account read below is credited only after this configured endpoint fails.
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
        // A saved-account reopen reads the committed library only: no server is contacted, so
        // it publishes once and there is no preview at all.
        XCTAssertEqual(reopenedLibrary.publicationOrder, ["committed"])
        XCTAssertEqual(reopenedLibrary.deliveredPreviewCount, 0)
        assertDisplayedLibrary(reopenedStore.snapshot, equals: secondCommitted.library)

        print(
            "LIBRARY SYNC APP INTEGRATION"
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

    private func assertDefaultActionShortcutBisection() async throws -> Bool {
        for variant in DefaultActionShortcutVariant.allCases {
            let fired = try await defaultActionShortcutFires(variant)
            print(
                "ACCOUNT CONNECT DEFAULT ACTION BISECTION variant=\(variant.rawValue)"
                    + " added=\(variant.addedAttribute)"
                    + " result=\(fired ? "fired" : "did-not-fire")"
            )
            if case .baseline = variant, fired {
                print("ACCOUNT CONNECT KEYBOARD POSITIVE CONTROL defaultAction=return:fired")
            }
            guard fired else {
                XCTFail(
                    "Default-action bisection first stopped at \(variant.rawValue)"
                        + " after adding \(variant.addedAttribute)"
                )
                return false
            }
        }
        return true
    }

    private func defaultActionShortcutFires(
        _ variant: DefaultActionShortcutVariant
    ) async throws -> Bool {
        let probe = DefaultActionShortcutProbe()
        let hostingView = NSHostingView(rootView: DefaultActionShortcutBisectionControl(
            variant: variant,
            fire: probe.fire,
            markAppeared: probe.markAppeared,
            markFocused: probe.markFocused
        ))
        hostingView.frame = NSRect(x: 0, y: 0, width: 400, height: 200)
        let window = NSWindow(
            contentRect: hostingView.frame,
            styleMask: [.titled, .closable],
            backing: .buffered,
            defer: false
        )
        window.isReleasedWhenClosed = false
        window.contentView = hostingView
        window.makeKeyAndOrderFront(nil)
        defer { window.close() }
        hostingView.layoutSubtreeIfNeeded()

        try await waitUntil(
            timeout: .seconds(2),
            failureMessage: "the \(variant.rawValue) shortcut control did not become ready in the key window"
        ) {
            probe.didAppear
                && window.isKeyWindow
                && (!variant.requiresFocus || probe.didFocus)
        }
        guard probe.didAppear,
              window.isKeyWindow,
              !variant.requiresFocus || probe.didFocus else {
            return false
        }
        try sendKey(.returnKey, to: window)

        let clock = ContinuousClock()
        let deadline = clock.now.advanced(by: .seconds(2))
        while probe.fireCount == 0 && clock.now < deadline {
            try await Task.sleep(for: .milliseconds(50))
        }
        return probe.fireCount == 1
    }

    private func requestDiagnostic(
        _ requests: [DulcetAccountConnectRequest]
    ) -> String {
        let entries = requests.map { request in
            // The password is deliberately reduced to a length. This repository has a control
            // forbidding credential values in diagnostics, and CI logs are the widest surface here;
            // server, username and the local-HTTP flag already identify which request was submitted.
            "{serverURL=\(request.serverURL.debugDescription),"
                + " username=\(request.username.debugDescription),"
                + " password=<redacted length=\(request.password.count)>,"
                + " allowLocalHTTP=\(request.allowLocalHTTP)}"
        }
        .joined(separator: ", ")
        return "[" + entries + "]"
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
                return
            }
            try await Task.sleep(for: .milliseconds(50))
        }
    }

    private func sendKey(
        _ key: KeyboardKey,
        modifiers: NSEvent.ModifierFlags = [],
        to window: NSWindow
    ) throws {
        let characters: String
        switch (key, modifiers.contains(.shift)) {
        case (.tab, true):
            characters = "\u{19}" // NSBackTabCharacter
        default:
            characters = key.characters
        }

        for eventType in [NSEvent.EventType.keyDown, .keyUp] {
            let event = try XCTUnwrap(NSEvent.keyEvent(
                with: eventType,
                location: .zero,
                modifierFlags: modifiers,
                timestamp: ProcessInfo.processInfo.systemUptime,
                windowNumber: window.windowNumber,
                context: nil,
                characters: characters,
                charactersIgnoringModifiers: characters,
                isARepeat: false,
                keyCode: key.keyCode
            ), "Could not construct \(eventType) keyCode=\(key.keyCode) window=\(window.windowNumber)")
            NSApp.sendEvent(event)
        }
    }

    private func sendText(_ text: String, to window: NSWindow) throws {
        for character in text {
            let value = String(character)
            for eventType in [NSEvent.EventType.keyDown, .keyUp] {
                let event = try XCTUnwrap(NSEvent.keyEvent(
                    with: eventType,
                    location: .zero,
                    modifierFlags: [],
                    timestamp: ProcessInfo.processInfo.systemUptime,
                    windowNumber: window.windowNumber,
                    context: nil,
                    characters: value,
                    charactersIgnoringModifiers: value,
                    isARepeat: false,
                    keyCode: 0
                ), "Could not construct search \(eventType) character=\(value.debugDescription) window=\(window.windowNumber)")
                NSApp.sendEvent(event)
            }
        }
    }

    private func accessibilityElement(
        identifiedBy identifier: String,
        in root: NSView,
        timeout: Duration
    ) async throws -> Any {
        let clock = ContinuousClock()
        let deadline = clock.now.advanced(by: timeout)
        repeat {
            root.layoutSubtreeIfNeeded()
            if let match = accessibilityDescendants(in: root).first(where: {
                accessibilityIdentifier($0) == identifier
            }) {
                return match
            }
            try await Task.sleep(for: .milliseconds(50))
        } while clock.now < deadline
        let elements = accessibilityDescendants(in: root)
        reportSearchRealization(root: root, phase: "lookup-timeout")
        let diagnostic = elements.map {
            "\(type(of: $0)):id=\(accessibilityIdentifier($0) ?? "nil"):label=\(accessibilityLabel($0) ?? "nil")"
        }.joined(separator: "; ")
        throw SearchHostedAppTestError.missingAccessibilityElement(
            "\(identifier); count=\(elements.count); geometry=\(realizationGeometryDiagnostic(root: root, elements: elements)); tree=\(diagnostic)"
        )
    }

    /// Compact legacy summary. See reportSearchRealization for native rows and ancestor clipping;
    /// documentVisibleRect and the presence of AX row proxies do not establish realization.
    private func realizationGeometryDiagnostic(root: NSView, elements: [Any]) -> String {
        let table = elements.first {
            String(describing: type(of: $0)).contains("OutlineTableView")
        } as? NSTableView
        let tableDescription: String
        if let table {
            let visible = table.enclosingScrollView?.documentVisibleRect ?? table.visibleRect
            tableDescription = "tableFrame=\(table.frame) numberOfRows=\(table.numberOfRows)"
                + " documentVisibleRect=\(visible)"
        } else {
            tableDescription = "no-table-resolved"
        }
        let realizedRanks = elements.compactMap { accessibilityIdentifier($0) }
            .filter { $0.hasPrefix("dulcet.search.result.") }
            .sorted()
        return "window=\(root.window.map { "\($0.frame)" } ?? "no-window") hostingView=\(root.frame)"
            + " backingScaleFactor=\(root.window?.backingScaleFactor ?? -1) \(tableDescription)"
            + " realizedRanks=\(realizedRanks)"
    }

    /// Observation only: never ask AppKit to manufacture a row or cell. AX getters can themselves
    /// materialize AX proxies, so native row/cell presence is sampled BEFORE walking those getters.
    /// Keep row indices, object identities, and graph paths: the lookup walk's LIFO order is not
    /// visual order, and an NSOutlineRow proxy alone does not prove a native row view exists.
    private func reportSearchRealization(root: NSView, phase: String) {
        func tag(_ value: Any) -> String {
            "\(type(of: value))@\(ObjectIdentifier(value as AnyObject))"
        }
        func nativeViews(_ view: NSView) -> [NSView] {
            [view] + view.subviews.flatMap { nativeViews($0) }
        }
        let prefix = "MACOS SEARCH PROBE \(phase)"
        let window = root.window
        print("\(prefix) os=\(ProcessInfo.processInfo.operatingSystemVersionString) window=\(String(describing: window?.frame)) root=\(root.frame) screen=\(String(describing: window?.screen?.frame)) screenVisible=\(String(describing: window?.screen?.visibleFrame)) scale=\(window?.backingScaleFactor ?? -1)")
        let tables = nativeViews(root).compactMap { $0 as? NSTableView }
        var nativeAnchors: [(Any, String)] = []
        for table in tables {
            print("\(prefix) TABLE \(tag(table)) rows=\(table.numberOfRows) columns=\(table.numberOfColumns) rowHeight=\(table.rowHeight) automaticHeights=\(table.usesAutomaticRowHeights) rowsInVisible=\(table.rows(in: table.visibleRect)) bounds=\(table.bounds) visible=\(table.visibleRect) documentVisible=\(String(describing: table.enclosingScrollView?.documentVisibleRect))")
            var ancestor: NSView? = table
            while let view = ancestor {
                print("\(prefix) ANCESTOR \(tag(view)) frame=\(view.frame) bounds=\(view.bounds) visible=\(view.visibleRect) flipped=\(view.isFlipped) hidden=\(view.isHiddenOrHasHiddenAncestor) windowRect=\(view.convert(view.bounds, to: nil))")
                ancestor = view.superview
            }
            for row in 0..<table.numberOfRows {
                let rowView = table.rowView(atRow: row, makeIfNecessary: false)
                if let rowView { nativeAnchors.append((rowView, "nativeTable[\(tag(table))].row[\(row)]")) }
                let rect = table.rect(ofRow: row)
                let screenRect = window?.convertToScreen(table.convert(rect, to: nil)) ?? .zero
                print("\(prefix) ROW table=\(tag(table)) index=\(row) rect=\(rect) screenRect=\(screenRect) intersectsVisible=\(rect.intersects(table.visibleRect)) native=\(rowView.map { tag($0) } ?? "nil") group=\(String(describing: rowView?.isGroupRowStyle))")
                for column in 0..<table.numberOfColumns {
                    let cell = table.view(atColumn: column, row: row, makeIfNecessary: false)
                    if let cell { nativeAnchors.append((cell, "nativeTable[\(tag(table))].row[\(row)].cell[\(column)]")) }
                    print("\(prefix) CELL table=\(tag(table)) row=\(row) column=\(column) native=\(cell.map { tag($0) } ?? "nil")")
                }
            }
        }
        let original = accessibilityDescendants(in: root)
        let before = original.compactMap { accessibilityIdentifier($0) }
            .filter { $0.hasPrefix("dulcet.search.result.") }.sorted()
        print("\(prefix) beforeGraphRanks=\(before)")
        let originalIDs = Set(original.map { ObjectIdentifier($0 as AnyObject) })
        // Retain objects throughout the traversal, preventing temporary proxy addresses from reuse.
        var retained: [Any] = []
        var visited = Set<ObjectIdentifier>()
        var pending: [(Any, String)] = [(root, "root"), (NSApp, "application")]
        pending += NSApp.windows.enumerated().map { ($0.element, "window[\($0.offset)]") }
        pending += nativeAnchors
        let edges = ["accessibilityChildren", "accessibilityRows", "accessibilityVisibleRows",
                     "accessibilityColumns", "accessibilityContents"]
        while let (current, path) = pending.popLast() {
            guard visited.insert(ObjectIdentifier(current as AnyObject)).inserted else { continue }
            retained.append(current)
            let identifier = accessibilityIdentifier(current)
            let label = accessibilityLabel(current)
            let frame = (current as? NSAccessibilityElementProtocol)?.accessibilityFrame()
            let parent = accessibilityObjectValue("accessibilityParent", of: current)
            print("\(prefix) NODE \(tag(current)) path=\(path) inLookup=\(originalIDs.contains(ObjectIdentifier(current as AnyObject))) id=\(identifier ?? "nil") label=\(label ?? "nil") role=\(String(describing: accessibilityObjectValue("accessibilityRole", of: current))) frame=\(String(describing: frame)) parent=\(parent.map { tag($0) } ?? "nil")")
            var links: [(Any, String)] = []
            for edge in edges {
                let children = accessibilityObjectValue(edge, of: current) as? [Any] ?? []
                for (index, child) in children.enumerated() {
                    print("\(prefix) EDGE \(tag(current)) \(edge)[\(index)]=\(tag(child))")
                    links.append((child, "\(path).\(edge)[\(index)]"))
                }
            }
            if let view = current as? NSView {
                links += view.subviews.enumerated().map { ($0.element, "\(path).subviews[\($0.offset)]") }
            }
            pending.append(contentsOf: links.reversed())
        }
        let after = accessibilityDescendants(in: root).compactMap { accessibilityIdentifier($0) }
            .filter { $0.hasPrefix("dulcet.search.result.") }.sorted()
        print("\(prefix) END nodes=\(retained.count) afterProbeRanks=\(after)")
        for table in tables {
            let rows = (0..<table.numberOfRows).map {
                "\($0):\(table.rowView(atRow: $0, makeIfNecessary: false).map { tag($0) } ?? "nil")"
            }
            print("\(prefix) afterProbeNative table=\(tag(table)) rows=\(rows)")
        }
    }

    private func accessibilityDescendants(in root: Any) -> [Any] {
        var result: [Any] = []
        var visited = Set<ObjectIdentifier>()
        var pending: [Any] = [root]
        while let current = pending.popLast() {
            guard let object = current as AnyObject? else { continue }
            let identity = ObjectIdentifier(object)
            guard visited.insert(identity).inserted else { continue }
            var children = accessibilityObjectValue("accessibilityChildren", of: current) as? [Any] ?? []
            if let view = current as? NSView {
                children.append(contentsOf: view.subviews)
            }
            result.append(current)
            pending.append(contentsOf: children)
        }
        return result
    }

    // SwiftUI AccessibilityNode implements these Objective-C getters without conforming to
    // the complete NSAccessibilityProtocol. It is neither NSView nor NSAccessibilityElement.
    // Check the public selector instead of dropping such nodes or naming a private SwiftUI type.
    // Object-valued getters only: struct/scalar returns must never go through perform(_:).
    private func accessibilityObjectValue(_ name: String, of element: Any) -> Any? {
        guard let object = element as? NSObject else { return nil }
        let selector = NSSelectorFromString(name)
        guard object.responds(to: selector) else { return nil }
        return object.perform(selector)?.takeUnretainedValue()
    }

    private func accessibilityIdentifier(_ element: Any) -> String? {
        accessibilityObjectValue("accessibilityIdentifier", of: element) as? String
    }

    private func accessibilityLabel(_ element: Any) -> String? {
        accessibilityObjectValue("accessibilityLabel", of: element) as? String
            ?? accessibilityObjectValue("accessibilityTitle", of: element) as? String
            ?? accessibilityValue(element) as? String
    }

    private func accessibilityValue(_ element: Any) -> Any? {
        accessibilityObjectValue("accessibilityValue", of: element)
    }

    private func selectAccessibilityTableRow(_ element: Any, in window: NSWindow) throws -> NSTableView {
        let point = try accessibilityWindowPoint(element, in: window)
        let content = try XCTUnwrap(window.contentView, "Hosted window has no content")
        let table = try XCTUnwrap(content.hitTest(content.convert(point, from: nil)) as? NSTableView,
            "Expected table at \(point) for \(accessibilityIdentifier(element) ?? "nil")")
        let index = table.row(at: table.convert(point, from: nil))
        // The SDK types this as [NSAccessibilityRow], but AppKit actually returns NSOutlineRow
        // objects that fail Swift's protocol-array bridge. Preserve the Objective-C object array.
        let rows = try XCTUnwrap(accessibilityObjectValue("accessibilityRows", of: table) as? [Any],
            "No accessibility rows for \(accessibilityIdentifier(element) ?? "nil")")
        guard rows.indices.contains(index) else {
            throw SearchHostedAppTestError.missingAccessibilityElement("\(accessibilityIdentifier(element) ?? "nil"): row=\(index) count=\(rows.count)")
        }
        let selectRows = NSSelectorFromString("setAccessibilitySelectedRows:")
        guard table.responds(to: selectRows) else {
            throw SearchHostedAppTestError.missingAccessibilityElement(
                "\(accessibilityIdentifier(element) ?? "nil"): table lacks setAccessibilitySelectedRows:"
            )
        }
        table.perform(selectRows, with: [rows[index]])
        XCTAssertEqual(table.selectedRow, index, "Accessibility selection for \(accessibilityIdentifier(element) ?? "nil")")
        return table
    }

    private func accessibilityWindowPoint(_ element: Any, in window: NSWindow) throws -> NSPoint {
        let accessible = try XCTUnwrap(element as? any NSAccessibilityElementProtocol,
            "\(accessibilityIdentifier(element) ?? "nil") frame API unavailable on \(type(of: element))")
        let screenFrame = accessible.accessibilityFrame()
        XCTAssertFalse(screenFrame.isEmpty,
            "\(accessibilityIdentifier(element) ?? "<no identifier>") frame=\(screenFrame)")
        let point = window.convertPoint(fromScreen: NSPoint(x: screenFrame.midX, y: screenFrame.midY))
        return point
    }


}

private enum SearchHostedAppTestError: Error {
    case invalidFixture
    case missingAccessibilityElement(String)
}

@MainActor
private final class SearchMemoryCredentialStore: DulcetCredentialStoring {
    private(set) var credentialGeneration: Int64 = 0

    func load() throws -> DulcetAccountConnectRequest? { nil }

    func save(_ request: DulcetAccountConnectRequest) throws {
        credentialGeneration += 1
    }

    func delete() throws {
        credentialGeneration = 0
    }
}

@MainActor
private final class SearchIntentPlaybackController: DulcetPlaybackControlling {
    private var presentationHandler: (@MainActor (DulcetPlaybackPresentation) -> Void)?
    private(set) var currentPresentation: DulcetPlaybackPresentation = .unavailable
    private(set) var lastIntent: DulcetPlaybackQueueIntent?
    private(set) var queueReplacementCount = 0

    func setPresentationHandler(
        _ handler: @escaping @MainActor (DulcetPlaybackPresentation) -> Void
    ) {
        presentationHandler = handler
    }

    func configure(account: DulcetPlaybackAccount) {}
    func restorePersistedQueue(
        with tracks: [DulcetTrack],
        catalogCoverage: DulcetLibraryCatalogCoverage
    ) {}

    func replaceQueueAndPlay(_ intent: DulcetPlaybackQueueIntent) {
        queueReplacementCount += 1
        lastIntent = intent
        guard !intent.tracks.isEmpty else { return }
        let index = intent.startIndex ?? 0
        currentPresentation = DulcetPlaybackPresentation(
            status: .ready,
            nowPlaying: DulcetNowPlaying(
                current: intent.tracks[index],
                queue: intent.tracks,
                currentIndex: index,
                sourceDisplayName: intent.sourceDisplayName,
                elapsed: .zero,
                isPlaying: true,
                outputName: "Hosted app intent witness",
                volume: 1,
                audioFormat: DulcetAudioFormat(codec: "Fixture", sampleRateKilohertz: 0),
                phase: .ready,
                progressBegan: false
            )
        )
        presentationHandler?(currentPresentation)
    }

    func send(_ intent: DulcetPlaybackControlIntent) {}

    func disconnect() {
        currentPresentation = .unavailable
        presentationHandler?(currentPresentation)
    }
}

private enum KeyboardKey {
    case tab
    case returnKey
    case escape

    var characters: String {
        switch self {
        case .tab: "\t"
        case .returnKey: "\r"
        case .escape: "\u{1b}"
        }
    }

    var keyCode: UInt16 {
        switch self {
        case .tab: 48
        case .returnKey: 36
        case .escape: 53
        }
    }
}

private enum DefaultActionShortcutVariant: String, CaseIterable {
    case baseline
    case systemImage
    case borderedProminent
    case focusedConnect
    case disabledFalse

    var addedAttribute: String {
        switch self {
        case .baseline: "positive-control baseline"
        case .systemImage: "systemImage: initializer"
        case .borderedProminent: ".buttonStyle(.borderedProminent)"
        case .focusedConnect: ".focused(..., equals: .connect)"
        case .disabledFalse: ".disabled(false)"
        }
    }

    var requiresFocus: Bool {
        switch self {
        case .focusedConnect, .disabledFalse: true
        case .baseline, .systemImage, .borderedProminent: false
        }
    }
}

private enum DefaultActionBisectionFocus: Hashable {
    case connect
}

private struct DefaultActionShortcutBisectionControl: View {
    let variant: DefaultActionShortcutVariant
    let fire: () -> Void
    let markAppeared: () -> Void
    let markFocused: () -> Void

    @FocusState private var focusedControl: DefaultActionBisectionFocus?

    var body: some View {
        Group {
            switch variant {
            case .baseline:
                Button("Default Action Bisection", action: fire)
                    .keyboardShortcut(.defaultAction)
            case .systemImage:
                Button("Default Action Bisection", systemImage: "link", action: fire)
                    .keyboardShortcut(.defaultAction)
            case .borderedProminent:
                Button("Default Action Bisection", systemImage: "link", action: fire)
                    .buttonStyle(.borderedProminent)
                    .keyboardShortcut(.defaultAction)
            case .focusedConnect:
                Button("Default Action Bisection", systemImage: "link", action: fire)
                    .buttonStyle(.borderedProminent)
                    .keyboardShortcut(.defaultAction)
                    .focused($focusedControl, equals: .connect)
            case .disabledFalse:
                Button("Default Action Bisection", systemImage: "link", action: fire)
                    .buttonStyle(.borderedProminent)
                    .keyboardShortcut(.defaultAction)
                    .focused($focusedControl, equals: .connect)
                    .disabled(false)
            }
        }
        .onAppear {
            markAppeared()
            if variant.requiresFocus {
                focusedControl = .connect
            }
        }
        .onChange(of: focusedControl) { _, current in
            if current == .connect {
                markFocused()
            }
        }
    }
}

private final class DefaultActionShortcutProbe {
    private(set) var didAppear = false
    private(set) var didFocus = false
    private(set) var fireCount = 0

    func markAppeared() {
        didAppear = true
    }

    func markFocused() {
        didFocus = true
    }

    func fire() {
        fireCount += 1
    }
}

@MainActor
private final class KeyboardTraceAccountConnector: DulcetAccountConnecting {
    let operation = KeyboardTraceAccountOperation()
    private(set) var requests: [DulcetAccountConnectRequest] = []

    func connect(
        _ request: DulcetAccountConnectRequest,
        completion _: @escaping @MainActor (DulcetAccountConnectOutcome) -> Void
    ) -> any DulcetAccountConnectOperation {
        requests.append(request)
        return operation
    }
}

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

    func schedule(
        after delay: Duration,
        action: @escaping @MainActor () -> Void
    ) -> any DulcetLibraryRefreshOperation {
        scheduledCount += 1
        guard scheduledCount == 1 else {
            return InertLibraryRefreshOperation()
        }
        return scheduler.schedule(after: delay, action: action)
    }
}

@MainActor
private final class InertLibraryRefreshOperation: DulcetLibraryRefreshOperation {
    func cancel() {}
}
