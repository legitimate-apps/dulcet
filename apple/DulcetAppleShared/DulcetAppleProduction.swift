import DulcetCore
import DulcetKit
import Foundation

/// Shared production account composition for every touch-capable Apple shell.
@MainActor
enum DulcetAppleProduction {
    static func makePresentationStore() -> DulcetPresentationStore {
        DulcetPresentationStore(
            source: DulcetAccountDataSource(
                connector: DulcetCoreAccountConnector(),
                credentialStore: DulcetKeychainCredentialStore()
            )
        )
    }
}

@MainActor
final class DulcetCoreAccountConnector: DulcetAccountConnecting {
    private let client = AppleAccountConnectionClient()

    func connect(
        _ request: DulcetAccountConnectRequest,
        completion: @escaping @MainActor (DulcetAccountConnectOutcome) -> Void
    ) -> any DulcetAccountConnectOperation {
        let coreRequest = AccountConnectionRequest(
            serverUrl: request.serverURL,
            username: request.username,
            password: request.password,
            allowLocalHttp: request.allowLocalHTTP
        )
        let operation = client.startConnect(request: coreRequest) { outcome in
            if let account = outcome.account {
                let fallbackName = URL(string: account.normalizedBaseUrl)?.host
                    ?? account.normalizedBaseUrl
                completion(.connected(DulcetConnectedAccountSummary(
                    serverName: account.serverType.isEmpty ? fallbackName : account.serverType,
                    normalizedServerURL: account.normalizedBaseUrl
                )))
                return
            }

            guard let error = outcome.errorPresentation else {
                preconditionFailure("A failed account connection must carry its presentation key")
            }
            guard let kind = DulcetAccountFailureKind(rawValue: error.kind) else {
                preconditionFailure("The core exported an unmapped account error kind")
            }
            let context = DulcetAccountErrorContext(
                kind: kind,
                serverName: URL(string: request.serverURL)?.host ?? request.serverURL,
                targetHost: error.targetHost,
                invalidServerURLIsInternationalized:
                    error.invalidServerURLIsInternationalized
            )
            completion(.failed(DulcetAccountErrorPresenter.presentation(for: context)))
        }
        return DulcetCoreAccountOperation(operation: operation)
    }
}

@MainActor
final class DulcetCoreAccountOperation: DulcetAccountConnectOperation {
    private let operation: any AppleAccountConnectOperation

    init(operation: any AppleAccountConnectOperation) {
        self.operation = operation
    }

    func cancel() {
        operation.cancel()
    }
}
