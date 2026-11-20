#if os(macOS) || os(iOS) || os(tvOS)
import SwiftUI

/// The one message a page shows in place of content it does not have: empty, first run, loading,
/// an error with nothing cached, offline with nothing cached. It is the system's
/// `ContentUnavailableView` with the system's fonts on every platform -- no size or weight
/// overrides -- so every such page reads as one design.
///
/// Layout: the message centres in the space it is given and scrolls when it is taller than that
/// (the largest text sizes, a phone with the keyboard up), inside its own vertical scroll view.
/// Give it the whole content area. Inside a page that already scrolls (a list's empty body under
/// its header) call `.embeddedInScrollingPage()`: it then takes no scroll view of its own, and
/// sits `DulcetSpacing.xxl` below what is above it, the same offset on every such page. The body
/// wraps at `DulcetMetrics.stateMessageMaxWidth`.
///
/// Actions: at most one prominent action, which the `action:` initializer draws as a large
/// `DulcetProminentAction`. A page with a second, lesser action (Skip beside Try Again) passes
/// both through the `actions:` builder: the first as
/// `DulcetProminentAction(..., controlSize: .large)`, the second as a `.bordered` button at
/// `.controlSize(.large)`.
///
/// ```swift
/// DulcetStateMessage(
///     symbol: "wifi.slash",
///     title: DulcetStrings.serverUnreachableTitle(name),
///     message: DulcetStrings.savedAccountDisconnectedFootnote,
///     action: .init(DulcetStrings.reconnect, systemImage: "arrow.clockwise") { reconnect() }
/// )
/// DulcetStateMessage(progress: DulcetStrings.libraryLoadingTitle, message: DulcetStrings.libraryLoadingBody)
/// ```
///
/// A page that has content and is merely offline or stale uses `DulcetDestinationStatus` under its
/// title instead, never this.
struct DulcetStateMessage<Actions: View>: View {
    @Environment(\.dynamicTypeSize) private var dynamicTypeSize
    enum Glyph: Equatable {
        /// An SF Symbol, drawn by the system at its state-message size.
        case symbol(String)
        /// An indeterminate progress indicator, for a page that is loading.
        case progress
    }

    let glyph: Glyph
    let title: String
    var message: String?
    /// Identifies the message as a whole, for UI tests.
    var accessibilityIdentifier: String?
    private var embedded = false
    private var unreachableServer: String?
    @ViewBuilder let actions: () -> Actions

    init(
        symbol: String,
        title: String,
        message: String? = nil,
        accessibilityIdentifier: String? = nil,
        @ViewBuilder actions: @escaping () -> Actions
    ) {
        glyph = .symbol(symbol)
        self.title = title
        self.message = message
        self.accessibilityIdentifier = accessibilityIdentifier
        self.actions = actions
    }

    /// For a message inside a page that already scrolls: no scroll view of its own, a fixed
    /// offset from the content above it.
    func embeddedInScrollingPage() -> Self {
        var copy = self
        copy.embedded = true
        return copy
    }

    /// At accessibility sizes give the server its own line at the description's text size.
    func identifyingUnreachableServer(_ name: String?) -> Self {
        var copy = self
        copy.unreachableServer = name
        return copy
    }

    private var serverInDescription: String? {
        dynamicTypeSize.isAccessibilitySize ? unreachableServer : nil
    }

    var body: some View {
        if embedded {
            unavailableView
                .frame(maxWidth: .infinity)
                .padding(.vertical, DulcetSpacing.xxl)
        } else {
            GeometryReader { proxy in
                ScrollView {
                    unavailableView
                        .frame(maxWidth: .infinity, minHeight: proxy.size.height)
                }
                .scrollBounceBehavior(.basedOnSize)
            }
            .background(Color.dulcetWindow)
        }
    }

    private var unavailableView: some View {
        ContentUnavailableView {
            // At accessibility sizes the symbol gives way to the words: at the largest sizes it
            // alone took a quarter of a phone screen and pushed the body under the search field.
            // The Label stays (with no icon) because the view styles its title and description
            // from it; a bare Text title came out regular weight over a primary-coloured body.
            Label {
                Text(serverInDescription == nil ? title : DulcetStrings.serverUnreachableGenericTitle)
                    .fixedSize(horizontal: false, vertical: true)
            } icon: {
                switch glyph {
                case let .symbol(name):
                    if !dynamicTypeSize.isAccessibilitySize {
                        Image(systemName: name)
                            .accessibilityHidden(true)
                    }
                case .progress:
                    ProgressView()
                        .controlSize(.large)
                        .accessibilityLabel(title)
                }
            }
        } description: {
            if let serverInDescription {
                VStack(spacing: DulcetSpacing.xs) {
                    Text(verbatim: serverInDescription)
                        .lineLimit(1)
                        .truncationMode(.middle)
                        .accessibilityLabel(Text(verbatim: serverInDescription))
                    if let message { Text(message).fixedSize(horizontal: false, vertical: true) }
                }
                .dulcetForeground(.secondaryTextOnWindow)
                .frame(maxWidth: DulcetMetrics.stateMessageMaxWidth)
            } else if let message {
                Text(message)
                    .dulcetForeground(.secondaryTextOnWindow)
                    .fixedSize(horizontal: false, vertical: true)
                    .frame(maxWidth: DulcetMetrics.stateMessageMaxWidth)
            }
        } actions: {
            actions()
        }
        .accessibilityElement(children: .contain)
        .dulcetOptionalAccessibilityIdentifier(accessibilityIdentifier)
    }
}

extension DulcetStateMessage where Actions == DulcetStateMessageAction {
    /// A message with no action, or one prominent action.
    init(
        symbol: String,
        title: String,
        message: String? = nil,
        accessibilityIdentifier: String? = nil,
        action: DulcetStateAction? = nil
    ) {
        glyph = .symbol(symbol)
        self.title = title
        self.message = message
        self.accessibilityIdentifier = accessibilityIdentifier
        actions = { DulcetStateMessageAction(action: action) }
    }

    /// A page that is loading: a large progress indicator in place of the symbol.
    init(progress title: String, message: String? = nil, accessibilityIdentifier: String? = nil) {
        glyph = .progress
        self.title = title
        self.message = message
        self.accessibilityIdentifier = accessibilityIdentifier
        actions = { DulcetStateMessageAction(action: nil) }
    }
}

/// The one prominent action of a state message.
struct DulcetStateAction {
    let title: String
    var systemImage: String?
    var isDefaultAction = true
    var accessibilityIdentifier: String?
    /// A spoken label fuller than the visible title ("Reconnect to music.example.com").
    var accessibilityLabel: String?
    let handler: () -> Void

    init(
        _ title: String,
        systemImage: String? = nil,
        isDefaultAction: Bool = true,
        accessibilityIdentifier: String? = nil,
        accessibilityLabel: String? = nil,
        handler: @escaping () -> Void
    ) {
        self.title = title
        self.systemImage = systemImage
        self.isDefaultAction = isDefaultAction
        self.accessibilityIdentifier = accessibilityIdentifier
        self.accessibilityLabel = accessibilityLabel
        self.handler = handler
    }
}


/// The single prominent action slot of `DulcetStateMessage`, at the large control size.
struct DulcetStateMessageAction: View {
    let action: DulcetStateAction?

    var body: some View {
        if let action {
            DulcetProminentAction(
                action.title,
                systemImage: action.systemImage,
                isDefaultAction: action.isDefaultAction,
                controlSize: .large,
                accessibilityIdentifier: action.accessibilityIdentifier,
                action: action.handler
            )
            .accessibilityLabel(action.accessibilityLabel ?? action.title)
        }
    }
}
#endif
