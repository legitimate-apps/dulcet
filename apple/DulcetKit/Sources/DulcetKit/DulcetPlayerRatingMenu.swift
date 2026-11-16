import SwiftUI

/// The same optimistic, account-scoped rating writes as the row control, offered in More.
/// The existing identifiers name the submenu and its individual values for assistive tools.
struct DulcetPlayerRatingMenu: View {
    @Environment(DulcetPresentationStore.self) private var store
    let track: DulcetTrack

    var body: some View {
        if let session = store.librarySession, let offered = store.nowPlayingRating(for: track) {
            let rating = session.rating(track.id, published: offered.published)
            let state = session.ratingState(offered.target)
            Menu {
                Button(DulcetStrings.readerRatingMenuNone) { session.setRating(offered.target, rating: 0) }
                    .accessibilityIdentifier("dulcet.now-playing.rating.star.0")
                ForEach(Array(DulcetRating.stars), id: \.self) { star in
                    Button {
                        session.setRating(offered.target, rating: DulcetRating.value(pressing: star, current: rating))
                    } label: {
                        Label(DulcetRating.starLabel(star: star, current: rating),
                              systemImage: rating == star ? "checkmark" : "star")
                    }
                    .accessibilityIdentifier("dulcet.now-playing.rating.star.\(star)")
                }
            } label: {
                Label(DulcetStrings.readerRating, systemImage: "star")
            }
            .accessibilityValue(DulcetRating.accessibilityValue(rating: rating, state: state))
            .accessibilityAdjustableAction { direction in
                if let next = DulcetRating.adjusted(rating, increment: direction == .increment) {
                    session.setRating(offered.target, rating: next)
                }
            }
            .accessibilityIdentifier("dulcet.now-playing.rating")
        }
    }
}
