#if os(macOS) || os(iOS)
#if os(macOS)
import AppKit
#endif
import SwiftUI

enum DulcetAccountConnectionFocus: String, Sendable {
    case serverAddress
    case username
    case password
    case allowLocalHTTP
    case primaryAction
    case tryAgain
}

struct DulcetAccountConnectionView: View {
    @Bindable var store: DulcetPresentationStore
    @FocusState private var focusedControl: DulcetAccountConnectionFocus?
    @State private var lastSubmissionTimestamp: TimeInterval?

    private let focusDidChange: (@MainActor (DulcetAccountConnectionFocus?) -> Void)?

    init(
        store: DulcetPresentationStore,
        focusDidChange: (@MainActor (DulcetAccountConnectionFocus?) -> Void)? = nil
    ) {
        self.store = store
        self.focusDidChange = focusDidChange
    }

    private var isConnecting: Bool {
        if case .connecting = store.snapshot.accountConnection { return true }
        return false
    }

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: DulcetSpacing.lg) {
                heading
                connectionForm
                statusPanel
            }
            .padding(DulcetSpacing.xl)
            .frame(maxWidth: 720)
            .frame(maxWidth: .infinity)
        }
        .background(Color.dulcetWindow)
        .dulcetForeground(.primaryTextOnWindow)
        .navigationTitle(DulcetStrings.settings)
        .dulcetOnExitCommand {
            if isConnecting {
                store.cancelAccountConnection()
            }
        }
        .onAppear {
            if focusedControl == nil {
                focusedControl = preferredFocus(for: store.snapshot.accountConnection)
            }
        }
        .onChange(of: store.snapshot.accountConnection) { previous, current in
            switch (previous, current) {
            case (_, .connecting):
                // The primary action keeps both its view and focus identity while its behavior
                // changes from Connect to Cancel, so there is no replacement control to focus.
                break
            case (.connecting, .idle):
                focusedControl = .primaryAction
            case (_, .failed):
                focusedControl = .tryAgain
            case (_, .connected):
                focusedControl = nil
            case (_, .idle):
                focusedControl = .serverAddress
            }
        }
        .onChange(of: focusedControl) { _, current in
            focusDidChange?(current)
        }
    }

    private var heading: some View {
        VStack(alignment: .leading, spacing: DulcetSpacing.xs) {
            Text(DulcetStrings.accountConnectTitle)
                .font(.largeTitle.weight(.bold))
                .lineLimit(nil)
                .accessibilityAddTraits(.isHeader)
            Text(DulcetStrings.accountConnectBody)
                .font(.callout)
                .dulcetForeground(.secondaryTextOnWindow)
                .lineLimit(nil)
        }
    }

    private var connectionForm: some View {
        GroupBox(DulcetStrings.accountDetails) {
            Grid(alignment: .leadingFirstTextBaseline, horizontalSpacing: DulcetSpacing.md, verticalSpacing: DulcetSpacing.sm) {
                GridRow {
                    Text(DulcetStrings.serverAddress)
                    TextField(
                        DulcetStrings.serverAddressPlaceholder,
                        text: $store.accountServerURL
                    )
                    .textFieldStyle(.roundedBorder)
                    .dulcetCredentialInput(.serverAddress)
                    .accessibilityLabel(DulcetStrings.serverAddress)
                    .focused($focusedControl, equals: .serverAddress)
                }
                GridRow {
                    Text(DulcetStrings.username)
                    TextField(DulcetStrings.username, text: $store.accountUsername)
                        .textFieldStyle(.roundedBorder)
                        .dulcetCredentialInput(.username)
                        .accessibilityLabel(DulcetStrings.username)
                        .focused($focusedControl, equals: .username)
                }
                GridRow {
                    Text(DulcetStrings.password)
                    SecureField(DulcetStrings.password, text: $store.accountPassword)
                        .textFieldStyle(.roundedBorder)
                        .dulcetCredentialInput(.password)
                        .accessibilityLabel(DulcetStrings.password)
                        .focused($focusedControl, equals: .password)
                }
                GridRow {
                    Color.clear.frame(width: 1, height: 1)
                    Toggle(
                        DulcetStrings.allowLocalHTTP,
                        isOn: $store.accountAllowLocalHTTP
                    )
                    .accessibilityHint(DulcetStrings.allowLocalHTTPHint)
                    .focused($focusedControl, equals: .allowLocalHTTP)
                }
            }
            .disabled(isConnecting)
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(.vertical, DulcetSpacing.xxs)
        }
    }

    @ViewBuilder
    private var statusPanel: some View {
        switch store.snapshot.accountConnection {
        case .idle, .connecting:
            accountPrimaryActionPanel
        case let .connected(account):
            Label {
                VStack(alignment: .leading, spacing: DulcetSpacing.xxs) {
                    Text(DulcetStrings.connectedTo(account.serverName))
                        .font(.headline)
                    Text(account.normalizedServerURL)
                        .font(.callout.monospaced())
                        .dulcetForeground(.secondaryTextOnWindow)
                        .textSelection(.enabled)
                }
            } icon: {
                Image(systemName: "checkmark.circle.fill")
                    .font(.title2)
                    .dulcetForeground(.accentIconOnWindow)
            }
            .accessibilityElement(children: .combine)
        case let .failed(failure):
            failurePanel(failure)
        }
    }

    private var accountPrimaryActionPanel: some View {
        VStack(alignment: .leading, spacing: DulcetSpacing.sm) {
            HStack(alignment: .top, spacing: DulcetSpacing.md) {
                accountPrimaryAction

                if isConnecting {
                    connectingStatus
                }
            }

            if !isConnecting {
                Text(DulcetStrings.accountCredentialFootnote)
                    .font(.footnote)
                    .dulcetForeground(.secondaryTextOnWindow)
                    .lineLimit(nil)
            }
        }
        .padding(DulcetSpacing.md)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(
            isConnecting ? Color.dulcetControl.opacity(0.52) : Color.clear,
            in: RoundedRectangle(cornerRadius: 12)
        )
    }

    private var connectingStatus: some View {
        HStack(alignment: .top, spacing: DulcetSpacing.md) {
            ProgressView()
                .controlSize(.small)
                .accessibilityLabel(DulcetStrings.connecting)
            VStack(alignment: .leading, spacing: DulcetSpacing.xxs) {
                Text(DulcetStrings.connecting)
                    .font(.headline)
                Text(DulcetStrings.connectingBody)
                    .font(.callout)
                    .dulcetForeground(.secondaryTextOnControl)
                    .lineLimit(nil)
            }
        }
        .dulcetForeground(.primaryTextOnControl)
    }

    private var accountPrimaryAction: some View {
        Button(role: isConnecting ? .cancel : nil) {
            performAccountPrimaryAction()
        } label: {
            Label(
                isConnecting ? DulcetStrings.cancel : DulcetStrings.connect,
                systemImage: isConnecting ? "xmark" : "link"
            )
        }
        .buttonStyle(.borderedProminent)
        .keyboardShortcut(.defaultAction)
        .focused($focusedControl, equals: .primaryAction)
        .disabled(
            !isConnecting
                && (store.accountServerURL.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
                    || store.accountUsername.isEmpty
                    || store.accountPassword.isEmpty)
        )
    }

    private func performAccountPrimaryAction() {
        let actionTimestamp = ProcessInfo.processInfo.systemUptime

        if isConnecting {
            if let submissionTimestamp = lastSubmissionTimestamp {
                let interval = actionTimestamp - submissionTimestamp
                if interval >= 0, interval <= rapidRepeatSuppressionInterval {
                    // Ignore any primary-action activation too close to the submission. This
                    // consumes a rapid repeated Return and also a pointer click in the same
                    // system-defined double-click window; Escape remains an immediate cancel.
                    return
                }
            }

            lastSubmissionTimestamp = nil
            store.cancelAccountConnection()
        } else {
            lastSubmissionTimestamp = actionTimestamp
            store.submitAccountConnection()
        }
    }

    private var rapidRepeatSuppressionInterval: TimeInterval {
#if os(macOS)
        NSEvent.doubleClickInterval
#else
        0.35
#endif
    }

    private func failurePanel(_ failure: DulcetAccountFailurePresentation) -> some View {
        VStack(alignment: .leading, spacing: DulcetSpacing.md) {
            HStack(alignment: .top, spacing: DulcetSpacing.sm) {
                ZStack {
                    Circle()
                        .fill(Color.dulcetDanger.opacity(0.11))
                        .frame(width: 42, height: 42)
                    Image(systemName: "exclamationmark.triangle.fill")
                        .font(.title2)
                        .dulcetForeground(.dangerIconOnTint)
                        .accessibilityHidden(true)
                }
                VStack(alignment: .leading, spacing: DulcetSpacing.xs) {
                    Text(failure.title)
                        .font(.title2.weight(.semibold))
                        .lineLimit(nil)
                        .accessibilityAddTraits(.isHeader)
                    Text(failure.message)
                        .lineLimit(nil)
                    Text(failure.recovery)
                        .font(.callout)
                        .dulcetForeground(.secondaryTextOnControl)
                        .lineLimit(nil)
                }
            }

            HStack(spacing: DulcetSpacing.sm) {
                Button(DulcetStrings.tryAgain) {
                    store.submitAccountConnection()
                }
                .buttonStyle(.borderedProminent)
                .keyboardShortcut(.defaultAction)
                .focused($focusedControl, equals: .tryAgain)

                if failure.kind == .tlsUntrusted {
                    Link(DulcetStrings.openCertificateHelp, destination: DulcetLinks.certificateInstallationGuide)
                        .buttonStyle(.bordered)
                }
            }
        }
        .padding(DulcetSpacing.md)
        .background(Color.dulcetControl.opacity(0.52), in: RoundedRectangle(cornerRadius: 12))
        .dulcetForeground(.primaryTextOnControl)
    }

    private func preferredFocus(
        for status: DulcetAccountConnectionStatus
    ) -> DulcetAccountConnectionFocus? {
        switch status {
        case .idle:
            .serverAddress
        case .connecting:
            .primaryAction
        case .connected:
            nil
        case .failed:
            .tryAgain
        }
    }

}

/// Which credential a field collects, so the touch platforms can pick the right
/// keyboard and content type.
enum DulcetCredentialInputKind: Sendable {
    case serverAddress
    case username
    case password
}

extension View {
    /// Text-input traits that only matter on the touch platforms.
    ///
    /// On macOS this is deliberately the identity transform: there is no
    /// autocapitalization and no software keyboard, so applying nothing keeps the
    /// deterministic macOS capture byte-identical.
    ///
    /// On iOS the SwiftUI defaults are actively WRONG for credentials, and both
    /// failures break a real login rather than merely looking untidy. Sentence
    /// capitalization rewrites `https://…` as `HTTPS://…` and a lowercase username
    /// as `Username`; autocorrect can silently substitute a hostname. Measured on
    /// an iPhone 17 Pro simulator 2026-08-23 by typing into the live field — the
    /// build was green and the view rendered correctly, so only driving the UI
    /// surfaced it.
    func dulcetCredentialInput(_ kind: DulcetCredentialInputKind) -> some View {
        #if os(iOS)
        return
            self
            .textInputAutocapitalization(.never)
            .autocorrectionDisabled()
            .keyboardType(kind.keyboardType)
            .textContentType(kind.textContentType)
        #else
        return self
        #endif
    }
}

#if os(iOS)
extension DulcetCredentialInputKind {
    var keyboardType: UIKeyboardType {
        switch self {
        case .serverAddress: .URL
        case .username, .password: .asciiCapable
        }
    }

    var textContentType: UITextContentType? {
        switch self {
        case .serverAddress: .URL
        case .username: .username
        case .password: .password
        }
    }
}
#endif

#endif
