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

    /// Where focus goes when a connection fails: to the field the person has to change when the
    /// failure names one, as a system sign-in form returns to Password after a wrong password;
    /// otherwise to the retry. A TV form shows only its retry, so it always focuses that.
    static func after(_ failure: DulcetAccountFailureKind, fieldsShown: Bool) -> Self {
        guard fieldsShown else { return .tryAgain }
        switch failure {
        case .invalidCredentials: return .password
        case .invalidServerURL: return .serverAddress
        default: return .tryAgain
        }
    }
}

struct DulcetAccountConnectionView: View {
    @Bindable var store: DulcetPresentationStore
    @FocusState private var focusedControl: DulcetAccountConnectionFocus?
#if os(iOS)
    @Environment(\.horizontalSizeClass) private var horizontalSizeClass
    @Environment(\.dynamicTypeSize) private var dynamicTypeSize
#endif
    @State private var lastSubmissionTimestamp: TimeInterval?
    @State private var connectionState: DulcetConnectionsState?
    @State private var connectionHTTPSAddress = ""
    @State private var enrollingConnections = false
    @State private var connectionEnrollmentID: UUID?
#if os(tvOS)
    @Namespace private var accountFocusScope
    @Environment(DulcetArrivalFocus.self) private var arrivalFocus: DulcetArrivalFocus?
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

    private var primaryActionTitle: String {
        isConnecting ? DulcetStrings.cancel : (savedServerName == nil ? DulcetStrings.signIn : DulcetStrings.reconnect)
    }

    private var signOutConfirmationTitle: String {
        if case let .connected(account) = store.snapshot.accountConnection {
            return DulcetStrings.signOutConfirmationTitle(DulcetServerAddressDisplay.text(account.normalizedServerURL))
        }
        return DulcetStrings.signOutConfirmationTitle
    }

    /// Whether this page may move focus for itself. On Apple TV a person moving along the
    /// section bar keeps focus on the bar, as every other section does: Account claims focus
    /// only on an arrival the shell still offers (launch, or a change the app made) or once
    /// focus is already inside the page. Without this, landing on Account from the bar pulled
    /// focus to Reconnect and the bar could no longer be walked past it.
    private var mayMoveFocus: Bool {
        guard allowsProgrammaticFocus else { return false }
#if os(tvOS)
        if let arrivalFocus, arrivalFocus.barHoldsFocus, !arrivalFocus.pending { return false }
#endif
        return true
    }

    private func setInitialFocusIfNeeded() {
        guard mayMoveFocus, focusedControl == nil else { return }
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
#if os(tvOS)
        .dulcetForeground(.primaryTextOnWindow)
#endif
        // Connection is a launch surface: its navigation bar's identifier is how tests read the
        // launch section, so the bar stays (and the content begins below it).
        .dulcetNavigationTitle(DulcetStrings.accountSection, keepsTVNavigationBar: true)
#if os(iOS)
        .toolbar {
            if !store.snapshot.accountConnection.isConnected {
                ToolbarItem(placement: .confirmationAction) {
                    Button(primaryActionTitle) {
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
        // Either scroll direction dismisses input without requiring a downward sheet drag.
        .scrollDismissesKeyboard(.immediately)
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
            guard mayMoveFocus else { return }
            switch (previous, current) {
            case (_, .connecting):
                // The primary action keeps both its view and focus identity while its behavior
                // changes from Connect to Cancel, so there is no replacement control to focus.
                break
            case (.connecting, .idle):
                focusedControl = .primaryAction
            case (.connecting, .saved), (_, .saved):
                focusedControl = .primaryAction
            case let (_, .failed(failure)):
                focusedControl = .after(failure.kind, fieldsShown: Self.showsFieldsOnFailure)
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
            signOutConfirmationTitle,
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

    /// The connected server's product name ("Navidrome"), as the card above the list shows it.
    private var connectedServerName: String? {
        if case let .connected(account) = store.snapshot.accountConnection { return account.serverName }
        return nil
    }

#if os(tvOS)
    /// Saved servers as Apple TV Settings lists them: a secondary-text header above the group and
    /// each server, then Add Server, as its own full-width focusable row. Only a live session
    /// marks a server as the one in use (``DulcetSavedServerRow``).
    @ViewBuilder
    private var savedServerSection: some View {
        if !store.savedServers.isEmpty {
            Section {
                ForEach(store.savedServers) { server in
                    let row = DulcetSavedServerRow(server, connection: store.snapshot.accountConnection)
                    Button {
                        store.selectSavedServer(server.id)
                    } label: {
                        HStack {
                            VStack(alignment: .leading, spacing: 4) {
                                Text(row.text.title)
                                if !row.text.subtitle.isEmpty {
                                    // No colour of its own: a focused row's platter turns
                                    // light and the system restyles the whole label for it.
                                    Text(row.text.subtitle)
                                        .font(.caption)
                                        .lineLimit(1)
                                        .truncationMode(.middle)
                                }
                            }
                            Spacer()
                            if row.isSelected {
                                Image(systemName: "checkmark")
                                    .accessibilityLabel(DulcetStrings.currentServer)
                            }
                        }
                    }
                    .accessibilityIdentifier("dulcet.account.server.\(server.id)")
                    .disabled(isConnecting)
                }
                Button {
                    store.addServer()
                } label: {
                    Label(DulcetStrings.addServer, systemImage: "plus")
                }
                .accessibilityIdentifier("dulcet.account.add-server")
                .disabled(isConnecting)
            } header: {
                Text(DulcetStrings.savedServers)
            }
        }
    }
#else
    /// Saved servers, one row each under a system section header, with Add Server as a tinted
    /// action row after them.
    @ViewBuilder
    private var savedServerSection: some View {
        if !store.savedServers.isEmpty {
            Section {
                ForEach(store.savedServers) { server in
                    Button {
                        store.selectSavedServer(server.id)
                    } label: {
                        HStack {
                            VStack(alignment: .leading, spacing: 2) {
                                let text = DulcetSavedServerRowText(server, connectedName: connectedServerName)
                                Text(text.title)
                                    .dulcetForeground(.primaryTextOnControl)
#if os(iOS)
                                if dynamicTypeSize.isAccessibilitySize {
                                    // Who and where on lines of their own, from the leading edge, the
                                    // address breaking only after a "." or ":": as one centred line
                                    // it wrapped as "dulcet- / admin · / 127.0.0.1:49 / 322" (round 11).
                                    ForEach(text.subtitleLines, id: \.self) { line in
                                        Text(DulcetServerAddressDisplay.breakable(line))
                                            .font(.subheadline)
                                            .dulcetForeground(.secondaryTextOnControl)
                                            .multilineTextAlignment(.leading)
                                            .frame(maxWidth: .infinity, alignment: .leading)
                                            .accessibilityLabel(line)
                                    }
                                } else {
                                    Text(text.subtitle)
                                        .font(.subheadline)
                                        .dulcetForeground(.secondaryTextOnControl)
                                        .lineLimit(1)
                                        .truncationMode(.middle)
                                }
#else
                                Text(text.subtitle)
                                    .font(.subheadline)
                                    .dulcetForeground(.secondaryTextOnControl)
                                    .lineLimit(1)
                                    .truncationMode(.middle)
#endif
                            }
                            Spacer()
                            if server.isActive {
                                Image(systemName: "checkmark")
                                    .dulcetForeground(.accentIconOnWindow)
                                    .accessibilityLabel(DulcetStrings.currentServer)
                            }
                        }
                        .contentShape(Rectangle())
                    }
                    .buttonStyle(.borderless)
                    .accessibilityIdentifier("dulcet.account.server.\(server.id)")
                    .disabled(isConnecting)
                }
                Button {
                    store.addServer()
                } label: {
                    Label(DulcetStrings.addServer, systemImage: "plus")
                        .dulcetForeground(.accentTextOnWindow)
                        .frame(maxWidth: .infinity, alignment: .leading)
                        .contentShape(Rectangle())
                }
                .buttonStyle(.borderless)
                .accessibilityIdentifier("dulcet.account.add-server")
                .disabled(isConnecting)
            } header: {
                Text(DulcetStrings.savedServers)
            }
        }
    }
#endif

    /// The connections control in the native grouped style: routes as rows, the HTTPS address
    /// as a field row, actions as row buttons and the privacy note as the footer. iPhone, iPad
    /// and TV reach it as a page from the account, like Playback; a Mac Settings pane has no
    /// navigation stack, so it shows the section inline. It drives the same manager, state and
    /// identifiers wherever it appears.
    @ViewBuilder
    private var connectionsFormSection: some View {
        if let manager = store.connectionsManager, manager.currentOwnership != nil {
#if os(macOS)
            connectionsControls(manager)
#else
            Section {
                NavigationLink {
                    Form { connectionsControls(manager) }
                        .formStyle(.grouped)
                        .navigationTitle(DulcetStrings.connectionsTitle)
                        .accessibilityIdentifier("dulcet.connections")
                } label: {
                    Label(DulcetStrings.connectionsTitle, systemImage: "network")
                }
                .accessibilityIdentifier("dulcet.account.connections")
                .dulcetAccessibilityNavigationRowInsets()
            }
#endif
        }
    }

    /// `inline` is the signed-out Apple TV form, where the section sits among the sign-in rows
    /// and needs its own header; its page form takes the title from the page.
    private func connectionsControls(_ manager: any DulcetAccountConnectionsManaging, inline: Bool = false) -> some View {
        Section {
            if let connectionState, connectionState.ownership == manager.currentOwnership {
                ForEach(connectionState.routes.filter { !$0.removed }) { route in
                    LabeledContent {
                        Text(route.selected ? DulcetStrings.connectionCurrentHTTPS
                            : (route.enabled ? DulcetStrings.connectionVerifiedHTTPS : DulcetStrings.connectionUnavailable))
                    } label: {
                        Text(route.label ?? route.address)
                        if route.label != nil { Text(route.address) }
                    }
                    Button(DulcetStrings.removeConnection, role: .destructive) {
                        self.connectionState = manager.remove(ownership: connectionState.ownership, routeID: route.id)
                    }
                    .accessibilityIdentifier("dulcet.connections.remove.\(route.id)")
                }
                if let error = connectionState.error {
                    Label {
                        Text(error)
                    } icon: {
                        Image(systemName: "exclamationmark.triangle.fill")
                            .dulcetForeground(.dangerIconOnTint)
                    }
                }
#if os(macOS)
                // A Mac form row reads as a field only with its edge and a prompt in it; bare,
                // the label sat alone and read as static text.
                TextField(text: $connectionHTTPSAddress, prompt: Text(DulcetStrings.serverAddressPlaceholder)) {
                    Text(DulcetStrings.connectionHTTPSAddress)
                }
                .textFieldStyle(.roundedBorder)
                .dulcetCredentialInput(.serverAddress)
                .accessibilityIdentifier("dulcet.connections.https-address")
#else
                TextField(DulcetStrings.connectionHTTPSAddress, text: $connectionHTTPSAddress)
                    .dulcetCredentialInput(.serverAddress)
                    .accessibilityIdentifier("dulcet.connections.https-address")
#endif
                connectionsActionRow {
                    Button(connectionState.canUpgrade && !connectionState.enrolled ? DulcetStrings.upgradeConnectionHTTPS : DulcetStrings.addHTTPSConnection) { enrollConnections(manager, url: connectionHTTPSAddress) }
                        .disabled(!DulcetConnectionsEnrollmentInput.canSubmit(address: connectionHTTPSAddress, enrolling: enrollingConnections))
                        .accessibilityIdentifier(connectionState.canUpgrade && !connectionState.enrolled ? "dulcet.connections.upgrade" : "dulcet.connections.add")
                }
                if connectionState.verificationAddress != nil {
                    connectionsActionRow {
                        Button(DulcetStrings.verifyConnections) { enrollConnections(manager, url: nil) }
                            .disabled(enrollingConnections)
                            .accessibilityIdentifier("dulcet.connections.verify")
                    }
                }
                if connectionState.enrolled && !connectionState.routes.contains(where: { $0.selected && $0.enabled && !$0.removed }) {
                    Label(DulcetStrings.connectionsOfflineDownloads, systemImage: "arrow.down.circle")
                }
            }
            if enrollingConnections { ProgressView() }
        } header: {
#if os(macOS)
            // Inline in the Mac Account pane; elsewhere the page's title already names it.
            Text(DulcetStrings.connectionsTitle)
#else
            if inline {
                Text(DulcetStrings.connectionsTitle)
                    .accessibilityIdentifier("dulcet.connections")
            }
#endif
        } footer: {
            Text(DulcetStrings.connectionsPrivacy)
        }
        .task(id: manager.currentOwnership) {
            connectionEnrollmentID = nil
            enrollingConnections = false
            connectionHTTPSAddress = ""
            connectionState = manager.restore()
        }
    }

    /// A Mac Settings form puts a row's action at its trailing edge; elsewhere the action is the
    /// row itself.
    @ViewBuilder
    private func connectionsActionRow<Action: View>(@ViewBuilder _ action: () -> Action) -> some View {
#if os(macOS)
        HStack {
            Spacer()
            action()
        }
#else
        action()
#endif
    }

    private func enrollConnections(_ manager: any DulcetAccountConnectionsManaging, url: String?) {
        guard let owner = connectionState?.ownership, owner == manager.currentOwnership else { return }
        let enrollmentID = UUID()
        connectionEnrollmentID = enrollmentID
        enrollingConnections = true
        manager.enroll(ownership: owner, httpsURL: url) { result in
            guard connectionEnrollmentID == enrollmentID, manager.currentOwnership == owner else { return }
            connectionState = result
            enrollingConnections = false
            connectionEnrollmentID = nil
        }
    }

    @ViewBuilder
    private var accountSurface: some View {
#if os(tvOS)
        // One grouped form in every state, as tvOS Settings draws a page: secondary-text section
        // headers over full-width focusable rows, one type scale, and content that scrolls below
        // the page title rather than under it.
        Form {
            if case let .connected(account) = store.snapshot.accountConnection, store.snapshot.accountRemoval != .removing {
                Section {
                    LabeledContent(DulcetStrings.serverAddress, value: DulcetServerAddressDisplay.text(account.normalizedServerURL))
                    if let user = account.canonicalUsername { LabeledContent(DulcetStrings.username, value: user) }
                    // The connected state, as iPhone and iPad show it: Online, read as
                    // "Connected to <server>".
                    Label {
                        Text(DulcetStrings.online)
                    } icon: {
                        Image(systemName: "checkmark.circle.fill")
                            .dulcetForeground(.onlineIconOnWindow)
                    }
                    .accessibilityLabel(DulcetStrings.connectedTo(account.serverName))
                    .accessibilityIdentifier("dulcet.account.connected")
                }
                connectionsFormSection
                Section {
                    NavigationLink { DulcetPlaybackSettingsPage(store: store) }
                    label: { Label(DulcetStrings.playbackSettings, systemImage: "play.circle") }
                        .accessibilityIdentifier("dulcet.account.playback")
                }
                savedServerSection
                Section {
                    // An ordinary row: the system's destructive row draws dim text on Apple TV,
                    // which reads as disabled. The confirmation's own button stays destructive.
                    Button {
                        showingSignOutConfirmation = true
                    } label: {
                        Label {
                            Text(DulcetStrings.signOut)
                        } icon: {
                            Image(systemName: "rectangle.portrait.and.arrow.right")
                                .dulcetForeground(.dangerIconOnWindow)
                        }
                    }
                    .accessibilityIdentifier("dulcet.account.signOut")
                }
            } else if store.snapshot.accountRemoval == .removing {
                Section { accountRemovalProgress }
            } else {
                // Reconnect is what someone disconnected came for: first on the page, where focus
                // arrives and the screen does not cut it off. It stays there through Connecting and
                // a failure, so the action never moves under the person's focus.
                if savedServerName != nil { tvStatusSection }
                savedServerSection
                tvConnectionForm
                if savedServerName == nil { tvStatusSection }
                if let manager = store.connectionsManager, manager.currentOwnership != nil {
                    connectionsControls(manager, inline: true)
                }
            }
        }
        .focusScope(accountFocusScope)
#elseif os(macOS)
        // A Mac form submits from a default push button at its bottom trailing edge, as the
        // system's account sign-in forms do; the window's toolbar stays the pane picker.
        VStack(spacing: 0) {
            nativeAccountForm
            if !store.snapshot.accountConnection.isConnected { macPrimaryActionBar }
        }
#else
        nativeAccountForm
#endif
    }

#if os(macOS)
    private var macPrimaryActionBar: some View {
        HStack(spacing: DulcetSpacing.sm) {
            if isConnecting {
                ProgressView()
                    .controlSize(.small)
                    .accessibilityHidden(true)
            }
            Spacer()
            Button(primaryActionTitle) {
                performAccountPrimaryAction()
            }
            .disabled(!isConnecting && !canSubmit)
            .keyboardShortcut(.defaultAction)
            .accessibilityIdentifier("dulcet.account-connect.primary-action")
            .focused($focusedControl, equals: primaryFocus)
        }
        .padding(.horizontal, 20)
        .padding(.bottom, 16)
    }
#endif

#if !os(tvOS)
    private var canSubmit: Bool {
        !store.accountServerURL.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
            && !store.accountUsername.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
            && !store.accountPassword.isEmpty
    }

    /// A failure's section sits under the fields and their notes, below the fold at the largest
    /// text sizes, while Sign In stays in the bar: without this a refused attempt showed nothing.
    /// The progress row sits at the same place, so an attempt under way is brought into view too.
    private var nativeAccountForm: some View {
        ScrollViewReader { proxy in
            nativeAccountFormContent
                .onChange(of: anyFailureTitle) { _, title in
                    guard title != nil else { return }
                    // A field's own error sits under that field, so it is centred with the field in
                    // view; the progress row's scroll has left the form at its bottom by then.
                    let anchor: UnitPoint = shownFailureTitle == nil ? .center : .top
                    // On the next turn: the row joins the list in this update.
                    DispatchQueue.main.async {
                        withAnimation { proxy.scrollTo(Self.failureRowID, anchor: anchor) }
                    }
                }
                .onChange(of: isConnecting) { _, connecting in
                    guard connecting else { return }
                    DispatchQueue.main.async {
                        withAnimation { proxy.scrollTo(Self.progressRowID, anchor: .bottom) }
                    }
                }
        }
    }

    private static let failureRowID = "dulcet.account-connect.failure"
    private static let progressRowID = "dulcet.account-connect.progress"

    private var anyFailureTitle: String? {
        guard case let .failed(failure) = store.snapshot.accountConnection else { return nil }
        return failure.title
    }

    private var shownFailureTitle: String? {
        guard case let .failed(failure) = store.snapshot.accountConnection,
              failure.kind != .invalidServerURL && failure.kind != .invalidCredentials else { return nil }
        return failure.title
    }

    private var nativeAccountFormContent: some View {
        Form {
            if store.snapshot.accountRemoval == .removing {
                Section { accountRemovalProgress }
            } else if store.snapshot.accountRemoval == .failed {
                Section { accountRemovalFailure }
            } else if case let .connected(account) = store.snapshot.accountConnection {
                Section {
                    Label(account.serverName, systemImage: "server.rack")
                        .font(.headline)
                        .accessibilityIdentifier("dulcet.account-connect.title")
                    if let user = account.canonicalUsername, !user.isEmpty {
                        // Selectable like the address under it: the system draws a selectable
                        // value a shade darker than a plain one, and these two sit together.
                        LabeledContent(DulcetStrings.username) { Text(user).dulcetSelectableText() }
                    }
                    // The address the account connects to, without credentials or query.
                    connectedServerAddress(account.normalizedServerURL)
                    Label {
                        Text(DulcetStrings.online)
                    } icon: {
                        // Green, as Settings marks a working connection: the accent is the
                        // selected server's checkmark in the Servers list below.
                        Image(systemName: "checkmark.circle.fill")
                            .dulcetForeground(.onlineIconOnWindow)
                    }
                    .accessibilityLabel(DulcetStrings.connectedTo(account.serverName))
                    .accessibilityIdentifier("dulcet.account.connected")
                }
                .accessibilityHint(store.librarySession?.serverReportsNoEpoch == true ? DulcetStrings.readerNoEpoch : String())
                connectionsFormSection
#if !os(macOS)
                // A Mac reaches these as its Settings window's own panes, never as links here.
                Section {
                    NavigationLink { DulcetPlaybackSettingsPage(store: store) } label: {
                        Label(DulcetStrings.playbackSettings, systemImage: "play.circle")
                    }.accessibilityIdentifier("dulcet.account.playback")
                    .dulcetAccessibilityNavigationRowInsets()
                    if store.offlineLibraryStorage != nil {
                        NavigationLink { DulcetDownloadsStoragePage(store: store) } label: {
                            Label(DulcetStrings.downloadsStorage, systemImage: "arrow.down.circle")
                        }.accessibilityIdentifier("dulcet.account.downloadsStorage")
                        .dulcetAccessibilityNavigationRowInsets()
                    }
                }
#endif
                savedServerSection
                Section {
                    signOutButton
                }
            } else {
                if savedServerName != nil && !isConnecting {
                    Section {
                        Label {
                            Text(DulcetStrings.notConnected)
                        } icon: {
                            Image(systemName: "wifi.slash")
                                .dulcetForeground(.offlineIconOnTint)
                        }
                        .accessibilityIdentifier("dulcet.account.notConnected")
                    } footer: {
                        Text(DulcetStrings.savedAccountReconnectBody)
                    }
                }
                savedServerSection
                connectionsFormSection
                Section {
                    connectionForm
                } footer: {
                    VStack(alignment: .leading, spacing: DulcetSpacing.xxs) {
                        if showsLocalHTTPConsent { Text(DulcetStrings.allowLocalHTTPHint) }
                        Text(DulcetStrings.accountShortFooter)
                            .accessibilityIdentifier("dulcet.account-connect.title")
                    }
                }
                if case let .failed(failure) = store.snapshot.accountConnection,
                   failure.kind != .invalidServerURL && failure.kind != .invalidCredentials {
                    Section {
                        Label {
                            Text(failure.title)
                        } icon: {
                            Image(systemName: "exclamationmark.triangle.fill")
                                .dulcetForeground(.dangerIconOnTint)
                        }
                        .id(Self.failureRowID)
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
                if isConnecting { ProgressView(DulcetStrings.connecting).id(Self.progressRowID) }
            }
        }
        .formStyle(.grouped)
#if os(iOS)
        .labelStyle(DulcetStackedAtAccessibilitySizesLabelStyle())
#endif
        .accessibilityIdentifier("dulcet.account.settingsForm")
#if os(macOS)
        .fixedSize(horizontal: false, vertical: true)
        .frame(minWidth: 480)
#endif
    }

    @ViewBuilder
    private func connectedServerAddress(_ address: String) -> some View {
#if os(iOS)
        if dynamicTypeSize.isAccessibilitySize {
            let shown = DulcetServerAddressDisplay.text(address)
            VStack(alignment: .leading, spacing: 8) {
                Text(DulcetStrings.serverAddress)
                // An address short enough for one line shrinks into it rather than losing the
                // last digit of its port to a second; a long one wraps at full size.
                Text(shown)
                    .dulcetForeground(.secondaryTextOnControl)
                    .lineLimit(shown.count <= 24 ? 1 : nil)
                    .minimumScaleFactor(shown.count <= 24 ? 0.6 : 1)
                    .fixedSize(horizontal: false, vertical: shown.count > 24)
                    .dulcetSelectableText()
            }
        } else {
            LabeledContent(DulcetStrings.serverAddress) {
                Text(DulcetServerAddressDisplay.text(address)).dulcetSelectableText()
            }
        }
#else
        LabeledContent(DulcetStrings.serverAddress) {
            Text(DulcetServerAddressDisplay.text(address)).dulcetSelectableText()
        }
#endif
    }

    /// The final section's action. A phone or tablet shows the system's red, centred row; a Mac
    /// shows an ordinary push button, as its account panes do. Both confirm first.
    private var signOutButton: some View {
#if os(macOS)
        HStack {
            Button(DulcetStrings.signOut) { showingSignOutConfirmation = true }
                .accessibilityIdentifier("dulcet.account.signOut")
            Spacer()
        }
#else
        Button(role: .destructive) {
            showingSignOutConfirmation = true
        } label: {
            Text(DulcetStrings.signOut)
                .frame(maxWidth: .infinity)
        }
        .tint(.red)
        .accessibilityIdentifier("dulcet.account.signOut")
#endif
    }

    private func fieldError(_ failure: DulcetAccountFailurePresentation) -> some View {
        Label {
            Text(failure.title)
        } icon: {
            Image(systemName: "exclamationmark.circle.fill")
                .dulcetForeground(.dangerIconOnTint)
        }
        .font(.footnote)
        .dulcetForeground(.primaryTextOnControl)
        .accessibilityHint(failure.message)
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
                fieldError(failure).id(Self.failureRowID)
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
                fieldError(failure).id(Self.failureRowID)
            }
            if showsLocalHTTPConsent {
                Toggle(DulcetStrings.allowLocalHTTP, isOn: $store.accountAllowLocalHTTP)
                    .fixedSize(horizontal: false, vertical: true)
                    .accessibilityHint(DulcetStrings.allowLocalHTTPHint)
                    .focused($focusedControl, equals: .allowLocalHTTP)
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
    /// The sign-in fields as rows of the page's form, under a secondary-text header, in the
    /// same type as every other row. Each field is its own focusable row.
    private var tvConnectionForm: some View {
        Section {
            TextField(DulcetStrings.serverAddressPlaceholder, text: $store.accountServerURL)
                .dulcetCredentialInput(.serverAddress)
                .submitLabel(.next)
                .onSubmit { focusedControl = .username }
                .accessibilityLabel(DulcetStrings.serverAddress)
                .accessibilityIdentifier("dulcet.account-connect.server-address")
                .textFieldStyle(.plain)
                .font(.body)
                .focused($focusedControl, equals: .serverAddress)
                .prefersDefaultFocus(in: accountFocusScope)
            TextField(DulcetStrings.username, text: $store.accountUsername)
                .dulcetCredentialInput(.username)
                .submitLabel(.next)
                .onSubmit { focusedControl = .password }
                .accessibilityLabel(DulcetStrings.username)
                .accessibilityIdentifier("dulcet.account-connect.username")
                .textFieldStyle(.plain)
                .font(.body)
                .focused($focusedControl, equals: .username)
            SecureField(DulcetStrings.password, text: $store.accountPassword)
                .dulcetCredentialInput(.password)
                .submitLabel(.next)
                .onSubmit { focusedControl = showsLocalHTTPConsent ? .allowLocalHTTP : .primaryAction }
                .accessibilityLabel(DulcetStrings.password)
                .accessibilityIdentifier("dulcet.account-connect.password")
                .textFieldStyle(.plain)
                .font(.body)
                .focused($focusedControl, equals: .password)
            if showsLocalHTTPConsent {
                Toggle(DulcetStrings.allowLocalHTTP, isOn: $store.accountAllowLocalHTTP)
                    .accessibilityHint(DulcetStrings.allowLocalHTTPHint)
                    .focused($focusedControl, equals: .allowLocalHTTP)
            }
        } header: {
            Text(DulcetStrings.accountDetails)
                .accessibilityIdentifier("dulcet.account-connect.title")
                .accessibilityAddTraits(.isHeader)
        } footer: {
            Text(DulcetStrings.tvTextEntryHint)
        }
        .disabled(isConnecting)
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
                        .dulcetForeground(.onlineIconOnWindow)
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

#if os(tvOS)
    /// The signed-out form's action, as rows of the form: the sign-in or reconnect action is its
    /// own focusable row, with the saved server it reconnects to as the section's header and its
    /// notes as the footer. A failure or a failed sign-out keeps its panel.
    @ViewBuilder
    private var tvStatusSection: some View {
        switch store.snapshot.accountRemoval {
        case .failed:
            Section { accountRemovalFailure }
        case .removing:
            Section { accountRemovalProgress }
        case .idle:
            switch store.snapshot.accountConnection {
            case let .failed(failure):
                Section { failurePanel(failure) }
            case .connected:
                EmptyView()
            case .idle, .saved, .connecting:
                Section {
                    if isConnecting { connectingStatus }
                    accountPrimaryAction
                } header: {
                    if let savedServerName, !isConnecting {
                        Text(DulcetStrings.reconnectToServer(savedServerName))
                    }
                } footer: {
                    if !isConnecting {
                        VStack(alignment: .leading, spacing: DulcetSpacing.xs) {
                            if savedServerName != nil { Text(DulcetStrings.savedAccountReconnectBody) }
                            Text(DulcetStrings.accountCredentialFootnote)
                        }
                    }
                }
            }
        }
    }
#endif

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
#elseif os(iOS)
            .frame(maxWidth: .infinity, minHeight: 32)
#endif
        }
#if !os(tvOS)
        // Apple TV draws it as a row of its form, which dims when it cannot be used.
        .controlSize(.large)
        .buttonStyle(.borderedProminent)
#endif
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
        case let .failed(failure):
            .after(failure.kind, fieldsShown: Self.showsFieldsOnFailure)
        }
    }

    /// iPhone, iPad and Mac keep the form on screen under a failure; a TV shows its retry alone.
    private static var showsFieldsOnFailure: Bool {
#if os(tvOS)
        false
#else
        true
#endif
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
