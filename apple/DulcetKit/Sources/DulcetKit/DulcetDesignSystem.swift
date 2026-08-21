#if os(macOS)
import AppKit
import SwiftUI

enum DulcetSpacing {
    static let xxs: CGFloat = 4
    static let xs: CGFloat = 8
    static let sm: CGFloat = 12
    static let md: CGFloat = 16
    static let lg: CGFloat = 24
    static let xl: CGFloat = 32
    static let xxl: CGFloat = 44
}

enum DulcetMetrics {
    static let sidebarMinWidth: CGFloat = 210
    static let artworkCornerRadius: CGFloat = 12
    static let captureWidth: CGFloat = 1180
    static let captureHeight: CGFloat = 760
}

extension Color {
    static let dulcetAccent = Color(nsColor: DulcetContrastColor.accent)
    static let dulcetPrimaryActionFill = Color(nsColor: DulcetContrastColor.primaryActionFill)
    static let dulcetPrimaryActionLabel = Color(nsColor: DulcetContrastColor.primaryActionLabel)
    static let dulcetSelectionBackground = Color(nsColor: DulcetContrastColor.selectionBackground)
    static let dulcetSecondaryText = Color(nsColor: DulcetContrastColor.secondaryText)
    static let dulcetOffline = Color(nsColor: DulcetContrastColor.offline)
    static let dulcetDanger = Color(nsColor: DulcetContrastColor.danger)
    static let dulcetWindow = Color(nsColor: .windowBackgroundColor)
    static let dulcetControl = Color(nsColor: .controlBackgroundColor)
    static let dulcetSeparator = Color(nsColor: .separatorColor)
}

enum DulcetContrastColor {
    static let accent = adaptive(
        name: "DulcetAccent",
        light: NSColor(red: 0.20, green: 0.34, blue: 0.78, alpha: 1),
        dark: NSColor(red: 0.47, green: 0.64, blue: 1.00, alpha: 1)
    )
    static let primaryActionFill = adaptive(
        name: "DulcetPrimaryActionFill",
        light: NSColor(red: 0.20, green: 0.34, blue: 0.78, alpha: 1),
        dark: NSColor(red: 0.25, green: 0.38, blue: 0.72, alpha: 1)
    )
    static let primaryActionLabel = adaptive(
        name: "DulcetPrimaryActionLabel",
        light: .white,
        dark: .white
    )
    static let selectionBackground = adaptive(
        name: "DulcetSelectionBackground",
        light: NSColor(red: 0.82, green: 0.86, blue: 0.96, alpha: 1),
        dark: NSColor(red: 0.20, green: 0.27, blue: 0.43, alpha: 1)
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
}

/// Explicit authored color pairs. Native-control and state-driven pairs are outside this registry.
enum DulcetRegisteredContrastPair: String, CaseIterable, Hashable, Sendable {
    case primaryTextOnWindow = "primary-text/window"
    case primaryButtonLabelOnPrimaryActionFill = "primary-button-label/primary-action-fill"
    case selectedSidebarLabelOnSelectionFill = "selected-sidebar-label/selection-fill"
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
        case .primaryButtonLabelOnPrimaryActionFill:
            .dulcetPrimaryActionLabel
        case .primaryTextOnWindow, .primaryTextOnControl, .primaryTextOnOfflineTint,
             .primaryTextOnThinMaterial, .primaryTextOnRegularMaterial,
             .selectedSidebarLabelOnSelectionFill:
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
        case .primaryButtonLabelOnPrimaryActionFill:
            [AnyShapeStyle(Color.dulcetWindow), AnyShapeStyle(Color.dulcetPrimaryActionFill)]
        case .selectedSidebarLabelOnSelectionFill:
            [AnyShapeStyle(Color.dulcetWindow), AnyShapeStyle(Color.dulcetSelectionBackground)]
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
}

struct DulcetArtworkView: View {
    let artwork: DulcetArtwork
    let size: CGFloat
    var muted = false

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
        }
        .frame(width: size, height: size)
        .clipShape(RoundedRectangle(cornerRadius: DulcetMetrics.artworkCornerRadius, style: .continuous))
        .overlay {
            RoundedRectangle(cornerRadius: DulcetMetrics.artworkCornerRadius, style: .continuous)
                .stroke(.white.opacity(0.16), lineWidth: 1)
        }
        .saturation(muted ? 0.28 : 1)
        .accessibilityHidden(true)
    }
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

extension Int {
    var dulcetDuration: String {
        let hours = self / 3600
        let minutes = (self % 3600) / 60
        let seconds = self % 60
        if hours > 0 {
            return String(format: "%d:%02d:%02d", hours, minutes, seconds)
        }
        return String(format: "%d:%02d", minutes, seconds)
    }
}
#endif
