import CryptoKit
import UIKit
import XCTest

final class DulcetiOSUITests: XCTestCase {
    /// Server cleanup a test registers once it has made something on the disposable server; run
    /// in reverse order after the test, pass or fail. `addTeardownBlock` would do the same, but it
    /// comes from XCTest's Swift overlay, which `tools/typecheck-xcuitest-sources` cannot resolve.
    private var afterTest: [() -> Void] = []

    override func tearDown() {
        let cleanups = afterTest
        afterTest = []
        cleanups.reversed().forEach { $0() }
        super.tearDown()
    }

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

    /// The experiment this proof needs, asserted rather than assumed (docs/TRAPS.md traps 31 and 32):
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

    /// Opens an album from the library the way a person finds one by name: the Recently Added
    /// grid under a phone's Library list, or Albums in a regular window's sidebar.
    @MainActor
    private func openLibraryAlbum(_ album: String, in app: XCUIApplication, compact: Bool) -> Bool {
        guard openDestination("Library", sidebarIdentifier: "dulcet.sidebar.library", in: app, compact: compact) else {
            return false
        }
        if !compact, !openSidebarLibrarySection("albums", in: app) { return false }
        let tile = app.buttons.matching(identifier: "dulcet.library.album")
            .matching(NSPredicate(format: "label BEGINSWITH %@", album)).firstMatch
        // A phone's Recently Added grid is extended as it scrolls, newest first, so an older album
        // is reached by scrolling down to it (OBSERVED on CI: eight newer fixture albums filled the
        // first window and the oldest, Dulcet Conformance, was not yet in the grid).
        var swipes = 0
        while compact, !tile.waitForExistence(timeout: swipes == 0 ? 30 : 3), swipes < 12 {
            app.swipeUp()
            swipes += 1
        }
        // A regular window's Albums list has not been waited on above. OBSERVED on iPad in main's
        // run 37188677065 (rerun): straight after a fresh connection the list held no album yet,
        // and a 1-second wait failed the proof with no album tile in the hierarchy at all.
        guard tile.waitForExistence(timeout: compact ? 1 : 30), scrollIntoView(tile, in: app) else {
            XCTFail("The library must show the \(album) tile: " + app.debugDescription)
            return false
        }
        tile.tap()
        let title = app.staticTexts["dulcet.album.title"].firstMatch
        guard title.waitForExistence(timeout: 10), title.label == album else {
            XCTFail("The tile must open \(album): " + app.debugDescription)
            return false
        }
        return true
    }

    /// Whether the server holds the named album as a favourite, read over `/rest/search3` with the
    /// disposable account. Nil, with a failure recorded, on any error or on no single match.
    @MainActor
    private func readServerAlbumStarred(_ album: String, configuration: LivePlaybackConfiguration) -> Bool? {
        let salt = (0..<16).map { _ in String(format: "%02x", UInt8.random(in: 0...255)) }.joined()
        let token = Insecure.MD5.hash(data: Data((configuration.password + salt).utf8))
            .map { String(format: "%02x", $0) }.joined()
        guard var components = URLComponents(string: configuration.serverURL) else {
            XCTFail("The server URL is malformed (withheld)")
            return nil
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
            URLQueryItem(name: "query", value: album),
            URLQueryItem(name: "songCount", value: "0"),
            URLQueryItem(name: "albumCount", value: "20"),
            URLQueryItem(name: "artistCount", value: "0"),
        ]
        guard let url = components.url else {
            XCTFail("The request URL could not be built (withheld)")
            return nil
        }
        /// Written once by the completion handler, read after the semaphore it signals.
        final class Outcome: @unchecked Sendable {
            var data: Data?
        }
        let outcome = Outcome()
        let done = DispatchSemaphore(value: 0)
        let task = URLSession.shared.dataTask(with: url) { data, response, error in
            if error == nil, (response as? HTTPURLResponse)?.statusCode == 200 { outcome.data = data }
            done.signal()
        }
        task.resume()
        guard done.wait(timeout: .now() + 15) == .success else {
            task.cancel()
            XCTFail("/rest/search3 did not answer within 15 s")
            return nil
        }
        // The URL carries a token, so a failure names the step, never the request.
        guard let data = outcome.data,
              let document = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
              let envelope = document["subsonic-response"] as? [String: Any],
              envelope["status"] as? String == "ok" else {
            XCTFail("/rest/search3 did not return an ok envelope")
            return nil
        }
        let albums = (envelope["searchResult3"] as? [String: Any])?["album"] as? [[String: Any]] ?? []
        let matches = albums.filter { $0["name"] as? String == album }
        guard matches.count == 1, let match = matches.first else {
            XCTFail("\(matches.count) albums named \(album); exactly one is required")
            return nil
        }
        // Subsonic carries `starred` only on a favourite.
        return match["starred"] != nil
    }

    /// Polls until the server's favourite state is `expected` or the timeout passes.
    @MainActor
    private func awaitServerAlbumStarred(
        _ album: String,
        configuration: LivePlaybackConfiguration,
        expected: Bool,
        timeout: TimeInterval
    ) -> Bool? {
        let deadline = Date().addingTimeInterval(timeout)
        var observed = readServerAlbumStarred(album, configuration: configuration)
        while observed != nil, observed != expected, Date() < deadline {
            RunLoop.current.run(until: Date().addingTimeInterval(1))
            observed = readServerAlbumStarred(album, configuration: configuration)
        }
        return observed
    }

    /// Waits for the injected account's live connection to be confirmed where a person confirms
    /// it: Sign Out on the Connection destination. A first launch connects on Connection and then
    /// lands on the library; a launch with a saved account opens straight into that account's
    /// library (CONF-10b) and connects there. Either way Connection is opened the way a person
    /// opens it, and opened again if a connection still in flight moves the app to the library.
    ///
    /// A connection that reached account setup's 30-second request limit is retried the way a
    /// person retries it, with Try Again, at most twice; the form keeps the account, so the retry
    /// sends the same one. Run 37519277991 ended there on a starved runner (load 567 on three
    /// cores, 749 MB swapped out, the app itself among the swapped processes) before any request
    /// reached the server, and this proof is about what comes after connecting. Any other
    /// connection failure still fails the proof.
    @MainActor
    private func awaitLiveAccountConnection(in app: XCUIApplication, compact: Bool) -> Bool {
        let signOut = app.buttons["Sign Out"].firstMatch
        if signOut.waitForExistence(timeout: 5) { return true }
        let timedOut = app.staticTexts["The server took too long to respond"].firstMatch
        let tryAgain = app.buttons["Try Again"].firstMatch
        var retries = 0
        let deadline = Date().addingTimeInterval(35)
        repeat {
            guard openDestination(
                "Connection",
                sidebarIdentifier: "dulcet.sidebar.settings",
                in: app,
                compact: compact
            ) else { return false }
            if signOut.waitForExistence(timeout: 10) { return true }
            while retries < 2, timedOut.exists, tryAgain.exists {
                retries += 1
                print("ACCOUNT CONNECT RETRY \(retries): account setup reached its request limit")
                tryAgain.tap()
                // One more request limit, and the time a loaded host takes to show the outcome.
                if signOut.waitForExistence(timeout: 45) { return true }
            }
        } while Date() < deadline
        return false
    }

    /// Opens one of the library's own places from a regular-width sidebar -- Albums, to find an
    /// album by name the way a person does. Library itself opens on Home, whose rows scroll
    /// sideways and so cannot bring a named album into view by scrolling the page.
    @MainActor
    private func openSidebarLibrarySection(_ section: String, in app: XCUIApplication) -> Bool {
        let row = app.staticTexts["dulcet.sidebar.section.\(section)"].firstMatch
        guard row.waitForExistence(timeout: 10), row.isHittable else {
            XCTFail("The sidebar must list the library's \(section) section: " + app.debugDescription)
            return false
        }
        row.tap()
        return true
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
    /// `expectingTitle` nil skips the title check: a track shorter than the wait (the 2-second
    /// health probe) can end, and the album's next track take the bar, before it is read.
    private func openNowPlayingFromBar(in app: XCUIApplication, expectingTitle title: String?) -> Bool {
        let bar = app.buttons["dulcet.mini-player.open"].firstMatch
        guard bar.waitForExistence(timeout: 15) else {
            XCTFail("Activation must bring up the now-playing bar: " + app.debugDescription)
            return false
        }
        if let title {
            // Preparing shows a placeholder title; wait for the playing track's own name.
            let deadline = Date().addingTimeInterval(20)
            while !bar.label.contains(title), Date() < deadline {
                Thread.sleep(forTimeInterval: 0.25)
            }
            XCTAssertTrue(bar.label.contains(title), "The bar must name \(title); label=\(bar.label)")
        }
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
        guard awaitLiveAccountConnection(in: app, compact: true) else {
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

    /// The saved-account reader proof on iPhone. Recorded against the ios cell: it fails on any
    /// other idiom or on a regular-width window, so an iPad run cannot stand in for it.
    @MainActor
    func testASavedAccountReopensIntoItsLibraryAndKeepsAFavouriteOnIPhone() {
        guard requireSimulator(.phone, "The saved-account reader proof on iPhone") else { return }
        proveASavedAccountReopensIntoItsLibraryAndKeepsAFavourite(compact: true)
    }

    /// The same proof on iPad, through the sidebar. Recorded against the ipados cell: it fails on
    /// any other idiom or on a compact window, so an iPhone run cannot stand in for it.
    @MainActor
    func testASavedAccountReopensIntoItsLibraryAndKeepsAFavouriteOnIPadOS() {
        guard requireSimulator(.pad, "The saved-account reader proof on iPad") else { return }
        proveASavedAccountReopensIntoItsLibraryAndKeepsAFavourite(compact: false)
    }

    /// The library is the reader's, end to end, on the destination its caller names (the window's
    /// width decides the navigation, must match that destination, and the log names it):
    ///
    /// 1. A favourite made on an album page shows at once and reaches the server (CONF-84).
    /// 2. Relaunched with the account saved and no account hook, the app opens straight into the
    ///    library this device saw -- Reconnect offered, the album's tile opens its page, and that
    ///    page says it is showing what this device saw (CONF-76, CONF-10b) -- and the favourite is
    ///    still shown. Requests are not counted here, so "nothing is sent" is not observed.
    /// 3. Search there reads the device and says so (CONF-79's offline scope).
    /// 4. Reconnect brings the library back in place, and the favourite is removed again, so the
    ///    disposable server ends as it started.
    ///
    /// The server is read back over `/rest` with the disposable account; that read never writes.
    @MainActor
    private func proveASavedAccountReopensIntoItsLibraryAndKeepsAFavourite(compact expectedCompact: Bool) {
        guard let configuration = livePlaybackConfiguration() else { return }
        let album = "Double Lines"
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
        let compact = window.frame.width < 700
        print("DULCET READER PROOF destination=\(compact ? "compact" : "regular") window=\(window.frame)")
        guard compact == expectedCompact else {
            XCTFail("The reader proof expected a \(expectedCompact ? "compact" : "regular") window, got \(window.frame)")
            return
        }
        guard awaitLiveAccountConnection(in: app, compact: compact) else {
            XCTFail("The live account connection must succeed first")
            return
        }

        // 1. Favourite the album, from a known starting point whatever an earlier run left.
        guard openLibraryAlbum(album, in: app, compact: compact) else { return }
        let heart = app.buttons["dulcet.album.favorite"].firstMatch
        guard heart.waitForExistence(timeout: 10) else {
            XCTFail("The album page must offer its heart: " + app.debugDescription)
            return
        }
        if heart.label == "Remove Favorite" {
            heart.tap()
            guard waitForLabel("Favorite", of: heart, timeout: 5),
                  awaitServerAlbumStarred(album, configuration: configuration, expected: false, timeout: 20) == false else {
                XCTFail("An earlier run's favourite could not be cleared first")
                return
            }
        }
        XCTAssertEqual(readServerAlbumStarred(album, configuration: configuration), false,
            "The control: the server must not already hold the favourite this proof makes")
        heart.tap()
        XCTAssertTrue(waitForLabel("Remove Favorite", of: heart, timeout: 3),
            "The heart must fill at once, before the server answers; label=\(heart.label)")
        XCTAssertEqual(awaitServerAlbumStarred(album, configuration: configuration, expected: true, timeout: 20), true,
            "The favourite must reach the server")

        // 2. Relaunch with nothing but the saved account.
        app.terminate()
        app.launchArguments = []
        app.launch()
        let reconnect = app.buttons["dulcet.reader.reconnect"].firstMatch
        guard reconnect.waitForExistence(timeout: 15) else {
            XCTFail("A saved account must open straight into its library and offer Reconnect: " + app.debugDescription)
            return
        }
        guard openLibraryAlbum(album, in: app, compact: compact) else { return }
        XCTAssertTrue(heart.waitForExistence(timeout: 10) && waitForLabel("Remove Favorite", of: heart, timeout: 5),
            "The favourite must still show after the relaunch; label=\(heart.exists ? heart.label : "<none>")")
        let seenLine = app.staticTexts.matching(
            NSPredicate(format: "label BEGINSWITH %@", "Showing what")
        ).firstMatch
        XCTAssertTrue(seenLine.waitForExistence(timeout: 5),
            "The album page must say it is showing what this device saw: " + app.debugDescription)

        // 3. Search reads the device from the first characters and labels its scope.
        guard openDestination("Search", sidebarIdentifier: "dulcet.sidebar.search", in: app, compact: compact) else {
            return
        }
        let field = app.textFields["dulcet.search.field"].firstMatch
        guard field.waitForExistence(timeout: 5) else {
            XCTFail("The search field must exist: " + app.debugDescription)
            return
        }
        field.tap()
        field.typeText("Dou")
        let firstResult = app.buttons["dulcet.search.result.0"].firstMatch
        XCTAssertTrue(firstResult.waitForExistence(timeout: 10),
            "Device search must answer without the server: " + app.debugDescription)
        // The scope's icon carries the same identifier; the sentence is the text.
        let scope = app.staticTexts["dulcet.search.scope"].firstMatch
        XCTAssertTrue(scope.waitForExistence(timeout: 5), "The results must say they come from this device")
        let scopeLabel = scope.exists ? scope.label : "<none>"
        let resultLabel = firstResult.exists ? firstResult.label : "<none>"
        // The ranker orders by match tier, then type (spec §16.15), so the album need not be rank 0:
        // it must be among the device's rows. Rank 0 is only reported.
        let albumResult = app.buttons.matching(NSPredicate(
            format: "identifier BEGINSWITH %@ AND label BEGINSWITH %@", "dulcet.search.result.", album
        )).firstMatch
        // The list is lazy: a row below the fold is not in the hierarchy until it is scrolled to.
        _ = dismissKeyboardIfPresent(in: app)
        var swipes = 0
        while !albumResult.waitForExistence(timeout: 2), swipes < 4 {
            app.swipeUp()
            swipes += 1
        }
        XCTAssertTrue(albumResult.exists,
            "The album this device saw must be among the device's results; rank 0 was \(resultLabel): "
                + app.debugDescription)
        let albumRank = albumResult.exists ? albumResult.identifier : "<none>"
        _ = dismissKeyboardIfPresent(in: app)

        // 4. Reconnect in place, then leave the server as it was found.
        guard openDestination("Library", sidebarIdentifier: "dulcet.sidebar.library", in: app, compact: compact) else {
            return
        }
        // Library comes back where the person left it -- the album page, on a phone's tab and on
        // an iPad's sidebar alike -- and that page offers its own Reconnect (`dulcet.reader.retry`),
        // which reconnects the same session as the account's (`dulcet.reader.reconnect`) on Home.
        let reconnectHere = app.buttons.matching(NSPredicate(
            format: "label == %@ AND (identifier == %@ OR identifier == %@)",
            "Reconnect", "dulcet.reader.reconnect", "dulcet.reader.retry"
        )).firstMatch
        guard reconnectHere.waitForExistence(timeout: 10) else {
            XCTFail("Reconnect must still be offered in the library: " + app.debugDescription)
            return
        }
        let reconnectedFrom = reconnectHere.identifier
        reconnectHere.tap()
        XCTAssertTrue(reconnectHere.waitForNonExistence(timeout: 30),
            "Reconnect must bring the library back without leaving it: " + app.debugDescription)
        let albumTitle = app.staticTexts["dulcet.album.title"].firstMatch
        if !(albumTitle.exists && albumTitle.label == album) {
            guard openLibraryAlbum(album, in: app, compact: compact) else { return }
        }
        guard heart.waitForExistence(timeout: 10) else {
            XCTFail("The album page must offer its heart after Reconnect: " + app.debugDescription)
            return
        }
        heart.tap()
        XCTAssertTrue(waitForLabel("Favorite", of: heart, timeout: 3), "The heart must empty at once")
        XCTAssertEqual(awaitServerAlbumStarred(album, configuration: configuration, expected: false, timeout: 20), false,
            "Removing the favourite must reach the server")
        print("DULCET READER PROOF PASS destination=\(compact ? "compact" : "regular")"
            + " relaunch=device-only scope=\(scopeLabel.debugDescription) rank0=\(resultLabel.debugDescription)"
            + " album-row=\(albumRank) reconnected-from=\(reconnectedFrom)")
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
        assertAlbumHeaderReadsAtTheLargestTextSize(run)
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
        assertAlbumHeaderReadsAtTheLargestTextSize(run)
        let placement = assertNoticeClearsNavigation(notice)
        XCTAssertEqual(notice.label, "Couldn\u{2019}t play a track. Skipped.",
                       "The notice must show the sentence without the title; \(placement)")
        XCTAssertFalse(notice.label.contains("Concerto"), "notice=\(notice.label)")
        attachScreenshot(named: "skipped-track-notice-ax5-long-title", app: run.app)
        print("DULCET AUTO SKIP AX5 LONG TITLE OBSERVED notice=\(notice.label.debugDescription) placement=\(placement)")
    }

    /// At the largest text size the album page is taller than the screen: its first track starts
    /// below the fold, so the proof reached it by scrolling -- the path a person takes -- and the
    /// header's Play and Shuffle each keep their label on one line, full width, rather than
    /// wrapping it a letter per line into a button taller than the screen.
    @MainActor
    private func assertAlbumHeaderReadsAtTheLargestTextSize(_ run: SkipProbeRun) {
        let frames = run.headerActions.map { "\($0)" }.joined(separator: " ")
        print("DULCET SKIP PROBE AX5 HEADER row-swipes=\(run.rowSwipes) play-shuffle=\(frames)")
        XCTAssertGreaterThanOrEqual(run.rowSwipes, 1,
                                    "At this size the first track must start below the fold; header=\(frames)")
        for frame in run.headerActions {
            XCTAssertFalse(frame.isEmpty, "Play and Shuffle must both be on the page; header=\(frames)")
            XCTAssertGreaterThan(frame.width, 2 * frame.height,
                                 "A header action must keep its label on one line; header=\(frames)")
        }
    }

    private struct SkipProbeRun {
        let app: XCUIApplication
        /// The live element, for what happens after it was seen: that it goes away.
        let notice: XCUIElement
        /// What the notice and the bars were at the instant it was seen, from one snapshot.
        let sighting: NoticeSighting
        let markersBefore: [String]
        /// How many swipes brought the unplayable track into reach: zero when it was on screen.
        let rowSwipes: Int
        /// The album header's Play and Shuffle frames, read when the page opened.
        let headerActions: [CGRect]
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
        guard awaitLiveAccountConnection(in: app, compact: true) else {
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
        // The page is open once its title is. The tracks follow the header, and the list realizes
        // rows as they scroll into view, so at the largest text size -- where the artwork, title
        // and buttons fill more than a screen -- the first track does not exist until the page
        // scrolls to it. Scroll, bounded, rather than wait for a row that is not built yet.
        let header = app.staticTexts["dulcet.album.title"].firstMatch
        guard header.waitForExistence(timeout: 15) else {
            XCTFail("The \(probe.album) album page must open: " + app.debugDescription)
            return nil
        }
        let headerActions = ["dulcet.album.play", "dulcet.album.shuffle"].map { app.buttons[$0].firstMatch.frame }
        let rowSwipes = scrollIntoViewCountingSwipes(unplayableRow, in: app)
        guard let rowSwipes, playableRow.waitForExistence(timeout: 5),
              unplayableRow.frame.minY < playableRow.frame.minY else {
            XCTFail("The \(probe.album) album must list \(probe.unplayableTitle) before \(probe.playableTitle)"
                + " within the swipe bound: " + app.debugDescription)
            return nil
        }
        let markersBefore = proofMarkers(in: app)
        unplayableRow.tap()
        let notice = app.descendants(matching: .any)["dulcet.playback.skipped-notice"].firstMatch
        guard let sighting = sightNotice(in: app, markersBefore: markersBefore, timeout: 30) else { return nil }
        return SkipProbeRun(
            app: app,
            notice: notice,
            sighting: sighting,
            markersBefore: markersBefore,
            rowSwipes: rowSwipes,
            headerActions: headerActions
        )
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

    /// The write guard's own control: it launches nothing. A LAN address is refused even though
    /// a disposable server could live there -- so could a personal one.
    func testTheLiveServerGuardAcceptsOnlyALoopbackHost() {
        for host in ["127.0.0.1", "127.8.9.10", "localhost", "::1", "[::1]"] {
            XCTAssertTrue(Self.isLoopbackHost(host), host)
        }
        for host in ["192.168.1.20", "10.0.0.5", "music.example.com", "127.0.0.1.example.com", "1127.0.0.1", "", "0.0.0.0"] {
            XCTAssertFalse(Self.isLoopbackHost(host), host)
        }
    }

    /// A playlist on the server opens from Library > Playlists, plays in ITS order, and a rename
    /// made on its page reaches the server (spec §18.6). The playlist is made for this run over
    /// `/rest` with the canary first and "Thirty One Seconds" second -- the reverse of their album
    /// order -- so the track that starts proves the playlist's order and not the album's. It is
    /// deleted afterwards, whatever the outcome.
    @MainActor
    func testAPlaylistOpensPlaysInItsOrderAndARenameReachesTheServer() {
        provePlaylistOpensPlaysInItsOrderAndARenameReachesTheServer(compact: true)
    }

    /// The same on iPad, in its regular-width window, through the sidebar.
    @MainActor
    func testAPlaylistOpensPlaysInItsOrderAndARenameReachesTheServerOnIPadOS() {
        provePlaylistOpensPlaysInItsOrderAndARenameReachesTheServer(compact: false)
    }

    /// Fails on the other device class, so an iPhone run cannot stand as iPad evidence.
    @MainActor
    private func provePlaylistOpensPlaysInItsOrderAndARenameReachesTheServer(compact expectedCompact: Bool) {
        guard requireSimulator(expectedCompact ? .phone : .pad,
                               "The playlist play-in-order proof on \(expectedCompact ? "iPhone" : "iPad")"),
              let configuration = livePlaybackConfiguration() else { return }
        let first = "UI Playback Canary"
        let second = "Thirty One Seconds"
        let name = "Dulcet UI Proof " + UUID().uuidString.prefix(8)
        let renamed = name + " Renamed"
        guard let songs = restCall("search3", [
                  URLQueryItem(name: "query", value: "Threshold"), URLQueryItem(name: "songCount", value: "10"),
                  URLQueryItem(name: "albumCount", value: "0"), URLQueryItem(name: "artistCount", value: "0"),
              ], configuration: configuration)?["searchResult3"] as? [String: Any],
              let rows = songs["song"] as? [[String: Any]],
              let firstID = rows.first(where: { $0["title"] as? String == first })?["id"] as? String,
              let secondID = rows.first(where: { $0["title"] as? String == second })?["id"] as? String else {
            XCTFail("The corpus must hold \(first) and \(second)")
            return
        }
        guard let created = restCall("createPlaylist", [
                  URLQueryItem(name: "name", value: name),
                  URLQueryItem(name: "songId", value: firstID), URLQueryItem(name: "songId", value: secondID),
              ], configuration: configuration)?["playlist"] as? [String: Any],
              let playlistID = created["id"] as? String else {
            XCTFail("The run's playlist could not be made on the disposable server")
            return
        }
        afterTest.append { [configuration] in
            _ = self.restCall("deletePlaylist", [URLQueryItem(name: "id", value: playlistID)], configuration: configuration)
        }

        let app = XCUIApplication()
        app.launchArguments += [
            "-dulcet-debug-connect-account",
            "-dulcet-debug-account-server-url", configuration.serverURL,
            "-dulcet-debug-account-username", configuration.username,
            "-dulcet-debug-account-password", configuration.password,
        ]
        app.launch()
        let window = app.windows.firstMatch
        XCTAssertTrue(window.waitForExistence(timeout: 10), "The app window must exist")
        let compact = window.frame.width < 700
        guard compact == expectedCompact else {
            XCTFail("The proof expects a \(expectedCompact ? "compact" : "regular") window; width=\(window.frame.width)")
            return
        }
        guard awaitLiveAccountConnection(in: app, compact: compact) else {
            XCTFail("The live account connection must succeed first")
            return
        }
        guard openLibraryPlaylists(in: app, compact: compact) else { return }
        let row = app.buttons.matching(identifier: "dulcet.library.playlist")
            .matching(NSPredicate(format: "label BEGINSWITH %@", name)).firstMatch
        guard row.waitForExistence(timeout: 30), scrollIntoView(row, in: app) else {
            XCTFail("Playlists must list the run's playlist: " + app.debugDescription)
            return
        }
        row.tap()
        let title = app.staticTexts["dulcet.playlist.title"].firstMatch
        guard title.waitForExistence(timeout: 10), waitForLabel(name, of: title, timeout: 10) else {
            XCTFail("The row must open its playlist: " + app.debugDescription)
            return
        }
        let play = app.buttons["dulcet.playlist.play"].firstMatch
        guard play.waitForExistence(timeout: 10), waitForEnabled(play, timeout: 15) else {
            XCTFail("The playlist's Play must be offered once its tracks are read: " + app.debugDescription)
            return
        }
        play.tap()
        guard openNowPlayingFromBar(in: app, expectingTitle: first) else { return }
        let nowPlaying = app.staticTexts["dulcet.now-playing.title"].firstMatch
        XCTAssertTrue(nowPlaying.waitForExistence(timeout: 10) && nowPlaying.label.contains(first),
            "Play must start the playlist's FIRST entry, \(first), not the album's; title=\(nowPlaying.exists ? nowPlaying.label : "<none>")")
        let close = app.buttons["dulcet.now-playing.close"].firstMatch
        if close.waitForExistence(timeout: 5) { close.tap() } else { app.swipeDown() }

        // Rename from the page; the header shows it at once and the server gets it.
        let more = app.buttons["dulcet.playlist.more"].firstMatch
        guard more.waitForExistence(timeout: 10) else {
            XCTFail("The person's own playlist must offer its menu: " + app.debugDescription)
            return
        }
        more.tap()
        let rename = app.buttons.matching(NSPredicate(format: "label BEGINSWITH %@", "Rename")).firstMatch
        guard rename.waitForExistence(timeout: 5) else {
            XCTFail("The menu must offer Rename: " + app.debugDescription)
            return
        }
        rename.tap()
        let alert = app.alerts.firstMatch
        let field = alert.textFields.firstMatch
        guard alert.waitForExistence(timeout: 5), field.waitForExistence(timeout: 5) else {
            XCTFail("Rename must ask for the new name: " + app.debugDescription)
            return
        }
        // The field opens holding the current name. Command-A is not reliable in an alert's field
        // (it selected nothing on one run of two), so the name is deleted character by character.
        // A plain tap puts the cursor where it lands -- mid-name, OBSERVED 3 of 3 -- and deletes
        // only take what is before it, so the tap is at the trailing edge, and the field is
        // cleared until it reads empty (an empty field reports its placeholder as its value).
        for _ in 0..<3 {
            let current = field.value as? String ?? ""
            if current.isEmpty || current == field.placeholderValue { break }
            field.coordinate(withNormalizedOffset: CGVector(dx: 0.97, dy: 0.5)).tap()
            field.typeText(String(repeating: XCUIKeyboardKey.delete.rawValue, count: current.count))
        }
        field.typeText(renamed)
        guard (field.value as? String) == renamed else {
            XCTFail("The name field must hold exactly the new name; value=\(String(describing: field.value))")
            return
        }
        alert.buttons["Rename"].firstMatch.tap()
        XCTAssertTrue(waitForLabel(renamed, of: title, timeout: 5),
            "The page must show the new name before the server answers; title=\(title.label)")
        var serverName: String?
        let deadline = Date().addingTimeInterval(30)
        repeat {
            serverName = (restCall("getPlaylist", [URLQueryItem(name: "id", value: playlistID)], configuration: configuration)?[
                "playlist"] as? [String: Any])?["name"] as? String
            if serverName == renamed { break }
            RunLoop.current.run(until: Date().addingTimeInterval(1))
        } while Date() < deadline
        XCTAssertEqual(serverName, renamed, "The rename must reach the server")
        print("DULCET PLAYLIST PROOF PASS destination=\(compact ? "compact" : "regular") first=\(first.debugDescription)"
            + " server-name=\((serverName ?? "<none>").debugDescription)")
    }

    /// A playlist edit made while the server is unreachable shows as pending on iPhone, and is
    /// sent at the reconnect (spec §18.6).
    @MainActor
    func testAPlaylistEditMadeOfflineShowsPendingAndIsSentAtReconnectOnIPhone() {
        provePlaylistEditMadeOfflineIsPendingThenSent(compact: true)
    }

    /// The same on iPad, in its regular-width window.
    @MainActor
    func testAPlaylistEditMadeOfflineShowsPendingAndIsSentAtReconnectOnIPadOS() {
        provePlaylistEditMadeOfflineIsPendingThenSent(compact: false)
    }

    /// The app is connected through `tools/conformance-env/lyrics-fault-proxy`, which fronts the
    /// disposable server; the test reads and writes the server directly. With a playlist made for
    /// this run open on its page:
    ///
    /// 1. the proxy's outage begins: every `/rest` request the app sends is dropped unanswered;
    /// 2. the playlist is renamed on its page, which shows the new name at once;
    /// 3. Playlists says the playlist has "Changes not yet on your server", the server still holds
    ///    the old name, and the proxy dropped an `updatePlaylist` -- the edit met the outage;
    /// 4. the outage ends and the app returns to the foreground, a reconnect (§16.14): the server
    ///    gets the new name, the proxy forwarded an `updatePlaylist` after the outage, and the
    ///    pending words go away.
    ///
    /// The outage is ended and the playlist deleted afterwards, pass or fail. The test fails on the
    /// other device class, so an iPhone run cannot stand as iPad evidence.
    @MainActor
    private func provePlaylistEditMadeOfflineIsPendingThenSent(compact expectedCompact: Bool) {
        guard requireSimulator(expectedCompact ? .phone : .pad,
                               "The offline playlist edit proof on \(expectedCompact ? "iPhone" : "iPad")"),
              let configuration = livePlaybackConfiguration(),
              let proxy = lyricsFaultProxyConfiguration(server: configuration) else { return }
        let pendingWords = "Changes not yet on your server"
        let name = "Dulcet Offline Edit Proof " + UUID().uuidString.prefix(8)
        let renamed = name + " Renamed"
        guard let canaryID = serverSongID("UI Playback Canary", album: "Threshold Boundary", configuration: configuration),
              let playlistID = (restCall("createPlaylist", [
                  URLQueryItem(name: "name", value: name), URLQueryItem(name: "songId", value: canaryID),
              ], configuration: configuration)?["playlist"] as? [String: Any])?["id"] as? String else {
            XCTFail("The run's playlist could not be made on the disposable server")
            return
        }
        afterTest.append { [configuration] in
            _ = self.proxyRequest("POST", "/__dulcet/outage?state=off", proxy: proxy)
            _ = self.restCall("deletePlaylist", [URLQueryItem(name: "id", value: playlistID)], configuration: configuration)
        }
        guard proxyOutage(nil, proxy: proxy)?.outage == false else {
            XCTFail("The control: the proxy must start with no outage")
            return
        }
        guard let app = launchConnected(serverURL: proxy.url, configuration: configuration, compact: expectedCompact),
              openLibraryPlaylists(in: app, compact: expectedCompact) else { return }
        let row = app.buttons.matching(identifier: "dulcet.library.playlist")
            .matching(NSPredicate(format: "label BEGINSWITH %@", name)).firstMatch
        guard row.waitForExistence(timeout: 30), scrollIntoView(row, in: app) else {
            XCTFail("Playlists must list the run's playlist: " + app.debugDescription)
            return
        }
        XCTAssertFalse(row.label.contains(pendingWords), "The control: nothing is pending before the outage; label=\(row.label)")
        guard openPlaylistRow(name, in: app) else { return }

        // 1-2. The server goes away; the rename is made on the page and shown at once.
        guard proxyOutage(true, proxy: proxy)?.outage == true else {
            XCTFail("The proxy's outage must begin")
            return
        }
        let title = app.staticTexts["dulcet.playlist.title"].firstMatch
        guard renamePlaylist(to: renamed, in: app) else { return }
        XCTAssertTrue(waitForLabel(renamed, of: title, timeout: 5), "The page must show the new name at once; title=\(title.label)")

        // 3. Pending: said in Playlists, not on the server, and the edit met the outage.
        guard openLibraryPlaylistsFromAnywhere(in: app, compact: expectedCompact) else { return }
        let pendingRow = app.buttons.matching(identifier: "dulcet.library.playlist")
            .matching(NSPredicate(format: "label BEGINSWITH %@ AND label CONTAINS %@", renamed, pendingWords)).firstMatch
        XCTAssertTrue(pendingRow.waitForExistence(timeout: 15),
            "Playlists must say the renamed playlist has changes not yet on the server: " + app.debugDescription)
        let duringOutage = proxyOutage(nil, proxy: proxy)
        XCTAssertGreaterThanOrEqual(duringOutage?.dropped["updatePlaylist"] ?? 0, 1,
            "The fixture: the rename's send must have met the outage; dropped=\(String(describing: duringOutage?.dropped))")
        let serverNameDuringOutage = (restCall("getPlaylist", [URLQueryItem(name: "id", value: playlistID)], configuration: configuration)?[
            "playlist"] as? [String: Any])?["name"] as? String
        XCTAssertEqual(serverNameDuringOutage, name, "The server must still hold the old name during the outage")

        // 4. The server is back; a return to the foreground reconnects, and the edit is sent.
        guard proxyOutage(false, proxy: proxy)?.outage == false else {
            XCTFail("The proxy's outage must end")
            return
        }
        XCUIDevice.shared.press(.home)
        RunLoop.current.run(until: Date().addingTimeInterval(2))
        app.activate()
        XCTAssertEqual(awaitServerPlaylistName(playlistID, renamed, configuration: configuration), renamed,
            "The rename made offline must reach the server after the reconnect")
        let afterOutage = proxyOutage(nil, proxy: proxy)
        XCTAssertGreaterThanOrEqual(afterOutage?.forwardedAfterOutage["updatePlaylist"] ?? 0, 1,
            "The rename must be sent after the outage; forwarded=\(String(describing: afterOutage?.forwardedAfterOutage))")
        let settledRow = app.buttons.matching(identifier: "dulcet.library.playlist")
            .matching(NSPredicate(format: "label BEGINSWITH %@", renamed)).firstMatch
        let settled = NSPredicate(format: "NOT (label CONTAINS %@)", pendingWords)
        XCTAssertEqual(XCTWaiter.wait(for: [XCTNSPredicateExpectation(predicate: settled, object: settledRow)], timeout: 15), .completed,
            "Once sent, Playlists must stop saying the change is pending; label=\(settledRow.label)")
        print("DULCET PENDING PLAYLIST PROOF PASS destination=\(expectedCompact ? "compact" : "regular")"
            + " dropped=\(duringOutage?.dropped ?? [:]) forwarded-after=\(afterOutage?.forwardedAfterOutage ?? [:])"
            + " server-during=\((serverNameDuringOutage ?? "<none>").debugDescription)")
    }

    private struct ProxyOutage {
        let outage: Bool
        let dropped: [String: Int]
        let forwardedAfterOutage: [String: Int]
    }

    /// Begins (true) or ends (false) the proxy's outage, or only reads it (nil).
    private func proxyOutage(_ on: Bool?, proxy: LyricsFaultProxy) -> ProxyOutage? {
        let body = proxyRequest(on == nil ? "GET" : "POST",
                                "/__dulcet/outage" + (on.map { "?state=\($0 ? "on" : "off")" } ?? ""), proxy: proxy)
        guard let body, let outage = body["outage"] as? Bool else {
            XCTFail("The fault proxy must report its outage")
            return nil
        }
        return ProxyOutage(outage: outage, dropped: body["dropped"] as? [String: Int] ?? [:],
                           forwardedAfterOutage: body["forwardedAfterOutage"] as? [String: Int] ?? [:])
    }

    /// Every playlist edit through the iPhone app (spec §18.6, CONF-88..90).
    @MainActor
    func testEveryPlaylistEditReachesTheServerOnIPhone() {
        provePlaylistEditsReachTheServer(compact: true)
    }

    /// Every playlist edit through the iPad app, in its regular-width window (spec §18.6,
    /// CONF-88..90).
    @MainActor
    func testEveryPlaylistEditReachesTheServerOnIPadOS() {
        provePlaylistEditsReachTheServer(compact: false)
    }

    /// A track dragged out of its own open context menu onto the now-playing bar joins Up Next
    /// (spec §3.1: every track row is a drag source onto the queue). The row's menu is opened
    /// first, so the row is known to carry one, and the drag then begins with a hold long enough
    /// to open it again before it moves.
    ///
    /// A custom menu preview on a draggable row keeps the touch in the open menu, and the drag
    /// never lifts (OBSERVED on an iPhone 17 Pro simulator, iOS 26.5: Up Next stayed empty with the
    /// custom preview, and gained the track with the system preview or with no menu). The test
    /// fails if a preview of that kind comes back.
    @MainActor
    func testATrackDraggedOutOfItsContextMenuJoinsUpNextOnIPhone() {
        guard ProcessInfo.processInfo.environment["SIMULATOR_UDID"] != nil else {
            XCTFail("This proof requires a simulator; a physical device is not the destination it names")
            return
        }
        guard UIDevice.current.userInterfaceIdiom == .phone else {
            XCTFail("This proof names iPhone but runs on idiom \(UIDevice.current.userInterfaceIdiom.rawValue)")
            return
        }
        guard let configuration = livePlaybackConfiguration(),
              let app = launchConnected(serverURL: configuration.serverURL, configuration: configuration, compact: true),
              openLibraryDestinationForAlbums(in: app, compact: true) else { return }
        let tile = app.buttons.matching(identifier: "dulcet.library.album")
            .matching(NSPredicate(format: "label BEGINSWITH %@", "Threshold Boundary")).firstMatch
        guard tile.waitForExistence(timeout: 30), scrollIntoView(tile, in: app) else {
            XCTFail("Library must show Threshold Boundary: " + app.debugDescription)
            return
        }
        tile.tap()
        let track = { (title: String) in
            app.buttons.matching(identifier: "dulcet.reader.track")
                .matching(NSPredicate(format: "label BEGINSWITH %@", title + ", ")).firstMatch
        }
        let dragged = track("Thirty One Seconds")
        guard dragged.waitForExistence(timeout: 15), scrollIntoView(dragged, in: app) else {
            XCTFail("The album must list Thirty One Seconds: " + app.debugDescription)
            return
        }

        // The row carries a context menu: the condition the drag below has to get out of.
        var offered = false
        withoutIdleWaits(app) {
            let source = dragged.frame
            dragged.press(forDuration: 1.2)
            offered = app.buttons["Add to Playlist\u{2026}"].firstMatch.waitForExistence(timeout: 5)
            self.closeContextMenu(marker: "Add to Playlist\u{2026}", openedFrom: source, in: app)
        }
        guard offered else {
            XCTFail("The row must offer its context menu: " + app.debugDescription)
            return
        }
        guard app.buttons["Add to Playlist\u{2026}"].firstMatch.waitForNonExistence(timeout: 10) else {
            XCTFail("A tap outside the menu must close it: " + app.debugDescription)
            return
        }

        // Something plays, so the bar is there to drop onto; the canary is the album's last track,
        // so nothing follows it yet.
        let canary = track("UI Playback Canary")
        guard canary.waitForExistence(timeout: 10), scrollIntoView(canary, in: app) else {
            XCTFail("The album must list UI Playback Canary: " + app.debugDescription)
            return
        }
        canary.tap()
        let bar = app.buttons["dulcet.mini-player.open"].firstMatch
        guard bar.waitForExistence(timeout: 15) else {
            XCTFail("Playing a track must show the now-playing bar: " + app.debugDescription)
            return
        }
        guard let before = upNextLabels(openingFrom: bar, in: app) else { return }
        let queuedBefore = before.filter { $0.hasPrefix("Thirty One Seconds") }.count

        // A second drag only if the first queued nothing: on one local run of ten the lifted
        // track stayed where it was while the synthesized touch moved, and the menu closed. Under
        // a custom preview both drags queue nothing, so the retry cannot hide the defect.
        var after = before
        var attempts = 0
        var menuLeftOpen = 0
        while attempts < 2, after.filter({ $0.hasPrefix("Thirty One Seconds") }).count == queuedBefore {
            attempts += 1
            withoutIdleWaits(app) {
                dragged.press(forDuration: 0.8, thenDragTo: bar, withVelocity: XCUIGestureVelocity(200), thenHoldForDuration: 1.0)
            }
            // A drag that never lifted out leaves the menu open where the touch ended (the
            // defect's shape); close it so Up Next can be read.
            let menu = app.buttons["Add to Playlist\u{2026}"].firstMatch
            if menu.waitForExistence(timeout: 2) {
                menuLeftOpen += 1
                withoutIdleWaits(app) {
                    self.closeContextMenu(marker: "Add to Playlist\u{2026}", openedFrom: dragged.frame, in: app)
                }
                _ = menu.waitForNonExistence(timeout: 10)
            }
            let deadline = Date().addingTimeInterval(10)
            repeat {
                RunLoop.current.run(until: Date().addingTimeInterval(1))
                guard let read = upNextLabels(openingFrom: bar, in: app) else { return }
                after = read
            } while after.filter({ $0.hasPrefix("Thirty One Seconds") }).count == queuedBefore && Date() < deadline
        }
        attachScreenshot(named: "track-dragged-from-its-menu", app: app)
        XCTAssertEqual(after.filter { $0.hasPrefix("Thirty One Seconds") }.count, queuedBefore + 1,
                       "The dragged track must join Up Next once; before=\(before) after=\(after) drags=\(attempts)"
                       + " menu-left-open-after=\(menuLeftOpen)")
        print("DULCET DRAG FROM MENU before=\(before) after=\(after) drags=\(attempts) menu-left-open-after=\(menuLeftOpen)")
    }

    /// Opens the album from its tile and plays its last track, so the bar is there to drop onto
    /// and Up Next holds nothing of the album yet. Answers the bar.
    @MainActor
    private func startPlayingTheAlbumsLastTrack(
        album: String,
        last: String,
        tile: XCUIElement,
        in app: XCUIApplication
    ) -> XCUIElement? {
        tile.tap()
        let row = app.buttons.matching(identifier: "dulcet.reader.track")
            .matching(NSPredicate(format: "label BEGINSWITH %@", last + ", ")).firstMatch
        guard row.waitForExistence(timeout: 15), scrollIntoView(row, in: app) else {
            XCTFail("\(album) must list \(last): " + app.debugDescription)
            return nil
        }
        row.tap()
        let bar = app.buttons["dulcet.mini-player.open"].firstMatch
        guard bar.waitForExistence(timeout: 15) else {
            XCTFail("Playing a track must show the now-playing bar: " + app.debugDescription)
            return nil
        }
        // Paused, so the queue stays as it is: the corpus's tracks last about thirty seconds, and
        // a track that ends while the proof is still working ends the queue, after which a drop
        // starts playing what it carries and the count of what Up Next holds is no longer the
        // drop's (OBSERVED on a loaded host: the canary ended, the first album became the playing
        // track and the second drag's tracks followed it).
        let playPause = app.buttons["dulcet.mini-player.play-pause"].firstMatch
        guard playPause.waitForExistence(timeout: 10) else {
            XCTFail("The bar must offer play/pause: " + app.debugDescription)
            return nil
        }
        if playPause.label == "Pause" { playPause.tap() }
        guard waitForLabel("Play", of: playPause, timeout: 10) else {
            XCTFail("Pause on the bar must reach playback; label stayed \(playPause.label)")
            return nil
        }
        return bar
    }

    /// A playlist row dragged out of its open context menu onto the bar adds every track of the
    /// playlist to the end of Up Next, in the playlist's own order -- made different from the
    /// album's order here, so the order proves where the tracks came from.
    @MainActor
    func testAPlaylistRowDraggedOntoTheBarJoinsUpNextOnIPhone() {
        proveAPlaylistRowDragJoinsUpNext(compact: true)
    }

    @MainActor
    func testAPlaylistRowDraggedOntoTheBarJoinsUpNextOnIPadOS() {
        proveAPlaylistRowDragJoinsUpNext(compact: false)
    }

    @MainActor
    private func proveAPlaylistRowDragJoinsUpNext(compact: Bool) {
        guard requireSimulator(compact ? .phone : .pad, "The playlist row drag proof on \(compact ? "iPhone" : "iPad")"),
              let configuration = livePlaybackConfiguration() else { return }
        let album = "Threshold Boundary"
        let albumOrder = ["Twenty Nine Seconds", "Thirty One Seconds", "UI Playback Canary"]
        let playlistOrder = ["Thirty One Seconds", "UI Playback Canary", "Twenty Nine Seconds"]
        let run = String(UUID().uuidString.prefix(8))
        let name = "Dulcet Drag Proof " + run
        afterTest.append { [configuration] in
            for playlist in self.serverPlaylists(configuration: configuration) ?? [] where playlist.name.contains(run) {
                _ = self.restCall("deletePlaylist", [URLQueryItem(name: "id", value: playlist.id)], configuration: configuration)
            }
        }
        var songIDs: [URLQueryItem] = []
        for title in playlistOrder {
            guard let id = serverSongID(title, album: album, configuration: configuration) else { return }
            songIDs.append(URLQueryItem(name: "songId", value: id))
        }
        guard restCall("createPlaylist", [URLQueryItem(name: "name", value: name)] + songIDs, configuration: configuration) != nil,
              let playlistID = awaitServerPlaylist(named: name, configuration: configuration) else {
            XCTFail("The playlist could not be made on the disposable server")
            return
        }
        XCTAssertEqual(serverPlaylistEntries(playlistID, configuration: configuration), playlistOrder,
                       "The server must hold the playlist in the order the proof expects")
        guard let app = launchConnected(serverURL: configuration.serverURL, configuration: configuration, compact: compact),
              openLibraryDestinationForAlbums(in: app, compact: compact) else { return }
        let tile = { app.buttons.matching(identifier: "dulcet.library.album")
            .matching(NSPredicate(format: "label BEGINSWITH %@", album)).firstMatch }
        guard tile().waitForExistence(timeout: 30), scrollIntoView(tile(), in: app),
              let bar = startPlayingTheAlbumsLastTrack(album: album, last: albumOrder[2], tile: tile(), in: app),
              openLibraryPlaylistsFromAnywhere(in: app, compact: compact) else { return }
        let row = { app.buttons.matching(identifier: "dulcet.library.playlist")
            .matching(NSPredicate(format: "label BEGINSWITH %@", name)).firstMatch }
        guard row().waitForExistence(timeout: 30), scrollIntoView(row(), in: app),
              requireContextMenu(on: row(), marker: "Add to Queue", in: app),
              let before = awaitSettledUpNext(openingFrom: bar, in: app, compact: compact),
              let result = dragOntoTheBar(
                  row(), bar: bar, before: before, adding: playlistOrder.count, menuMarker: "Add to Queue",
                  in: app, compact: compact
              ) else { return }
        attachScreenshot(named: "playlist-row-dragged-onto-the-bar", app: app)
        XCTAssertEqual(result.after.count, before.count + playlistOrder.count,
                       "The dragged playlist must join Up Next once; before=\(before) after=\(result.after)"
                       + " drags=\(result.drags) menu-left-open-after=\(result.menuLeftOpen)")
        XCTAssertEqual(result.menuLeftOpen, 0,
                       "A drag that carries on out of the open menu must lift: a menu left open under the touch is the custom preview's defect (TRAPS 48)")
        for (label, title) in zip(result.after.dropFirst(before.count), playlistOrder) {
            XCTAssertTrue(label.hasPrefix(title), "Playlist order at the end of Up Next: \(title) expected, got \(label)")
        }
        print("DULCET PLAYLIST ROW DRAG before=\(before) after=\(result.after) drags=\(result.drags)"
              + " menu-left-open-after=\(result.menuLeftOpen)")
    }

    /// An album in the search results, dragged out of its open context menu onto the bar, adds
    /// the whole album to the end of Up Next in album order. The row had no drag source: only a
    /// track result lifted a card that could be added.
    @MainActor
    func testAnAlbumSearchResultDraggedOntoTheBarJoinsUpNextOnIPhone() {
        guard requireSimulator(.phone, "The album search result drag proof on iPhone"),
              let configuration = livePlaybackConfiguration(),
              let app = launchConnected(serverURL: configuration.serverURL, configuration: configuration, compact: true),
              openLibraryDestinationForAlbums(in: app, compact: true) else { return }
        let album = "Threshold Boundary"
        let albumOrder = ["Twenty Nine Seconds", "Thirty One Seconds", "UI Playback Canary"]
        let tile = { app.buttons.matching(identifier: "dulcet.library.album")
            .matching(NSPredicate(format: "label BEGINSWITH %@", album)).firstMatch }
        guard tile().waitForExistence(timeout: 30), scrollIntoView(tile(), in: app),
              let bar = startPlayingTheAlbumsLastTrack(album: album, last: albumOrder[2], tile: tile(), in: app),
              openDestination("Search", sidebarIdentifier: "dulcet.sidebar.search", in: app, compact: true) else { return }
        let field = app.textFields["dulcet.search.field"].firstMatch
        guard field.waitForExistence(timeout: 10) else {
            XCTFail("The search field must exist on the Search destination: " + app.debugDescription)
            return
        }
        field.tap()
        field.typeText(album)
        let result = { app.buttons.matching(NSPredicate(
            format: "identifier BEGINSWITH %@ AND label BEGINSWITH %@", "dulcet.search.result.", album + ", "
        )).firstMatch }
        guard result().waitForExistence(timeout: 30), dismissKeyboardBeforeActivation(in: app),
              scrollIntoView(result(), in: app),
              requireContextMenu(on: result(), marker: "Go to Album", in: app),
              let before = awaitSettledUpNext(openingFrom: bar, in: app, compact: true),
              let drag = dragOntoTheBar(
                  result(), bar: bar, before: before, adding: albumOrder.count, menuMarker: "Go to Album",
                  in: app, compact: true
              ) else { return }
        attachScreenshot(named: "album-search-result-dragged-onto-the-bar", app: app)
        XCTAssertEqual(drag.after.count, before.count + albumOrder.count,
                       "The dragged album must join Up Next once; before=\(before) after=\(drag.after)"
                       + " drags=\(drag.drags) menu-left-open-after=\(drag.menuLeftOpen)")
        XCTAssertEqual(drag.menuLeftOpen, 0,
                       "A drag that carries on out of the open menu must lift: a menu left open under the touch is the custom preview's defect (TRAPS 48)")
        for (label, title) in zip(drag.after.dropFirst(before.count), albumOrder) {
            XCTAssertTrue(label.hasPrefix(title), "Album order at the end of Up Next: \(title) expected, got \(label)")
        }
        print("DULCET ALBUM SEARCH RESULT DRAG before=\(before) after=\(drag.after) drags=\(drag.drags)"
              + " menu-left-open-after=\(drag.menuLeftOpen)")
    }

    /// The Playlists list, from wherever the Library destination's stack was left: the phone's
    /// tab keeps its stack, so it is popped to the root first.
    @MainActor
    private func openLibraryPlaylistsFromAnywhere(in app: XCUIApplication, compact: Bool) -> Bool {
        if compact {
            guard openDestination("Library", sidebarIdentifier: "dulcet.sidebar.library", in: app, compact: true) else { return false }
            let back = app.navigationBars.buttons["BackButton"].firstMatch
            var pops = 0
            while back.exists, back.isHittable, pops < 4 {
                back.tap()
                pops += 1
            }
        }
        return openLibraryPlaylists(in: app, compact: compact)
    }

    /// Up Next once two reads in a row agree. A queue restored from an earlier launch can still be
    /// on screen for a moment after the tap that replaces it, and a "before" read taken then makes
    /// the drag's count wrong (OBSERVED on the iPad: before held two entries of the restored
    /// queue, the queue then emptied, the drag's three entries were counted as too few, and a
    /// second drag queued the playlist twice). What the queue holds after settling is not assumed:
    /// a restored current track that the tap does not replace leaves its own entries, and the
    /// proofs count what the drag adds to them.
    @MainActor
    private func awaitSettledUpNext(openingFrom bar: XCUIElement, in app: XCUIApplication, compact: Bool) -> [String]? {
        let deadline = Date().addingTimeInterval(45)
        var previous: [String]?
        repeat {
            guard let read = upNextLabels(openingFrom: bar, in: app, compact: compact) else { return nil }
            if read == previous { return read }
            previous = read
            RunLoop.current.run(until: Date().addingTimeInterval(1.5))
        } while Date() < deadline
        XCTFail("Up Next must settle before a drag is counted; it last read \(previous ?? [])")
        return nil
    }

    /// Up Next's entries, read from the player opened from the bar, which is then closed again.
    /// A regular-width window shows Up Next beside the player and has no toggle for it.
    @MainActor
    private func upNextLabels(openingFrom bar: XCUIElement, in app: XCUIApplication, compact: Bool = true) -> [String]? {
        bar.tap()
        if compact {
            let toggle = app.buttons["dulcet.now-playing.up-next"].firstMatch
            guard toggle.waitForExistence(timeout: 10) else {
                XCTFail("The player must offer Up Next: " + app.debugDescription)
                return nil
            }
            toggle.tap()
        }
        let rows = app.descendants(matching: .any)
            .matching(NSPredicate(format: "identifier BEGINSWITH %@", "dulcet.upNext.row."))
        // An empty Up Next has no row to wait for; give the list a moment to draw.
        _ = rows.firstMatch.waitForExistence(timeout: 2)
        // Read through snapshots, which throw when a row has gone, instead of recording a failure
        // as a bare `.label` does: the queue can change between counting its rows and reading them
        // (OBSERVED: a restored queue replaced under the read, "No matches found for Element at
        // index 1"), and a read that raced is taken again.
        var labels: [String] = []
        var attempts = 0
        while true {
            attempts += 1
            if let read = try? rows.allElementsBoundByIndex.map({ try $0.snapshot().label }) {
                labels = read
                break
            }
            guard attempts < 4 else {
                XCTFail("Up Next's rows kept changing under the read: " + app.debugDescription)
                return nil
            }
            RunLoop.current.run(until: Date().addingTimeInterval(1))
        }
        let close = app.buttons["dulcet.now-playing.close"].firstMatch
        if close.waitForExistence(timeout: 5) { close.tap() } else { app.swipeDown() }
        guard bar.waitForExistence(timeout: 10) else {
            XCTFail("Closing the player must bring the bar back: " + app.debugDescription)
            return nil
        }
        print("DULCET UP NEXT read=\(labels)")
        return labels
    }

    /// Drags `source` onto the now-playing bar until Up Next holds `count` more entries than
    /// `before`, at most twice (one local run in ten of the track proof lifted without following
    /// the synthesized touch). A drag that never lifted leaves the menu it opened under the touch,
    /// which `menuMarker` names; it is closed so Up Next can be read, and counted. A drag that
    /// dropped queues only after its deferred read, which took over 10 s on a loaded CI host, so
    /// each drag gets 30 s before the next: a retry sooner queues the source twice. Under a custom
    /// menu preview, or with no drag source at all, both drags queue nothing, so the retry cannot
    /// hide the defect.
    @MainActor
    private func dragOntoTheBar(
        _ source: XCUIElement,
        bar: XCUIElement,
        before: [String],
        adding count: Int,
        menuMarker: String,
        in app: XCUIApplication,
        compact: Bool
    ) -> (after: [String], drags: Int, menuLeftOpen: Int)? {
        // Counted only while paused: a track that ends under the proof takes the head off Up
        // Next, and a drop that landed reads as one that did not.
        let playPause = app.buttons["dulcet.mini-player.play-pause"].firstMatch
        guard playPause.waitForExistence(timeout: 10), playPause.label == "Play" else {
            XCTFail("Playback must be paused before a drag is counted; the bar's button reads \(playPause.label)")
            return nil
        }
        var after = before
        var drags = 0
        var menuLeftOpen = 0
        while drags < 2, after.count < before.count + count {
            drags += 1
            withoutIdleWaits(app) {
                source.press(forDuration: 0.8, thenDragTo: bar, withVelocity: XCUIGestureVelocity(200), thenHoldForDuration: 1.0)
            }
            let menu = app.buttons[menuMarker].firstMatch
            if menu.waitForExistence(timeout: 2) {
                menuLeftOpen += 1
                withoutIdleWaits(app) { self.closeContextMenu(marker: menuMarker, openedFrom: source.frame, in: app) }
                _ = menu.waitForNonExistence(timeout: 10)
            }
            let deadline = Date().addingTimeInterval(30)
            repeat {
                RunLoop.current.run(until: Date().addingTimeInterval(1))
                guard let read = upNextLabels(openingFrom: bar, in: app, compact: compact) else { return nil }
                after = read
            } while after.count < before.count + count && Date() < deadline
        }
        XCTAssertEqual(playPause.label, "Play",
                       "Playback started during the drags: a tap meant to close a menu pressed one of its items")
        return (after, drags, menuLeftOpen)
    }

    /// Closes an open context menu with a tap beside it, clear of the menu's column and of the
    /// element it opened from. A fixed point is not clear on iPad, where the menu opens beside
    /// its row wherever the row is: there the tap pressed a menu item (INFERRED from two CI runs,
    /// in which the playlist row proof's Up Next read exactly as the playlist's Play and then its
    /// Shuffle leave it, and playback ran under the count).
    @MainActor
    private func closeContextMenu(marker menuMarker: String, openedFrom source: CGRect, in app: XCUIApplication) {
        let window = app.frame
        let item = app.buttons[menuMarker].firstMatch
        let column = item.exists ? item.frame.insetBy(dx: -24, dy: 0) : .null
        let lifted = source.insetBy(dx: -24, dy: -24)
        let candidates = [
            CGVector(dx: 0.92, dy: 0.25), CGVector(dx: 0.92, dy: 0.6), CGVector(dx: 0.6, dy: 0.25),
            CGVector(dx: 0.6, dy: 0.6), CGVector(dx: 0.4, dy: 0.25), CGVector(dx: 0.4, dy: 0.6),
        ]
        let clear = candidates.first { offset in
            let point = CGPoint(x: window.minX + window.width * offset.dx, y: window.minY + window.height * offset.dy)
            return (column.isNull || point.x < column.minX || point.x > column.maxX) && !lifted.contains(point)
        }
        app.coordinate(withNormalizedOffset: clear ?? candidates[0]).tap()
    }

    /// Opens `element`'s context menu, requires `menuMarker` in it, and closes it with a tap
    /// outside: the condition the drags below have to get out of.
    @MainActor
    private func requireContextMenu(on element: XCUIElement, marker menuMarker: String, in app: XCUIApplication) -> Bool {
        var offered = false
        withoutIdleWaits(app) {
            let source = element.frame
            element.press(forDuration: 1.2)
            offered = app.buttons[menuMarker].firstMatch.waitForExistence(timeout: 5)
            self.closeContextMenu(marker: menuMarker, openedFrom: source, in: app)
        }
        guard offered else {
            XCTFail("The element must offer its context menu (\(menuMarker)): " + app.debugDescription)
            return false
        }
        guard app.buttons[menuMarker].firstMatch.waitForNonExistence(timeout: 10) else {
            XCTFail("A tap outside the menu must close it: " + app.debugDescription)
            return false
        }
        return true
    }

    /// An album tile dragged out of its own open context menu onto the now-playing bar adds the
    /// whole album to the end of Up Next, in album order (spec §3.1). The reader's tile has no
    /// tracks to hand over when it lifts, so they are read when it is dropped.
    @MainActor
    func testAnAlbumTileDraggedOntoTheBarJoinsUpNextOnIPhone() {
        proveAnAlbumTileDragJoinsUpNext(compact: true)
    }

    @MainActor
    func testAnAlbumTileDraggedOntoTheBarJoinsUpNextOnIPadOS() {
        proveAnAlbumTileDragJoinsUpNext(compact: false)
    }

    @MainActor
    private func proveAnAlbumTileDragJoinsUpNext(compact: Bool) {
        guard requireSimulator(compact ? .phone : .pad, "The album tile drag proof on \(compact ? "iPhone" : "iPad")"),
              let configuration = livePlaybackConfiguration(),
              let app = launchConnected(serverURL: configuration.serverURL, configuration: configuration, compact: compact),
              openLibraryDestinationForAlbums(in: app, compact: compact) else { return }
        let album = "Threshold Boundary"
        let albumOrder = ["Twenty Nine Seconds", "Thirty One Seconds", "UI Playback Canary"]
        let tile = { app.buttons.matching(identifier: "dulcet.library.album")
            .matching(NSPredicate(format: "label BEGINSWITH %@", album)).firstMatch }
        guard tile().waitForExistence(timeout: 30), scrollIntoView(tile(), in: app) else {
            XCTFail("Library must show \(album): " + app.debugDescription)
            return
        }
        guard requireContextMenu(on: tile(), marker: "Add to Playlist\u{2026}", in: app) else { return }

        guard let bar = startPlayingTheAlbumsLastTrack(album: album, last: albumOrder[2], tile: tile(), in: app) else { return }
        guard openLibraryDestinationForAlbums(in: app, compact: compact),
              tile().waitForExistence(timeout: 30), scrollIntoView(tile(), in: app),
              let before = awaitSettledUpNext(openingFrom: bar, in: app, compact: compact) else { return }
        guard let result = dragOntoTheBar(
            tile(), bar: bar, before: before, adding: albumOrder.count, menuMarker: "Add to Playlist\u{2026}",
            in: app, compact: compact
        ) else { return }
        attachScreenshot(named: "album-tile-dragged-onto-the-bar", app: app)
        let added = Array(result.after.dropFirst(before.count))
        XCTAssertEqual(result.after.count, before.count + albumOrder.count,
                       "The dragged album must join Up Next once; before=\(before) after=\(result.after)"
                       + " drags=\(result.drags) menu-left-open-after=\(result.menuLeftOpen)")
        XCTAssertEqual(result.menuLeftOpen, 0,
                       "A drag that carries on out of the open menu must lift: a menu left open under the touch is the custom preview's defect (TRAPS 48)")
        XCTAssertEqual(added.count, albumOrder.count)
        for (label, title) in zip(added, albumOrder) {
            XCTAssertTrue(label.hasPrefix(title), "Album order at the end of Up Next: \(title) expected, got \(label)")
        }
        print("DULCET ALBUM TILE DRAG before=\(before) after=\(result.after) drags=\(result.drags)"
              + " menu-left-open-after=\(result.menuLeftOpen)")
    }

    /// Each playlist edit the app offers, made where a person makes it, and read back from the
    /// disposable server after each one:
    ///
    /// 1. create: New Playlist… in Library > Playlists, named in its prompt;
    /// 2. add an album: "Add to Playlist…" in the "Threshold Boundary" tile's context menu, then
    ///    the playlist in the chooser -- the server holds the album's three tracks in its order;
    /// 3. add a track: the same from the "Twenty Nine Seconds" row's context menu, which adds it a
    ///    second time at the end;
    /// 4. remove: the fourth entry -- the duplicate -- deleted in the playlist's edit list, by
    ///    position, so the first "Twenty Nine Seconds" stays;
    /// 5. reorder: "UI Playback Canary" dragged by its handle from third to first;
    /// 6. rename: Rename… in the page's menu;
    /// 7. delete: Delete Playlist in the page's menu, confirmed -- gone from the server and the list;
    /// 8. a failed edit the server reports: a second playlist, made over `/rest` for this run, is
    ///    opened and its page's own read lands (Edit is enabled), then it is deleted on the server
    ///    as another client would; a rename made on its page is sent, cannot be saved, the page
    ///    says so, and no playlist of the new name reaches the server;
    /// 9. a failed edit the page already knows: a third playlist is opened with its page's read
    ///    held at the fault proxy; the rename prompt is opened, the playlist is deleted on the
    ///    server, and only then is the read released, so the page learns the playlist is gone
    ///    while the person is typing (the order CI met by chance once). The core refuses the
    ///    rename before anything is sent; the page must still say so, under its statement that
    ///    the playlist is no longer on the server, and nothing of the new name reaches the server.
    ///
    /// The app reaches the server through the fault proxy for the whole proof; the proxy forwards
    /// every request unchanged except the one read step 9 holds.
    ///
    /// Every name carries this run's own suffix, and whatever the run made is deleted afterwards,
    /// pass or fail. The test fails on the other device class, so an iPhone run cannot stand as
    /// iPad evidence.
    @MainActor
    private func provePlaylistEditsReachTheServer(compact expectedCompact: Bool) {
        guard requireSimulator(expectedCompact ? .phone : .pad,
                               "The playlist edits proof on \(expectedCompact ? "iPhone" : "iPad")"),
              let configuration = livePlaybackConfiguration(),
              let proxy = lyricsFaultProxyConfiguration(server: configuration) else { return }
        let album = "Threshold Boundary"
        let albumOrder = ["Twenty Nine Seconds", "Thirty One Seconds", "UI Playback Canary"]
        let run = String(UUID().uuidString.prefix(8))
        let name = "Dulcet Edit Proof " + run
        let renamed = name + " Renamed"
        let doomed = "Dulcet Failed Edit Proof " + run
        let doomedRenamed = doomed + " Renamed"
        let known = "Dulcet Known Gone Proof " + run
        let knownRenamed = known + " Renamed"
        afterTest.append { [configuration] in
            for playlist in self.serverPlaylists(configuration: configuration) ?? [] where playlist.name.contains(run) {
                _ = self.restCall("deletePlaylist", [URLQueryItem(name: "id", value: playlist.id)], configuration: configuration)
            }
        }
        guard let canaryID = serverSongID("UI Playback Canary", album: album, configuration: configuration),
              let doomedID = (restCall("createPlaylist", [
                  URLQueryItem(name: "name", value: doomed), URLQueryItem(name: "songId", value: canaryID),
              ], configuration: configuration)?["playlist"] as? [String: Any])?["id"] as? String else {
            XCTFail("The failed-edit playlist could not be made on the disposable server")
            return
        }
        guard let knownID = (restCall("createPlaylist", [
                  URLQueryItem(name: "name", value: known), URLQueryItem(name: "songId", value: canaryID),
              ], configuration: configuration)?["playlist"] as? [String: Any])?["id"] as? String else {
            XCTFail("The known-gone playlist could not be made on the disposable server")
            return
        }
        afterTest.append { [proxy] in _ = self.proxyPlaylistHold(knownID, on: false, proxy: proxy) }
        guard let app = launchConnected(serverURL: proxy.url, configuration: configuration, compact: expectedCompact),
              openLibraryPlaylists(in: app, compact: expectedCompact) else { return }

        // 1. Create.
        let new = app.buttons["dulcet.playlists.new"].firstMatch
        guard new.waitForExistence(timeout: 15) else {
            XCTFail("Playlists must offer New Playlist: " + app.debugDescription)
            return
        }
        new.tap()
        guard submitNamePrompt(name, confirm: "Create", in: app) else { return }
        guard let playlistID = awaitServerPlaylist(named: name, configuration: configuration) else {
            XCTFail("The new playlist must reach the server")
            return
        }
        XCTAssertEqual(serverPlaylistEntries(playlistID, configuration: configuration), [], "A new playlist starts empty")

        // 2. Add an album from its tile's context menu.
        guard openLibraryDestinationForAlbums(in: app, compact: expectedCompact) else { return }
        let tile = app.buttons.matching(identifier: "dulcet.library.album")
            .matching(NSPredicate(format: "label BEGINSWITH %@", album)).firstMatch
        guard tile.waitForExistence(timeout: 30), scrollIntoView(tile, in: app),
              addToPlaylist(name, fromContextMenuOf: tile, in: app) else { return }
        XCTAssertEqual(awaitServerPlaylistEntries(playlistID, albumOrder, configuration: configuration), albumOrder,
            "Adding the album must append its tracks in album order")

        // 3. Add a track from its row's context menu.
        tile.tap()
        let row = app.buttons.matching(identifier: "dulcet.reader.track")
            .matching(NSPredicate(format: "label BEGINSWITH %@", albumOrder[0] + ", ")).firstMatch
        guard row.waitForExistence(timeout: 15), scrollIntoView(row, in: app),
              addToPlaylist(name, fromContextMenuOf: row, in: app) else { return }
        let withTrack = albumOrder + [albumOrder[0]]
        XCTAssertEqual(awaitServerPlaylistEntries(playlistID, withTrack, configuration: configuration), withTrack,
            "Adding a track must append it, even when the playlist already holds it")

        // 4-5. Remove by position, then reorder, in the edit list.
        guard openLibraryPlaylists(in: app, compact: expectedCompact), openPlaylistRow(name, in: app) else { return }
        let edit = app.buttons["dulcet.playlist.edit"].firstMatch
        guard edit.waitForExistence(timeout: 10), waitForEnabled(edit, timeout: 15) else {
            XCTFail("The person's own playlist must offer Edit once its entries are read: " + app.debugDescription)
            return
        }
        edit.tap()
        guard let entries = playlistEditEntries(count: 4, in: app) else { return }
        guard deleteEditEntry(entries[3], in: app) else { return }
        XCTAssertEqual(awaitServerPlaylistEntries(playlistID, albumOrder, configuration: configuration), albumOrder,
            "Removing the fourth entry must remove that entry alone, not the first of the same track")
        guard let three = playlistEditEntries(count: 3, in: app) else { return }
        let handle = three[2].buttons.matching(NSPredicate(format: "label BEGINSWITH %@", "Reorder")).firstMatch
        guard handle.waitForExistence(timeout: 5) else {
            XCTFail("The edit list must offer a reorder handle on each entry: " + app.debugDescription)
            return
        }
        // Slow, with a hold at the drop, as the queue drags above. A fast synthesized drag can be
        // lost before the list lifts the row: run 37483248578's recording shows the edit list
        // unmoved after one, so nothing was sent. Only a drag the screen never showed is repeated,
        // once; a screen that moved without the server following still fails below.
        let reordered = [albumOrder[2], albumOrder[0], albumOrder[1]]
        for attempt in 1...2 {
            handle.press(forDuration: 0.8, thenDragTo: three[0], withVelocity: XCUIGestureVelocity(200),
                thenHoldForDuration: 1.0)
            if attempt == 2 || playlistEditEntryTitles(in: app) != albumOrder { break }
        }
        XCTAssertEqual(playlistEditEntryTitles(in: app), reordered, "The edit list must show the dragged order")
        XCTAssertEqual(awaitServerPlaylistEntries(playlistID, reordered, configuration: configuration), reordered,
            "Dragging the third entry to the top must reorder the playlist on the server")
        app.buttons["dulcet.playlist.done"].firstMatch.tap()

        // 6. Rename.
        let title = app.staticTexts["dulcet.playlist.title"].firstMatch
        guard renamePlaylist(to: renamed, in: app) else { return }
        XCTAssertTrue(waitForLabel(renamed, of: title, timeout: 5), "The page must show the new name; title=\(title.label)")
        XCTAssertEqual(awaitServerPlaylistName(playlistID, renamed, configuration: configuration), renamed,
            "The rename must reach the server")

        // 7. Delete.
        guard deletePlaylistFromItsPage(in: app) else { return }
        XCTAssertTrue(awaitServerPlaylistAbsent(playlistID, configuration: configuration),
            "The deleted playlist must be gone from the server")
        let gone = app.buttons.matching(identifier: "dulcet.library.playlist")
            .matching(NSPredicate(format: "label BEGINSWITH %@", renamed)).firstMatch
        XCTAssertFalse(gone.waitForExistence(timeout: 3) && gone.isHittable, "The list must no longer show the deleted playlist")

        // 8. A failed edit the server reports: the playlist is deleted elsewhere after its page has
        // read it, so the rename is sent. Edit is enabled once the page's own read has its entries;
        // deleting before that would leave the order to chance (step 9 drives the other order).
        guard openLibraryPlaylists(in: app, compact: expectedCompact), openPlaylistRow(doomed, in: app) else { return }
        let doomedEdit = app.buttons["dulcet.playlist.edit"].firstMatch
        guard doomedEdit.waitForExistence(timeout: 10), waitForEnabled(doomedEdit, timeout: 30) else {
            XCTFail("The page must read the playlist before the other client deletes it: " + app.debugDescription)
            return
        }
        guard restCall("deletePlaylist", [URLQueryItem(name: "id", value: doomedID)], configuration: configuration) != nil,
              awaitServerPlaylistAbsent(doomedID, configuration: configuration) else {
            XCTFail("The other client's delete must reach the server first")
            return
        }
        guard renamePlaylist(to: doomedRenamed, in: app) else { return }
        let problem = app.descendants(matching: .any)["dulcet.playlist.problem"].firstMatch
        XCTAssertTrue(problem.waitForExistence(timeout: 30),
            "A rename the server cannot apply must be said on the playlist's page: " + app.debugDescription)
        let problemText = problem.exists ? problem.staticTexts.allElementsBoundByIndex.map(\.label).joined(separator: " | ") : "<none>"
        XCTAssertFalse((serverPlaylists(configuration: configuration) ?? []).contains { $0.name == doomedRenamed },
            "The failed rename must not reach the server under any id")

        // 9. A failed edit the page already knows: its read is held until the playlist is gone
        // from the server and the rename prompt is open, then released.
        guard let armed = proxyPlaylistHold(knownID, on: true, proxy: proxy), armed.holding else {
            XCTFail("The fault proxy must hold the playlist's read")
            return
        }
        guard openLibraryPlaylists(in: app, compact: expectedCompact), openPlaylistRow(known, in: app) else { return }
        guard let heldRead = awaitPlaylistObservations(knownID, proxy: proxy, timeout: 15, until: { $0.held >= 1 }) else {
            XCTFail("The page must have asked for the playlist, and the proxy must be holding that read")
            return
        }
        guard openRenamePrompt(in: app) else { return }
        guard restCall("deletePlaylist", [URLQueryItem(name: "id", value: knownID)], configuration: configuration) != nil,
              awaitServerPlaylistAbsent(knownID, configuration: configuration) else {
            XCTFail("The other client's delete must reach the server before the held read")
            return
        }
        guard proxyPlaylistHold(knownID, on: false, proxy: proxy) != nil,
              let releasedRead = awaitPlaylistObservations(knownID, proxy: proxy, timeout: 10, until: { $0.released >= 1 }) else {
            XCTFail("The held read must be released to the server")
            return
        }
        // The page says the playlist is gone before the rename is confirmed: the order is met,
        // not assumed.
        let statement = app.descendants(matching: .any)["dulcet.reader.unavailable"].firstMatch
        XCTAssertTrue(statement.waitForExistence(timeout: 20),
            "The released read must tell the page the playlist is gone, under the open prompt: " + app.debugDescription)
        guard submitNamePrompt(knownRenamed, confirm: "Rename", in: app) else { return }
        let knownProblem = app.descendants(matching: .any)["dulcet.playlist.problem"].firstMatch
        XCTAssertTrue(knownProblem.waitForExistence(timeout: 15),
            "A rename refused because the playlist is gone must be said on the playlist's page: " + app.debugDescription)
        let knownText = knownProblem.exists
            ? knownProblem.staticTexts.allElementsBoundByIndex.map(\.label).joined(separator: " | ") : "<none>"
        XCTAssertTrue(knownText.contains("deleted"), "The page must say the playlist is gone; says \(knownText.debugDescription)")
        // Still there after the notice has gone (the notice lasts four seconds), and reachable.
        RunLoop.current.run(until: Date().addingTimeInterval(6))
        attachScreenshot(named: "playlist-known-gone-problem-\(expectedCompact ? "compact" : "regular")", app: app)
        let dismiss = knownProblem.buttons["OK"].firstMatch
        XCTAssertTrue(knownProblem.exists && dismiss.exists && dismiss.isHittable,
            "The page's problem must outlast the notice and be reachable: " + app.debugDescription)
        XCTAssertTrue(statement.exists, "The page must still say the playlist is no longer on the server")
        XCTAssertFalse(app.buttons["dulcet.playlist.play"].exists,
            "A gone playlist's page offers nothing to play: " + app.debugDescription)
        XCTAssertFalse((serverPlaylists(configuration: configuration) ?? []).contains { $0.name == knownRenamed },
            "The refused rename must not reach the server under any id")
        print("DULCET PLAYLIST EDITS PROOF PASS destination=\(expectedCompact ? "compact" : "regular")"
            + " window-width=\(Int(app.windows.firstMatch.frame.width)) created=\(name.debugDescription)"
            + " album-added=\(albumOrder.count) track-added=1 removed-index=3 reordered=\(reordered) renamed=true deleted=true"
            + " failed-rename-problem=\(problemText.debugDescription)"
            + " known-gone-held=\(heldRead.held) released=\(releasedRead.released) timed-out=\(releasedRead.timedOut)"
            + " known-gone-problem=\(knownText.debugDescription)")
    }

    /// The library place that shows album tiles: Library on a phone, Albums in the sidebar on a
    /// regular width.
    @MainActor
    private func openLibraryDestinationForAlbums(in app: XCUIApplication, compact: Bool) -> Bool {
        guard openDestination("Library", sidebarIdentifier: "dulcet.sidebar.library", in: app, compact: compact) else {
            return false
        }
        if compact {
            // The tab keeps its stack; back to its root, where the Recently Added tiles are.
            let back = app.navigationBars.buttons["BackButton"].firstMatch
            var pops = 0
            while back.exists, back.isHittable, pops < 4 {
                back.tap()
                pops += 1
            }
            return true
        }
        return openSidebarLibrarySection("albums", in: app)
    }

    /// Opens the named playlist from the Playlists list.
    @MainActor
    private func openPlaylistRow(_ name: String, in app: XCUIApplication) -> Bool {
        let row = app.buttons.matching(identifier: "dulcet.library.playlist")
            .matching(NSPredicate(format: "label BEGINSWITH %@", name)).firstMatch
        guard row.waitForExistence(timeout: 30), scrollIntoView(row, in: app) else {
            XCTFail("Playlists must list \(name): " + app.debugDescription)
            return false
        }
        row.tap()
        let title = app.staticTexts["dulcet.playlist.title"].firstMatch
        guard title.waitForExistence(timeout: 10), waitForLabel(name, of: title, timeout: 10) else {
            XCTFail("The row must open \(name): " + app.debugDescription)
            return false
        }
        return true
    }

    /// Runs `body` without XCUITest's wait for the app to go idle before and after each event.
    ///
    /// While a context menu is open on a row that is also a drag source, UIKit keeps the drag's
    /// lift armed: a paused animation stays on a view the app never draws (OBSERVED in an lldb
    /// dump of the layer tree, iPhone 17 Pro simulator, iOS 26.5), so XCUITest's quiescence check
    /// never passes and each event waits its full 60 s before going ahead. A menu item tapped
    /// inside this block is tapped at once. The option is XCUIApplication's own private
    /// interaction-options call (bit 0 skips the wait before the event, bit 1 the wait after); if
    /// a later Xcode drops or changes it, `body` runs with the waits, which is slower but correct.
    @MainActor
    private func withoutIdleWaits(_ app: XCUIApplication, _ body: @escaping () -> Void) {
        let selector = NSSelectorFromString("_performWithInteractionOptions:block:")
        guard let method = class_getInstanceMethod(type(of: app), selector),
              let encoding = method_getTypeEncoding(method).map({ String(cString: $0) }),
              encoding.filter({ !$0.isNumber }) == "v@:I@?" else {
            print("DULCET withoutIdleWaits: the interaction-options call is missing or changed; waiting for idle")
            body()
            return
        }
        typealias Perform = @convention(c) (AnyObject, Selector, UInt32, @convention(block) () -> Void) -> Void
        let perform = unsafeBitCast(method_getImplementation(method), to: Perform.self)
        perform(app, selector, 3, body)
    }

    /// "Add to Playlist…" from the element's context menu, then the named playlist in the chooser,
    /// which closes once it is chosen.
    @MainActor
    private func addToPlaylist(_ name: String, fromContextMenuOf element: XCUIElement, in app: XCUIApplication) -> Bool {
        let add = app.buttons["Add to Playlist\u{2026}"].firstMatch
        var offered = false
        // A track row is also a drag source, and the app does not go idle while its menu is open
        // (see withoutIdleWaits); the press and the tap would each wait a minute for it.
        withoutIdleWaits(app) {
            element.press(forDuration: 1.2)
            offered = add.waitForExistence(timeout: 5)
            if offered { add.tap() }
        }
        guard offered else {
            XCTFail("The context menu must offer Add to Playlist…: " + app.debugDescription)
            return false
        }
        let choice = app.buttons.matching(identifier: "dulcet.addToPlaylist.playlist")
            .matching(NSPredicate(format: "label BEGINSWITH %@", name)).firstMatch
        guard choice.waitForExistence(timeout: 15) else {
            XCTFail("The chooser must offer \(name): " + app.debugDescription)
            return false
        }
        choice.tap()
        let sheetGone = NSPredicate(format: "exists == false")
        guard XCTWaiter.wait(for: [XCTNSPredicateExpectation(predicate: sheetGone, object: choice)], timeout: 5) == .completed else {
            XCTFail("The chooser must close once a playlist is chosen: " + app.debugDescription)
            return false
        }
        return true
    }

    /// The edit list's entries, top first, once exactly `count` are shown.
    @MainActor
    private func playlistEditEntries(count: Int, in app: XCUIApplication) -> [XCUIElement]? {
        // The identifier lands on the entry's texts, not on the List's cell that holds them.
        let cells = app.cells.containing(.staticText, identifier: "dulcet.playlist.entry")
        let deadline = Date().addingTimeInterval(15)
        while cells.count != count, Date() < deadline {
            RunLoop.current.run(until: Date().addingTimeInterval(0.25))
        }
        guard cells.count == count else {
            XCTFail("The edit list must show \(count) entries; shows \(cells.count): " + app.debugDescription)
            return nil
        }
        return (0..<count).map { cells.element(boundBy: $0) }
    }

    /// The edit list's entry titles, top to bottom, after the list settles for a moment. The
    /// identifier reaches both of an entry's texts; the title is the first.
    @MainActor
    private func playlistEditEntryTitles(in app: XCUIApplication) -> [String] {
        RunLoop.current.run(until: Date().addingTimeInterval(1))
        let cells = app.cells.containing(.staticText, identifier: "dulcet.playlist.entry").allElementsBoundByIndex
        return cells.sorted { $0.frame.minY < $1.frame.minY }
            .map { $0.staticTexts.matching(identifier: "dulcet.playlist.entry").firstMatch.label }
    }

    /// Deletes one entry of the edit list: its leading delete control, then the Delete it
    /// reveals. The control is exposed as the cell's "remove" image, not as a button (OBSERVED on
    /// iOS 26.5), and a swipe across an entry does nothing while the list is editing.
    @MainActor
    private func deleteEditEntry(_ entry: XCUIElement, in app: XCUIApplication) -> Bool {
        let control = entry.images["minus.circle.fill"].firstMatch
        guard control.waitForExistence(timeout: 5) else {
            XCTFail("Each entry must offer its delete control in the edit list: " + app.debugDescription)
            return false
        }
        control.tap()
        // Drawn beside the cell it deletes, not inside it, and the only Delete on screen.
        let confirm = app.buttons.matching(NSPredicate(format: "label == %@", "Delete"))
        guard confirm.firstMatch.waitForExistence(timeout: 5), confirm.count == 1 else {
            XCTFail("The delete control must reveal one Delete: " + app.debugDescription)
            return false
        }
        confirm.firstMatch.tap()
        return true
    }

    /// Types `name` into the open name prompt, replacing whatever it holds, and confirms it.
    @MainActor
    private func submitNamePrompt(_ name: String, confirm: String, in app: XCUIApplication) -> Bool {
        let alert = app.alerts.firstMatch
        let field = alert.textFields.firstMatch
        guard alert.waitForExistence(timeout: 5), field.waitForExistence(timeout: 5) else {
            XCTFail("The prompt must ask for a name: " + app.debugDescription)
            return false
        }
        // Command-A is not reliable in an alert's field (it selected nothing on one run of two),
        // so a held name is deleted character by character. A plain tap puts the cursor where it
        // lands -- mid-name, OBSERVED 3 of 3 -- and deletes only take what is before it, so the
        // tap is at the trailing edge, and the field is cleared until it reads empty (an empty
        // field reports its placeholder as its value).
        for _ in 0..<3 {
            let current = field.value as? String ?? ""
            if current.isEmpty || current == field.placeholderValue { break }
            field.coordinate(withNormalizedOffset: CGVector(dx: 0.97, dy: 0.5)).tap()
            field.typeText(String(repeating: XCUIKeyboardKey.delete.rawValue, count: current.count))
        }
        if (field.value(forKey: "hasKeyboardFocus") as? Bool) != true { field.tap() }
        field.typeText(name)
        // typeText can return before the app's field model has every character: OBSERVED in CI run
        // 37166450657 (iPad), where the check read a short value and the failure message, read a
        // second later, the whole name. Poll one snapshot at a time, briefly.
        var typed = (try? field.snapshot())?.value as? String
        let typedDeadline = ContinuousClock.now.advanced(by: .seconds(3))
        while typed != name, ContinuousClock.now < typedDeadline {
            Thread.sleep(forTimeInterval: 0.1)
            typed = (try? field.snapshot())?.value as? String
        }
        guard typed == name else {
            XCTFail("The name field must hold exactly the new name; value=\(String(describing: typed))")
            return false
        }
        alert.buttons[confirm].firstMatch.tap()
        return true
    }

    /// Rename… from the playlist page's menu, and the new name confirmed.
    @MainActor
    private func renamePlaylist(to name: String, in app: XCUIApplication) -> Bool {
        openRenamePrompt(in: app) && submitNamePrompt(name, confirm: "Rename", in: app)
    }

    /// Rename… from the playlist page's menu, leaving its prompt open.
    @MainActor
    private func openRenamePrompt(in app: XCUIApplication) -> Bool {
        let more = app.buttons["dulcet.playlist.more"].firstMatch
        guard more.waitForExistence(timeout: 10) else {
            XCTFail("The person's own playlist must offer its menu: " + app.debugDescription)
            return false
        }
        more.tap()
        let rename = app.buttons.matching(NSPredicate(format: "label BEGINSWITH %@", "Rename")).firstMatch
        guard rename.waitForExistence(timeout: 5) else {
            XCTFail("The menu must offer Rename: " + app.debugDescription)
            return false
        }
        rename.tap()
        guard app.alerts.firstMatch.waitForExistence(timeout: 5) else {
            XCTFail("Rename… must open its prompt: " + app.debugDescription)
            return false
        }
        return true
    }

    /// Delete Playlist from the page's menu, confirmed; the page closes.
    @MainActor
    private func deletePlaylistFromItsPage(in app: XCUIApplication) -> Bool {
        let more = app.buttons["dulcet.playlist.more"].firstMatch
        guard more.waitForExistence(timeout: 10) else {
            XCTFail("The person's own playlist must offer its menu: " + app.debugDescription)
            return false
        }
        more.tap()
        let delete = app.buttons["Delete Playlist"].firstMatch
        guard delete.waitForExistence(timeout: 5) else {
            XCTFail("The menu must offer Delete Playlist: " + app.debugDescription)
            return false
        }
        delete.tap()
        let confirm = app.alerts.firstMatch.buttons["Delete Playlist"].firstMatch
        guard confirm.waitForExistence(timeout: 5) else {
            XCTFail("Deleting must be confirmed first: " + app.debugDescription)
            return false
        }
        confirm.tap()
        let title = app.staticTexts["dulcet.playlist.title"].firstMatch
        let closed = NSPredicate(format: "exists == false")
        guard XCTWaiter.wait(for: [XCTNSPredicateExpectation(predicate: closed, object: title)], timeout: 10) == .completed else {
            XCTFail("The deleted playlist's page must close: " + app.debugDescription)
            return false
        }
        return true
    }

    private struct ServerPlaylist {
        let id: String
        let name: String
    }

    /// The account's playlists on the server, from `getPlaylists`.
    private func serverPlaylists(configuration: LivePlaybackConfiguration) -> [ServerPlaylist]? {
        guard let envelope = restCall("getPlaylists", [], configuration: configuration) else { return nil }
        let rows = (envelope["playlists"] as? [String: Any])?["playlist"] as? [[String: Any]] ?? []
        return rows.compactMap { row in
            guard let id = row["id"] as? String, let name = row["name"] as? String else { return nil }
            return ServerPlaylist(id: id, name: name)
        }
    }

    /// The id of the one server playlist named `name`, once there is one.
    @MainActor
    private func awaitServerPlaylist(named name: String, configuration: LivePlaybackConfiguration) -> String? {
        let deadline = Date().addingTimeInterval(30)
        repeat {
            let matches = (serverPlaylists(configuration: configuration) ?? []).filter { $0.name == name }
            if matches.count == 1 { return matches[0].id }
            XCTAssertLessThanOrEqual(matches.count, 1, "One create must make one playlist named \(name)")
            RunLoop.current.run(until: Date().addingTimeInterval(1))
        } while Date() < deadline
        return nil
    }

    /// The playlist's entries on the server, as titles in order; nil when it cannot be read.
    private func serverPlaylistEntries(_ id: String, configuration: LivePlaybackConfiguration) -> [String]? {
        guard let playlist = restCall("getPlaylist", [URLQueryItem(name: "id", value: id)], configuration: configuration)?[
            "playlist"] as? [String: Any] else { return nil }
        return (playlist["entry"] as? [[String: Any]] ?? []).compactMap { $0["title"] as? String }
    }

    @MainActor
    private func awaitServerPlaylistEntries(_ id: String, _ expected: [String], configuration: LivePlaybackConfiguration) -> [String]? {
        let deadline = Date().addingTimeInterval(30)
        var observed = serverPlaylistEntries(id, configuration: configuration)
        while observed != expected, Date() < deadline {
            RunLoop.current.run(until: Date().addingTimeInterval(1))
            observed = serverPlaylistEntries(id, configuration: configuration)
        }
        return observed
    }

    @MainActor
    private func awaitServerPlaylistName(_ id: String, _ expected: String, configuration: LivePlaybackConfiguration) -> String? {
        let deadline = Date().addingTimeInterval(30)
        var observed: String?
        repeat {
            observed = (restCall("getPlaylist", [URLQueryItem(name: "id", value: id)], configuration: configuration)?[
                "playlist"] as? [String: Any])?["name"] as? String
            if observed == expected { break }
            RunLoop.current.run(until: Date().addingTimeInterval(1))
        } while Date() < deadline
        return observed
    }

    /// Whether the playlist is gone from the account's playlists, polled for up to 30 seconds.
    @MainActor
    private func awaitServerPlaylistAbsent(_ id: String, configuration: LivePlaybackConfiguration) -> Bool {
        let deadline = Date().addingTimeInterval(30)
        repeat {
            if let playlists = serverPlaylists(configuration: configuration), !playlists.contains(where: { $0.id == id }) {
                return true
            }
            RunLoop.current.run(until: Date().addingTimeInterval(1))
        } while Date() < deadline
        return false
    }

    /// Now Playing's lyrics panel shows the server's synced lyrics and lights the current line as
    /// media time moves (spec §18.4). "Twenty Nine Seconds" carries embedded synced lyrics in three
    /// languages; an English-preferring simulator is shown the English layer, whose first line
    /// starts at two seconds.
    @MainActor
    func testTheLyricsPanelShowsTheSyncedLineThatIsPlaying() {
        guard let configuration = livePlaybackConfiguration() else { return }
        let album = "Threshold Boundary"
        let track = "Twenty Nine Seconds"
        let line = "Dulcet English line one"
        let app = XCUIApplication()
        app.launchArguments += [
            "-dulcet-debug-connect-account",
            "-dulcet-debug-account-server-url", configuration.serverURL,
            "-dulcet-debug-account-username", configuration.username,
            "-dulcet-debug-account-password", configuration.password,
        ]
        app.launch()
        let window = app.windows.firstMatch
        XCTAssertTrue(window.waitForExistence(timeout: 10), "The app window must exist")
        let compact = window.frame.width < 700
        guard awaitLiveAccountConnection(in: app, compact: compact) else {
            XCTFail("The live account connection must succeed first")
            return
        }
        guard openLibraryAlbum(album, in: app, compact: compact) else { return }
        guard tapAlbumPlay(in: app) else { return }
        guard openNowPlayingFromBar(in: app, expectingTitle: track) else { return }
        let toggle = app.buttons["dulcet.now-playing.lyrics"].firstMatch
        guard toggle.waitForExistence(timeout: 10) else {
            XCTFail("Now Playing must offer lyrics: " + app.debugDescription)
            return
        }
        toggle.tap()
        let panel = app.descendants(matching: .any)["dulcet.lyrics.panel"].firstMatch
        XCTAssertTrue(panel.waitForExistence(timeout: 10), "The lyrics panel must open: " + app.debugDescription)
        let shown = app.staticTexts.matching(NSPredicate(format: "label == %@", line)).firstMatch
        XCTAssertTrue(shown.waitForExistence(timeout: 20),
            "The panel must show the English layer's line: " + app.debugDescription)
        // The control: a lit line proves the cursor ran against media time, not only that text
        // was drawn. Nothing is lit before two seconds, so this also needs playback to progress.
        let current = app.staticTexts["dulcet.lyrics.line.current"].firstMatch
        XCTAssertTrue(current.waitForExistence(timeout: 25),
            "A line must light as the track plays: " + app.debugDescription)
        let lit = current.exists ? current.label : "<none>"
        XCTAssertTrue(lit.hasPrefix("Dulcet English line"), "The lit line must be one of the English layer's; lit=\(lit)")
        print("DULCET LYRICS PROOF PASS destination=\(compact ? "compact" : "regular") lit=\(lit.debugDescription)")
    }

    /// Every state of the lyrics panel (spec §18.4) rendered through the iPhone app, in the sheet.
    @MainActor
    func testTheLyricsPanelShowsEveryStateOnIPhone() {
        proveLyricsPanelStates(compact: true)
    }

    /// Every state of the lyrics panel (spec §18.4) rendered through the iPad app, beside the
    /// player in the full-screen player.
    @MainActor
    func testTheLyricsPanelShowsEveryStateOnIPadOS() {
        proveLyricsPanelStates(compact: false)
    }

    /// The lyrics panel draws each state the core can publish, through the app, for a track the
    /// disposable server really holds in that shape -- each shape first read back from the server,
    /// so a corpus that drifted fails here as a precondition and never as a missing line:
    ///
    /// 1. synced: "Twenty Nine Seconds" lights an English line as media time moves;
    /// 2. plain: "Ogg Probe"'s two unsynced sidecar lines, and nothing lit;
    /// 3. none: "UI Playback Canary" says it has no lyrics, with no spinner left behind;
    /// 4. a failed read: "Thirty One Seconds" read through `tools/conformance-env/lyrics-fault-proxy`,
    ///    which answers its lyrics read with HTTP 500 until the test disarms it. The panel says the
    ///    read failed and offers Try Again; the proxy's own count shows the failure was met, and
    ///    after it is disarmed Try Again sends a new read and the synced lines appear.
    ///
    /// Each state is its own launch, so one state's panel can never stand in for the next. The
    /// failed read needs a track this device has never stored lyrics for -- a stored document is
    /// painted first and the failure then shows as a note over it, with no Try Again -- so no
    /// earlier phase on the simulator may open that track's lyrics, and this test fails, naming
    /// why, when one did. The test fails on the other device class, so an iPhone run cannot stand
    /// as iPad evidence.
    @MainActor
    private func proveLyricsPanelStates(compact expectedCompact: Bool) {
        guard ProcessInfo.processInfo.environment["SIMULATOR_UDID"] != nil else {
            XCTFail("This proof requires a simulator; a physical device is not the destination it names")
            return
        }
        let expectedIdiom: UIUserInterfaceIdiom = expectedCompact ? .phone : .pad
        guard UIDevice.current.userInterfaceIdiom == expectedIdiom else {
            XCTFail("This proof names \(expectedCompact ? "iPhone" : "iPad") but runs on idiom \(UIDevice.current.userInterfaceIdiom.rawValue)")
            return
        }
        guard let configuration = livePlaybackConfiguration(),
              let proxy = lyricsFaultProxyConfiguration(server: configuration) else { return }
        let album = "Threshold Boundary"

        // The server's own shapes, read before anything is driven.
        guard let synced = serverSongID("Twenty Nine Seconds", album: album, configuration: configuration),
              let plain = serverSongID("Ogg Probe", album: "Dulcet Conformance", configuration: configuration),
              let none = serverSongID("UI Playback Canary", album: album, configuration: configuration),
              let failing = serverSongID("Thirty One Seconds", album: album, configuration: configuration) else { return }
        let layers = { (id: String) in self.serverLyricsLayers(songID: id, configuration: configuration) }
        guard let syncedLayers = layers(synced), syncedLayers.contains(where: { $0.synced && $0.lines.contains("Dulcet English line one") }),
              let plainLayers = layers(plain), plainLayers.count == 1, plainLayers.allSatisfy({ !$0.synced }),
              plainLayers[0].lines == ["Dulcet plain sidecar line one", "Dulcet plain sidecar line two"],
              let noLayers = layers(none), noLayers.isEmpty,
              let failingLayers = layers(failing), failingLayers.contains(where: { $0.synced }) else {
            XCTFail("The disposable server's lyrics fixtures drifted: synced, plain, none and a synced sidecar are required")
            return
        }

        // 1. Synced: a line lights as media time moves.
        guard let syncedApp = launchConnected(serverURL: configuration.serverURL, configuration: configuration,
                                              compact: expectedCompact),
              openLibraryAlbum(album, in: syncedApp, compact: expectedCompact) else { return }
        guard tapAlbumPlay(in: syncedApp) else { return }
        guard openNowPlayingFromBar(in: syncedApp, expectingTitle: "Twenty Nine Seconds"),
              let syncedPanel = openLyricsPanel(in: syncedApp) else { return }
        let current = syncedApp.staticTexts["dulcet.lyrics.line.current"].firstMatch
        XCTAssertTrue(current.waitForExistence(timeout: 25), "A synced line must light as the track plays: " + syncedApp.debugDescription)
        let lit = current.exists ? current.label : "<none>"
        XCTAssertTrue(lit.hasPrefix("Dulcet English line"), "The lit line must be the English layer's; lit=\(lit)")
        var placement = "sheet"
        if !expectedCompact {
            // Beside the player: the panel starts right of the player's own title, in one row.
            let title = syncedApp.staticTexts["dulcet.now-playing.title"].firstMatch
            XCTAssertTrue(title.exists, "The player's title must be on screen beside the lyrics")
            placement = "panel=\(syncedPanel.frame) title=\(title.frame)"
            XCTAssertGreaterThanOrEqual(syncedPanel.frame.minX, title.frame.maxX,
                "On a regular width the lyrics must sit beside the player, not over or under it; \(placement)")
            XCTAssertTrue(syncedPanel.frame.minY < title.frame.maxY && title.frame.minY < syncedPanel.frame.maxY,
                "The lyrics and the player must share a row; \(placement)")
        }
        attachScreenshot(named: "lyrics-synced", app: syncedApp)

        // 2. Plain: both lines, and nothing lit.
        guard let plainApp = launchConnected(serverURL: configuration.serverURL, configuration: configuration,
                                             compact: expectedCompact),
              playFromSearch("Ogg Probe", in: plainApp, compact: expectedCompact) else { return }
        // A one-second track, played from a search that matches it alone, so it is the whole
        // queue: the finished queue keeps it as Now Playing's track (spec §14.3). Played from its
        // album it would advance to the next track and show that track's lyrics instead.
        guard openNowPlayingFromBar(in: plainApp, expectingTitle: "Ogg Probe"),
              openLyricsPanel(in: plainApp) != nil else { return }
        let plainLines = plainApp.staticTexts.matching(identifier: "dulcet.lyrics.line")
        for line in plainLayers[0].lines {
            XCTAssertTrue(plainLines.matching(NSPredicate(format: "label == %@", line)).firstMatch.waitForExistence(timeout: 20),
                "Plain lyrics must show \(line.debugDescription): " + plainApp.debugDescription)
        }
        XCTAssertFalse(plainApp.staticTexts["dulcet.lyrics.line.current"].firstMatch.exists,
            "Plain lyrics have no current line, so none may be lit")
        let plainShown = plainLines.count
        attachScreenshot(named: "lyrics-plain", app: plainApp)

        // 3. None: said, and no spinner left.
        guard let noneApp = launchConnected(serverURL: configuration.serverURL, configuration: configuration,
                                            compact: expectedCompact),
              openLibraryAlbum(album, in: noneApp, compact: expectedCompact),
              playAlbumTrack("UI Playback Canary", in: noneApp),
              openNowPlayingFromBar(in: noneApp, expectingTitle: "UI Playback Canary"),
              openLyricsPanel(in: noneApp) != nil else { return }
        let noLyrics = noneApp.staticTexts["dulcet.lyrics.none"].firstMatch
        XCTAssertTrue(noLyrics.waitForExistence(timeout: 20), "A track with no lyrics must say so: " + noneApp.debugDescription)
        // Read now: the next launch replaces this app, and its elements with it.
        let noneShown = noLyrics.exists ? noLyrics.label : "<none>"
        XCTAssertEqual(noneShown, "No lyrics for this song.")
        XCTAssertFalse(noneApp.activityIndicators["Loading lyrics"].firstMatch.exists,
            "A track the server has no lyrics for must never show a spinner")
        attachScreenshot(named: "lyrics-none", app: noneApp)

        // 4. A failed read, then Try Again.
        guard proxyLyricsFailure(song: failing, on: true, proxy: proxy) != nil else { return }
        afterTest.append { _ = self.proxyLyricsFailure(song: failing, on: false, proxy: proxy) }
        guard let failedApp = launchConnected(serverURL: proxy.url, configuration: configuration,
                                              compact: expectedCompact),
              // From a search that matches it alone, so the queue ends on it: played from its
              // album, a slow read outlived the 31 s track and the panel moved on to the next
              // track's lyrics (OBSERVED locally under host load).
              playFromSearch("Thirty One Seconds", in: failedApp, compact: expectedCompact),
              openNowPlayingFromBar(in: failedApp, expectingTitle: "Thirty One Seconds"),
              openLyricsPanel(in: failedApp) != nil else { return }
        let unavailable = failedApp.staticTexts["dulcet.lyrics.unavailable"].firstMatch
        let retry = failedApp.buttons["dulcet.lyrics.retry"].firstMatch
        guard unavailable.waitForExistence(timeout: 30), retry.waitForExistence(timeout: 5) else {
            let saved = failedApp.staticTexts.matching(NSPredicate(format: "label BEGINSWITH %@", "Showing saved lyrics")).firstMatch
            XCTFail(saved.exists
                ? "This device already holds Thirty One Seconds' lyrics, so the failure shows as a note over them; the proof needs a simulator that never opened them"
                : "A failed read must say so and offer Try Again: " + failedApp.debugDescription)
            return
        }
        XCTAssertTrue(unavailable.label.hasPrefix("Lyrics couldn\u{2019}t be loaded"),
            "The panel must say the lyrics could not be loaded; message=\(unavailable.label)")
        guard let afterFailure = proxyLyricsFailure(song: failing, on: false, proxy: proxy) else { return }
        XCTAssertGreaterThanOrEqual(afterFailure.failed, 1,
            "The proxy must have failed this track's lyrics read; otherwise the state above came from somewhere else")
        attachScreenshot(named: "lyrics-failed", app: failedApp)
        retry.tap()
        let recovered = failedApp.staticTexts.matching(NSPredicate(format: "label BEGINSWITH %@", "Dulcet synced line")).firstMatch
        XCTAssertTrue(recovered.waitForExistence(timeout: 30), "Try Again must read the lyrics again and show them: " + failedApp.debugDescription)
        XCTAssertFalse(unavailable.exists, "The failure must give way to the lyrics")
        let afterRetry = proxyObservations(song: failing, proxy: proxy)
        XCTAssertGreaterThanOrEqual(afterRetry?.forwardedAfterDisarm ?? 0, 1,
            "Try Again must send a new lyrics read; observed \(String(describing: afterRetry))")
        print("DULCET LYRICS STATES PROOF PASS destination=\(expectedCompact ? "compact" : "regular")"
            + " synced-lit=\(lit.debugDescription) placement=\(placement) plain-lines=\(plainShown)"
            + " none=\(noneShown.debugDescription) failed-reads=\(afterFailure.failed)"
            + " retry-reads=\(afterRetry?.forwardedAfterDisarm ?? 0)")
    }

    /// A streaming-quality cap chosen in the iPhone Connection screen reaches the server.
    @MainActor
    func testAStreamingQualityChosenOnConnectionCapsTheStreamOnIPhone() {
        proveStreamingQualityCapsTheStream(compact: true)
    }

    /// A streaming-quality cap chosen in the iPad Connection screen reaches the server.
    @MainActor
    func testAStreamingQualityChosenOnConnectionCapsTheStreamOnIPadOS() {
        proveStreamingQualityCapsTheStream(compact: false)
    }

    /// The Wi-Fi choice of the Connection screen's Streaming Quality section (spec §12.5), made
    /// with the real picker, caps the next song the app streams: the disposable server's own log
    /// records the stream of "Dulcet Health Probe" -- a 123 kbps FLAC, read back first -- as
    /// transcoded to 96 kbps. The log is read from the offset it had before the play, so a line
    /// an earlier run left behind cannot answer. A simulator's network is not metered, so the
    /// Wi-Fi choice is the one in force; the cellular choice is set to 128 kbps first, so a cap
    /// that came from the wrong row would show as 128 instead. Both are put back to Original at
    /// the end through the same picker.
    @MainActor
    private func proveStreamingQualityCapsTheStream(compact expectedCompact: Bool) {
        guard ProcessInfo.processInfo.environment["SIMULATOR_UDID"] != nil else {
            XCTFail("This proof requires a simulator; a physical device is not the destination it names")
            return
        }
        let expectedIdiom: UIUserInterfaceIdiom = expectedCompact ? .phone : .pad
        guard UIDevice.current.userInterfaceIdiom == expectedIdiom else {
            XCTFail("This proof names \(expectedCompact ? "iPhone" : "iPad") but runs on idiom \(UIDevice.current.userInterfaceIdiom.rawValue)")
            return
        }
        guard let configuration = livePlaybackConfiguration(),
              let logPath = runtimeValue(environment: "DULCET_UI_TEST_SERVER_LOG", argument: "-dulcet-ui-test-server-log") else {
            XCTFail("DULCET_UI_TEST_SERVER_LOG must name the disposable server's log")
            return
        }
        let track = "Dulcet Health Probe"
        let album = "Dulcet Conformance"
        guard let songID = serverSongID(track, album: album, configuration: configuration),
              let song = restCall("getSong", [URLQueryItem(name: "id", value: songID)], configuration: configuration)?[
                  "song"] as? [String: Any],
              let sourceKbps = song["bitRate"] as? Int else {
            XCTFail("The server must report \(track)'s bitrate")
            return
        }
        XCTAssertGreaterThan(sourceKbps, 96, "The control: the source must be above the cap, or no cap would be sent")
        guard let logBefore = serverLogSize(logPath) else {
            XCTFail("The disposable server's log must be readable from the test: \(logPath)")
            return
        }

        guard let app = launchConnected(serverURL: configuration.serverURL, configuration: configuration,
                                        compact: expectedCompact) else { return }
        // launchConnected leaves the app on Connection, where Sign Out confirmed the account.
        guard chooseStreamingQuality("128 kbps", row: "dulcet.streaming-quality.metered", in: app),
              chooseStreamingQuality("96 kbps", row: "dulcet.streaming-quality.unmetered", in: app) else { return }
        attachScreenshot(named: "streaming-quality-chosen", app: app)
        guard openLibraryAlbum(album, containing: track, in: app, compact: expectedCompact),
              playAlbumTrack(track, in: app),
              // The server's log, read below, names the track; the 2-second probe can be over
              // before the bar is read.
              openNowPlayingFromBar(in: app, expectingTitle: nil) else { return }

        // The capped play must start, not only reach the server: a first play that failed after
        // its stream request still leaves the transcode in the log. The probe lasts 2 seconds and
        // its album 5, and Now Playing counts whole seconds, so the start is either media time
        // moving or the queue moving past the probe -- with no failure and no skip notice seen at
        // any poll, since a track that cannot play is skipped with a notice (section 12.12). Now
        // Playing's progress is a slider, or a progress bar where the stream cannot seek.
        let sliderProgress = app.sliders["Now Playing"].firstMatch
        let barProgress = app.progressIndicators["Now Playing"].firstMatch
        let title = app.staticTexts.matching(identifier: "dulcet.now-playing.title").firstMatch
        let failure = app.descendants(matching: .any).matching(identifier: "dulcet.now-playing.failure").firstMatch
        let skipNotice = app.staticTexts.matching(NSPredicate(format: "label CONTAINS %@", "Skipped.")).firstMatch
        var started: String?
        var failed: String?
        var lastValue = "none"
        let playDeadline = Date().addingTimeInterval(20)
        repeat {
            if failure.exists { failed = "failure shown: \(failure.label)"; break }
            if skipNotice.exists { failed = "skip notice: \(skipNotice.label)"; break }
            // One snapshot each, whose failure is thrown rather than recorded: the progress view
            // can be redrawn between an `exists` check and a `value` read. That failed this proof
            // with "Failed to get matching snapshot" (CI run 37142156762, iPhone).
            let progress = (try? sliderProgress.snapshot()) ?? (try? barProgress.snapshot())
            if let value = progress?.value as? String {
                lastValue = value
                if let sample = playbackProgressSample(from: value), sample.elapsed > 0 {
                    started = "media-time \(value)"
                    break
                }
            }
            if title.exists, !title.label.isEmpty, title.label != track {
                started = "moved-past-probe to \(title.label)"
            }
            RunLoop.current.run(until: Date().addingTimeInterval(0.25))
        } while Date() < playDeadline && started == nil
        XCTAssertNil(failed, "The capped play must not fail: \(failed ?? "")")
        XCTAssertNotNil(started, "The capped play must start: Now Playing's media time must move, or the queue"
            + " move past the probe; last progress \(lastValue), title \(title.exists ? title.label : "none")")

        var lines: [[String: String]] = []
        let deadline = Date().addingTimeInterval(30)
        repeat {
            lines = serverStreamLines(logPath, from: logBefore).filter { $0["title"] == track }
            if !lines.isEmpty { break }
            RunLoop.current.run(until: Date().addingTimeInterval(0.5))
        } while Date() < deadline
        XCTAssertFalse(lines.isEmpty, "The server must log a stream of \(track) after the play")
        for line in lines {
            XCTAssertEqual(line["transcoding"], "true", "The server must transcode the capped stream; line=\(line)")
            XCTAssertEqual(line["bitRate"], "96", "The stream must carry the Wi-Fi choice, 96 kbps; line=\(line)")
            XCTAssertEqual(line["originalBitRate"], String(sourceKbps), "The line must be this source's; line=\(line)")
        }

        // Put both choices back, through the same screen, so later phases stream originals.
        let close = app.buttons["dulcet.now-playing.close"].firstMatch
        if close.waitForExistence(timeout: 5) { close.tap() } else { app.swipeDown() }
        guard openDestination("Connection", sidebarIdentifier: "dulcet.sidebar.settings", in: app, compact: expectedCompact),
              chooseStreamingQuality("Original", row: "dulcet.streaming-quality.unmetered", in: app),
              chooseStreamingQuality("Original", row: "dulcet.streaming-quality.metered", in: app) else { return }
        let summary = lines.map { "\($0["format"] ?? "?")@\($0["bitRate"] ?? "?")" }.joined(separator: ",")
        print("DULCET STREAMING QUALITY PROOF PASS destination=\(expectedCompact ? "compact" : "regular")"
            + " track=\(track.debugDescription) source-kbps=\(sourceKbps) server-streams=\(summary)"
            + " started=\(started ?? "nil")")
    }

    /// Launches the app with the account injected for `serverURL` and waits for its live
    /// connection, on the device class the caller names.
    @MainActor
    private func launchConnected(
        serverURL: String,
        configuration: LivePlaybackConfiguration,
        compact expectedCompact: Bool
    ) -> XCUIApplication? {
        let app = XCUIApplication()
        app.launchArguments += [
            "-dulcet-debug-connect-account",
            "-dulcet-debug-account-server-url", serverURL,
            "-dulcet-debug-account-username", configuration.username,
            "-dulcet-debug-account-password", configuration.password,
        ]
        app.launch()
        let window = app.windows.firstMatch
        guard window.waitForExistence(timeout: 10) else {
            XCTFail("The app window must exist")
            return nil
        }
        let compact = window.frame.width < 700
        guard compact == expectedCompact else {
            XCTFail("This proof needs a \(expectedCompact ? "compact" : "regular") window; observed \(window.frame)")
            return nil
        }
        guard awaitLiveAccountConnection(in: app, compact: compact) else {
            XCTFail("The live account connection must succeed first")
            return nil
        }
        return app
    }

    /// Starts one track of the open album from its row.
    /// Searches for `query` and activates the one track result titled by it. A search queue holds
    /// the playable track results (`activateSearchResult`), so a query matching one track queues
    /// exactly that track; the result count is asserted so a corpus change cannot widen it.
    @MainActor
    private func playFromSearch(_ query: String, in app: XCUIApplication, compact: Bool) -> Bool {
        guard openDestination("Search", sidebarIdentifier: "dulcet.sidebar.search", in: app, compact: compact) else {
            return false
        }
        let field = app.textFields["dulcet.search.field"].firstMatch
        guard field.waitForExistence(timeout: 10) else {
            XCTFail("The search field must exist on the Search destination: " + app.debugDescription)
            return false
        }
        // A tap under load has been seen not to give the field focus (OBSERVED locally: "Neither
        // element nor any descendant has keyboard focus"), so focus is confirmed before typing.
        let focused = NSPredicate(format: "hasKeyboardFocus == true")
        var taps = 0
        repeat {
            taps += 1
            field.tap()
        } while XCTWaiter.wait(for: [XCTNSPredicateExpectation(predicate: focused, object: field)], timeout: 3) != .completed && taps < 3
        guard (field.value(forKey: "hasKeyboardFocus") as? Bool) == true else {
            XCTFail("The search field must take focus; \(taps) taps: " + app.debugDescription)
            return false
        }
        field.typeText(query)
        let tracks = app.buttons.matching(NSPredicate(
            format: "identifier BEGINSWITH %@ AND label ENDSWITH %@", "dulcet.search.result.", ", Track"))
        let result = tracks.matching(NSPredicate(format: "label BEGINSWITH %@", query + ", ")).firstMatch
        guard result.waitForExistence(timeout: 30) else {
            XCTFail("Search must find \(query): " + app.debugDescription)
            return false
        }
        XCTAssertEqual(tracks.count, 1, "The search must match \(query) alone, so the queue holds one track")
        guard dismissKeyboardBeforeActivation(in: app), scrollIntoView(result, in: app) else { return false }
        result.tap()
        return true
    }

    /// Opens the album of that name that lists `track`. The corpus holds two albums named "Dulcet
    /// Conformance" by the same artist, so a tile's label cannot tell them apart; each is opened
    /// in turn until one lists the track.
    @MainActor
    private func openLibraryAlbum(_ album: String, containing track: String, in app: XCUIApplication, compact: Bool) -> Bool {
        guard openLibraryAlbum(album, in: app, compact: compact) else { return false }
        let row = app.buttons.matching(identifier: "dulcet.reader.track")
            .matching(NSPredicate(format: "label BEGINSWITH %@", track + ", ")).firstMatch
        let tiles = app.buttons.matching(identifier: "dulcet.library.album")
            .matching(NSPredicate(format: "label BEGINSWITH %@", album))
        var opened = 1
        while !row.waitForExistence(timeout: 5) {
            let back = app.navigationBars.buttons["BackButton"].firstMatch
            guard opened < 4, back.exists else {
                XCTFail("No \(album) album among the \(opened) opened lists \(track): " + app.debugDescription)
                return false
            }
            back.tap()
            let tile = tiles.element(boundBy: opened)
            guard tile.waitForExistence(timeout: 10), scrollIntoView(tile, in: app) else {
                XCTFail("Only \(opened) \(album) tile(s), and none lists \(track): " + app.debugDescription)
                return false
            }
            tile.tap()
            opened += 1
            guard app.staticTexts["dulcet.album.title"].firstMatch.waitForExistence(timeout: 10) else {
                XCTFail("The \(album) tile must open its album: " + app.debugDescription)
                return false
            }
        }
        return true
    }

    @MainActor
    private func playAlbumTrack(_ track: String, in app: XCUIApplication) -> Bool {
        // The now-playing bar names a track too; its own identifier keeps it out of the match.
        let row = app.buttons.matching(
            NSPredicate(format: "label CONTAINS %@ AND identifier != %@", track, "dulcet.mini-player.open")
        ).firstMatch
        guard row.waitForExistence(timeout: 15), scrollIntoView(row, in: app) else {
            XCTFail("The album must list \(track): " + app.debugDescription)
            return false
        }
        row.tap()
        return true
    }

    /// Shows lyrics from Now Playing's toggle and returns the panel.
    @MainActor
    private func openLyricsPanel(in app: XCUIApplication) -> XCUIElement? {
        let toggle = app.buttons["dulcet.now-playing.lyrics"].firstMatch
        guard toggle.waitForExistence(timeout: 10) else {
            XCTFail("Now Playing must offer lyrics: " + app.debugDescription)
            return nil
        }
        toggle.tap()
        let panel = app.descendants(matching: .any)["dulcet.lyrics.panel"].firstMatch
        guard panel.waitForExistence(timeout: 10) else {
            XCTFail("The lyrics panel must open: " + app.debugDescription)
            return nil
        }
        return panel
    }

    /// Chooses `option` in one of the Streaming Quality pickers on the Connection screen, then
    /// reads the picker back.
    @MainActor
    private func chooseStreamingQuality(_ option: String, row identifier: String, in app: XCUIApplication) -> Bool {
        let picker = app.buttons[identifier].firstMatch
        guard picker.waitForExistence(timeout: 10), scrollIntoView(picker, in: app) else {
            XCTFail("Connection must offer the \(identifier) picker: " + app.debugDescription)
            return false
        }
        picker.tap()
        let item = app.buttons.matching(NSPredicate(format: "label == %@ AND identifier != %@", option, identifier)).firstMatch
        guard item.waitForExistence(timeout: 5) else {
            XCTFail("The picker must offer \(option): " + app.debugDescription)
            return false
        }
        item.tap()
        let deadline = Date().addingTimeInterval(5)
        while !pickerShows(option, picker), Date() < deadline {
            RunLoop.current.run(until: Date().addingTimeInterval(0.2))
        }
        guard pickerShows(option, picker) else {
            XCTFail("The \(identifier) picker must show \(option) once chosen; label=\(picker.label) value=\(String(describing: picker.value))")
            return false
        }
        return true
    }

    private func pickerShows(_ option: String, _ picker: XCUIElement) -> Bool {
        picker.label.contains(option) || (picker.value as? String)?.contains(option) == true
    }

    /// The id of the one song of `album` named `title`, read from the disposable server.
    @MainActor
    private func serverSongID(_ title: String, album: String, configuration: LivePlaybackConfiguration) -> String? {
        let songs = (restCall("search3", [
            URLQueryItem(name: "query", value: title), URLQueryItem(name: "songCount", value: "20"),
            URLQueryItem(name: "albumCount", value: "0"), URLQueryItem(name: "artistCount", value: "0"),
        ], configuration: configuration)?["searchResult3"] as? [String: Any])?["song"] as? [[String: Any]] ?? []
        let matches = songs.filter { $0["title"] as? String == title && $0["album"] as? String == album }
        guard matches.count == 1, let id = matches[0]["id"] as? String else {
            XCTFail("\(matches.count) songs named \(title) on \(album); exactly one is required")
            return nil
        }
        return id
    }

    private struct ServerLyricsLayer {
        let synced: Bool
        let lines: [String]
    }

    /// The server's own lyrics layers for a song, from `getLyricsBySongId`.
    private func serverLyricsLayers(songID: String, configuration: LivePlaybackConfiguration) -> [ServerLyricsLayer]? {
        guard let list = restCall("getLyricsBySongId", [URLQueryItem(name: "id", value: songID)],
                                  configuration: configuration)?["lyricsList"] as? [String: Any] else { return nil }
        let structured = list["structuredLyrics"] as? [[String: Any]] ?? []
        return structured.map { layer in
            ServerLyricsLayer(
                synced: layer["synced"] as? Bool ?? false,
                lines: (layer["line"] as? [[String: Any]] ?? []).compactMap { $0["value"] as? String }
            )
        }
    }

    private struct LyricsFaultProxy {
        let url: String
    }

    private struct LyricsFaultObservations {
        let failed: Int
        let forwardedAfterDisarm: Int
    }

    /// The lyrics fault proxy in front of the disposable server, after checking that it is a
    /// loopback proxy, that it answers, and that it serves the same library as the server.
    @MainActor
    private func lyricsFaultProxyConfiguration(server: LivePlaybackConfiguration) -> LyricsFaultProxy? {
        guard let url = runtimeValue(environment: "DULCET_UI_TEST_LYRICS_FAULT_PROXY_URL",
                                     argument: "-dulcet-ui-test-lyrics-fault-proxy-url") else {
            XCTFail("DULCET_UI_TEST_LYRICS_FAULT_PROXY_URL must name the lyrics fault proxy")
            return nil
        }
        guard Self.isLoopbackHost(URLComponents(string: url)?.host?.lowercased() ?? "") else {
            XCTFail("The lyrics fault proxy must be a loopback host")
            return nil
        }
        let proxy = LyricsFaultProxy(url: url)
        // Up to three bounded attempts: the first request a freshly launched runner sends has
        // been seen to stall past its 10 s bound (OBSERVED once locally, under host load).
        var attempts = 0
        var healthy = false
        while !healthy, attempts < 3 {
            attempts += 1
            healthy = proxyRequest("GET", "/__dulcet/health", proxy: proxy)?["ok"] as? Bool == true
        }
        print("DULCET LYRICS FAULT PROXY health attempts=\(attempts) healthy=\(healthy)")
        guard healthy else {
            XCTFail("The lyrics fault proxy must answer its health check; \(attempts) attempts")
            return nil
        }
        // Server identity: the proxy's library is the server's, by a song id read through both.
        let through = LivePlaybackConfiguration(serverURL: url, username: server.username, password: server.password)
        let direct = serverSongID("Thirty One Seconds", album: "Threshold Boundary", configuration: server)
        let proxied = serverSongID("Thirty One Seconds", album: "Threshold Boundary", configuration: through)
        guard direct != nil, direct == proxied else {
            XCTFail("The lyrics fault proxy must front the disposable server; direct=\(String(describing: direct)) proxied=\(String(describing: proxied))")
            return nil
        }
        return proxy
    }

    @discardableResult
    private func proxyLyricsFailure(song: String, on: Bool, proxy: LyricsFaultProxy) -> LyricsFaultObservations? {
        observations(proxyRequest("POST", "/__dulcet/lyrics-failure?song=\(song)&state=\(on ? "on" : "off")", proxy: proxy))
    }

    private func proxyObservations(song: String, proxy: LyricsFaultProxy) -> LyricsFaultObservations? {
        observations(proxyRequest("GET", "/__dulcet/observations?song=\(song)", proxy: proxy))
    }

    private func observations(_ body: [String: Any]?) -> LyricsFaultObservations? {
        guard let body, let failed = body["failed"] as? Int, let forwarded = body["forwardedAfterDisarm"] as? Int else {
            XCTFail("The lyrics fault proxy must report its observations")
            return nil
        }
        return LyricsFaultObservations(failed: failed, forwardedAfterDisarm: forwarded)
    }

    private struct PlaylistHoldObservations {
        let holding: Bool
        let held: Int
        let released: Int
        let timedOut: Int
    }

    /// Holds, or releases, every read of `playlist` at the proxy.
    @discardableResult
    private func proxyPlaylistHold(_ playlist: String, on: Bool, proxy: LyricsFaultProxy) -> PlaylistHoldObservations? {
        playlistHoldObservations(proxyRequest("POST", "/__dulcet/playlist-hold?playlist=\(playlist)&state=\(on ? "on" : "off")", proxy: proxy))
    }

    /// Polls the proxy's counts for `playlist` until `condition` holds; nil if it never does.
    private func awaitPlaylistObservations(
        _ playlist: String,
        proxy: LyricsFaultProxy,
        timeout: TimeInterval,
        until condition: (PlaylistHoldObservations) -> Bool
    ) -> PlaylistHoldObservations? {
        let deadline = Date().addingTimeInterval(timeout)
        repeat {
            if let seen = playlistHoldObservations(proxyRequest("GET", "/__dulcet/playlist-observations?playlist=\(playlist)", proxy: proxy)),
               condition(seen) {
                return seen
            }
            RunLoop.current.run(until: Date().addingTimeInterval(0.5))
        } while Date() < deadline
        return nil
    }

    private func playlistHoldObservations(_ body: [String: Any]?) -> PlaylistHoldObservations? {
        guard let body, let holding = body["holding"] as? Bool, let held = body["held"] as? Int,
              let released = body["released"] as? Int, let timedOut = body["timedOut"] as? Int else { return nil }
        return PlaylistHoldObservations(holding: holding, held: held, released: released, timedOut: timedOut)
    }

    /// One control request to the proxy: credential-free, so it may be named in a failure.
    private func proxyRequest(_ method: String, _ path: String, proxy: LyricsFaultProxy) -> [String: Any]? {
        guard let url = URL(string: proxy.url + path) else { return nil }
        var request = URLRequest(url: url)
        request.httpMethod = method
        final class Outcome: @unchecked Sendable { var data: Data? }
        let outcome = Outcome()
        let done = DispatchSemaphore(value: 0)
        let task = URLSession.shared.dataTask(with: request) { data, response, error in
            if error == nil, (response as? HTTPURLResponse)?.statusCode == 200 { outcome.data = data }
            done.signal()
        }
        task.resume()
        guard done.wait(timeout: .now() + 10) == .success else {
            task.cancel()
            print("DULCET LYRICS FAULT PROXY \(method) \(path) timed out")
            return nil
        }
        return outcome.data.flatMap { try? JSONSerialization.jsonObject(with: $0) as? [String: Any] }
    }

    /// The size of the server's log, or nil when it cannot be read.
    private func serverLogSize(_ path: String) -> UInt64? {
        guard let handle = FileHandle(forReadingAtPath: path) else { return nil }
        defer { try? handle.close() }
        return try? handle.seekToEnd()
    }

    /// The server's "Streaming file" lines written after `offset`, as their key=value fields.
    private func serverStreamLines(_ path: String, from offset: UInt64) -> [[String: String]] {
        guard let handle = FileHandle(forReadingAtPath: path) else { return [] }
        defer { try? handle.close() }
        guard (try? handle.seek(toOffset: offset)) != nil, let data = try? handle.readToEnd(),
              let text = String(data: data, encoding: .utf8) else { return [] }
        return text.split(separator: "\n").filter { $0.contains("msg=\"Streaming file\"") }.map { line in
            var fields: [String: String] = [:]
            var rest = Substring(line)
            while let equals = rest.firstIndex(of: "=") {
                let key = rest[..<equals].split(separator: " ").last.map(String.init) ?? ""
                rest = rest[rest.index(after: equals)...]
                let value: Substring
                if rest.first == "\"" {
                    let body = rest.dropFirst()
                    let end = body.firstIndex(of: "\"") ?? body.endIndex
                    value = body[..<end]
                    rest = end < body.endIndex ? body[body.index(after: end)...] : ""
                } else {
                    let end = rest.firstIndex(of: " ") ?? rest.endIndex
                    value = rest[..<end]
                    rest = rest[end...]
                }
                if !key.isEmpty { fields[key] = String(value) }
            }
            return fields
        }
    }

    /// Now Playing's heart for the playing track (spec §16.20) on iPhone, in the player sheet.
    @MainActor
    func testTheNowPlayingHeartFillsAtOnceAndReachesTheServerOnIPhone() {
        proveNowPlayingHeartFillsAtOnceAndReachesTheServer(compact: true)
    }

    /// Now Playing's heart for the playing track (spec §16.20) on iPad, in the full-screen player.
    @MainActor
    func testTheNowPlayingHeartFillsAtOnceAndReachesTheServerOnIPadOS() {
        proveNowPlayingHeartFillsAtOnceAndReachesTheServer(compact: false)
    }

    /// The heart fills with the tap, before the server answers, and the star reaches the server
    /// for the playing track. Taking it off again empties it at once and unstars the track, so the
    /// proof leaves the server as it found it. Each destination has its own test, and each fails
    /// on the other's window class, so an iPhone run cannot stand as iPad evidence.
    @MainActor
    private func proveNowPlayingHeartFillsAtOnceAndReachesTheServer(compact expectedCompact: Bool) {
        guard ProcessInfo.processInfo.environment["SIMULATOR_UDID"] != nil else {
            XCTFail("This proof requires a simulator; a physical device is not the destination it names")
            return
        }
        guard let configuration = livePlaybackConfiguration() else { return }
        let album = "Threshold Boundary"
        let track = "Twenty Nine Seconds"
        let app = XCUIApplication()
        app.launchArguments += [
            "-dulcet-debug-connect-account",
            "-dulcet-debug-account-server-url", configuration.serverURL,
            "-dulcet-debug-account-username", configuration.username,
            "-dulcet-debug-account-password", configuration.password,
        ]
        app.launch()
        let window = app.windows.firstMatch
        XCTAssertTrue(window.waitForExistence(timeout: 10), "The app window must exist")
        let compact = window.frame.width < 700
        guard compact == expectedCompact else {
            XCTFail(expectedCompact
                ? "This proof requires a compact-width iPhone window; an iPad is invalid evidence"
                : "This proof requires a regular-width iPad window; an iPhone is invalid evidence")
            return
        }
        guard awaitLiveAccountConnection(in: app, compact: compact) else {
            XCTFail("The live account connection must succeed first")
            return
        }
        guard openLibraryAlbum(album, in: app, compact: compact) else { return }
        guard tapAlbumPlay(in: app) else { return }
        guard openNowPlayingFromBar(in: app, expectingTitle: track) else { return }
        let title = app.staticTexts["dulcet.now-playing.title"].firstMatch
        XCTAssertTrue(title.waitForExistence(timeout: 10) && title.label == track,
            "Now Playing must show the track this proof stars: " + app.debugDescription)
        let heart = app.buttons["dulcet.now-playing.favorite"].firstMatch
        guard heart.waitForExistence(timeout: 10) else {
            XCTFail("Now Playing must offer the playing track's heart: " + app.debugDescription)
            return
        }
        // Paused, and on the track, before any tap: the fixture tracks are about thirty seconds
        // long, and a held tap can land after the queue moved on (see the stars proof).
        guard bringPausedNowPlaying(to: track, in: app) else { return }
        // From a known starting point, whatever an earlier run left.
        if heart.label == "Remove Favorite" {
            heart.tap()
            guard waitForLabel("Favorite", of: heart, timeout: 5),
                  awaitServerSongStarred(track, album: album, configuration: configuration, expected: false, timeout: 30) == false else {
                XCTFail("An earlier run's favourite could not be cleared first")
                return
            }
        }
        XCTAssertEqual(readServerSongStarred(track, album: album, configuration: configuration), false,
            "The control: the server must not already hold the favourite this proof makes")
        heart.tap()
        XCTAssertTrue(waitForLabel("Remove Favorite", of: heart, timeout: 3),
            "The heart must fill at once, before the server answers; label=\(heart.label)")
        XCTAssertEqual(awaitServerSongStarred(track, album: album, configuration: configuration, expected: true, timeout: 30), true,
            "The star must reach the server for the playing track")
        XCTAssertEqual(title.label, track, "Starring must not change what is playing")
        heart.tap()
        XCTAssertTrue(waitForLabel("Favorite", of: heart, timeout: 3), "The heart must empty at once")
        XCTAssertEqual(awaitServerSongStarred(track, album: album, configuration: configuration, expected: false, timeout: 30), false,
            "Removing the favourite must reach the server")
        print("DULCET NOW PLAYING HEART PROOF PASS destination=\(compact ? "compact" : "regular")"
            + " window-width=\(Int(window.frame.width)) track=\(track.debugDescription) starred=true->false")
    }

    /// Whether the server holds the named track of `album` as a favourite, read over
    /// `/rest/search3`. Nil, with the reason printed, unless exactly one song matches.
    @MainActor
    private func readServerSongStarred(_ track: String, album: String, configuration: LivePlaybackConfiguration) -> Bool? {
        guard let envelope = restCall("search3", [
            URLQueryItem(name: "query", value: track),
            URLQueryItem(name: "songCount", value: "20"),
            URLQueryItem(name: "albumCount", value: "0"),
            URLQueryItem(name: "artistCount", value: "0"),
        ], configuration: configuration) else { return nil }
        let songs = (envelope["searchResult3"] as? [String: Any])?["song"] as? [[String: Any]] ?? []
        let matches = songs.filter { $0["title"] as? String == track && $0["album"] as? String == album }
        guard matches.count == 1, let match = matches.first else {
            print("DULCET REST search3 matched \(matches.count) songs named \(track) on \(album); exactly one is required")
            return nil
        }
        // Subsonic carries `starred` only on a favourite.
        return match["starred"] != nil
    }

    /// Polls until the server's favourite state for the track is `expected` or the timeout passes.
    @MainActor
    private func awaitServerSongStarred(
        _ track: String,
        album: String,
        configuration: LivePlaybackConfiguration,
        expected: Bool,
        timeout: TimeInterval
    ) -> Bool? {
        let deadline = Date().addingTimeInterval(timeout)
        var observed = readServerSongStarred(track, album: album, configuration: configuration)
        while observed != nil, observed != expected, Date() < deadline {
            RunLoop.current.run(until: Date().addingTimeInterval(1))
            observed = readServerSongStarred(track, album: album, configuration: configuration)
        }
        return observed
    }

    /// A track row's heart and an artist page's heart (spec §16.20) on iPhone.
    @MainActor
    func testATrackRowHeartAndAnArtistHeartReachTheServerOnIPhone() {
        proveTrackRowAndArtistHeartsReachTheServer(compact: true)
    }

    /// The same hearts on iPad, through the sidebar.
    @MainActor
    func testATrackRowHeartAndAnArtistHeartReachTheServerOnIPadOS() {
        proveTrackRowAndArtistHeartsReachTheServer(compact: false)
    }

    /// The heart beside a track on an album page, then the heart on that album's artist page: each
    /// fills with the tap, before the server answers, and the star reaches the server for that
    /// track or artist; taking it off empties it at once and unstars it, so the server ends as it
    /// started. The artist page is reached through the album page's artist link, the way a person
    /// reaches it. Each destination has its own test, and each fails on the other's window class.
    @MainActor
    private func proveTrackRowAndArtistHeartsReachTheServer(compact expectedCompact: Bool) {
        guard ProcessInfo.processInfo.environment["SIMULATOR_UDID"] != nil else {
            XCTFail("This proof requires a simulator; a physical device is not the destination it names")
            return
        }
        guard let configuration = livePlaybackConfiguration() else { return }
        let album = "Threshold Boundary"
        let track = "Twenty Nine Seconds"
        let artist = "Dulcet Fixtures"
        guard let app = launchConnected(
            serverURL: configuration.serverURL, configuration: configuration, compact: expectedCompact
        ) else { return }
        guard openLibraryAlbum(album, in: app, compact: expectedCompact) else { return }

        // 1. The track row's heart: the one beside the row, by the row's own frame.
        let row = app.buttons.matching(identifier: "dulcet.reader.track")
            .matching(NSPredicate(format: "label BEGINSWITH %@", track + ", ")).firstMatch
        guard row.waitForExistence(timeout: 15), scrollIntoView(row, in: app) else {
            XCTFail("\(album) must list \(track): " + app.debugDescription)
            return
        }
        let rowHearts = app.buttons.matching(identifier: "dulcet.reader.favorite").allElementsBoundByIndex
            .filter { $0.frame.midY > row.frame.minY && $0.frame.midY < row.frame.maxY }
        guard rowHearts.count == 1, let rowHeart = rowHearts.first else {
            XCTFail("Exactly one heart must sit beside \(track)'s row; found \(rowHearts.count): " + app.debugDescription)
            return
        }
        let songStarred = { self.readServerSongStarred(track, album: album, configuration: configuration) }
        let awaitSong = { (expected: Bool) in
            self.awaitServerSongStarred(track, album: album, configuration: configuration, expected: expected, timeout: 30)
        }
        guard toggleHeartAndProve(rowHeart, what: "\(track)'s row", read: songStarred, awaitServer: awaitSong) else { return }

        // 2. The artist page's heart, reached through the album's artist link.
        let link = app.buttons.matching(NSPredicate(format: "label == %@", artist)).firstMatch
        guard link.waitForExistence(timeout: 10), scrollIntoView(link, in: app) else {
            XCTFail("The album page must link its artist \(artist): " + app.debugDescription)
            return
        }
        link.tap()
        let artistTitle = app.staticTexts["dulcet.artist.title"].firstMatch
        guard artistTitle.waitForExistence(timeout: 15), artistTitle.label == artist else {
            XCTFail("The artist link must open \(artist)'s page: " + app.debugDescription)
            return
        }
        let artistHeart = app.buttons["dulcet.artist.favorite"].firstMatch
        guard artistHeart.waitForExistence(timeout: 10) else {
            XCTFail("The artist page must offer its heart: " + app.debugDescription)
            return
        }
        let artistStarred = { self.readServerArtistStarred(artist, configuration: configuration) }
        let awaitArtist = { (expected: Bool) -> Bool? in
            let deadline = Date().addingTimeInterval(30)
            var observed = artistStarred()
            while observed != nil, observed != expected, Date() < deadline {
                RunLoop.current.run(until: Date().addingTimeInterval(1))
                observed = artistStarred()
            }
            return observed
        }
        guard toggleHeartAndProve(artistHeart, what: "\(artist)'s page", read: artistStarred, awaitServer: awaitArtist) else { return }
        print("DULCET ROW HEARTS PROOF PASS destination=\(expectedCompact ? "compact" : "regular")"
            + " track=\(track.debugDescription) artist=\(artist.debugDescription) starred=true->false")
    }

    /// From a known starting point whatever an earlier run left: the heart fills with the tap, the
    /// star reaches the server, and a second tap empties it at once and unstars it there.
    @MainActor
    private func toggleHeartAndProve(
        _ heart: XCUIElement,
        what: String,
        read: () -> Bool?,
        awaitServer: (Bool) -> Bool?
    ) -> Bool {
        if heart.label == "Remove Favorite" {
            heart.tap()
            guard waitForLabel("Favorite", of: heart, timeout: 5), awaitServer(false) == false else {
                XCTFail("An earlier run's favourite on \(what) could not be cleared first")
                return false
            }
        }
        guard read() == false else {
            XCTFail("The control: the server must not already hold the favourite on \(what) this proof makes")
            return false
        }
        heart.tap()
        XCTAssertTrue(waitForLabel("Remove Favorite", of: heart, timeout: 3),
            "\(what): the heart must fill at once, before the server answers; label=\(heart.label)")
        XCTAssertEqual(awaitServer(true), true, "\(what): the star must reach the server")
        heart.tap()
        XCTAssertTrue(waitForLabel("Favorite", of: heart, timeout: 3), "\(what): the heart must empty at once")
        XCTAssertEqual(awaitServer(false), false, "\(what): removing the favourite must reach the server")
        return true
    }

    /// Whether the server holds the named artist as a favourite, read over `/rest/search3`. Nil,
    /// with the reason printed, unless exactly one artist matches.
    @MainActor
    private func readServerArtistStarred(_ artist: String, configuration: LivePlaybackConfiguration) -> Bool? {
        guard let envelope = restCall("search3", [
            URLQueryItem(name: "query", value: artist),
            URLQueryItem(name: "songCount", value: "0"),
            URLQueryItem(name: "albumCount", value: "0"),
            URLQueryItem(name: "artistCount", value: "20"),
        ], configuration: configuration) else { return nil }
        let artists = (envelope["searchResult3"] as? [String: Any])?["artist"] as? [[String: Any]] ?? []
        let matches = artists.filter { $0["name"] as? String == artist }
        guard matches.count == 1, let match = matches.first else {
            print("DULCET REST search3 matched \(matches.count) artists named \(artist); exactly one is required")
            return nil
        }
        // Subsonic carries `starred` only on a favourite.
        return match["starred"] != nil
    }

    /// Now Playing's stars for the playing track (spec §16.20, CONF-84) on iPhone, in the sheet.
    @MainActor
    func testNowPlayingStarsRateTheTrackOnTheServerOnIPhone() {
        proveNowPlayingStarsRateTheTrackOnTheServer(compact: true)
    }

    /// Now Playing's stars for the playing track (spec §16.20, CONF-84) on iPad, in the
    /// full-screen player.
    @MainActor
    func testNowPlayingStarsRateTheTrackOnTheServerOnIPadOS() {
        proveNowPlayingStarsRateTheTrackOnTheServer(compact: false)
    }

    /// A tap on a star rates the playing track that many stars: the stars show it at once, before
    /// the server answers, and the server's `userRating` reads it back. Then another client's
    /// rating is written to the server directly, the app is relaunched, and Now Playing shows the
    /// server's value -- so what the stars show is read from the server, not remembered from the
    /// tap. A tap on the star already shown removes the rating, and the server reads back 0.
    ///
    /// The stars are one adjustable accessibility element, so a star is tapped where it is drawn:
    /// five equal hit areas side by side, star N at the centre of the N-th fifth. Each value is
    /// chosen against what the server holds first, so no earlier run's rating can make a write
    /// unobservable, and the server's rating is put back afterwards. Each destination has its own
    /// test, and each fails on the other's device class, so an iPhone run cannot stand as iPad
    /// evidence.
    @MainActor
    private func proveNowPlayingStarsRateTheTrackOnTheServer(compact expectedCompact: Bool) {
        guard requireSimulator(expectedCompact ? .phone : .pad,
                               "The Now Playing stars proof on \(expectedCompact ? "iPhone" : "iPad")"),
              let configuration = livePlaybackConfiguration() else { return }
        let album = "Threshold Boundary"
        let track = "Twenty Nine Seconds"
        guard let songID = serverSongID(track, album: album, configuration: configuration),
              let before = readServerSongRating(songID, configuration: configuration) else {
            XCTFail("The control: the server's rating of \(track) must be readable before any tap")
            return
        }
        afterTest.append { [configuration] in
            _ = self.restCall("setRating", [URLQueryItem(name: "id", value: songID), URLQueryItem(name: "rating", value: String(before))],
                              configuration: configuration)
        }
        let rating = before == 3 ? 2 : 3
        let elsewhere = rating == 4 ? 5 : 4

        // 1. A tap rates the track: shown at once, then saved on the server.
        guard let app = launchConnected(serverURL: configuration.serverURL, configuration: configuration, compact: expectedCompact),
              let stars = openNowPlayingStars(album: album, track: track, in: app, compact: expectedCompact) else { return }
        XCTAssertTrue(waitForValue(ratingValue(before), of: stars, timeout: 15),
            "Before the tap the stars must show the server's rating, \(before); value=\(String(describing: stars.value))")
        // Paused and on the track first, as in step 3. OBSERVED on iPad in main's run 37188677065:
        // the stars showed the tap's 3 at once, but the server's rating of this track stayed 0, and
        // Now Playing read "UI Playback Canary" -- the tap rated the track the queue had reached.
        guard bringPausedNowPlaying(to: track, in: app) else { return }
        tapStar(rating, of: stars)
        XCTAssertTrue(waitForValuePrefix(ratingValue(rating), of: stars, timeout: 3),
            "The stars must show \(rating) at once, before the server answers; value=\(String(describing: stars.value))")
        XCTAssertEqual(awaitServerSongRating(songID, configuration: configuration, expected: rating, timeout: 30), rating,
            "The tap on star \(rating) must rate \(track) \(rating) on the server (was \(before))")
        XCTAssertTrue(waitForValue(ratingValue(rating), of: stars, timeout: 15),
            "Once saved the stars must read \(rating) with nothing pending; value=\(String(describing: stars.value))")
        XCTAssertEqual(app.staticTexts["dulcet.now-playing.title"].firstMatch.label, track, "Rating must not change what is playing")

        // 2. Re-read: another client's rating, written to the server, is what a relaunch shows.
        guard restCall("setRating", [URLQueryItem(name: "id", value: songID), URLQueryItem(name: "rating", value: String(elsewhere))],
                       configuration: configuration) != nil,
              readServerSongRating(songID, configuration: configuration) == elsewhere else {
            XCTFail("The other client's rating, \(elsewhere), must be on the server before the relaunch")
            return
        }
        app.terminate()
        guard let relaunched = launchConnected(serverURL: configuration.serverURL, configuration: configuration, compact: expectedCompact),
              let reread = openNowPlayingStars(album: album, track: track, in: relaunched, compact: expectedCompact) else { return }
        XCTAssertTrue(waitForValue(ratingValue(elsewhere), of: reread, timeout: 30),
            "After a relaunch the stars must show the server's rating, \(elsewhere), not the tap's \(rating); value=\(String(describing: reread.value))")

        // 3. A tap on the star shown removes the rating -- on this track. Paused first, and brought
        // back to the track if the queue moved on: the fixture tracks are about thirty seconds
        // long. OBSERVED on iPad in CI run 37133427531: XCUITest held this tap 60 s waiting for
        // the app to idle. The track ended, the queue reached "UI Playback Canary", and the tap
        // landed on that track's stars.
        guard bringPausedNowPlaying(to: track, in: relaunched) else { return }
        tapStar(elsewhere, of: reread)
        XCTAssertTrue(waitForValuePrefix(ratingValue(0), of: reread, timeout: 3),
            "A tap on the star shown must clear the stars at once; value=\(String(describing: reread.value))")
        XCTAssertEqual(awaitServerSongRating(songID, configuration: configuration, expected: 0, timeout: 30), 0,
            "The tap on the star shown must remove the rating on the server")
        print("DULCET NOW PLAYING STARS PROOF PASS destination=\(expectedCompact ? "compact" : "regular")"
            + " window-width=\(Int(relaunched.windows.firstMatch.frame.width)) track=\(track.debugDescription)"
            + " server-before=\(before) tapped=\(rating) other-client=\(elsewhere) shown-after-relaunch=\(elsewhere) removed=0")
    }

    /// Opens `album`, plays it from its first track, opens Now Playing from the bar, and returns
    /// the playing track's stars.
    @MainActor
    private func openNowPlayingStars(album: String, track: String, in app: XCUIApplication, compact: Bool) -> XCUIElement? {
        guard openLibraryAlbum(album, in: app, compact: compact) else { return nil }
        guard tapAlbumPlay(in: app) else { return nil }
        guard openNowPlayingFromBar(in: app, expectingTitle: track) else { return nil }
        let title = app.staticTexts["dulcet.now-playing.title"].firstMatch
        guard title.waitForExistence(timeout: 10), waitForLabel(track, of: title, timeout: 10) else {
            XCTFail("Now Playing must show \(track): " + app.debugDescription)
            return nil
        }
        let stars = app.descendants(matching: .any)["dulcet.now-playing.rating"].firstMatch
        guard stars.waitForExistence(timeout: 10), stars.isHittable else {
            XCTFail("Now Playing must offer the playing track's stars: " + app.debugDescription)
            return nil
        }
        return stars
    }

    /// Pauses Now Playing, then steps back with Previous until `track` is in front, so a later tap
    /// cannot reach a track the queue advanced to on its own. Four presses: the first may only
    /// restart the track in front, and the fixture album has three tracks.
    @MainActor
    private func bringPausedNowPlaying(to track: String, in app: XCUIApplication) -> Bool {
        // By label, re-queried each time: an element bound to "Pause" stops resolving once it reads Play.
        func playerButton(_ label: String) -> XCUIElement? {
            app.buttons.matching(NSPredicate(format: "label == %@", label))
                .allElementsBoundByIndex.first { $0.frame.width > 0 && $0.isHittable }
        }
        playerButton("Pause")?.tap()
        let pauseDeadline = Date().addingTimeInterval(10)
        while playerButton("Play") == nil, Date() < pauseDeadline {
            Thread.sleep(forTimeInterval: 0.25)
        }
        guard playerButton("Play") != nil else {
            XCTFail("Now Playing must pause before its stars are tapped: " + app.debugDescription)
            return false
        }
        let title = app.staticTexts["dulcet.now-playing.title"].firstMatch
        for _ in 0..<4 where title.label != track {
            playerButton("Previous")?.tap()
            _ = waitForLabel(track, of: title, timeout: 5)
        }
        guard title.label == track else {
            XCTFail("Now Playing must be back on \(track) before its stars are tapped; title=\(title.label)")
            return false
        }
        return true
    }

    /// Taps star `star` of a five-star row where it is drawn.
    @MainActor
    private func tapStar(_ star: Int, of stars: XCUIElement) {
        stars.coordinate(withNormalizedOffset: CGVector(dx: (Double(star) - 0.5) / 5, dy: 0.5)).tap()
    }

    /// What the stars say their value is, settled: "Not rated", "1 star", "3 stars".
    private func ratingValue(_ rating: Int) -> String {
        switch rating {
        case 0: "Not rated"
        case 1: "1 star"
        default: "\(rating) stars"
        }
    }

    /// The value, settled or with a pending or held change after it.
    @MainActor
    private func waitForValuePrefix(_ value: String, of element: XCUIElement, timeout: TimeInterval) -> Bool {
        let matches = { (observed: String?) in observed == value || observed?.hasPrefix(value + ", ") == true }
        let deadline = Date().addingTimeInterval(timeout)
        while !matches(element.value as? String), Date() < deadline {
            Thread.sleep(forTimeInterval: 0.05)
        }
        return matches(element.value as? String)
    }

    /// The server's rating of one song, 0 when it carries none, read over `/rest/getSong`.
    private func readServerSongRating(_ songID: String, configuration: LivePlaybackConfiguration) -> Int? {
        guard let song = restCall("getSong", [URLQueryItem(name: "id", value: songID)], configuration: configuration)?["song"]
                as? [String: Any] else { return nil }
        // Subsonic omits `userRating` on an unrated song.
        return song["userRating"] as? Int ?? 0
    }

    /// Polls until the server's rating of the song is `expected` or the timeout passes.
    @MainActor
    private func awaitServerSongRating(
        _ songID: String,
        configuration: LivePlaybackConfiguration,
        expected: Int,
        timeout: TimeInterval
    ) -> Int? {
        let deadline = Date().addingTimeInterval(timeout)
        var observed = readServerSongRating(songID, configuration: configuration)
        while observed != nil, observed != expected, Date() < deadline {
            RunLoop.current.run(until: Date().addingTimeInterval(1))
            observed = readServerSongRating(songID, configuration: configuration)
        }
        return observed
    }

    /// Library > Playlists: a row of the phone's Library, a sidebar section on a regular width.
    @MainActor
    private func openLibraryPlaylists(in app: XCUIApplication, compact: Bool) -> Bool {
        guard openDestination("Library", sidebarIdentifier: "dulcet.sidebar.library", in: app, compact: compact) else {
            return false
        }
        if !compact { return openSidebarLibrarySection("playlists", in: app) }
        let link = app.buttons["dulcet.reader.section.playlists"].firstMatch
        guard link.waitForExistence(timeout: 15), scrollIntoView(link, in: app) else {
            XCTFail("The phone's Library must list Playlists: " + app.debugDescription)
            return false
        }
        link.tap()
        return true
    }

    /// Presses an album's Play once the page lets it be pressed. A disabled button takes the tap
    /// and does nothing, and the page disables Play whenever the reader is offline, its tracks
    /// "Unavailable offline": OBSERVED in main's conformance run 37229956675, whose recording
    /// shows the iPhone album page offline for 4.5 s from about two seconds after it opened, the
    /// test's tap landing inside that window, and the bar keeping the restored queue's track.
    @MainActor
    private func tapAlbumPlay(in app: XCUIApplication) -> Bool {
        let play = app.buttons["dulcet.album.play"].firstMatch
        guard play.waitForExistence(timeout: 10), waitForEnabled(play, timeout: 30) else {
            XCTFail("The album page must offer Play, enabled: " + app.debugDescription)
            return false
        }
        play.tap()
        return true
    }

    @MainActor
    private func waitForEnabled(_ element: XCUIElement, timeout: TimeInterval) -> Bool {
        let deadline = Date().addingTimeInterval(timeout)
        while !element.isEnabled, Date() < deadline {
            RunLoop.current.run(until: Date().addingTimeInterval(0.25))
        }
        return element.isEnabled
    }

    /// One `/rest` call with the disposable account; the envelope's body on `ok`, else nil with
    /// the endpoint named -- never the URL, which carries a token.
    private func restCall(
        _ endpoint: String,
        _ query: [URLQueryItem],
        configuration: LivePlaybackConfiguration
    ) -> [String: Any]? {
        let salt = (0..<16).map { _ in String(format: "%02x", UInt8.random(in: 0...255)) }.joined()
        let token = Insecure.MD5.hash(data: Data((configuration.password + salt).utf8))
            .map { String(format: "%02x", $0) }.joined()
        guard var components = URLComponents(string: configuration.serverURL) else { return nil }
        let basePath = components.path.hasSuffix("/") ? String(components.path.dropLast()) : components.path
        components.path = basePath + "/rest/" + endpoint
        components.queryItems = [
            URLQueryItem(name: "u", value: configuration.username),
            URLQueryItem(name: "t", value: token),
            URLQueryItem(name: "s", value: salt),
            URLQueryItem(name: "v", value: "1.16.1"),
            URLQueryItem(name: "c", value: "dulcet-ui-test"),
            URLQueryItem(name: "f", value: "json"),
        ] + query
        guard let url = components.url else { return nil }
        final class Outcome: @unchecked Sendable { var data: Data? }
        let outcome = Outcome()
        let done = DispatchSemaphore(value: 0)
        let task = URLSession.shared.dataTask(with: url) { data, response, error in
            if error == nil, (response as? HTTPURLResponse)?.statusCode == 200 { outcome.data = data }
            done.signal()
        }
        task.resume()
        guard done.wait(timeout: .now() + 15) == .success else {
            task.cancel()
            print("DULCET REST \(endpoint) timed out")
            return nil
        }
        guard let data = outcome.data,
              let document = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
              let envelope = document["subsonic-response"] as? [String: Any],
              envelope["status"] as? String == "ok" else {
            print("DULCET REST \(endpoint) did not return an ok envelope")
            return nil
        }
        return envelope
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
        guard awaitLiveAccountConnection(in: app, compact: false) else {
            XCTFail("The live account connection must succeed first")
            return
        }
        guard requireProofMarkers(in: app) else { return }
        guard openDestination("Library", sidebarIdentifier: "dulcet.sidebar.library", in: app, compact: false),
              openSidebarLibrarySection("albums", in: app) else {
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
        guard tapAlbumPlay(in: app) else { return }

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
        let canaryLabel = "UI Playback Canary, Dulcet Fixtures · Threshold Boundary, Track"
        // The rows this query must render, each exactly once. Their ORDER is not asserted here:
        // search reads the device first and the server's answer replaces rows in place and
        // appends the rest (spec §16.15), so which rank a row takes depends on what this device
        // already held when the last keystroke landed -- a matter of typing speed against the
        // debounce, not of correctness. The ranking itself is the core's, and its tests pin it.
        // What this proof keeps is what only the app can show: every rank carries its own
        // identifier and its own row, and activating one rank plays that row, not rank zero.
        let expectedLabels: Set<String> = [
            "Thirty One Seconds, Dulcet Fixtures · Threshold Boundary, Track",
            "Twenty Nine Seconds, Dulcet Fixtures · Threshold Boundary, Track",
            canaryLabel,
            "Threshold Boundary, Dulcet Fixtures, Album",
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

        guard awaitLiveAccountConnection(in: app, compact: windowExpectation == .compactWidth) else {
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
        // Labels are read as each rank is reached: the list is lazy, so reaching a low rank can
        // scroll a high one out of the hierarchy, and re-reading it would throw.
        var observedLabels: [String] = []
        var scrolledDuringWalk = false
        for rank in 0..<expectedLabels.count {
            let result = app.buttons["dulcet.search.result.\(rank)"].firstMatch
            if !result.waitForExistence(timeout: rank == 0 ? 10 : 5) {
                scrolledDuringWalk = true
                guard scrollIntoView(result, in: app) else {
                    XCTFail("Rank \(rank) must render its own identifier: " + app.debugDescription)
                    return
                }
            }
            let label = result.label
            XCTAssertTrue(
                expectedLabels.contains(label),
                "Rank \(rank) rendered \(label.debugDescription), which this query does not match"
            )
            XCTAssertFalse(observedLabels.contains(label), "Rank \(rank) repeats an earlier rank's row")
            observedLabels.append(label)
        }
        XCTAssertEqual(Set(observedLabels), expectedLabels, "Every matching row must render once")
        guard let canaryRank = observedLabels.firstIndex(of: canaryLabel) else {
            XCTFail("The canary must render: observed \(observedLabels)")
            return
        }
        XCTAssertNotEqual(
            canaryRank,
            0,
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
        let canaryResult = app.buttons["dulcet.search.result.\(canaryRank)"].firstMatch
        let resultsList = app.scrollViews.firstMatch
        guard resultsList.waitForExistence(timeout: 5) else {
            XCTFail("The ranked results must render in a scrollable list: " + app.debugDescription)
            return
        }
        var scrollAttempts = 0
        while scrollAttempts < 6 && !isReachableForTap(canaryResult, in: window) {
            // The walk above may have scrolled past the canary: a row above the window, or one
            // the lazy list has already released after a scroll, is reached by going back up.
            let above = canaryResult.exists
                ? canaryResult.frame.midY < window.frame.midY
                : scrolledDuringWalk
            if above { resultsList.swipeDown() } else { resultsList.swipeUp() }
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

        // A device cannot reach the Mac's loopback, so it may name one disposable host instead.
        proveLivePlaybackAdvancesPastScrobbleThreshold(allowingDisposableHost: true)
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

    /// The same proof on a phone: a compact window, the library reached through the tab bar, and
    /// the workflow's server play-count reads either side of it. A phone and an iPad run the same
    /// engine, but only a run on each shows the shell drives it there.
    @MainActor
    func testIPhoneSimulatorPlaybackAdvancesPastScrobbleThreshold() {
        guard requireSimulator(.phone, "The iPhone playback proof") else { return }
        XCUIDevice.shared.orientation = .portrait
        proveLivePlaybackAdvancesPastScrobbleThreshold(usingInjectedAccount: true, compact: true)
    }

    @MainActor
    private func proveLivePlaybackAdvancesPastScrobbleThreshold(
        usingInjectedAccount: Bool = false,
        allowingDisposableHost: Bool = false,
        compact: Bool = false
    ) {
        guard let configuration = livePlaybackConfiguration(allowingDisposableHost: allowingDisposableHost) else { return }

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
        if !compact {
            XCTAssertGreaterThan(
                window.frame.width,
                700,
                "This playback proof requires a regular-width iPad window; an iPhone is invalid evidence"
            )
        }

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

        guard awaitLiveAccountConnection(in: app, compact: compact) else {
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

        if compact {
            // The phone's library grid, scrolled to the album, as a person reaches it.
            guard openLibraryAlbum("Threshold Boundary", in: app, compact: true) else { return }
        } else {
            guard openDestination(
                "Library",
                sidebarIdentifier: "dulcet.sidebar.library",
                in: app,
                compact: false
            ), openSidebarLibrarySection("albums", in: app) else { return }

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
        }

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
        // The budget covers the next 500 ms position sample, the request, and a loaded host, and
        // it is a retry window: the facade retries a refused submission itself, waiting 1, 2, 4,
        // 8 and 16 s between tries (spec §15.3). 60 s holds the first request and five retries
        // (31 s of waiting) with half the budget left for the requests themselves, so a refusal
        // on a heavily loaded runner (CI run 37235449008) delays the acknowledgement instead of
        // ending the proof.
        guard let delivered = waitForScrobbleDeliveryCounts(
            in: deliveryMarker,
            timeout: 60,
            until: { ($0["delivered"] ?? 0) >= 1 }
        ) else {
            XCTFail(
                "The app must report the scrobble delivered before this proof returns; last marker: \(deliveryMarker.label)"
            )
            return
        }
        XCTAssertEqual(delivered["delivered"], 1, "Exactly one play is expected for one crossing")
        // A refused attempt that a retry then delivered is the designed path, not a failure of
        // this proof (apple-ci's server-side play count still has to read exactly one); what must
        // not be left is a play waiting in the outbox.
        XCTAssertEqual(
            delivered["pending"],
            0,
            "No play may be left unsent once it is delivered (failed attempts before it: \(delivered["failures"] ?? -1))"
        )
    }

    /// The live server every proof here reads -- and writes: playlists, favourites, and a play
    /// count whenever playback crosses the scrobble threshold. Automated writes go only to a local
    /// disposable server, so the URL must name a loopback host, or -- for a physical device, which
    /// cannot reach the Mac's loopback -- exactly the host `DULCET_UI_TEST_DISPOSABLE_HOST` names.
    /// Anything else fails before the app launches and before any request.
    private func livePlaybackConfiguration(allowingDisposableHost: Bool = false) -> LivePlaybackConfiguration? {
        guard let configuration = unguardedLivePlaybackConfiguration() else { return nil }
        let host = URLComponents(string: configuration.serverURL)?.host?.lowercased() ?? ""
        let disposable = allowingDisposableHost
            ? runtimeValue(environment: "DULCET_UI_TEST_DISPOSABLE_HOST", argument: "-dulcet-ui-test-disposable-host")?.lowercased()
            : nil
        guard Self.isLoopbackHost(host) || (disposable != nil && !host.isEmpty && host == disposable) else {
            XCTFail("DULCET_UI_TEST_SERVER_URL must name a loopback host (a local disposable server); refusing to write to '\(host)'")
            return nil
        }
        return configuration
    }

    static func isLoopbackHost(_ host: String) -> Bool {
        let bare = host.hasPrefix("[") && host.hasSuffix("]") ? String(host.dropFirst().dropLast()) : host
        if bare == "localhost" || bare == "::1" { return true }
        let octets = bare.split(separator: ".", omittingEmptySubsequences: false)
        return octets.count == 4 && octets.first == "127" && octets.allSatisfy { UInt8($0) != nil }
    }

    private func unguardedLivePlaybackConfiguration() -> LivePlaybackConfiguration? {
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
        scrollIntoViewCountingSwipes(
            element,
            in: app,
            probingBlockingSystemAlerts: probingBlockingSystemAlerts
        ) != nil
    }

    /// `scrollIntoView`, answering how many swipes it took (at most six), or nil when the element
    /// never became reachable.
    @MainActor
    private func scrollIntoViewCountingSwipes(
        _ element: XCUIElement,
        in app: XCUIApplication,
        probingBlockingSystemAlerts: Bool = true
    ) -> Int? {
        let window = app.windows.firstMatch
        guard window.exists else {
            XCTFail("Dulcet's app window is unavailable because the app terminated or was backgrounded")
            return nil
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
                    return nil
                case .absent:
                    break
                }
            }

            if element.exists {
                let frame = element.frame
                let midpoint = CGPoint(x: frame.midX, y: frame.midY)
                if !frame.isEmpty && !frame.isInfinite &&
                    window.frame.contains(midpoint) && element.isHittable {
                    return swipeCount
                }
            }

            guard swipeCount < 6 else { return nil }

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
