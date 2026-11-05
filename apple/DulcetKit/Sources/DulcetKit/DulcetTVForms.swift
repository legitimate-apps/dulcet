#if os(tvOS)
import SwiftUI

/// Apple TV's one action control, for every button a page offers beside its content: a quiet glass
/// shape at rest, white with dark words under focus, lifted a little. A single treatment, so a
/// page's Play, Shuffle, heart and More read as one row.
struct DulcetTVActionButtonStyle: ButtonStyle {
    enum Shape { case capsule, circle }
    static let height: CGFloat = 66
    var shape: Shape = .capsule
    /// The height of a capsule, the diameter of a circle.
    var side: CGFloat = DulcetTVActionButtonStyle.height
    /// A transport keeps its glyphs bare at rest, as the system's player does.
    var hasRestingPlate = true

    func makeBody(configuration: Configuration) -> some View {
        ActionBody(configuration: configuration, shape: shape, side: side, hasRestingPlate: hasRestingPlate)
    }

    private struct ActionBody: View {
        let configuration: Configuration
        let shape: Shape
        let side: CGFloat
        let hasRestingPlate: Bool
        @Environment(\.isFocused) private var isFocused
        @Environment(\.isEnabled) private var isEnabled

        var body: some View {
            sized
                .font(.callout.weight(.semibold))
                .lineLimit(1)
                .environment(\.colorScheme, isFocused ? .light : .dark)
                .background(isFocused ? Color.white : Color.white.opacity(hasRestingPlate ? 0.14 : 0), in: Capsule())
                .contentShape(Capsule())
                .scaleEffect(isFocused ? 1.06 : 1)
                .shadow(color: .black.opacity(isFocused ? 0.45 : 0), radius: isFocused ? 14 : 0, y: isFocused ? 8 : 0)
                .opacity(isEnabled ? (configuration.isPressed ? 0.7 : 1) : 0.45)
                .animation(.easeOut(duration: 0.16), value: isFocused)
        }

        @ViewBuilder private var sized: some View {
            switch shape {
            case .capsule:
                configuration.label
                    .padding(.horizontal, DulcetSpacing.md)
                    .frame(height: side)
            case .circle:
                configuration.label
                    .frame(width: side, height: side)
            }
        }
    }
}

enum DulcetTVDetailMetrics {
    static let artworkSize: CGFloat = 300
    static let leadingColumnWidth: CGFloat = 540
    static let columnGap: CGFloat = DulcetSpacing.xl
    /// Room inside the scrolling column for a lifted row's plate and a lifted tile.
    static let liftRoom: CGFloat = 36
}

/// Album, artist, playlist and genre on Apple TV: who or what it is and its actions held still on
/// the left, the content scrolling on the right. One scroll view on the page, so the section bar
/// follows only it.
struct DulcetTVDetailColumns<Header: View, Content: View>: View {
    let title: String
    @ViewBuilder let header: () -> Header
    /// Handed the width its content shares.
    @ViewBuilder let content: (CGFloat) -> Content

    var body: some View {
        GeometryReader { geometry in
            let inset = DulcetLibraryMetrics.horizontalInset(forWidth: geometry.size.width)
            let contentWidth = max(0, geometry.size.width - inset * 2
                - DulcetTVDetailMetrics.leadingColumnWidth - DulcetTVDetailMetrics.columnGap)
            HStack(alignment: .top, spacing: DulcetTVDetailMetrics.columnGap - DulcetTVDetailMetrics.liftRoom) {
                header()
                    .frame(width: DulcetTVDetailMetrics.leadingColumnWidth, alignment: .topLeading)
                    .padding(.top, DulcetSpacing.md)
                    .focusSection()
                ScrollView {
                    VStack(alignment: .leading, spacing: DulcetSpacing.md) {
                        content(contentWidth)
                    }
                    .padding(.horizontal, DulcetTVDetailMetrics.liftRoom)
                    .padding(.vertical, DulcetSpacing.md)
                    .frame(maxWidth: .infinity, alignment: .leading)
                }
                // So the remote can move between the header's actions and the content at all.
                .focusSection()
            }
            .padding(.leading, inset)
            .padding(.trailing, inset - DulcetTVDetailMetrics.liftRoom)
        }
        .background(Color.dulcetWindow)
        .dulcetForeground(.primaryTextOnWindow)
        .dulcetNavigationTitle(title, keepsTVNavigationBar: false)
        // Pushed from a scrolled grid, the section bar would otherwise stay away, leaving its
        // empty band above a page that every other route shows under the bar.
        .toolbar(.visible, for: .tabBar)
    }
}

/// The left column's contents: artwork, name, the lines under it, one row of actions.
struct DulcetTVDetailHeader<Artwork: View, Detail: View, Actions: View>: View {
    let title: String
    let titleIdentifier: String
    @ViewBuilder let artwork: () -> Artwork
    @ViewBuilder let detail: () -> Detail
    @ViewBuilder let actions: () -> Actions

    var body: some View {
        VStack(alignment: .leading, spacing: DulcetSpacing.md) {
            artwork()
            VStack(alignment: .leading, spacing: DulcetSpacing.xxs) {
                Text(title)
                    .font(.title2.weight(.bold))
                    .lineLimit(3)
                    .fixedSize(horizontal: false, vertical: true)
                    .accessibilityAddTraits(.isHeader)
                    .accessibilityIdentifier(titleIdentifier)
                detail()
            }
            HStack(spacing: DulcetSpacing.sm) {
                actions()
            }
            .buttonStyle(DulcetTVActionButtonStyle())
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }
}

/// The row's More: the item's actions, in the row's circular control.
struct DulcetTVDetailMoreMenu<Items: View>: View {
    let identifier: String
    @ViewBuilder let items: () -> Items

    var body: some View {
        Menu { items() } label: { Image(systemName: "ellipsis") }
            .menuStyle(.button)
            .buttonStyle(DulcetTVActionButtonStyle(shape: .circle))
            .accessibilityLabel(DulcetStrings.more)
            .accessibilityIdentifier(identifier)
    }
}

/// Apple TV's one form: a centred column on the window, with room around its rows for focus to lift
/// them, and every heading, row and footnote on one leading edge.
struct DulcetTVFormPage<Content: View>: View {
    static var columnWidth: CGFloat { 1120 }
    @ViewBuilder let content: () -> Content

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: DulcetSpacing.lg) {
                content()
            }
            .frame(width: Self.columnWidth, alignment: .leading)
            .padding(.vertical, DulcetSpacing.md)
            .frame(maxWidth: .infinity)
        }
        .background(Color.dulcetWindow)
        .dulcetForeground(.primaryTextOnWindow)
    }
}

/// A heading, its rows and a footnote. Heading and footnote sit on the rows' words.
struct DulcetTVFormSection<Rows: View>: View {
    var title: String?
    var footnote: String?
    @ViewBuilder let rows: () -> Rows

    var body: some View {
        VStack(alignment: .leading, spacing: DulcetSpacing.xs) {
            if let title {
                Text(title)
                    .font(.headline)
                    .dulcetForeground(.secondaryTextOnWindow)
                    .padding(.horizontal, DulcetTVFocusedRowButtonStyle.plateInset)
                    .accessibilityAddTraits(.isHeader)
            }
            rows()
                .buttonStyle(DulcetTVFocusedRowButtonStyle(placement: .form))
            if let footnote {
                Text(footnote)
                    .font(.callout)
                    .dulcetForeground(.secondaryTextOnWindow)
                    .lineLimit(nil)
                    .fixedSize(horizontal: false, vertical: true)
                    .padding(.horizontal, DulcetTVFocusedRowButtonStyle.plateInset)
            }
        }
    }
}

/// A form row's label: its name, what is chosen, and where pressing it goes.
struct DulcetTVFormRowLabel: View {
    enum Trailing { case none, disclosure, checkmark }
    let title: String
    var systemImage: String?
    var value: String?
    var trailing: Trailing = .none

    var body: some View {
        HStack(spacing: DulcetSpacing.sm) {
            if let systemImage {
                Image(systemName: systemImage)
                    .dulcetForeground(.primaryTextOnWindow)
                    .accessibilityHidden(true)
            }
            Text(title)
                .dulcetForeground(.primaryTextOnWindow)
                .lineLimit(2)
            Spacer(minLength: DulcetSpacing.sm)
            if let value {
                Text(value)
                    .dulcetForeground(.secondaryTextOnWindow)
                    .lineLimit(1)
            }
            switch trailing {
            case .none: EmptyView()
            case .disclosure:
                Image(systemName: "chevron.forward")
                    .font(.caption.weight(.semibold))
                    .dulcetForeground(.secondaryTextOnWindow)
                    .accessibilityHidden(true)
            case .checkmark:
                Image(systemName: "checkmark")
                    .dulcetForeground(.primaryTextOnWindow)
                    .accessibilityHidden(true)
            }
        }
        .font(.callout)
        .frame(minHeight: 72)
        .frame(maxWidth: .infinity, alignment: .leading)
        .contentShape(Rectangle())
    }
}

extension View {
    /// A resting plate for a text field, which the system draws nearly invisible until it is
    /// focused. Under focus the system's own bright capsule takes over.
    func dulcetTVFieldPlate() -> some View {
        modifier(DulcetTVFieldPlate())
    }
}

private struct DulcetTVFieldPlate: ViewModifier {
    @FocusState private var focused: Bool

    func body(content: Content) -> some View {
        content
            .focused($focused)
            .background(Color.white.opacity(focused ? 0 : 0.14), in: Capsule())
    }
}

/// Reports whether a page's scroll view has left its top.
struct DulcetTVScrolledAwayReader: ViewModifier {
    @Binding var scrolled: Bool

    func body(content: Content) -> some View {
        if #available(tvOS 18.0, *) {
            content.onScrollGeometryChange(for: Bool.self) { geometry in
                geometry.contentOffset.y + geometry.contentInsets.top > 12
            } action: { _, isScrolled in
                scrolled = isScrolled
            }
        } else {
            content
        }
    }
}
#endif
