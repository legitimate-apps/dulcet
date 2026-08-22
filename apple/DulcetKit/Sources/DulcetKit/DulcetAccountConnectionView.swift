#if os(macOS)
import SwiftUI

enum DulcetAccountConnectionFocus: String, Sendable {
    case serverAddress
    case username
    case password
    case allowLocalHTTP
    case connect
    case cancel
    case tryAgain
}

struct DulcetAccountConnectionView: View {
    @Bindable var store: DulcetPresentationStore
    @FocusState private var focusedControl: DulcetAccountConnectionFocus?

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
        .onAppear {
            if focusedControl == nil {
                focusedControl = preferredFocus(for: store.snapshot.accountConnection)
            }
        }
        .onChange(of: store.snapshot.accountConnection) { previous, current in
            switch (previous, current) {
            case (_, .connecting):
                focusedControl = .cancel
            case (.connecting, .idle):
                focusedControl = .connect
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
                    .accessibilityLabel(DulcetStrings.serverAddress)
                    .focused($focusedControl, equals: .serverAddress)
                }
                GridRow {
                    Text(DulcetStrings.username)
                    TextField(DulcetStrings.username, text: $store.accountUsername)
                        .textFieldStyle(.roundedBorder)
                        .accessibilityLabel(DulcetStrings.username)
                        .focused($focusedControl, equals: .username)
                }
                GridRow {
                    Text(DulcetStrings.password)
                    SecureField(DulcetStrings.password, text: $store.accountPassword)
                        .textFieldStyle(.roundedBorder)
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
        case .idle:
            VStack(alignment: .leading, spacing: DulcetSpacing.sm) {
                Button(DulcetStrings.connect, systemImage: "link") {
                    store.submitAccountConnection()
                }
                .buttonStyle(.borderedProminent)
                .keyboardShortcut(.defaultAction)
                .focused($focusedControl, equals: .connect)
                .disabled(
                    store.accountServerURL.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
                        || store.accountUsername.isEmpty
                        || store.accountPassword.isEmpty
                )
                Text(DulcetStrings.accountCredentialFootnote)
                    .font(.footnote)
                    .dulcetForeground(.secondaryTextOnWindow)
                    .lineLimit(nil)
            }
        case .connecting:
            HStack(alignment: .center, spacing: DulcetSpacing.md) {
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
                Spacer(minLength: DulcetSpacing.md)
                Button(DulcetStrings.cancel, role: .cancel) {
                    store.cancelAccountConnection()
                }
                .keyboardShortcut(.cancelAction)
                .focused($focusedControl, equals: .cancel)
            }
            .padding(DulcetSpacing.md)
            .background(Color.dulcetControl.opacity(0.52), in: RoundedRectangle(cornerRadius: 12))
            .dulcetForeground(.primaryTextOnControl)
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
            .cancel
        case .connected:
            nil
        case .failed:
            .tryAgain
        }
    }

}
#endif
