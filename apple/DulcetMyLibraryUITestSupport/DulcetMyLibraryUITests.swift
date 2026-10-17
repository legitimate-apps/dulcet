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
#if os(macOS)
    private let accountFixtureNamespace = UUID().uuidString
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
        let tile = albumTile(album.title)
        XCTAssertTrue(tile.waitForExistence(timeout: 30))
        focus(tile)
        XCUIRemote.shared.press(.select, forDuration: 1.5)
        let contextAdd = app.descendants(matching: .any)["dulcet.membership.album.\(album.id)"].firstMatch
        XCTAssertTrue(contextAdd.waitForExistence(timeout: 10), "Remote press and hold offers membership")
        activate(contextAdd)
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
        try select("library")
        XCTAssertTrue(albumTile(album.title).waitForExistence(timeout: 20))
        try awaitServerMembership(album.id, added: true)
        app.terminate()
        try launch(connect: false)
        try select("library")
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
        // The Library destination rows and offline notice precede its lazy album grid.
        // A cold launch starts at the top: reveal the grid before testing durable membership.
        revealPhoneAlbumTile(albumTile(album.title))
        XCTAssertTrue(albumTile(album.title).waitForExistence(timeout: 20), "Offline relaunch paints the durable pending add before reconnect")
        let pendingAfterRelaunch = try serverContains(album.id)
        XCTAssertFalse(pendingAfterRelaunch)
        app.terminate()
        try setOffline(false)
        try launch()
        try select("library")
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
        awaitStaticText("dulcet.library.pinned.title", content: "Pinned")
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
        XCTAssertTrue(app.descendants(matching: .any)["dulcet.library.songs.sort"].firstMatch.waitForExistence(timeout: 20))
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
        awaitStaticText("dulcet.search.resultCount", content: "1 result")
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
        var launchArguments = [variant == "dark" ? "-dulcet-debug-dark-mode" : "-dulcet-debug-light-mode"]
        if variant == "largest-text" { launchArguments += ["-dulcet-debug-largest-text"] }
        if connect {
            launchArguments += ["-dulcet-debug-connect-account", "-dulcet-debug-account-server-url", server ?? base,
                "-dulcet-debug-account-username", username ?? user, "-dulcet-debug-account-password", password ?? self.password]
        }
#if os(macOS)
        launchArguments += ["-dulcet-debug-account-fixture", accountFixtureNamespace]
#endif
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
        XCTAssertTrue(window.waitForExistence(timeout: 10))
        let screenWidth = try XCTUnwrap(NSScreen.main?.frame.width)
        let visible = XCTNSPredicateExpectation(predicate: NSPredicate { _, _ in
            window.frame.minX >= 0 && window.frame.maxX <= screenWidth
        }, object: nil)
        XCTAssertEqual(XCTWaiter.wait(for: [visible], timeout: 10), .completed,
                       "The Mac window must remain on screen after every launch: \(window.frame)")
#elseif os(iOS)
        if connect {
            let connected = server == navidrome
                ? app.buttons["dulcet.library.album"].firstMatch.waitForExistence(timeout: 30)
                : awaitDestination("browse")
            if !connected {
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
        return app.staticTexts["dulcet.sidebar.\(section)"].firstMatch
#elseif os(tvOS)
        return app.buttons["dulcet.tab.\(section)"].firstMatch
#else
        if UIDevice.current.userInterfaceIdiom == .pad {
            // Construct the identified query even before the account publishes its sidebar.
            // Choosing a label-based Cell fallback before it exists leaves the wait on cells
            // with no label; SwiftUI exposes the destination label on their StaticText child.
            let identifier = "dulcet.sidebar.\(section)"
            return app.staticTexts.allElementsBoundByIndex.first(where: { $0.identifier == identifier })
                ?? app.staticTexts[identifier].firstMatch
        }
        let title = section == "library" ? "Library" : section == "browse" ? "Browse" : "Search"
        return app.tabBars.buttons[title].firstMatch
#endif
    }
    @MainActor private func awaitDestination(_ section: String) -> Bool {
        // Re-resolve after publication: the initial iPad name-index query can miss a bound
        // sidebar Text even while the same snapshot contains the visible Browse label.
        let ready = XCTNSPredicateExpectation(predicate: NSPredicate { _, _ in
            self.destination(section).exists
        }, object: nil)
        return XCTWaiter.wait(for: [ready], timeout: 30) == .completed
    }
    @MainActor private func select(_ section: String) throws {
        XCTAssertTrue(awaitDestination(section), "Missing destination \(section)")
        let control = destination(section)
#if os(tvOS)
        // Menu returns to the section bar from roots; from a pushed page it pops first.
        let remote = XCUIRemote.shared
        for _ in 0..<6 {
            if app.buttons.matching(NSPredicate(format: "identifier BEGINSWITH 'dulcet.tab.' AND hasFocus == true")).firstMatch.exists { break }
            remote.press(.menu)
        }
        let order = ["library", "browse", "search", "nowPlaying", "settings"]
        for _ in 0..<12 {
            if control.hasFocus { remote.press(.select); return }
            if let current = order.firstIndex(where: { app.buttons["dulcet.tab.\($0)"].firstMatch.hasFocus }), let target = order.firstIndex(of: section) {
                remote.press(target > current ? .right : .left)
            } else { remote.press(.up) }
        }
        XCTFail("Remote could not reach \(section)")
#else
        activate(control)
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
    @MainActor private func albumTile(_ title: String) -> XCUIElement {
        app.buttons.matching(NSPredicate(format: "identifier == 'dulcet.library.album' AND label BEGINSWITH %@", title)).firstMatch
    }
    @MainActor private func revealPhoneAlbumTile(_ tile: XCUIElement) {
#if os(iOS)
        guard UIDevice.current.userInterfaceIdiom == .phone else { return }
        // Library's destination rows and Browse's largest-text introduction precede a lazy
        // album grid. Reach that grid with touch scrolling before asserting its publication.
        for _ in 0..<20 {
            if tile.exists && tile.isHittable { return }
            app.scrollViews.firstMatch.swipeUp()
        }
#endif
    }
    @MainActor private func openAlbum(_ title: String) throws {
#if !os(tvOS)
        let all = app.buttons["dulcet.reader.home.recentlyAdded.all"]
        if all.exists { activate(all) }
#endif
        let tile = albumTile(title)
#if os(macOS)
        // The sidebar is also a scroll view. Wait for catalog publication and scroll only the
        // album page; scrolling firstMatch can hide Library before the catalog finishes loading.
        let albumPage = app.scrollViews.containing(.button, identifier: "dulcet.library.album").firstMatch
        XCTAssertTrue(albumPage.waitForExistence(timeout: 30), "The album page must load before scrolling")
#endif
#if !os(tvOS)
#if os(iOS)
        // At the largest accessibility size each album is a tall single-column card.
        // Six swipes reached only the third fixture album in the recorded failure.
        let maximumScrolls = UIDevice.current.userInterfaceIdiom == .phone ? 20 : 6
#else
        let maximumScrolls = 6
#endif
        for _ in 0..<maximumScrolls {
            if albumTileCanBeActivated(tile) { break }
#if os(macOS)
            albumPage.scroll(byDeltaX: 0, deltaY: -450)
#else
            app.scrollViews.firstMatch.swipeUp()
#endif
        }
        XCTAssertTrue(tile.waitForExistence(timeout: 30))
        XCTAssertTrue(albumTileCanBeActivated(tile), "The album centre must be visible before activation: " + tile.debugDescription)
#else
        XCTAssertTrue(tile.waitForExistence(timeout: 30))
#endif
        activate(tile)
#if os(iOS)
        awaitStaticText("dulcet.album.title", content: title)
#endif
    }
    @MainActor private func albumTileCanBeActivated(_ tile: XCUIElement) -> Bool {
        guard tile.exists && tile.isHittable else { return false }
#if os(iOS)
        // At large text, a sliver of a tall card can be hittable with its tap centre below
        // the iPad window. Scroll until that centre clears the navigation and bottom edges.
        let frame = tile.frame
        return app.windows.firstMatch.frame.insetBy(dx: 16, dy: 100)
            .contains(CGPoint(x: frame.midX, y: frame.midY))
#else
        return true
#endif
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
    private func isFocused(_ target: XCUIElement) -> Bool {
        if target.hasFocus { return true }
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
    /// macOS exposes some SwiftUI Text in AXValue rather than AXLabel. Resolve the exact
    /// identifier from bound elements: the macOS firstMatch name-index query can miss a Text
    /// that the same failure's accessibility hierarchy and recording show on screen.
    @MainActor private func staticTextContent(_ identifier: String) -> String? {
#if os(macOS)
        guard let element = app.staticTexts.allElementsBoundByIndex.first(where: { $0.identifier == identifier }) else { return nil }
#else
        let element = app.staticTexts[identifier].firstMatch
        guard element.exists else { return nil }
#endif
        return element.label.isEmpty ? element.value as? String : element.label
    }

    @MainActor private func awaitStaticText(_ identifier: String, content: String) {
        let expected = XCTNSPredicateExpectation(predicate: NSPredicate { _, _ in
            self.staticTextContent(identifier) == content
        }, object: nil)
        XCTAssertEqual(XCTWaiter.wait(for: [expected], timeout: 20), .completed,
                       "Expected visible text \(content) at \(identifier); found \(staticTextContent(identifier) ?? "<absent>")")
    }

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
        _ = try fetch(URL(string: base + "/fixture/network?offline=\(offline ? "1" : "0")")!)
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
