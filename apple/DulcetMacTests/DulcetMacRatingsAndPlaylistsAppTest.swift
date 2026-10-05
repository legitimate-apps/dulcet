import AppKit
import DulcetCore
import SwiftUI
import XCTest
@testable import DulcetKit
@testable import DulcetMac

/// The Mac's rating control and playlist editing, driven through the production root view hosted
/// in the app, over the production controller and library reader, against the disposable server
/// (spec §16.20, §18.6; CONF-84, CONF-88..91). The account is connected through the presentation
/// store, as the other hosted proofs connect it; everything after that goes through the rendered
/// views. A macOS host test does not inherit xcodebuild's environment, so the server arrives
/// through the DulcetMac scheme, from `DULCET_TEST_*` build settings.
@MainActor
final class DulcetMacRatingsAndPlaylistsAppTest: XCTestCase {
    private let username = "dulcet-admin"
    private let password = "dulcet-ci-canary-password"
    /// The track the rating proof rates: the first of "Threshold Boundary".
    private let track = "Twenty Nine Seconds"

    /// The five stars the Mac draws beside the heart in the window's toolbar, clicked where each
    /// star is drawn:
    ///
    /// 1. a click on star N shows N at once, before the server answers, and the server's
    ///    `userRating` reads N back;
    /// 2. another client's rating is written to the server directly, the app is reopened over
    ///    the same reader database and account, and the stars show the server's value -- so what
    ///    they show is read from the server, not remembered from the click;
    /// 3. a click on the star shown removes the rating, and the server reads back 0.
    ///
    /// Each star is clicked at the centre of its own button by a mouse event the window
    /// hit-tests, and what the stars show is read from the group's label, checked against the
    /// star buttons' own labels.
    /// Each value is chosen against what the server holds first, so no earlier run's rating can
    /// make a write unobservable, and the server's rating is put back afterwards.
    func nowPlayingStarsRateTheTrackOnTheServerThroughTheHostedMacApp() async throws {
        let server = try disposableServer()
        let album = "Threshold Boundary"
        let songID = try await server.songID(track, album: album)
        let before = try await server.rating(songID)
        addTeardownBlock { try? await server.setRating(songID, before) }
        let rating = before == 3 ? 2 : 3
        let elsewhere = rating == 4 ? 5 : 4
        let run = UUID().uuidString

        // 1. A click rates the track: shown at once, then saved on the server.
        let app = try await HostedApp(serverURL: server.baseURL, username: username, password: password, run: run, toolbar: true)
        try await app.play(query: "Twenty Nine", track: track)
        _ = try await app.windowElement(identifiedBy: "dulcet.now-playing.rating", timeout: .seconds(10))
        try await waitForShown(before, in: app, timeout: .seconds(15))
        try await clickStar(rating, in: app)
        try await waitForShown(rating, in: app, timeout: .seconds(3), allowingPending: true)
        let saved = try await awaitRating(rating, of: songID, on: server)
        XCTAssertEqual(saved, rating, "The click on star \(rating) must rate \(track) \(rating) on the server (was \(before))")
        try await waitForShown(rating, in: app, timeout: .seconds(15))
        XCTAssertEqual(app.store.snapshot.nowPlaying?.current.title, track, "Rating must not change what is playing")
        app.close()

        // 2. Re-read: another client's rating is what the reopened app shows.
        try await server.setRating(songID, elsewhere)
        let written = try await server.rating(songID)
        XCTAssertEqual(written, elsewhere, "The other client's rating must be on the server before the reopen")
        let reopened = try await HostedApp(serverURL: server.baseURL, username: username, password: password, run: run, toolbar: true)
        defer { reopened.close() }
        try await reopened.play(query: "Twenty Nine", track: track)
        let reread = try await reopened.windowElement(identifiedBy: "dulcet.now-playing.rating", timeout: .seconds(10))
        try await waitForShown(elsewhere, in: reopened, timeout: .seconds(30))

        // 3. A click on the star shown removes the rating.
        try await clickStar(elsewhere, in: reopened)
        try await waitForShown(0, in: reopened, timeout: .seconds(3), allowingPending: true)
        let removed = try await awaitRating(0, of: songID, on: server)
        XCTAssertEqual(removed, 0, "The click on the star shown must remove the rating on the server")
        print("DULCET MAC STARS PROOF PASS track=\(track.debugDescription) server-before=\(before) clicked=\(rating)"
            + " other-client=\(elsewhere) shown-after-reopen=\(elsewhere) removed=0 frame=\(try reopened.frame(reread)) window-width=\(reopened.hostingView.bounds.width)")
    }

    /// The Mac's hearts (spec §16.20, CONF-84), each clicked where it is drawn and read back from
    /// the server:
    ///
    /// 1. the heart beside the stars in the window's toolbar, for the playing track;
    /// 2. the heart beside a track's row on its album page.
    ///
    /// Each fills with the click, before the server answers, and the server's `starred` reads the
    /// track starred; a second click empties it at once and the server reads it unstarred. Each
    /// starts from what the server holds, so an earlier run's star cannot make a write
    /// unobservable, and the server is left unstarred.
    func toolbarAndRowHeartsStarTheTrackOnTheServerThroughTheHostedMacApp() async throws {
        let server = try disposableServer()
        let album = "Threshold Boundary"
        let songID = try await server.songID(track, album: album)
        if try await server.starred(songID) { try await server.setStarred(songID, false) }
        addTeardownBlock { try? await server.setStarred(songID, false) }

        let app = try await HostedApp(serverURL: server.baseURL, username: username, password: password, toolbar: true)
        defer { app.close() }

        // 1. The toolbar's heart, for the playing track.
        try await app.play(query: "Twenty Nine", track: track)
        let toolbarHeart = try await app.windowElement(identifiedBy: "dulcet.now-playing.favorite", timeout: .seconds(10))
        try await toggleAndProve(toolbarHeart, named: "the toolbar's heart", songID: songID, on: server, in: app)
        XCTAssertEqual(app.store.snapshot.nowPlaying?.current.title, track, "Starring must not change what is playing")

        // 2. The heart beside the track's row on its album page.
        try await app.selectSidebarRow(identifiedBy: "dulcet.sidebar.section.albums")
        let tile = try await app.element(timeout: .seconds(30), described: "the \(album) tile") {
            app.identifier($0) == "dulcet.library.album" && (app.label($0) ?? "").hasPrefix(album)
        }
        try app.press(tile, named: "the \(album) tile")
        let row = try await app.element(timeout: .seconds(15), described: "the \(track) row") {
            app.identifier($0) == "dulcet.reader.track" && (app.label($0) ?? "").hasPrefix(track)
        }
        let rowFrame = try app.frame(row)
        let rowHearts = app.windowElements { app.identifier($0) == "dulcet.reader.favorite" }
            .filter { (try? app.frame($0)).map { $0.midY > rowFrame.minY && $0.midY < rowFrame.maxY } ?? false }
        XCTAssertEqual(rowHearts.count, 1, "Exactly one heart must sit beside \(track)'s row; row=\(rowFrame)")
        let rowHeart = try XCTUnwrap(rowHearts.first, "No heart beside \(track)'s row")
        try await toggleAndProve(rowHeart, named: "\(track)'s row heart", songID: songID, on: server, in: app)
        print("DULCET MAC HEARTS PROOF PASS track=\(track.debugDescription) toolbar=true->false row=true->false"
            + " row-heart=\(try app.frame(rowHeart)) window-width=\(app.hostingView.bounds.width)")
    }

    /// Every playlist edit the Mac offers, made through the rendered views and read back from the
    /// disposable server after each step:
    ///
    /// 1. New Playlist… in the Playlists list and its name alert create an empty playlist;
    /// 2. an album tile's context menu, Add to Playlist… and the chooser append the album's tracks
    ///    in album order;
    /// 3. a track row's context menu adds one track, though the playlist already holds it;
    /// 4. the edit list's remove button on the fourth entry removes that entry alone;
    /// 5. the third entry's Move Up accessibility action, twice, brings it to the top (the Mac's
    ///    list reorders by drag or by these actions; the actions are what VoiceOver and a test can
    ///    reach without a synthesized drag);
    /// 6. the page's menu renames it, and 7. deletes it after the confirmation alert;
    /// 8. a rename of a playlist another client deleted is said on its page as a problem, and no
    ///    playlist of the new name reaches the server.
    ///
    /// The window's width is whatever the host's screen allows, so nothing here depends on it.
    func everyPlaylistEditReachesTheServerThroughTheHostedMacApp() async throws {
        let server = try disposableServer()
        let album = "Threshold Boundary"
        let albumOrder = Self.albumOrder
        let run = String(UUID().uuidString.prefix(8))
        let name = "Dulcet Mac Edit Proof " + run
        let renamed = name + " Renamed"
        let doomed = "Dulcet Mac Failed Edit Proof " + run
        let doomedRenamed = doomed + " Renamed"
        addTeardownBlock {
            for playlist in (try? await server.playlists()) ?? [] where playlist.name.contains(run) {
                try? await server.deletePlaylist(playlist.id)
            }
        }
        let canaryID = try await server.songID(albumOrder[2], album: album)
        let doomedID = try await server.createPlaylist(doomed, songID: canaryID)

        let app = try await HostedApp(serverURL: server.baseURL, username: username, password: password, toolbar: true)
        defer { app.close() }

        // 1. Create.
        try await app.selectSidebarRow(identifiedBy: "dulcet.sidebar.section.playlists")
        let new = try await app.element(identifiedBy: "dulcet.playlists.new", timeout: .seconds(15))
        try app.press(new, named: "dulcet.playlists.new")
        try await app.answerAlert(typing: name, pressing: "Create", described: "New Playlist")
        let playlistID = try await awaitPlaylist(named: name, on: server)
        let created = try await server.playlistEntries(playlistID)
        XCTAssertEqual(created, [], "A new playlist starts empty")

        // 2. Add an album from its tile's context menu.
        try await app.selectSidebarRow(identifiedBy: "dulcet.sidebar.section.albums")
        let tile = try await app.element(timeout: .seconds(30), described: "the \(album) tile") {
            app.identifier($0) == "dulcet.library.album" && (app.label($0) ?? "").hasPrefix(album)
        }
        try await addToPlaylist(name, fromContextMenuOf: tile, in: app)
        let withAlbum = try await awaitEntries(albumOrder, of: playlistID, on: server)
        XCTAssertEqual(withAlbum, albumOrder, "Adding the album must append its tracks in album order")

        // 3. Add a track from its row's context menu.
        try app.press(tile, named: "the \(album) tile")
        let row = try await app.element(timeout: .seconds(15), described: "the \(albumOrder[0]) row") {
            app.identifier($0) == "dulcet.reader.track" && (app.label($0) ?? "").hasPrefix(albumOrder[0])
        }
        try await addToPlaylist(name, fromContextMenuOf: row, in: app)
        let withTrack = albumOrder + [albumOrder[0]]
        let added = try await awaitEntries(withTrack, of: playlistID, on: server)
        XCTAssertEqual(added, withTrack, "Adding a track must append it, even when the playlist already holds it")

        // 4-5. Remove by position, then reorder, in the edit list.
        try await openPlaylist(name, in: app)
        let edit = try await app.element(identifiedBy: "dulcet.playlist.edit", timeout: .seconds(10))
        try await waitUntil(timeout: .seconds(15), "Edit must be enabled once the entries are read") {
            (edit as? any NSAccessibilityProtocol)?.isAccessibilityEnabled() ?? true
        }
        try app.press(edit, named: "dulcet.playlist.edit")
        let removes = try await removeButtons(count: 4, in: app)
        try app.press(removes[3], named: "the fourth entry's remove button")
        let afterRemove = try await awaitEntries(albumOrder, of: playlistID, on: server)
        XCTAssertEqual(afterRemove, albumOrder, "Removing the fourth entry must remove that entry alone, not the first of the same track")
        _ = try await removeButtons(count: 3, in: app)
        let offered = try app.performCustomAction("Move Up", on: try await entry(albumOrder[2], in: app), described: "the third entry")
        let once = [albumOrder[0], albumOrder[2], albumOrder[1]]
        let movedOnce = try await awaitEntries(once, of: playlistID, on: server)
        XCTAssertEqual(movedOnce, once, "Move Up on the third entry must swap it with the second on the server")
        try await waitUntil(timeout: .seconds(10), "the edit list must redraw the canary second") {
            self.entryTitles(in: app).firstIndex(of: albumOrder[2]) == 1
        }
        try app.performCustomAction("Move Up", on: try await entry(albumOrder[2], in: app), described: "the second entry")
        let reordered = [albumOrder[2], albumOrder[0], albumOrder[1]]
        let movedTwice = try await awaitEntries(reordered, of: playlistID, on: server)
        XCTAssertEqual(movedTwice, reordered, "Move Up again must bring the canary to the top on the server")
        let done = try await app.windowElement(identifiedBy: "dulcet.playlist.done", timeout: .seconds(5))
        try app.press(done, named: "dulcet.playlist.done")

        // 6. Rename.
        try await renamePlaylist(to: renamed, in: app)
        try await waitUntil(timeout: .seconds(10), "the page must show the new name") {
            app.firstElement(where: { app.identifier($0) == "dulcet.playlist.title" }).flatMap(app.label) == renamed
        }
        let serverName = try await awaitName(renamed, of: playlistID, on: server)
        XCTAssertEqual(serverName, renamed, "The rename must reach the server")

        // 7. Delete.
        try await choosePlaylistMenuItem("Delete Playlist", in: app)
        let confirmation = try await app.answerAlert(pressing: "Delete Playlist", described: "the delete confirmation")
        let absent = try await awaitAbsent(playlistID, on: server)
        XCTAssertTrue(absent, "The deleted playlist must be gone from the server")
        try await app.selectSidebarRow(identifiedBy: "dulcet.sidebar.section.playlists")
        try await waitUntil(timeout: .seconds(10), "the list must no longer show the deleted playlist") {
            app.firstElement(where: { app.identifier($0) == "dulcet.library.playlist" && (app.label($0) ?? "").hasPrefix(renamed) }) == nil
        }

        // 8. A failed edit: the playlist is deleted elsewhere while its page is open, after the
        // page's own read (Edit is enabled once it has the entries), so the rename is sent and the
        // server's answer is what the page reports. Deleting before that read lands left the order
        // to chance: a page that learns first refuses the rename without sending it.
        try await openPlaylist(doomed, in: app)
        let doomedEdit = try await app.element(identifiedBy: "dulcet.playlist.edit", timeout: .seconds(10))
        try await waitUntil(timeout: .seconds(30), "the page must read the playlist before the other client deletes it") {
            (doomedEdit as? any NSAccessibilityProtocol)?.isAccessibilityEnabled() ?? true
        }
        try await server.deletePlaylist(doomedID)
        let doomedAbsent = try await awaitAbsent(doomedID, on: server)
        XCTAssertTrue(doomedAbsent, "The other client's delete must reach the server first")
        XCTAssertNil(app.firstElement(where: { app.identifier($0) == "dulcet.playlist.problem" }),
            "No problem may be said before the rename")
        try await renamePlaylist(to: doomedRenamed, in: app)
        let problem = try await app.element(identifiedBy: "dulcet.playlist.problem", timeout: .seconds(30))
        let problemText = ((app.value("accessibilityChildren", of: problem) as? [Any]) ?? [])
            .compactMap(app.label).filter { !$0.isEmpty }.joined(separator: " | ")
        XCTAssertTrue(problemText.contains("no longer has this"),
            "The problem must say the server no longer has the playlist; says \(problemText.debugDescription)")
        let leaked = try await server.playlists().contains { $0.name == doomedRenamed }
        XCTAssertFalse(leaked, "The failed rename must not reach the server under any id")
        print("DULCET MAC PLAYLIST EDITS PROOF PASS window-width=\(app.hostingView.bounds.width) created=\(name.debugDescription)"
            + " album-added=\(albumOrder.count) track-added=1 removed-index=3 move-up-offered=\(offered) reordered=\(reordered)"
            + " renamed=true deleted=true confirmation=\(confirmation) failed-rename-problem=\(problemText.debugDescription)")
    }

    // MARK: - Helpers

    private func disposableServer() throws -> LiveServer {
        let environment = ProcessInfo.processInfo.environment
        let baseURL = try XCTUnwrap(environment["DULCET_CONFORMANCE_BASE_URL"], "The disposable server URL must be supplied")
        guard let components = URLComponents(string: baseURL), components.scheme == "http",
              components.host == "127.0.0.1" || components.host == "localhost",
              environment["DULCET_CONFORMANCE_DISPOSABLE"] == "true" else {
            XCTFail("Refused: \(baseURL.debugDescription) is not a disposable loopback server")
            throw HostedAppError.refused
        }
        return LiveServer(baseURL: baseURL, username: username, password: password)
    }

    /// What the stars show, as the group's label says it after the track's name -- "3 stars",
    /// "Not rated", "Rating unknown", with ", Waiting to send" while pending -- checked against
    /// the five star buttons, of which only the N-th offers to remove the rating. "unreadable"
    /// when the two disagree.
    private func shownRating(in app: HostedApp) -> String {
        let prefix = "Rating for \(track), "
        guard let group = app.windowFirstElement(where: { app.identifier($0) == "dulcet.now-playing.rating" }),
              let label = app.label(group), label.hasPrefix(prefix) else { return "absent" }
        let said = String(label.dropFirst(prefix.count))
        let labels = (1...5).map { star in
            app.windowFirstElement(where: { app.identifier($0) == "dulcet.now-playing.rating.star.\(star)" }).flatMap(app.label) ?? "absent"
        }
        let removable = labels.indices.filter { labels[$0] == "Remove Rating" }.map { $0 + 1 }
        let rated = Int(said.split(separator: " ").first ?? "") ?? 0
        let consistent = removable == (rated == 0 ? [] : [rated])
            && labels.enumerated().allSatisfy { index, label in
                label == "Remove Rating" || label == (index == 0 ? "Rate 1 star" : "Rate \(index + 1) stars")
            }
        return consistent ? said : "unreadable said=\(said.debugDescription) stars=\(labels)"
    }

    /// Waits until the stars show `rating`, settled -- or, with `allowingPending`, with a pending
    /// or held change after it.
    private func waitForShown(_ rating: Int, in app: HostedApp, timeout: Duration, allowingPending: Bool = false) async throws {
        let expected = Self.ratingValue(rating)
        try await waitUntil(timeout: timeout, "the stars must show \(expected.debugDescription); show \(shownRating(in: app))") {
            let shown = self.shownRating(in: app)
            return shown == expected || (allowingPending && shown.hasPrefix(expected + ", "))
        }
    }

    /// What the stars say their value is, settled: "Not rated", "1 star", "3 stars".
    private static func ratingValue(_ rating: Int) -> String {
        switch rating {
        case 0: "Not rated"
        case 1: "1 star"
        default: "\(rating) stars"
        }
    }

    /// Clicks star `star` where it is drawn: a mouse click at the centre of that star's own
    /// button, which the window hit-tests.
    private func clickStar(_ star: Int, in app: HostedApp) async throws {
        let button = try await app.windowElement(identifiedBy: "dulcet.now-playing.rating.star.\(star)", timeout: .seconds(5))
        let frame = try app.frame(button)
        // The window's width is whatever the host's screen allowed (a CI Mac fits the window to a
        // narrower screen), so it is reported, never assumed; the stars live in the toolbar in
        // either Now Playing layout.
        XCTAssertGreaterThan(frame.width, 0, "Star \(star) must be drawn in the toolbar, not overflowed;"
            + " frame=\(frame) window-width=\(app.hostingView.bounds.width)")
        try app.click(atScreenPoint: NSPoint(x: frame.midX, y: frame.midY))
    }

    /// Clicks `heart` at its centre and proves the star reaches the server, then clicks it again
    /// and proves it is taken off. The heart's own label says what it shows.
    private func toggleAndProve(_ heart: Any, named name: String, songID: String, on server: LiveServer, in app: HostedApp) async throws {
        try await waitUntil(timeout: .seconds(10), "\(name) must show the track unstarred; label=\(app.label(heart) ?? "nil")") {
            app.label(heart) == "Favorite"
        }
        let starredBefore = try await server.starred(songID)
        XCTAssertFalse(starredBefore, "The control: the server must not already hold the star \(name) makes")
        for expected in [true, false] {
            let frame = try app.frame(heart)
            XCTAssertGreaterThan(frame.width, 0, "\(name) must be drawn; frame=\(frame)")
            try app.click(atScreenPoint: NSPoint(x: frame.midX, y: frame.midY))
            let label = expected ? "Remove Favorite" : "Favorite"
            try await waitUntil(timeout: .seconds(3), "\(name) must show \(label) at once; label=\(app.label(heart) ?? "nil")") {
                app.label(heart) == label
            }
            let deadline = ContinuousClock.now.advanced(by: .seconds(30))
            var observed = try await server.starred(songID)
            while observed != expected, ContinuousClock.now < deadline {
                try await Task.sleep(for: .seconds(1))
                observed = try await server.starred(songID)
            }
            XCTAssertEqual(observed, expected, "\(name): the server must read the track \(expected ? "starred" : "unstarred")")
        }
    }

    private func awaitRating(_ expected: Int, of songID: String, on server: LiveServer) async throws -> Int {
        let deadline = ContinuousClock.now.advanced(by: .seconds(30))
        var observed = try await server.rating(songID)
        while observed != expected, ContinuousClock.now < deadline {
            try await Task.sleep(for: .seconds(1))
            observed = try await server.rating(songID)
        }
        return observed
    }

    // MARK: Playlist helpers

    private static let albumOrder = ["Twenty Nine Seconds", "Thirty One Seconds", "UI Playback Canary"]

    /// A context menu's Add to Playlist…, then the named playlist in the chooser sheet.
    private func addToPlaylist(_ name: String, fromContextMenuOf element: Any, in app: HostedApp) async throws {
        try await app.chooseMenuItem("Add to Playlist\u{2026}", described: "the context menu") {
            try app.rightClick(element)
        }
        let choice = try await app.sheetElement(timeout: .seconds(15), described: "\(name) in the chooser") {
            app.identifier($0) == "dulcet.addToPlaylist.playlist" && (app.label($0) ?? "").hasPrefix(name)
        }
        try app.press(choice, named: "\(name) in the chooser")
        try await waitUntil(timeout: .seconds(10), "the chooser must close after a choice") { !app.showsSheet }
    }

    private func openPlaylist(_ name: String, in app: HostedApp) async throws {
        try await app.selectSidebarRow(identifiedBy: "dulcet.sidebar.section.playlists")
        let row = try await app.element(timeout: .seconds(30), described: "the \(name) row") {
            app.identifier($0) == "dulcet.library.playlist" && (app.label($0) ?? "").hasPrefix(name)
        }
        try app.press(row, named: "the \(name) row")
        try await waitUntil(timeout: .seconds(15), "the page must open on \(name)") {
            app.firstElement(where: { app.identifier($0) == "dulcet.playlist.title" }).flatMap(app.label) == name
        }
    }

    private func renamePlaylist(to name: String, in app: HostedApp) async throws {
        try await choosePlaylistMenuItem("Rename\u{2026}", in: app)
        try await app.answerAlert(typing: name, pressing: "Rename", described: "Rename")
    }

    /// The page's More menu of playlist edits, opened by AXPress as VoiceOver opens it, and the
    /// item `title` performed from it.
    private func choosePlaylistMenuItem(_ title: String, in app: HostedApp) async throws {
        // Found afresh for each press: after a rename the page redraws, and a press on the element
        // found before the redraw opened nothing (CI run 37117426402, after Rename… had worked).
        try await app.chooseMenuItem(title, described: "the playlist's More menu", attempts: 3) {
            let more = try await app.element(identifiedBy: "dulcet.playlist.more", timeout: .seconds(10))
            XCTAssertEqual(app.label(more), "More", "The playlist's menu must say what it is to accessibility")
            try app.press(more, named: "dulcet.playlist.more")
        }
    }

    /// The edit list's remove buttons, top first, once exactly `count` are drawn. Each is found
    /// by its label inside an entry: the entry's identifier reaches the button too, over the
    /// button's own `dulcet.playlist.entry.remove` (OBSERVED, macOS 26).
    private func removeButtons(count: Int, in app: HostedApp) async throws -> [Any] {
        var found: [Any] = []
        try await waitUntil(timeout: .seconds(15), "the edit list must draw \(count) remove buttons; draws \(found.count);"
            + " window holds \(app.windowElements(where: { (app.identifier($0) ?? "").hasPrefix("dulcet.") }).map { "\(app.identifier($0)!)=\(app.label($0) ?? "nil")" })") {
            found = app.windowElements(where: {
                (app.identifier($0) ?? "").hasPrefix("dulcet.playlist.entry") && app.label($0) == "Remove from Playlist"
                    && (app.value("accessibilityRole", of: $0) as? String) == NSAccessibility.Role.button.rawValue
            })
                .sorted { ((try? app.frame($0).maxY) ?? 0) > ((try? app.frame($1).maxY) ?? 0) }
            return found.count == count
        }
        return found
    }

    private func hasCustomActions(_ element: Any, in app: HostedApp) -> Bool {
        !((app.value("accessibilityCustomActions", of: element) as? [NSAccessibilityCustomAction]) ?? []).isEmpty
    }

    /// The edit list's entry titled `title`: the element that carries the entry's actions.
    private func entry(_ title: String, in app: HostedApp) async throws -> Any {
        try await app.element(timeout: .seconds(10), described: "the \(title) entry with actions") {
            app.identifier($0) == "dulcet.playlist.entry" && (app.label($0) ?? "").hasPrefix(title)
                && self.hasCustomActions($0, in: app)
        }
    }

    /// The edit list's entries' titles, top first.
    private func entryTitles(in app: HostedApp) -> [String] {
        app.windowElements(where: { app.identifier($0) == "dulcet.playlist.entry" && self.hasCustomActions($0, in: app) })
            .sorted { ((try? app.frame($0).maxY) ?? 0) > ((try? app.frame($1).maxY) ?? 0) }
            .compactMap { element in
                let label = app.label(element) ?? ""
                return Self.albumOrder.first { label.hasPrefix($0) }
            }
    }

    /// Polls the server once a second for up to 30 s until `read` returns `done`; the last read.
    private func poll<T>(_ read: () async throws -> T, until done: (T) -> Bool) async throws -> T {
        let deadline = ContinuousClock.now.advanced(by: .seconds(30))
        var observed = try await read()
        while !done(observed), ContinuousClock.now < deadline {
            try await Task.sleep(for: .seconds(1))
            observed = try await read()
        }
        return observed
    }

    private func awaitPlaylist(named name: String, on server: LiveServer) async throws -> String {
        let matches = try await poll({ try await server.playlists().filter { $0.name == name } }, until: { !$0.isEmpty })
        XCTAssertEqual(matches.count, 1, "The server must hold exactly one playlist named \(name.debugDescription)")
        return try XCTUnwrap(matches.first?.id, "The new playlist must reach the server")
    }

    private func awaitEntries(_ expected: [String], of id: String, on server: LiveServer) async throws -> [String]? {
        try await poll({ try await server.playlistEntries(id) }, until: { $0 == expected })
    }

    private func awaitName(_ expected: String, of id: String, on server: LiveServer) async throws -> String? {
        try await poll({ try await server.playlists().first { $0.id == id }?.name }, until: { $0 == expected })
    }

    private func awaitAbsent(_ id: String, on server: LiveServer) async throws -> Bool {
        try await poll({ try await server.playlists().allSatisfy { $0.id != id } }, until: { $0 })
    }
}
