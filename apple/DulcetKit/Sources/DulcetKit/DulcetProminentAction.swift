#if os(macOS) || os(iOS) || os(tvOS)
import SwiftUI

/// The label of a Play button on tvOS and where a plain `Label` is drawn: Pause while the button's own
/// music plays.
struct DulcetPlayLabel: View {
    var isPlaying = false

    var body: some View {
        Label(isPlaying ? DulcetStrings.pause : DulcetStrings.play, systemImage: isPlaying ? "pause.fill" : "play.fill")
    }
}

/// The one prominent (accent-filled) action. Every filled button in the app is this component:
/// `tools/verify_design_tokens.py` rejects `.buttonStyle(.borderedProminent)` anywhere else, so the
/// label colour, size and keyboard default are decided once.
///
/// Label colour: on the accent fill the label takes the registered `labelOnAccentFill` pair -- the
/// window colour, light on the dark accent of light mode and dark on the light accent of dark
/// mode, where the platform's white label measured about 2.6:1. On the Mac that pair applies only
/// while the window is key and the action enabled: an inactive window or a disabled action draws
/// a grey bezel, and the system's own label is the readable one there (a forced pair measured
/// about 1.3:1 on the inactive bezel). tvOS keeps the platform label, whose focus engine owns it.
///
/// Size: `.regular` in forms and headers; `.large` for the one action of an empty, error or
/// first-run state (`DulcetStateMessage` passes it), about 50 pt tall on iPhone and iPad.
///
/// Put no foreground style on a container that holds this button; scope it to the `Text`s.
struct DulcetProminentAction: View {
    @Environment(\.dynamicTypeSize) private var dynamicTypeSize
    let title: String
    var systemImage: String? = nil
    var isEnabled = true
    var isDefaultAction = false
    /// The label fills the width it is offered, so the bezel fills an equal-width action row's
    /// share; a frame outside the button does not stretch it.
    var fillsWidth = false
    var controlSize: ControlSize = .regular
    var accessibilityIdentifier: String? = nil
    let action: () -> Void

    init(
        _ title: String,
        systemImage: String? = nil,
        isEnabled: Bool = true,
        isDefaultAction: Bool = false,
        fillsWidth: Bool = false,
        controlSize: ControlSize = .regular,
        accessibilityIdentifier: String? = nil,
        action: @escaping () -> Void
    ) {
        self.title = title
        self.systemImage = systemImage
        self.isEnabled = isEnabled
        self.isDefaultAction = isDefaultAction
        self.fillsWidth = fillsWidth
        self.controlSize = controlSize
        self.accessibilityIdentifier = accessibilityIdentifier
        self.action = action
    }

    var body: some View {
        Button(action: action) {
            if let systemImage {
                Label(title, systemImage: systemImage)
                    .frame(maxWidth: fillsWidth ? .infinity : nil)
            } else {
                Text(title)
                    .frame(maxWidth: fillsWidth ? .infinity : nil)
            }
        }
        .buttonStyle(.dulcetProminent)
        .controlSize(controlSize)
        // One line at ordinary sizes; at accessibility sizes the title wraps rather than
        // truncating ("Connect a...").
        .lineLimit(dynamicTypeSize.isAccessibilitySize ? 3 : 1)
        .multilineTextAlignment(.center)
        .disabled(!isEnabled)
        .dulcetDefaultActionShortcut(isDefaultAction)
        .dulcetOptionalAccessibilityIdentifier(accessibilityIdentifier)
    }
}

/// The prominent style itself, for a button `DulcetProminentAction` cannot express: one with a
/// role, a focus binding, a custom label layout, or a `Link`. Use it as
/// `.buttonStyle(.dulcetProminent)` and set `.controlSize(_:)` beside it; never
/// `.buttonStyle(.borderedProminent)` directly. It is `.borderedProminent` with the label colour
/// rule described on `DulcetProminentAction`.
struct DulcetProminentButtonStyle: PrimitiveButtonStyle {
    func makeBody(configuration: Configuration) -> some View {
        DulcetProminentButtonBody(configuration: configuration)
    }
}

extension PrimitiveButtonStyle where Self == DulcetProminentButtonStyle {
    static var dulcetProminent: DulcetProminentButtonStyle { DulcetProminentButtonStyle() }
}

private struct DulcetProminentButtonBody: View {
    let configuration: PrimitiveButtonStyleConfiguration
    @Environment(\.isEnabled) private var isEnabled
#if os(macOS)
    @Environment(\.controlActiveState) private var controlActiveState
#endif

    var body: some View {
        Button(role: configuration.role, action: configuration.trigger) {
            configuration.label.dulcetProminentLabelStyle(labelPair)
        }
        .buttonStyle(.borderedProminent)
    }

    /// Nil leaves the label to the system.
    private var labelPair: DulcetRegisteredContrastPair? {
#if os(macOS)
        guard isEnabled, controlActiveState == .key else { return nil }
        return .labelOnAccentFill
#elseif os(tvOS)
        return nil
#else
        return isEnabled ? .labelOnAccentFill : nil
#endif
    }
}

/// A collection header's Play. Music's album and playlist headers pair Play and Shuffle as two
/// equal buttons on iPhone, iPad and Mac; a filled Play beside a bordered Shuffle weighed far
/// more than it, and on a white Mac window the pale Shuffle all but vanished. TV keeps the
/// prominent action.
struct DulcetHeaderPlayAction: View {
    /// "Play" on a collection page; the Mac Library header says "Play All".
    var title = DulcetStrings.play
    var isEnabled = true
    var isDefaultAction = false
    var fillsWidth = false
    /// Its own music is playing: the button reads Pause, and pressing it pauses.
    var isPlaying = false
    let action: () -> Void

    private var shownTitle: String { isPlaying ? DulcetStrings.pause : title }
    private var shownSymbol: String { isPlaying ? "pause.fill" : "play.fill" }

    var body: some View {
#if os(iOS) || os(macOS)
        Button(action: action) {
            Label(shownTitle, systemImage: shownSymbol)
                .lineLimit(1)
                .frame(maxWidth: fillsWidth ? .infinity : nil)
        }
        .buttonStyle(.bordered)
        .disabled(!isEnabled)
        .dulcetDefaultActionShortcut(isDefaultAction)
#else
        DulcetProminentAction(
            shownTitle,
            systemImage: shownSymbol,
            isEnabled: isEnabled,
            isDefaultAction: isDefaultAction,
            fillsWidth: fillsWidth,
            action: action
        )
#endif
    }
}

private extension View {
    @ViewBuilder
    func dulcetProminentLabelStyle(_ pair: DulcetRegisteredContrastPair?) -> some View {
        if let pair {
            dulcetForeground(pair)
        } else {
            self
        }
    }
}

extension View {
    @ViewBuilder
    func dulcetOptionalAccessibilityIdentifier(_ identifier: String?) -> some View {
        if let identifier {
            accessibilityIdentifier(identifier)
        } else {
            self
        }
    }
}
#endif
