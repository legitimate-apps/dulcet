import DulcetCore
import DulcetKit
import Foundation
import SwiftUI

@main
struct DulcetMacApp: App {
    @State private var presentation = DulcetPresentationStore(
        source: DulcetAccountDataSource(connector: DulcetCoreAccountConnector())
    )

    var body: some Scene {
        WindowGroup {
            DulcetRootView(store: presentation)
                .frame(minWidth: 900, minHeight: 600)
        }
        .defaultSize(width: 1180, height: 760)
    }
}

@MainActor
private final class DulcetCoreAccountConnector: DulcetAccountConnecting {
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

            let kind: DulcetAccountFailureKind = outcome.errorKind == .transportCancelled
                ? .transportCancelled
                : .transportUnreachable
            completion(.failed(DulcetAccountFailurePresentation(
                kind: kind,
                serverName: URL(string: request.serverURL)?.host ?? request.serverURL,
                title: kind == .transportCancelled ? "Connection cancelled" : "Can’t connect yet",
                message: kind == .transportCancelled
                    ? "Dulcet stopped the account connection."
                    : "Dulcet could not complete account setup.",
                recovery: "Review the connection details and try again."
            )))
        }
        return DulcetCoreAccountOperation(operation: operation)
    }
}

@MainActor
private final class DulcetCoreAccountOperation: DulcetAccountConnectOperation {
    private let operation: any AppleAccountConnectOperation

    init(operation: any AppleAccountConnectOperation) {
        self.operation = operation
    }

    func cancel() {
        operation.cancel()
    }
}
