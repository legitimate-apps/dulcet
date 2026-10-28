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
#if os(iOS)
    @Environment(\.horizontalSizeClass) private var horizontalSizeClass
    @Environment(\.dynamicTypeSize) private var dynamicTypeSize
#endif
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

    private var primaryFocus: DulcetAccountConnectionFocus {
        if case .failed = store.snapshot.accountConnection { return .tryAgain }
        return .primaryAction
    }

    private var isConnecting: Bool {
        if case .connecting = store.snapshot.accountConnection { return true }
        return false
    }

    private var showsLocalHTTPConsent: Bool {
        store.accountServerURL.trimmingCharacters(in: .whitespacesAndNewlines)
            .lowercased().hasPrefix("http://")
    }

    private var savedServerName: String? {
        if case let .saved(serverName) = store.snapshot.accountConnection { return serverName }
        return nil
    }

    private func setInitialFocusIfNeeded() {
        guard allowsProgrammaticFocus, focusedControl == nil else { return }
#if os(iOS)
        // On a phone this form is a tab. Focusing a field on arrival raises the keyboard over
        // the tab bar every time the tab is chosen, so a compact window waits for a tap.
        guard horizontalSizeClass != .compact else { return }
#endif
        let initialFocus = preferredFocus(for: store.snapshot.accountConnection)
        focusedControl = initialFocus
        // onChange does not replay initial focus; report it at its source.
        focusDidChange?(initialFocus)
    }

    var body: some View {
        accountSurface
        .modifier(DulcetAccountSheetClose())
        .background(Color.dulcetWindow)
        .dulcetForeground(.primaryTextOnWindow)
        // Connection is a launch surface: its navigation bar's identifier is how tests read the
        // launch section, so the bar stays (and the content begins below it).
        .dulcetNavigationTitle(DulcetStrings.accountSection, keepsTVNavigationBar: true)
#if !os(tvOS)
        .toolbar {
            if !store.snapshot.accountConnection.isConnected {
                ToolbarItem(placement: .confirmationAction) {
                    Button(isConnecting ? DulcetStrings.cancel : (savedServerName == nil ? DulcetStrings.signIn : DulcetStrings.reconnect)) {
                        performAccountPrimaryAction()
                    }
                    .disabled(!isConnecting && !canSubmit)
                    .keyboardShortcut(.defaultAction)
                    .accessibilityIdentifier("dulcet.account-connect.primary-action")
                    .focused($focusedControl, equals: primaryFocus)
                }
            }
        }
#endif
#if os(iOS)
        .navigationBarTitleDisplayMode(.inline)
#endif
#if os(iOS)
        // Dragging the form puts the keyboard away, uncovering the tab bar.
        .scrollDismissesKeyboard(.interactively)
#endif
#if os(macOS)
        .toolbarTitleDisplayMode(.inline)
        .dulcetOnExitCommand {
            if isConnecting { store.cancelAccountConnection() }
        }
#else
        .dulcetOnExitCommand(perform: isConnecting ? { store.cancelAccountConnection() } : nil)
#endif
        .onAppear {
#if os(macOS)
            // OBSERVED while running DulcetMacTests on a developer Mac, not on a CI runner:
            // synchronous initial focus during layout made AppKit reject a SwiftUI focus
            // proxy whose window was nil, and Shift-Tab traversal then failed. Deferring to
            // the next main-queue turn removed it. CI has not exhibited the rejection, so
            // treat this as a latent ordering bug that one environment surfaces -- not as a
            // fix for anything CI observed.
            DispatchQueue.main.async { setInitialFocusIfNeeded() }
#else
            setInitialFocusIfNeeded()
#endif
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
            Button(DulcetStrings.cancel, role: .cancel) {}.dulcetDefaultActionShortcut()
        } message: {
            Text(DulcetStrings.signOutConfirmationBody)
        }
    }

    @ViewBuilder
    private var savedServers: some View {
        if !store.savedServers.isEmpty {
            VStack(alignment: .leading, spacing: 12) {
                Text(DulcetStrings.savedServers).font(.headline)
                ForEach(store.savedServers) { server in
                    Button {
                        store.selectSavedServer(server.id)
                    } label: {
                        HStack {
                            VStack(alignment: .leading) {
                                Text(URL(string: server.serverURL)?.host ?? server.serverURL)
                                Text(server.username).font(.caption)
                            }
                            Spacer()
                            if server.isActive { Image(systemName: "checkmark").accessibilityLabel(DulcetStrings.currentServer) }
                        }
                        .contentShape(Rectangle())
                    }
                    .accessibilityIdentifier("dulcet.account.server.\(server.id)")
                    .disabled(isConnecting)
                }
                Button(DulcetStrings.addServer, systemImage: "plus") { store.addServer() }
                    .accessibilityIdentifier("dulcet.account.add-server")
                    .disabled(isConnecting)
            }
            // Several buttons share one Form cell. Automatic style gives the cell all their
            // actions; choosing a saved server must not also activate Add Server.
            .buttonStyle(.borderless)
        }
    }

    @ViewBuilder
    private var accountSurface: some View {
#if os(tvOS)
        ScrollView {
            VStack(alignment: .leading, spacing: 32) {
                if store.snapshot.accountRemoval == .removing {
                    accountRemovalProgress
                } else {
                    if case let .connected(account) = store.snapshot.accountConnection {
                        Form {
                            Section {
                                Label(account.serverName, systemImage: "person.crop.circle")
                                if let user = account.canonicalUsername { LabeledContent(DulcetStrings.username, value: user) }
                            }
                            Section {
                                NavigationLink { DulcetPlaybackSettingsPage(store: store) }
                                label: { Label(DulcetStrings.playbackSettings, systemImage: "play.circle") }
                                    .accessibilityIdentifier("dulcet.account.playback")
                                NavigationLink { DulcetDownloadsStoragePage(store: store) }
                                label: { Label(DulcetStrings.downloadsStorage, systemImage: "arrow.down.circle") }
                                    .accessibilityIdentifier("dulcet.account.downloadsStorage")
                            }
                            Section { savedServers }
                            Section {
                                Button(DulcetStrings.signOut, role: .destructive) { showingSignOutConfirmation = true }
                            }
                        }
                    } else {
                        savedServers
                        tvConnectionForm
                        statusPanel
                    }
                }
            }
            .padding(40)
            .frame(maxWidth: 1600)
            .frame(maxWidth: .infinity)
            .focusScope(accountFocusScope)
        }
#else
        nativeAccountForm

#endif
    }

#if !os(tvOS)
    private var canSubmit: Bool {
        !store.accountServerURL.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
            && !store.accountUsername.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
            && !store.accountPassword.isEmpty
    }

    private var nativeAccountForm: some View {
        Form {
            if store.snapshot.accountRemoval == .removing {
                Section { accountRemovalProgress }
            } else if store.snapshot.accountRemoval == .failed {
                Section { accountRemovalFailure }
            } else if case let .connected(account) = store.snapshot.accountConnection {
                Section {
                    Label(account.serverName, systemImage: "person.crop.circle")
                        .font(.headline)
                    if let user = account.canonicalUsername, !user.isEmpty {
                        LabeledContent(DulcetStrings.username, value: user)
                    }
                    LabeledContent(DulcetStrings.serverAddress, value: account.serverName)
                    Label(DulcetStrings.online, systemImage: "checkmark.circle")
                        .dulcetForeground(.secondaryTextOnWindow)
                        .accessibilityLabel(DulcetStrings.connectedTo(account.serverName))
                        .accessibilityIdentifier("dulcet.account.connected")
                }
                .accessibilityIdentifier("dulcet.account-connect.title")
                .accessibilityHint(store.librarySession?.serverReportsNoEpoch == true ? DulcetStrings.readerNoEpoch : String())
                Section {
                    NavigationLink { DulcetPlaybackSettingsPage(store: store) } label: {
                        Label(DulcetStrings.playbackSettings, systemImage: "play.circle")
                    }.accessibilityIdentifier("dulcet.account.playback")
                    if store.offlineLibraryStorage != nil {
                        NavigationLink { DulcetDownloadsStoragePage(store: store) } label: {
                            Label(DulcetStrings.downloadsStorage, systemImage: "arrow.down.circle")
                        }.accessibilityIdentifier("dulcet.account.downloadsStorage")
                    }
                }
                Section { savedServers }
                Section {
                    Button(DulcetStrings.signOut, role: .destructive) { showingSignOutConfirmation = true }
                        .accessibilityIdentifier("dulcet.account.signOut")
                }
            } else {
                if !store.savedServers.isEmpty { Section { savedServers } }
                Section {
                    connectionForm
                } footer: {
                    Text(DulcetStrings.accountShortFooter)
                }
                .accessibilityIdentifier("dulcet.account-connect.title")
                if case let .failed(failure) = store.snapshot.accountConnection,
                   failure.kind != .invalidServerURL && failure.kind != .invalidCredentials {
                    Section {
                        Label(failure.title, systemImage: "exclamationmark.triangle")
                        if failure.kind == .tlsUntrusted {
                            Link(DulcetStrings.openCertificateHelp, destination: DulcetLinks.certificateInstallationGuide)
                        }
                        if failure.kind == .localNetworkAccessDenied,
                           let settings = DulcetLinks.localNetworkSettings {
                            Link(DulcetStrings.openLocalNetworkSettings, destination: settings)
                                .accessibilityIdentifier("dulcet.account-connect.local-network-settings")
                        }
                    } footer: { Text(failure.recovery) }
                }
                if isConnecting { ProgressView(DulcetStrings.connecting) }
            }
        }
        .formStyle(.grouped)
        .accessibilityIdentifier("dulcet.account.settingsForm")
#if os(macOS)
        .frame(minWidth: 480, minHeight: 480)
#endif
    }
#endif

#if os(iOS)
    private var accountCredentialsSection: some View {
        Section {
            connectionForm
        } header: {
            Text(DulcetStrings.accountDetails)
                .accessibilityIdentifier("dulcet.account-connect.title")
        } footer: {
            Text(DulcetStrings.accountConnectBody)
        }
    }

    private var accountStatusSection: some View {
        Section {
            statusPanel.id("account-status")
        }
    }

#endif

#if !os(tvOS)
    private var connectionForm: some View {
        Group {
            credentialRow(DulcetStrings.serverAddress) {
                TextField(DulcetStrings.serverAddress, text: $store.accountServerURL, prompt: Text(DulcetStrings.serverAddressPlaceholder))
                    .labelsHidden()
                    .dulcetCredentialInput(.serverAddress)
                    .accessibilityLabel(DulcetStrings.serverAddress)
                    .accessibilityIdentifier("dulcet.account-connect.server-address")
                    .focused($focusedControl, equals: .serverAddress)
            }
            if case let .failed(failure) = store.snapshot.accountConnection, failure.kind == .invalidServerURL {
                Text(failure.title).font(.footnote).dulcetForeground(.primaryTextOnControl).accessibilityHint(failure.message)
            }
            credentialRow(DulcetStrings.username) {
                TextField(DulcetStrings.username, text: $store.accountUsername)
                    .labelsHidden()
                    .dulcetCredentialInput(.username)
                    .accessibilityLabel(DulcetStrings.username)
                    .accessibilityIdentifier("dulcet.account-connect.username")
                    .focused($focusedControl, equals: .username)
            }
            credentialRow(DulcetStrings.password) {
                SecureField(DulcetStrings.password, text: $store.accountPassword)
                    .labelsHidden()
                    .dulcetCredentialInput(.password)
                    .accessibilityLabel(DulcetStrings.password)
                    .accessibilityIdentifier("dulcet.account-connect.password")
                    .focused($focusedControl, equals: .password)
            }
            if case let .failed(failure) = store.snapshot.accountConnection, failure.kind == .invalidCredentials {
                Text(failure.title).font(.footnote).dulcetForeground(.primaryTextOnControl).accessibilityHint(failure.message)
            }
            if showsLocalHTTPConsent {
                Toggle(DulcetStrings.allowLocalHTTP, isOn: $store.accountAllowLocalHTTP)
                    .fixedSize(horizontal: false, vertical: true)
                    .accessibilityHint(DulcetStrings.allowLocalHTTPHint)
                    .focused($focusedControl, equals: .allowLocalHTTP)
                Text(DulcetStrings.allowLocalHTTPHint)
                    .font(.footnote)
                    .dulcetForeground(.secondaryTextOnControl)
                    .fixedSize(horizontal: false, vertical: true)
            }
        }
        .disabled(isConnecting)
        .onSubmit { if canSubmit && !isConnecting { performAccountPrimaryAction() } }
    }

    private func credentialRow<Control: View>(
        _ title: String, @ViewBuilder control: () -> Control
    ) -> some View {
#if os(macOS)
        LabeledContent(title) {
            control()
                .textFieldStyle(.roundedBorder)
                .labelsHidden()
                .multilineTextAlignment(.leading)
                .frame(maxWidth: 400)
        }
#else
        control()
            .textFieldStyle(.plain)
            .font(.body)
            .frame(maxWidth: .infinity, alignment: .leading)

#endif
    }
#endif

#if os(tvOS)
    private var tvConnectionForm: some View {
        VStack(alignment: .leading, spacing: DulcetSpacing.md) {
            Text(DulcetStrings.accountDetails)
                .font(.title2.weight(.semibold))
                .accessibilityIdentifier("dulcet.account-connect.title")
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
                    .onSubmit { focusedControl = showsLocalHTTPConsent ? .allowLocalHTTP : .primaryAction }
                    .accessibilityLabel(DulcetStrings.password)
                    .accessibilityIdentifier("dulcet.account-connect.password")
                    .focused($focusedControl, equals: .password)
            }

            if showsLocalHTTPConsent {
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
            }

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

            VStack(alignment: .leading, spacing: DulcetSpacing.sm) {
                Button(DulcetStrings.tryAgain) {
                    store.removeAccount()
                }
                .buttonStyle(.borderedProminent)
                .dulcetConnectionProminentLabel()
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

            if isConnecting { connectingStatus }
#if os(macOS)
            HStack {
                Spacer()
                accountPrimaryAction
            }
#elseif os(tvOS)
            accountPrimaryAction
#endif

            if !isConnecting {
                Text(DulcetStrings.accountCredentialFootnote)
                    .font(.footnote)
                    .dulcetForeground(.secondaryTextOnWindow)
                    .lineLimit(nil)
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
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
#if os(macOS)
            .frame(minWidth: 96)
#else
            .frame(maxWidth: .infinity, minHeight: 32)
#endif
        }
        .controlSize(.large)
        .buttonStyle(.borderedProminent)
        .dulcetConnectionProminentLabel()
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
            failureDetails(failure)

            VStack(alignment: .leading, spacing: DulcetSpacing.sm) {
                Button {
                    store.submitAccountConnection()
                } label: {
                    Text(DulcetStrings.tryAgain)
#if os(macOS)
                        .frame(minWidth: 96)
#else
                        .frame(maxWidth: .infinity, minHeight: 32)
#endif
                }
                .controlSize(.large)
                .buttonStyle(.borderedProminent)
                .dulcetConnectionProminentLabel()
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

#if !os(tvOS)
                // tvOS has no link out to Settings; its recovery text names where to go.
                if failure.kind == .localNetworkAccessDenied,
                   let settings = DulcetLinks.localNetworkSettings {
                    Link(DulcetStrings.openLocalNetworkSettings, destination: settings)
                        .buttonStyle(.bordered)
                        .accessibilityIdentifier("dulcet.account-connect.local-network-settings")
                }
#endif
            }
#if os(macOS)
            .frame(maxWidth: .infinity, alignment: .trailing)
#endif
        }
        .padding(DulcetSpacing.md)
        .fixedSize(horizontal: false, vertical: true)
        .dulcetForeground(.primaryTextOnControl)
    }

    @ViewBuilder
    private func failureDetails(_ failure: DulcetAccountFailurePresentation) -> some View {
#if os(iOS)
        if dynamicTypeSize.isAccessibilitySize {
            VStack(alignment: .leading, spacing: DulcetSpacing.xs) {
                HStack(alignment: .top, spacing: DulcetSpacing.sm) {
                    failureSymbol
                    failureTitle(failure)
                }
                // Explanations use the full row at large text, rather than an icon column.
                failureExplanation(failure)
            }
        } else {
            horizontalFailureDetails(failure)
        }
#else
        horizontalFailureDetails(failure)
#endif
    }

    private func horizontalFailureDetails(_ failure: DulcetAccountFailurePresentation) -> some View {
        HStack(alignment: .top, spacing: DulcetSpacing.sm) {
            failureSymbol
            VStack(alignment: .leading, spacing: DulcetSpacing.xs) {
                failureTitle(failure)
                failureExplanation(failure)
            }
        }
    }

    private var failureSymbol: some View {
        ZStack {
            Circle()
                .fill(Color.dulcetDanger.opacity(0.11))
                .frame(width: 42, height: 42)
            Image(systemName: "exclamationmark.triangle.fill")
                .font(.title2)
                .dulcetForeground(.dangerIconOnTint)
                .accessibilityHidden(true)
        }
    }

    private func failureTitle(_ failure: DulcetAccountFailurePresentation) -> some View {
        Text(failure.title)
            .font(.headline)
            .lineLimit(nil)
            .accessibilityAddTraits(.isHeader)
    }

    private func failureExplanation(_ failure: DulcetAccountFailurePresentation) -> some View {
        VStack(alignment: .leading, spacing: DulcetSpacing.xs) {
            Text(failure.message)
                .lineLimit(nil)
            Text(failure.recovery)
                .font(.callout)
                .dulcetForeground(.secondaryTextOnControl)
                .lineLimit(nil)
        }
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

private extension View {
    /// The form's own foreground is the window's text colour, which a prominent button would
    /// otherwise inherit: dark text on the accent fill. A phone draws the label in the registered
    /// on-accent colour instead. Other platforms keep the system's treatment, which this change has
    /// not measured there.
    @ViewBuilder
    func dulcetConnectionProminentLabel() -> some View {
#if os(iOS)
        dulcetForeground(.labelOnAccentFill)
#else
        self
#endif
    }
}
