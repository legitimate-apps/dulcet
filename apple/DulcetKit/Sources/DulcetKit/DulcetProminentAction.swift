#if os(macOS) || os(iOS) || os(tvOS)
import SwiftUI

/// A prominent (accent-filled) action whose label follows the fill under it. While the window
/// is key the fill is the accent and the label takes the registered label/accent-fill pair: the
/// window colour, light on the dark accent of light mode and dark on the light accent of dark
/// mode. While the window is inactive -- or the action is disabled -- the fill is grey and the
/// label takes the control's plain text colour. An ancestor's primary-text style would
/// otherwise paint the label dark on the dark accent of a key window, and the platform's own
/// white label is lost on the light accent of dark mode; colouring the label inside the button
/// reaches where a foreground on the button cannot (there it repaints the fill too). tvOS keeps
/// the platform label, whose focus engine owns the style.
struct DulcetProminentAction: View {
    let title: String
    var systemImage: String? = nil
    var isEnabled = true
    var isDefaultAction = false
    /// The label fills the width it is offered, so the bezel fills an equal-width action row's
    /// share; a frame outside the button does not stretch it.
    var fillsWidth = false
    let action: () -> Void

#if os(macOS)
    @Environment(\.controlActiveState) private var controlActiveState
#endif

    init(
        _ title: String,
        systemImage: String? = nil,
        isEnabled: Bool = true,
        isDefaultAction: Bool = false,
        fillsWidth: Bool = false,
        action: @escaping () -> Void
    ) {
        self.title = title
        self.systemImage = systemImage
        self.isEnabled = isEnabled
        self.isDefaultAction = isDefaultAction
        self.fillsWidth = fillsWidth
        self.action = action
    }

    var body: some View {
        Group {
            if let systemImage {
                Button(action: action) {
                    Label(title, systemImage: systemImage)
                        .dulcetProminentLabelStyle(labelPair)
                        .frame(maxWidth: fillsWidth ? .infinity : nil)
                }
            } else {
                Button(action: action) {
                    Text(title)
                        .dulcetProminentLabelStyle(labelPair)
                        .frame(maxWidth: fillsWidth ? .infinity : nil)
                }
            }
        }
        .buttonStyle(.borderedProminent)
        .lineLimit(1)
        .disabled(!isEnabled)
        .dulcetDefaultActionShortcut(isDefaultAction)
    }

    private var labelPair: DulcetRegisteredContrastPair {
#if os(macOS)
        guard isEnabled, controlActiveState == .key else { return .primaryTextOnControl }
        return .labelOnAccentFill
#else
        return .labelOnAccentFill
#endif
    }
}

private extension View {
    @ViewBuilder
    func dulcetProminentLabelStyle(_ pair: DulcetRegisteredContrastPair) -> some View {
#if os(tvOS)
        self
#else
        dulcetForeground(pair)
#endif
    }
}
#endif
