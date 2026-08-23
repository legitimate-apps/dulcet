import Foundation
import Security
import Testing
@testable import DulcetKit

@Test
func credentialBearingPresentationValuesCannotPrintCredentials() {
    let credentialValues = [
        "https://listener:request-secret@music.example.invalid",
        "print-canary-username",
        "print-canary-password",
    ]
    let request = DulcetAccountConnectRequest(
        serverURL: credentialValues[0],
        username: credentialValues[1],
        password: credentialValues[2],
        allowLocalHTTP: false
    )
    let snapshot = DulcetSnapshot(
        state: .accountConnectIdle,
        selectedDestination: .settings,
        accountConnected: false,
        connectivity: .unavailable,
        albums: [],
        looseTracks: [],
        recentlyAddedTracks: [],
        captureDate: Date(timeIntervalSince1970: 0),
        accountForm: request
    )
    var requestDump = ""
    var snapshotDump = ""
    dump(request, to: &requestDump)
    dump(snapshot, to: &snapshotDump)
    let rendered = [
        String(describing: request),
        String(reflecting: request),
        requestDump,
        String(describing: snapshot),
        String(reflecting: snapshot),
        snapshotDump,
    ]

    for value in credentialValues {
        #expect(rendered.allSatisfy { !$0.contains(value) })
    }
    #expect(rendered.allSatisfy { $0.contains("<redacted>") })
}

@Test @MainActor
func accountConnectSurfacePublishesProgressAndCancelsTheActiveOperation() {
    let connector = ControlledAccountConnector()
    let source = DulcetAccountDataSource(connector: connector)
    let store = DulcetPresentationStore(source: source)

    #expect(store.snapshot.state == .accountConnectIdle)

    store.accountServerURL = "https://music.example.invalid"
    store.accountUsername = "listener"
    store.accountPassword = "correct horse battery staple"
    store.submitAccountConnection()

    #expect(store.snapshot.state == .accountConnecting)
    #expect(connector.requests == [DulcetAccountConnectRequest(
        serverURL: "https://music.example.invalid",
        username: "listener",
        password: "correct horse battery staple",
        allowLocalHTTP: false
    )])
    #expect(connector.operation.cancelCount == 0)

    store.cancelAccountConnection()

    #expect(connector.operation.cancelCount == 1)
}

@Test @MainActor
func lateCancelSuppressesQueuedSuccessAndCredentialPersistence() {
    let connector = ControlledAccountConnector()
    let credentials = MemoryCredentialStore(persisted: nil)
    let source = DulcetAccountDataSource(
        connector: connector,
        credentialStore: credentials
    )
    let store = DulcetPresentationStore(source: source)

    store.accountServerURL = "https://music.example.invalid"
    store.accountUsername = "listener"
    store.accountPassword = "fixture-password"
    store.submitAccountConnection()

    store.cancelAccountConnection()
    connector.complete(.connected(DulcetConnectedAccountSummary(
        serverName: "Music",
        normalizedServerURL: "https://music.example.invalid"
    )))

    #expect(connector.operation.cancelCount == 1)
    #expect(store.snapshot.state == .accountConnectIdle)
    #expect(!store.snapshot.accountConnected)
    #expect(credentials.saved.isEmpty)
}

@Test @MainActor
func replacementSubmissionCancelsThePreviousOperationAndOwnsTheOutcome() {
    let connector = SequencedAccountConnector()
    let credentials = MemoryCredentialStore(persisted: nil)
    let source = DulcetAccountDataSource(
        connector: connector,
        credentialStore: credentials
    )
    let first = DulcetAccountConnectRequest(
        serverURL: "https://first.example.invalid",
        username: "first-listener",
        password: "first-password",
        allowLocalHTTP: false
    )
    let second = DulcetAccountConnectRequest(
        serverURL: "https://second.example.invalid",
        username: "second-listener",
        password: "second-password",
        allowLocalHTTP: false
    )

    source.send(.submitAccountConnection(first))
    source.send(.submitAccountConnection(second))

    #expect(connector.requests == [first, second])
    #expect(connector.operations[0].cancelCount == 1)
    #expect(connector.operations[1].cancelCount == 0)

    connector.complete(.connected(DulcetConnectedAccountSummary(
        serverName: "First",
        normalizedServerURL: first.serverURL
    )), at: 0)
    #expect(credentials.saved.isEmpty)
    #expect(source.currentSnapshot.state == .accountConnecting)

    connector.complete(.connected(DulcetConnectedAccountSummary(
        serverName: "Second",
        normalizedServerURL: second.serverURL
    )), at: 1)
    #expect(credentials.saved == [second])
    #expect(source.currentSnapshot.state == .accountConnected)
    #expect(source.currentSnapshot.accountForm == second)
}

@Test
func accountDomainErrorsHaveATotalActionablePresentation() {
    let presentations = DulcetAccountFailureKind.allCases.map { kind in
        DulcetAccountErrorPresenter.presentation(for: DulcetAccountErrorContext(
            kind: kind,
            serverName: "Music server",
            targetHost: kind == .crossOriginRedirectRejected
                ? "login.example.invalid"
                : nil
        ))
    }

    #expect(Set(presentations.map(\.kind)) == Set(DulcetAccountFailureKind.allCases))
    #expect(presentations.allSatisfy {
        !$0.title.isEmpty && !$0.message.isEmpty && !$0.recovery.isEmpty
    })

    let tls = presentations.first { $0.kind == .tlsUntrusted }
    #expect(tls?.recovery.localizedCaseInsensitiveContains("install") == true)
    #expect(tls?.recovery.localizedCaseInsensitiveContains("CA") == true)
    #expect(tls?.recovery.localizedCaseInsensitiveContains("macOS") == true)

    let internationalizedHost = DulcetAccountErrorPresenter.presentation(
        for: DulcetAccountErrorContext(
            kind: .invalidServerURL,
            serverName: "müsik.example.invalid",
            invalidServerURLIsInternationalized: true
        )
    )
    #expect(internationalizedHost.recovery.localizedCaseInsensitiveContains("punycode"))
    #expect(internationalizedHost.recovery.contains("xn--msik-0ra.example.invalid"))

    let crossOrigin = presentations.first { $0.kind == .crossOriginRedirectRejected }
    #expect(crossOrigin?.message.contains("login.example.invalid") == true)
    #expect(crossOrigin?.recovery.contains("/rest/") == true)
    #expect(crossOrigin?.recovery.localizedCaseInsensitiveContains("SSO") == true)
    #expect(crossOrigin?.message.contains("/") == false)
    #expect(crossOrigin?.message.contains("?") == false)
}

@Test @MainActor
func unentitledKeychainWriteFailsClosedWithoutLegacyFallback() throws {
    let identifier = UUID().uuidString
    let suiteName = "com.legitimateapps.dulcet.tests.\(identifier)"
    let service = "com.legitimateapps.dulcet.tests.\(identifier)"
    let activeAccountKey = "active-account"
    let defaults = try #require(UserDefaults(suiteName: suiteName))
    let store = DulcetKeychainCredentialStore(
        service: service,
        defaults: defaults,
        activeAccountKey: activeAccountKey
    )
    let request = DulcetAccountConnectRequest(
        serverURL: "https://music.example.invalid",
        username: "listener",
        password: "fixture-password",
        allowLocalHTTP: false
    )
    let legacyQuery: [String: Any] = [
        kSecClass as String: kSecClassGenericPassword,
        kSecAttrService as String: service,
        kSecMatchLimit as String: kSecMatchLimitOne,
    ]
    defer {
        try? store.delete()
        SecItemDelete(legacyQuery as CFDictionary)
        defaults.removePersistentDomain(forName: suiteName)
    }

    var observedError: DulcetCredentialStoreError?
    do {
        try store.save(request)
    } catch let error as DulcetCredentialStoreError {
        observedError = error
    } catch {
        throw error
    }

    #expect(observedError == .missingDataProtectionKeychainEntitlement)
    #expect(defaults.string(forKey: activeAccountKey) == nil)
    let legacyLookupStatus = SecItemCopyMatching(legacyQuery as CFDictionary, nil)
#if os(macOS)
    #expect(legacyLookupStatus == errSecItemNotFound)
#else
    #expect(legacyLookupStatus == errSecMissingEntitlement)
#endif
}

@Test @MainActor
func relaunchPrefillsKeychainCredentialsButWaitsForExplicitReconnect() {
    let persisted = DulcetAccountConnectRequest(
        serverURL: "https://music.example.invalid",
        username: "listener",
        password: "fixture-password",
        allowLocalHTTP: false
    )
    let credentials = MemoryCredentialStore(persisted: persisted)
    let connector = ControlledAccountConnector()
    let source = DulcetAccountDataSource(
        connector: connector,
        credentialStore: credentials
    )
    let store = DulcetPresentationStore(source: source)

    #expect(store.snapshot.state == .accountConnectIdle)
    #expect(store.snapshot.accountForm == persisted)
    #expect(connector.requests.isEmpty)

    store.submitAccountConnection()
    connector.complete(.connected(DulcetConnectedAccountSummary(
        serverName: "Music",
        normalizedServerURL: persisted.serverURL
    )))

    #expect(credentials.saved == [persisted])
    #expect(store.snapshot.state == .accountConnected)
}

@MainActor
private final class ControlledAccountConnector: DulcetAccountConnecting {
    let operation = ControlledAccountOperation()
    private(set) var requests: [DulcetAccountConnectRequest] = []
    private var completion: (@MainActor (DulcetAccountConnectOutcome) -> Void)?

    func connect(
        _ request: DulcetAccountConnectRequest,
        completion: @escaping @MainActor (DulcetAccountConnectOutcome) -> Void
    ) -> any DulcetAccountConnectOperation {
        requests.append(request)
        self.completion = completion
        return operation
    }

    func complete(_ outcome: DulcetAccountConnectOutcome) {
        completion?(outcome)
        completion = nil
    }
}

@MainActor
private final class ControlledAccountOperation: DulcetAccountConnectOperation {
    private(set) var cancelCount = 0

    func cancel() {
        cancelCount += 1
    }
}

@MainActor
private final class SequencedAccountConnector: DulcetAccountConnecting {
    private(set) var requests: [DulcetAccountConnectRequest] = []
    private(set) var operations: [ControlledAccountOperation] = []
    private var completions: [Int: (@MainActor (DulcetAccountConnectOutcome) -> Void)] = [:]

    func connect(
        _ request: DulcetAccountConnectRequest,
        completion: @escaping @MainActor (DulcetAccountConnectOutcome) -> Void
    ) -> any DulcetAccountConnectOperation {
        let index = requests.count
        let operation = ControlledAccountOperation()
        requests.append(request)
        operations.append(operation)
        completions[index] = completion
        return operation
    }

    func complete(_ outcome: DulcetAccountConnectOutcome, at index: Int) {
        completions.removeValue(forKey: index)?(outcome)
    }
}

@MainActor
private final class MemoryCredentialStore: DulcetCredentialStoring {
    private let persisted: DulcetAccountConnectRequest?
    private(set) var saved: [DulcetAccountConnectRequest] = []

    init(persisted: DulcetAccountConnectRequest?) {
        self.persisted = persisted
    }

    func load() throws -> DulcetAccountConnectRequest? {
        persisted
    }

    func save(_ request: DulcetAccountConnectRequest) throws {
        saved.append(request)
    }

    func delete() throws {}
}
