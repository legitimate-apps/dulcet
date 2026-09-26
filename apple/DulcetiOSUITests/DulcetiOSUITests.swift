import CryptoKit
import UIKit
import XCTest

final class DulcetiOSUITests: XCTestCase {
    /// A missing launch-screen declaration opts into the legacy 320-by-480 canvas.
    /// Compare the actual window with the display, independently of device resolution.
    @MainActor
    func testIPhoneWindowUsesFullDisplay() {
        XCUIDevice.shared.orientation = .portrait
        let app = XCUIApplication()
        app.launchArguments.append("-dulcet-account-connect-layout-fixture")
        app.launch()
        let window = app.windows.firstMatch
        XCTAssertTrue(window.waitForExistence(timeout: 10))
        let display = XCUIScreen.main.screenshot().image.size
        let frame = window.frame
        print("DULCET DISPLAY GEOMETRY window=\(frame) displayImageSize=\(display)")
        print(app.debugDescription)
        XCTAssertGreaterThan(frame.width, 0)
        XCTAssertLessThan(frame.width, 700, "Run this full-display proof on an iPhone")
        XCTAssertEqual(frame.minX, 0, accuracy: 1)
        XCTAssertEqual(frame.minY, 0, accuracy: 1)
        XCTAssertEqual(
            frame.height / frame.width, display.height / display.width, accuracy: 0.01,
            "The app window must fill the portrait display without legacy letterboxing"
        )

        // A full-size empty window is not evidence that the account interface rendered.
        // Scope both semantic queries to this window, then require visible, contained frames.
        let content: [(String, XCUIElement)] = [
            ("dulcet.account-connect.title",
             window.staticTexts["dulcet.account-connect.title"].firstMatch),
            ("dulcet.account-connect.server-address",
             window.textFields["dulcet.account-connect.server-address"].firstMatch),
        ]
        for (identifier, element) in content {
            guard element.waitForExistence(timeout: 5) else {
                XCTFail("Full-display content missing from measured window: \(identifier)")
                continue
            }
            let contentFrame = element.frame
            print("DULCET DISPLAY CONTENT id=\(identifier) frame=\(contentFrame) window=\(frame) hittable=\(element.isHittable)")
            XCTAssertGreaterThan(contentFrame.width, 0, "\(identifier) must have visible width")
            XCTAssertGreaterThan(contentFrame.height, 0, "\(identifier) must have visible height")
            XCTAssertTrue(element.isHittable, "\(identifier) must be visible and reachable")
            XCTAssertTrue(
                frame.contains(contentFrame),
                "\(identifier) frame \(contentFrame) must lie inside measured window \(frame)"
            )
        }
    }

    /// The compact shell on the deterministic layout fixture -- no server, so nothing here can
    /// fail for a reason that belongs to one. It proves, on an iPhone-width window:
    ///
    /// 1. The album page is not blank: its artwork, title and Play/Shuffle lie inside the window
    ///    with real width, the two actions are equal-width single-line buttons, and the first
    ///    track row is on screen without scrolling. The audit that motivated this measured the
    ///    title at width 0 and the first row about three screens down.
    /// 2. Playing a track leaves the album page showing and brings up the now-playing bar,
    ///    whose play/pause reaches the store (its label flips), and which stays on screen on
    ///    another tab.
    /// 3. The bar opens the full player as a sheet showing the track that was played, and the
    ///    sheet closes back to the tab with the bar still there.
    @MainActor
    func testCompactShellKeepsTheAlbumAndOpensNowPlayingFromTheBar() {
        XCUIDevice.shared.orientation = .portrait
        let app = XCUIApplication()
        app.launchArguments.append("-dulcet-account-connect-layout-fixture")
        app.launch()

        let window = app.windows.firstMatch
        XCTAssertTrue(window.waitForExistence(timeout: 10), "The app window must exist")
        // Checks the experiment before the product: a regular-width window has no tab bar.
        XCTAssertLessThan(
            window.frame.width,
            700,
            "This proof requires a compact-width iPhone window; an iPad is invalid evidence"
        )

        let libraryTab = app.tabBars.buttons["Library"].firstMatch
        guard libraryTab.waitForExistence(timeout: 10) else {
            XCTFail("A compact window must present a tab bar with Library: " + app.debugDescription)
            return
        }
        XCTAssertFalse(
            app.staticTexts["dulcet.sidebar.library"].firstMatch.exists,
            "A compact window must not fall back to the sidebar list"
        )
        // The fixture opens on the Connection tab. A compact window must not raise the keyboard
        // there on its own: it would cover the tab bar this proof (and a person) needs.
        XCTAssertFalse(app.keyboards.firstMatch.exists,
                       "The Connection tab must not raise the keyboard over the tab bar on arrival")
        libraryTab.tap()
        XCTAssertTrue(libraryTab.isSelected, "The Library tab must be selected after tapping it")

        let album = app.buttons.matching(
            NSPredicate(format: "label BEGINSWITH %@", "Double Lines")
        ).firstMatch
        guard album.waitForExistence(timeout: 10),
              scrollIntoView(album, in: app, probingBlockingSystemAlerts: false) else {
            XCTFail("The fixture's Double Lines album must be reachable in the grid: "
                + app.debugDescription)
            return
        }
        album.tap()

        let title = app.staticTexts["dulcet.album.title"].firstMatch
        guard title.waitForExistence(timeout: 10) else {
            XCTFail("The album page must render its title: " + app.debugDescription)
            return
        }
        let play = app.buttons["dulcet.album.play"].firstMatch
        let shuffle = app.buttons["dulcet.album.shuffle"].firstMatch
        let firstTrack = app.buttons.matching(
            NSPredicate(format: "label BEGINSWITH %@", "Disc 1 Track 1")
        ).firstMatch
        XCTAssertTrue(play.waitForExistence(timeout: 5), "The album page must offer Play")
        XCTAssertTrue(shuffle.exists, "The album page must offer Shuffle")
        XCTAssertTrue(firstTrack.waitForExistence(timeout: 5), "The first track row must exist")

        let frame = window.frame
        print("DULCET COMPACT ALBUM OBSERVED window=\(frame) title=\(title.frame)"
            + " play=\(play.frame) shuffle=\(shuffle.frame) first-track=\(firstTrack.frame)")
        for (name, element) in [("title", title), ("play", play), ("shuffle", shuffle),
                                ("first track", firstTrack)] {
            XCTAssertGreaterThan(element.frame.width, 1, "\(name) must have visible width")
            XCTAssertTrue(
                frame.contains(element.frame),
                "\(name) frame \(element.frame) must lie inside the window \(frame) without scrolling"
            )
        }
        XCTAssertTrue(firstTrack.isHittable, "The first track must be on screen without scrolling")
        XCTAssertEqual(play.frame.width, shuffle.frame.width, accuracy: 1,
                       "Play and Shuffle must be equal-width buttons")
        XCTAssertEqual(play.frame.minY, shuffle.frame.minY, accuracy: 1,
                       "Play and Shuffle must sit side by side at the default text size")
        XCTAssertLessThan(play.frame.height, 70,
                          "A one-line button; a label wrapping letter by letter is far taller")

        let bar = app.buttons["dulcet.mini-player.open"].firstMatch
        XCTAssertFalse(bar.exists, "Nothing is queued yet, so there must be no now-playing bar")

        firstTrack.tap()
        guard bar.waitForExistence(timeout: 10) else {
            XCTFail("Playing a track must bring up the now-playing bar: " + app.debugDescription)
            return
        }
        XCTAssertTrue(bar.label.contains("Disc 1 Track 1"),
                      "The bar must name the track that was played; label=\(bar.label)")
        XCTAssertTrue(title.exists && title.isHittable,
                      "Playing must leave the album page showing, not navigate away from it")

        let playPause = app.buttons["dulcet.mini-player.play-pause"].firstMatch
        XCTAssertTrue(playPause.waitForExistence(timeout: 5), "The bar must offer play/pause")
        XCTAssertEqual(playPause.label, "Pause", "A playing queue offers Pause")
        playPause.tap()
        XCTAssertTrue(
            waitForLabel("Play", of: playPause, timeout: 5),
            "Pause on the bar must reach playback; label stayed \(playPause.label)"
        )
        XCTAssertTrue(app.buttons["dulcet.mini-player.next"].firstMatch.exists,
                      "The bar must offer Next")

        app.tabBars.buttons["Search"].firstMatch.tap()
        XCTAssertTrue(app.textFields["dulcet.search.field"].firstMatch.waitForExistence(timeout: 5),
                      "The Search tab must show search")
        XCTAssertTrue(bar.waitForExistence(timeout: 5) && bar.isHittable,
                      "The now-playing bar must stay on screen on another tab")

        bar.tap()
        let nowPlayingTitle = app.staticTexts["dulcet.now-playing.title"].firstMatch
        guard nowPlayingTitle.waitForExistence(timeout: 10) else {
            XCTFail("The bar must open the full player: " + app.debugDescription)
            return
        }
        XCTAssertEqual(nowPlayingTitle.label, "Disc 1 Track 1",
                       "The full player must show the track that is playing")
        let close = app.buttons["dulcet.now-playing.close"].firstMatch
        XCTAssertTrue(close.waitForExistence(timeout: 5), "The full player must offer a close control")
        close.tap()
        XCTAssertTrue(
            nowPlayingTitle.waitForNonExistence(timeout: 10),
            "Closing must dismiss the full player"
        )
        XCTAssertTrue(bar.waitForExistence(timeout: 5), "The bar must remain after the player closes")

        // 4. Each tab keeps its own navigation stack. The album opened under Library is still
        //    open when Library is chosen again from Search, and choosing Library while it is the
        //    selected tab returns it to the grid, as every tab bar does. This is the per-tab form
        //    of choosing a sidebar destination again, which a compact window once handled by
        //    stranding the person on an empty column.
        libraryTab.tap()
        let albumKept = title.waitForExistence(timeout: 5) && title.isHittable
        XCTAssertTrue(albumKept, "Library must come back on the album it was left on: "
            + app.debugDescription)
        libraryTab.tap()
        let reselectPopped = title.waitForNonExistence(timeout: 5)
        let gridShown = album.waitForExistence(timeout: 5)
        XCTAssertTrue(reselectPopped && gridShown,
                      "Choosing the selected Library tab must return it to the grid: "
                        + app.debugDescription)
        // The grid is now the Library tab's page, so a round trip must not bring the album back.
        app.tabBars.buttons["Search"].firstMatch.tap()
        XCTAssertTrue(app.textFields["dulcet.search.field"].firstMatch.waitForExistence(timeout: 5),
                      "The Search tab must show search")
        libraryTab.tap()
        let gridKept = album.waitForExistence(timeout: 5) && !title.exists
        XCTAssertTrue(gridKept, "Library must come back on the grid it was left on")
        // Observed values, not a verdict: a line printed after a failed assertion must not read
        // as a pass.
        print("DULCET COMPACT SHELL OBSERVED bar-on-search=\(bar.exists)"
            + " album-kept=\(albumKept) reselect-popped=\(reselectPopped) grid-shown=\(gridShown)"
            + " grid-kept=\(gridKept)")
    }

    /// A failed track is not a dead end, on the deterministic fixture: the bar names the track
    /// that failed and offers Try Again, Skip and Dismiss in place of play/pause and next; the
    /// full player offers the same; Skip plays the next track and Try Again plays the failed one.
    @MainActor
    func testAFailedTrackOffersSkipAndRetryInTheBarAndThePlayer() {
        XCUIDevice.shared.orientation = .portrait
        let app = XCUIApplication()
        app.launchArguments += [
            "-dulcet-account-connect-layout-fixture",
            "-dulcet-layout-fixture-fail-track", "Disc 1 Track 1",
        ]
        app.launch()
        let window = app.windows.firstMatch
        XCTAssertTrue(window.waitForExistence(timeout: 10), "The app window must exist")
        XCTAssertLessThan(window.frame.width, 700,
                          "This proof requires a compact-width iPhone window; an iPad is invalid evidence")
        guard openDestination("Library", sidebarIdentifier: "dulcet.sidebar.library", in: app, compact: true) else {
            return
        }
        let album = app.buttons.matching(
            NSPredicate(format: "label BEGINSWITH %@", "Double Lines")
        ).firstMatch
        guard album.waitForExistence(timeout: 10),
              scrollIntoView(album, in: app, probingBlockingSystemAlerts: false) else {
            XCTFail("The fixture's Double Lines album must be reachable in the grid")
            return
        }
        album.tap()
        let firstTrack = app.buttons.matching(
            NSPredicate(format: "label BEGINSWITH %@", "Disc 1 Track 1")
        ).firstMatch
        guard firstTrack.waitForExistence(timeout: 10) else {
            XCTFail("The album page must list its first track: " + app.debugDescription)
            return
        }
        firstTrack.tap()

        // The bar: the failure named, and the three ways out of it.
        let bar = app.buttons["dulcet.mini-player.open"].firstMatch
        let retry = app.buttons["dulcet.mini-player.retry"].firstMatch
        let skip = app.buttons["dulcet.mini-player.skip"].firstMatch
        let dismiss = app.buttons["dulcet.mini-player.dismiss"].firstMatch
        guard retry.waitForExistence(timeout: 10) else {
            XCTFail("A failed track must offer Try Again on the bar: " + app.debugDescription)
            return
        }
        let failureLine = bar.label
        XCTAssertTrue(failureLine.contains("Couldn") && failureLine.contains("Disc 1 Track 1"),
                      "The bar must say which track failed; label=\(failureLine)")
        XCTAssertTrue(skip.exists && skip.isEnabled, "A failed track with a next entry must offer Skip")
        XCTAssertTrue(dismiss.exists, "A failed track's bar must be dismissable")
        XCTAssertFalse(app.buttons["dulcet.mini-player.play-pause"].exists,
                       "Play/pause has nothing to act on while the track has failed")
        attachScreenshot(named: "failed-track-bar", app: app)

        // The player says the same, with the same actions.
        bar.tap()
        let failure = app.descendants(matching: .any)["dulcet.now-playing.failure"].firstMatch
        guard failure.waitForExistence(timeout: 10) else {
            XCTFail("The player must show the failure: " + app.debugDescription)
            return
        }
        let playerSkip = app.buttons["dulcet.now-playing.skip"].firstMatch
        XCTAssertTrue(app.buttons["dulcet.now-playing.retry"].firstMatch.exists,
                      "The player must offer Try Again")
        XCTAssertTrue(playerSkip.exists && playerSkip.isEnabled, "The player must offer Skip")
        attachScreenshot(named: "failed-track-player", app: app)
        playerSkip.tap()
        let nowPlayingTitle = app.staticTexts["dulcet.now-playing.title"].firstMatch
        let skippedTo = nowPlayingTitle.waitForExistence(timeout: 10) ? nowPlayingTitle.label : "<none>"
        XCTAssertEqual(skippedTo, "Disc 1 Track 2", "Skip must play the entry after the failed one")
        app.buttons["dulcet.now-playing.close"].firstMatch.tap()
        XCTAssertTrue(nowPlayingTitle.waitForNonExistence(timeout: 10), "Closing must dismiss the player")

        // Dismiss puts a failure the person has seen away. The fixture fails the track until
        // Try Again, which Skip did not use, so playing it again fails it again.
        firstTrack.tap()
        guard dismiss.waitForExistence(timeout: 10) else {
            XCTFail("Playing the failed track again must fail it again: " + app.debugDescription)
            return
        }
        dismiss.tap()
        let dismissed = bar.waitForNonExistence(timeout: 5)
        XCTAssertTrue(dismissed, "Dismiss must put the failed track's bar away")

        // A new attempt after a dismissal is a new failure, and it must be shown, not stay away
        // with the old one.
        firstTrack.tap()
        let failureShownAgain = retry.waitForExistence(timeout: 10)
        XCTAssertTrue(failureShownAgain, "A failure after a dismissal must show the bar again")

        // Try Again plays the failed track itself. A Try Again wired to Next would name Disc 1
        // Track 2 here instead.
        retry.tap()
        let retried = waitForLabelContaining("Disc 1 Track 1", of: bar, timeout: 10)
            && !retry.exists
        XCTAssertTrue(retried, "Try Again must play the failed track; bar=\(bar.label)")
        print("DULCET FAILED TRACK OBSERVED bar=\(failureLine.debugDescription) skipped-to=\(skippedTo) dismissed=\(dismissed)"
            + " shown-again=\(failureShownAgain) retried=\(retried)")
    }

    /// The player survives the window crossing the compact/regular boundary while it is open --
    /// a large iPhone turned sideways here, iPad Split View and Stage Manager in use. The two
    /// presentation styles flip in one update; the shell hands the player from one to the other.
    ///
    /// This is a behaviour check, not a regression proof for that handover: on iOS 26.5 it also
    /// passes with the handover removed, so it cannot tell whether the handover is needed. What
    /// it asserts is the outcome: the player is on screen after each crossing, and the bar opens
    /// it again once it is closed.
    ///
    /// Requires an iPhone whose landscape width is regular (a Pro Max or Plus model); a device
    /// that stays compact when turned never crosses the boundary, and the test says so rather than
    /// passing.
    @MainActor
    func testThePlayerFollowsTheWindowAcrossASizeClassChange() {
        // Every compact proof starts by setting portrait, so a run that fails while turned does
        // not leave the next one on a landscape window.
        XCUIDevice.shared.orientation = .portrait
        let app = XCUIApplication()
        app.launchArguments.append("-dulcet-account-connect-layout-fixture")
        app.launch()
        let window = app.windows.firstMatch
        XCTAssertTrue(window.waitForExistence(timeout: 10), "The app window must exist")
        XCTAssertLessThan(window.frame.width, 700, "The proof starts on a compact portrait window")
        guard openDestination("Library", sidebarIdentifier: "dulcet.sidebar.library", in: app, compact: true) else {
            return
        }
        let album = app.buttons.matching(
            NSPredicate(format: "label BEGINSWITH %@", "Double Lines")
        ).firstMatch
        guard album.waitForExistence(timeout: 10),
              scrollIntoView(album, in: app, probingBlockingSystemAlerts: false) else {
            XCTFail("The fixture's Double Lines album must be reachable in the grid")
            return
        }
        album.tap()
        let firstTrack = app.buttons.matching(
            NSPredicate(format: "label BEGINSWITH %@", "Disc 1 Track 1")
        ).firstMatch
        guard firstTrack.waitForExistence(timeout: 10) else {
            XCTFail("The album page must list its first track")
            return
        }
        firstTrack.tap()
        let bar = app.buttons["dulcet.mini-player.open"].firstMatch
        guard bar.waitForExistence(timeout: 10) else {
            XCTFail("Playing must bring up the bar")
            return
        }
        bar.tap()
        let nowPlayingTitle = app.staticTexts["dulcet.now-playing.title"].firstMatch
        let close = app.buttons["dulcet.now-playing.close"].firstMatch
        guard nowPlayingTitle.waitForExistence(timeout: 10) else {
            XCTFail("The bar must open the player")
            return
        }

        // Across the boundary with the player open: it must come back in the other style.
        XCUIDevice.shared.orientation = .landscapeLeft
        let presentedAfterTurn = close.waitForExistence(timeout: 10)
            && nowPlayingTitle.waitForExistence(timeout: 5)
        let landscapeWidth = window.frame.width
        attachScreenshot(named: "player-after-turn-to-landscape", app: app)
        XCTAssertTrue(presentedAfterTurn,
                      "The player must still be on screen after the window turned regular: "
                        + app.debugDescription)
        guard presentedAfterTurn else { return }
        close.tap()
        XCTAssertTrue(nowPlayingTitle.waitForNonExistence(timeout: 10), "Close must dismiss the player")
        // Checks the experiment: the turn must actually have produced the regular shell.
        let regularShell = !app.tabBars.firstMatch.exists
            && app.staticTexts["dulcet.sidebar.library"].firstMatch.waitForExistence(timeout: 5)
        XCTAssertTrue(regularShell,
                      "Landscape must be a regular-width window (sidebar, no tab bar); a device that"
                        + " stays compact never crosses the boundary. width=\(landscapeWidth)")
        attachScreenshot(named: "regular-shell-landscape", app: app)

        // The defect's own symptom: after the flip, the bar must still open the player.
        guard bar.waitForExistence(timeout: 5) else {
            XCTFail("The bar must remain in the regular shell")
            return
        }
        bar.tap()
        let reopenedInLandscape = nowPlayingTitle.waitForExistence(timeout: 10)
        XCTAssertTrue(reopenedInLandscape, "The bar must open the player after the window changed")

        // And back across, with the player open again.
        XCUIDevice.shared.orientation = .portrait
        let presentedAfterReturn = close.waitForExistence(timeout: 10)
            && nowPlayingTitle.waitForExistence(timeout: 5)
        XCTAssertTrue(presentedAfterReturn,
                      "The player must still be on screen after the window turned compact again")
        if presentedAfterReturn { close.tap() }
        let closedInPortrait = nowPlayingTitle.waitForNonExistence(timeout: 10)
        bar.tap()
        let reopenedInPortrait = nowPlayingTitle.waitForExistence(timeout: 10)
        XCTAssertTrue(reopenedInPortrait, "The bar must open the player after the window turned back")
        print("DULCET SIZE CLASS PLAYER OBSERVED landscape-width=\(landscapeWidth)"
            + " regular-shell=\(regularShell) presented-after-turn=\(presentedAfterTurn)"
            + " reopened-landscape=\(reopenedInLandscape) presented-after-return=\(presentedAfterReturn)"
            + " closed-portrait=\(closedInPortrait) reopened-portrait=\(reopenedInPortrait)")
    }

    /// A swipe across the player's artwork changes track, as the system player's does: left for
    /// the next track, right for the previous one; a diagonal drag, a short drag and a swipe toward
    /// a control that is unavailable do nothing. On the deterministic fixture, so the titles are
    /// known and no server is involved. Each swipe is proved by the marker the swipe handler
    /// itself records -- exactly one per swipe, naming what it asked for -- so a title that
    /// changed, or stayed, for some other reason cannot pass for a handled swipe.
    @MainActor
    func testSwipingThePlayerArtworkChangesTrack() {
        guard requireSimulator(.phone, "The artwork swipe proof") else { return }
        XCUIDevice.shared.orientation = .portrait
        let app = XCUIApplication()
        app.launchArguments += ["-dulcet-account-connect-layout-fixture", "-dulcet-debug-ui-markers"]
        app.launch()
        let window = app.windows.firstMatch
        XCTAssertTrue(window.waitForExistence(timeout: 10), "The app window must exist")
        XCTAssertLessThan(window.frame.width, 700,
                          "This proof requires a compact-width iPhone window; an iPad is invalid evidence")
        guard requireProofMarkers(in: app) else { return }
        guard openDestination("Library", sidebarIdentifier: "dulcet.sidebar.library", in: app, compact: true) else {
            return
        }
        let album = app.buttons.matching(
            NSPredicate(format: "label BEGINSWITH %@", "Double Lines")
        ).firstMatch
        guard album.waitForExistence(timeout: 10),
              scrollIntoView(album, in: app, probingBlockingSystemAlerts: false) else {
            XCTFail("The fixture's Double Lines album must be reachable in the grid")
            return
        }
        album.tap()
        let firstTrack = app.buttons.matching(
            NSPredicate(format: "label BEGINSWITH %@", "Disc 1 Track 1")
        ).firstMatch
        guard firstTrack.waitForExistence(timeout: 10) else {
            XCTFail("The album page must list its first track")
            return
        }
        firstTrack.tap()
        let bar = app.buttons["dulcet.mini-player.open"].firstMatch
        guard bar.waitForExistence(timeout: 10) else {
            XCTFail("Playing must bring up the bar")
            return
        }
        bar.tap()
        let title = app.staticTexts["dulcet.now-playing.title"].firstMatch
        guard title.waitForExistence(timeout: 10), waitForLabel("Disc 1 Track 1", of: title, timeout: 5) else {
            XCTFail("The player must open on the track that was played")
            return
        }
        attachScreenshot(named: "player-sheet-artwork-glow", app: app)

        // The artwork is decorative and hidden from accessibility, so the swipe is placed by the
        // title under it: the cover ends one spacing step above the title and is at least 120
        // points tall, so 70 points above the title's top is on the cover.
        func drag(from startX: CGFloat, to endX: CGFloat, rising: CGFloat = 0) {
            let y = title.frame.minY - 70
            let origin = window.coordinate(withNormalizedOffset: .zero)
            let start = origin.withOffset(CGVector(dx: startX, dy: y))
            let end = origin.withOffset(CGVector(dx: endX, dy: y - rising))
            start.press(forDuration: 0.05, thenDragTo: end)
        }
        /// Performs one drag and returns what the handler recorded for it, and the title after.
        func swipe(_ name: String, expecting marker: String, then expectedTitle: String,
                   _ perform: () -> Void) -> Bool {
            let before = proofMarkers(in: app)
            perform()
            let recorded = waitForMarkers(after: before, [marker], in: app, timeout: 5)
            let titled = waitForLabel(expectedTitle, of: title, timeout: 5)
            // "Nothing happened" has to have lasted, not merely not happened yet.
            if marker == "swipe:none" { Thread.sleep(forTimeInterval: 1) }
            let settled = title.label == expectedTitle
            print("DULCET ARTWORK SWIPE step=\(name) recorded=\(recorded) title=\(title.label.debugDescription)")
            XCTAssertEqual(recorded, [marker], "\(name): the swipe handler must record exactly \(marker)")
            XCTAssertTrue(titled && settled, "\(name): the player must show \(expectedTitle); title=\(title.label)")
            return recorded == [marker] && titled && settled
        }
        let width = window.frame.width
        // On the first track Previous is unavailable, so a right swipe asks for nothing.
        let unavailable = swipe("right-on-first", expecting: "swipe:none", then: "Disc 1 Track 1") {
            drag(from: width * 0.2, to: width * 0.8)
        }
        let next = swipe("left", expecting: "swipe:next", then: "Disc 1 Track 2") {
            drag(from: width * 0.8, to: width * 0.2)
        }
        // Up and across by the same distance: neither direction dominates. Upward, so the sheet's
        // own drag-to-dismiss is not what answers it. Observed on an iPhone 17 Pro simulator: an
        // upward drag with this much vertical travel ends without the swipe handler running at all
        // (no marker), at 45 degrees and at 30 alike -- presumably taken by the scroll view the
        // player sits in, which was not measured. So this step can show only that nothing happens:
        // the handler, if it runs, must ask for nothing, and the track must not change. Which of the
        // two occurred is printed; the classification itself is `ArtworkSwipeTests`'.
        let diagonalBefore = proofMarkers(in: app)
        drag(from: width * 0.8, to: width * 0.2, rising: width * 0.6)
        let diagonalRecorded = waitForMarkers(after: diagonalBefore, ["swipe:none"], in: app, timeout: 3)
        Thread.sleep(forTimeInterval: 1)
        let diagonal = (diagonalRecorded == ["swipe:none"] || diagonalRecorded.isEmpty)
            && title.label == "Disc 1 Track 2"
        print("DULCET ARTWORK SWIPE step=diagonal recorded=\(diagonalRecorded)"
            + " handler-ran=\(!diagonalRecorded.isEmpty) title=\(title.label.debugDescription)")
        XCTAssertTrue(diagonal, "A diagonal drag must do nothing; recorded=\(diagonalRecorded) title=\(title.label)")
        let short = swipe("short", expecting: "swipe:none", then: "Disc 1 Track 2") {
            drag(from: width * 0.6, to: width * 0.6 - 40)
        }
        let previous = swipe("right", expecting: "swipe:previous", then: "Disc 1 Track 1") {
            drag(from: width * 0.2, to: width * 0.8)
        }
        print("DULCET ARTWORK SWIPE OBSERVED unavailable-previous=\(unavailable) next=\(next)"
            + " diagonal=\(diagonal) short=\(short) previous=\(previous) markers=\(proofMarkers(in: app))")
    }

    /// The experiment this proof needs, asserted rather than assumed (CLAUDE.md traps 31 and 32):
    /// a simulator, of the device class the claim is about. It prints what it measured, so each
    /// run's transcript names its own destination.
    @MainActor
    private func requireSimulator(_ idiom: UIUserInterfaceIdiom, _ proof: String) -> Bool {
        let environment = ProcessInfo.processInfo.environment
        let name = environment["SIMULATOR_DEVICE_NAME"] ?? "<none>"
        let model = environment["SIMULATOR_MODEL_IDENTIFIER"] ?? "<none>"
        let observed = UIDevice.current.userInterfaceIdiom
        print("DULCET UI DESTINATION proof=\(proof.debugDescription) simulator=\(environment["SIMULATOR_UDID"] != nil)"
            + " name=\(name.debugDescription) model=\(model) idiom=\(observed.rawValue)")
        guard environment["SIMULATOR_UDID"] != nil else {
            XCTFail("\(proof) requires a simulator; a physical device is not valid evidence")
            return false
        }
        guard observed == idiom else {
            XCTFail("\(proof) requires idiom \(idiom.rawValue) but runs on \(name) (idiom \(observed.rawValue))")
            return false
        }
        return true
    }

    /// The marker log a debug build shows when launched with `-dulcet-debug-ui-markers`. Its
    /// absence fails the proof: without it no handler can be shown to have run.
    @MainActor
    private func requireProofMarkers(in app: XCUIApplication) -> Bool {
        let log = app.staticTexts["dulcet.debug.ui-markers"].firstMatch
        guard log.waitForExistence(timeout: 10), log.label.hasPrefix("dulcet-ui") else {
            XCTFail("The debug build must show its proof markers: " + app.debugDescription)
            return false
        }
        return true
    }

    /// What the app's handlers have recorded, oldest first.
    @MainActor
    private func proofMarkers(in app: XCUIApplication) -> [String] {
        let log = app.staticTexts["dulcet.debug.ui-markers"].firstMatch
        guard log.exists else { return ["<no marker log>"] }
        let words = log.label.split(separator: " ").map(String.init)
        return words.first == "dulcet-ui" ? Array(words.dropFirst()) : ["<unreadable marker log>"]
    }

    /// Waits until exactly `expected` has been recorded after `before`, and returns what was.
    /// Only what follows `before` counts, so an earlier identical event cannot satisfy it.
    @MainActor
    private func waitForMarkers(
        after before: [String], _ expected: [String], in app: XCUIApplication, timeout: TimeInterval
    ) -> [String] {
        let deadline = Date().addingTimeInterval(timeout)
        var recorded: [String] = []
        repeat {
            let now = proofMarkers(in: app)
            recorded = now.starts(with: before) ? Array(now.dropFirst(before.count)) : ["<log rewritten>"] + now
            if recorded == expected { return recorded }
            Thread.sleep(forTimeInterval: 0.2)
        } while Date() < deadline
        return recorded
    }

    @MainActor
    private func waitForLabelContaining(
        _ text: String, of element: XCUIElement, timeout: TimeInterval
    ) -> Bool {
        let deadline = Date().addingTimeInterval(timeout)
        while !(element.exists && element.label.contains(text)), Date() < deadline {
            Thread.sleep(forTimeInterval: 0.1)
        }
        return element.exists && element.label.contains(text)
    }

    /// Kept in the result bundle whatever the outcome, so a layout can be read after the run.
    @MainActor
    private func attachScreenshot(named name: String, app: XCUIApplication) {
        let attachment = XCTAttachment(screenshot: app.screenshot())
        attachment.name = name
        attachment.lifetime = .keepAlways
        add(attachment)
    }

    /// Reaches a top-level destination the way a person does on the window's size class: the tab
    /// bar on a compact window, the sidebar on a regular one.
    @MainActor
    private func openDestination(
        _ tabTitle: String,
        sidebarIdentifier: String,
        in app: XCUIApplication,
        compact: Bool
    ) -> Bool {
        if compact {
            let tab = app.tabBars.buttons[tabTitle].firstMatch
            guard tab.waitForExistence(timeout: 5) else {
                XCTFail("A compact window must present the \(tabTitle) tab: " + app.debugDescription)
                return false
            }
            tab.tap()
            return true
        }
        // staticTexts, not descendants(matching: .any): the sidebar row's identifier is carried
        // by both its SF Symbol image and its label, so an .any query resolves ambiguously.
        let row = app.staticTexts[sidebarIdentifier].firstMatch
        guard row.waitForExistence(timeout: 5), row.isHittable else {
            XCTFail("The \(tabTitle) row must be visible in the sidebar: " + app.debugDescription)
            return false
        }
        row.tap()
        return true
    }

    /// Playing leaves the person where they were; the full player is one tap on the bar away.
    /// Asserting the bar first separates "playback never started" from "the bar never opened".
    @MainActor
    private func openNowPlayingFromBar(in app: XCUIApplication, expectingTitle title: String) -> Bool {
        let bar = app.buttons["dulcet.mini-player.open"].firstMatch
        guard bar.waitForExistence(timeout: 15) else {
            XCTFail("Activation must bring up the now-playing bar: " + app.debugDescription)
            return false
        }
        // Preparing shows a placeholder title; wait for the playing track's own name.
        let deadline = Date().addingTimeInterval(20)
        while !bar.label.contains(title), Date() < deadline {
            Thread.sleep(forTimeInterval: 0.25)
        }
        XCTAssertTrue(bar.label.contains(title), "The bar must name \(title); label=\(bar.label)")
        bar.tap()
        return true
    }

    @MainActor
    private func waitForLabel(_ label: String, of element: XCUIElement, timeout: TimeInterval) -> Bool {
        let deadline = Date().addingTimeInterval(timeout)
        while element.label != label, Date() < deadline {
            Thread.sleep(forTimeInterval: 0.1)
        }
        return element.label == label
    }

    private enum BlockingSystemDialogProbeResult {
        case absent
        case handled
        case unsupported
    }

    private struct LivePlaybackConfiguration {
        let serverURL: String
        let username: String
        let password: String
    }

    private struct PlayCountReadFailure: Error {
        let message: String
    }

    /// One `search3` read of the server, as the disposable server's own account. Its salt is fresh
    /// per request, and nothing about the request -- which carries a token -- is ever reported.
    private func readServerPlayCount(
        title: String,
        album: String,
        configuration: LivePlaybackConfiguration
    ) -> Result<Int, PlayCountReadFailure> {
        let salt = (0..<16).map { _ in String(format: "%02x", UInt8.random(in: 0...255)) }.joined()
        let token = Insecure.MD5.hash(data: Data((configuration.password + salt).utf8))
            .map { String(format: "%02x", $0) }.joined()
        guard var components = URLComponents(string: configuration.serverURL) else {
            return .failure(.init(message: "the server URL is malformed (withheld)"))
        }
        let basePath = components.path.hasSuffix("/") ? String(components.path.dropLast()) : components.path
        components.path = basePath + "/rest/search3"
        components.queryItems = [
            URLQueryItem(name: "u", value: configuration.username),
            URLQueryItem(name: "t", value: token),
            URLQueryItem(name: "s", value: salt),
            URLQueryItem(name: "v", value: "1.16.1"),
            URLQueryItem(name: "c", value: "dulcet-ui-test"),
            URLQueryItem(name: "f", value: "json"),
            URLQueryItem(name: "query", value: title),
            URLQueryItem(name: "songCount", value: "50"),
            URLQueryItem(name: "albumCount", value: "0"),
            URLQueryItem(name: "artistCount", value: "0"),
        ]
        guard let url = components.url else {
            return .failure(.init(message: "the request URL could not be built (withheld)"))
        }
        /// Written once by the completion handler, read after the semaphore it signals.
        final class Outcome: @unchecked Sendable {
            var value: Result<Data, PlayCountReadFailure> = .failure(.init(message: "no response"))
        }
        let outcome = Outcome()
        let done = DispatchSemaphore(value: 0)
        let task = URLSession.shared.dataTask(with: url) { data, response, error in
            if let error = error as? URLError {
                // The code only: a URLError's description can carry the URL, and so the token.
                outcome.value = .failure(.init(message: "/rest/search3 unreachable: code \(error.code.rawValue)"))
            } else if error != nil {
                outcome.value = .failure(.init(message: "/rest/search3 failed"))
            } else if let status = (response as? HTTPURLResponse)?.statusCode, status != 200 {
                outcome.value = .failure(.init(message: "/rest/search3 returned HTTP \(status)"))
            } else if let data {
                outcome.value = .success(data)
            }
            done.signal()
        }
        task.resume()
        guard done.wait(timeout: .now() + 15) == .success else {
            task.cancel()
            return .failure(.init(message: "/rest/search3 did not answer within 15 s"))
        }
        let data: Data
        switch outcome.value {
        case let .success(body): data = body
        case let .failure(failure): return .failure(failure)
        }
        guard let document = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
              let envelope = document["subsonic-response"] as? [String: Any] else {
            return .failure(.init(message: "/rest/search3 returned no subsonic-response envelope"))
        }
        guard envelope["status"] as? String == "ok" else {
            let error = envelope["error"] as? [String: Any]
            return .failure(.init(message: "/rest/search3 failed: code=\(String(describing: error?["code"]))"))
        }
        let songs = (envelope["searchResult3"] as? [String: Any])?["song"] as? [[String: Any]] ?? []
        let matches = songs.filter { $0["title"] as? String == title && $0["album"] as? String == album }
        guard matches.count == 1, let song = matches.first else {
            return .failure(.init(message: "\(matches.count) songs titled \(title) on \(album); exactly one is required"))
        }
        // Subsonic omits playCount when it is zero.
        guard let raw = song["playCount"] else { return .success(0) }
        guard let number = raw as? NSNumber, CFGetTypeID(number) != CFBooleanGetTypeID(),
              number.doubleValue == Double(number.intValue), number.intValue >= 0 else {
            return .failure(.init(message: "playCount is not a non-negative integer: \(raw)"))
        }
        return .success(number.intValue)
    }

    private struct PlaybackProgressSample {
        let elapsed: TimeInterval
        let duration: TimeInterval
        let accessibilityValue: String
    }

    private enum SearchUIWindowExpectation {
        case compactWidth
        case regularWidth
    }

    /// Simulator evidence for the `platform-observed-search-activation` gate on the iOS cell: a
    /// query typed through the app's own UI reaches the search field, ranked results render with
    /// the disposable corpus's canary track at rank zero, and activating it starts the
    /// search-sourced queue -- observed through the Now Playing surface (the activated track's
    /// title, the "Playing from Search" source line) and the progress slider that only exists
    /// once media time is actually advancing.
    @MainActor
    func testSimulatorSearchQueryRanksAndActivatesTrackOnIPhone() {
        guard ProcessInfo.processInfo.environment["SIMULATOR_UDID"] != nil else {
            XCTFail("This search proof requires an iPhone simulator; a physical device is not valid evidence")
            return
        }

        proveSearchQueryRanksAndActivatesTrack(windowExpectation: .compactWidth)
    }

    /// The same typed-query-to-Now-Playing proof on the regular-width iPad split layout, where
    /// the sidebar and detail are visible at once. Recorded against the iPadOS cell: a compact
    /// window here is a wrong-destination failure, not a pass.
    @MainActor
    func testSimulatorSearchQueryRanksAndActivatesTrackOnIPadOS() {
        guard ProcessInfo.processInfo.environment["SIMULATOR_UDID"] != nil else {
            XCTFail("This search proof requires an iPad simulator; a physical device is not valid evidence")
            return
        }

        proveSearchQueryRanksAndActivatesTrack(windowExpectation: .regularWidth)
    }

    /// A grid tile opens its album on a phone against a live library, by element tap -- the path
    /// a device run reported dead. Two cases: a named tile, wherever it sits, and, with the
    /// now-playing bar on screen, a tile whose centre lies under the bar or the tab bar, which the
    /// tap must scroll to rather than lose to the controls on top of it.
    ///
    /// Every tile's frame is printed with the bar's and the tab bar's, so the geometry here can be
    /// compared with a device build's.
    @MainActor
    func testCompactLibraryTileOpensItsAlbumOnALiveLibrary() {
        XCUIDevice.shared.orientation = .portrait
        guard let configuration = livePlaybackConfiguration() else { return }
        let app = XCUIApplication()
        app.launchArguments += [
            "-dulcet-debug-connect-account",
            "-dulcet-debug-account-server-url",
            configuration.serverURL,
            "-dulcet-debug-account-username",
            configuration.username,
            "-dulcet-debug-account-password",
            configuration.password,
        ]
        app.launch()
        let window = app.windows.firstMatch
        XCTAssertTrue(window.waitForExistence(timeout: 10), "The app window must exist")
        XCTAssertLessThan(window.frame.width, 700,
                          "This proof requires a compact-width iPhone window; an iPad is invalid evidence")
        guard app.buttons["Sign Out"].firstMatch.waitForExistence(timeout: 30) else {
            XCTFail("The live account connection must succeed first")
            return
        }
        guard openDestination("Library", sidebarIdentifier: "dulcet.sidebar.library", in: app, compact: true) else {
            return
        }
        let tiles = app.buttons.matching(identifier: "dulcet.library.album")
        let albumTitle = app.staticTexts["dulcet.album.title"].firstMatch
        guard tiles.firstMatch.waitForExistence(timeout: 30) else {
            XCTFail("The live library must show album tiles: " + app.debugDescription)
            return
        }

        // 1. The Double Lines tile opens its album -- and Play there puts up the bar the second
        //    case needs. Neither the tile's row nor an idle player is asserted: where it sorts is
        //    the corpus's business, and a queue restored from an earlier run can have the bar up
        //    before this taps anything. Double Lines, not whichever tile sorts first: the corpus's
        //    untagged album holds an Ogg file, which says nothing about tiles.
        let first = tiles.matching(NSPredicate(format: "label BEGINSWITH %@", "Double Lines")).firstMatch
        guard first.waitForExistence(timeout: 10) else {
            XCTFail("The live library must show the Double Lines tile: " + app.debugDescription)
            return
        }
        let firstLabel = first.label
        print("DULCET GRID TILE first label=\(firstLabel.debugDescription) frame=\(first.frame)"
            + " hittable=\(first.isHittable)")
        first.tap()
        guard albumTitle.waitForExistence(timeout: 10) else {
            XCTFail("Tapping the first tile must open its album: " + app.debugDescription)
            return
        }
        let firstOpened = firstLabel.hasPrefix(albumTitle.label)
        XCTAssertTrue(firstOpened,
                      "The first tile must open its own album; tile=\(firstLabel) page=\(albumTitle.label)")
        // Playing puts the bar on screen, which the second case needs. The bar is the condition,
        // not a Pause label: this album's four tracks last two seconds in all, so the queue has
        // usually finished -- and the bar is back on Play, still showing -- before a label query
        // returns on a loaded host. (A queue restored from an earlier run can have the bar up
        // already; either way the second case runs with it on screen, which is what it needs.)
        app.buttons["dulcet.album.play"].firstMatch.tap()
        guard app.buttons["dulcet.mini-player.open"].firstMatch.waitForExistence(timeout: 30) else {
            XCTFail("The now-playing bar must be on screen after Play: " + app.debugDescription)
            return
        }
        app.navigationBars.buttons.firstMatch.tap()
        XCTAssertTrue(albumTitle.waitForNonExistence(timeout: 10), "Back must return to the grid")

        // 2. A tile whose centre lies under the bar or the tab bar -- where a tap at the centre
        //    would land on the controls on top -- located from the frames rather than assumed:
        //    without one on screen this case measures nothing, and it fails saying so. A tile
        //    whose top half shows above the bar is not the case; its centre is plainly tappable.
        let bar = app.buttons["dulcet.mini-player.open"].firstMatch
        let tabBar = app.tabBars.firstMatch
        guard bar.waitForExistence(timeout: 5), tabBar.exists else {
            XCTFail("The bar and the tab bar must both be on screen over the grid")
            return
        }
        let covered = bar.frame.union(tabBar.frame)
        print("DULCET GRID FRAMES window=\(window.frame) bar=\(bar.frame) tab-bar=\(tabBar.frame)")
        var target: XCUIElement?
        var targetLabel = ""
        for index in 0..<tiles.count {
            let tile = tiles.element(boundBy: index)
            let frame = tile.frame
            let centreCovered = frame.intersects(covered) && frame.midY >= covered.minY
                && frame.minY < window.frame.maxY
            print("DULCET GRID TILE \(index) label=\(tile.label.debugDescription) frame=\(frame)"
                + " hittable=\(tile.isHittable) centre-under-bar-or-tab-bar=\(centreCovered)")
            if target == nil, centreCovered {
                target = tile
                targetLabel = tile.label
            }
        }
        guard let target else {
            XCTFail("No tile's centre lay under the bar or tab bar, so the covered case was not exercised")
            return
        }
        target.tap()
        let openedCovered = albumTitle.waitForExistence(timeout: 10)
        let openedLabel = openedCovered ? albumTitle.label : "<none>"
        XCTAssertTrue(openedCovered && targetLabel.hasPrefix(openedLabel),
                      "Tapping a tile under the bar must open that tile's album; tile=\(targetLabel)"
                        + " page=\(openedLabel)")
        print("DULCET GRID TILE OBSERVED first-opened=\(firstOpened)"
            + " covered-tile=\(targetLabel.debugDescription) covered-opened=\(openedLabel.debugDescription)")
    }

    /// A track that cannot play because of what it is -- here, an MP3 whose frames do not decode --
    /// is skipped with a notice, and the next track plays (spec §12.12). Live, because only the
    /// real engine and the core's queue decide this: the layout fixture has neither.
    ///
    /// Requires the disposable server to hold the opt-in "Skip Probe" album that
    /// `tools/seed-skip-probe` adds -- "Unplayable Probe" (undecodable) then "Playable After Skip"
    /// -- and fails, never skips, when it does not.
    ///
    /// The server's play counts are read over `/rest`, read-only: the skipped track's must stay
    /// zero, and -- the control that shows the read can see a play at all -- the next track's must
    /// go up by one once it has played past its threshold. Only a count that must NOT move is the
    /// claim here; the playback canary's proof of a delivered scrobble reads its count outside the
    /// test, for the reason `tools/read-play-count` gives.
    @MainActor
    func testAnUnplayableTrackIsSkippedWithANoticeAndTheNextPlays() {
        guard requireSimulator(.phone, "The automatic skip proof") else { return }
        XCUIDevice.shared.orientation = .portrait
        guard let configuration = livePlaybackConfiguration() else { return }
        guard let unplayableBefore = serverPlayCount(title: "Unplayable Probe", configuration: configuration),
              let playableBefore = serverPlayCount(title: "Playable After Skip", configuration: configuration) else {
            return
        }
        XCTAssertEqual(unplayableBefore, 0, "The skipped track must start this proof with no plays")
        guard let run = startSkipProbe(configuration: configuration) else { return }
        let app = run.app
        let notice = run.notice
        let noticeLabel = run.sighting.label
        XCTAssertTrue(noticeLabel.contains("Unplayable Probe") && noticeLabel.contains("Skipped"),
                      "A skipped track must be named in a notice; notice=\(noticeLabel)")
        let noticeMarkers = waitForMarkers(after: run.markersBefore, ["skip-notice:1"], in: app, timeout: 5)
        XCTAssertEqual(noticeMarkers, ["skip-notice:1"], "The notice's own handler must run, once")
        let placement = assertNoticeClearsNavigation(run.sighting)
        attachScreenshot(named: "skipped-track-notice", app: app)

        // The next track plays, and no failure line stays for the track that is not playing.
        let bar = app.buttons["dulcet.mini-player.open"].firstMatch
        let playPause = app.buttons["dulcet.mini-player.play-pause"].firstMatch
        let barNamesNext = waitForLabelContaining("Playable After Skip", of: bar, timeout: 20)
        let playing = playPause.waitForExistence(timeout: 10) && waitForLabel("Pause", of: playPause, timeout: 20)
        let noFailureLine = !app.buttons["dulcet.mini-player.retry"].exists
        XCTAssertTrue(barNamesNext, "The bar must move on to the next track; bar=\(bar.label)")
        XCTAssertTrue(playing, "The next track must play; play/pause=\(playPause.label)")
        XCTAssertTrue(noFailureLine, "No failure line may stay for a track that is not playing")
        // Non-blocking and brief: it goes away by itself.
        let noticeGone = notice.waitForNonExistence(timeout: 10)
        XCTAssertTrue(noticeGone, "The notice must go away by itself")

        // Previously Played lists the skipped track like any other entry the queue passed, unmarked.
        bar.tap()
        let title = app.staticTexts["dulcet.now-playing.title"].firstMatch
        let titled = title.waitForExistence(timeout: 10) && waitForLabel("Playable After Skip", of: title, timeout: 10)
        XCTAssertTrue(titled, "The player must show the track that plays; title=\(title.label)")
        let upNextToggle = app.buttons["dulcet.now-playing.up-next"].firstMatch
        var historyRow = "<none>"
        if upNextToggle.waitForExistence(timeout: 5) {
            upNextToggle.tap()
            let historyToggle = app.descendants(matching: .any)["dulcet.history.toggle"].firstMatch
            if historyToggle.waitForExistence(timeout: 5) {
                historyToggle.tap()
                let row = app.buttons["dulcet.history.row.0"].firstMatch
                historyRow = row.waitForExistence(timeout: 5) ? row.label : "<none>"
            }
        }
        XCTAssertTrue(historyRow.hasPrefix("Unplayable Probe") && !historyRow.localizedCaseInsensitiveContains("skip"),
                      "The skipped track must be listed, unmarked, under Previously Played; row=\(historyRow)")
        attachScreenshot(named: "skipped-track-history", app: app)

        // The server: the next track's play is counted once it passes its threshold -- so the read
        // can see a play -- and the skipped track's never is.
        let playableAfter = awaitServerPlayCount(
            title: "Playable After Skip", configuration: configuration,
            expected: playableBefore + 1, timeout: 60
        )
        let unplayableAfter = serverPlayCount(title: "Unplayable Probe", configuration: configuration)
        XCTAssertEqual(playableAfter, playableBefore + 1,
                       "Control: the track that played must be counted once, or the read sees no plays")
        XCTAssertEqual(unplayableAfter, 0, "The skipped track must never be counted as played")
        print("DULCET AUTO SKIP OBSERVED notice=\(noticeLabel.debugDescription) markers=\(noticeMarkers)"
            + " placement=\(placement) bar=\(bar.label.debugDescription) playing=\(playing)"
            + " no-failure-line=\(noFailureLine) notice-gone=\(noticeGone) history-row-0=\(historyRow.debugDescription)"
            + " unplayable-plays=\(unplayableBefore)->\(String(describing: unplayableAfter))"
            + " playable-plays=\(playableBefore)->\(String(describing: playableAfter))")
    }

    /// At the largest accessibility text size the skip notice -- its text capped at the second
    /// accessibility size -- wraps onto several lines, still names a short title, and sits clear of
    /// the navigation bar, the tab bar and the now-playing bar, inside the screen's margins (spec
    /// §12.12 rule 5). The screenshot is the evidence that the card contains its
    /// text; the frames are the evidence of where it is.
    @MainActor
    func testTheSkipNoticeStaysClearOfNavigationAtTheLargestTextSize() {
        guard requireSimulator(.phone, "The accessibility-size skip notice proof") else { return }
        XCUIDevice.shared.orientation = .portrait
        guard let configuration = livePlaybackConfiguration() else { return }
        guard let run = startSkipProbe(
            configuration: configuration,
            extraLaunchArguments: ["-UIPreferredContentSizeCategoryName", "UICTContentSizeCategoryAccessibilityXXXL"]
        ) else { return }
        let notice = run.sighting
        XCTAssertTrue(notice.label.contains("Unplayable Probe"), "notice=\(notice.label)")
        let placement = assertNoticeClearsNavigation(notice)
        // The experiment is the one intended: at this size the sentence wraps, so the notice is
        // several lines tall rather than the one line of the default size.
        XCTAssertGreaterThan(notice.frame.height, 90, "The notice must be at an accessibility size; \(placement)")
        attachScreenshot(named: "skipped-track-notice-ax5", app: run.app)
        print("DULCET AUTO SKIP AX5 OBSERVED notice=\(notice.label.debugDescription) placement=\(placement)")
    }

    /// A title long enough that, at the largest text size, the sentence naming it is too tall for
    /// the notice's share of the page, so the notice must say the same without it (spec §12.12
    /// rule 5). The album is its own, so this proof's queue never runs on into the other's tracks.
    @MainActor
    func testALongTitledSkipNoticeGivesWayToItsShorterSentenceAtTheLargestTextSize() {
        guard requireSimulator(.phone, "The long-title skip notice proof") else { return }
        XCUIDevice.shared.orientation = .portrait
        guard let configuration = livePlaybackConfiguration() else { return }
        let probe = SkipProbeAlbum.longTitle
        // The experiment is the one intended: the fixture title is the 70 characters decided.
        XCTAssertEqual(probe.unplayableTitle.count, 70, "The long title must be 70 characters")
        guard let run = startSkipProbe(
            configuration: configuration,
            probe: probe,
            extraLaunchArguments: ["-UIPreferredContentSizeCategoryName", "UICTContentSizeCategoryAccessibilityXXXL"]
        ) else { return }
        let notice = run.sighting
        let placement = assertNoticeClearsNavigation(notice)
        XCTAssertEqual(notice.label, "Couldn\u{2019}t play a track. Skipped.",
                       "The notice must show the sentence without the title; \(placement)")
        XCTAssertFalse(notice.label.contains("Concerto"), "notice=\(notice.label)")
        attachScreenshot(named: "skipped-track-notice-ax5-long-title", app: run.app)
        print("DULCET AUTO SKIP AX5 LONG TITLE OBSERVED notice=\(notice.label.debugDescription) placement=\(placement)")
    }

    private struct SkipProbeRun {
        let app: XCUIApplication
        /// The live element, for what happens after it was seen: that it goes away.
        let notice: XCUIElement
        /// What the notice and the bars were at the instant it was seen, from one snapshot.
        let sighting: NoticeSighting
        let markersBefore: [String]
    }

    /// The notice and everything its placement is judged against, read from ONE accessibility
    /// snapshot of the whole app. The notice lives four seconds, and under a loaded CI host a
    /// single XCUITest query has taken nearly eight (run 36224652690: the existence check that
    /// found the notice returned after it had gone, and the next query found nothing). Reading
    /// the label and each frame as separate queries would compare frames from different moments,
    /// or find the notice gone; a snapshot is one instant.
    private struct NoticeSighting {
        let label: String
        let frame: CGRect
        let window: CGRect?
        let navigationBar: CGRect?
        let tabBar: CGRect?
        let nowPlayingBar: CGRect?
        let attempts: Int
        let seconds: Double
    }

    /// Takes whole-app snapshots until one holds the notice. Fails, never skips: nil after
    /// `timeout`, reporting how many snapshots were taken and what the notice's own handler
    /// recorded, so a notice that was shown too briefly for the harness is told apart from one
    /// that was never shown.
    @MainActor
    private func sightNotice(in app: XCUIApplication, markersBefore: [String], timeout: TimeInterval) -> NoticeSighting? {
        let start = Date()
        var attempts = 0
        while Date().timeIntervalSince(start) < timeout {
            attempts += 1
            guard let root = try? app.snapshot() else { continue }
            var notice: XCUIElementSnapshot?
            var window: CGRect?
            var navigationBar: CGRect?
            var tabBar: CGRect?
            var nowPlayingBar: CGRect?
            var pending: [XCUIElementSnapshot] = [root]
            // Depth-first, in document order, so each first match is the one `firstMatch` names.
            while let element = pending.popLast() {
                if notice == nil, element.identifier == "dulcet.playback.skipped-notice" { notice = element }
                if window == nil, element.elementType == .window { window = element.frame }
                if navigationBar == nil, element.elementType == .navigationBar { navigationBar = element.frame }
                if tabBar == nil, element.elementType == .tabBar { tabBar = element.frame }
                if nowPlayingBar == nil, element.identifier == "dulcet.mini-player.open" { nowPlayingBar = element.frame }
                pending.append(contentsOf: element.children.reversed())
            }
            if let notice {
                let seconds = Date().timeIntervalSince(start)
                print("DULCET SKIP NOTICE SIGHTED attempts=\(attempts) seconds=\(String(format: "%.1f", seconds))")
                return NoticeSighting(
                    label: notice.label, frame: notice.frame, window: window, navigationBar: navigationBar,
                    tabBar: tabBar, nowPlayingBar: nowPlayingBar, attempts: attempts, seconds: seconds
                )
            }
        }
        let recorded = proofMarkers(in: app)
        let after = recorded.starts(with: markersBefore) ? Array(recorded.dropFirst(markersBefore.count)) : recorded
        XCTFail("A skipped track must be named in a notice; no snapshot held one in \(attempts) attempt(s) "
            + "over \(Int(timeout)) s. Markers recorded since the tap: \(after) -- a skip-notice marker "
            + "means it was shown and outlived no snapshot. " + app.debugDescription)
        return nil
    }

    /// An opt-in album `tools/seed-skip-probe` adds: an undecodable track, then a playable one.
    private struct SkipProbeAlbum {
        let album: String
        let unplayableTitle: String
        let playableTitle: String

        static let standard = Self(
            album: "Skip Probe",
            unplayableTitle: "Unplayable Probe",
            playableTitle: "Playable After Skip"
        )
        static let longTitle = Self(
            album: "Long Title Skip Probe",
            unplayableTitle: "Unplayable Long Probe -- Concerto for Two Violins in D minor, BWV 1043",
            playableTitle: "Playable After Long Title"
        )
    }

    /// Connects, opens the probe album, taps its unplayable first track, and waits for the notice.
    /// Fails, never skips, when the album is not there.
    @MainActor
    private func startSkipProbe(
        configuration: LivePlaybackConfiguration,
        probe: SkipProbeAlbum = .standard,
        extraLaunchArguments: [String] = []
    ) -> SkipProbeRun? {
        let app = XCUIApplication()
        app.launchArguments += [
            "-dulcet-debug-ui-markers",
            "-dulcet-debug-connect-account",
            "-dulcet-debug-account-server-url",
            configuration.serverURL,
            "-dulcet-debug-account-username",
            configuration.username,
            "-dulcet-debug-account-password",
            configuration.password,
        ] + extraLaunchArguments
        app.launch()
        let window = app.windows.firstMatch
        XCTAssertTrue(window.waitForExistence(timeout: 10), "The app window must exist")
        guard app.buttons["Sign Out"].firstMatch.waitForExistence(timeout: 30) else {
            XCTFail("The live account connection must succeed first")
            return nil
        }
        guard requireProofMarkers(in: app),
              openDestination("Library", sidebarIdentifier: "dulcet.sidebar.library", in: app, compact: true) else {
            return nil
        }
        // The library's rows are realized as they scroll into view, and the probe albums sort
        // after the default corpus, so on a phone "Skip Probe" is below the fold and does not exist
        // until the list scrolls to it. Wait for the library to show albums at all, then scroll.
        let albums = app.buttons.matching(identifier: "dulcet.library.album")
        let album = albums.matching(NSPredicate(format: "label BEGINSWITH %@", probe.album)).firstMatch
        guard albums.firstMatch.waitForExistence(timeout: 30) else {
            XCTFail("The library must list albums: " + app.debugDescription)
            return nil
        }
        guard scrollIntoView(album, in: app, probingBlockingSystemAlerts: false) else {
            XCTFail("The disposable server must expose the opt-in \(probe.album) album; add it with "
                + "tools/seed-skip-probe: " + app.debugDescription)
            return nil
        }
        album.tap()
        // The fixture is the one intended: the unplayable track first, a playable one after it.
        let unplayableRow = app.buttons.matching(NSPredicate(format: "label BEGINSWITH %@", probe.unplayableTitle)).firstMatch
        let playableRow = app.buttons.matching(NSPredicate(format: "label BEGINSWITH %@", probe.playableTitle)).firstMatch
        guard unplayableRow.waitForExistence(timeout: 15), scrollIntoView(unplayableRow, in: app),
              playableRow.waitForExistence(timeout: 5),
              unplayableRow.frame.minY < playableRow.frame.minY else {
            XCTFail("The \(probe.album) album must list \(probe.unplayableTitle) before \(probe.playableTitle): "
                + app.debugDescription)
            return nil
        }
        let markersBefore = proofMarkers(in: app)
        unplayableRow.tap()
        let notice = app.descendants(matching: .any)["dulcet.playback.skipped-notice"].firstMatch
        guard let sighting = sightNotice(in: app, markersBefore: markersBefore, timeout: 30) else { return nil }
        return SkipProbeRun(app: app, notice: notice, sighting: sighting, markersBefore: markersBefore)
    }

    /// The notice is drawn over no navigation control: not the navigation bar, not the tab bar,
    /// not the now-playing bar -- it sits above the last two -- and it stays inside the window's
    /// side margins. Each bar must be on screen, so the comparison is against something real. Every
    /// frame comes from the one snapshot in which the notice was seen.
    @MainActor
    @discardableResult
    private func assertNoticeClearsNavigation(_ sighting: NoticeSighting) -> String {
        let frame = sighting.frame
        let placement = "notice=\(frame) window=\(String(describing: sighting.window))"
            + " navigation=\(String(describing: sighting.navigationBar)) tabs=\(String(describing: sighting.tabBar))"
            + " now-playing=\(String(describing: sighting.nowPlayingBar))"
            + " sighted-after=\(sighting.attempts) snapshot(s)"
        guard let window = sighting.window, let navigationBar = sighting.navigationBar,
              let tabBar = sighting.tabBar, let nowPlayingBar = sighting.nowPlayingBar else {
            XCTFail("The window, navigation bar, tab bar and now-playing bar must all be on screen; \(placement)")
            return placement
        }
        XCTAssertFalse(frame.intersects(navigationBar), "The notice covers the navigation bar; \(placement)")
        XCTAssertFalse(frame.intersects(tabBar), "The notice covers the tab bar; \(placement)")
        XCTAssertLessThanOrEqual(frame.maxY, nowPlayingBar.minY,
                                 "The notice must sit above the now-playing bar; \(placement)")
        XCTAssertLessThanOrEqual(frame.maxY, tabBar.minY, "The notice must sit above the tab bar; \(placement)")
        XCTAssertGreaterThanOrEqual(frame.minX - window.minX, 16, "The notice runs to the left edge; \(placement)")
        XCTAssertGreaterThanOrEqual(window.maxX - frame.maxX, 16, "The notice runs to the right edge; \(placement)")
        return placement
    }

    /// The server's play count for the one "Skip Probe" song with this title, read over `/rest`
    /// with the disposable server's own account. Read-only: nothing here can record a play.
    /// Fails -- nil, with a failure recorded -- on no match, several matches, or any error, so a
    /// read that cannot find its track is never mistaken for a track with no plays. The URL, which
    /// carries a token, is never printed.
    @MainActor
    private func serverPlayCount(title: String, configuration: LivePlaybackConfiguration) -> Int? {
        switch readServerPlayCount(title: title, album: "Skip Probe", configuration: configuration) {
        case let .success(count):
            return count
        case let .failure(reason):
            XCTFail("The server's play count for \(title) could not be read: \(reason.message)")
            return nil
        }
    }

    /// Polls until the count reaches `expected` or the timeout passes, and returns the last read.
    @MainActor
    private func awaitServerPlayCount(
        title: String,
        configuration: LivePlaybackConfiguration,
        expected: Int?,
        timeout: TimeInterval
    ) -> Int? {
        let deadline = Date().addingTimeInterval(timeout)
        var observed = serverPlayCount(title: title, configuration: configuration)
        while observed != nil, observed != expected, Date() < deadline {
            RunLoop.current.run(until: Date().addingTimeInterval(1))
            observed = serverPlayCount(title: title, configuration: configuration)
        }
        return observed
    }
    /// The iPad shell: Now Playing is not a sidebar place, and the now-playing bar opens the
    /// player over the whole window with Up Next beside it.
    ///
    /// Hardware-keyboard shortcuts are deliberately not asserted here. XCUITest's `typeKey`
    /// reached the app's shortcuts in one run on an iPadOS 26.5 simulator and not in the next,
    /// with the same sequence, so a keyboard assertion here would measure the harness.
    @MainActor
    func testIPadFullScreenPlayerFromTheBar() {
        guard requireSimulator(.pad, "The iPad full-screen player proof") else { return }
        guard let configuration = livePlaybackConfiguration() else { return }
        let app = XCUIApplication()
        app.launchArguments += [
            "-dulcet-debug-ui-markers",
            "-dulcet-debug-connect-account",
            "-dulcet-debug-account-server-url",
            configuration.serverURL,
            "-dulcet-debug-account-username",
            configuration.username,
            "-dulcet-debug-account-password",
            configuration.password,
        ]
        app.launch()

        let window = app.windows.firstMatch
        XCTAssertTrue(window.waitForExistence(timeout: 10), "The app window must exist")
        XCTAssertGreaterThan(
            window.frame.width,
            700,
            "This proof requires a regular-width iPad window; an iPhone is invalid evidence"
        )
        guard app.buttons["Sign Out"].firstMatch.waitForExistence(timeout: 30) else {
            XCTFail("The live account connection must succeed first")
            return
        }
        guard requireProofMarkers(in: app) else { return }
        guard openDestination("Library", sidebarIdentifier: "dulcet.sidebar.library", in: app, compact: false) else {
            return
        }
        XCTAssertFalse(
            app.staticTexts["dulcet.sidebar.nowPlaying"].exists,
            "Now Playing is presented from the bar on iPad, never listed as a sidebar place"
        )

        let album = app.buttons.matching(NSPredicate(
            format: "label CONTAINS %@ AND identifier != %@",
            "Threshold Boundary", "dulcet.mini-player.open"
        )).firstMatch
        guard album.waitForExistence(timeout: 30), scrollIntoView(album, in: app) else {
            XCTFail("The disposable server must expose the Threshold Boundary album")
            return
        }
        album.tap()
        let play = app.buttons["dulcet.album.play"].firstMatch
        guard play.waitForExistence(timeout: 10) else {
            XCTFail("The album page must offer Play")
            return
        }
        play.tap()

        // Playback must have started before the bar is used to open it.
        let playPause = app.buttons["dulcet.mini-player.play-pause"].firstMatch
        guard playPause.waitForExistence(timeout: 20),
              waitForLabel("Pause", of: playPause, timeout: 30) else {
            XCTFail("Playback must start from the album; play/pause reads \(playPause.label)")
            return
        }

        let bar = app.buttons["dulcet.mini-player.open"].firstMatch
        guard bar.waitForExistence(timeout: 5) else {
            XCTFail("The bar must be shown while the album plays")
            return
        }
        bar.tap()
        let title = app.staticTexts["dulcet.now-playing.title"].firstMatch
        guard title.waitForExistence(timeout: 10) else {
            XCTFail("The bar must open the player: " + app.debugDescription)
            return
        }
        XCTAssertEqual(title.label, "Twenty Nine Seconds")
        // Paused, so the track in front cannot end on its own while the layout is measured: the
        // fixture's tracks are about thirty seconds long, and a natural advance would make the
        // history steps below read a different queue position from the one they set up. OBSERVED
        // on an iPad Pro 13-inch simulator before this pause: the first Next landed on the third
        // track, because the second had already started.
        func playerButton(_ label: String) -> XCUIElement? {
            app.buttons.matching(NSPredicate(format: "label == %@", label))
                .allElementsBoundByIndex.first { $0.frame.width > 0 && $0.isHittable }
        }
        let playerPause = playerButton("Pause")
        XCTAssertNotNil(playerPause, "The full-screen player must offer Pause while the album plays")
        playerPause?.tap()
        // Re-queried by label: an element bound to the "Pause" query stops resolving once it
        // reads Play.
        let pauseDeadline = Date().addingTimeInterval(10)
        var paused = false
        while !paused, Date() < pauseDeadline {
            paused = playerButton("Play") != nil
            if !paused { Thread.sleep(forTimeInterval: 0.25) }
        }
        XCTAssertTrue(paused, "The player's Pause must pause")
        // Covering the window, not a detail column or a centred form sheet: the close control
        // sits at the window's leading edge, and the player's navigation bar spans the window.
        // A sheet on a regular-width window is inset on both sides, so neither holds for it.
        let close = app.buttons["dulcet.now-playing.close"].firstMatch
        let playerBar = app.navigationBars.containing(.button, identifier: "dulcet.now-playing.close").firstMatch
        XCTAssertTrue(close.waitForExistence(timeout: 5), "The player must offer a close control")
        let windowFrame = window.frame
        let closeFrame = close.frame
        let playerBarFrame = playerBar.exists ? playerBar.frame : .zero
        attachScreenshot(named: "ipad-full-screen-player", app: app)
        print("DULCET IPAD PLAYER FRAMES window=\(windowFrame) close=\(closeFrame)"
            + " player-navigation-bar=\(playerBarFrame) title=\(title.frame)")
        XCTAssertLessThan(closeFrame.minX - windowFrame.minX, 60,
                          "The close control must sit at the window's leading edge; close=\(closeFrame)")
        XCTAssertEqual(playerBarFrame.width, windowFrame.width, accuracy: 2,
                       "The player must span the whole window; its bar is \(playerBarFrame)")
        XCTAssertTrue(
            app.descendants(matching: .any)["dulcet.upNext"].firstMatch.waitForExistence(timeout: 5),
            "A regular-width player shows Up Next beside it"
        )
        XCTAssertFalse(
            app.buttons["dulcet.now-playing.up-next"].exists,
            "With Up Next already beside the player there is no toggle for it"
        )
        // Beside a regular-width player the queue column spans the player -- cover to footer --
        // and is centred with it. Its bottom is measured against the lowest thing drawn in the
        // player's own column, and its centre against the player's.
        let upNext = app.descendants(matching: .any)["dulcet.upNext"].firstMatch
        let queueFrame = upNext.frame
        // Only what is in front in the player's column, and never this test's own instrument: the
        // debug marker log is a text at the window's foot. OBSERVED on an iPad Pro 13-inch
        // simulator: counted as a player element, it put the "player's bottom" at y=1351 in a
        // 1376-point window, far below the footer.
        let playerColumn = (app.staticTexts.allElementsBoundByIndex + app.buttons.allElementsBoundByIndex)
            .filter {
                let frame = $0.frame
                return frame.width > 0 && frame.height > 0 && frame.maxX <= queueFrame.minX
                    && frame.minY >= title.frame.minY && windowFrame.contains(frame)
                    && $0.identifier != "dulcet.debug.ui-markers" && $0.isHittable
            }
        let lowest = playerColumn.max { $0.frame.maxY < $1.frame.maxY }
        let playerBottom = lowest?.frame.maxY ?? .nan
        print("DULCET IPAD QUEUE COLUMN queue=\(queueFrame) title=\(title.frame) player-bottom=\(playerBottom)"
            + " lowest=\((lowest?.identifier ?? "").debugDescription)/\((lowest?.label ?? "").debugDescription)"
            + " window=\(windowFrame) player-elements=\(playerColumn.count)")
        XCTAssertLessThan(queueFrame.height, windowFrame.height - 100,
                          "The queue column must not span the window; queue=\(queueFrame)")
        XCTAssertEqual(queueFrame.maxY, playerBottom, accuracy: 12,
                       "The queue column must end where the player does; queue=\(queueFrame) player-bottom=\(playerBottom)")
        XCTAssertLessThan(queueFrame.minY, title.frame.minY - 200,
                          "The queue column must rise beside the cover, above the title; queue=\(queueFrame)")

        // What has played is kept under Up Next, collapsed until asked for, nearest first, and a
        // row plays that track again. Two tracks forward, so the order is observable.
        // The player's own Next: the window also carries zero-size keyboard-shortcut buttons
        // with the same label, which a first match can land on.
        func playerNext() -> XCUIElement? {
            app.buttons.matching(NSPredicate(format: "label == %@", "Next Track"))
                .allElementsBoundByIndex.first { $0.frame.width > 0 && $0.isHittable }
        }
        XCTAssertNotNil(playerNext(), "The full-screen player must offer Next")
        playerNext()?.tap()
        let advanced = waitForLabel("Thirty One Seconds", of: title, timeout: 15)
        XCTAssertTrue(advanced, "Next must advance the player; title=\(title.label)")
        playerNext()?.tap()
        let advancedAgain = waitForLabel("UI Playback Canary", of: title, timeout: 15)
        XCTAssertTrue(advancedAgain, "A second Next must advance again; title=\(title.label)")
        let historyToggle = app.descendants(matching: .any)["dulcet.history.toggle"].firstMatch
        let historyShown = historyToggle.waitForExistence(timeout: 5)
        XCTAssertTrue(historyShown, "Played tracks must be listed under Previously Played")
        let firstRow = app.buttons["dulcet.history.row.0"].firstMatch
        let secondRow = app.buttons["dulcet.history.row.1"].firstMatch
        let collapsed = historyShown && !firstRow.exists && !secondRow.exists
        XCTAssertTrue(collapsed, "Previously Played must start collapsed")
        var historyRows: [String] = []
        var replayed = false
        var jumpMarkers: [String] = []
        if historyShown {
            historyToggle.tap()
            if firstRow.waitForExistence(timeout: 5), secondRow.waitForExistence(timeout: 5) {
                historyRows = [firstRow.label, secondRow.label]
            }
            XCTAssertTrue(historyRows.count == 2 && historyRows[0].hasPrefix("Thirty One Seconds")
                          && historyRows[1].hasPrefix("Twenty Nine Seconds"),
                          "The most recent track leads the history; rows=\(historyRows)")
            attachScreenshot(named: "ipad-full-screen-player-history", app: app)
            if secondRow.exists {
                let before = proofMarkers(in: app)
                secondRow.tap()
                jumpMarkers = waitForMarkers(after: before, ["history-jump:1"], in: app, timeout: 5)
                replayed = waitForLabel("Twenty Nine Seconds", of: title, timeout: 15)
            }
            XCTAssertEqual(jumpMarkers, ["history-jump:1"], "The history row's own handler must run")
            XCTAssertTrue(replayed, "A history row must play that track again; title=\(title.label)")
        }
        print("DULCET IPAD HISTORY OBSERVED advanced=\(advanced) advanced-again=\(advancedAgain)"
            + " history-shown=\(historyShown) collapsed=\(collapsed) rows=\(historyRows)"
            + " jump-markers=\(jumpMarkers) replayed=\(replayed)")
        close.tap()
        XCTAssertTrue(title.waitForNonExistence(timeout: 10), "Closing must dismiss the player")
        let albumTitle = app.staticTexts["dulcet.album.title"].firstMatch
        XCTAssertTrue(albumTitle.exists, "Closing returns to the album the player was opened from")

        // The sidebar keeps each destination as it was left: Library comes back on the album
        // after Search, and choosing Library while it is showing returns it to the grid.
        guard openDestination("Search", sidebarIdentifier: "dulcet.sidebar.search", in: app, compact: false),
              app.textFields["dulcet.search.field"].firstMatch.waitForExistence(timeout: 5) else {
            XCTFail("Search must be reachable from the sidebar")
            return
        }
        guard openDestination("Library", sidebarIdentifier: "dulcet.sidebar.library", in: app, compact: false) else {
            return
        }
        let albumKept = albumTitle.waitForExistence(timeout: 5)
        XCTAssertTrue(albumKept, "Library must come back on the album it was left on")
        guard openDestination("Library", sidebarIdentifier: "dulcet.sidebar.library", in: app, compact: false) else {
            return
        }
        let reselectPopped = albumTitle.waitForNonExistence(timeout: 5) && album.waitForExistence(timeout: 5)
        XCTAssertTrue(reselectPopped, "Choosing Library while it shows the album must return to the grid")
        print("DULCET IPAD PLAYER OBSERVED close-min-x=\(closeFrame.minX) player-width=\(playerBarFrame.width)"
            + " window-width=\(windowFrame.width) album-kept=\(albumKept) reselect-popped=\(reselectPopped)")
    }

    @MainActor
    private func proveSearchQueryRanksAndActivatesTrack(
        windowExpectation: SearchUIWindowExpectation
    ) {
        guard let configuration = livePlaybackConfiguration() else { return }
        // A query matching exactly one row cannot separate rank from arity: with a single result,
        // "rank zero" and "the only row" are the same assertion, and so are "activate the row that
        // was pressed" and "activate the first result". This query matches four rows and places
        // the canary at a non-zero rank, so both distinctions become observable.
        let query = "Threshold"
        let canaryTitle = "UI Playback Canary"
        let canaryRank = 2
        // The rendered order is a product contract, not a server one: results are ranked by match
        // quality first, then by kind with tracks ahead of albums, then by the order the server
        // returned them. The server lists the matching album ahead of every track; the app does
        // not. Asserting each rank's identity makes drift in either fail here, naming what it
        // observed, rather than silently relocating the canary to another rank.
        //
        // OBSERVED: this query matches four rows, the fourth being the matching album, and the
        // results list materializes its rows lazily -- so the album row is not addressable on a
        // compact window. These three ranks are asserted on every destination; the rendered
        // result count below pins the full arity without depending on an offscreen row, and the
        // macOS control, whose table renders every row at once, pins the album's rank.
        let rankedLabels = [
            "Thirty One Seconds, Dulcet Fixtures · Threshold Boundary, Track",
            "Twenty Nine Seconds, Dulcet Fixtures · Threshold Boundary, Track",
            "UI Playback Canary, Dulcet Fixtures · Threshold Boundary, Track",
        ]
        let renderedResultCount = "4 results"

        let app = XCUIApplication()
        app.launchArguments += [
            "-dulcet-debug-connect-account",
            "-dulcet-debug-account-server-url",
            configuration.serverURL,
            "-dulcet-debug-account-username",
            configuration.username,
            "-dulcet-debug-account-password",
            configuration.password,
        ]
        app.launch()

        let window = app.windows.firstMatch
        XCTAssertTrue(window.waitForExistence(timeout: 10), "The app window must exist")
        switch windowExpectation {
        case .compactWidth:
            XCTAssertLessThanOrEqual(
                window.frame.width,
                700,
                "This proof requires a compact-width iPhone window; an iPad is invalid evidence"
            )
        case .regularWidth:
            XCTAssertGreaterThan(
                window.frame.width,
                700,
                "This proof requires a regular-width iPad window; an iPhone is invalid evidence"
            )
        }

        guard app.buttons["Sign Out"].firstMatch.waitForExistence(timeout: 30) else {
            XCTFail("The live account connection must succeed before search is attempted")
            return
        }

        guard openDestination(
            "Search",
            sidebarIdentifier: "dulcet.sidebar.search",
            in: app,
            compact: windowExpectation == .compactWidth
        ) else { return }

        let searchField = app.textFields["dulcet.search.field"].firstMatch
        // MEASURED before the tab bar replaced the compact sidebar: 38 of 38 successful CI
        // executions resolved this on the first poll. Exhausting the budget means the Search
        // destination never rendered.
        guard searchField.waitForExistence(timeout: 5) else {
            XCTFail("The search field must exist on the Search destination: " + app.debugDescription)
            return
        }
        searchField.tap()
        searchField.typeText(query)
        // The field's value settles asynchronously, and `typeText` returning does not mean every
        // synthesized keystroke has been delivered and rendered -- XCUITest's own post-typing idle
        // wait is not that guarantee. Reading `.value` once therefore conflated "the field lost
        // characters" with "the sample was early", and one apple-ci run failed on the second.
        //
        // MEASURED on an iPad Pro 11-inch (M5) simulator with per-keystroke app cost induced at
        // 0, 20, 60, 150, 400 and 1000 ms, tracing the app's own text binding: all 9 keystrokes
        // reach it in order in every case, and across 84 keystrokes no value ever regressed. So a
        // short read is the sample being early. The 400 ms run reproduced the CI failure exactly
        // -- a prefix, everything after it passing -- while the trace showed the field completing
        // 3.8 s later.
        //
        // This polls the same assertion instead of sampling it. A field that genuinely drops
        // characters still fails, because the poll requires the full query and has an end; what it
        // no longer does is fail for being late. The attempt count is printed rather than
        // asserted: zero is the normal outcome, and a run that needed many is a responsiveness
        // signal worth seeing in the log rather than a reason to fail.
        //
        // Monotonic, per this repository's clock rule: a wall-clock deadline can be moved by the
        // host while the poll is running.
        //
        // The samples carry their own process check, matching the tvOS control in
        // DulcetTVUITests: an incremental commit produces non-shrinking prefixes of the query, so
        // a sample that is not a prefix, or one that goes backwards, is a different defect and
        // says so rather than being counted as "still settling".
        var settleSamples: [String] = []
        var observedFieldValue = searchField.value as? String ?? ""
        settleSamples.append(observedFieldValue)
        let settleClock = ContinuousClock()
        let settleDeadline = settleClock.now.advanced(by: .seconds(10))
        while observedFieldValue != query, settleClock.now < settleDeadline {
            Thread.sleep(forTimeInterval: 0.1)
            observedFieldValue = searchField.value as? String ?? ""
            settleSamples.append(observedFieldValue)
        }
        print("DULCET SEARCH TYPING OBSERVED settle-attempts=\(settleSamples.count - 1)"
            + " first=\(settleSamples[0].debugDescription) final=\(observedFieldValue.debugDescription)")
        for (index, sample) in settleSamples.enumerated() {
            XCTAssertTrue(
                query.hasPrefix(sample),
                "Sample \(index) was \(sample.debugDescription), which is not a prefix of the typed"
                    + " query: the field is not receiving this text one character at a time"
            )
            if index > 0 {
                XCTAssertGreaterThanOrEqual(
                    sample.count,
                    settleSamples[index - 1].count,
                    "The field's value went backwards between samples \(index - 1) and \(index)"
                )
            }
        }
        XCTAssertEqual(
            observedFieldValue,
            query,
            "The typed text must reach the search field's own value;"
                + " polled \(settleSamples.count - 1) times over 10 s"
        )

        let firstResult = app.buttons["dulcet.search.result.0"].firstMatch
        // The check above reads the text field, and MEASURED: with the field's binding replaced
        // by a constant empty string -- so the app can never hold the query -- it still reported
        // "Threshold" and passed, and this wait is where the run failed instead. A text field
        // being edited reports what is on screen, not what the app's state received, so this is
        // the first assertion that depends on the query having actually reached the app. Its
        // message must therefore not blame the server for a query that never arrived.
        guard firstResult.waitForExistence(timeout: 30) else {
            XCTFail(
                "No ranked results for the typed query. Either the query never reached the app's"
                    + " state -- the field's own value is not proof that it did -- or the"
                    + " disposable server did not answer: " + app.debugDescription
            )
            return
        }
        // A person dismisses the software keyboard before activating a result; the test must do
        // the same, because the occluding keyboard window eats the hit test. Measured on iPhone
        // 17 Pro: the rank-zero row's midpoint sat at y=500 under a keyboard whose top edge was
        // y=472, so tapping without dismissing is not a real activation path.
        guard dismissKeyboardBeforeActivation(in: app) else { return }

        // Every rank is addressed by its own identifier and checked against the row that belongs
        // there. A view that stamped one constant identifier on every row would satisfy rank zero
        // and then fail to produce rank one at all.
        var rankedResults: [XCUIElement] = []
        for rank in rankedLabels.indices {
            let result = app.buttons["dulcet.search.result.\(rank)"].firstMatch
            guard result.waitForExistence(timeout: 10) else {
                XCTFail("Rank \(rank) must render its own identifier: " + app.debugDescription)
                return
            }
            XCTAssertEqual(
                result.label,
                rankedLabels[rank],
                "Rank \(rank) rendered accessibility text (title, credits, album, kind)"
            )
            rankedResults.append(result)
        }
        // Captured while the rows are still on screen: after activation the search surface is
        // replaced and re-reading these elements throws rather than returning a stale value.
        let observedLabels = rankedResults.map(\.label)
        XCTAssertNotEqual(
            rankedResults[0].label,
            rankedLabels[canaryRank],
            "The canary must not render at rank zero, or this proof cannot tell rank from arity"
        )
        // The app's own count of the ranked list. Asserting it keeps the arity pinned even
        // though the list renders lazily, so a query that starts matching a different number of
        // rows fails here instead of quietly changing which rank the canary occupies.
        XCTAssertTrue(
            app.staticTexts[renderedResultCount].firstMatch.exists,
            "The results header must report \(renderedResultCount): " + app.debugDescription
        )

        // Scroll the results container only when the canary is outside the visible window.
        // The legacy letterboxed iPhone canvas needed this; a full-display iPhone may expose
        // every row already. Keep the reachability check for smaller windows and keyboards,
        // and preserve the rank assertions above independently of any scrolling.
        let canaryResult = rankedResults[canaryRank]
        let resultsList = app.scrollViews.firstMatch
        guard resultsList.waitForExistence(timeout: 5) else {
            XCTFail("The ranked results must render in a scrollable list: " + app.debugDescription)
            return
        }
        var scrollAttempts = 0
        while scrollAttempts < 6 && !isReachableForTap(canaryResult, in: window) {
            resultsList.swipeUp()
            scrollAttempts += 1
        }

        guard canaryResult.isHittable else {
            // Frame diagnostics make an occlusion report actionable from the CI log alone:
            // a covered row and an offscreen row are different defects with the same symptom.
            let keyboard = app.keyboards.firstMatch
            print(
                "DULCET SEARCH UI DIAG result-frame=\(canaryResult.frame)"
                    + " window-frame=\(window.frame)"
                    + " keyboard-exists=\(keyboard.exists)"
                    + " keyboard-frame=\(keyboard.exists ? String(describing: keyboard.frame) : "none")"
            )
            XCTFail("The rank-\(canaryRank) result must be hittable so activation is a real tap")
            return
        }

        canaryResult.tap()

        // Activation plays without leaving the results; the bar is the way to the full player
        // (a sheet on the iPhone, the Now Playing destination on the iPad).
        guard openNowPlayingFromBar(in: app, expectingTitle: canaryTitle) else { return }

        let nowPlayingTitle = app.staticTexts["dulcet.now-playing.title"].firstMatch
        // Successful CI executions resolve this in one to four polls of the fifteen available,
        // so exhausting the budget means activation produced no navigation at all -- which the
        // tree below distinguishes from "Now Playing rendered a state without a title".
        guard nowPlayingTitle.waitForExistence(timeout: 15) else {
            XCTFail(
                "Activating rank \(canaryRank) must present the Now Playing surface: "
                    + app.debugDescription
            )
            return
        }
        // Now Playing showing rank zero's track here would mean activation played the first
        // result rather than the row that was pressed. That is why a non-zero rank is pressed.
        XCTAssertEqual(
            nowPlayingTitle.label,
            canaryTitle,
            "Now Playing must show the row that was activated, not the first result"
        )
        XCTAssertTrue(
            app.staticTexts["Playing from Search"].firstMatch.waitForExistence(timeout: 5),
            "The Now Playing source line must report the search-sourced queue"
        )
        XCTAssertTrue(
            app.sliders["Now Playing"].firstMatch.waitForExistence(timeout: 30),
            "Real playback of the activated track must begin and expose progressing media time"
        )
        print(
            "DULCET SEARCH UI PASS width=\(windowExpectation == .compactWidth ? "compact" : "regular")"
                + " query=typed ranks=\(observedLabels)"
                + " activated-rank=\(canaryRank) activation=tap source=search now-playing=\(canaryTitle)"
        )
    }

    /// Proves the iPad renders the regular-width split: sidebar and detail visible at once, in
    /// separate columns. An iPhone cannot satisfy this, which is the point -- a test that passed on
    /// both would let the iPadOS cell claim evidence it does not have.
    @MainActor
    func testAccountConnectUsesRegularWidthSplitLayout() {
        let app = XCUIApplication()
        app.launchArguments.append("-dulcet-account-connect-layout-fixture")
        app.launch()
        print("DULCET IPADOS APP LAUNCH PASS layout-assertions-starting=true")

        // Guard on window width, not XCUIApplication.horizontalSizeClass: that attribute reports
        // .unspecified for the application element (OBSERVED on iPad Pro 13-inch, rawValue 0), so
        // asserting .regular against it fails on the very device it is meant to accept. A regular
        // split needs a window far wider than any iPhone.
        let window = app.windows.firstMatch
        XCTAssertTrue(window.waitForExistence(timeout: 10), "The app window must exist")
        XCTAssertGreaterThan(
            window.frame.width,
            700,
            "This proof requires a regular-width window; an iPhone destination is invalid evidence"
        )

        // staticTexts, not descendants(matching: .any): the row's identifier is carried by BOTH its
        // SF Symbol image and its label (OBSERVED in the captured hierarchy), so an .any query is
        // ambiguous and resolves against the decorative image, whose isHittable is false.
        let sidebar = app.staticTexts["dulcet.sidebar.library"].firstMatch
        let detail = app.staticTexts["dulcet.account-connect.title"].firstMatch

        XCTAssertTrue(sidebar.waitForExistence(timeout: 10), "The Library row must be in the sidebar")
        XCTAssertTrue(detail.waitForExistence(timeout: 10), "The account-connect heading must be in the detail column")
        XCTAssertTrue(sidebar.isHittable, "The sidebar must be visible, not retained offscreen")
        XCTAssertTrue(detail.isHittable, "The detail must be visible at the same time as the sidebar")
        XCTAssertLessThan(
            sidebar.frame.maxX,
            detail.frame.minX,
            "Regular-width layout must place the sidebar and detail in separate visible columns"
        )
    }

    /// Device-only evidence that the production account, library, stream, audio engine, and
    /// progressing-media-time scrobble path can be driven through the iPad UI. The server-side
    /// play-count assertion intentionally remains outside XCUITest so the app remains the only
    /// playback actor and the test cannot independently submit a scrobble.
    @MainActor
    func testRealDevicePlaybackAdvancesPastScrobbleThreshold() {
        guard ProcessInfo.processInfo.environment["SIMULATOR_UDID"] == nil else {
            XCTFail("This playback proof requires a physical iPad; a simulator is not valid evidence")
            return
        }

        proveLivePlaybackAdvancesPastScrobbleThreshold()
    }

    /// Simulator-only evidence for the same live account, library, stream, audio engine, and
    /// progressing-media-time scrobble path exercised by the physical-iPad proof.
    @MainActor
    func testSimulatorPlaybackAdvancesPastScrobbleThreshold() {
        guard ProcessInfo.processInfo.environment["SIMULATOR_UDID"] != nil else {
            XCTFail("This playback proof requires an iPad simulator; a physical device is not valid evidence")
            return
        }

        proveLivePlaybackAdvancesPastScrobbleThreshold(usingInjectedAccount: true)
    }

    @MainActor
    private func proveLivePlaybackAdvancesPastScrobbleThreshold(
        usingInjectedAccount: Bool = false
    ) {
        guard let configuration = livePlaybackConfiguration() else { return }

        let app = XCUIApplication()
        // The app's DEBUG delivery marker. The Now Playing slider shows the threshold; nothing in
        // the product shows delivery, and delivery is the event the workflow's play-count read
        // depends on. Without it this proof twice returned on the displayed threshold while the
        // app's own accumulator (counted from its first sampled position) had not crossed, and
        // XCUITest's teardown killed the app before its next sample (apple-ci 2026-09-06 and
        // 2026-09-11: NowPlaying logged, no Scrobbled line, count stayed 0, test green).
        app.launchArguments += ["-dulcet-debug-scrobble-delivery-marker"]
        if usingInjectedAccount {
            app.launchArguments += [
                "-dulcet-debug-connect-account",
                "-dulcet-debug-account-server-url",
                configuration.serverURL,
                "-dulcet-debug-account-username",
                configuration.username,
                "-dulcet-debug-account-password",
                configuration.password,
            ]
        } else {
            installSystemAlertInterruptionMonitors()
        }
        app.launch()

        let window = app.windows.firstMatch
        XCTAssertTrue(window.waitForExistence(timeout: 10), "The app window must exist")
        XCTAssertGreaterThan(
            window.frame.width,
            700,
            "This playback proof requires a regular-width iPad window; an iPhone is invalid evidence"
        )

        if !usingInjectedAccount {
            let serverField = app.textFields["dulcet.account-connect.server-address"].firstMatch
            let usernameField = app.textFields["dulcet.account-connect.username"].firstMatch
            let passwordField = app.secureTextFields["dulcet.account-connect.password"].firstMatch
            guard replaceText(in: serverField, with: configuration.serverURL, name: "server address"),
                  replaceText(in: usernameField, with: configuration.username, name: "username"),
                  replaceText(
                    in: passwordField,
                    with: configuration.password,
                    name: "password",
                    secure: true
                  ) else { return }
            guard dismissKeyboardIfPresent(in: app) else { return }

            if configuration.serverURL.lowercased().hasPrefix("http://") {
                // The physical-device accessibility service describes this as Button, Toggle,
                // while XCUITest exposes neither a Button nor a Switch. Match its semantic label
                // and binary value across element types, then guard uniqueness so a decorative
                // child cannot win.
                let localHTTPControls = app.descendants(matching: .any).matching(
                    NSPredicate(
                        format: "label == %@ AND (value == %@ OR value == %@)",
                        "Allow HTTP on this local network",
                        "0",
                        "1"
                    )
                )
                let allowLocalHTTP = localHTTPControls.firstMatch
                guard allowLocalHTTP.waitForExistence(timeout: 5) else {
                    XCTFail("The local-HTTP consent control must exist for a cleartext disposable server")
                    return
                }
                XCTAssertEqual(
                    localHTTPControls.count,
                    1,
                    "The local-HTTP consent query must resolve to exactly one semantic control"
                )
                guard scrollIntoView(allowLocalHTTP, in: app) else {
                    XCTFail("The local-HTTP consent control must be hittable before it is changed")
                    return
                }
                if (allowLocalHTTP.value as? String) != "1" {
                    allowLocalHTTP.coordinate(
                        withNormalizedOffset: CGVector(dx: 0.9, dy: 0.5)
                    ).tap()
                }
                guard waitForValue("1", of: allowLocalHTTP, timeout: 5) else {
                    XCTFail("The local-HTTP consent control must visibly change from off to on")
                    return
                }
            }

            let connectAction = app.buttons["dulcet.account-connect.primary-action"].firstMatch
            guard connectAction.waitForExistence(timeout: 5) else {
                XCTFail("The account primary action must exist")
                return
            }
            guard connectAction.isEnabled else {
                XCTFail("The account primary action must enable after all required values are entered")
                return
            }
            connectAction.tap()
            allowLocalNetworkAccessIfRequested()
        }

        guard app.buttons["Sign Out"].firstMatch.waitForExistence(timeout: 30) else {
            XCTFail("The live account connection must succeed before playback is attempted")
            return
        }

        // Negative control, before any playback: the marker must be live and read three zeros,
        // each excluding a different way a later "delivered=1" could be credited to the wrong
        // event. The counts are per process; only `pending` reads the durable outbox.
        //   delivered=0  this launch has not yet delivered anything (a leftover row drained on
        //                configureDelivery would already show here);
        //   pending=0    no row survives from an earlier launch on a reused simulator, so a
        //                drain that has not happened yet cannot supply the 1 either;
        //   persisted=0  this launch has not itself persisted a play (a crossing before the
        //                account settled, or a restored session, would show here).
        let deliveryMarker = app.staticTexts["dulcet.debug.scrobble-delivery"].firstMatch
        guard deliveryMarker.waitForExistence(timeout: 10) else {
            XCTFail("The app's scrobble delivery marker must exist when its launch argument is passed")
            return
        }
        guard let baseline = waitForScrobbleDeliveryCounts(
            in: deliveryMarker,
            timeout: 10,
            until: { $0["delivered"] != nil }
        ) else {
            XCTFail("The scrobble delivery marker must report counts; last label: \(deliveryMarker.label)")
            return
        }
        XCTAssertEqual(baseline["delivered"], 0, "No play may be delivered before playback starts")
        XCTAssertEqual(baseline["pending"], 0, "No play may be waiting in the outbox from an earlier launch")
        XCTAssertEqual(baseline["persisted"], 0, "No play may be persisted by this launch before playback")
        guard baseline["delivered"] == 0, baseline["pending"] == 0, baseline["persisted"] == 0 else {
            return
        }

        guard openDestination(
            "Library",
            sidebarIdentifier: "dulcet.sidebar.library",
            in: app,
            compact: false
        ) else { return }

        // A queue restored from an earlier run on this simulator puts the canary in the
        // now-playing bar at launch, and the bar's label names the track. Every label query here
        // excludes the bar, or it resolves to the bar and opens the player instead of the album.
        let thresholdAlbum = app.buttons.matching(
            NSPredicate(format: "label CONTAINS %@ AND identifier != %@",
                        "Threshold Boundary", "dulcet.mini-player.open")
        ).firstMatch
        guard thresholdAlbum.waitForExistence(timeout: 30) else {
            XCTFail("The disposable server must expose the Threshold Boundary album")
            return
        }
        guard scrollIntoView(
            thresholdAlbum,
            in: app,
            probingBlockingSystemAlerts: !usingInjectedAccount
        ) else {
            XCTFail("The threshold canary album must be reachable in the library")
            return
        }
        thresholdAlbum.tap()

        let thresholdTrack = app.buttons.matching(
            NSPredicate(format: "label CONTAINS %@ AND identifier != %@",
                        "UI Playback Canary", "dulcet.mini-player.open")
        ).firstMatch
        guard thresholdTrack.waitForExistence(timeout: 10) else {
            XCTFail("The disposable server must expose the dedicated eligible UI playback canary")
            return
        }
        guard scrollIntoView(
            thresholdTrack,
            in: app,
            probingBlockingSystemAlerts: !usingInjectedAccount
        ) else {
            XCTFail("The scrobble canary track must be reachable")
            return
        }
        thresholdTrack.tap()
        guard openNowPlayingFromBar(in: app, expectingTitle: "UI Playback Canary") else { return }

        let progress = app.sliders["Now Playing"].firstMatch
        guard progress.waitForExistence(timeout: 30) else {
            XCTFail("Real playback must begin and expose progressing media time in Now Playing")
            return
        }
        guard let finalSample = waitUntilPastScrobbleThreshold(progress) else { return }
        let threshold = min(finalSample.duration * 0.5, 4 * 60)
        XCTAssertGreaterThanOrEqual(
            finalSample.duration,
            30,
            "The server-reported or decoded duration must be eligible for scrobbling"
        )
        XCTAssertGreaterThan(
            finalSample.elapsed,
            threshold,
            "Observed progressing media time must move past the §15.2 scrobble threshold"
        )

        // The displayed position and the accumulator are different quantities with half a second
        // between them on this track, so the threshold is necessary, never sufficient. Return only
        // once the app reports the server acknowledged `submission=true`; XCUITest kills the app
        // when this method returns, and a play that has not left by then never leaves in CI.
        // The budget covers the next 500 ms position sample, the request, and a loaded host; it
        // is not a retry window, because the facade schedules no in-process retry.
        guard let delivered = waitForScrobbleDeliveryCounts(
            in: deliveryMarker,
            timeout: 30,
            until: { ($0["delivered"] ?? 0) >= 1 }
        ) else {
            XCTFail(
                "The app must report the scrobble delivered before this proof returns; last marker: \(deliveryMarker.label)"
            )
            return
        }
        XCTAssertEqual(delivered["delivered"], 1, "Exactly one play is expected for one crossing")
        XCTAssertEqual(delivered["failures"], 0, "No delivery attempt may have failed")
    }

    private func livePlaybackConfiguration() -> LivePlaybackConfiguration? {
        let serverURL = runtimeValue(
            environment: "DULCET_UI_TEST_SERVER_URL",
            argument: "-dulcet-ui-test-server-url"
        )
        let username = runtimeValue(
            environment: "DULCET_UI_TEST_USERNAME",
            argument: "-dulcet-ui-test-username"
        )
        let password = runtimeValue(
            environment: "DULCET_UI_TEST_PASSWORD",
            argument: "-dulcet-ui-test-password"
        )

        if serverURL == nil {
            XCTFail("Missing server URL; set DULCET_UI_TEST_SERVER_URL or -dulcet-ui-test-server-url")
        }
        if username == nil {
            XCTFail("Missing username; set DULCET_UI_TEST_USERNAME or -dulcet-ui-test-username")
        }
        if password == nil {
            XCTFail("Missing password; set DULCET_UI_TEST_PASSWORD or -dulcet-ui-test-password")
        }
        guard let serverURL, let username, let password else { return nil }
        return LivePlaybackConfiguration(serverURL: serverURL, username: username, password: password)
    }

    private func runtimeValue(environment key: String, argument flag: String) -> String? {
        let environmentValue = ProcessInfo.processInfo.environment[key]?
            .trimmingCharacters(in: .whitespacesAndNewlines)
        if let environmentValue, !environmentValue.isEmpty {
            return environmentValue
        }

        let arguments = ProcessInfo.processInfo.arguments
        guard let flagIndex = arguments.firstIndex(of: flag),
              arguments.indices.contains(flagIndex + 1) else { return nil }
        let argumentValue = arguments[flagIndex + 1].trimmingCharacters(in: .whitespacesAndNewlines)
        return argumentValue.isEmpty ? nil : argumentValue
    }

    @MainActor
    private func replaceText(
        in field: XCUIElement,
        with value: String,
        name: String,
        secure: Bool = false
    ) -> Bool {
        guard field.waitForExistence(timeout: 5) else {
            XCTFail("The \(name) field must exist")
            return false
        }
        field.tap()
        field.typeKey("a", modifierFlags: .command)
        field.typeText(value)

        // URL fields can occasionally preserve a stale insertion point across app relaunches on
        // physical iPadOS. Verify the resulting value and make one fresh selection/replacement.
        if !secure, (field.value as? String) != value {
            field.tap()
            field.typeKey("a", modifierFlags: .command)
            field.typeKey(.delete, modifierFlags: [])
            field.typeText(value)
        }

        let enteredValue = field.value as? String
        let didEnterValue = secure ? enteredValue?.count == value.count : enteredValue == value
        guard didEnterValue else {
            XCTFail("The \(name) field must contain exactly the supplied runtime value")
            return false
        }
        return true
    }

    @MainActor
    private func dismissKeyboardIfPresent(in app: XCUIApplication) -> Bool {
        let keyboard = app.keyboards.firstMatch
        guard keyboard.exists else { return true }
        let hideKeyboard = keyboard.buttons["Hide keyboard"].firstMatch
        if hideKeyboard.waitForExistence(timeout: 2) {
            hideKeyboard.tap()
        } else {
            keyboard.coordinate(withNormalizedOffset: CGVector(dx: 0.97, dy: 0.95)).tap()
        }
        guard keyboard.waitForNonExistence(timeout: 5) else {
            XCTFail("The software keyboard must dismiss before the account controls are driven")
            return false
        }
        return true
    }

    @MainActor
    private func dismissKeyboardBeforeActivation(in app: XCUIApplication) -> Bool {
        let keyboard = app.keyboards.firstMatch
        guard keyboard.exists else { return true }
        // The search field carries the platform's search submit key, which resigns the field on
        // both iPhone and iPad. The iPad-only hide key and a results drag remain fallbacks.
        let submit = keyboard.buttons["Search"].firstMatch
        let hideKeyboard = keyboard.buttons["Hide keyboard"].firstMatch
        if submit.waitForExistence(timeout: 2) {
            submit.tap()
        } else if hideKeyboard.waitForExistence(timeout: 2) {
            hideKeyboard.tap()
        } else {
            app.swipeDown()
        }
        guard keyboard.waitForNonExistence(timeout: 5) else {
            XCTFail("The software keyboard must dismiss before a result is activated")
            return false
        }
        return true
    }

    /// A row is reachable when the hit point XCUITest would use for a tap -- its midpoint -- is
    /// inside the window, not merely when the element exists. A row that begins on screen and
    /// extends past the bottom edge satisfies `exists` while its midpoint does not.
    @MainActor
    private func isReachableForTap(_ element: XCUIElement, in window: XCUIElement) -> Bool {
        guard element.exists, window.exists else { return false }
        let frame = element.frame
        guard !frame.isEmpty, !frame.isInfinite else { return false }
        return window.frame.contains(CGPoint(x: frame.midX, y: frame.midY)) && element.isHittable
    }

    @MainActor
    private func scrollIntoView(
        _ element: XCUIElement,
        in app: XCUIApplication,
        probingBlockingSystemAlerts: Bool = true
    ) -> Bool {
        let window = app.windows.firstMatch
        guard window.exists else {
            XCTFail("Dulcet's app window is unavailable because the app terminated or was backgrounded")
            return false
        }

        var swipeCount = 0
        while true {
            if probingBlockingSystemAlerts {
                let springboard = XCUIApplication(bundleIdentifier: "com.apple.springboard")
                // An application-level swipe always resolves a hit point, even while SpringBoard
                // is presenting an alert, so XCTest has no blocked action with which to invoke an
                // interruption monitor. Alert handling does not consume a scroll attempt.
                switch dismissBlockingSystemAlertIfPresent(in: springboard) {
                case .handled:
                    continue
                case .unsupported:
                    return false
                case .absent:
                    break
                }
            }

            if element.exists {
                let frame = element.frame
                let midpoint = CGPoint(x: frame.midX, y: frame.midY)
                if !frame.isEmpty && !frame.isInfinite &&
                    window.frame.contains(midpoint) && element.isHittable {
                    return true
                }
            }

            guard swipeCount < 6 else { return false }

            // This must remain a real event rather than an isHittable-gated preflight. Tap paths
            // can still invoke the interruption monitors installed as a backstop.
            app.swipeUp()
            swipeCount += 1
            RunLoop.current.run(until: Date().addingTimeInterval(0.25))
        }
    }

    @MainActor
    private func dismissBlockingSystemAlertIfPresent(
        in springboard: XCUIApplication
    ) -> BlockingSystemDialogProbeResult {
        // The password-save surface is not exposed as a SpringBoard alert on every runtime. Its
        // dismissal buttons are exposed directly on the SpringBoard process, however. Query only
        // known decline labels: falling back to an arbitrary SpringBoard button can background the
        // app or drive unrelated system UI.
        let knownDeclineLabels = ["Not Now", "Never", "No Thanks", "Don't Save", "Don’t Save"]
        let knownDecline = springboard.buttons.matching(
            NSPredicate(format: "label IN %@", knownDeclineLabels)
        ).firstMatch
        if knownDecline.waitForExistence(timeout: 3) {
            let title = knownDecline.label
            knownDecline.tap()
            print("DULCET_UI_SYSTEM_ALERT handled=proactive button=\(title)")
            return .handled
        }

        // A changed button label must not collapse into the same result as no dialog. The dialog's
        // password copy is independent evidence that the blocking surface exists; if it is present,
        // preserve the surface, report every observed button label, and fail at the probe site.
        let passwordCopy = springboard.staticTexts.matching(
            NSPredicate(format: "label CONTAINS[c] %@", "password")
        )
        guard passwordCopy.count > 0 else { return .absent }

        let labels = observedButtonLabels(in: springboard)
        print("DULCET_UI_SYSTEM_ALERT handled=unsupported buttons=\(labels)")
        XCTFail(
            "Unsupported blocking password dialog; observed SpringBoard buttons: \(labels)"
        )
        return .unsupported
    }

    @MainActor
    private func waitForValue(
        _ expectedValue: String,
        of element: XCUIElement,
        timeout: TimeInterval
    ) -> Bool {
        let deadline = Date().addingTimeInterval(timeout)
        while Date() < deadline {
            if (element.value as? String) == expectedValue {
                return true
            }
            RunLoop.current.run(until: Date().addingTimeInterval(0.1))
        }
        return (element.value as? String) == expectedValue
    }

    @MainActor
    private func allowLocalNetworkAccessIfRequested() {
        let springboard = XCUIApplication(bundleIdentifier: "com.apple.springboard")
        let allow = springboard.buttons["Allow"].firstMatch
        if allow.waitForExistence(timeout: 3) {
            allow.tap()
        }
    }

    @MainActor
    private func installSystemAlertInterruptionMonitors() {
        let springboard = XCUIApplication(bundleIdentifier: "com.apple.springboard")

        // Monitors run in reverse installation order. Keep this general fallback first so the
        // expected-response monitor below gets the first opportunity to preserve test intent.
        _ = addUIInterruptionMonitor(withDescription: "Dismiss an unexpected system alert") { _ in
            for title in ["Cancel", "Close", "Dismiss", "Not Now", "OK"] {
                let button = springboard.buttons[title].firstMatch
                if button.exists {
                    button.tap()
                    print("DULCET_UI_INTERRUPTION handled=general button=\(title)")
                    return true
                }
            }

            let labels = self.observedButtonLabels(in: springboard)
            print("DULCET_UI_INTERRUPTION handled=unsupported buttons=\(labels)")
            XCTFail("Unsupported system interruption; observed SpringBoard buttons: \(labels)")
            return false
        }

        _ = addUIInterruptionMonitor(withDescription: "Handle expected system alerts") { _ in
            for title in ["Not Now", "Allow"] {
                let button = springboard.buttons[title].firstMatch
                if button.exists {
                    button.tap()
                    print("DULCET_UI_INTERRUPTION handled=expected button=\(title)")
                    return true
                }
            }
            return false
        }
    }

    @MainActor
    private func observedButtonLabels(in application: XCUIApplication) -> [String] {
        application.buttons.allElementsBoundByIndex.map { button in
            button.label.isEmpty ? "<empty>" : button.label
        }
    }

    /// Polls the delivery marker's label (`dulcet-scrobble persisted=N delivered=N ...`) until
    /// `until` accepts the parsed counts, returning nil at the deadline. A label without counts
    /// (the app has not yet installed its observer) never satisfies a predicate.
    @MainActor
    private func waitForScrobbleDeliveryCounts(
        in marker: XCUIElement,
        timeout: TimeInterval,
        until accepted: ([String: Int]) -> Bool
    ) -> [String: Int]? {
        let deadline = Date().addingTimeInterval(timeout)
        while Date() < deadline {
            if let counts = scrobbleDeliveryCounts(from: marker.label), accepted(counts) {
                return counts
            }
            RunLoop.current.run(until: Date().addingTimeInterval(0.25))
        }
        if let counts = scrobbleDeliveryCounts(from: marker.label), accepted(counts) {
            return counts
        }
        return nil
    }

    private func scrobbleDeliveryCounts(from label: String) -> [String: Int]? {
        let words = label.split(separator: " ")
        guard words.first == "dulcet-scrobble" else { return nil }
        var counts: [String: Int] = [:]
        for word in words.dropFirst() {
            let pair = word.split(separator: "=", maxSplits: 1)
            guard pair.count == 2, let value = Int(pair[1]) else { return nil }
            counts[String(pair[0])] = value
        }
        return counts.isEmpty ? nil : counts
    }

    @MainActor
    private func waitUntilPastScrobbleThreshold(
        _ progress: XCUIElement,
        timeout: TimeInterval = 90
    ) -> PlaybackProgressSample? {
        let deadline = Date().addingTimeInterval(timeout)
        var lastSample: PlaybackProgressSample?
        var observedIncrease = false

        while Date() < deadline {
            if let value = progress.value as? String,
               let sample = playbackProgressSample(from: value) {
                if let previous = lastSample, sample.elapsed > previous.elapsed {
                    observedIncrease = true
                }
                lastSample = sample

                // §15.2: eligible at min(50% of media duration, four minutes), with duration >= 30s.
                // Waiting for the next whole displayed second proves media time moved beyond, not
                // merely onto, the fractional threshold and leaves delivery a progressing update.
                let threshold = min(sample.duration * 0.5, 4 * 60)
                let pastThreshold = floor(threshold) + 1
                if sample.duration >= 30,
                   observedIncrease,
                   sample.elapsed >= pastThreshold {
                    return sample
                }
            }
            RunLoop.current.run(until: Date().addingTimeInterval(0.5))
        }

        let lastValue = lastSample?.accessibilityValue ?? String(describing: progress.value)
        XCTFail(
            "Playback media time did not progress past the §15.2 scrobble threshold; last value: \(lastValue)"
        )
        return nil
    }

    private func playbackProgressSample(from value: String) -> PlaybackProgressSample? {
        let components = value.components(separatedBy: " of ")
        guard components.count == 2,
              let elapsed = clockSeconds(components[0]),
              let duration = clockSeconds(components[1]) else { return nil }
        return PlaybackProgressSample(
            elapsed: elapsed,
            duration: duration,
            accessibilityValue: value
        )
    }

    private func clockSeconds(_ value: String) -> TimeInterval? {
        let components = value.split(separator: ":").compactMap { TimeInterval($0) }
        switch components.count {
        case 2:
            return components[0] * 60 + components[1]
        case 3:
            return components[0] * 3_600 + components[1] * 60 + components[2]
        default:
            return nil
        }
    }
}
