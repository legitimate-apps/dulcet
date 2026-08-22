import Foundation

public struct DulcetAccountErrorContext: Sendable, Hashable {
    public let kind: DulcetAccountFailureKind
    public let serverName: String
    public let targetHost: String?
    public let invalidServerURLIsInternationalized: Bool

    public init(
        kind: DulcetAccountFailureKind,
        serverName: String,
        targetHost: String? = nil,
        invalidServerURLIsInternationalized: Bool = false
    ) {
        self.kind = kind
        self.serverName = serverName
        self.targetHost = targetHost
        self.invalidServerURLIsInternationalized = invalidServerURLIsInternationalized
    }
}

/// The exhaustive switch is intentional: a new presentation kind cannot compile without copy.
public enum DulcetAccountErrorPresenter {
    public static func presentation(
        for context: DulcetAccountErrorContext
    ) -> DulcetAccountFailurePresentation {
        let copy: (title: String, message: String, recovery: String) = switch context.kind {
        case .invalidServerURL where context.invalidServerURLIsInternationalized:
            (
                "Use the punycode server address",
                "Dulcet cannot safely compare internationalized host names yet.",
                "Enter the punycode spelling instead. For example, müsik.example.invalid is xn--msik-0ra.example.invalid."
            )
        case .invalidServerURL:
            (
                "Check the server address",
                "That address is not a valid OpenSubsonic server URL.",
                "Enter the full http:// or https:// address. Plain HTTP is limited to explicitly allowed local servers."
            )
        case .transportUnreachable:
            (
                "The server could not be reached",
                "Dulcet could not establish a connection to \(context.serverName).",
                "Check that the server is running and reachable from this Mac, then try again."
            )
        case .transportTimeout:
            (
                "The server took too long to respond",
                "Account setup reached its 30-second request limit.",
                "Check the server and network, then try again. You can cancel while Dulcet is waiting."
            )
        case .transportCancelled:
            (
                "Connection cancelled",
                "Dulcet stopped the account connection.",
                "Choose Connect whenever you are ready to try again."
            )
        case .tlsUntrusted:
            (
                "This server’s certificate isn’t trusted",
                "Dulcet stopped before sending account credentials.",
                "Fix or renew the certificate. For a private certificate authority, install the CA at the macOS operating-system level, then try again."
            )
        case .localNetworkPolicyRejected:
            (
                "Local HTTP is not allowed",
                "Dulcet will not send credentials over this plaintext connection.",
                "Use HTTPS, or enable local HTTP only for a server you control on a private local network."
            )
        case .redirectRejected:
            (
                "The server redirect was refused",
                "The redirect could not be followed safely.",
                "Enter the server’s final HTTPS address directly and try again."
            )
        case .malformedEnvelope:
            (
                "The server response was malformed",
                "The server replied successfully but its OpenSubsonic envelope was not valid.",
                "Check the server version or its reverse-proxy response, then try again."
            )
        case .incompatibleProtocol:
            (
                "The server protocol is not compatible",
                "This server does not support the OpenSubsonic version Dulcet needs.",
                "Update the server, or use a compatible OpenSubsonic endpoint."
            )
        case .notASubsonicServer:
            (
                "This is not an OpenSubsonic endpoint",
                "The address responded, but not as an OpenSubsonic server.",
                "Enter the server base address rather than a web player or sign-in page."
            )
        case .knownServerError:
            (
                "The server rejected account setup",
                "The server returned a recognized OpenSubsonic error.",
                "Review the account and server settings, then try again."
            )
        case .unknownServerError:
            (
                "The server could not complete account setup",
                "The server returned an OpenSubsonic error Dulcet does not recognize.",
                "Check the server logs or version, then try again."
            )
        case .invalidCredentials:
            (
                "The username or password was not accepted",
                "The server rejected these account credentials.",
                "Check the username and password, then try again."
            )
        case .tokenAuthenticationUnsupported:
            (
                "This account authentication is not supported",
                "The server rejected salted-token authentication.",
                "Enable token authentication on the server or use a compatible endpoint."
            )
        case .forbidden:
            (
                "This account is not allowed to connect",
                "The server accepted the credentials but denied access.",
                "Ask the server administrator to allow Subsonic API access for this account."
            )
        case .unsupportedAuthenticationChallenge:
            (
                "The intermediary’s sign-in method is not supported",
                "A reverse proxy requested HTTP, proxy, or client-certificate authentication that account setup does not support.",
                "Expose the OpenSubsonic endpoint without that extra authentication challenge, then try again."
            )
        case .crossOriginRedirectRejected:
            (
                "The server redirected to another sign-in host",
                context.targetHost.map {
                    "The server bounced account setup to \($0), so Dulcet refused to carry credentials there."
                } ?? "The server bounced account setup to another host, so Dulcet refused to carry credentials there.",
                "Exempt /rest/ from the SSO or identity-provider layer, or point Dulcet at an OpenSubsonic endpoint that is not behind it."
            )
        case .capabilityUnsupported:
            (
                "A required server capability is unavailable",
                "This server cannot provide a capability required for account setup.",
                "Update the server or use an endpoint with the required OpenSubsonic capability."
            )
        case .credentialPersistenceFailed:
            (
                "The account could not be saved",
                "The server accepted the account, but the system Keychain did not save it.",
                "Review Keychain access for Dulcet, then connect again."
            )
        }

        let localizationPrefix = if context.kind == .invalidServerURL &&
            context.invalidServerURLIsInternationalized {
            "account.error.unsupportedInternationalizedHost"
        } else {
            "account.error.\(context.kind.rawValue)"
        }
        let localizedMessage: String
        if context.kind == .crossOriginRedirectRejected {
            localizedMessage = DulcetStrings.dynamicFormatted(
                "\(localizationPrefix).message",
                fallback: "The server bounced account setup to %@, so Dulcet refused to carry credentials there.",
                context.targetHost ?? "another host"
            )
        } else {
            localizedMessage = DulcetStrings.dynamicText(
                "\(localizationPrefix).message",
                fallback: copy.message
            )
        }
        return DulcetAccountFailurePresentation(
            kind: context.kind,
            serverName: context.serverName,
            title: DulcetStrings.dynamicText(
                "\(localizationPrefix).title",
                fallback: copy.title
            ),
            message: localizedMessage,
            recovery: DulcetStrings.dynamicText(
                "\(localizationPrefix).recovery",
                fallback: copy.recovery
            ),
            targetHost: context.targetHost
        )
    }
}

@MainActor
public protocol DulcetAccountConnectOperation: AnyObject {
    func cancel()
}

@MainActor
public protocol DulcetAccountConnecting: AnyObject {
    func connect(
        _ request: DulcetAccountConnectRequest,
        completion: @escaping @MainActor (DulcetAccountConnectOutcome) -> Void
    ) -> any DulcetAccountConnectOperation
}

/// Live presentation source for account setup. Network and persistence adapters stay replaceable.
@MainActor
public final class DulcetAccountDataSource: DulcetDataSource {
    private let connector: any DulcetAccountConnecting
    private let credentialStore: (any DulcetCredentialStoring)?
    private var snapshotHandler: (@MainActor (DulcetSnapshot) -> Void)?
    private var activeOperation: (any DulcetAccountConnectOperation)?
    private var generation = 0

    public private(set) var currentSnapshot: DulcetSnapshot

    public init(
        connector: any DulcetAccountConnecting,
        credentialStore: (any DulcetCredentialStoring)? = nil,
        initialRequest: DulcetAccountConnectRequest = .empty
    ) {
        self.connector = connector
        self.credentialStore = credentialStore
        do {
            let restoredRequest = try credentialStore?.load() ?? initialRequest
            currentSnapshot = Self.snapshot(
                state: .accountConnectIdle,
                form: restoredRequest,
                status: .idle
            )
        } catch {
            let failure = DulcetAccountErrorPresenter.presentation(for: DulcetAccountErrorContext(
                kind: .credentialPersistenceFailed,
                serverName: "Music server"
            ))
            currentSnapshot = Self.snapshot(
                state: .accountErrorPersistence,
                form: initialRequest,
                status: .failed(failure)
            )
        }
    }

    public func setSnapshotHandler(
        _ handler: @escaping @MainActor (DulcetSnapshot) -> Void
    ) {
        snapshotHandler = handler
    }

    public func send(_ action: DulcetPresentationAction) {
        switch action {
        case let .selectDestination(destination):
            if destination == .settings {
                publish(
                    state: currentSnapshot.state.accountStateOrIdle,
                    form: currentSnapshot.accountForm,
                    status: currentSnapshot.accountConnection
                )
            }
        case .updateSearchQuery:
            break
        case let .submitAccountConnection(request):
            submit(request)
        case .cancelAccountConnection:
            activeOperation?.cancel()
        }
    }

    private func submit(_ request: DulcetAccountConnectRequest) {
        generation += 1
        let submissionGeneration = generation
        activeOperation = nil
        publish(state: .accountConnecting, form: request, status: .connecting)

        let operation = connector.connect(request) { [weak self] outcome in
            guard let self, self.generation == submissionGeneration else { return }
            self.activeOperation = nil
            switch outcome {
            case let .connected(account):
                do {
                    try self.credentialStore?.save(request)
                    self.publish(
                        state: .accountConnected,
                        form: request,
                        status: .connected(account)
                    )
                } catch {
                    let failure = DulcetAccountErrorPresenter.presentation(
                        for: DulcetAccountErrorContext(
                            kind: .credentialPersistenceFailed,
                            serverName: account.serverName
                        )
                    )
                    self.publish(
                        state: .accountErrorPersistence,
                        form: request,
                        status: .failed(failure)
                    )
                }
            case let .failed(failure) where failure.kind == .transportCancelled:
                self.publish(
                    state: .accountConnectIdle,
                    form: request,
                    status: .idle
                )
            case let .failed(failure):
                self.publish(
                    state: failure.kind.family.presentationState,
                    form: request,
                    status: .failed(failure)
                )
            }
        }
        if generation == submissionGeneration,
           currentSnapshot.state == .accountConnecting {
            activeOperation = operation
        }
    }

    private func publish(
        state: DulcetPresentationState,
        form: DulcetAccountConnectRequest,
        status: DulcetAccountConnectionStatus
    ) {
        currentSnapshot = Self.snapshot(state: state, form: form, status: status)
        snapshotHandler?(currentSnapshot)
    }

    private static func snapshot(
        state: DulcetPresentationState,
        form: DulcetAccountConnectRequest,
        status: DulcetAccountConnectionStatus
    ) -> DulcetSnapshot {
        let connectivity: DulcetConnectivity = switch status {
        case .idle, .connecting:
            .unavailable
        case let .connected(account):
            .online(serverName: account.serverName)
        case let .failed(failure):
            .connectionFailed(.account(failure))
        }
        return DulcetSnapshot(
            state: state,
            selectedDestination: .settings,
            accountConnected: {
                if case .connected = status { return true }
                return false
            }(),
            connectivity: connectivity,
            albums: [],
            looseTracks: [],
            recentlyAddedTracks: [],
            captureDate: Date(timeIntervalSince1970: 0),
            accountForm: form,
            accountConnection: status
        )
    }
}

private extension DulcetPresentationState {
    var accountStateOrIdle: DulcetPresentationState {
        switch self {
        case .accountConnectIdle, .accountConnecting, .accountConnected,
             .accountErrorInput, .accountErrorTransport, .accountErrorSecurity,
             .accountErrorProtocol, .accountErrorServer, .accountErrorAuthentication,
             .accountErrorCapability, .accountErrorPersistence:
            self
        case .emptyLibraryNoAccount, .libraryBrowse, .albumDetailMultiDisc, .nowPlaying,
             .searchMixedSources, .tlsUntrusted, .offlineMetadataOnly:
            .accountConnectIdle
        }
    }
}

private extension DulcetAccountErrorFamily {
    var presentationState: DulcetPresentationState {
        switch self {
        case .input: .accountErrorInput
        case .transport: .accountErrorTransport
        case .security: .accountErrorSecurity
        case .protocol: .accountErrorProtocol
        case .server: .accountErrorServer
        case .authentication: .accountErrorAuthentication
        case .capability: .accountErrorCapability
        case .persistence: .accountErrorPersistence
        }
    }
}
