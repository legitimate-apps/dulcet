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

    @Test func retainedPagesKeepTheirPresentationAcrossPushesAndSuccessiveBacks() {
        var presentation = Presentation()
        let context = "account-A/browse"
        presentation.request(.init(context: context, path: ["albums"]))
        presentation.appeared("albums")
        // A native link can appear before onChange acknowledges its appended path.
        _ = presentation.wrote(["albums", "album"])
        presentation.appeared("album")
        presentation.request(.init(context: context, path: ["albums", "album"]))
        #expect(presentation.presentedRoutes == ["albums", "album"])
        let albumBack = presentation.wrote(["albums"])
        #expect(albumBack, "Back from an actual native push is accepted")
        presentation.request(.init(context: context, path: ["albums"]))
        let rootBack = presentation.wrote([])
        #expect(rootBack, "The retained parent still accepts Back to Browse")
        #expect(presentation.pendingReplay == nil, "Real Back must never replay a departed page")
        presentation.request(.init(context: context, path: []))
        presentation.request(.init(context: context, path: ["album"]))
        let removedReset = presentation.wrote([])
        #expect(!removedReset, "A removed route is not presented by a later push")
        presentation.appeared("album")
        presentation.request(.init(context: "account-B/browse", path: ["album"]))
        let accountReset = presentation.wrote([])
        #expect(!accountReset, "A different account cannot inherit appearance")
    }
}
