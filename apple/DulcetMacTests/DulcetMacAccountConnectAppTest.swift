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
            .connect,
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
            .connect,
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
            failureMessage: "Return did not invoke Connect or focus the appearing Cancel control"
        ) {
            store.snapshot.state == .accountConnecting
                && connector.requests == [request]
                && observedFocus == .cancel
        }

        try sendKey(.escape, to: window)
        try await waitUntil(
            timeout: .seconds(2),
            failureMessage: "Escape did not dismiss connecting and restore focus to Connect"
        ) {
            store.snapshot.state == .accountConnectIdle
                && connector.operation.cancelCount == 1
                && observedFocus == .connect
        }

        let expectedTrace: [DulcetAccountConnectionFocus] = [
            .serverAddress,
            .username,
            .password,
            .allowLocalHTTP,
            .connect,
            .allowLocalHTTP,
            .password,
            .username,
            .serverAddress,
            .username,
            .password,
            .allowLocalHTTP,
            .connect,
            .cancel,
            .connect,
        ]
        XCTAssertEqual(focusTrace, expectedTrace)
        print(
            "ACCOUNT CONNECT KEYBOARD TRACE focus="
                + focusTrace.map(\.rawValue).joined(separator: ">")
                + " actions=return:connect>escape:cancel"
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

    private func waitUntil(
        timeout: Duration,
        failureMessage: String,
        condition: @MainActor () -> Bool
    ) async throws {
        let clock = ContinuousClock()
        let deadline = clock.now.advanced(by: timeout)
        while !condition() {
            if clock.now >= deadline {
                XCTFail(failureMessage)
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
