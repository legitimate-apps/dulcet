#if os(macOS) || os(iOS) || os(tvOS)
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
#if os(tvOS)
    @Namespace private var accountFocusScope
#endif
    @State private var showingSignOutConfirmation = false

    // Deterministic presentation captures opt out because their artifact contract represents a
    // resting surface, while the shipping view keeps automatic keyboard focus enabled by default.
    private let allowsProgrammaticFocus: Bool
    private let focusDidChange: (@MainActor (DulcetAccountConnectionFocus?) -> Void)?

    init(
        store: DulcetPresentationStore,
        allowsProgrammaticFocus: Bool = true,
        focusDidChange: (@MainActor (DulcetAccountConnectionFocus?) -> Void)? = nil
    ) {
        self.store = store
        self.allowsProgrammaticFocus = allowsProgrammaticFocus
        self.focusDidChange = focusDidChange
    }

    private var isConnecting: Bool {
        if case .connecting = store.snapshot.accountConnection { return true }
        return false
    }

    private var savedServerName: String? {
        if case let .saved(serverName) = store.snapshot.accountConnection { return serverName }
        return nil
    }

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: DulcetSpacing.lg) {
                if store.snapshot.accountRemoval == .removing {
                    accountRemovalProgress
                } else {
                    heading
                    connectionForm
                    statusPanel
                }
            }
            .padding(DulcetSpacing.xl)
            .frame(maxWidth: contentMaxWidth)
            .frame(maxWidth: .infinity)
#if os(tvOS)
            .focusScope(accountFocusScope)
#endif
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
            if allowsProgrammaticFocus, focusedControl == nil {
                let initialFocus = preferredFocus(for: store.snapshot.accountConnection)
                focusedControl = initialFocus
                // `onChange` observes later focus-engine movement but does not replay the
                // value assigned during this first appearance. Report that initial value
                // at its source so focus instrumentation sees the same state as the field
                // styling and FocusState binding.
                focusDidChange?(initialFocus)
            }
        }
        .onChange(of: store.snapshot.accountConnection) { previous, current in
            guard allowsProgrammaticFocus else { return }
            switch (previous, current) {
            case (_, .connecting):
                // The primary action keeps both its view and focus identity while its behavior
                // changes from Connect to Cancel, so there is no replacement control to focus.
                break
            case (.connecting, .idle):
                focusedControl = .primaryAction
            case (.connecting, .saved), (_, .saved):
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
        .confirmationDialog(
            DulcetStrings.signOutConfirmationTitle,
            isPresented: $showingSignOutConfirmation,
            titleVisibility: .visible
        ) {
            Button(DulcetStrings.signOut, role: .destructive) {
                store.removeAccount()
            }
            Button(DulcetStrings.cancel, role: .cancel) {}
        } message: {
            Text(DulcetStrings.signOutConfirmationBody)
        }
    }

    private var contentMaxWidth: CGFloat {
#if os(tvOS)
        1_100
#else
        720
#endif
    }

    private var heading: some View {
        VStack(alignment: .leading, spacing: DulcetSpacing.xs) {
            Text(DulcetStrings.accountConnectTitle)
                .font(.largeTitle.weight(.bold))
                .lineLimit(nil)
                .accessibilityIdentifier("dulcet.account-connect.title")
                .accessibilityAddTraits(.isHeader)
            Text(DulcetStrings.accountConnectBody)
                .font(.callout)
                .dulcetForeground(.secondaryTextOnWindow)
                .lineLimit(nil)
        }
    }

    private var connectionForm: some View {
#if os(tvOS)
        tvConnectionForm
#else
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
                    .accessibilityIdentifier("dulcet.account-connect.server-address")
                    .focused($focusedControl, equals: .serverAddress)
                }
                GridRow {
                    Text(DulcetStrings.username)
                    TextField(DulcetStrings.username, text: $store.accountUsername)
                        .textFieldStyle(.roundedBorder)
                        .dulcetCredentialInput(.username)
                        .accessibilityLabel(DulcetStrings.username)
                        .accessibilityIdentifier("dulcet.account-connect.username")
                        .focused($focusedControl, equals: .username)
                }
                GridRow {
                    Text(DulcetStrings.password)
                    SecureField(DulcetStrings.password, text: $store.accountPassword)
                        .textFieldStyle(.roundedBorder)
                        .dulcetCredentialInput(.password)
                        .accessibilityLabel(DulcetStrings.password)
                        .accessibilityIdentifier("dulcet.account-connect.password")
                        .focused($focusedControl, equals: .password)
                }
                GridRow {
                    // This cell only occupies the label column; it has no text, so under the grid's
                    // .leadingFirstTextBaseline guide there is no first text baseline to align to and
                    // one has to be synthesised for the row. gridCellAnchor switches this cell to
                    // anchor-based alignment, which is documented to take it out of the alignment-guide
                    // strategy entirely, so the row's geometry no longer depends on resolving a
                    // baseline that does not exist.
                    Color.clear
                        .frame(width: 1, height: 1)
                        .gridCellAnchor(.topLeading)
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
#endif
    }

#if os(tvOS)
    private var tvConnectionForm: some View {
        VStack(alignment: .leading, spacing: DulcetSpacing.md) {
            Text(DulcetStrings.accountDetails)
                .font(.title2.weight(.semibold))
                .accessibilityAddTraits(.isHeader)

            VStack(alignment: .leading, spacing: DulcetSpacing.xs) {
                Text(DulcetStrings.serverAddress)
                    .font(.headline)
                TextField(
                    DulcetStrings.serverAddressPlaceholder,
                    text: $store.accountServerURL
                )
                .dulcetTVTextField(focused: focusedControl == .serverAddress)
                .dulcetCredentialInput(.serverAddress)
                .submitLabel(.next)
                .onSubmit { focusedControl = .username }
                .accessibilityLabel(DulcetStrings.serverAddress)
                .accessibilityIdentifier("dulcet.account-connect.server-address")
                .focused($focusedControl, equals: .serverAddress)
                .prefersDefaultFocus(in: accountFocusScope)
            }

            VStack(alignment: .leading, spacing: DulcetSpacing.xs) {
                Text(DulcetStrings.username)
                    .font(.headline)
                TextField(DulcetStrings.username, text: $store.accountUsername)
                    .dulcetTVTextField(focused: focusedControl == .username)
                    .dulcetCredentialInput(.username)
                    .submitLabel(.next)
                    .onSubmit { focusedControl = .password }
                    .accessibilityLabel(DulcetStrings.username)
                    .accessibilityIdentifier("dulcet.account-connect.username")
                    .focused($focusedControl, equals: .username)
            }

            VStack(alignment: .leading, spacing: DulcetSpacing.xs) {
                Text(DulcetStrings.password)
                    .font(.headline)
                SecureField(DulcetStrings.password, text: $store.accountPassword)
                    .dulcetTVTextField(focused: focusedControl == .password)
                    .dulcetCredentialInput(.password)
                    .submitLabel(.next)
                    .onSubmit { focusedControl = .allowLocalHTTP }
                    .accessibilityLabel(DulcetStrings.password)
                    .accessibilityIdentifier("dulcet.account-connect.password")
                    .focused($focusedControl, equals: .password)
            }

            Toggle(
                DulcetStrings.allowLocalHTTP,
                isOn: $store.accountAllowLocalHTTP
            )
            .font(.headline)
            .padding(.horizontal, DulcetSpacing.md)
            .padding(.vertical, DulcetSpacing.sm)
            .background(Color.dulcetControl.opacity(0.72), in: RoundedRectangle(cornerRadius: 16))
            .accessibilityHint(DulcetStrings.allowLocalHTTPHint)
            .focused($focusedControl, equals: .allowLocalHTTP)

            Text(DulcetStrings.tvTextEntryHint)
                .font(.callout)
                .dulcetForeground(.secondaryTextOnWindow)
                .lineLimit(nil)
        }
        .disabled(isConnecting)
        .padding(DulcetSpacing.lg)
        .background(.thinMaterial, in: RoundedRectangle(cornerRadius: 24))
        .focusSection()
    }
#endif

    @ViewBuilder
    private var statusPanel: some View {
        switch store.snapshot.accountRemoval {
        case .removing:
            accountRemovalProgress
        case .failed:
            accountRemovalFailure
        case .idle:
            connectionStatusPanel
        }
    }

    @ViewBuilder
    private var connectionStatusPanel: some View {
        switch store.snapshot.accountConnection {
        case .idle, .saved, .connecting:
            accountPrimaryActionPanel
        case let .connected(account):
            VStack(alignment: .leading, spacing: DulcetSpacing.md) {
                Label {
                    VStack(alignment: .leading, spacing: DulcetSpacing.xxs) {
                        Text(DulcetStrings.connectedTo(account.serverName))
                            .font(.headline)
                        Text(account.normalizedServerURL)
                            .font(.callout.monospaced())
                            .dulcetForeground(.secondaryTextOnWindow)
                            .dulcetSelectableText()
                    }
                } icon: {
                    Image(systemName: "checkmark.circle.fill")
                        .font(.title2)
                        .dulcetForeground(.accentIconOnWindow)
                }
                .accessibilityElement(children: .combine)

                Button(DulcetStrings.signOut, role: .destructive) {
                    showingSignOutConfirmation = true
                }
                .buttonStyle(.bordered)
            }
        case let .failed(failure):
            failurePanel(failure)
        }
    }

    private var accountRemovalProgress: some View {
        HStack(alignment: .top, spacing: DulcetSpacing.md) {
            ProgressView()
                .controlSize(.small)
                .accessibilityLabel(DulcetStrings.signingOut)
            VStack(alignment: .leading, spacing: DulcetSpacing.xxs) {
                Text(DulcetStrings.signingOut)
                    .font(.headline)
                Text(DulcetStrings.signingOutBody)
                    .font(.callout)
                    .dulcetForeground(.secondaryTextOnControl)
                    .lineLimit(nil)
            }
        }
        .padding(DulcetSpacing.md)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(Color.dulcetControl.opacity(0.52), in: RoundedRectangle(cornerRadius: 12))
        .dulcetForeground(.primaryTextOnControl)
    }

    private var accountRemovalFailure: some View {
        VStack(alignment: .leading, spacing: DulcetSpacing.md) {
            Label {
                VStack(alignment: .leading, spacing: DulcetSpacing.xs) {
                    Text(DulcetStrings.signOutErrorTitle)
                        .font(.headline)
                    Text(DulcetStrings.signOutErrorBody)
                        .font(.callout)
                        .lineLimit(nil)
                }
            } icon: {
                Image(systemName: "exclamationmark.triangle.fill")
                    .dulcetForeground(.dangerIconOnTint)
                    .accessibilityHidden(true)
            }

            HStack(spacing: DulcetSpacing.sm) {
                Button(DulcetStrings.tryAgain) {
                    store.removeAccount()
                }
                .buttonStyle(.borderedProminent)
                Button(DulcetStrings.keepAccount) {
                    store.dismissAccountRemovalFailure()
                }
                .buttonStyle(.bordered)
            }
        }
        .padding(DulcetSpacing.md)
        .background(Color.dulcetControl.opacity(0.52), in: RoundedRectangle(cornerRadius: 12))
        .dulcetForeground(.primaryTextOnControl)
    }

    private var accountPrimaryActionPanel: some View {
        VStack(alignment: .leading, spacing: DulcetSpacing.sm) {
            if let savedServerName, !isConnecting {
                Text(DulcetStrings.reconnectToServer(savedServerName))
                    .font(.headline)
                    .lineLimit(nil)
                Text(DulcetStrings.savedAccountReconnectBody)
                    .font(.callout)
                    .dulcetForeground(.secondaryTextOnWindow)
                    .lineLimit(nil)
            }

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
                isConnecting
                    ? DulcetStrings.cancel
                    : (savedServerName == nil ? DulcetStrings.connect : DulcetStrings.reconnect),
                systemImage: isConnecting ? "xmark" : "link"
            )
        }
        .buttonStyle(.borderedProminent)
        .dulcetDefaultActionShortcut()
        .accessibilityIdentifier("dulcet.account-connect.primary-action")
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
                .dulcetDefaultActionShortcut()
                .focused($focusedControl, equals: .tryAgain)

                if failure.kind == .tlsUntrusted {
#if os(tvOS)
                    EmptyView()
#else
                    Link(DulcetStrings.openCertificateHelp, destination: DulcetLinks.certificateInstallationGuide)
                        .buttonStyle(.bordered)
#endif
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
        case .saved:
            .primaryAction
        case .connecting:
            .primaryAction
        case .connected:
            nil
        case .failed:
            .tryAgain
        }
    }

}

/// Which credential a field collects, so onscreen text entry can use the right
/// keyboard and content type.
enum DulcetCredentialInputKind: Sendable {
    case serverAddress
    case username
    case password
}

extension View {
    /// Text-input traits that only matter on platforms with system text entry.
    ///
    /// On macOS this is deliberately the identity transform: there is no
    /// autocapitalization and no software keyboard, so applying nothing keeps the
    /// deterministic macOS capture byte-identical.
    ///
    /// On iOS and tvOS the SwiftUI defaults are actively WRONG for credentials, and both
    /// failures break a real login rather than merely looking untidy. Sentence
    /// capitalization rewrites `https://…` as `HTTPS://…` and a lowercase username
    /// as `Username`; autocorrect can silently substitute a hostname. Measured on
    /// an iPhone 17 Pro simulator 2026-08-23 by typing into the live field — the
    /// build was green and the view rendered correctly, so only driving the UI
    /// surfaced it.
    func dulcetCredentialInput(_ kind: DulcetCredentialInputKind) -> some View {
        #if os(iOS) || os(tvOS)
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

#if os(tvOS)
private extension View {
    func dulcetTVTextField(focused: Bool) -> some View {
        textFieldStyle(.plain)
            .font(.title3)
            .padding(.horizontal, DulcetSpacing.md)
            .padding(.vertical, DulcetSpacing.sm)
            .background(Color.dulcetControl, in: RoundedRectangle(cornerRadius: 16))
            .overlay {
                RoundedRectangle(cornerRadius: 16)
                    .stroke(
                        focused ? Color.dulcetAccent : Color.dulcetSeparator.opacity(0.7),
                        lineWidth: focused ? 4 : 1
                    )
            }
            .scaleEffect(focused ? 1.025 : 1)
            .animation(.easeOut(duration: 0.16), value: focused)
    }
}
#endif

#if os(iOS) || os(tvOS)
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
