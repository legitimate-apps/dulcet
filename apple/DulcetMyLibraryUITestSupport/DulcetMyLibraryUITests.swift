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
        let more = app.buttons["dulcet.library.item.more.\(album.id)"].firstMatch
        XCTAssertTrue(more.waitForExistence(timeout: 10))
        focus(more)
        XCTAssertTrue(more.hasFocus, "The grid's More control must take independent remote focus")
        try capture("TV-Full-Grid-More")
        XCUIRemote.shared.press(.select)
        XCTAssertTrue(add.waitForExistence(timeout: 10), "Select on More must open the full-grid tile's actions: " + app.debugDescription)
        activate(add)
        try awaitServerMembership(album.id, added: true)
        // The same tile's primary Select action must still open its detail after dismissal.
        focus(tile)
        XCUIRemote.shared.press(.select)
        XCTAssertTrue(app.staticTexts["dulcet.album.title"].firstMatch.waitForExistence(timeout: 20))
        print("TV FULL GRID MENU PROOF remoteSelectMore=true membershipServer=true primarySelect=true")
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
        XCTAssertTrue(app.buttons["dulcet.myLibrary.browse"].waitForExistence(timeout: 30))
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
        let add = app.buttons["dulcet.membership.album.\(album.id)"].firstMatch
        XCTAssertTrue(add.waitForExistence(timeout: 30))
        XCTAssertEqual(add.label, "Add to Library")
        activate(add)
        awaitLabel(add, "Remove from Library")
#if !os(tvOS)
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
        let remove = app.buttons["dulcet.membership.album.\(album.id)"].firstMatch
        XCTAssertTrue(remove.waitForExistence(timeout: 20))
        activate(remove)
        try select("library")
        XCTAssertTrue(app.buttons["dulcet.myLibrary.browse"].waitForExistence(timeout: 20))
        // Restore online to flush the durable remove, then cut only this fixture's HTTP route.
        app.terminate(); try launch()
        try awaitServerMembership(album.id, added: false)
        try select("browse"); try openAlbum(album.title)
        try setOffline(true)
        let offlineAdd = app.buttons["dulcet.membership.album.\(album.id)"].firstMatch
        activate(offlineAdd)
        awaitLabel(offlineAdd, "Remove from Library")
        try select("library")
        revealPhoneAlbumTile(albumTile(album.title))
        XCTAssertTrue(albumTile(album.title).waitForExistence(timeout: 20))
        // A direct read bypasses the offline proxy: it proves the write has not reached the server.
        let pendingBeforeRelaunch = try serverContains(album.id)
        XCTAssertFalse(pendingBeforeRelaunch)
        app.terminate()
        try launch(connect: false)
        try select("library")
#if os(iOS)
        if UIDevice.current.userInterfaceIdiom == .phone {
            // Offline status and the category rows put Recently Added below the compact
            // viewport. Prove the regression condition before driving the real scroll.
            XCTAssertTrue(app.buttons["dulcet.myLibrary.section.recent"].waitForExistence(timeout: 20))
            XCTAssertFalse(albumTile(album.title).exists,
                           "The compact offline root must begin with its lazy album grid below the viewport")
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
        let pin = app.buttons["dulcet.pin.album.\(album.id)"].firstMatch
        XCTAssertTrue(pin.waitForExistence(timeout: 20))
        if !pin.label.hasPrefix("Unpin") { activate(pin) }
        awaitLabel(pin, "Unpin \(album.title)")
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
        let unpin = app.buttons["dulcet.pin.album.\(album.id)"].firstMatch
        XCTAssertTrue(unpin.waitForExistence(timeout: 20))
        if unpin.label.hasPrefix("Unpin") { activate(unpin) }
        awaitLabel(unpin, "Pin \(album.title)")
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
        let sort = app.buttons["dulcet.library.albums.sort"].firstMatch
        XCTAssertTrue(sort.waitForExistence(timeout: 20))
        activate(sort)
        let release = app.buttons["Release Date"].firstMatch
        XCTAssertTrue(release.waitForExistence(timeout: 10))
        activate(release)
        XCTAssertTrue(app.staticTexts["Release Date uses the server’s year ordering. Albums without a year are available in Title order."].firstMatch.waitForExistence(timeout: 10))
        XCTAssertTrue(app.buttons["dulcet.library.album"].firstMatch.waitForExistence(timeout: 20))
        try capture("Library-Depth-Release-Sort")
        app.terminate()
        try launch(connect: false)
        try select("browse")
        try reveal(albums); activate(albums)
        XCTAssertTrue(sort.waitForExistence(timeout: 20))
        XCTAssertTrue(sort.label.contains("Release Date"), "Category selection survives a saved-account relaunch: \(sort.label)")
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
        try select("library")
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
            app.scrollViews.firstMatch.swipeUp()
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
        let field = app.textFields["dulcet.search.field"].firstMatch
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
        let catalog = app.buttons["dulcet.library.album"].firstMatch
        XCTAssertTrue(catalog.waitForExistence(timeout: 30), "Positive catalog control must load before asserting absence")
        XCTAssertFalse(destination("browse").exists)
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
            XCTAssertTrue(app.buttons["dulcet.myLibrary.browse"].waitForExistence(timeout: 30))
            try capture("Library-\(variant)")
            try select("browse")
            let catalogAlbum = app.buttons["dulcet.library.album"].firstMatch
            revealPhoneAlbumTile(catalogAlbum)
            XCTAssertTrue(catalogAlbum.waitForExistence(timeout: 30))
            try capture("Browse-\(variant)")
            try openAlbum(album.title)
            let add = app.buttons["dulcet.membership.album.\(album.id)"].firstMatch
            XCTAssertTrue(add.waitForExistence(timeout: 30))
            XCTAssertEqual(add.label, "Add to Library")
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
        // At accessibility text sizes, Browse's introduction and destination rows take
        // several screens before its lazy album grid. Reach the catalog first, as the
        // light/dark/largest-text tour above does, then look for the fixture album.
        let catalogAlbum = app.buttons["dulcet.library.album"].firstMatch
        XCTAssertFalse(catalogAlbum.exists && catalogAlbum.isHittable,
            "The cold largest-text Browse must exercise scrolling to its catalog")
        let catalogScrolls = revealPhoneAlbumTile(catalogAlbum)
        XCTAssertGreaterThan(catalogScrolls, 0, "The catalog must be reached by a real touch scroll")
        XCTAssertTrue(catalogAlbum.exists && catalogAlbum.isHittable,
            "The catalog must be visible before searching its albums: " + app.debugDescription)
        print("MY LIBRARY LARGEST TEXT catalog-scrolls=\(catalogScrolls) catalog-visible=true")
        try openAlbum(album.title)
        let add = app.buttons["dulcet.membership.album.\(album.id)"].firstMatch
        XCTAssertTrue(add.waitForExistence(timeout: 30))
        XCTAssertEqual(add.label, "Add to Library")
        try captureAlbumAdd("Album-Add-largest-text", button: add)
#else
        throw XCTSkip("Compact iPhone capture correction")
#endif
    }

    @MainActor private func captureAlbumAdd(_ name: String, button: XCUIElement) throws {
#if os(iOS)
        for _ in 0..<5 {
            let window = app.frame
            let target = button.frame
            let top = window.minY + 120
            let bottom = window.maxY - 100
            if button.isHittable && target.minY >= top && target.maxY < bottom { break }
            // A full swipe can move this header from under the tab bar to above the viewport
            // at the largest text size. Make a short drag toward the target, and hold its end
            // to avoid inertial overshoot. If it is already above the viewport, scroll back.
            let downward = target.minY < top
            let start = app.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: downward ? 0.45 : 0.65))
            let end = app.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: downward ? 0.65 : 0.45))
            print("MY LIBRARY CAPTURE \(name) add=\(target) viewport=\(top)...\(bottom) downward=\(downward)")
            start.press(forDuration: 0.01, thenDragTo: end, withVelocity: .slow, thenHoldForDuration: 0.2)
        }
        XCTAssertTrue(button.isHittable)
        XCTAssertGreaterThanOrEqual(button.frame.minY, app.frame.minY + 120, "The Add action must be below the navigation bar in the capture")
        XCTAssertLessThan(button.frame.maxY, app.frame.maxY - 100, "The Add action must be above the tab bar in the capture")
#endif
        try capture(name)
    }

    @MainActor
    private func launch(connect: Bool = true, server: String? = nil, username: String? = nil, password: String? = nil, variant: String = "light") throws {
        var launchArguments = ["-dulcet-debug-ui-markers", variant == "dark" ? "-dulcet-debug-dark-mode" : "-dulcet-debug-light-mode"]
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
        if UIDevice.current.userInterfaceIdiom == .pad {
            // iPad sidebar choices are explicit buttons. Resolve the indexed action itself,
            // including its selected trait, rather than a label or a former List cell.
            return app.buttons.matching(identifier: "dulcet.sidebar.\(section)").element(boundBy: 0)
        }
        let title = section == "library" ? "Library" : section == "browse" ? "Browse" : "Search"
        return app.tabBars.buttons[title].firstMatch
#endif
    }
    @MainActor private func select(_ section: String) throws {
#if os(iOS)
        if UIDevice.current.userInterfaceIdiom == .pad {
            // Each launch installs a new application process. Scope this navigation to
            // that process; destination readiness and selected-state checks stay explicit.
            withoutIdleWaits { [self] in selectDestination(section) }
            return
        }
#endif
        selectDestination(section)
    }
    @MainActor private func selectDestination(_ section: String) {
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
        activate(control)
#if os(iOS)
        if UIDevice.current.userInterfaceIdiom == .phone {
            // A correctly targeted native tab tap can finish without selecting its tab.
            // Observe acknowledgement and retry ordinary input within the same budget.
            let deadline = Date().addingTimeInterval(10)
            var retries = 0
            repeat {
                let remaining = max(0, deadline.timeIntervalSince(Date()))
                let selected = XCTNSPredicateExpectation(predicate: NSPredicate { _, _ in control.isSelected }, object: nil)
                if XCTWaiter.wait(for: [selected], timeout: min(3, remaining)) == .completed { return }
                guard Date() < deadline else { break }
                if retries < 2, control.isHittable, Date() < deadline {
                    retries += 1
                    print("MY LIBRARY IPHONE unacknowledged-tab-tap target=\(section) retry=\(retries)")
                    activate(control)
                    if control.isSelected { return }
                }
            } while Date() < deadline
            XCTAssertTrue(control.isSelected, "The tab tap must select \(section): \(app.debugDescription)")
        } else {
            // A synthesized iPad label tap can finish without changing the sidebar selection.
            // Retained failures show Listen Now still selected after a tap inside Library's
            // correct bounds, at both default and accessibility text sizes. Observe selection
            // before reading destination content; make at most one second real row tap.
            let firstSelection = XCTNSPredicateExpectation(predicate: NSPredicate { _, _ in control.isSelected }, object: nil)
            if XCTWaiter.wait(for: [firstSelection], timeout: 3) != .completed {
                let row = app.buttons.matching(identifier: "dulcet.sidebar.\(section)").element(boundBy: 0)
                guard row.waitForExistence(timeout: 3) else {
                    XCTFail("Missing native sidebar row for \(section): \(app.debugDescription)")
                    return
                }
                if !row.isSelected {
                    print("MY LIBRARY IPAD unacknowledged-sidebar-tap target=\(section) retry=button")
                    row.tap()
                }
            }
            let selected = XCTNSPredicateExpectation(predicate: NSPredicate { _, _ in control.isSelected }, object: nil)
            XCTAssertEqual(XCTWaiter.wait(for: [selected], timeout: 10), .completed,
                           "The sidebar tap must select \(section): \(app.debugDescription)")
        }
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
            print("MY LIBRARY IPAD sidebar interaction options unavailable; using ordinary waits")
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
        // Library's destination rows and Browse's largest-text introduction precede a lazy
        // album grid. Reach that grid with touch scrolling before asserting its publication.
        for scrolls in 0..<8 {
            if tile.exists && tile.isHittable { return scrolls }
            app.scrollViews.firstMatch.swipeUp()
        }
        return 8
#else
        return 0
#endif
    }
    @MainActor private func openAlbumList() throws {
#if os(tvOS)
        // Browse's section bar keeps TV navigation visible and is reachable by the remote.
        let albumListLink = "dulcet.reader.section.albums"
#elseif os(iOS)
        // Compact Browse has section links and a vertical Recently Added grid; it has no
        // Recently Added See All. Regular-width Browse uses horizontal shelves instead.
        let albumListLink = UIDevice.current.userInterfaceIdiom == .phone
            ? "dulcet.reader.section.albums" : "dulcet.reader.home.recentlyAdded.all"
#else
        let albumListLink = "dulcet.reader.home.recentlyAdded.all"
#endif
        let all = app.buttons[albumListLink].firstMatch
        // Wait for the appropriate catalog link to publish before leaving Browse.
        XCTAssertTrue(all.waitForExistence(timeout: 30), "Browse must publish its album-list link: \(albumListLink). \(app.debugDescription)")
        activate(all)
    }
    @MainActor private func openAlbum(_ title: String) throws {
        // Horizontal Browse shelves instantiate only visible cards. Enter the full list on
        // TV as well, so waiting for a named album does not depend on its shelf position.
        try openAlbumList()
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
    private func isFocused(_ target: XCUIElement) -> Bool {
        if target.hasFocus { return true }
        // Native confirmation dialogs expose an identified action and a focused UIKit
        // alias at the same label/frame. The alias carries focus, the identified one does not.
        let button = app.buttons.matching(NSPredicate(format: "hasFocus == true")).firstMatch
        if button.exists, button.label == target.label, button.frame == target.frame { return true }
        // tvOS context-menu actions sit inside a focused cell rather than holding focus themselves.
        let cell = app.cells.matching(NSPredicate(format: "hasFocus == true")).firstMatch
        return cell.exists && cell.frame.contains(CGPoint(x: target.frame.midX, y: target.frame.midY))
    }
    private func focus(_ target: XCUIElement) {
        var previousFrame: CGRect?
        for _ in 0..<40 {
            if isFocused(target) { return }
            let focusedCell = app.cells.matching(NSPredicate(format: "hasFocus == true")).firstMatch
            let current = focusedCell.exists ? focusedCell : app.buttons.matching(NSPredicate(format: "hasFocus == true")).firstMatch
            guard current.exists else { XCUIRemote.shared.press(.down); continue }
            let a = current.frame, b = target.frame
            // Album Add is centred beside the vertical Play/Shuffle stack. Its centre is
            // below Play and above Shuffle, so centre-only steering oscillates Up/Down.
            // Overlapping vertical spans identify the reachable horizontal neighbour.
            if min(a.maxY, b.maxY) > max(a.minY, b.minY),
               b.minX >= a.maxX || b.maxX <= a.minX {
                XCUIRemote.shared.press(b.midX > a.midX ? .right : .left)
                previousFrame = a
                continue
            }
            if previousFrame == a && abs(a.midX - b.midX) > 20 {
                // A narrow first-column tile can have no Down neighbor from a wider section bar.
                XCUIRemote.shared.press(b.midX > a.midX ? .right : .left)
                previousFrame = nil
                continue
            }
            previousFrame = a
            if abs(a.midY - b.midY) > max(20, min(a.height, b.height) / 2) {
                XCUIRemote.shared.press(b.midY > a.midY ? .down : .up)
            } else {
                XCUIRemote.shared.press(b.midX > a.midX ? .right : .left)
            }
        }
        XCTFail("Remote could not focus " + target.debugDescription)
    }
#endif
    @MainActor private func awaitLabel(_ element: XCUIElement, _ label: String) {
        let expected = NSPredicate(format: "label == %@", label)
        XCTAssertEqual(XCTWaiter.wait(for: [XCTNSPredicateExpectation(predicate: expected, object: element)], timeout: 20), .completed)
    }
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
