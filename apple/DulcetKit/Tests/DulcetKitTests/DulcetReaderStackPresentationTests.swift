import Testing
@testable import DulcetKit

@Suite struct DulcetReaderStackPresentationTests {
    typealias Presentation = DulcetReaderStackPresentation<String, String>

    @Test func initialEmptyWriteReplaysTheUnpresentedAlbumOnce() {
        var presentation = Presentation()
        let album = Presentation.Request(context: "account-A/library", path: ["album"])
        presentation.request(album)
        let acceptedInitial = presentation.wrote([])
        #expect(!acceptedInitial)
        #expect(presentation.path.isEmpty, "Acknowledge SwiftUI's actual displayed root")
        #expect(presentation.request == album, "Keep the store's intent")
        #expect(presentation.pendingReplay == album, "Schedule a new path transition after the reset")
        presentation.replay(album)
        #expect(presentation.path == ["album"])
        #expect(presentation.pendingReplay == nil)
        let acceptedAgain = presentation.wrote([])
        #expect(!acceptedAgain)
        #expect(presentation.pendingReplay == nil, "A missing destination must not create a replay loop")
    }

    @Test func displayedAlbumBackIsAcceptedWithoutReplay() {
        var presentation = Presentation()
        presentation.request(.init(context: "account-A/library", path: ["wishlist", "album"]))
        presentation.appeared("album")
        let acceptedBack = presentation.wrote(["wishlist"])
        #expect(acceptedBack)
        #expect(presentation.pendingReplay == nil)
        #expect(presentation.path == ["wishlist"])
    }

    @Test func queuedReplayCannotRestoreAnOldAccountOrDestination() {
        for context in ["account-B/library", "account-A/search"] {
            var presentation = Presentation()
            let old = Presentation.Request(context: "account-A/library", path: ["album"])
            presentation.request(old)
            _ = presentation.wrote([])
            presentation.request(.init(context: context, path: []))
            presentation.replay(old)
            #expect(presentation.path.isEmpty)
            #expect(presentation.pendingReplay == nil)
        }
    }
}
