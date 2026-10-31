import Foundation
import XCTest
#if os(macOS)
import AppKit
#else
import UIKit
#endif

/// Production UI and real loopback /rest calls. Each run requires disposable fixture accounts.
@MainActor
final class DulcetMyLibraryUITests: XCTestCase {
    private var app = XCUIApplication()
    // Keep each disposable proof's account separate on every shell. Relaunches inside
    // one proof retain this namespace; a later server must not inherit a prior account.
    private let accountFixtureNamespace = UUID().uuidString
#if os(macOS)
    private var installedFixtureNetworkMonitor = false
#endif
    private var user: String {
#if os(macOS)
        "apple-mac"
#elseif os(tvOS)
        "apple-tv"
#else
        UIDevice.current.userInterfaceIdiom == .pad ? "apple-ipad" : "apple-iphone"
#endif
    }
    private let password = "fixture-password"
    private var base: String {
#if os(macOS)
        "http://127.0.0.1:4784"
#elseif os(tvOS)
        "http://127.0.0.1:4788"
#else
        UIDevice.current.userInterfaceIdiom == .pad ? "http://127.0.0.1:4787" : "http://127.0.0.1:4786"
#endif
    }
    private let navidrome = "http://127.0.0.1:4785"

    @MainActor
    func testNativeBaselineTour() throws {
        guard ProcessInfo.processInfo.environment["DULCET_NATIVE_BASELINE_OUT"] != nil else {
            throw XCTSkip("Opt-in disposable visual census")
        }
        continueAfterFailure = false
        try setOffline(false)
        let album = try fixtureAlbum()
        try removeAlbum(album.id)
        try launch()
        try select("library")
        XCTAssertTrue(app.buttons["dulcet.myLibrary.section.albums"].waitForExistence(timeout: 30))
        try capture("tvos-81-library-empty")
        try select("browse")
        XCTAssertTrue(app.buttons["dulcet.library.album"].firstMatch.waitForExistence(timeout: 30))
        try capture("tvos-82-browse")
        try openAlbum(album.title)
        revealAlbumActions()
        let add = app.descendants(matching: .any)["dulcet.membership.album.\(album.id)"].firstMatch
        XCTAssertTrue(add.waitForExistence(timeout: 20)); activate(add)
        let added = XCTNSPredicateExpectation(predicate: NSPredicate(format: "label == %@", "Remove from Library"), object: add)
        XCTAssertEqual(XCTWaiter.wait(for: [added], timeout: 20), .completed)
        try select("library"); try capture("tvos-83-library-personal")
        let songs = app.buttons["dulcet.myLibrary.section.songs"].firstMatch
        XCTAssertTrue(songs.waitForExistence(timeout: 15)); activate(songs)
        try capture("tvos-84-songs")
        try setOffline(true)
        try select("browse"); try capture("tvos-85-offline-library")
        try setOffline(false)
    }

#if os(tvOS)
    @MainActor
    func testFullGridMoreOpensAlbumActionsAndMembershipReachesServer() throws {
        continueAfterFailure = false
        try setOffline(false)
        let album = try fixtureAlbum()
        try removeAlbum(album.id)
        defer { try? removeAlbum(album.id) }
        try launch()
        try select("browse")
        try addFromFullGridMore(album)
    }

    private func addFromFullGridMore(_ album: (id: String, title: String)) throws {
        try openAlbumList()
        let tile = albumTile(album.title)
        XCTAssertTrue(tile.waitForExistence(timeout: 30), "The full album grid must render the fixture")
        focus(tile)
        XCTAssertTrue(tile.hasFocus, "The full-grid tile must hold remote focus")
        let add = app.descendants(matching: .any)["dulcet.membership.album.\(album.id)"].firstMatch
        try capture("TV-Full-Grid-Context-Menu")
        XCUIRemote.shared.press(.select, forDuration: 1.5)
        XCTAssertTrue(add.waitForExistence(timeout: 10), "Long Select must open the tile's native context actions")
        activate(add)
        try awaitServerMembership(album.id, added: true)
        // The same tile's primary Select action must still open its detail after dismissal.
        focus(tile)
        XCUIRemote.shared.press(.select)
        XCTAssertTrue(app.staticTexts["dulcet.album.title"].firstMatch.waitForExistence(timeout: 20))
        print("TV FULL GRID MENU PROOF remoteLongSelect=true membershipServer=true primarySelect=true")
    }
#endif

    @MainActor
    func testBrowseAddRemoveRelaunchAndOfflineReplay() throws {
        continueAfterFailure = false
        try setOffline(false)
        let album = try fixtureAlbum()
        try removeAlbum(album.id)
        try launch()
        try select("library")
        XCTAssertTrue(app.buttons["dulcet.myLibrary.section.albums"].waitForExistence(timeout: 30))
        try select("browse")
#if os(tvOS)
        try addFromFullGridMore(album)
        try select("library")
        XCTAssertTrue(albumTile(album.title).waitForExistence(timeout: 20))
        try awaitServerMembership(album.id, added: true)
        try removeAlbum(album.id)
        app.terminate(); try launch()
        try select("browse")
#endif
        try openAlbum(album.title)
        revealAlbumActions()
        let add = app.descendants(matching: .any)["dulcet.membership.album.\(album.id)"].firstMatch
        XCTAssertTrue(add.waitForExistence(timeout: 30))
        XCTAssertEqual(actionText(add), "Add to Library")
        activate(add)
        awaitLabel(add, "Remove from Library", albumMenu: true)
#if !os(tvOS)
        dismissAlbumActions(add)
        // The production reader expands the newly added album, and the existing executor
        // downloads it without an explicit Download action. Cut only this account's HTTP
        // route before playing, so the native engine must consume the retained local file.
        let albumResponse = try rest("getAlbum", params: ["id": album.id])
        let detail = try XCTUnwrap(albumResponse["album"] as? [String: Any])
        let songs = try XCTUnwrap(detail["song"] as? [[String: Any]])
        let songTitle = try XCTUnwrap(songs.first?["title"] as? String)
        let song = app.buttons.matching(NSPredicate(format: "label BEGINSWITH %@ OR label BEGINSWITH %@",
                                                    songTitle, "Current track, " + songTitle)).firstMatch
        XCTAssertTrue(song.waitForExistence(timeout: 20), app.debugDescription)
        let downloaded = XCTNSPredicateExpectation(predicate: NSPredicate(format: "value CONTAINS %@", "Downloaded"), object: song)
        XCTAssertEqual(XCTWaiter.wait(for: [downloaded], timeout: 90), .completed,
                       "Adding an album must automatically download its songs through the production reader")
        try setOffline(true)
        activate(song)
        let playPause = app.buttons["dulcet.mini-player.play-pause"].firstMatch
        XCTAssertTrue(playPause.waitForExistence(timeout: 30), app.debugDescription)
        awaitLabel(playPause, "Pause")
        XCTAssertTrue(app.buttons["dulcet.mini-player.open"].firstMatch.label.contains(songTitle))
        try capture("Offline-Automatic-Album-Playback")
        print("OFFLINE-AUTO-UI account=\(user) trigger=album-add downloaded=true route=offline engine=playing")
        app.terminate()
        try setOffline(false)
        try launch()
#endif
        try select("library")
        // The compact root's destination rows precede its lazy Recently Added grid.
        // Relaunch resets its scroll position; reveal the actual tile before observing it.
        revealPhoneAlbumTile(albumTile(album.title))
        XCTAssertTrue(albumTile(album.title).waitForExistence(timeout: 20))
        try awaitServerMembership(album.id, added: true)
        app.terminate()
        try launch(connect: false)
        try select("library")
        revealPhoneAlbumTile(albumTile(album.title))
        XCTAssertTrue(albumTile(album.title).waitForExistence(timeout: 20), "Saved account paints its durable My Library without reconnecting")
        activate(albumTile(album.title))
        revealAlbumActions()
        let remove = app.descendants(matching: .any)["dulcet.membership.album.\(album.id)"].firstMatch
        XCTAssertTrue(remove.waitForExistence(timeout: 20))
        activate(remove)
        try select("library")
        XCTAssertTrue(app.buttons["dulcet.myLibrary.section.albums"].waitForExistence(timeout: 20))
        // Restore online to flush the durable remove, then cut only this fixture's HTTP route.
        app.terminate(); try launch()
        try awaitServerMembership(album.id, added: false)
        try select("browse"); try openAlbum(album.title)
        try setOffline(true)
        revealAlbumActions()
        let offlineAdd = app.descendants(matching: .any)["dulcet.membership.album.\(album.id)"].firstMatch
        activate(offlineAdd)
        awaitLabel(offlineAdd, "Remove from Library", albumMenu: true)
        try select("library")
        revealPhoneAlbumTile(albumTile(album.title))
        XCTAssertTrue(albumTile(album.title).waitForExistence(timeout: 20))
        // A direct read bypasses the offline proxy: it proves the write has not reached the server.
        let pendingBeforeRelaunch = try serverContains(album.id)
        XCTAssertFalse(pendingBeforeRelaunch)
        app.terminate()
#if os(iOS)
        // Native compact rows now fit at default size. Largest text creates the real
        // offscreen/reveal condition without depending on removed introductory content.
        try launch(connect: false, variant: UIDevice.current.userInterfaceIdiom == .phone ? "largest-text" : "light")
#else
        try launch(connect: false)
#endif
        try select("library")
#if os(iOS)
        if UIDevice.current.userInterfaceIdiom == .phone {
            // Require an actual scroll at the largest text size, then require the durable
            // album to publish and become actionable after that scroll.
            // The List may not publish the offscreen section header until it scrolls.
            // Prove the visible category root first; the scroll below must reveal the album.
            XCTAssertTrue(app.buttons["dulcet.myLibrary.section.playlists"].waitForExistence(timeout: 20),
                          "The offline Library category root must paint before revealing its lazy album: " + app.debugDescription)
            XCTAssertFalse(albumTile(album.title).isHittable,
                           "The largest-text offline root must begin with its album below the viewport")
            let scrolls = revealPhoneAlbumTile(albumTile(album.title))
            XCTAssertGreaterThan(scrolls, 0, "The offline relaunch proof must actually reveal the lazy grid")
            XCTAssertTrue(albumTile(album.title).isHittable)
            print("MY LIBRARY OFFLINE RELAUNCH lazy-grid-revealed scrolls=\(scrolls)")
        }
#endif
        XCTAssertTrue(albumTile(album.title).waitForExistence(timeout: 20), "Offline relaunch paints the durable pending add before reconnect")
        let pendingAfterRelaunch = try serverContains(album.id)
        XCTAssertFalse(pendingAfterRelaunch)
        app.terminate()
        try setOffline(false)
        try launch()
        try select("library")
        revealPhoneAlbumTile(albumTile(album.title))
        XCTAssertTrue(albumTile(album.title).waitForExistence(timeout: 20))
        try awaitServerMembership(album.id, added: true)
        try removeAlbum(album.id)
    }

    /// Drives the production library on each Apple shell using disposable server metadata.
    @MainActor
    func testLibraryDepthPinSurvivesRelaunchAndOfflineDestinationsRemainReachable() throws {
        continueAfterFailure = false
        try setOffline(false)
        let album = try fixtureAlbum()
        try launch()
        try select("browse")
        try openAlbum(album.title)
        revealAlbumActions()
        let pin = app.descendants(matching: .any)["dulcet.pin.album.\(album.id)"].firstMatch
        XCTAssertTrue(pin.waitForExistence(timeout: 20))
        if !actionText(pin).hasPrefix("Unpin") { activate(pin) }
        awaitLabel(pin, "Unpin \(album.title)", albumMenu: true)
        app.terminate()
        try setOffline(true)
        try launch(connect: false)
        try select("library")
#if os(macOS)
        // On the hosted Mac, this exact query reports firstMatch=false but count=1 and
        // indexed.exists=true. Avoid the firstMatch shortcut without changing the target.
        let pinnedHeading = app.staticTexts
            .matching(identifier: "dulcet.library.pinned.title").element(boundBy: 0)
#else
        let pinnedHeading = app.staticTexts["dulcet.library.pinned.title"].firstMatch
#endif
        XCTAssertTrue(pinnedHeading.waitForExistence(timeout: 20))
        XCTAssertEqual(pinnedHeading.label, "Pinned", "The actual pinned section heading must be present")
        print("MY LIBRARY PIN heading=\(pinnedHeading.label)")
        XCTAssertTrue(albumTile(album.title).waitForExistence(timeout: 20), "Pinned metadata must survive an offline relaunch")
        try capture("Library-Depth-Pinned-Offline")
        let downloaded = app.buttons["dulcet.library.downloaded"].firstMatch
#if os(tvOS)
        XCTAssertFalse(downloaded.exists, "Apple TV has no durable user-managed media downloads in v1")
#else
        XCTAssertTrue(downloaded.waitForExistence(timeout: 10))
        activate(downloaded)
        XCTAssertTrue(app.staticTexts["Music stored on this device, ready to play offline."].firstMatch.waitForExistence(timeout: 20))
        try capture("Library-Depth-Downloaded")
        try select("library")
#endif
        try select("browse")
        let songs = app.buttons["dulcet.library.songs"].firstMatch
        XCTAssertTrue(songs.waitForExistence(timeout: 10))
        activate(songs)
#if os(tvOS)
        // The TV Picker is a native segmented control. Restrict its query rather than
        // collecting every accessibility descendant in the cached Songs list.
        let sort = app.segmentedControls["dulcet.library.songs.sort"].firstMatch
        XCTAssertTrue(sort.waitForExistence(timeout: 20))
        XCTAssertTrue(sort.buttons["Title"].exists)
#else
        XCTAssertTrue(app.descendants(matching: .any)["dulcet.library.songs.sort"].firstMatch.waitForExistence(timeout: 20))
#endif
        try capture("Library-Depth-Songs")
        try setOffline(false)
        try select("browse")
        try openAlbum(album.title)
        revealAlbumActions()
        let unpin = app.descendants(matching: .any)["dulcet.pin.album.\(album.id)"].firstMatch
        XCTAssertTrue(unpin.waitForExistence(timeout: 20))
        if actionText(unpin).hasPrefix("Unpin") { activate(unpin) }
        awaitLabel(unpin, "Pin \(album.title)", albumMenu: true)
    }

#if !os(tvOS)
    /// Catalog order, durable category selection, shuffle queue, and artist depth through UI.
    @MainActor
    func testIPhoneLibrarySortShuffleAndArtistDepth() throws {
#if os(iOS)
        guard UIDevice.current.userInterfaceIdiom == .phone else { throw XCTSkip("Compact library proof") }
        continueAfterFailure = false
        try setOffline(false)
        try launch()
        try select("browse")
        let albums = app.buttons["dulcet.reader.section.albums"].firstMatch
        try reveal(albums)
        activate(albums)
        let options = app.buttons["dulcet.library.albums.options"].firstMatch
        XCTAssertTrue(options.waitForExistence(timeout: 20)); activate(options)
        let sort = app.buttons["dulcet.library.albums.sort"].firstMatch
        XCTAssertTrue(sort.waitForExistence(timeout: 20))
        activate(sort)
        let release = app.buttons["Release Date"].firstMatch
        XCTAssertTrue(release.waitForExistence(timeout: 10))
        activate(release)
        XCTAssertTrue(options.waitForExistence(timeout: 10)); activate(options)
        XCTAssertTrue(sort.waitForExistence(timeout: 10))
        dismissToolbarMenu(options, action: sort)
        XCTAssertTrue(app.buttons["dulcet.library.album"].firstMatch.waitForExistence(timeout: 20))
        try capture("Library-Depth-Release-Sort")
        app.terminate()
        try launch(connect: false)
        try select("browse")
        try reveal(albums); activate(albums)
        XCTAssertTrue(options.waitForExistence(timeout: 10)); activate(options)
        XCTAssertTrue(sort.waitForExistence(timeout: 20))
        // UIKit's native submenu exports Sort By without its SwiftUI accessibilityValue.
        // Read the actual selected Picker action inside it after the saved launch.
        activate(sort)
        XCTAssertTrue(release.waitForExistence(timeout: 10))
        XCTAssertTrue(release.isSelected, "The selected Release Date action must survive relaunch")
        dismissToolbarMenu(options, action: release)
        dismissToolbarMenu(options, action: sort)
        // Return through a different tab and reselect Browse to reach its navigation root.
        try select("library"); try select("browse")
        for _ in 0..<8 {
            if app.buttons["dulcet.reader.section.artists"].exists { break }
            let back = app.navigationBars.buttons.firstMatch
            guard back.exists else { break }
            back.tap()
        }
        let artists = app.buttons["dulcet.reader.section.artists"].firstMatch
        try reveal(artists); activate(artists)
        let artist = app.buttons["dulcet.library.artist"].firstMatch
        XCTAssertTrue(artist.waitForExistence(timeout: 20)); activate(artist)
        XCTAssertTrue(app.staticTexts["Top Songs"].firstMatch.waitForExistence(timeout: 20))
        try capture("Library-Depth-Artist-Top-Songs")
        let artistAlbum = app.buttons["dulcet.library.album"].firstMatch
        try reveal(artistAlbum)
        XCTAssertTrue(artistAlbum.exists, "Artist discography contains the fixture's albums")
        app.scrollViews.firstMatch.swipeUp()
        XCTAssertTrue(artistAlbum.isHittable, "The discography can be brought fully into the compact viewport")
        try capture("Library-Depth-Artist-Discography")
        // This proof shuffles the complete server catalog. Its destination is Browse;
        // Library's Songs category contains only personal membership.
        try select("browse")
        let songs = app.buttons["dulcet.library.songs"].firstMatch
        try reveal(songs); activate(songs)
        let shuffle = app.buttons["dulcet.songs.shuffleAll"].firstMatch
        XCTAssertTrue(shuffle.waitForExistence(timeout: 20)); XCTAssertTrue(shuffle.isEnabled)
        activate(shuffle)
        XCTAssertTrue(app.buttons["dulcet.mini-player.open"].firstMatch.waitForExistence(timeout: 30), "Shuffle All creates a real playback queue")
        try capture("Library-Depth-Shuffle-All")
        print("LIBRARY DEPTH IPHONE sort=releaseDate persisted=true artistTopSongs=true discography=true shuffleQueue=true")
#else
        throw XCTSkip("iPhone library proof")
#endif
    }

#if os(iOS)
    @MainActor private func reveal(_ element: XCUIElement) throws {
        for _ in 0..<12 {
            if element.exists && element.isHittable { return }
            let list = app.collectionViews.firstMatch
            if list.exists { list.swipeUp() }
            else { app.scrollViews.firstMatch.swipeUp() }
        }
        XCTAssertTrue(element.waitForExistence(timeout: 10))
        XCTAssertTrue(element.isHittable)
    }
#endif

    @MainActor
    func testLibrarySearchGroupsAndRecentQuerySurviveRelaunch() throws {
        continueAfterFailure = false
        try setOffline(false)
        try launch()
        try select("search")
#if os(iOS)
        let field = dulcetSearchField(in: app)
#else
        let field = app.descendants(matching: .any).matching(NSPredicate(format: "identifier == %@ OR elementType == %d", "dulcet.search.field", XCUIElement.ElementType.searchField.rawValue)).firstMatch
#endif
        XCTAssertTrue(field.waitForExistence(timeout: 20))
        activate(field)
        field.typeText("Dulcet")
        let artists = app.buttons["dulcet.search.group.artist"].firstMatch
        let albums = app.buttons["dulcet.search.group.album"].firstMatch
        let songs = app.buttons["dulcet.search.group.track"].firstMatch
        XCTAssertTrue(artists.waitForExistence(timeout: 30))
        XCTAssertTrue(albums.waitForExistence(timeout: 30))
        XCTAssertTrue(songs.waitForExistence(timeout: 30))
        activate(artists)
        XCTAssertTrue(app.buttons["dulcet.search.group.all"].firstMatch.waitForExistence(timeout: 10))
        let resultCount = app.staticTexts["dulcet.search.resultCount"].firstMatch
        XCTAssertTrue(resultCount.waitForExistence(timeout: 10))
#if os(macOS)
        let countText = NSPredicate(format: "value == %@", "1 result")
#else
        let countText = NSPredicate(format: "label == %@", "1 result")
#endif
        XCTAssertEqual(XCTWaiter.wait(for: [XCTNSPredicateExpectation(predicate: countText, object: resultCount)], timeout: 20), .completed,
                       "The selected Artists group counts its own results")
#if os(macOS)
        XCTAssertEqual(resultCount.value as? String, "1 result")
        // SwiftUI's native Table is a SwiftUIOutlineTableView with AXOutline role.
        // Scope it by the rendered result so the sidebar's outline cannot satisfy this proof.
        let artistResults = app.outlines.containing(.staticText, identifier: "dulcet.search.result.0")
            .element(boundBy: 0)
        XCTAssertTrue(artistResults.waitForExistence(timeout: 20), "The selected group must render its real result cell")
        let oneArtistRow = XCTNSPredicateExpectation(predicate: NSPredicate { _, _ in
            artistResults.outlineRows.count == 1
        }, object: nil)
        XCTAssertEqual(XCTWaiter.wait(for: [oneArtistRow], timeout: 20), .completed,
                       "Artists See All must contain only the fixture artist")
        XCTAssertEqual(artistResults.outlineRows.count, 1)
        print("MY LIBRARY SEARCH selected=Artists count=\(resultCount.value ?? "nil") rows=\(artistResults.outlineRows.count) role=outline")
#else
        XCTAssertEqual(resultCount.label, "1 result")
#endif
        try capture("Library-Depth-Search-Groups")
        activate(field)
        field.typeText("\n")
        app.terminate()
        try launch(connect: false)
        try select("search")
        XCTAssertTrue(app.buttons["dulcet.search.recent.Dulcet"].firstMatch.waitForExistence(timeout: 20), "Submitted query survives relaunch for this account")
        activate(app.buttons["dulcet.search.recent.Dulcet"].firstMatch)
        XCTAssertTrue(artists.waitForExistence(timeout: 20), "Recent query reruns the real reader search")
    }
#endif

    @MainActor
    func testNonDulcetServerKeepsTheOriginalLibraryNavigation() throws {
        continueAfterFailure = false
        try launch(server: navidrome, username: "dulcet-admin", password: "dulcet-ci-canary-password")
        try select("library")
        XCTAssertTrue(app.buttons["dulcet.reader.section.favourites"].waitForExistence(timeout: 30))
        XCTAssertTrue(destination("browse").exists, "Browse is stable on every server")
        try select("browse")
        try openAlbumList()
        let catalog = app.buttons["dulcet.library.album"].firstMatch
        XCTAssertTrue(catalog.waitForExistence(timeout: 30), "The server catalog must load before asserting membership controls are absent")
        XCTAssertFalse(app.descendants(matching: .any)["dulcet.myLibrary"].exists)
        XCTAssertFalse(app.buttons.matching(NSPredicate(format: "identifier BEGINSWITH 'dulcet.membership.'")).firstMatch.exists)
    }

    @MainActor
    func testLibraryBrowseAndAlbumInLightDarkAndLargestText() throws {
        continueAfterFailure = false
        try setOffline(false)
        let album = try fixtureAlbum()
        for variant in ["light", "dark", "largest-text"] {
            app.terminate()
            try removeAlbum(album.id)
            try launch(variant: variant)
            try select("library")
            XCTAssertTrue(app.buttons["dulcet.myLibrary.section.albums"].waitForExistence(timeout: 30))
            try capture("Library-\(variant)")
            try select("browse")
            let catalog = app.buttons["dulcet.reader.section.albums"].firstMatch
#if os(iOS)
            try reveal(catalog)
#else
            XCTAssertTrue(catalog.waitForExistence(timeout: 30))
#endif
            XCTAssertTrue(catalog.isHittable, "Browse must expose its Albums destination")
            try capture("Browse-\(variant)")
            try openAlbum(album.title)
            revealAlbumActions()
            let add = app.descendants(matching: .any)["dulcet.membership.album.\(album.id)"].firstMatch
            XCTAssertTrue(add.waitForExistence(timeout: 30))
            XCTAssertEqual(actionText(add), "Add to Library")
            try captureAlbumAdd("Album-Add-\(variant)", button: add)
        }
    }

    @MainActor
    func testAlbumAddRemainsVisibleAtLargestTextOnIPhone() throws {
#if os(iOS)
        guard UIDevice.current.userInterfaceIdiom == .phone else { throw XCTSkip("Compact iPhone capture correction") }
        continueAfterFailure = false
        try setOffline(false)
        let album = try fixtureAlbum()
        try removeAlbum(album.id)
        try launch(variant: "largest-text")
        try select("browse")
        let albums = app.buttons["dulcet.reader.section.albums"].firstMatch
        try reveal(albums)
        try openAlbumList()
        let catalogAlbum = albumTile(album.title)
        let catalogScrolls = revealPhoneAlbumTile(catalogAlbum)
        XCTAssertTrue(catalogAlbum.exists && catalogAlbum.isHittable,
            "The catalog must be visible before searching its albums: " + app.debugDescription)
        print("MY LIBRARY LARGEST TEXT catalog-scrolls=\(catalogScrolls) catalog-visible=true")
        try openAlbum(album.title)
        revealAlbumActions()
        let add = app.descendants(matching: .any)["dulcet.membership.album.\(album.id)"].firstMatch
        XCTAssertTrue(add.waitForExistence(timeout: 30))
        XCTAssertEqual(actionText(add), "Add to Library")
        try captureAlbumAdd("Album-Add-largest-text", button: add)
#else
        throw XCTSkip("Compact iPhone capture correction")
#endif
    }

    @MainActor private func captureAlbumAdd(_ name: String, button: XCUIElement) throws {
#if os(iOS)
        // More is a native overlay, so its actions can overlap navigation chrome.
        // Require the complete action to be visible and tappable in the real window.
        let frame = button.frame
        XCTAssertTrue(button.isHittable, "The native Add action must be tappable at this text size")
        XCTAssertFalse(frame.isEmpty)
        XCTAssertTrue(app.windows.firstMatch.frame.contains(frame),
                      "The complete native Add action must remain inside the window: \(frame)")
        print("MY LIBRARY CAPTURE \(name) native-add=\(frame) fully-visible=true")
#endif
        try capture(name)
    }

    @MainActor
    private func launch(connect: Bool = true, server: String? = nil, username: String? = nil, password: String? = nil, variant: String = "light") throws {
        var launchArguments = ["-dulcet-debug-ui-markers", "-dulcet-debug-navigation-receipts", variant == "dark" ? "-dulcet-debug-dark-mode" : "-dulcet-debug-light-mode"]
        if variant == "largest-text" { launchArguments += ["-dulcet-debug-largest-text"] }
        if connect {
            launchArguments += ["-dulcet-debug-connect-account", "-dulcet-debug-account-server-url", server ?? base,
                "-dulcet-debug-account-username", username ?? user, "-dulcet-debug-account-password", password ?? self.password]
        }
        launchArguments += ["-dulcet-debug-account-fixture", accountFixtureNamespace]
        app.launchArguments = launchArguments
#if os(macOS)
        if !installedFixtureNetworkMonitor {
            installedFixtureNetworkMonitor = true
            _ = addUIInterruptionMonitor(withDescription: "Allow the disposable Python fixture's local network") { dialog in
                self.allowFixtureLocalNetwork(dialog)
            }
        }
#endif
        app.launch()
#if os(macOS)
        // The fixture's Python process can trigger macOS's local-network permission dialog.
        // Handle only that named fixture prompt, including when it predates this app's launch.
        let notifications = XCUIApplication(bundleIdentifier: "com.apple.UserNotificationCenter")
        for dialog in notifications.dialogs.allElementsBoundByIndex {
            _ = allowFixtureLocalNetwork(dialog)
        }
        app.activate()
        let window = app.windows.firstMatch
        if !window.waitForExistence(timeout: 10) {
            // The hosted app can expose its menu bar without a window. Start this proof
            // through the same File > New Window action available to a person.
            app.menuBars.menuBarItems["File"].click()
            let newWindow = app.menuItems["New Window"].firstMatch
            XCTAssertTrue(newWindow.waitForExistence(timeout: 5), app.debugDescription)
            XCTAssertTrue(newWindow.isEnabled, "File > New Window must be available")
            newWindow.click()
        }
        XCTAssertTrue(window.waitForExistence(timeout: 10), app.debugDescription)
        let screenWidth = try XCTUnwrap(NSScreen.main?.frame.width)
        let visible = XCTNSPredicateExpectation(predicate: NSPredicate { _, _ in
            window.frame.minX >= 0 && window.frame.maxX <= screenWidth
        }, object: nil)
        XCTAssertEqual(XCTWaiter.wait(for: [visible], timeout: 10), .completed,
                       "The Mac window must remain on screen after every launch: \(window.frame)")
#elseif os(iOS)
        if connect {
            // Browse is inserted as soon as capability discovery publishes. On a cold
            // connection its tab can precede the Home landing and catalog publication;
            // tapping during that handoff can leave Home selected. Wait for actual reader
            // content, as the ordinary-server path does, before choosing another destination.
            // A measured cold negotiation remained Connecting beyond 30 seconds under CI
            // host contention, then published the actual album tiles; allow that cold path.
            let connected = app.buttons["dulcet.library.album"].firstMatch
            if !connected.waitForExistence(timeout: 90) {
                let welcome = app.buttons["Connect a Server"]
                if welcome.exists { welcome.tap() }
                let copy = app.staticTexts.allElementsBoundByIndex.map(\.label).joined(separator: " | ")
                XCTFail("Disposable account fixture failed to connect: \(copy)")
            }
        }
#endif
    }
#if os(macOS)
    @MainActor private func allowFixtureLocalNetwork(_ dialog: XCUIElement) -> Bool {
        let text = dialog.staticTexts.allElementsBoundByIndex.map {
            $0.label + " " + ($0.value as? String ?? "")
        }.joined(separator: " ")
        guard text.contains("Python"), text.contains("local networks") else { return false }
        let allow = dialog.buttons["Allow"].firstMatch
        guard allow.exists else { return false }
        allow.click()
        let dismissed = XCTNSPredicateExpectation(predicate: NSPredicate(format: "exists == false"), object: dialog)
        XCTAssertEqual(XCTWaiter.wait(for: [dismissed], timeout: 5), .completed)
        print("MY LIBRARY MAC fixture-local-network-prompt handled=Allow")
        return true
    }
#endif
    @MainActor private func destination(_ section: String) -> XCUIElement {
#if os(macOS)
        // Labels also match section headings and toolbar titles. The actual navigation row
        // carries this identifier on its text in the macOS outline's accessibility tree.
        return app.descendants(matching: .any)["dulcet.sidebar.\(section)"].firstMatch
#elseif os(tvOS)
        return app.buttons["dulcet.tab.\(section)"].firstMatch
#else
        let nativeTab = app.tabBars.buttons[section == "library" ? "Library" : section == "browse" ? "Browse" : "Search"].firstMatch
        if nativeTab.exists { return nativeTab }
        if UIDevice.current.userInterfaceIdiom == .pad {
            // Adaptable TabView exposes its native sidebar destination as a Cell.
            let nativeSidebar = app.descendants(matching: .any)["dulcet.tab.\(section)"].firstMatch
            if nativeSidebar.exists { return nativeSidebar }
            return app.descendants(matching: .any)["dulcet.sidebar.\(section)"].firstMatch
        }
        let title = section == "library" ? "Library" : section == "browse" ? "Browse" : "Search"
        return app.tabBars.buttons[title].firstMatch
#endif
    }
    @MainActor private func select(_ section: String) throws {
#if os(iOS)
        // Scope native input to this launch's process, as the design tour does.
        // Destination readiness and selected-state checks remain explicit.
        withoutIdleWaits { [self] in selectDestination(section) }
#else
        selectDestination(section)
#endif
    }
    @MainActor private func selectDestination(_ section: String) {
#if os(iOS)
        if section != "search" {
            let field = app.searchFields.firstMatch
            let closeSearch = app.buttons["Close"].firstMatch
            if field.exists, closeSearch.exists, closeSearch.isHittable,
               abs(closeSearch.frame.midY - field.frame.midY) < field.frame.height {
                closeSearch.tap()
                XCTAssertTrue(field.waitForNonExistence(timeout: 10), "Close native Search before changing destination")
                guard !field.exists else { return }
            }
        }
        // A saved/debug connection can leave Account presented above the landing page.
        // Its underlying tab frames remain queryable but cannot receive the tap.
        let accountClose = app.buttons["dulcet.account.close"].firstMatch
        if accountClose.exists {
            activate(accountClose)
            let dismissed = accountClose.waitForNonExistence(timeout: 10)
            XCTAssertTrue(dismissed, "Dismiss Account before selecting a native destination")
            print("MY LIBRARY IOS account-dismissed=\(dismissed) target=\(section)")
            guard dismissed else { return }
        }
#endif
        let membership = app.descendants(matching: .any).matching(NSPredicate(format: "identifier BEGINSWITH %@", "dulcet.membership.album.")).firstMatch
        if membership.exists {
#if os(macOS)
            app.typeKey(.escape, modifierFlags: [])
#elseif os(tvOS)
            XCUIRemote.shared.press(.menu)
#else
            app.coordinate(withNormalizedOffset: CGVector(dx: 0.05, dy: 0.5)).tap()
#endif
        }
#if os(iOS)
        if section == "search", app.searchFields.firstMatch.exists { return }
#endif
        let control = destination(section)
        XCTAssertTrue(control.waitForExistence(timeout: 30), "Missing destination \(section)")
#if os(tvOS)
        // Menu pops a pushed page, then returns focus to the section bar. That focus
        // transition is asynchronous: the retained Songs -> Browse failure sent another
        // Menu before observing it and eventually left the app for the TV Home screen.
        let remote = XCUIRemote.shared
        let focusedTab = app.buttons.matching(NSPredicate(
            format: "identifier BEGINSWITH 'dulcet.tab.' AND hasFocus == true"
        )).firstMatch
        var menuPresses = 0
        while !focusedTab.exists && menuPresses < 6 {
            guard app.state == .runningForeground else {
                XCTFail("Menu navigation left the app before reaching the section bar")
                return
            }
            remote.press(.menu)
            menuPresses += 1
            // Observe the result of this press before deciding whether a page still needs
            // popping. A successful return must prove actual bar focus, not exhaust retries.
            if focusedTab.waitForExistence(timeout: 3) { break }
        }
        guard focusedTab.exists else {
            XCTFail("Menu did not return focus to the section bar for \(section): \(app.debugDescription)")
            return
        }
        print("MY LIBRARY TV section-bar-focused target=\(section) menu-presses=\(menuPresses)")
        let order = ["listenNow", "library", "browse", "search", "nowPlaying", "settings"]
        for _ in 0..<order.count {
            if control.hasFocus {
                XCTAssertTrue(control.hasFocus, "The requested section must actually have remote focus")
                remote.press(.select)
                return
            }
            guard let current = order.firstIndex(where: { app.buttons["dulcet.tab.\($0)"].firstMatch.hasFocus }),
                  let target = order.firstIndex(of: section) else {
                XCTFail("Section-bar focus disappeared while reaching \(section)")
                return
            }
            remote.press(target > current ? .right : .left)
        }
        XCTFail("Remote could not reach \(section): \(app.debugDescription)")
#else
        activateDestination(control)
#if os(iOS)
        // Adaptable tabs can be top-bar Buttons or sidebar Cells on iPad. Observe
        // the destination returned by the native query instead of assuming the legacy
        // sidebar identifier exists. Immediate Search replaces its tab button with a field.
        // Other destinations still require the selected state of their existing control.
        func destinationReached() -> Bool {
            if section == "search", app.searchFields.firstMatch.exists { return true }
            return control.exists && control.isSelected
        }
        let deadline = Date().addingTimeInterval(10)
        var retries = 0
        repeat {
            let remaining = max(0, deadline.timeIntervalSince(Date()))
            let selected = XCTNSPredicateExpectation(predicate: NSPredicate { _, _ in destinationReached() }, object: nil)
            if XCTWaiter.wait(for: [selected], timeout: min(3, remaining)) == .completed { return }
            guard Date() < deadline else { break }
            if retries < 2, Date() < deadline, control.exists, control.isHittable {
                retries += 1
                print("MY LIBRARY IOS unacknowledged-destination-tap target=\(section) retry=\(retries)")
                activateDestination(control)
                if destinationReached() { return }
            }
        } while Date() < deadline
        XCTAssertTrue(destinationReached(), "The native destination tap must select \(section): \(app.debugDescription)")
#endif
#if os(macOS)
        // A macOS List does not write its selection binding when its selected row is clicked
        // again. Return through the visible navigation controls, just as Menu pops on tvOS,
        // so a retained album detail cannot hide the Library root's membership/empty state.
        if section == "library" || section == "browse" {
            let back = app.toolbars.buttons["chevron.backward"].firstMatch
            for _ in 0..<6 {
                if !back.exists { break }
                activate(back)
            }
            XCTAssertFalse(back.exists, "The selected destination must reach its root")
        }
#endif
#endif
    }
#if os(iOS)
    /// The same guarded interaction scope used by the account and player UI proofs.
    /// It applies to the current application process and retains ordinary native input.
    @MainActor private func withoutIdleWaits(_ body: @escaping () -> Void) {
        let selector = NSSelectorFromString("_performWithInteractionOptions:block:")
        guard let method = class_getInstanceMethod(type(of: app), selector),
              let encoding = method_getTypeEncoding(method).map({ String(cString: $0) }),
              encoding.filter({ !$0.isNumber }) == "v@:I@?" else {
            print("MY LIBRARY IOS destination interaction options unavailable; using ordinary waits")
            body()
            return
        }
        typealias Perform = @convention(c) (AnyObject, Selector, UInt32, @convention(block) () -> Void) -> Void
        let perform = unsafeBitCast(method_getImplementation(method), to: Perform.self)
        perform(app, selector, 3, body)
    }
#endif
    @MainActor private func albumTile(_ title: String) -> XCUIElement {
        app.buttons.matching(NSPredicate(format: "identifier == 'dulcet.library.album' AND label BEGINSWITH %@", title)).firstMatch
    }
    @discardableResult
    @MainActor private func revealPhoneAlbumTile(_ tile: XCUIElement) -> Int {
#if os(iOS)
        guard UIDevice.current.userInterfaceIdiom == .phone else { return 0 }
        // Native List exposes a CollectionView; artwork pages expose a ScrollView.
        // Scroll the actual destination container before asserting a lazy tile's publication.
        for scrolls in 0..<8 {
            if tile.exists && tile.isHittable { return scrolls }
            let list = app.collectionViews.firstMatch
            if list.exists { list.swipeUp() }
            else { app.scrollViews.firstMatch.swipeUp() }
        }
        return 8
#else
        return 0
#endif
    }
    @MainActor private func openAlbumList() throws {
        let albumListLink = "dulcet.reader.section.albums"
        let all = app.buttons[albumListLink].firstMatch
        // Wait for the appropriate catalog link to publish before leaving Browse.
        XCTAssertTrue(all.waitForExistence(timeout: 30), "Browse must publish its album-list link: \(albumListLink). \(app.debugDescription)")
        activate(all)
    }
    @MainActor private func openAlbum(_ title: String) throws {
        // Horizontal Browse shelves instantiate only visible cards. Enter the full list on
        // TV as well, so waiting for a named album does not depend on its shelf position.
        if app.buttons["dulcet.reader.section.albums"].firstMatch.exists { try openAlbumList() }
        let tile = albumTile(title)
#if !os(tvOS)
        // The sidebar is also a scroll view. Wait for catalog publication and scroll only the
        // album page; scrolling firstMatch can hide Library before the catalog finishes loading.
        let albumPage = app.scrollViews.containing(.button, identifier: "dulcet.library.album").firstMatch
        XCTAssertTrue(albumPage.waitForExistence(timeout: 30), "The album page must load before scrolling")
#elseif os(iOS)
        let albumPage = app.scrollViews.containing(.button, identifier: "dulcet.library.album").firstMatch
        XCTAssertTrue(albumPage.waitForExistence(timeout: 30), "The catalog must load before scrolling its tiles")
#endif
#if !os(tvOS)
        let viewport = app.windows.firstMatch.frame.insetBy(dx: 0, dy: 100)
        var swipes = 0
        for _ in 0..<6 {
            if tile.exists && tile.isHittable {
                let frame = tile.frame
                if !frame.isEmpty && viewport.contains(CGPoint(x: frame.midX, y: frame.midY)) { break }
            }
#if os(macOS)
            albumPage.scroll(byDeltaX: 0, deltaY: -450)
#else
            // On iPad the first scroll view may be the sidebar. Scroll the actual catalog,
            // and require the tile's center, not a sliver at the bottom, to enter the window.
            albumPage.swipeUp()
#endif
            swipes += 1
        }
        XCTAssertTrue(tile.waitForExistence(timeout: 30))
        XCTAssertTrue(tile.isHittable, "The album must be visible before activation")
        let tileFrame = tile.frame
        XCTAssertTrue(viewport.contains(CGPoint(x: tileFrame.midX, y: tileFrame.midY)),
                      "The album's center must be inside the visible catalog before activation: \(tileFrame)")
        print("MY LIBRARY ALBUM target=\(title) center-in-viewport=true swipes=\(swipes) frame=\(tileFrame)")
#else
        // At the largest text size the first native grid row fills the viewport. Its lazy
        // second row is absent from AX until remote navigation scrolls it into view (run
        // 40547199270). Reach it with the remote before resolving the named tile.
        if !tile.exists {
            let firstTile = app.buttons["dulcet.library.album"].firstMatch
            XCTAssertTrue(firstTile.waitForExistence(timeout: 30), "The album grid must publish before remote navigation")
            focus(firstTile)
            var presses = 0
            for _ in 0..<12 {
                if tile.exists { break }
                XCUIRemote.shared.press(.down)
                presses += 1
            }
            print("MY LIBRARY TV ALBUM target=\(title) reveal-down-presses=\(presses) published=\(tile.exists)")
        }
        XCTAssertTrue(tile.waitForExistence(timeout: 30))
#endif
        activate(tile)
        // Resolve the indexed result, as for the iPad sidebar. On the Mac the firstMatch wait
        // reported absent for 15 s while the failure hierarchy held this exact titled StaticText.
        let header = app.staticTexts.matching(identifier: "dulcet.album.title").element(boundBy: 0)
        let opened = header.waitForExistence(timeout: 15)
        if !opened {
            print("MY LIBRARY NAVIGATION " + app.staticTexts["dulcet.debug.ui-markers"].firstMatch.label)
            print("MY LIBRARY FAILED ALBUM HIERARCHY " + app.debugDescription)
        }
        XCTAssertTrue(opened, "The activated tile must open its album page")
        XCTAssertEqual(header.label, title, "The album page must name the activated tile")
    }
    @MainActor private func activateDestination(_ element: XCUIElement) {
#if os(iOS)
        if element.identifier.hasPrefix("dulcet.tab.") {
            // A native tab can report an invalid synthesized hit point (-1, -1)
            // despite publishing its visible frame. Tap that frame, then require selection.
            let frame = element.frame
            guard frame.width > 0, frame.height > 0, app.frame.contains(frame) else {
                XCTFail("The native destination must have an on-screen frame: " + element.debugDescription)
                return
            }
            let origin = app.coordinate(withNormalizedOffset: .zero)
            origin.withOffset(CGVector(dx: frame.midX - app.frame.minX,
                                       dy: frame.midY - app.frame.minY)).tap()
            return
        }
#endif
        activate(element)
    }
    @MainActor private func activate(_ element: XCUIElement) {
#if os(tvOS)
        focus(element)
        if isFocused(element) { XCUIRemote.shared.press(.select) }
#elseif os(macOS)
        element.click()
#else
        element.tap()
#endif
    }
#if os(tvOS)
    @MainActor private func focusBrowseShelfAlbum(_ title: String) throws -> XCUIElement {
        // The membership context-menu proof uses Browse's horizontal shelf, its original
        // production path. Real RIGHT movement composes lazy tiles outside the viewport.
        let shelf = app.otherElements["dulcet.reader.home.recentlyAdded"].firstMatch
        let tiles = shelf.buttons.matching(identifier: "dulcet.library.album")
        XCTAssertTrue(tiles.firstMatch.waitForExistence(timeout: 30), "Browse must publish its album shelf")
        focus(tiles.firstMatch)
        let tile = tiles.matching(NSPredicate(format: "label BEGINSWITH %@", title)).firstMatch
        for _ in 0..<20 {
            if tile.exists {
                focus(tile)
                XCTAssertTrue(tile.hasFocus, "The named shelf album must hold focus before its menu")
                return tile
            }
            XCUIRemote.shared.press(.right)
        }
        XCTFail("The remote did not reveal shelf album \(title): \(app.debugDescription)")
        return tile
    }
    private func snapshotIfPresent(_ element: XCUIElement) -> XCUIElementSnapshot? {
        guard element.exists else { return nil }
        return try? element.snapshot()
    }
    private func focusedSnapshots() -> (button: XCUIElementSnapshot?, cell: XCUIElementSnapshot?) {
        let button = snapshotIfPresent(app.buttons.matching(NSPredicate(format: "hasFocus == true")).firstMatch)
        let cell = snapshotIfPresent(app.cells.matching(NSPredicate(format: "hasFocus == true")).firstMatch)
        return (button, cell)
    }
    private func isFocused(
        _ target: XCUIElementSnapshot,
        among focused: (button: XCUIElementSnapshot?, cell: XCUIElementSnapshot?)
    ) -> Bool {
        if target.hasFocus { return true }
        // Native confirmation dialogs expose an identified action and a focused UIKit
        // alias at the same label/frame. The alias carries focus, the identified one does not.
        if let button = focused.button, button.label == target.label, button.frame == target.frame { return true }
        // tvOS context-menu actions sit inside a focused cell rather than holding focus themselves.
        return focused.cell?.frame.contains(CGPoint(x: target.frame.midX, y: target.frame.midY)) == true
    }
    private func isFocused(_ target: XCUIElement) -> Bool {
        guard let target = snapshotIfPresent(target) else { return false }
        if target.hasFocus { return true }
        return isFocused(target, among: focusedSnapshots())
    }
    private func focus(_ target: XCUIElement) {
        func identity(_ element: XCUIElementSnapshot) -> String {
            "\(element.elementType.rawValue)|\(element.identifier)|\(element.label)"
        }
        func opposite(_ direction: XCUIRemote.Button) -> XCUIRemote.Button {
            switch direction {
            case .up: .down
            case .down: .up
            case .left: .right
            default: .left
            }
        }
        let targetIdentifier = target.identifier, targetLabel = target.label
        var lastTargetFrame = target.frame
        var tried: [String: Set<UInt>] = [:]
        var arrival: [String: XCUIRemote.Button] = [:]
        for move in 0..<40 {
            let started = ProcessInfo.processInfo.systemUptime
            // Each live attribute read resolves a fresh accessibility query. Reuse one snapshot
            // per element per move, including diagnostics, instead of resolving every field again.
            let targetSnapshot = snapshotIfPresent(target)
            if targetSnapshot?.hasFocus == true { return }
            let focused = focusedSnapshots()
            if let targetSnapshot {
                if isFocused(targetSnapshot, among: focused) { return }
                lastTargetFrame = targetSnapshot.frame
            }
            let published = targetSnapshot != nil
            guard let current = focused.cell ?? focused.button else {
                XCUIRemote.shared.press(.down)
                continue
            }
            let key = identity(current), a = current.frame, b = lastTargetFrame
            let horizontal: XCUIRemote.Button = b.midX > a.midX ? .right : .left
            let vertical: XCUIRemote.Button = b.midY > a.midY ? .down : .up
            let overlaps = min(a.maxY, b.maxY) > max(a.minY, b.minY)
            let horizontalNeighbor = overlaps && (b.minX >= a.maxX || b.maxX <= a.minX)
            let verticalDistance = abs(a.midY - b.midY) > max(20, min(a.height, b.height) / 2)
            var directions: [XCUIRemote.Button] = horizontalNeighbor || !verticalDistance
                ? [horizontal, vertical, opposite(vertical), opposite(horizontal)]
                : [vertical, horizontal, opposite(vertical), opposite(horizontal)]
            // Focus geometry is a preference, not a route. A tall, staggered grid can
            // require moving back onto the card above a More control before moving left.
            // Remember tried directions by focus identity. A press that scrolls the same
            // focused element still makes progress and remains eligible below. The target
            // itself can leave the lazy snapshot while a route moves back through a card.
            if let entered = arrival[key] {
                let back = opposite(entered)
                directions.removeAll { $0 == back }; directions.append(back)
            }
            let direction = directions.first { !(tried[key] ?? []).contains($0.rawValue) }
                ?? arrival[key].map(opposite) ?? .up
            tried[key, default: []].insert(direction.rawValue)
            print("FOCUS MOVE \(move) direction=\(direction) tried=\(tried[key] ?? []) currentID=\(current.identifier) currentLabel=\(current.label) currentFrame=\(a) targetID=\(targetIdentifier) targetLabel=\(targetLabel) targetFrame=\(b) published=\(published)")
            let queried = ProcessInfo.processInfo.systemUptime
            XCUIRemote.shared.press(direction)
            let pressed = ProcessInfo.processInfo.systemUptime
            let afterFocus = focusedSnapshots()
            if let after = afterFocus.cell ?? afterFocus.button {
                let next = identity(after)
                let afterFrame = after.frame
                if next != key, arrival[next] == nil { arrival[next] = direction }
                if next == key, afterFrame != a { tried[key]?.remove(direction.rawValue) }
                print("FOCUS AFTER \(move) changed=\(next != key) id=\(after.identifier) label=\(after.label) frame=\(afterFrame)")
            } else { print("FOCUS AFTER \(move) none=true") }
            let finished = ProcessInfo.processInfo.systemUptime
            print("FOCUS TIMING \(move) before=\(queried - started) press=\(pressed - queried) after=\(finished - pressed) total=\(finished - started)")
        }
        let hierarchy = XCTAttachment(string: app.debugDescription)
        hierarchy.name = "tv-focus-timeout-hierarchy"; hierarchy.lifetime = .keepAlways; add(hierarchy)
        let screen = XCTAttachment(screenshot: app.screenshot())
        screen.name = "tv-focus-timeout-screen"; screen.lifetime = .keepAlways; add(screen)
        XCTFail("Remote could not focus \(targetIdentifier), \(targetLabel), last frame \(lastTargetFrame)")
    }
#endif
    @MainActor private func revealAlbumActions() {
        let action = app.descendants(matching: .any).matching(NSPredicate(format: "identifier BEGINSWITH %@", "dulcet.membership.album.")).firstMatch
#if os(macOS)
        // AppKit can retain a closed menu item's node. Existence alone is not visibility.
        if action.exists && action.isHittable { return }
#else
        if action.exists { return }
#endif
        let more = app.descendants(matching: .any)["dulcet.album.more"].firstMatch
        XCTAssertTrue(more.waitForExistence(timeout: 15), "The album must offer More")
        activate(more)
        XCTAssertTrue(action.waitForExistence(timeout: 10), "The native menu must offer the membership action")
    }

    @MainActor private func actionText(_ element: XCUIElement) -> String {
#if os(macOS)
        // Native AppKit menu items publish their visible text as title, with an empty label.
        return element.label.isEmpty ? element.title : element.label
#else
        return element.label
#endif
    }
    @MainActor private func awaitLabel(_ element: XCUIElement, _ label: String, albumMenu: Bool = false) {
        // Reopen before resolving a menu item's identifier: selecting an item closes its menu.
        if albumMenu { revealAlbumActions() }
#if os(macOS)
        let menuTitle = label.hasPrefix("Unpin ") ? "Unpin from Library" : label.hasPrefix("Pin ") ? "Pin to Library" : label
        let expected = NSPredicate(format: "label == %@ OR title == %@ OR title == %@", label, label, menuTitle)
#else
        let expected = NSPredicate(format: "label == %@", label)
#endif
        XCTAssertEqual(XCTWaiter.wait(for: [XCTNSPredicateExpectation(predicate: expected, object: element)], timeout: 20), .completed)
    }
#if !os(tvOS)
    @MainActor private func dismissAlbumActions(_ action: XCUIElement) {
        // Reading a changed menu action reopens More. Dismiss that transient surface
        // before a track tap, otherwise the first tap only dismisses the native menu.
        dismissToolbarMenu(app.buttons["dulcet.album.more"].firstMatch, action: action)
    }
    @MainActor private func dismissToolbarMenu(_ anchor: XCUIElement, action: XCUIElement) {
#if os(macOS)
        app.typeKey(.escape, modifierFlags: [])
#else
        // A native menu temporarily hides its toolbar anchor from accessibility. Dismiss
        // only while the action is visible; the outside tap is consumed by the menu.
        guard action.exists && action.isHittable else { return }
        app.coordinate(withNormalizedOffset: CGVector(dx: 0.05, dy: 0.12)).tap()
#endif
        let closed = XCTNSPredicateExpectation(predicate: NSPredicate { _, _ in
            !action.exists || !action.isHittable
        }, object: nil)
        XCTAssertEqual(XCTWaiter.wait(for: [closed], timeout: 5), .completed,
                       "The native action menu must close before page interaction")
    }
#endif
    @MainActor private func capture(_ name: String) throws {
#if os(macOS)
        let window = app.windows.firstMatch
        XCTAssertTrue(window.waitForExistence(timeout: 10))
        let image = window.screenshot().image
        let bitmap = try XCTUnwrap(image.tiffRepresentation.flatMap(NSBitmapImageRep.init(data:)))
        let data = try XCTUnwrap(bitmap.representation(using: .jpeg, properties: [.compressionFactor: 0.65]))
#else
        let data = try XCTUnwrap(app.screenshot().image.jpegData(compressionQuality: 0.65))
#endif
        let attachment = XCTAttachment(data: data, uniformTypeIdentifier: "public.jpeg")
        attachment.name = name; attachment.lifetime = .keepAlways; add(attachment)
        if let directory = ProcessInfo.processInfo.environment["DULCET_NATIVE_BASELINE_OUT"] {
            try data.write(to: URL(fileURLWithPath: directory).appendingPathComponent(name + ".jpg"))
        }
    }
    private func fixtureAlbum() throws -> (id: String, title: String) {
        let response = try rest("getAlbumList2", params: ["type": "newest", "size": "500"])
        let list = try XCTUnwrap(response["albumList2"] as? [String: Any])
        let albums = try XCTUnwrap(list["album"] as? [[String: Any]])
        let album = try XCTUnwrap(albums.first(where: { ($0["name"] as? String) == "Threshold Boundary" }))
        return (try XCTUnwrap(album["id"] as? String), try XCTUnwrap(album["name"] as? String))
    }
    private func removeAlbum(_ id: String) throws { _ = try rest("removeFromDulcetLibrary", params: ["albumId": id]) }
    private func serverContains(_ id: String) throws -> Bool {
        let response = try rest("getDulcetLibrary")
        let library = try XCTUnwrap(response["dulcetLibrary"] as? [String: Any])
        return (library["album"] as? [[String: Any]] ?? []).contains { ($0["id"] as? String) == id }
    }
    private func awaitServerMembership(_ id: String, added: Bool) throws {
        let deadline = Date().addingTimeInterval(30)
        repeat {
            if try serverContains(id) == added { return }
            let pulse = XCTestExpectation(description: "Membership poll interval")
            DispatchQueue.global().asyncAfter(deadline: .now() + 0.2) { pulse.fulfill() }
            _ = XCTWaiter.wait(for: [pulse], timeout: 1)
        } while Date() < deadline
        XCTFail("Server did not settle membership \(id) added=\(added)")
    }
    private func rest(_ method: String, params: [String: String] = [:]) throws -> [String: Any] {
        var url = URLComponents(string: "http://127.0.0.1:4783/rest/\(method).view")!
        let query = params.merging(["u": user, "p": password, "v": "1.16.1", "c": "dulcet-ui-proof", "f": "json"]) { _, b in b }
        url.queryItems = query.map { URLQueryItem(name: $0.key, value: $0.value) }
        let data = try fetch(url.url!)
        let body = try XCTUnwrap(JSONSerialization.jsonObject(with: data) as? [String: Any])
        let response = try XCTUnwrap(body["subsonic-response"] as? [String: Any])
        XCTAssertEqual(response["status"] as? String, "ok")
        return response
    }
    private func fetch(_ url: URL) throws -> Data {
        let request = URLRequest(url: url, cachePolicy: .reloadIgnoringLocalCacheData, timeoutInterval: 15)
        let completed = XCTestExpectation(description: "Loopback fixture response")
        let result = DulcetFixtureHTTPResult()
        let task = URLSession.shared.dataTask(with: request) { data, response, error in
            if let error {
                result.store(.failure(error))
            } else if let data, (response as? HTTPURLResponse)?.statusCode == 200 {
                result.store(.success(data))
            } else {
                result.store(.failure(URLError(.badServerResponse)))
            }
            completed.fulfill()
        }
        task.resume()
        defer { task.cancel() }
        XCTAssertEqual(XCTWaiter.wait(for: [completed], timeout: 16), .completed)
        return try XCTUnwrap(result.load()).get()
    }
    private func setOffline(_ offline: Bool) throws {
        let response = try fetch(URL(string: base + "/fixture/network?offline=\(offline ? "1" : "0")")!)
        XCTAssertEqual(String(data: response, encoding: .utf8), offline ? "offline" : "online",
                       "The disposable route must acknowledge the requested network condition")
    }
}

/// URLSession replies off the test actor; the waiter pumps the test run loop.
private final class DulcetFixtureHTTPResult: @unchecked Sendable {
    private let lock = NSLock()
    private var value: Result<Data, Error>?
    func store(_ result: Result<Data, Error>) {
        lock.lock(); defer { lock.unlock() }
        value = result
    }
    func load() -> Result<Data, Error>? {
        lock.lock(); defer { lock.unlock() }
        return value
    }
}
