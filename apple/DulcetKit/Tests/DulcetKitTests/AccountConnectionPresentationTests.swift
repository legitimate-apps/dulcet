import Foundation
import Testing
@testable import DulcetKit

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
func keychainCredentialsRoundTripAndDelete() throws {
    let identifier = UUID().uuidString
    let suiteName = "com.legitimateapps.dulcet.tests.\(identifier)"
    let defaults = try #require(UserDefaults(suiteName: suiteName))
    let store = DulcetKeychainCredentialStore(
        service: "com.legitimateapps.dulcet.tests.\(identifier)",
        defaults: defaults,
        activeAccountKey: "active-account"
    )
    let request = DulcetAccountConnectRequest(
        serverURL: "https://music.example.invalid",
        username: "listener",
        password: "fixture-password",
        allowLocalHTTP: false
    )
    defer {
        try? store.delete()
        defaults.removePersistentDomain(forName: suiteName)
    }

    try store.save(request)
    #expect(try store.load() == request)

    try store.delete()
    #expect(try store.load() == nil)
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
