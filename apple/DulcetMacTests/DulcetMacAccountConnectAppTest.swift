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
        assertDisplayedLibrary(store.snapshot, equals: firstCommitted.library)
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
            ))
            NSApp.sendEvent(event)
        }
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
