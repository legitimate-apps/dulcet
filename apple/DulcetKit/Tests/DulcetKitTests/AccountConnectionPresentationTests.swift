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
