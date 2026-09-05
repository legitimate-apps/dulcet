import AppKit
import SwiftUI
import XCTest
@testable import DulcetKit
@testable import DulcetMac

@MainActor
final class DulcetMacAccountConnectAppTest: XCTestCase {
    private let activeAccountKey = "com.legitimateapps.dulcet.active-account-id"
    private let fixtureUsername = "dulcet-admin"
    private let fixturePassword = "dulcet-ci-canary-password"

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
            failureMessage: "the disposable account did not connect before the search UI test deadline"
        ) {
            store.snapshot.accountConnected
        }

        NSApp.accessibilitySetValue(true, forAttribute: NSAccessibility.Attribute(rawValue: "AXEnhancedUserInterface"))
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
            failureMessage: "clicking the Search sidebar row did not navigate to Search"
        ) {
            store.selectedDestination == .search
        }

        hostingView.layoutSubtreeIfNeeded()
        let searchFieldElement = try await accessibilityElement(
            identifiedBy: "dulcet.search.field",
            in: hostingView,
            timeout: .seconds(5)
        )
        try clickAccessibilityElement(searchFieldElement, in: window)
        let query = "UI Playback Canary"
        try sendText(query, to: window)
        try await waitUntil(
            timeout: .seconds(5),
            failureMessage: "the typed query did not reach the bound search field"
        ) {
            store.searchQuery == query
        }
        XCTAssertEqual(
            accessibilityValue(searchFieldElement) as? String,
            query,
            "the platform field itself must expose the query sent through key events"
        )

        try await waitUntil(
            timeout: .seconds(20),
            failureMessage: "the ranked live result did not render before the search deadline"
        ) {
            store.snapshot.state == .searchResults
                && store.snapshot.searchResults.first?.title == query
        }
        hostingView.layoutSubtreeIfNeeded()
        let firstResult = try await accessibilityElement(
            identifiedBy: "dulcet.search.result.0",
            in: hostingView,
            timeout: .seconds(5)
        )
        XCTAssertEqual(
            accessibilityLabel(firstResult),
            "UI Playback Canary, Dulcet Fixtures · Threshold Boundary, Track",
            "rank zero must be the rendered canary track, not merely a store value"
        )

        let resultTable = try selectAccessibilityTableRow(firstResult, in: window)
        XCTAssertTrue(window.makeFirstResponder(resultTable), "Rank-zero table must accept keyboard focus")
        try sendKey(.returnKey, to: window)
        try await waitUntil(
            timeout: .seconds(10),
            failureMessage: "Pressing Return on rank zero did not render its Now Playing intent"
        ) {
            store.snapshot.state == .nowPlaying
                && store.snapshot.nowPlaying?.current.title == query
        }

        let intent = try XCTUnwrap(playback.lastIntent)
        XCTAssertEqual(intent.sourceKind, .search)
        XCTAssertNil(intent.sourceID)
        XCTAssertEqual(intent.sourceDisplayName, "Search")
        XCTAssertEqual(intent.startIndex, 0)
        XCTAssertEqual(intent.tracks.first?.title, query)
        hostingView.layoutSubtreeIfNeeded()
        let nowPlayingTitle = try await accessibilityElement(
            identifiedBy: "dulcet.now-playing.title",
            in: hostingView,
            timeout: .seconds(5)
        )
        XCTAssertEqual(accessibilityLabel(nowPlayingTitle), query)
        print(
            "MACOS SEARCH UI OBSERVED query=typed rank0=rendered"
                + " activation=return source=search now-playing=\(query)"
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
                ))
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
        let diagnostic = elements.map {
            "\(type(of: $0)):id=\(accessibilityIdentifier($0) ?? "nil"):label=\(accessibilityLabel($0) ?? "nil")"
        }.joined(separator: "; ")
        throw SearchHostedAppTestError.missingAccessibilityElement(
            "\(identifier); count=\(elements.count); tree=\(diagnostic)"
        )
    }

    private func accessibilityDescendants(in root: Any) -> [Any] {
        var result: [Any] = [root]
        var visited = Set<ObjectIdentifier>()
        var pending: [Any] = [root]
        while let current = pending.popLast() {
            guard let object = current as AnyObject? else { continue }
            let identity = ObjectIdentifier(object)
            guard visited.insert(identity).inserted else { continue }
            var children = accessibilityAttribute(.children, of: current) as? [Any] ?? []
            if let view = current as? NSView {
                children.append(contentsOf: view.subviews)
            }
            result.append(contentsOf: children)
            pending.append(contentsOf: children)
        }
        return result
    }

    private func accessibilityAttribute(
        _ attribute: NSAccessibility.Attribute,
        of element: Any
    ) -> Any? {
        guard let accessible = element as? any NSAccessibilityProtocol else {
            let name: String
            switch attribute {
            case .children: name = "accessibilityChildren"
            case .identifier: name = "accessibilityIdentifier"
            case .title: name = "accessibilityLabel"
            case .description: name = "accessibilityTitle"
            case .value: name = "accessibilityValue"
            default: return nil
            }
            guard let object = element as? NSObject else { return nil }
            let selector = NSSelectorFromString(name)
            if object.responds(to: selector) {
                return object.perform(selector)?.takeUnretainedValue()
            }
            let legacy = NSSelectorFromString("accessibilityAttributeValue:")
            guard object.responds(to: legacy) else { return nil }
            return object.perform(legacy, with: attribute.rawValue)?.takeUnretainedValue()
        }
        switch attribute {
        case .children: return accessible.accessibilityChildren()
        case .identifier: return accessible.accessibilityIdentifier()
        case .title: return accessible.accessibilityLabel()
        case .description: return accessible.accessibilityTitle()
        case .value: return accessible.accessibilityValue()
        default: return nil
        }
    }

    private func accessibilityIdentifier(_ element: Any) -> String? {
        accessibilityAttribute(.identifier, of: element) as? String
    }

    private func accessibilityLabel(_ element: Any) -> String? {
        accessibilityAttribute(.title, of: element) as? String
            ?? accessibilityAttribute(.description, of: element) as? String
            ?? accessibilityValue(element) as? String
    }

    private func accessibilityValue(_ element: Any) -> Any? {
        accessibilityAttribute(.value, of: element)
    }

    private func selectAccessibilityTableRow(_ element: Any, in window: NSWindow) throws -> NSTableView {
        let point = try accessibilityWindowPoint(element, in: window)
        let content = try XCTUnwrap(window.contentView, "Hosted window has no content")
        let table = try XCTUnwrap(content.hitTest(content.convert(point, from: nil)) as? NSTableView,
            "Expected table at \(point) for \(accessibilityIdentifier(element) ?? "nil")")
        let index = table.row(at: table.convert(point, from: nil))
        let rows = try XCTUnwrap(table.perform(NSSelectorFromString("accessibilityRows"))?.takeUnretainedValue() as? [Any],
            "No accessibility rows for \(accessibilityIdentifier(element) ?? "nil")")
        guard rows.indices.contains(index) else {
            throw SearchHostedAppTestError.missingAccessibilityElement("row=\(index) count=\(rows.count)")
        }
        table.perform(NSSelectorFromString("setAccessibilitySelectedRows:"), with: [rows[index]])
        XCTAssertEqual(table.selectedRow, index, "Accessibility selection for \(accessibilityIdentifier(element) ?? "nil")")
        return table
    }

    private func clickAccessibilityElement(_ element: Any, in window: NSWindow) throws {
        try sendMouseClick(at: accessibilityWindowPoint(element, in: window), count: 1, to: window)
    }

    private func doubleClickAccessibilityElement(_ element: Any, in window: NSWindow) throws {
        let point = try accessibilityWindowPoint(element, in: window)
        try sendMouseClick(at: point, count: 1, to: window)
        try sendMouseClick(at: point, count: 2, to: window)
    }

    private func accessibilityWindowPoint(_ element: Any, in window: NSWindow) throws -> NSPoint {
        let accessible = try XCTUnwrap(element as? any NSAccessibilityElementProtocol,
            "Pointer target must conform to NSAccessibility: \(type(of: element))")
        let screenFrame = accessible.accessibilityFrame()
        XCTAssertFalse(screenFrame.isEmpty,
            "Pointer target \(accessibilityIdentifier(element) ?? "<no identifier>") frame=\(screenFrame)")
        let point = window.convertPoint(fromScreen: NSPoint(x: screenFrame.midX, y: screenFrame.midY))
        print("SEARCH POINTER id=\(accessibilityIdentifier(element) ?? "nil") frame=\(screenFrame) window=\(window.frame) point=\(point) active=\(NSApp.isActive) key=\(window.isKeyWindow)")
        if let content = window.contentView {
            let local = content.convert(point, from: nil)
            print("SEARCH HIT \(String(describing: content.hitTest(local)))")
        }
        return point
    }

    private func sendMouseClick(at point: NSPoint, count: Int, to window: NSWindow) throws {
        func event(_ type: NSEvent.EventType) throws -> NSEvent {
            try XCTUnwrap(NSEvent.mouseEvent(
                with: type, location: point, modifierFlags: [],
                timestamp: ProcessInfo.processInfo.systemUptime,
                windowNumber: window.windowNumber, context: nil,
                eventNumber: 0, clickCount: count,
                pressure: type == .leftMouseDown ? 1 : 0
            ), "Could not construct \(type) at \(point) clickCount=\(count)")
        }
        NSApp.postEvent(try event(.leftMouseUp), atStart: true)
        NSApp.sendEvent(try event(.leftMouseDown))
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

    func setPresentationHandler(
        _ handler: @escaping @MainActor (DulcetPlaybackPresentation) -> Void
    ) {
        presentationHandler = handler
    }

    func configure(account: DulcetPlaybackAccount) {}
    func restorePersistedQueue(with tracks: [DulcetTrack]) {}

    func replaceQueueAndPlay(_ intent: DulcetPlaybackQueueIntent) {
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
