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

@MainActor
private final class ControlledAccountConnector: DulcetAccountConnecting {
    let operation = ControlledAccountOperation()
    private(set) var requests: [DulcetAccountConnectRequest] = []

    func connect(
        _ request: DulcetAccountConnectRequest,
        completion: @escaping @MainActor (DulcetAccountConnectOutcome) -> Void
    ) -> any DulcetAccountConnectOperation {
        requests.append(request)
        return operation
    }
}

@MainActor
private final class ControlledAccountOperation: DulcetAccountConnectOperation {
    private(set) var cancelCount = 0

    func cancel() {
        cancelCount += 1
    }
}
