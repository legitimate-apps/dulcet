import SwiftUI

// Ratings (spec §16.20, §18.3): a track's 0-5 stars, set through the same session and the same
// outbox as its heart, so a pending, held or saved rating reads the way a heart does. 0 removes the
// rating, as `setRating` defines it.

/// The rules every star control follows, kept apart from the views so they can be tested.
public enum DulcetRating {
    /// Every value a rating can take; 0 is no rating.
    public static let range = 0 ... 5
    /// The stars drawn.
    public static let stars = 1 ... 5

    /// Pressing a star rates the track that many stars; pressing the star that is already the
    /// rating removes it, so a rating can be taken off without a separate control. `current` is nil
    /// when the rating is unknown: a press is absolute, so it still sets that many stars.
    public static func value(pressing star: Int, current: Int?) -> Int {
        star == current ? 0 : min(max(star, range.lowerBound), range.upperBound)
    }

    /// VoiceOver's adjustable action: one star up or down, never past 0 or 5. Nil -- nothing is
    /// sent -- from an unknown rating: a step from a value nobody knows would overwrite the
    /// server's with a guess.
    public static func adjusted(_ current: Int?, increment: Bool) -> Int? {
        guard let current else { return nil }
        return min(max(current + (increment ? 1 : -1), range.lowerBound), range.upperBound)
    }

    /// Whether star `star` is drawn filled for `rating`; none is filled while it is unknown.
    public static func isFilled(star: Int, rating: Int?) -> Bool {
        guard let rating else { return false }
        return star <= rating
    }

    /// What VoiceOver says the value is, with any pending or held change after it.
    public static func accessibilityValue(rating: Int?, state: DulcetFavouriteChangeState) -> String {
        let value = switch rating {
        case nil: DulcetStrings.readerRatingUnknown
        case 0: DulcetStrings.readerRatingNone
        case let stars?: DulcetStrings.readerRatingValue(stars)
        }
        switch state {
        case .settled: return value
        case .pending: return "\(value), \(DulcetStrings.readerFavoritePending)"
        case .held: return "\(value), \(DulcetStrings.readerFavoriteHeld)"
        }
    }

    /// What pressing star `star` would do, as its label: rate that many stars, or remove it.
    public static func starLabel(star: Int, current: Int?) -> String {
        value(pressing: star, current: current) == 0 ? DulcetStrings.readerRatingRemove : DulcetStrings.readerRatingRate(star)
    }
}

extension DulcetPresentationStore {
    /// The stars Now Playing draws for `track`: offered only while the reader holds the account the
    /// track came from, as the heart is. `published` is what the screens last published for it --
    /// a queued track carries no rating of its own.
    public func nowPlayingRating(for track: DulcetTrack) -> (target: DulcetFavouriteTarget, published: Int?)? {
        guard let session = librarySession, session.reader != nil,
              track.id.providerInstanceID == session.account?.providerInstanceID else { return nil }
        return (DulcetFavouriteTarget(kind: .track, id: track.id), session.knownRatings[track.id])
    }
}

/// Five stars: the rating as the person set it while it is being sent, and the heart's mark while a
/// change is pending or kept unsent. For VoiceOver on iOS it is one adjustable element -- swipe up
/// or down to change it; on macOS a group whose label says the rating, holding a button per star; on
/// Apple TV each star takes focus, so the remote moves across them and Select sets that many.
struct DulcetRatingControl: View {
    @Environment(DulcetPresentationStore.self) private var store
    let target: DulcetFavouriteTarget
    let published: Int?
    var title: String = ""
    var size: Font = .body
    var identifier = "dulcet.reader.rating"
    /// The smallest side of each star's hit area.
    var minimumSide: CGFloat = 28

    var body: some View {
        if let session = store.librarySession {
            let rating = session.rating(target.id, published: published)
            let state = session.ratingState(target)
            stars(rating: rating, state: state, session: session)
        }
    }

    @ViewBuilder
    private func stars(rating: Int?, state: DulcetFavouriteChangeState, session: DulcetLibrarySession) -> some View {
        let row = HStack(spacing: Self.starSpacing) {
            ForEach(Array(DulcetRating.stars), id: \.self) { star in
                starButton(star, rating: rating, session: session)
            }
            if state != .settled {
                Image(systemName: state == .pending ? "clock" : "exclamationmark.circle.fill")
                    .font(.caption2.weight(.bold))
                    .dulcetForeground(.secondaryTextOnWindow)
                    .padding(.leading, 2)
                    .accessibilityHidden(true)
            }
        }
#if os(tvOS)
        // No identifier on the row here: on tvOS a container's identifier replaces each star's
        // own, so all five read as one id and a remote proof cannot tell them apart (observed).
        row
            .focusSection()
#elseif os(macOS)
        // On macOS each star is its own button, labelled with what pressing it does, in a group
        // whose label says the rating. The single adjustable element iOS uses reached the Mac's
        // accessibility as a node with no role, no value and no actions (observed in the window's
        // toolbar, macOS 26), so VoiceOver could neither read nor change the rating there. An
        // AXGroup carries no value and an AXButton no selected state (both observed), so the
        // group's label carries the value, unknown and pending included.
        row
            .accessibilityElement(children: .contain)
            .accessibilityLabel(DulcetStrings.readerRatingGroupAccessibility(
                title.isEmpty ? DulcetStrings.readerRating : DulcetStrings.readerRatingAccessibility(title),
                value: DulcetRating.accessibilityValue(rating: rating, state: state)))
            .accessibilityIdentifier(identifier)
#else
        row
            .accessibilityElement(children: .ignore)
            .accessibilityLabel(title.isEmpty ? DulcetStrings.readerRating : DulcetStrings.readerRatingAccessibility(title))
            .accessibilityValue(DulcetRating.accessibilityValue(rating: rating, state: state))
            .accessibilityAdjustableAction { direction in
                switch direction {
                case .increment, .decrement:
                    if let value = DulcetRating.adjusted(rating, increment: direction == .increment) {
                        session.setRating(target, rating: value)
                    }
                @unknown default: break
                }
            }
            .accessibilityIdentifier(identifier)
#endif
    }

    /// Between stars: touching where a pointer or a finger aims. On a television a focused
    /// star's platter is drawn at several times the glyph (measured ≈3x on tvOS 26), so the gap
    /// keeps adjacent platters clear of each other; at a small gap the focused star covered its
    /// neighbours (observed on Now Playing).
    static var starSpacing: CGFloat {
#if os(tvOS)
        DulcetSpacing.lg
#else
        0
#endif
    }

    private func starButton(_ star: Int, rating: Int?, session: DulcetLibrarySession) -> some View {
        Button {
            session.setRating(target, rating: DulcetRating.value(pressing: star, current: rating))
        } label: {
            Image(systemName: DulcetRating.isFilled(star: star, rating: rating) ? "star.fill" : "star")
                .font(size)
                .dulcetForeground(.accentIconOnWindow)
                // Unknown is not unrated: dimmed, so it never reads as a server's "no rating".
                .opacity(rating == nil ? 0.45 : 1)
                .frame(minWidth: minimumSide, minHeight: minimumSide)
                .contentShape(Rectangle())
        }
#if os(tvOS)
        // The heart's style: a plain button's focus platter is wider than the star and covered
        // both neighbours (observed on Now Playing); this one hugs the focused star, as the
        // heart's and Lyrics' do beside it.
        .buttonStyle(.borderless)
        .accessibilityLabel(DulcetRating.starLabel(star: star, current: rating))
        .accessibilityAddTraits(DulcetRating.isFilled(star: star, rating: rating) ? .isSelected : [])
        .accessibilityIdentifier("\(identifier).star.\(star)")
#elseif os(macOS)
        .buttonStyle(.borderless)
        .help(DulcetRating.starLabel(star: star, current: rating))
        .accessibilityLabel(DulcetRating.starLabel(star: star, current: rating))
        .accessibilityIdentifier("\(identifier).star.\(star)")
#else
        .buttonStyle(.borderless)
#endif
    }
}

/// The playing track's stars, beside its heart on every Apple platform: the rows' stars through the
/// same session. Nothing is drawn while the reader does not hold the track's account.
struct DulcetNowPlayingRatingControl: View {
    @Environment(DulcetPresentationStore.self) private var store
    let track: DulcetTrack
    var size: Font = .body
    var minimumSide: CGFloat = 28

    static let identifier = "dulcet.now-playing.rating"

    var body: some View {
        if let rating = store.nowPlayingRating(for: track) {
            DulcetRatingControl(
                target: rating.target,
                published: rating.published,
                title: track.title,
                size: size,
                identifier: Self.identifier,
                minimumSide: minimumSide
            )
        }
    }
}

#if !os(tvOS)
/// Rating in a track's context menu: No Rating and 1 to 5 stars, the current one checked. Offered only
/// while the reader holds the track's account.
struct DulcetRatingMenu: View {
    @Environment(DulcetPresentationStore.self) private var store
    let target: DulcetFavouriteTarget
    let published: Int?

    var body: some View {
        if let session = store.librarySession, session.reader != nil,
           session.account?.providerInstanceID == target.id.providerInstanceID {
            let rating = session.rating(target.id, published: published)
            Menu {
                // An unknown rating checks nothing; choosing is absolute, so it is always safe.
                Picker(DulcetStrings.readerRating, selection: Binding<Int?>(
                    get: { rating },
                    set: { if let value = $0 { session.setRating(target, rating: value) } }
                )) {
                    ForEach(Array(DulcetRating.range), id: \.self) { value in
                        Text(value == 0 ? DulcetStrings.readerRatingMenuNone : DulcetStrings.readerRatingMenuStars(value))
                            .tag(Optional(value))
                    }
                }
                .pickerStyle(.inline)
            } label: {
                Label(DulcetStrings.readerRating, systemImage: (rating ?? 0) == 0 ? "star" : "star.fill")
            }
        }
    }
}
#endif
