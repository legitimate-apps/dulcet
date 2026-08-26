#if os(macOS) || os(iOS) || os(tvOS)
#if os(macOS)
import AppKit
#elseif os(iOS) || os(tvOS)
import UIKit
#endif
import SwiftUI

enum DulcetSpacing {
#if os(tvOS)
    static let xxs: CGFloat = 8
    static let xs: CGFloat = 12
    static let sm: CGFloat = 18
    static let md: CGFloat = 24
    static let lg: CGFloat = 36
    static let xl: CGFloat = 52
    static let xxl: CGFloat = 72
#else
    static let xxs: CGFloat = 4
    static let xs: CGFloat = 8
    static let sm: CGFloat = 12
    static let md: CGFloat = 16
    static let lg: CGFloat = 24
    static let xl: CGFloat = 32
    static let xxl: CGFloat = 44
#endif
}

enum DulcetMetrics {
    static let sidebarMinWidth: CGFloat = 210
    static let artworkCornerRadius: CGFloat = 12
    static let denseRowArtworkSize: CGFloat = 28
    static let denseRowSeparatorInset: CGFloat = 36
    static let denseRowVerticalPadding: CGFloat = 2
    static let captureWidth: CGFloat = 1180
    static let captureHeight: CGFloat = 760
}

extension Color {
#if os(macOS)
    static let dulcetAccent = Color(nsColor: DulcetContrastColor.accent)
    static let dulcetSecondaryText = Color(nsColor: DulcetContrastColor.secondaryText)
    static let dulcetOffline = Color(nsColor: DulcetContrastColor.offline)
    static let dulcetDanger = Color(nsColor: DulcetContrastColor.danger)
    static let dulcetWindow = Color(nsColor: .windowBackgroundColor)
    static let dulcetControl = Color(nsColor: .controlBackgroundColor)
    static let dulcetSeparator = Color(nsColor: .separatorColor)
#elseif os(iOS)
    static let dulcetAccent = Color(uiColor: DulcetContrastColor.accent)
    static let dulcetSecondaryText = Color(uiColor: DulcetContrastColor.secondaryText)
    static let dulcetOffline = Color(uiColor: DulcetContrastColor.offline)
    static let dulcetDanger = Color(uiColor: DulcetContrastColor.danger)
    static let dulcetWindow = Color(uiColor: .systemBackground)
    static let dulcetControl = Color(uiColor: .secondarySystemBackground)
    static let dulcetSeparator = Color(uiColor: .separator)
#elseif os(tvOS)
    static let dulcetAccent = Color(uiColor: DulcetContrastColor.accent)
    static let dulcetSecondaryText = Color(uiColor: DulcetContrastColor.secondaryText)
    static let dulcetOffline = Color(uiColor: DulcetContrastColor.offline)
    static let dulcetDanger = Color(uiColor: DulcetContrastColor.danger)
    static let dulcetWindow = Color(uiColor: UIColor { traits in
        traits.userInterfaceStyle == .light
            ? UIColor(red: 0.93, green: 0.94, blue: 0.96, alpha: 1)
            : UIColor(red: 0.035, green: 0.043, blue: 0.065, alpha: 1)
    })
    static let dulcetControl = Color(uiColor: UIColor { traits in
        traits.userInterfaceStyle == .light
            ? UIColor(red: 0.84, green: 0.86, blue: 0.90, alpha: 1)
            : UIColor(red: 0.11, green: 0.13, blue: 0.18, alpha: 1)
    })
    static let dulcetSeparator = Color(uiColor: UIColor { traits in
        traits.userInterfaceStyle == .light
            ? UIColor.black.withAlphaComponent(0.22)
            : UIColor.white.withAlphaComponent(0.24)
    })
#endif
}

enum DulcetContrastColor {
#if os(macOS)
    static let accent = adaptive(
        name: "DulcetAccent",
        light: NSColor(red: 0.20, green: 0.34, blue: 0.78, alpha: 1),
        dark: NSColor(red: 0.47, green: 0.64, blue: 1.00, alpha: 1)
    )
    static let secondaryText = adaptive(
        name: "DulcetSecondaryText",
        light: NSColor(red: 0.36, green: 0.36, blue: 0.38, alpha: 1),
        dark: NSColor(red: 0.74, green: 0.74, blue: 0.77, alpha: 1)
    )
    static let offline = adaptive(
        name: "DulcetOffline",
        light: NSColor(red: 0.52, green: 0.27, blue: 0.02, alpha: 1),
        dark: NSColor(red: 1.00, green: 0.68, blue: 0.28, alpha: 1)
    )
    static let danger = adaptive(
        name: "DulcetDanger",
        light: NSColor(red: 0.68, green: 0.10, blue: 0.14, alpha: 1),
        dark: NSColor(red: 1.00, green: 0.46, blue: 0.49, alpha: 1)
    )

    private static func adaptive(name: String, light: NSColor, dark: NSColor) -> NSColor {
        NSColor(name: NSColor.Name(name)) { appearance in
            appearance.bestMatch(from: [.darkAqua, .aqua]) == .darkAqua ? dark : light
        }
    }
#elseif os(iOS) || os(tvOS)
    static let accent = adaptive(
        light: UIColor(red: 0.20, green: 0.34, blue: 0.78, alpha: 1),
        dark: UIColor(red: 0.47, green: 0.64, blue: 1.00, alpha: 1)
    )
    static let secondaryText = adaptive(
        light: UIColor(red: 0.36, green: 0.36, blue: 0.38, alpha: 1),
        dark: UIColor(red: 0.74, green: 0.74, blue: 0.77, alpha: 1)
    )
    static let offline = adaptive(
        light: UIColor(red: 0.52, green: 0.27, blue: 0.02, alpha: 1),
        dark: UIColor(red: 1.00, green: 0.68, blue: 0.28, alpha: 1)
    )
    static let danger = adaptive(
        light: UIColor(red: 0.68, green: 0.10, blue: 0.14, alpha: 1),
        dark: UIColor(red: 1.00, green: 0.46, blue: 0.49, alpha: 1)
    )

    private static func adaptive(light: UIColor, dark: UIColor) -> UIColor {
        UIColor { traits in
            traits.userInterfaceStyle == .dark ? dark : light
        }
    }
#endif
}

/// Explicit authored color pairs. Native-control and state-driven pairs are outside this registry.
enum DulcetRegisteredContrastPair: String, CaseIterable, Hashable, Sendable {
    case primaryTextOnWindow = "primary-text/window"
    case secondaryTextOnWindow = "secondary-text/window"
    case primaryTextOnControl = "primary-text/control"
    case secondaryTextOnControl = "secondary-text/control"
    case primaryTextOnThinMaterial = "primary-text/thin-material"
    case secondaryTextOnThinMaterial = "secondary-text/thin-material"
    case primaryTextOnRegularMaterial = "primary-text/regular-material"
    case secondaryTextOnRegularMaterial = "secondary-text/regular-material"
    case accentIconOnWindow = "accent-icon/window"
    case accentIconOnTint = "accent-icon/accent-tint"
    case offlineLabelOnControl = "offline-label/control"
    case primaryTextOnOfflineTint = "primary-text/offline-tint"
    case secondaryTextOnOfflineTint = "secondary-text/offline-tint"
    case offlineIconOnTint = "offline-icon/offline-tint"
    case dangerIconOnTint = "danger-icon/danger-tint"

    var foreground: Color {
        switch self {
        case .primaryTextOnWindow, .primaryTextOnControl, .primaryTextOnOfflineTint,
             .primaryTextOnThinMaterial, .primaryTextOnRegularMaterial:
            .primary
        case .secondaryTextOnWindow, .secondaryTextOnControl, .secondaryTextOnOfflineTint,
             .secondaryTextOnThinMaterial, .secondaryTextOnRegularMaterial:
            .dulcetSecondaryText
        case .accentIconOnWindow, .accentIconOnTint:
            .dulcetAccent
        case .offlineLabelOnControl, .offlineIconOnTint:
            .dulcetOffline
        case .dangerIconOnTint:
            .dulcetDanger
        }
    }

    /// Ordered back-to-front to match the pixels under the rendered foreground.
    var backgroundLayers: [AnyShapeStyle] {
        switch self {
        case .primaryTextOnWindow, .secondaryTextOnWindow, .accentIconOnWindow:
            [AnyShapeStyle(Color.dulcetWindow)]
        case .primaryTextOnControl, .secondaryTextOnControl, .offlineLabelOnControl:
            [
                AnyShapeStyle(Color.dulcetWindow),
                AnyShapeStyle(Color.dulcetControl.opacity(0.52)),
            ]
        case .primaryTextOnThinMaterial, .secondaryTextOnThinMaterial:
            [AnyShapeStyle(Color.dulcetWindow), AnyShapeStyle(.thinMaterial)]
        case .primaryTextOnRegularMaterial, .secondaryTextOnRegularMaterial:
            [AnyShapeStyle(Color.dulcetWindow), AnyShapeStyle(.regularMaterial)]
        case .accentIconOnTint:
            [AnyShapeStyle(Color.dulcetWindow), AnyShapeStyle(Color.dulcetAccent.opacity(0.10))]
        case .offlineIconOnTint:
            [AnyShapeStyle(Color.dulcetWindow), AnyShapeStyle(Color.dulcetOffline.opacity(0.10))]
        case .primaryTextOnOfflineTint, .secondaryTextOnOfflineTint:
            [AnyShapeStyle(Color.dulcetWindow), AnyShapeStyle(Color.dulcetOffline.opacity(0.10))]
        case .dangerIconOnTint:
            [AnyShapeStyle(Color.dulcetWindow), AnyShapeStyle(Color.dulcetDanger.opacity(0.11))]
        }
    }

    var minimumRatio: Double {
        switch self {
        case .accentIconOnWindow, .accentIconOnTint, .offlineIconOnTint, .dangerIconOnTint:
            3.0
        default:
            4.5
        }
    }
}

private struct DulcetRegisteredContrastPairPreferenceKey: PreferenceKey {
    static let defaultValue: Set<DulcetRegisteredContrastPair> = []

    static func reduce(
        value: inout Set<DulcetRegisteredContrastPair>,
        nextValue: () -> Set<DulcetRegisteredContrastPair>
    ) {
        value.formUnion(nextValue())
    }
}

extension View {
    func dulcetForeground(_ pair: DulcetRegisteredContrastPair) -> some View {
        foregroundStyle(pair.foreground) // dulcet-contrast-waiver: catalog-applier
            .transformPreference(DulcetRegisteredContrastPairPreferenceKey.self) { value in
                value.insert(pair)
            }
    }

    func onDulcetRegisteredContrastPairs(
        perform action: @escaping (Set<DulcetRegisteredContrastPair>) -> Void
    ) -> some View {
        onPreferenceChange(DulcetRegisteredContrastPairPreferenceKey.self, perform: action)
    }

    @ViewBuilder
    func dulcetOnExitCommand(perform action: @escaping () -> Void) -> some View {
#if os(macOS) || os(tvOS)
        onExitCommand(perform: action)
#else
        self
#endif
    }

    @ViewBuilder
    func dulcetLinkButtonStyle() -> some View {
#if os(macOS)
        buttonStyle(.link)
#else
        buttonStyle(.plain)
#endif
    }

    @ViewBuilder
    func dulcetAlternatingRowsDisabled() -> some View {
#if os(macOS)
        alternatingRowBackgrounds(.disabled)
#else
        self
#endif
    }

    @ViewBuilder
    func dulcetMediaButtonStyle() -> some View {
#if os(tvOS)
        buttonStyle(.borderless)
#else
        buttonStyle(.plain)
#endif
    }

    @ViewBuilder
    func dulcetSelectableText() -> some View {
#if os(macOS) || os(iOS)
        textSelection(.enabled)
#else
        self
#endif
    }

    @ViewBuilder
    func dulcetDefaultActionShortcut() -> some View {
#if os(tvOS)
        self
#else
        keyboardShortcut(.defaultAction)
#endif
    }

    @ViewBuilder
    func dulcetPlaybackShortcut() -> some View {
#if os(tvOS)
        self
#else
        keyboardShortcut(.space, modifiers: [])
#endif
    }

    @ViewBuilder
    func dulcetSecondaryActionStyle() -> some View {
#if os(tvOS)
        buttonStyle(.borderedProminent)
#else
        buttonStyle(.bordered)
#endif
    }
}

struct DulcetArtworkView: View {
    let artwork: DulcetArtwork
    let size: CGFloat
    var muted = false
    @Environment(DulcetPresentationStore.self) private var store
    @Environment(\.displayScale) private var displayScale
    @State private var loadedData: Data?
    @State private var operation: (any DulcetArtworkFetchOperation)?
    @State private var loadGeneration = 0

    var body: some View {
        ZStack {
            RoundedRectangle(cornerRadius: DulcetMetrics.artworkCornerRadius, style: .continuous)
                .fill(
                    LinearGradient(
                        colors: artwork.palette.colors,
                        startPoint: .topLeading,
                        endPoint: .bottomTrailing
                    )
                )

            Circle()
                .fill(.white.opacity(0.18))
                .frame(width: size * 0.72, height: size * 0.72)
                .offset(x: size * 0.22, y: -size * 0.20)

            RoundedRectangle(cornerRadius: size * 0.08, style: .continuous)
                .fill(.black.opacity(0.16))
                .frame(width: size * 0.68, height: size * 0.22)
                .rotationEffect(.degrees(artwork.rotation))
                .offset(x: -size * 0.16, y: size * 0.20)

            Image(systemName: artwork.symbolName)
                .font(.system(size: max(18, size * 0.23), weight: .medium))
                .symbolRenderingMode(.hierarchical)
                .foregroundStyle(.white.opacity(0.94)) // dulcet-contrast-waiver: decorative-artwork
                .accessibilityHidden(true)

            if let loadedImage {
                loadedImage
                    .resizable()
                    .interpolation(.high)
                    .scaledToFill()
                    .frame(width: size, height: size)
            }
        }
        .frame(width: size, height: size)
        .clipShape(RoundedRectangle(cornerRadius: DulcetMetrics.artworkCornerRadius, style: .continuous))
        .overlay {
            RoundedRectangle(cornerRadius: DulcetMetrics.artworkCornerRadius, style: .continuous)
                .stroke(.white.opacity(0.16), lineWidth: 1)
        }
        .saturation(muted ? 0.28 : 1)
        .accessibilityHidden(true)
        .onAppear(perform: startLoading)
        .onChange(of: loadIdentity) { _, _ in startLoading() }
        .onDisappear(perform: cancelLoading)
    }

    private var sizeBucket: DulcetArtworkSizeBucket {
        .containing(pixelSize: size * displayScale)
    }

    private var loadIdentity: DulcetArtworkLoadIdentity? {
        artwork.remoteReference.map {
            DulcetArtworkLoadIdentity(reference: $0, sizeBucket: sizeBucket)
        }
    }

    private var loadedImage: Image? {
        guard let loadedData else { return nil }
#if os(macOS)
        guard let image = NSImage(data: loadedData) else { return nil }
        return Image(nsImage: image)
#else
        guard let image = UIImage(data: loadedData) else { return nil }
        return Image(uiImage: image)
#endif
    }

    private func startLoading() {
        cancelLoading()
        loadedData = nil
        guard let reference = artwork.remoteReference else { return }
        loadGeneration += 1
        let generation = loadGeneration
        operation = store.loadArtwork(reference, sizeBucket: sizeBucket) { outcome in
            guard generation == loadGeneration else { return }
            operation = nil
            if case let .loaded(data) = outcome {
                loadedData = data
            }
        }
    }

    private func cancelLoading() {
        loadGeneration += 1
        operation?.cancel()
        operation = nil
    }
}

private struct DulcetArtworkLoadIdentity: Hashable {
    let reference: DulcetArtworkReference
    let sizeBucket: DulcetArtworkSizeBucket
}

struct DulcetStatusDot: View {
    let color: Color

    var body: some View {
        Circle()
            .fill(color)
            .frame(width: 8, height: 8)
            .overlay(Circle().stroke(.white.opacity(0.45), lineWidth: 1))
            .accessibilityHidden(true)
    }
}

extension DulcetArtworkPalette {
    var colors: [Color] {
        switch self {
        case .indigoCoral: [Color(red: 0.18, green: 0.24, blue: 0.55), Color(red: 0.89, green: 0.34, blue: 0.34)]
        case .mossGold: [Color(red: 0.16, green: 0.34, blue: 0.28), Color(red: 0.84, green: 0.62, blue: 0.18)]
        case .plumIce: [Color(red: 0.38, green: 0.18, blue: 0.46), Color(red: 0.43, green: 0.72, blue: 0.83)]
        case .oceanMint: [Color(red: 0.08, green: 0.34, blue: 0.52), Color(red: 0.36, green: 0.78, blue: 0.62)]
        case .emberRose: [Color(red: 0.46, green: 0.16, blue: 0.12), Color(red: 0.90, green: 0.41, blue: 0.57)]
        case .duskLavender: [Color(red: 0.15, green: 0.18, blue: 0.35), Color(red: 0.64, green: 0.49, blue: 0.82)]
        case .slateApricot: [Color(red: 0.23, green: 0.29, blue: 0.34), Color(red: 0.91, green: 0.58, blue: 0.35)]
        case .tealSun: [Color(red: 0.03, green: 0.39, blue: 0.40), Color(red: 0.90, green: 0.73, blue: 0.18)]
        }
    }
}

private extension DulcetArtwork {
    var rotation: Double {
        let value = seed.unicodeScalars.reduce(0) { ($0 + Int($1.value)) % 31 }
        return Double(value - 15)
    }

    var symbolName: String {
        let symbols = ["waveform", "music.note", "dot.radiowaves.left.and.right", "circle.hexagongrid"]
        let value = seed.unicodeScalars.reduce(0) { ($0 + Int($1.value)) % symbols.count }
        return symbols[value]
    }
}

extension Duration {
    var dulcetSeconds: Double {
        let components = self.components
        return Double(components.seconds) + Double(components.attoseconds) / 1e18
    }

    var dulcetDuration: String {
        let wholeSeconds = max(0, components.seconds)
        let hours = wholeSeconds / 3600
        let minutes = (wholeSeconds % 3600) / 60
        let seconds = wholeSeconds % 60
        if hours > 0 {
            return String(format: "%lld:%02lld:%02lld", hours, minutes, seconds)
        }
        return String(format: "%lld:%02lld", minutes, seconds)
    }
}
#endif
