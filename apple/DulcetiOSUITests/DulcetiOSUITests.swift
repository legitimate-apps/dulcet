import CryptoKit
import UIKit
import XCTest

final class DulcetWindowWidthSettlementTests: XCTestCase {
    private final class Samples {
        let frames: [CGRect?]
        var elapsed: TimeInterval = 0
        var reads = 0

        init(_ frames: [CGRect?]) { self.frames = frames }

        func settle(
            timeout: TimeInterval = 1, queryDuration: TimeInterval = 0,
            expecting expectedClass: String? = nil, matching expectation: (CGFloat) -> Bool = { _ in true }
        ) throws -> CGFloat {
            try waitForSettledWindowWidth(
                timeout: timeout, expecting: expectedClass, matching: expectation,
                readFrame: {
                    self.elapsed += queryDuration
                    let frame = self.frames[min(self.reads, self.frames.count - 1)]
                    self.reads += 1
                    return frame
                },
                now: { self.elapsed },
                pause: { self.elapsed += $0 }
            )
        }
    }

    private func frame(_ width: CGFloat, height: CGFloat = 768) -> CGRect {
        CGRect(x: 0, y: 0, width: width, height: height)
    }

    func testTransientCompactWidthSettlesAtRegularWidth() throws {
        let samples = Samples([frame(0), frame(320), frame(1032), frame(1032)])
        XCTAssertEqual(try samples.settle(), 1032)
        XCTAssertEqual(samples.reads, 4)
        XCTAssertEqual(samples.elapsed, 0.75)
    }

    func testStableTransientCompactWidthWaitsForExpectedRegularWidth() throws {
        let samples = Samples([frame(0), frame(320), frame(320), frame(1032), frame(1032)])
        XCTAssertEqual(try samples.settle(timeout: 2, expecting: "regular", matching: { $0 > 700 }), 1032)
        XCTAssertEqual(samples.reads, 5)
        XCTAssertEqual(samples.elapsed, 1)
    }

    func testWidthStuckInCompactClassFailsAtDeadlineExpectingRegular() {
        let samples = Samples([frame(320)])
        do {
            _ = try samples.settle(expecting: "regular", matching: { $0 > 700 })
            XCTFail("A stable compact width must fail at the deadline when regular is required")
        } catch let error as WindowWidthSettlementTimeout {
            XCTAssertEqual(error.previousWidth, 320)
            XCTAssertEqual(error.latestWidth, 320)
            XCTAssertEqual(error.elapsed, 1)
            XCTAssertTrue(error.description.contains("320"))
            XCTAssertTrue(error.description.contains("regular"))
            XCTAssertEqual(samples.reads, 4)
        } catch { XCTFail("Unexpected settlement error: \(error)") }
    }

    func testInvalidOrMissingFramesBreakConsecutiveAgreement() throws {
        for invalid in [nil, frame(1032, height: 0), frame(.infinity), frame(.nan),
                        CGRect(x: CGFloat.infinity, y: 0, width: 1032, height: 768)] as [CGRect?] {
            let samples = Samples([frame(1032), invalid, frame(1032), frame(1032)])
            XCTAssertEqual(try samples.settle(), 1032)
            XCTAssertEqual(samples.reads, 4)
        }
    }

    func testChangingWidthFailsAtDeadlineWithBothWidthsAndElapsedTime() {
        let samples = Samples([frame(320), frame(321), frame(322), frame(323)])
        do {
            _ = try samples.settle()
            XCTFail("A width that keeps changing must fail at its deadline")
        } catch let error as WindowWidthSettlementTimeout {
            XCTAssertEqual(error.previousWidth, 322)
            XCTAssertEqual(error.latestWidth, 323)
            XCTAssertEqual(error.elapsed, 1)
            XCTAssertTrue(error.description.contains("previous-width=Optional(322.0)"))
            XCTAssertTrue(error.description.contains("latest-width=Optional(323.0)"))
            XCTAssertTrue(error.description.contains("elapsed=1.0 seconds"))
            XCTAssertEqual(samples.reads, 4)
        } catch { XCTFail("Unexpected settlement error: \(error)") }
    }

    func testAgreementReturnedAfterDeadlineDoesNotSettle() {
        let samples = Samples([frame(1032), frame(1032)])
        do {
            _ = try samples.settle(queryDuration: 0.6)
            XCTFail("A slow query must not turn agreement after the deadline into success")
        } catch let error as WindowWidthSettlementTimeout {
            XCTAssertEqual(error.previousWidth, 1032)
            XCTAssertEqual(error.latestWidth, 1032)
            XCTAssertGreaterThanOrEqual(error.elapsed, 1)
            XCTAssertEqual(samples.reads, 2)
        } catch { XCTFail("Unexpected settlement error: \(error)") }
    }
}

final class DulcetiOSUITests: PlayerHittabilityTestCase {
    /// Server cleanup a test registers once it has made something on the disposable server; run
    /// in reverse order after the test, pass or fail. `addTeardownBlock` would do the same, but it
    /// comes from XCTest's Swift overlay, which `tools/typecheck-xcuitest-sources` cannot resolve.
    private var afterTest: [() -> Void] = []
    // Each test owns its accounts. Relaunches of one endpoint keep the same identity; a
    // disposable fault proxy is an independent server rather than an implicit account rebind.
    private var liveAccountFixtures: [String: String] = [:]

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
        guard let width = settledWindowWidth(
            window, expecting: "compact", matching: { $0 < 700 }
        ) else { return }
        let frame = window.frame
        print("DULCET DISPLAY GEOMETRY width=\(width) window=\(frame) displayImageSize=\(display)")
        print(app.debugDescription)
        XCTAssertGreaterThan(frame.width, 0)
        XCTAssertLessThan(width, 700, "Run this full-display proof on an iPhone")
        XCTAssertEqual(frame.minX, 0, accuracy: 1)
        XCTAssertEqual(frame.minY, 0, accuracy: 1)
        XCTAssertEqual(
            frame.height / frame.width, display.height / display.width, accuracy: 0.01,
            "The app window must fill the portrait display without legacy letterboxing"
        )

        // A full-size empty window is not evidence that the account interface rendered.
        // Scope the account section's text and server field to this window, then require visible,
        // contained frames. The legacy title identifier now marks the native form's footer.
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
    /// Library uses one native navigation title at both default and accessibility text sizes.
    /// The former second marketing headline is gone; its wrapping is no longer the contract.
    @MainActor
    func testTheFirstRunLibraryUsesOneVisibleNativeTitleOnIPhone() {
        for size in ["UICTContentSizeCategoryL", "UICTContentSizeCategoryAccessibilityXXXL"] {
            XCUIDevice.shared.orientation = .portrait
            let app = XCUIApplication()
            app.launchArguments += ["-UIPreferredContentSizeCategoryName", size,
                "-dulcet-debug-account-fixture", "first-run-" + UUID().uuidString]
            app.launch()
            guard openDestination("Library", sidebarIdentifier: "dulcet.sidebar.library", in: app, compact: true) else { return }
            let window = app.windows.firstMatch
            let title = app.navigationBars.staticTexts["Library"].firstMatch
            let account = app.buttons["dulcet.account.open"].firstMatch
            guard title.waitForExistence(timeout: 10), account.waitForExistence(timeout: 10) else {
                XCTFail("Library must show its native title and Account toolbar item at \(size): " + app.debugDescription)
                return
            }
            let visibleTitles = app.navigationBars.staticTexts.matching(identifier: "Library")
                .allElementsBoundByIndex.filter { $0.isHittable }
            XCTAssertEqual(visibleTitles.count, 1, "Only one visible Library navigation title")
            for (name, element) in [("Library title", title), ("Account", account)] {
                XCTAssertTrue(element.isHittable, "\(name) must be reachable at \(size)")
                XCTAssertGreaterThan(element.frame.width, 1)
                XCTAssertGreaterThan(element.frame.height, 1)
                XCTAssertTrue(window.frame.contains(element.frame), "\(name) must fit the window at \(size)")
            }
            XCTAssertFalse(title.frame.intersects(account.frame), "Account must not cover the Library title")
            XCTAssertFalse(app.staticTexts["Your music, wherever you listen"].firstMatch.exists,
                "Library must not duplicate its native title with the removed marketing headline")
            let shot = XCTAttachment(screenshot: app.screenshot())
            shot.name = "first-run-library-\(size)"
            shot.lifetime = .keepAlways
            add(shot)
            print("DULCET FIRST RUN TITLE size=\(size) title=\(title.frame) account=\(account.frame)")
            app.terminate()
        }
    }

    @MainActor
    func testCompactShellKeepsTheAlbumAndOpensNowPlayingFromTheBar() {
        XCUIDevice.shared.orientation = .portrait
        let app = XCUIApplication()
        app.launchArguments.append("-dulcet-account-connect-layout-fixture")
        app.launch()

        let window = app.windows.firstMatch
        XCTAssertTrue(window.waitForExistence(timeout: 10), "The app window must exist")
        // Checks the experiment before the product: a regular-width window has no tab bar.
        guard let width = settledWindowWidth(
            window, expecting: "compact", matching: { $0 < 700 }
        ) else { return }
        XCTAssertLessThan(
            width,
            700,
            "This proof requires a compact-width iPhone window; an iPad is invalid evidence"
        )

        // This fixture starts with Account presented. Check keyboard policy before
        // closing it, then judge the tab bar after the native modal is dismissed.
        XCTAssertFalse(app.keyboards.firstMatch.exists,
                       "Account must not raise the keyboard on arrival")
        guard openDestination("Library", sidebarIdentifier: "dulcet.sidebar.library", in: app, compact: true) else { return }
        let libraryTab = app.tabBars.buttons["Library"].firstMatch
        guard libraryTab.waitForExistence(timeout: 10), libraryTab.isHittable else {
            XCTFail("A compact window must present a visible tab bar with Library: " + app.debugDescription)
            return
        }
        XCTAssertFalse(
            window.buttons["dulcet.sidebar.library"].firstMatch.exists,
            "A compact window must not fall back to the sidebar list"
        )
        XCTAssertTrue(libraryTab.isSelected, "The Library tab must be selected after closing Account")

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
        XCTAssertTrue(dulcetSearchField(in: app).waitForExistence(timeout: 5),
                      "The Search tab must show search")
        // Immediate native search presents the keyboard over the bottom accessory. End
        // search and visit another content tab before judging the player's visibility.
        guard openDestination("Listen Now", sidebarIdentifier: "dulcet.sidebar.listenNow", in: app, compact: true) else { return }
        XCTAssertTrue(bar.waitForExistence(timeout: 5) && self.playerElementIsHittable(bar),
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
        //    open when Library is chosen again from Listen Now, and choosing Library while it is the
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
        XCTAssertTrue(dulcetSearchField(in: app).waitForExistence(timeout: 5),
                      "The Search tab must show search")
        guard openDestination("Library", sidebarIdentifier: "dulcet.sidebar.library", in: app, compact: true) else { return }
        let gridKept = album.waitForExistence(timeout: 5) && !title.exists
        XCTAssertTrue(gridKept, "Library must come back on the grid it was left on")
        // Observed values, not a verdict: a line printed after a failed assertion must not read
        // as a pass.
        print("DULCET COMPACT SHELL OBSERVED bar-after-native-search=\(bar.exists)"
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
        guard let width = settledWindowWidth(
            window, expecting: "compact", matching: { $0 < 700 }
        ) else { return }
        XCTAssertLessThan(width, 700,
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
        guard let width = settledWindowWidth(
            window, expecting: "compact", matching: { $0 < 700 }
        ) else { return }
        XCTAssertLessThan(width, 700, "The proof starts on a compact portrait window")
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
            && app.descendants(matching: .any)["dulcet.sidebar.library"].firstMatch.waitForExistence(timeout: 5)
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
        guard let width = settledWindowWidth(
            window, expecting: "compact", matching: { $0 < 700 }
        ) else { return }
        XCTAssertLessThan(width, 700,
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
        let swipeWidth = window.frame.width
        // On the first track Previous is unavailable, so a right swipe asks for nothing.
        let unavailable = swipe("right-on-first", expecting: "swipe:none", then: "Disc 1 Track 1") {
            drag(from: swipeWidth * 0.2, to: swipeWidth * 0.8)
        }
        let next = swipe("left", expecting: "swipe:next", then: "Disc 1 Track 2") {
            drag(from: swipeWidth * 0.8, to: swipeWidth * 0.2)
        }
        // Up and across by the same distance: neither direction dominates. Upward, so the sheet's
        // own drag-to-dismiss is not what answers it. Observed on an iPhone 17 Pro simulator: an
        // upward drag with this much vertical travel ends without the swipe handler running at all
        // (no marker), at 45 degrees and at 30 alike -- presumably taken by the scroll view the
        // player sits in, which was not measured. So this step can show only that nothing happens:
        // the handler, if it runs, must ask for nothing, and the track must not change. Which of the
        // two occurred is printed; the classification itself is `ArtworkSwipeTests`'.
        let diagonalBefore = proofMarkers(in: app)
        drag(from: swipeWidth * 0.8, to: swipeWidth * 0.2, rising: swipeWidth * 0.6)
        let diagonalRecorded = waitForMarkers(after: diagonalBefore, ["swipe:none"], in: app, timeout: 3)
        Thread.sleep(forTimeInterval: 1)
        let diagonal = (diagonalRecorded == ["swipe:none"] || diagonalRecorded.isEmpty)
            && title.label == "Disc 1 Track 2"
        print("DULCET ARTWORK SWIPE step=diagonal recorded=\(diagonalRecorded)"
            + " handler-ran=\(!diagonalRecorded.isEmpty) title=\(title.label.debugDescription)")
        XCTAssertTrue(diagonal, "A diagonal drag must do nothing; recorded=\(diagonalRecorded) title=\(title.label)")
        let short = swipe("short", expecting: "swipe:none", then: "Disc 1 Track 2") {
            drag(from: swipeWidth * 0.6, to: swipeWidth * 0.6 - 40)
        }
        let previous = swipe("right", expecting: "swipe:previous", then: "Disc 1 Track 1") {
            drag(from: swipeWidth * 0.2, to: swipeWidth * 0.8)
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
        guard words.first == "dulcet-ui" else { return ["<unreadable marker log>"] }
        // A sheet's appearance records its accessibility environment for capture eligibility.
        // That metadata is not a handler event. Keep every action (including duplicates)
        // so the exact event sequence still rejects an extra or missing invocation.
        return words.dropFirst().filter { !$0.hasPrefix("accessibility:") }
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

    /// Opens a named server album through Browse and its Albums collection.
    @MainActor
    private func openLibraryAlbum(_ album: String, in app: XCUIApplication, compact: Bool) -> Bool {
        guard openSidebarLibrarySection("albums", in: app, compact: compact) else { return false }
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
        // A connecting launch can navigate to Listen Now during a global Sign Out query.
        // Open Account in the bounded loop before querying its connected-state control.
        let timedOut = app.staticTexts["The server took too long to respond"].firstMatch
        let tryAgain = app.buttons["dulcet.account-connect.primary-action"].firstMatch
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
            // At accessibility sizes the connected form's final section is lazy and offscreen.
            // Reveal the same Sign Out control only after the server-confirmed state appears.
            if app.staticTexts["dulcet.account.connected"].firstMatch.exists,
               scrollIntoView(signOut, in: app) { return true }
            while retries < 2, timedOut.exists, tryAgain.exists {
                retries += 1
                print("ACCOUNT CONNECT RETRY \(retries): account setup reached its request limit")
                tryAgain.tap()
                // A successful retry can navigate to Listen Now while its state is queried.
                // Revisit Connection within the same request-limit/readiness budget.
                let retryDeadline = Date().addingTimeInterval(45)
                repeat {
                    guard openDestination(
                        "Connection",
                        sidebarIdentifier: "dulcet.sidebar.settings",
                        in: app,
                        compact: compact
                    ) else { return false }
                    let remaining = max(0, retryDeadline.timeIntervalSince(Date()))
                    if signOut.waitForExistence(timeout: min(10, remaining)) { return true }
                    if app.staticTexts["dulcet.account.connected"].firstMatch.exists,
                       scrollIntoView(signOut, in: app) { return true }
                } while Date() < retryDeadline
            }
        } while Date() < deadline
        return false
    }

    /// Server categories live in Browse; personal playlists live in Library. A tab retains
    /// its stack, including an album restored after reconnect, so return through native Back
    /// controls before requiring the destination's category row.
    @MainActor
    private func openSidebarLibrarySection(
        _ section: String, in app: XCUIApplication, compact: Bool = false
    ) -> Bool {
        let destination = section == "playlists" ? "Library" : "Browse"
        let sidebar = section == "playlists" ? "dulcet.sidebar.library" : "dulcet.sidebar.browse"
        guard openDestination(destination, sidebarIdentifier: sidebar, in: app, compact: compact) else { return false }
        let row = app.buttons["dulcet.reader.section.\(section)"].firstMatch
        let back = app.navigationBars.buttons["BackButton"].firstMatch
        var pops = 0
        while !row.exists, back.exists, back.isHittable, pops < 4 {
            back.tap()
            pops += 1
        }
        guard row.waitForExistence(timeout: 10), scrollIntoView(row, in: app) else {
            XCTFail("\(destination) must offer its \(section) section: " + app.debugDescription)
            return false
        }
        row.tap()
        return true
    }

    /// Reaches a top-level destination the way a person does on the window's size class: the tab
    /// bar on a compact window, the sidebar on a regular one.
    @MainActor
    private func openDestination(
        _ tabTitle: String, sidebarIdentifier: String, in app: XCUIApplication, compact: Bool
    ) -> Bool {
        if tabTitle != "Search" {
            let field = app.searchFields.firstMatch
            let closeSearch = app.buttons["Close"].firstMatch
            // The system's compact Search close control shares the field's row. It ends
            // editing and restores native navigation; hiding the keyboard alone does not.
            if field.exists, closeSearch.exists, closeSearch.isHittable,
               abs(closeSearch.frame.midY - field.frame.midY) < field.frame.height {
                closeSearch.tap()
                guard field.waitForNonExistence(timeout: 10) else {
                    XCTFail("Native search must close before selecting \(tabTitle)")
                    return false
                }
            }
        }
        if tabTitle == "Connection" {
            if app.navigationBars["Account"].firstMatch.exists { return true }
            let close = app.buttons["dulcet.account.close"].firstMatch
            if close.exists { close.tap(); guard close.waitForNonExistence(timeout: 10) else { return false } }
            let account = app.buttons["dulcet.account.open"].firstMatch
            if !account.exists, !app.tabBars.buttons["Connection"].firstMatch.exists,
               app.tabBars.buttons["Listen Now"].firstMatch.exists {
                guard openDestination("Listen Now", sidebarIdentifier: "dulcet.sidebar.listenNow", in: app, compact: compact) else { return false }
            }
            if account.exists {
                guard account.isHittable else { return false }
                account.tap()
                return app.navigationBars["Account"].firstMatch.waitForExistence(timeout: 10)
            }
            let legacySidebar = app.buttons[sidebarIdentifier].firstMatch
            if !compact, legacySidebar.exists, legacySidebar.isHittable { legacySidebar.tap(); return true }
        }
        let close = app.buttons["dulcet.account.close"].firstMatch
        if close.exists { close.tap(); guard close.waitForNonExistence(timeout: 10) else { return false } }
        if tabTitle == "Search", app.searchFields.firstMatch.exists { return true }
        let tab = app.tabBars.buttons[tabTitle].firstMatch
        let nativeSidebar = app.descendants(matching: .any)[sidebarIdentifier.replacingOccurrences(of: "dulcet.sidebar.", with: "dulcet.tab.")].firstMatch
        let legacySidebar = app.buttons[sidebarIdentifier].firstMatch
        guard let row = [tab, nativeSidebar, legacySidebar].first(where: { $0.exists && $0.isHittable }) else {
            XCTFail("The \(tabTitle) destination must be visible: " + app.debugDescription)
            return false
        }
        // Re-selecting the current native tab resets its navigation stack. Keep an
        // existing selection and let callers use Back before opening their category.
        func destinationReached() -> Bool {
            if tabTitle == "Search", app.searchFields.firstMatch.exists { return true }
            return row.exists && row.isSelected
        }
        if destinationReached() { return true }
        for _ in 0..<2 {
            if row.exists, row.isHittable { row.tap() }
            let deadline = Date().addingTimeInterval(3)
            repeat {
                if destinationReached() { return true }
                Thread.sleep(forTimeInterval: 0.1)
            } while Date() < deadline
        }
        XCTFail("The destination tap must select \(tabTitle): " + app.debugDescription)
        return false
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
        // A tap synthesized while the host is starved can be lost: main's run 37550927017 tapped
        // the bar at load1 315 on three cores and no Now Playing surface followed in 15 s, with the
        // bar still showing and the track playing. Only a tap that left the bar showing, hittable
        // and with no Now Playing surface is repeated, once, and the repeat is printed, so a bar
        // that ignores taps still fails here.
        let surface = [
            app.staticTexts["dulcet.now-playing.title"].firstMatch,
            app.buttons["dulcet.now-playing.close"].firstMatch,
            app.buttons["dulcet.now-playing.lyrics"].firstMatch,
        ]
        let deadline = Date().addingTimeInterval(15)
        while !surface.contains(where: \.exists), Date() < deadline {
            Thread.sleep(forTimeInterval: 0.25)
        }
        if !surface.contains(where: \.exists), bar.exists, self.playerElementIsHittable(bar) {
            print("DULCET NOW PLAYING RETAP the first tap on the bar presented nothing in 15 s")
            bar.tap()
        }
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
        app.launchArguments += liveAccountLaunchArguments(
            serverURL: configuration.serverURL, configuration: configuration)
        app.launch()
        let window = app.windows.firstMatch
        XCTAssertTrue(window.waitForExistence(timeout: 10), "The app window must exist")
        guard let width = settledWindowWidth(
            window, expecting: "compact", matching: { $0 < 700 }
        ) else { return }
        XCTAssertLessThan(width, 700,
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

    /// Through the production reader on the named destination:
    /// 1. A favourite made on an album page shows at once and reaches the server (CONF-84).
    /// 2. A fresh launch with only the saved account opens Listen Now, reconnects without a tap,
    ///    and still shows that favourite (CONF-76, CONF-10b).
    /// 3. Search finds the album after reconnect and clears the device-only scope.
    /// 4. Removing the favourite reaches the server, leaving the fixture as it started.
    /// The server is read back over `/rest` with the disposable account.
    @MainActor
    private func proveASavedAccountReopensIntoItsLibraryAndKeepsAFavourite(compact expectedCompact: Bool) {
        guard let configuration = livePlaybackConfiguration() else { return }
        let album = "Double Lines"
        let app = XCUIApplication()
        app.launchArguments += liveAccountLaunchArguments(
            serverURL: configuration.serverURL, configuration: configuration)
        app.launch()
        let window = app.windows.firstMatch
        XCTAssertTrue(window.waitForExistence(timeout: 10), "The app window must exist")
        guard let width = settledWindowWidth(
            window, expecting: expectedCompact ? "compact" : "regular",
            matching: { ($0 < 700) == expectedCompact }
        ) else { return }
        let compact = width < 700
        print("DULCET READER PROOF destination=\(compact ? "compact" : "regular") width=\(width)")
        guard compact == expectedCompact else {
            XCTFail("The reader proof expected a \(expectedCompact ? "compact" : "regular") window, got width=\(width)")
            return
        }
        guard awaitLiveAccountConnection(in: app, compact: compact) else {
            XCTFail("The live account connection must succeed first")
            return
        }

        // 1. Favourite the album, from a known starting point whatever an earlier run left.
        guard openLibraryAlbum(album, in: app, compact: compact) else { return }
        let more = app.buttons["dulcet.album.more"].firstMatch
        func favoriteAction() -> XCUIElement? {
            guard more.waitForExistence(timeout: 10), more.isHittable else { return nil }
            more.tap()
            let action = app.buttons["dulcet.album.favorite"].firstMatch
            return action.waitForExistence(timeout: 5) ? action : nil
        }
        guard var action = favoriteAction() else {
            XCTFail("The album More menu must offer Favorite: " + app.debugDescription)
            return
        }
        if action.label == "Remove Favorite" {
            action.tap()
            guard awaitServerAlbumStarred(album, configuration: configuration, expected: false, timeout: 20) == false,
                  let cleared = favoriteAction(), cleared.label == "Favorite" else {
                XCTFail("An earlier run's favorite could not be cleared first")
                return
            }
            action = cleared
        }
        XCTAssertEqual(readServerAlbumStarred(album, configuration: configuration), false,
            "The control: the server must not already hold this favorite")
        action.tap()
        XCTAssertEqual(awaitServerAlbumStarred(album, configuration: configuration, expected: true, timeout: 20), true,
            "The favorite must reach the server")

        // 2. Relaunch with nothing but the saved account at the default Listen Now destination.
        app.terminate()
        app.launchArguments = liveAccountFixtureArguments(serverURL: configuration.serverURL)
        app.launch()
        let landing = app.navigationBars["Listen Now"].firstMatch
        XCTAssertTrue(landing.waitForExistence(timeout: 15),
            "A saved account must open Listen Now without an account prompt: " + app.debugDescription)
        guard openDestination("Connection", sidebarIdentifier: "dulcet.sidebar.settings", in: app, compact: compact),
              app.buttons["Sign Out"].firstMatch.waitForExistence(timeout: 30) else {
            XCTFail("The saved account must reconnect without a tap: " + app.debugDescription)
            return
        }
        guard openLibraryAlbum(album, in: app, compact: compact) else { return }
        guard let restored = favoriteAction() else { XCTFail("The album More menu must open"); return }
        XCTAssertEqual(restored.label, "Remove Favorite", "The favorite must survive relaunch")
        app.tap() // Dismiss the native menu without invoking an action.
        attachScreenshot(named: "saved-launch-connected-favourite", app: app)

        // 3. Connected search merges server and device results; its offline scope clears.
        guard openDestination("Search", sidebarIdentifier: "dulcet.sidebar.search", in: app, compact: compact) else {
            return
        }
        let field = dulcetSearchField(in: app)
        guard field.waitForExistence(timeout: 5) else {
            XCTFail("The search field must exist: " + app.debugDescription)
            return
        }
        field.tap()
        field.typeText("Dou")
        let firstResult = app.buttons["dulcet.search.result.0"].firstMatch
        XCTAssertTrue(firstResult.waitForExistence(timeout: 10),
            "Connected search must answer: " + app.debugDescription)
        // The scope's icon carries the same identifier; the sentence is the text.
        let scope = app.staticTexts["dulcet.search.scope"].firstMatch
        XCTAssertTrue(scope.waitForNonExistence(timeout: 30),
            "The device-only search scope must clear after the server answers")
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
            "The album must be among the search results; rank 0 was \(resultLabel): "
                + app.debugDescription)
        let albumRank = albumResult.exists ? albumResult.identifier : "<none>"
        _ = dismissKeyboardIfPresent(in: app)

        // 4. The automatic reconnect already completed; leave the fixture as it was found.
        guard openDestination("Library", sidebarIdentifier: "dulcet.sidebar.library", in: app, compact: compact) else {
            return
        }
        let albumTitle = app.staticTexts["dulcet.album.title"].firstMatch
        if !(albumTitle.exists && albumTitle.label == album) {
            guard openLibraryAlbum(album, in: app, compact: compact) else { return }
        }
        guard let remove = favoriteAction(), remove.label == "Remove Favorite" else {
            XCTFail("The album More menu must offer Remove Favorite after reconnect")
            return
        }
        remove.tap()
        XCTAssertEqual(awaitServerAlbumStarred(album, configuration: configuration, expected: false, timeout: 20), false,
            "Removing the favourite must reach the server")
        print("DULCET READER PROOF PASS destination=\(compact ? "compact" : "regular")"
            + " relaunch=auto-connected scope=\(scopeLabel.debugDescription) rank0=\(resultLabel.debugDescription)"
            + " album-row=\(albumRank)")
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
        let markersBeforeOpen = proofMarkers(in: app)
        bar.tap()
        let openMarkers = waitForMarkers(after: markersBeforeOpen, ["mini-player-open"], in: app, timeout: 3)
        guard openMarkers == ["mini-player-open"] else {
            XCTFail("The bar's single tap must reach its open handler; markers=\(openMarkers): " + app.debugDescription)
            return
        }
        let title = app.staticTexts["dulcet.now-playing.title"].firstMatch
        let titled = title.waitForExistence(timeout: 10) && waitForLabel("Playable After Skip", of: title, timeout: 10)
        guard titled else {
            XCTFail("The player must show the track that plays; handlers=\(proofMarkers(in: app)): " + app.debugDescription)
            return
        }
        let upNextToggle = app.buttons["dulcet.now-playing.up-next"].firstMatch
        guard upNextToggle.waitForExistence(timeout: 5), upNextToggle.isHittable else {
            XCTFail("The playing track must offer Up Next: " + app.debugDescription)
            return
        }
        upNextToggle.tap()
        let queue = app.descendants(matching: .any)["dulcet.upNext"].firstMatch
        guard queue.waitForExistence(timeout: 10) else {
            XCTFail("Up Next must expose its actual queue list: " + app.debugDescription)
            return
        }
        // AutoPlay is on by default. Its recommendations precede Previously Played, so the
        // lazy list may not materialize history until the person scrolls the queue itself.
        let autoplay = app.switches["dulcet.upNext.autoplay.toggle"].firstMatch
        let autoplayValue = autoplay.exists ? String(describing: autoplay.value ?? "<none>") : "<absent>"
        let visibleRecommendations = queue.buttons.matching(NSPredicate(
            format: "identifier BEGINSWITH %@", "dulcet.upNext.autoplay.row."
        )).count
        let historyToggle = queue.descendants(matching: .any)["dulcet.history.toggle"].firstMatch
        let initiallyReachable = historyToggle.exists && historyToggle.isHittable
        var historySwipes = 0
        while !(historyToggle.exists && historyToggle.isHittable), historySwipes < 6 {
            queue.swipeUp()
            historySwipes += 1
        }
        guard historyToggle.exists && historyToggle.isHittable else {
            XCTFail("Scrolling Up Next must reveal a hittable Previously Played disclosure: " + app.debugDescription)
            return
        }
        let row = queue.buttons["dulcet.history.row.0"].firstMatch
        XCTAssertFalse(row.exists, "Previously Played must start collapsed")
        historyToggle.tap()
        var rowSwipes = 0
        while !(row.exists && row.isHittable), rowSwipes < 6 {
            queue.swipeUp()
            rowSwipes += 1
        }
        guard row.exists && row.isHittable else {
            XCTFail("Expanding Previously Played must reveal its actual first track: " + app.debugDescription)
            return
        }
        let historyRow = row.label
        print("DULCET SKIP HISTORY REVEAL autoplay=\(autoplayValue) visible-recommendations=\(visibleRecommendations)"
            + " initially-reachable=\(initiallyReachable) disclosure-swipes=\(historySwipes) row-swipes=\(rowSwipes)"
            + " disclosure-hittable=\(historyToggle.isHittable) row-hittable=\(row.isHittable)")
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

    /// At the largest text size the album page is taller than the screen: its playable next track starts
    /// below the fold, so the proof reached it by scrolling -- the path a person takes -- and the
    /// header's Play and Shuffle each keep their label on one line, full width, rather than
    /// wrapping it a letter per line into a button taller than the screen.
    @MainActor
    private func assertAlbumHeaderReadsAtTheLargestTextSize(_ run: SkipProbeRun) {
        let frames = run.headerActions.map { "\($0)" }.joined(separator: " ")
        print("DULCET SKIP PROBE AX5 HEADER catalog-initially-reachable=\(run.catalogInitiallyReachable)"
            + " catalog-swipes=\(run.catalogSwipes) next-track-initially-reachable=\(run.nextTrackInitiallyReachable)"
            + " row-swipes=\(run.rowSwipes) play-shuffle=\(frames)")
        XCTAssertFalse(run.nextTrackInitiallyReachable,
                       "The album's playable next track must start below the fold at the largest text size")
        XCTAssertGreaterThanOrEqual(run.rowSwipes, 1,
                                    "At this size the playable next track must start below the fold; header=\(frames)")
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
        let catalogInitiallyReachable: Bool
        let catalogSwipes: Int
        let nextTrackInitiallyReachable: Bool
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
        app.launchArguments += liveAccountLaunchArguments(
            serverURL: configuration.serverURL, configuration: configuration,
            extraArguments: ["-dulcet-debug-ui-markers"] + extraLaunchArguments)
        app.launch()
        let window = app.windows.firstMatch
        XCTAssertTrue(window.waitForExistence(timeout: 10), "The app window must exist")
        guard awaitLiveAccountConnection(in: app, compact: true) else {
            XCTFail("The live account connection must succeed first")
            return nil
        }
        guard requireProofMarkers(in: app),
              openDestination("Browse", sidebarIdentifier: "dulcet.sidebar.browse", in: app, compact: true) else {
            return nil
        }
        // Browse owns the server catalog. Its category rows can put Recently Added below
        // the fold at accessibility sizes; reach the shelf through actual touch scrolling.
        let albums = app.buttons.matching(identifier: "dulcet.library.album")
        let album = albums.matching(NSPredicate(format: "label BEGINSWITH %@", probe.album)).firstMatch
        let firstAlbum = albums.firstMatch
        let catalogInitiallyReachable = firstAlbum.exists && firstAlbum.isHittable
        let catalogHeading = app.buttons["dulcet.reader.home.recentlyAdded.all"].firstMatch
        // A native List may not materialize this shelf until it scrolls into view.
        _ = catalogHeading.waitForExistence(timeout: 5)
        guard let headingSwipes = scrollIntoViewCountingSwipes(
                catalogHeading, in: app, probingBlockingSystemAlerts: false,
                minimumSpaceBelow: app.windows.firstMatch.frame.height / 2
              ) else {
            XCTFail("Touch scrolling must reveal Recently Added with room for its lazy grid: " + app.debugDescription)
            return nil
        }
        // Revealing a stable heading is independent of the catalog request completing. Retain
        // the data-readiness wait after the grid has room to realize its first actual tile.
        guard firstAlbum.waitForExistence(timeout: 30),
              let tileSwipes = scrollIntoViewCountingSwipes(
            firstAlbum, in: app, probingBlockingSystemAlerts: false,
            maximumSwipes: 6 - headingSwipes
        ), firstAlbum.exists, firstAlbum.isHittable else {
            XCTFail("Touch scrolling must reveal Browse's actual album catalog: " + app.debugDescription)
            return nil
        }
        let catalogSwipes = headingSwipes + tileSwipes
        print("DULCET SKIP PROBE CATALOG initially-reachable=\(catalogInitiallyReachable)"
            + " swipes=\(catalogSwipes) tile-hittable=\(firstAlbum.isHittable)")
        // The shelf is horizontal. At accessibility sizes the third fixture cover is
        // outside the window even after its shelf is vertically visible.
        let shelf = app.descendants(matching: .any)["dulcet.reader.home.recentlyAdded"]
            .scrollViews.firstMatch
        guard shelf.waitForExistence(timeout: 5) else {
            XCTFail("Recently Added must expose its horizontal shelf: " + app.debugDescription)
            return nil
        }
        for _ in 0..<6 {
            if album.exists, window.frame.contains(CGPoint(x: album.frame.midX, y: album.frame.midY)),
               album.isHittable { break }
            guard shelf.exists else { break }
            // A full-velocity swipe can jump over the middle cover and oscillate back past
            // it. Move half a cover slowly, within the actual shelf, so each pass advances.
            let visible = shelf.frame.intersection(window.frame)
            guard !visible.isEmpty else { break }
            let movesRight = album.exists && album.frame.midX < visible.minX
            let distance = min(visible.width * 0.35,
                               max(44, (album.exists ? album.frame.width : visible.width) / 2))
            let fromX = movesRight ? visible.minX + visible.width * 0.3
                : visible.maxX - visible.width * 0.3
            let toX = fromX + (movesRight ? distance : -distance)
            print("DULCET SKIP PROBE SHELF target-x=\(album.exists ? album.frame.midX : -1)"
                + " drag=\(toX - fromX)")
            window.coordinate(withNormalizedOffset: CGVector(
                dx: (fromX - window.frame.minX) / window.frame.width,
                dy: (visible.midY - window.frame.minY) / window.frame.height))
                .press(forDuration: 0.1, thenDragTo:
                    window.coordinate(withNormalizedOffset: CGVector(
                        dx: (toX - window.frame.minX) / window.frame.width,
                        dy: (visible.midY - window.frame.minY) / window.frame.height)),
                    withVelocity: XCUIGestureVelocity(200), thenHoldForDuration: 0.3)
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
        // The compact Browse categories no longer guarantee a below-fold shelf. The album's
        // accessibility-size header does put its next track below the fold: prove this before
        // scrolling, then use real touch input to reach both tracks and trigger the skip.
        let nextTrackInitiallyReachable = playableRow.exists && playableRow.isHittable
        // The first row can be hittable while the second is still unrealized below the
        // large-text header. Reveal both before checking their order, within the same bound.
        let rowSwipes = scrollIntoViewCountingSwipes(unplayableRow, in: app, alsoRevealing: playableRow)
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
            catalogInitiallyReachable: catalogInitiallyReachable,
            catalogSwipes: catalogSwipes,
            nextTrackInitiallyReachable: nextTrackInitiallyReachable,
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
        app.launchArguments += liveAccountLaunchArguments(
            serverURL: configuration.serverURL, configuration: configuration)
        app.launch()
        let window = app.windows.firstMatch
        XCTAssertTrue(window.waitForExistence(timeout: 10), "The app window must exist")
        guard let width = settledWindowWidth(
            window, expecting: expectedCompact ? "compact" : "regular",
            matching: { ($0 < 700) == expectedCompact }
        ) else { return }
        let compact = width < 700
        guard compact == expectedCompact else {
            XCTFail("The proof expects a \(expectedCompact ? "compact" : "regular") window; width=\(width)")
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

        // The 31-second canary ended before the Pause touch in run 39301701884:
        // track touch at 141 s, Pause touch at 177 s. Restore its saved queue paused instead.
        // This proof needs a held queue, not a timed Play/Pause race on a busy UI runner.
        let canary = track("UI Playback Canary")
        guard canary.waitForExistence(timeout: 10), scrollIntoView(canary, in: app) else {
            XCTFail("The album must list UI Playback Canary: " + app.debugDescription)
            return
        }
        canary.tap()
        app.terminate()
        app.launch()
        guard awaitLiveAccountConnection(in: app, compact: true) else {
            XCTFail("The restored queue's account must reconnect before reading its album")
            return
        }
        // Reading the album supplies the catalog needed to resolve the persisted selection.
        guard openLibraryAlbum("Threshold Boundary", in: app, compact: true),
              dragged.waitForExistence(timeout: 15), scrollIntoView(dragged, in: app) else { return }
        let bar = app.buttons["dulcet.mini-player.open"].firstMatch
        let playPause = app.buttons["dulcet.mini-player.play-pause"].firstMatch
        guard waitForLabelContaining("UI Playback Canary", of: bar, timeout: 15),
              waitForLabel("Play", of: playPause, timeout: 10) else {
            XCTFail("The saved queue must restore the original canary paused before reading Up Next: " + app.debugDescription)
            return
        }
        print("DULCET DRAG QUEUE PRECONDITION restored-canary=true paused=true")
        guard let before = awaitSettledUpNext(openingFrom: bar, in: app, compact: true) else { return }
        // In run 40065138225 both 0.8-second gestures showed the menu near/after scheduled
        // arrival and never lifted. Open the named row's menu in this process, then lift its actual
        // system Preview with a second touch, as the album-menu proof does. Each attempt must
        // positively reach that menu before any drag; a menu action cannot satisfy the oracle.
        var nativeMenuReady = 0
        guard let result = dragOntoTheBar(
            dragged, bar: bar, before: before, adding: 1, menuMarker: "Add to Playlist\u{2026}",
            in: app, compact: true, prepareSource: { attempt in
                guard let preview = self.nativeTrackMenuPreview(on: dragged, attempt: attempt, in: app) else { return nil }
                nativeMenuReady += 1
                return preview
            }
        ) else { return }
        attachScreenshot(named: "track-dragged-from-its-menu", app: app)
        XCTAssertGreaterThan(nativeMenuReady, 0, "The track's native context menu must actually open")
        XCTAssertEqual(nativeMenuReady, result.drags, "Every drag must start from the observed open menu's Preview")
        XCTAssertEqual(result.menuLeftOpen, 0, "Dragging the Preview must lift out of the context menu")
        XCTAssertEqual(result.after.count, before.count + 1,
                       "The drag must append exactly one track; before=\(before) after=\(result.after) drags=\(result.drags)"
                       + " menu-left-open-after=\(result.menuLeftOpen)")
        XCTAssertEqual(Array(result.after.prefix(before.count)), before, "The preexisting Up Next entries must stay in order")
        XCTAssertTrue(result.after.last?.hasPrefix("Thirty One Seconds,") == true,
                      "The appended entry must be the dragged track; after=\(result.after)")
        XCTAssertEqual(playPause.label, "Play", "The queue proof must keep playback paused")
        XCTAssertTrue(bar.label.contains("UI Playback Canary"),
                      "The drag must append to the held canary's queue; bar=\(bar.label)")
        print("DULCET DRAG FROM MENU before=\(before) after=\(result.after) drags=\(result.drags)"
            + " menu-left-open-after=\(result.menuLeftOpen) native-menu-ready=\(nativeMenuReady)")
    }

    @MainActor
    private func nativeTrackMenuPreview(on track: XCUIElement, attempt: Int, in app: XCUIApplication) -> XCUIElement? {
        var ready: XCUIElement?
        withoutIdleWaits(app) {
            guard track.exists, track.isHittable else {
                XCTFail("The named track must be hittable before opening its menu")
                return
            }
            track.press(forDuration: 1.2)
            let marker = app.buttons["Add to Playlist\u{2026}"].firstMatch
            let previews = app.otherElements.matching(NSPredicate(format: "label == %@", "Preview"))
            let preview = previews.element(boundBy: 0)
            guard marker.waitForExistence(timeout: 10), marker.isHittable,
                  preview.waitForExistence(timeout: 10), previews.count == 1, preview.isHittable,
                  !marker.frame.intersects(preview.frame) else {
                XCTFail("The track's native menu and separate hittable Preview must be open before every drag: " + app.debugDescription)
                return
            }
            print("DULCET TRACK MENU READY attempt=\(attempt) preview=\(preview.frame) marker=\(marker.frame)")
            ready = preview
        }
        return ready
    }

    /// Plays the album's last track, then restores that saved selection paused so a short
    /// fixture cannot finish while a drag is counted. Answers the restored bar.
    @MainActor
    private func startPlayingTheAlbumsLastTrack(
        album: String,
        last: String,
        tile: XCUIElement,
        in app: XCUIApplication,
        compact: Bool
    ) -> XCUIElement? {
        tile.tap()
        let row = app.buttons.matching(identifier: "dulcet.reader.track")
            .matching(NSPredicate(format: "label BEGINSWITH %@", last + ", ")).firstMatch
        guard row.waitForExistence(timeout: 15), scrollIntoView(row, in: app) else {
            XCTFail("\(album) must list \(last): " + app.debugDescription)
            return nil
        }
        row.tap()
        // The loading bar exists before playback starts. In run 39938927349 its label
        // was still Play at the conditional Pause check, so no toggle touch was sent; it
        // became Pause while the helper waited for Play. Prove the selected track is playing
        // before terminating, then use the same paused restoration as the track-menu proof.
        guard waitForMiniPlayer(track: last, playing: true, in: app, timeout: 15) else {
            XCTFail("The album's last track must actually start before its queue is restored paused: " + app.debugDescription)
            return nil
        }
        app.terminate()
        app.launch()
        guard awaitLiveAccountConnection(in: app, compact: compact) else {
            XCTFail("The restored queue's account must reconnect before reading its album")
            return nil
        }
        guard openLibraryAlbum(album, in: app, compact: compact) else { return nil }
        // Reading the album supplies the catalog needed to resolve the saved queue selection.
        guard waitForMiniPlayer(track: last, playing: false, in: app, timeout: 10) else {
            XCTFail("The saved queue must restore the album's last track paused before a drag: " + app.debugDescription)
            return nil
        }
        print("DULCET ALBUM DRAG PRECONDITION track=\(last) started=true restored-paused=true")
        return app.buttons.matching(identifier: "dulcet.mini-player.open").element(boundBy: 0)
    }

    /// The title and transport must describe the same publication. A loading bar or an older
    /// restored selection cannot supply the playing or paused precondition of a drag proof.
    @MainActor
    private func waitForMiniPlayer(track: String, playing: Bool, in app: XCUIApplication, timeout: TimeInterval) -> Bool {
        let deadline = ProcessInfo.processInfo.systemUptime + timeout
        repeat {
            if let window = try? app.windows.element(boundBy: 0).snapshot() {
                let elements = snapshotElements(in: window)
                let bars = elements.filter { $0.elementType == .button && $0.identifier == "dulcet.mini-player.open" }
                let controls = elements.filter { $0.elementType == .button && $0.identifier == "dulcet.mini-player.play-pause" }
                if bars.count == 1, controls.count == 1,
                   bars[0].label.contains(track), controls[0].isEnabled,
                   controls[0].label == (playing ? "Pause" : "Play") {
                    return true
                }
            }
            RunLoop.current.run(until: Date().addingTimeInterval(0.2))
        } while ProcessInfo.processInfo.systemUptime < deadline
        return false
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
              let bar = startPlayingTheAlbumsLastTrack(album: album, last: albumOrder[2], tile: tile(), in: app, compact: compact),
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
              let bar = startPlayingTheAlbumsLastTrack(album: album, last: albumOrder[2], tile: tile(), in: app, compact: true),
              openDestination("Search", sidebarIdentifier: "dulcet.sidebar.search", in: app, compact: true) else { return }
        let field = dulcetSearchField(in: app)
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

    /// The shared section helper returns through native Back until Library exposes Playlists.
    /// A second pop loop can race the root's arrival and try to tap a disappearing Back button.
    @MainActor
    private func openLibraryPlaylistsFromAnywhere(in app: XCUIApplication, compact: Bool) -> Bool {
        openLibraryPlaylists(in: app, compact: compact)
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
        var labels: [String]?
        // A hosted open/read/dismiss cycle took the entire settling window and produced only
        // one sample. Keep the player open while comparing actual queue snapshots, then close
        // it once. The 45-second stability window starts with its first positively read list.
        withoutIdleWaits(app) {
            labels = self.readUpNextLabels(openingFrom: bar, in: app, compact: compact, requireStable: true)
        }
        return labels
    }

    /// Up Next's entries, read from the player opened from the bar, which is then closed again.
    /// A regular-width window shows Up Next beside the player and has no toggle for it.
    @MainActor
    private func upNextLabels(openingFrom bar: XCUIElement, in app: XCUIApplication, compact: Bool = true) -> [String]? {
        var labels: [String]?
        // The player may still carry the drag/menu animation counted by XCTest after Close.
        // Read and dismiss by their explicit states, keeping the live uncovered-bar assertion.
        withoutIdleWaits(app) {
            labels = self.readUpNextLabels(openingFrom: bar, in: app, compact: compact)
        }
        return labels
    }

    @MainActor
    private func readUpNextLabels(
        openingFrom bar: XCUIElement, in app: XCUIApplication, compact: Bool, requireStable: Bool = false
    ) -> [String]? {
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
        // Empty is a positively rendered state. Waiting for a row that cannot exist
        // makes XCTest retry a missing-element snapshot even when this list is ready.
        let contents = app.descendants(matching: .any).matching(NSPredicate(
            format: "identifier == %@ OR identifier BEGINSWITH %@",
            "dulcet.upNext.empty", "dulcet.upNext.row."
        )).firstMatch
        // Read through snapshots, which throw when a row has gone, instead of recording a failure
        // as a bare `.label` does: the queue can change between counting its rows and reading them
        // (OBSERVED: a restored queue replaced under the read, "No matches found for Element at
        // index 1"), and a read that raced is taken again.
        guard var labels = currentUpNextLabels(rows, contents: contents, in: app) else { return nil }
        if requireStable {
            let deadline = Date().addingTimeInterval(45)
            var stable = false
            print("DULCET UP NEXT stability sample=\(labels)")
            repeat {
                RunLoop.current.run(until: Date().addingTimeInterval(1.5))
                guard let next = currentUpNextLabels(rows, contents: contents, in: app) else { return nil }
                print("DULCET UP NEXT stability sample=\(next)")
                if next == labels {
                    stable = true
                    break
                }
                labels = next
            } while Date() < deadline
            guard stable else {
                XCTFail("Up Next must settle before a drag is counted; it last read \(labels)")
                return nil
            }
        }
        let close = app.buttons["dulcet.now-playing.close"].firstMatch
        if close.waitForExistence(timeout: 5) { close.tap() } else { app.swipeDown() }
        // The bar remains in the accessibility tree beneath the full-screen player on iPad.
        // Its existence alone let a read return while the player still obscured the drag source.
        // A missing-element query alone has taken 9.25 s on CI. Polling it together
        // with a second accessibility query exhausted one deadline despite a dismissed
        // player and visible bar. Wait for each required state in its own bounded phase.
        guard close.waitForNonExistence(timeout: 10) else {
            XCTFail("Closing the player must dismiss it: " + app.debugDescription)
            return nil
        }
        let uncovered = NSPredicate(format: "hittable == true")
        guard XCTWaiter.wait(for: [XCTNSPredicateExpectation(predicate: uncovered, object: bar)], timeout: 10) == .completed else {
            XCTFail("Closing the player must dismiss it and uncover the bar: " + app.debugDescription)
            return nil
        }
        print("DULCET UP NEXT read=\(labels)")
        return labels
    }

    @MainActor
    private func currentUpNextLabels(
        _ rows: XCUIElementQuery, contents: XCUIElement, in app: XCUIApplication
    ) -> [String]? {
        // Require a rendered empty state or row for every sample, including the second
        // stability read. A missing list must never count as an agreeing empty queue.
        guard contents.waitForExistence(timeout: 10) else {
            XCTFail("Up Next must render its empty state or queued rows: " + app.debugDescription)
            return nil
        }
        for attempt in 1...4 {
            if let labels = try? rows.allElementsBoundByIndex.map({ try $0.snapshot().label }) {
                return labels
            }
            guard attempt < 4 else { break }
            RunLoop.current.run(until: Date().addingTimeInterval(1))
        }
        XCTFail("Up Next's rows kept changing under the read: " + app.debugDescription)
        return nil
    }

    /// Drags `source` onto the now-playing bar until Up Next holds `count` more entries than
    /// `before`, at most twice (one local run in ten of the track proof lifted without following
    /// the synthesized touch). A drag that never lifted leaves the menu it opened under the touch,
    /// which `menuMarker` names; it is closed so Up Next can be read, and counted. A drag that
    /// dropped queues only after its deferred read, which took over 10 s on a loaded CI host, so
    /// each drag gets 30 s before the next: a retry sooner queues the source twice. An optional
    /// preparation must positively establish its source before every attempt. A no-drag-source
    /// control rejects menu side effects; the historical custom-preview control is intermittent
    /// for albums (TRAPS 49), so its result alone does not establish the menu condition.
    @MainActor
    private func dragOntoTheBar(
        _ source: XCUIElement,
        bar: XCUIElement,
        before: [String],
        adding count: Int,
        menuMarker: String,
        in app: XCUIApplication,
        compact: Bool,
        prepareSource: ((Int) -> XCUIElement?)? = nil
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
            let dragSource: XCUIElement
            let targetFrame: CGRect?
            if let prepareSource {
                // Capture a real, hittable target before the menu shields the background.
                // XCTest can reject an element-to-element drag before sending any touch
                // when it hit-tests the covered bar (hosted iPhone activities, 2026-10-18).
                let ready = XCTNSPredicateExpectation(predicate: NSPredicate { _, _ in
                    bar.exists && self.playerElementIsHittable(bar)
                }, object: nil)
                guard XCTWaiter.wait(for: [ready], timeout: 10) == .completed else {
                    XCTFail("The bar must be hittable before preparing each native-menu drag")
                    return nil
                }
                let frame = bar.frame
                guard !frame.isEmpty, !frame.isInfinite, !frame.isNull,
                      app.frame.contains(frame) else {
                    XCTFail("The prepared drag requires the bar's visible, finite frame: \(frame)")
                    return nil
                }
                targetFrame = frame
                guard let prepared = prepareSource(drags + 1) else { return nil }
                dragSource = prepared
            } else {
                targetFrame = nil
                dragSource = source
            }
            drags += 1
            if let targetFrame {
                let window = app.frame
                let preview = dragSource.frame
                let startPoint = CGPoint(x: preview.midX, y: preview.midY)
                let endPoint = CGPoint(x: targetFrame.midX, y: targetFrame.midY)
                guard bar.exists, bar.frame == targetFrame,
                      !preview.isEmpty, !preview.isInfinite, !preview.isNull,
                      preview.contains(startPoint), window.contains(startPoint),
                      targetFrame.contains(endPoint), window.contains(endPoint) else {
                    XCTFail("Native-menu drag geometry must remain visible and stable: preview=\(preview) bar=\(bar.frame) captured=\(targetFrame) window=\(window)")
                    return nil
                }
                let origin = app.coordinate(withNormalizedOffset: .zero)
                let start = origin.withOffset(CGVector(dx: startPoint.x - window.minX, dy: startPoint.y - window.minY))
                let end = origin.withOffset(CGVector(dx: endPoint.x - window.minX, dy: endPoint.y - window.minY))
                print("DULCET PREPARED DRAG attempt=\(drags) start=\(startPoint) end=\(endPoint) preview=\(preview) bar=\(targetFrame)")
                withoutIdleWaits(app) {
                    start.press(forDuration: 0.8, thenDragTo: end, withVelocity: XCUIGestureVelocity(200), thenHoldForDuration: 1.0)
                }
            } else {
                withoutIdleWaits(app) {
                    dragSource.press(forDuration: 0.8, thenDragTo: bar, withVelocity: XCUIGestureVelocity(200), thenHoldForDuration: 1.0)
                }
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
    /// Shuffle leave it, and playback ran under the count). On iPhone the menu's column covers
    /// every fixed point, and tapping the first one anyway chose the album's Shuffle: the album
    /// drag proof then read Up Next as Shuffle leaves it and the bar as playing (run 40030838091).
    /// The middle of the wider gap beside the column is outside it; with no clear point the proof
    /// fails here rather than pressing a menu item.
    @MainActor
    private func closeContextMenu(marker menuMarker: String, openedFrom source: CGRect, in app: XCUIApplication) {
        let window = app.frame
        let item = app.buttons[menuMarker].firstMatch
        let column = item.exists ? item.frame.insetBy(dx: -24, dy: 0) : .null
        let lifted = source.insetBy(dx: -24, dy: -24)
        var across: [CGFloat] = [0.92, 0.6, 0.4]
        if !column.isNull {
            let left = column.minX - window.minX, right = window.maxX - column.maxX
            let gap = left > right ? window.minX + left / 2 : column.maxX + right / 2
            across.append((gap - window.minX) / window.width)
        }
        let candidates = across.flatMap { dx in [CGVector(dx: dx, dy: 0.25), CGVector(dx: dx, dy: 0.6)] }
        let clear = candidates.first { offset in
            let point = CGPoint(x: window.minX + window.width * offset.dx, y: window.minY + window.height * offset.dy)
            return (column.isNull || point.x < column.minX || point.x > column.maxX) && !lifted.contains(point)
        }
        guard let clear else {
            XCTFail("No point beside the open menu (\(menuMarker)) is clear of it and of its source: " + app.debugDescription)
            return
        }
        app.coordinate(withNormalizedOffset: clear).tap()
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
              openAlphabeticalAlbumGrid(in: app, compact: compact) else { return }
        let album = "Threshold Boundary"
        let albumOrder = ["Twenty Nine Seconds", "Thirty One Seconds", "UI Playback Canary"]
        let tile = { app.buttons.matching(identifier: "dulcet.library.album")
            .matching(NSPredicate(format: "label BEGINSWITH %@", album)).firstMatch }
        guard tile().waitForExistence(timeout: 30), scrollIntoView(tile(), in: app) else {
            XCTFail("Library must show \(album): " + app.debugDescription)
            return
        }
        guard let bar = startPlayingTheAlbumsLastTrack(album: album, last: albumOrder[2], tile: tile(), in: app, compact: compact) else { return }
        // The menu is proved in the process that drags, after the helper's relaunch, as the
        // playlist row proof does. Proved only before the relaunch, the drag was that process's
        // first long press, and on iPad it opened the menu without lifting the tile (run
        // 39991469062, both attempts: drags=2 menu-left-open-after=1).
        guard openAlphabeticalAlbumGrid(in: app, compact: compact),
              tile().waitForExistence(timeout: 30), scrollIntoView(tile(), in: app),
              requireContextMenu(on: tile(), marker: "Add to Playlist\u{2026}", in: app),
              let before = awaitSettledUpNext(openingFrom: bar, in: app, compact: compact) else { return }
        var nativeMenuReady = 0
        let prepareSource: ((Int) -> XCUIElement?)? = compact ? { attempt in
            guard let preview = self.nativeAlbumMenuPreview(on: tile(), album: album, attempt: attempt, in: app) else { return nil }
            nativeMenuReady += 1
            return preview
        } : nil
        guard let result = dragOntoTheBar(
            tile(), bar: bar, before: before, adding: albumOrder.count, menuMarker: "Add to Playlist\u{2026}",
            in: app, compact: compact, prepareSource: prepareSource
        ) else { return }
        if compact {
            XCTAssertGreaterThan(nativeMenuReady, 0, "The phone must actually reach its native album menu")
            XCTAssertEqual(nativeMenuReady, result.drags, "Every attempt, including a retry, must start from its positively observed native menu preview")
        }
        attachScreenshot(named: "album-tile-dragged-onto-the-bar", app: app)
        let added = Array(result.after.dropFirst(before.count))
        XCTAssertEqual(result.after.count, before.count + albumOrder.count,
                       "The dragged album must join Up Next once; before=\(before) after=\(result.after)"
                       + " drags=\(result.drags) menu-left-open-after=\(result.menuLeftOpen)")
        XCTAssertEqual(result.menuLeftOpen, 0,
                       "A completed album drag must dismiss its menu; menu side effects cannot substitute for a queue drop")
        XCTAssertEqual(added.count, albumOrder.count)
        for (label, title) in zip(added, albumOrder) {
            XCTAssertTrue(label.hasPrefix(title), "Album order at the end of Up Next: \(title) expected, got \(label)")
        }
        print("DULCET ALBUM TILE DRAG before=\(before) after=\(result.after) drags=\(result.drags)"
              + " menu-left-open-after=\(result.menuLeftOpen) native-menu-ready=\(nativeMenuReady)")
    }

    /// The phone lifts the actual system Preview with a second touch after its native menu
    /// opens. This positively covers an already-open menu; the uninterrupted long-press path
    /// has a separate retained recording. The covered library button is not a hit target.
    @MainActor
    private func nativeAlbumMenuPreview(on tile: XCUIElement, album: String, attempt: Int, in app: XCUIApplication) -> XCUIElement? {
        guard tile.waitForExistence(timeout: 5), tile.isHittable else {
            XCTFail("The named album must be hittable before opening its menu")
            return nil
        }
        withoutIdleWaits(app) { tile.press(forDuration: 1.2) }
        let marker = app.buttons["Add to Playlist\u{2026}"].firstMatch
        let albumPin = app.buttons.matching(NSPredicate(format: "label == %@ OR label == %@", "Pin \(album)", "Unpin \(album)")).firstMatch
        let previews = app.otherElements.matching(NSPredicate(format: "label == %@", "Preview"))
        let preview = previews.element(boundBy: 0)
        guard marker.waitForExistence(timeout: 5), marker.isHittable,
              albumPin.exists, albumPin.isHittable,
              preview.waitForExistence(timeout: 5), previews.count == 1, preview.isHittable,
              !marker.frame.intersects(preview.frame) else {
            XCTFail("The named album's native menu and separate hittable Preview must be open before every drag: " + app.debugDescription)
            return nil
        }
        print("DULCET ALBUM MENU READY attempt=\(attempt) album=\(album) preview=\(preview.frame) marker=\(marker.frame)")
        return preview
    }

    /// Use the phone's full Albums collection in Title order rather than depending on fixture
    /// creation timestamps in Recently Added. Retain the centered gesture and menu preflight;
    /// a no-drag-source control must fail its queue-order and paused-playback assertions.
    @MainActor
    private func openAlphabeticalAlbumGrid(in app: XCUIApplication, compact: Bool) -> Bool {
        guard openLibraryDestinationForAlbums(in: app, compact: compact) else { return false }
        guard compact else { return true }
        let options = app.buttons["dulcet.library.albums.options"].firstMatch
        guard options.waitForExistence(timeout: 10), options.isHittable else {
            XCTFail("The Albums collection must expose its toolbar menu")
            return false
        }
        options.tap()
        let sort = app.buttons["dulcet.library.albums.sort"].firstMatch
        guard sort.waitForExistence(timeout: 10) else {
            XCTFail("The Albums collection must expose its sort control")
            return false
        }
        sort.tap()
        let title = app.buttons["Title"].firstMatch
        guard title.waitForExistence(timeout: 5) else {
            XCTFail("The Albums sort menu must offer Title")
            return false
        }
        title.tap()
        // A selected Picker action can leave its parent menu open. Only send an
        // outside tap while that menu's own action is still visible.
        if sort.exists && sort.isHittable {
            app.coordinate(withNormalizedOffset: CGVector(dx: 0.05, dy: 0.12)).tap()
        }
        let closed = XCTNSPredicateExpectation(predicate: NSPredicate { _, _ in
            !sort.exists || !sort.isHittable
        }, object: nil)
        guard XCTWaiter.wait(for: [closed], timeout: 10) == .completed else {
            XCTFail("The Albums toolbar menu must close before the drag proof")
            return false
        }
        return true
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
        guard let app = launchConnected(serverURL: proxy.url, configuration: configuration, compact: expectedCompact) else { return }
        // An earlier drag-source menu can leave XCTest counting an animation after it closes.
        // Hosted run 38488956473 spent twelve 60 s idle waits here before its 900 s allowance
        // expired. Keep this proof's events in the existing interaction scope; every screen
        // transition and server write still has its explicit assertions below.
        withoutIdleWaits(app) { [self] in
            guard openLibraryPlaylists(in: app, compact: expectedCompact) else { return }

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

            // 9. A failed edit the page already knows. Prepare the prompt before starting the
            // held read: a hosted AX query can outlast the production client's 30 s deadline.
            // Returning to the foreground reconnects the real reader and revalidates its page.
            guard openLibraryPlaylists(in: app, compact: expectedCompact), openPlaylistRow(known, in: app) else { return }
            let knownEdit = app.buttons["dulcet.playlist.edit"].firstMatch
            guard knownEdit.waitForExistence(timeout: 10), waitForEnabled(knownEdit, timeout: 30) else {
                XCTFail("The initial playlist read must finish before preparing its foreground reread")
                return
            }
            guard openRenamePrompt(in: app) else { return }
            XCUIDevice.shared.press(.home)
            guard app.wait(for: .runningBackground, timeout: 10) else {
                XCTFail("The app must be backgrounded before arming its foreground reread")
                return
            }
            guard let armed = proxyPlaylistHold(knownID, on: true, proxy: proxy), armed.holding else {
                XCTFail("The fault proxy must hold the playlist's read")
                return
            }
            guard restCall("deletePlaylist", [URLQueryItem(name: "id", value: knownID)], configuration: configuration) != nil,
                  awaitServerPlaylistAbsent(knownID, configuration: configuration) else {
                XCTFail("The other client's delete must reach the server before the held read")
                return
            }
            let rereadStart = ContinuousClock.now
            app.activate()
            // No AX query between this request's arrival and release. Proxy counts alone do
            // not prove the client consumed an answer; the unavailable page below must do so.
            guard let heldRead = awaitPlaylistObservations(knownID, proxy: proxy, timeout: 15, until: { $0.isPending }) else {
                XCTFail("The foreground page reread must arrive and remain held until explicit release")
                return
            }
            guard proxyPlaylistHold(knownID, on: false, proxy: proxy) != nil,
                  let releasedRead = awaitPlaylistObservations(knownID, proxy: proxy, timeout: 10, until: {
                      !$0.holding && $0.held > 0 && $0.released == $0.held && $0.timedOut == 0
                  }) else {
                XCTFail("Every held read must reach the server by explicit release, with none timing out")
                return
            }
            print("DULCET PLAYLIST known-gone foreground-to-release=\(rereadStart.duration(to: .now)) held=\(heldRead.held) released=\(releasedRead.released) timed-out=\(releasedRead.timedOut)")
            // The page says the playlist is gone before the rename is confirmed: the order is met,
            // not assumed.
            let statement = app.descendants(matching: .any)["dulcet.reader.unavailable"].firstMatch
            XCTAssertTrue(statement.waitForExistence(timeout: 20),
                "The released read must tell the page the playlist is gone, under the open prompt: " + app.debugDescription)
            let prompt = app.alerts.matching(identifier: "Rename").element(boundBy: 0)
            guard prompt.waitForExistence(timeout: 5), prompt.textFields.element(boundBy: 0).exists else {
                XCTFail("The original Rename prompt must remain open after the page learns the playlist is gone")
                return
            }
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
    }

    /// The full server Albums collection has the same route on every window width.
    @MainActor
    private func openLibraryDestinationForAlbums(in app: XCUIApplication, compact: Bool) -> Bool {
        openSidebarLibrarySection("albums", in: app)
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
        let more = app.buttons.matching(identifier: "dulcet.playlist.more").element(boundBy: 0)
        guard more.waitForExistence(timeout: 10) else {
            XCTFail("The person's own playlist must offer its menu: " + app.debugDescription)
            return false
        }
        more.tap()
        // Resolve the menu action itself, with an indexed query. A broad label firstMatch
        // spent 25 seconds resolving this action in run 39728604012, consuming the held read.
        let rename = app.buttons.matching(identifier: "dulcet.playlist.rename").element(boundBy: 0)
        guard waitForStableButton(rename, identifier: "dulcet.playlist.rename", in: app, timeout: 5) else {
            XCTFail("The menu must offer a settled Rename action: " + app.debugDescription)
            return false
        }
        rename.tap()
        let prompt = app.alerts.matching(identifier: "Rename").element(boundBy: 0)
        guard prompt.waitForExistence(timeout: 5), prompt.textFields.element(boundBy: 0).exists else {
            XCTFail("Rename… must open its prompt: " + app.debugDescription)
            return false
        }
        return true
    }

    /// Flatten one immutable observation; querying each descendant separately can span a
    /// presentation handoff and combine elements which were never on screen together.
    private func snapshotElements(in root: XCUIElementSnapshot) -> [XCUIElementSnapshot] {
        var pending = [root]
        var elements: [XCUIElementSnapshot] = []
        while let element = pending.popLast() {
            elements.append(element)
            pending.append(contentsOf: element.children.reversed())
        }
        return elements
    }

    @MainActor
    private func waitForStableButton(
        _ button: XCUIElement, identifier: String, in app: XCUIApplication, timeout: TimeInterval
    ) -> Bool {
        let deadline = ProcessInfo.processInfo.systemUptime + timeout
        var previous: CGRect?
        var stableSince = ProcessInfo.processInfo.systemUptime
        repeat {
            if let window = try? app.windows.element(boundBy: 0).snapshot() {
                let matches = snapshotElements(in: window).filter {
                    $0.elementType == .button && $0.identifier == identifier && $0.isEnabled
                        && !$0.frame.isEmpty && window.frame.contains($0.frame)
                }
                let now = ProcessInfo.processInfo.systemUptime
                if matches.count == 1, let match = matches.first {
                    if match.frame != previous {
                        previous = match.frame
                        stableSince = now
                    } else if now - stableSince >= 0.4, button.isHittable {
                        return true
                    }
                } else {
                    previous = nil
                    stableSince = now
                }
            }
            RunLoop.current.run(until: Date().addingTimeInterval(0.2))
        } while ProcessInfo.processInfo.systemUptime < deadline
        return false
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
        app.launchArguments += liveAccountLaunchArguments(
            serverURL: configuration.serverURL, configuration: configuration)
        app.launch()
        let window = app.windows.firstMatch
        XCTAssertTrue(window.waitForExistence(timeout: 10), "The app window must exist")
        guard let width = settledWindowWidth(window) else { return }
        let compact = width < 700
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

    /// Tapping a synced line seeks the track to the line's start through the player's control
    /// path (spec §18.4). The proof pauses first: with media time frozen, the target line cannot
    /// light on its own, so it lighting after the tap is the seek's own work. The tap goes in
    /// whichever direction the track has not reached, so a slow connection cannot stale it.
    @MainActor
    func testASyncedLineTapSeeksToItsStart() {
        guard let configuration = livePlaybackConfiguration() else { return }
        let album = "Threshold Boundary"
        let track = "Twenty Nine Seconds"
        let app = XCUIApplication()
        app.launchArguments += liveAccountLaunchArguments(
            serverURL: configuration.serverURL, configuration: configuration)
        app.launch()
        let window = app.windows.firstMatch
        XCTAssertTrue(window.waitForExistence(timeout: 10), "The app window must exist")
        guard let width = settledWindowWidth(window) else { return }
        let compact = width < 700
        guard awaitLiveAccountConnection(in: app, compact: compact) else {
            XCTFail("The live account connection must succeed first")
            return
        }
        guard openLibraryAlbum(album, in: app, compact: compact) else { return }
        guard tapAlbumPlay(in: app) else { return }
        guard openNowPlayingFromBar(in: app, expectingTitle: track),
              openLyricsPanel(in: app) != nil else { return }
        let current = app.staticTexts["dulcet.lyrics.line.current"].firstMatch
        XCTAssertTrue(current.waitForExistence(timeout: 25),
            "A line must light as the track plays: " + app.debugDescription)

        // Pause, so media time cannot light the other line on its own. Buttons by label are
        // filtered to the one really on screen: the bar and the player both carry a play/pause,
        // and an offscreen match has no frame to tap (OBSERVED: kAXErrorCannotComplete).
        func playerButton(_ label: String) -> XCUIElement? {
            visiblePlayerButton(label, in: app)
        }
        let pauseDeadline = Date().addingTimeInterval(15)
        var pause = playerButton("Pause")
        while pause == nil, Date() < pauseDeadline {
            Thread.sleep(forTimeInterval: 0.25)
            pause = playerButton("Pause")
        }
        XCTAssertNotNil(pause, "Now Playing must offer Pause: " + app.debugDescription)
        pause?.tap()
        let playDeadline = Date().addingTimeInterval(10)
        while playerButton("Play") == nil, Date() < playDeadline {
            Thread.sleep(forTimeInterval: 0.25)
        }
        XCTAssertNotNil(playerButton("Play"), "The track must pause before the tap: " + app.debugDescription)

        let litBefore = current.exists ? current.label : "<none>"
        XCTAssertTrue(litBefore.hasPrefix("Dulcet English line"), "A line must be lit before the tap; lit=\(litBefore)")
        let target = litBefore.hasSuffix("one") ? "Dulcet English line two" : "Dulcet English line one"
        let targetLine = app.staticTexts.matching(NSPredicate(format: "label == %@", target)).firstMatch
        XCTAssertTrue(targetLine.waitForExistence(timeout: 10) && targetLine.isHittable,
            "The line tapped must be on screen: " + app.debugDescription)
        targetLine.tap()
        // The lit-line identifier moves between elements as the seek lands, so a poll that reads
        // the label only when a current line exists never samples the transition (OBSERVED: an
        // unguarded read fails the snapshot with zero matches mid-move).
        let litDeadline = Date().addingTimeInterval(5)
        var litAfter = false
        while !litAfter, Date() < litDeadline {
            litAfter = current.exists && current.label == target
            if !litAfter { Thread.sleep(forTimeInterval: 0.1) }
        }
        XCTAssertTrue(litAfter,
            "Tapping a line must seek to its start: paused, \(target.debugDescription) can light only by the seek; lit before=\(litBefore.debugDescription)")
        attachScreenshot(named: "lyrics-tap-to-seek", app: app)
    }

    /// A design instrument, not a proof: photographs the synced-lyrics panel's behaviours against
    /// the disposable server on "Thirty One Seconds" (synced sidecar: lines at 0.75 s and 5.25 s,
    /// a blank line at 9.75 s, the last at 20 s) -- the instrumental dots, the follow spring
    /// landing, the blank-line interlude, a hand scroll drawing every line plainly, the follow
    /// resuming, and a tap-to-seek. Runs only when `DULCET_LYRICS_PROBE=1` and
    /// `DULCET_LYRICS_PROBE_OUT` (the directory frames are written to) are set; any ordinary run
    /// returns at once, so this never photographs anything in the regular suite.
    @MainActor
    func testLyricsDesignProbe() {
        let environment = ProcessInfo.processInfo.environment
        guard environment["DULCET_LYRICS_PROBE"] == "1",
              let outDirectory = environment["DULCET_LYRICS_PROBE_OUT"], !outDirectory.isEmpty else { return }
        guard let configuration = livePlaybackConfiguration() else { return }
        var frameNumber = 0
        func frame(_ name: String, _ app: XCUIApplication) {
            frameNumber += 1
            let file = String(format: "%02d-%@.png", frameNumber, name)
            let screenshot = app.screenshot()
            try? screenshot.pngRepresentation.write(to: URL(fileURLWithPath: outDirectory).appendingPathComponent(file))
            let attachment = XCTAttachment(screenshot: screenshot)
            attachment.name = name
            attachment.lifetime = .keepAlways
            add(attachment)
            print("DULCET LYRICS PROBE frame=\(file)")
        }
        func poll(_ timeout: TimeInterval, _ condition: () -> Bool) -> Bool {
            let deadline = Date().addingTimeInterval(timeout)
            while !condition(), Date() < deadline { RunLoop.current.run(until: Date().addingTimeInterval(0.1)) }
            return condition()
        }

        let app = XCUIApplication()
        app.launchArguments += liveAccountLaunchArguments(
            serverURL: configuration.serverURL, configuration: configuration)
        app.launch()
        let window = app.windows.firstMatch
        XCTAssertTrue(window.waitForExistence(timeout: 10), "The app window must exist")
        guard let width = settledWindowWidth(window) else { return }
        let compact = width < 700
        guard awaitLiveAccountConnection(in: app, compact: compact) else {
            XCTFail("The live account connection must succeed first")
            return
        }

        // `sheet` mode photographs a longer lyric sheet (a track named by the caller) during
        // playback: the follow at work, the distance falloff, and a hand scroll. The corpus's own
        // lyric tracks are four lines at most, so a full sheet needs a track the caller added.
        if environment["DULCET_LYRICS_PROBE_MODE"] == "sheet",
           let sheetAlbum = environment["DULCET_LYRICS_PROBE_ALBUM"], !sheetAlbum.isEmpty {
            guard openLibraryAlbum(sheetAlbum, in: app, compact: compact) else { return }
            guard tapAlbumPlay(in: app) else { return }
            guard openNowPlayingFromBar(in: app, expectingTitle: nil),
                  openLyricsPanel(in: app) != nil else { return }
            let current = app.staticTexts["dulcet.lyrics.line.current"].firstMatch
            XCTAssertTrue(current.waitForExistence(timeout: 25), "A line must light: " + app.debugDescription)
            frame("sheet-following-1", app)
            RunLoop.current.run(until: Date().addingTimeInterval(1.2))
            frame("sheet-following-2", app)
            RunLoop.current.run(until: Date().addingTimeInterval(1.2))
            frame("sheet-following-3", app)
            let panel = app.descendants(matching: .any)["dulcet.lyrics.panel"].firstMatch
            panel.swipeUp()
            RunLoop.current.run(until: Date().addingTimeInterval(0.6))
            frame("sheet-hand-scroll", app)
            RunLoop.current.run(until: Date().addingTimeInterval(4))
            frame("sheet-resumed", app)
            print("DULCET LYRICS PROBE SHEET PASS frames=\(frameNumber)")
            return
        }

        // Search plays exactly the sidecar-lyrics track, so the queue holds it alone, and pause
        // at once, so the early lines are still ahead: every later frame is taken at a chosen
        // media time, not wherever launch landed. Buttons by label are re-queried and filtered to
        // the one really on screen: the bar and the player both carry a play/pause, and an
        // offscreen match has no frame to tap (OBSERVED: kAXErrorCannotComplete, frame {{inf,inf}}).
        guard playFromSearch("Thirty One Seconds", in: app, compact: compact) else { return }
        guard openNowPlayingFromBar(in: app, expectingTitle: "Thirty One Seconds") else { return }
        func playerButton(_ label: String) -> XCUIElement? {
            visiblePlayerButton(label, in: app)
        }
        let pause = playerButton("Pause")
        XCTAssertNotNil(pause, "Now Playing must offer Pause: " + app.debugDescription)
        pause?.tap()
        let playDeadline = Date().addingTimeInterval(10)
        while playerButton("Play") == nil, Date() < playDeadline {
            Thread.sleep(forTimeInterval: 0.25)
        }
        guard playerButton("Play") != nil else {
            XCTFail("The track must pause before the frames: " + app.debugDescription)
            return
        }
        guard openLyricsPanel(in: app) != nil else { return }
        let current = app.staticTexts["dulcet.lyrics.line.current"].firstMatch
        let dots = app.descendants(matching: .any)["dulcet.lyrics.instrumental"].firstMatch
        frame("01-open-paused", app)

        // Seek onto line one (0.75 s) with a tap: the line lights and, the next line being 4.5 s
        // away, the instrumental dots stand where it will appear.
        let lineOne = app.staticTexts.matching(NSPredicate(format: "label == %@", "Dulcet synced line one")).firstMatch
        XCTAssertTrue(lineOne.waitForExistence(timeout: 10) && lineOne.isHittable, "Line one must be on screen: " + app.debugDescription)
        lineOne.tap()
        XCTAssertTrue(poll(5, { current.exists && current.label == "Dulcet synced line one" }), "The tap must seek to line one")
        XCTAssertTrue(dots.waitForExistence(timeout: 5), "The instrumental dots must show in the 4.5 s gap: " + app.debugDescription)
        frame("02-gap-dots", app)

        // Resume and catch the follow spring as line two lights at 5.25 s.
        playerButton("Play")?.tap()
        guard poll(8, { current.exists && current.label == "Dulcet synced line two" }) else {
            XCTFail("Line two must light at 5.25 s: " + app.debugDescription)
            return
        }
        for index in 1...6 {
            frame(String(format: "03-follow-%02d", index), app)
            RunLoop.current.run(until: Date().addingTimeInterval(0.35))
        }

        // The blank line at 9.75 s is the interlude: nothing lit, the line drawn as an ellipsis.
        guard poll(10, { !current.exists }) else {
            XCTFail("The blank line must begin an interlude (nothing lit): " + app.debugDescription)
            return
        }
        frame("04-interlude", app)

        // Line four at 20 s, then a hand scroll: every line drawn plainly, follow paused.
        guard poll(15, { current.exists && current.label == "Dulcet synced line four" }) else {
            XCTFail("Line four must light at 20 s: " + app.debugDescription)
            return
        }
        frame("05-line-four", app)
        let panel = app.descendants(matching: .any)["dulcet.lyrics.panel"].firstMatch
        panel.swipeUp()
        frame("06-hand-scroll", app)
        RunLoop.current.run(until: Date().addingTimeInterval(4))
        frame("07-follow-resumed", app)

        // A tap on line one seeks back to 0.75 s and the follow resumes at once.
        XCTAssertTrue(lineOne.exists && lineOne.isHittable, "Line one must be tappable after the scroll: " + app.debugDescription)
        lineOne.tap()
        XCTAssertTrue(poll(5, { current.exists && current.label == "Dulcet synced line one" }), "The tap must seek back to line one")
        frame("08-tap-to-seek", app)
        print("DULCET LYRICS PROBE PASS frames=\(frameNumber)")
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

        var originalAutoplay: Bool?
        defer {
            if let originalAutoplay {
                XCTAssertTrue(chooseAutoplayForLyrics(originalAutoplay, in: XCUIApplication(), holdPlayback: true),
                              "Restore the person's AutoPlay choice after the lyrics proof")
            }
        }

        // 1. Synced: a line lights as media time moves.
        guard let syncedApp = launchConnected(serverURL: configuration.serverURL, configuration: configuration,
                                              compact: expectedCompact),
              openLibraryAlbum(album, in: syncedApp, compact: expectedCompact) else { return }
        guard tapAlbumPlay(in: syncedApp) else { return }
        // These states include a one-second track and a deliberately slow failed read. Choose
        // an ending queue explicitly: one search result no longer implies that with AutoPlay on.
        // Pause during setup so navigating Up Next cannot consume the 29-second synced fixture.
        let syncedTransport = syncedApp.buttons["dulcet.mini-player.play-pause"].firstMatch
        guard syncedTransport.waitForExistence(timeout: 10),
              waitForLabel("Pause", of: syncedTransport, timeout: 15) else {
            XCTFail("The synced fixture must actually start before its setup pause")
            return
        }
        syncedTransport.tap()
        guard waitForLabel("Play", of: syncedTransport, timeout: 10) else {
            XCTFail("The setup pause must reach playback before navigating Up Next")
            return
        }
        guard openNowPlayingFromBar(in: syncedApp, expectingTitle: "Twenty Nine Seconds"),
              chooseAutoplayForLyrics(false, in: syncedApp, capturePrevious: { originalAutoplay = $0 }) else { return }
        syncedTransport.tap()
        guard waitForLabel("Pause", of: syncedTransport, timeout: 10) else {
            XCTFail("The synced fixture must resume after the paused AutoPlay setup")
            return
        }
        guard openNowPlayingFromBar(in: syncedApp, expectingTitle: "Twenty Nine Seconds"),
              let syncedPanel = openLyricsPanel(in: syncedApp) else { return }
        let current = syncedApp.staticTexts["dulcet.lyrics.line.current"].firstMatch
        XCTAssertTrue(current.waitForExistence(timeout: 25), "A synced line must light as the track plays: " + syncedApp.debugDescription)
        let lit = current.exists ? current.label : "<none>"
        XCTAssertTrue(lit.hasPrefix("Dulcet English line"), "The lit line must be the English layer's; lit=\(lit)")
        // The live highlight is proven above. Hold that track before the layout snapshots and
        // gesture: the 29-second fixture ended during coordinate resolution,
        // replacing the lyrics ScrollView with the next track's loading state.
        guard holdLyricsPlayback(in: syncedApp) else { return }
        let syncedTitleID = expectedCompact ? "dulcet.lyrics.header.title" : "dulcet.now-playing.title"
        let syncedTitle = syncedApp.staticTexts[syncedTitleID].firstMatch
        guard waitForLabel("Twenty Nine Seconds", of: syncedTitle, timeout: 5) else {
            XCTFail("The highlighted synced fixture must remain selected for the layout and scroll proof")
            return
        }
        var placement = "sheet"
        if !expectedCompact {
            // Beside the player: the panel starts right of the player's own title, in one row.
            let title = syncedApp.staticTexts["dulcet.now-playing.title"].firstMatch
            XCTAssertTrue(title.exists, "The player's title must be on screen beside the lyrics")
            // Every .frame read is a fresh snapshot, so the panel's arrival can move it between two
            // reads. Assert on one settled pair: the same frames on two reads in a row.
            var panelFrame = syncedPanel.frame
            var titleFrame = title.frame
            for _ in 0..<20 {
                RunLoop.current.run(until: Date().addingTimeInterval(0.25))
                let (nextPanel, nextTitle) = (syncedPanel.frame, title.frame)
                if nextPanel == panelFrame, nextTitle == titleFrame { break }
                (panelFrame, titleFrame) = (nextPanel, nextTitle)
            }
            placement = "panel=\(panelFrame) title=\(titleFrame)"
            XCTAssertGreaterThanOrEqual(panelFrame.minX, titleFrame.maxX,
                "On a regular width the lyrics must sit beside the player, not over or under it; \(placement)")
            XCTAssertTrue(panelFrame.minY < titleFrame.maxY && titleFrame.minY < panelFrame.maxY,
                "The lyrics and the player must share a row; \(placement)")
        }
        attachScreenshot(named: "lyrics-synced", app: syncedApp)
        let window = syncedApp.windows.firstMatch
        let windowFrame = window.frame
        let panelFrame = syncedPanel.frame
        let geometry = [windowFrame.minX, windowFrame.minY, windowFrame.width, windowFrame.height,
                        panelFrame.minX, panelFrame.minY, panelFrame.width, panelFrame.height]
        let visiblePanel = panelFrame.intersection(windowFrame).insetBy(dx: 16, dy: 16)
        guard geometry.allSatisfy(\.isFinite), !visiblePanel.isEmpty, syncedPanel.isHittable,
              visiblePanel.height > 40 else {
            XCTFail("The lyrics drag needs a finite, visible panel; panel=\(panelFrame) window=\(windowFrame)")
            return
        }
        // Resolve the panel once, then anchor both endpoints to the stable window. XCUITest
        // otherwise re-resolves the changing lyrics element separately for each coordinate.
        let origin = window.coordinate(withNormalizedOffset: .zero)
        let lyricDrag = origin.withOffset(CGVector(dx: visiblePanel.midX - windowFrame.minX,
                                                   dy: visiblePanel.midY - windowFrame.minY))
        let lyricEnd = origin.withOffset(CGVector(dx: visiblePanel.midX - windowFrame.minX,
                                                  dy: min(visiblePanel.midY + 240, visiblePanel.maxY) - windowFrame.minY))
        lyricDrag.press(forDuration: 0.05, thenDragTo: lyricEnd,
                        withVelocity: .fast, thenHoldForDuration: 0)
        XCTAssertTrue(syncedPanel.exists && syncedApp.buttons["dulcet.now-playing.close"].firstMatch.isHittable,
                      "Scrolling lyrics must leave the player open and usable")

        // 2. Plain: both lines, and nothing lit.
        guard let plainApp = launchConnected(serverURL: configuration.serverURL, configuration: configuration,
                                             compact: expectedCompact),
              playFromSearch("Ogg Probe", in: plainApp, compact: expectedCompact) else { return }
        // A fresh phase has no Now Playing bar yet: activate its fixture before opening Up Next.
        // Read the existing choice before any change, so a lost preference still fails here.
        var persistedAutoplay: Bool?
        guard chooseAutoplayForLyrics(false, in: plainApp, capturePrevious: { persistedAutoplay = $0 }) else { return }
        XCTAssertEqual(persistedAutoplay, false, "The plain phase must inherit the explicitly disabled AutoPlay choice")
        guard persistedAutoplay == false else { return }
        // A one-second track, played from a search that matches it alone with AutoPlay proven
        // off: the finished queue keeps it as Now Playing's track (spec §14.3).
        guard openNowPlayingFromBar(in: plainApp, expectingTitle: "Ogg Probe"),
              openLyricsPanel(in: plainApp) != nil else { return }
        let plainLines = plainApp.staticTexts.matching(identifier: "dulcet.lyrics.line")
        for line in plainLayers[0].lines {
            XCTAssertTrue(plainLines.matching(NSPredicate(format: "label == %@", line)).firstMatch.waitForExistence(timeout: 20),
                "Plain lyrics must show \(line.debugDescription): " + plainApp.debugDescription)
        }
        XCTAssertFalse(plainApp.staticTexts["dulcet.lyrics.line.current"].firstMatch.exists,
            "Plain lyrics have no current line, so none may be lit")
        let plainTitleID = expectedCompact ? "dulcet.lyrics.header.title" : "dulcet.now-playing.title"
        XCTAssertEqual(plainApp.staticTexts[plainTitleID].firstMatch.label, "Ogg Probe",
                       "The single-result queue must retain its track while the plain lyrics are shown")
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
              // A failed read and its retry need static lyrics, not progressing playback.
              // Hold the named item before either wait can consume its remaining duration.
              holdLyricsPlayback(in: failedApp),
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
        var started: String?
        var failed: String?
        var lastValue = "none"
        var lastTitle = "none"
        var sampledSnapshots = 0
        let playDeadline = Date().addingTimeInterval(20)
        repeat {
            // Resolve the stable app once, then inspect one immutable tree. AutoPlay's short
            // tracks can replace an element while XCTest resolves its individual snapshot.
            // Missing/failed snapshots prove nothing and cannot count as playback evidence.
            if let root = try? app.snapshot() {
                sampledSnapshots += 1
                var sliderProgress: XCUIElementSnapshot?
                var barProgress: XCUIElementSnapshot?
                var title: XCUIElementSnapshot?
                var failure: XCUIElementSnapshot?
                var skipNotice: XCUIElementSnapshot?
                var pending: [XCUIElementSnapshot] = [root]
                while let element = pending.popLast() {
                    if sliderProgress == nil, element.elementType == .slider, element.label == "Now Playing" {
                        sliderProgress = element
                    }
                    if barProgress == nil, element.elementType == .progressIndicator, element.label == "Now Playing" {
                        barProgress = element
                    }
                    if title == nil, element.elementType == .staticText, element.identifier == "dulcet.now-playing.title" {
                        title = element
                    }
                    if failure == nil, element.identifier == "dulcet.now-playing.failure" { failure = element }
                    if skipNotice == nil, element.elementType == .staticText, element.label.contains("Skipped.") {
                        skipNotice = element
                    }
                    pending.append(contentsOf: element.children.reversed())
                }
                // Inspect the entire tree before accepting advancement: a title from a queue
                // that skipped a failed track must not bypass either negative check.
                if let failure { failed = "failure shown: \(failure.label)"; break }
                if let skipNotice { failed = "skip notice: \(skipNotice.label)"; break }
                if let value = (sliderProgress ?? barProgress)?.value as? String {
                    lastValue = value
                    if let sample = playbackProgressSample(from: value), sample.elapsed > 0 {
                        started = "media-time \(value)"
                    }
                }
                if let title {
                    lastTitle = title.label
                    if started == nil, !lastTitle.isEmpty, lastTitle != track {
                        started = "moved-past-probe to \(lastTitle)"
                    }
                }
            }
            RunLoop.current.run(until: Date().addingTimeInterval(0.25))
        } while Date() < playDeadline && started == nil
        XCTAssertNil(failed, "The capped play must not fail: \(failed ?? "")")
        XCTAssertNotNil(started, "The capped play must start: Now Playing's media time must move, or the queue"
            + " move past the probe; last progress \(lastValue), title \(lastTitle), snapshots \(sampledSnapshots)")
        print("DULCET QUALITY PLAYBACK SAMPLE snapshots=\(sampledSnapshots) title=\(lastTitle.debugDescription)"
            + " progress=\(lastValue.debugDescription) started=\(String(describing: started))")

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
        guard close.waitForExistence(timeout: 5), close.isHittable else {
            XCTFail("Now Playing must keep its close control reachable while the short queue advances: "
                + app.debugDescription)
            return
        }
        close.tap()
        guard close.waitForNonExistence(timeout: 5) else {
            XCTFail("Close Now Playing must dismiss the player before restoring streaming quality")
            return
        }
        guard openDestination("Connection", sidebarIdentifier: "dulcet.sidebar.settings", in: app, compact: expectedCompact),
              chooseStreamingQuality("Original", row: "dulcet.streaming-quality.unmetered", in: app),
              chooseStreamingQuality("Original", row: "dulcet.streaming-quality.metered", in: app) else { return }
        let summary = lines.map { "\($0["format"] ?? "?")@\($0["bitRate"] ?? "?")" }.joined(separator: ",")
        print("DULCET STREAMING QUALITY PROOF PASS destination=\(expectedCompact ? "compact" : "regular")"
            + " track=\(track.debugDescription) source-kbps=\(sourceKbps) server-streams=\(summary)"
            + " started=\(started ?? "nil")")
    }

    /// Unrelated proofs own separate credential stores; relaunches of one endpoint keep the
    /// same saved account. Explicit credential fixtures and killed-process handoffs own theirs.
    private func liveAccountFixtureArguments(serverURL: String, extraArguments: [String] = []) -> [String] {
        guard !extraArguments.contains("-dulcet-debug-account-fixture"),
              !extraArguments.contains("-dulcet-debug-download-handoff") else { return [] }
        let fixture = liveAccountFixtures[serverURL] ?? UUID().uuidString
        liveAccountFixtures[serverURL] = fixture
        return ["-dulcet-debug-account-fixture", fixture]
    }

    private func liveAccountLaunchArguments(
        serverURL: String,
        configuration: LivePlaybackConfiguration,
        extraArguments: [String] = []
    ) -> [String] {
        extraArguments + liveAccountFixtureArguments(serverURL: serverURL, extraArguments: extraArguments) + [
            "-dulcet-debug-connect-account",
            "-dulcet-debug-account-server-url", serverURL,
            "-dulcet-debug-account-username", configuration.username,
            "-dulcet-debug-account-password", configuration.password,
        ]
    }

    /// Launches the app with the account injected for `serverURL` and waits for its live
    /// connection, on the device class the caller names.
    @MainActor
    private func launchConnected(
        serverURL: String,
        configuration: LivePlaybackConfiguration,
        compact expectedCompact: Bool,
        extraArguments: [String] = []
    ) -> XCUIApplication? {
        let app = XCUIApplication()
        app.launchArguments += liveAccountLaunchArguments(
            serverURL: serverURL, configuration: configuration, extraArguments: extraArguments)
        app.launch()
        let window = app.windows.firstMatch
        guard window.waitForExistence(timeout: 10) else {
            XCTFail("The app window must exist")
            return nil
        }
        guard let width = settledWindowWidth(
            window, expecting: expectedCompact ? "compact" : "regular",
            matching: { ($0 < 700) == expectedCompact }
        ) else { return nil }
        let compact = width < 700
        guard compact == expectedCompact else {
            XCTFail("This proof needs a \(expectedCompact ? "compact" : "regular") window; observed width=\(width)")
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
        let field = dulcetSearchField(in: app)
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

    /// Chooses the production Up Next setting and reads its switch back. Lyrics states that
    /// outlive a short track need an ending queue; the original choice is restored by the caller.
    @MainActor
    private func chooseAutoplayForLyrics(
        _ enabled: Bool,
        in app: XCUIApplication,
        holdPlayback: Bool = false,
        capturePrevious: ((Bool) -> Void)? = nil
    ) -> Bool {
        // Restoring AutoPlay can append short tracks immediately. Hold the current item before
        // navigating to its switch: a natural end replaces the player with Preparing playback,
        // destroying the queue view while XCTest is still resolving the switch's new value.
        // The initial setup already pauses; cleanup needs the same observed precondition.
        if holdPlayback, !holdLyricsPlayback(in: app) { return false }
        let close = app.buttons["dulcet.now-playing.close"].firstMatch
        if !close.exists, !openNowPlayingFromBar(in: app, expectingTitle: nil) { return false }
        let lyricsPanel = app.descendants(matching: .any)["dulcet.lyrics.panel"].firstMatch
        if lyricsPanel.exists {
            let lyrics = app.buttons["dulcet.now-playing.lyrics"].firstMatch
            guard lyrics.exists && lyrics.isHittable else {
                XCTFail("The open lyrics panel must offer its own toggle")
                return false
            }
            lyrics.tap()
            guard lyricsPanel.waitForNonExistence(timeout: 5) else {
                XCTFail("Hide Lyrics must reveal the queue's place beside the regular player")
                return false
            }
        }
        let queueToggle = app.buttons["dulcet.now-playing.up-next"].firstMatch
        let queue = app.descendants(matching: .any)["dulcet.upNext"].firstMatch
        // The title and close control can enter AX before the compact player's toolbar.
        // Regular width already shows the queue inline; compact width needs its actual action.
        let queueControlReady = XCTNSPredicateExpectation(predicate: NSPredicate { _, _ in
            (queue.exists && queue.isHittable) || (queueToggle.exists && queueToggle.isHittable)
        }, object: nil)
        guard XCTWaiter.wait(for: [queueControlReady], timeout: 10) == .completed else {
            XCTFail("Now Playing must expose Up Next or its usable control before choosing AutoPlay: " + app.debugDescription)
            return false
        }
        if !queue.exists, queueToggle.label == "Show Up Next" { queueToggle.tap() }
        guard queue.waitForExistence(timeout: 10) else {
            XCTFail("Up Next must expose its actual list before choosing AutoPlay: " + app.debugDescription)
            return false
        }
        let toggle = app.switches["dulcet.upNext.autoplay.toggle"].firstMatch
        for _ in 0..<6 {
            if toggle.exists && toggle.isHittable { break }
            queue.swipeUp()
        }
        guard toggle.exists && toggle.isHittable,
              let previous = toggle.value as? String, ["0", "1"].contains(previous) else {
            XCTFail("Up Next must expose a readable AutoPlay switch: " + app.debugDescription)
            return false
        }
        capturePrevious?(previous == "1")
        let expected = enabled ? "1" : "0"
        if previous != expected {
            // The native Switch's AX frame includes its label and the gap before the thumb.
            // A center tap landed in that gap (x=201 in a 370-point row) and left it on.
            toggle.coordinate(withNormalizedOffset: CGVector(dx: 1, dy: 0.5))
                .withOffset(CGVector(dx: -24, dy: 0)).tap()
        }
        let chosen = XCTNSPredicateExpectation(predicate: NSPredicate(format: "value == %@", expected), object: toggle)
        guard XCTWaiter.wait(for: [chosen], timeout: 10) == .completed else {
            XCTFail("The real AutoPlay switch must acknowledge \(expected); value=\(String(describing: toggle.value))")
            return false
        }
        print("DULCET LYRICS AUTOPLAY previous=\(previous) value=\(toggle.value ?? "nil")")
        guard close.exists && close.isHittable else {
            XCTFail("The player must stay open while choosing AutoPlay")
            return false
        }
        close.tap()
        guard close.waitForNonExistence(timeout: 5) else {
            XCTFail("The player must close before returning to the lyrics fixture")
            return false
        }
        return true
    }

    @MainActor
    private func holdLyricsPlayback(in app: XCUIApplication) -> Bool {
        func visibleTransport(_ label: String) -> XCUIElement? {
            visiblePlayerButton(label, in: app)
        }
        if let pause = visibleTransport("Pause") { pause.tap() }
        let held = XCTNSPredicateExpectation(predicate: NSPredicate { _, _ in
            visibleTransport("Play") != nil
        }, object: nil)
        guard XCTWaiter.wait(for: [held], timeout: 10) == .completed else {
            XCTFail("The lyrics fixture must be paused before its static-state waits and AutoPlay restoration")
            return false
        }
        print("DULCET LYRICS HELD transport=Play")
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
        if !picker.exists {
            let playback = app.buttons["dulcet.account.playback"].firstMatch
            guard playback.waitForExistence(timeout: 10), playback.isHittable else {
                XCTFail("Account must link to Playback: " + app.debugDescription)
                return false
            }
            playback.tap()
        }
        let form = app.descendants(matching: .any)["dulcet.settings.playback"].firstMatch
        guard form.waitForExistence(timeout: 10) else {
            XCTFail("Connection must offer its Settings Form: " + app.debugDescription)
            return false
        }
        // A Form row can be hittable while its midpoint is beneath the translucent navigation
        // bar. Reveal the entire picker inside the content viewport before the real tap.
        var revealed = false
        for attempt in 0...6 {
            let navigationBar = app.navigationBars.firstMatch
            let tabBar = app.tabBars.firstMatch
            let top = (navigationBar.exists ? navigationBar.frame.maxY : form.frame.minY) + 16
            let bottom = (tabBar.exists ? tabBar.frame.minY : form.frame.maxY) - 16
            var above = false
            if picker.exists {
                let frame = picker.frame
                above = frame.minY < top
                if !frame.isEmpty, frame.minY >= top, frame.maxY <= bottom, picker.isHittable {
                    revealed = true
                    break
                }
            }
            guard attempt < 6 else { break }
            if picker.exists {
                form.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.5))
                    .press(forDuration: 0.05, thenDragTo:
                        form.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: above ? 0.75 : 0.25)))
            } else {
                // Native Form realizes each quality row when it enters the viewport.
                form.swipeUp()
            }
        }
        guard revealed, picker.waitForExistence(timeout: 10) else {
            XCTFail("Connection must offer the \(identifier) picker: " + app.debugDescription)
            return false
        }
        // Hosted queries in run 40058182881 took 1-4 seconds each. Give existence,
        // bounds and hittability enough time for several complete evaluations, then reopen
        // once if this presentation never supplies a usable activation target.
        let item = app.collectionViews.buttons.matching(NSPredicate(
            format: "label == %@ AND identifier != %@ AND identifier != %@", option,
            "dulcet.streaming-quality.unmetered", "dulcet.streaming-quality.metered"
        )).firstMatch
        var usable = false
        for attempt in 1...2 {
            picker.tap()
            let ready = XCTNSPredicateExpectation(predicate: NSPredicate { _, _ in
                guard item.exists else { return false }
                let frame = item.frame
                return frame.origin.x.isFinite && frame.origin.y.isFinite
                    && frame.width.isFinite && frame.height.isFinite
                    && frame.width > 0 && frame.height > 0
                    && app.windows.firstMatch.frame.contains(frame) && item.isHittable
            }, object: item)
            if XCTWaiter.wait(for: [ready], timeout: 30) == .completed {
                usable = true
                break
            }
            print("DULCET QUALITY MENU unusable option=\(option) attempt=\(attempt)")
            if attempt < 2 {
                // A tap on the navigation bar outside the native menu dismisses it without
                // activating an option or changing the saved quality. Confirm closure first.
                app.navigationBars.firstMatch
                    .coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.5)).tap()
                guard item.waitForNonExistence(timeout: 10) else {
                    XCTFail("The unusable quality menu must dismiss before reopening: " + app.debugDescription)
                    return false
                }
            }
        }
        guard usable else {
            XCTFail("The picker must offer a hittable \(option) after two presentations: " + app.debugDescription)
            return false
        }
        // A native menu row can report a usable frame and hittability while XCTest cannot
        // compute its accessibility activation point. Send the same native tap at the checked
        // row center; the selected value and subsequent stream assertion still prove its effect.
        item.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.5)).tap()
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
        var isPending: Bool { holding && held > 0 && released == 0 && timedOut == 0 }
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

    /// Exercises the recovery path even on a fast host: Previous from a paused third song must
    /// move to the second, then the heart proof must return to its first song and pause it.
    @MainActor
    func testPausedPreviousReturnsToTheHeartProofTrackOnIPhone() {
        guard requireSimulator(.phone, "The paused Previous proof"),
              let configuration = livePlaybackConfiguration(),
              let app = launchConnected(serverURL: configuration.serverURL, configuration: configuration, compact: true),
              openLibraryAlbum("Threshold Boundary", in: app, compact: true),
              playAlbumTrack("UI Playback Canary", in: app),
              openNowPlayingFromBar(in: app, expectingTitle: "UI Playback Canary") else { return }
        let title = app.staticTexts["dulcet.now-playing.title"].firstMatch
        func button(_ label: String) -> XCUIElement? { visiblePlayerButton(label, in: app) }
        func press(_ label: String) -> Bool {
            guard let control = button(label) else {
                XCTFail("The player must offer a tappable \(label): " + app.debugDescription)
                return false
            }
            control.tap()
            return true
        }
        func pause(startIfNeeded: Bool = false) -> Bool {
            // Selecting the final row keeps its title stable even if a slow host lets it end.
            // Play in that case starts it again; Previous must then step from its paused start.
            if startIfNeeded, button("Play") != nil, !press("Play") { return false }
            let deadline = Date().addingTimeInterval(10)
            while button("Pause") == nil, Date() < deadline {
                RunLoop.current.run(until: Date().addingTimeInterval(0.1))
            }
            guard press("Pause") else { return false }
            while button("Play") == nil, Date() < deadline {
                RunLoop.current.run(until: Date().addingTimeInterval(0.1))
            }
            XCTAssertNotNil(button("Play"), "The current song must pause")
            return button("Play") != nil
        }
        guard pause(startIfNeeded: true) else { return }
        XCTAssertEqual(title.label, "UI Playback Canary", "The control must start on the paused third song")
        print("DULCET PAUSED PREVIOUS CONTROL old-label-matches=\(app.buttons.matching(NSPredicate(format: "label == %@", "Previous")).count)"
            + " actual-label=Previous Track title=\(title.label.debugDescription)")
        guard press("Previous Track"), waitForLabel("Thirty One Seconds", of: title, timeout: 10),
              pause() else {
            XCTFail("Previous from the paused third song must select the second; title=\(title.label)")
            return
        }
        print("DULCET PAUSED PREVIOUS CONTROL actual-press=third-to-second paused=true")
        guard bringPausedNowPlaying(to: "Twenty Nine Seconds", in: app) else { return }
        XCTAssertEqual(title.label, "Twenty Nine Seconds")
        XCTAssertNotNil(button("Play"), "The heart proof must leave its target song paused")
        attachScreenshot(named: "heart-proof-track-paused-after-previous", app: app)
        print("DULCET PAUSED PREVIOUS PROOF PASS third-to-second=true second-to-first=true paused=true")
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
        app.launchArguments += liveAccountLaunchArguments(
            serverURL: configuration.serverURL, configuration: configuration)
        app.launch()
        let window = app.windows.firstMatch
        XCTAssertTrue(window.waitForExistence(timeout: 10), "The app window must exist")
        guard let width = settledWindowWidth(
            window, expecting: expectedCompact ? "compact" : "regular",
            matching: { ($0 < 700) == expectedCompact }
        ) else { return }
        let compact = width < 700
        guard compact == expectedCompact else {
            XCTFail((expectedCompact
                ? "This proof requires a compact-width iPhone window; an iPad is invalid evidence"
                : "This proof requires a regular-width iPad window; an iPhone is invalid evidence") + "; width=\(width)")
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
            + " window-width=\(width) track=\(track.debugDescription) starred=true->false")
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
            serverURL: configuration.serverURL, configuration: configuration, compact: expectedCompact,
            extraArguments: ["-dulcet-debug-ui-markers"]
        ) else { return }
        guard requireProofMarkers(in: app), openLibraryAlbum(album, in: app, compact: expectedCompact) else { return }

        // 1. The track row's heart: the one beside the row, by the row's own frame.
        let row = app.buttons.matching(identifier: "dulcet.reader.track")
            .matching(NSPredicate(format: "label BEGINSWITH %@", track + ", ")).firstMatch
        guard row.waitForExistence(timeout: 15), scrollIntoView(row, in: app) else {
            XCTFail("\(album) must list \(track): " + app.debugDescription)
            return
        }
        let moreButtons = app.buttons.matching(identifier: "dulcet.track.more").allElementsBoundByIndex
            .filter { $0.frame.midY > row.frame.minY && $0.frame.midY < row.frame.maxY }
        guard moreButtons.count == 1, let more = moreButtons.first else {
            XCTFail("Exactly one More button must sit beside the track row: " + app.debugDescription)
            return
        }
        guard toggleMenuFavoriteAndProve(more, in: app, what: track,
            read: { self.readServerSongStarred(track, album: album, configuration: configuration) },
            awaitServer: { self.awaitServerSongStarred(track, album: album, configuration: configuration, expected: $0, timeout: 30) }
        ) else { return }

        // 2. The artist page's heart, reached through the album's artist link.
        let link = app.buttons.matching(NSPredicate(format: "label == %@", artist)).firstMatch
        guard link.waitForExistence(timeout: 10), scrollIntoView(link, in: app) else {
            XCTFail("The album page must link its artist \(artist): " + app.debugDescription)
            return
        }
        let artistTitle = app.staticTexts["dulcet.artist.title"].firstMatch
        let albumTitle = app.staticTexts["dulcet.album.title"].firstMatch
        // A synthesized tap can leave the album unchanged. Re-interact only while that exact
        // source page and its hittable link remain, never after reaching any artist page.
        var reachedArtist = false
        for attempt in 1...2 {
            guard !artistTitle.exists, albumTitle.exists, albumTitle.label == album,
                  link.exists, link.isHittable else { break }
            link.tap()
            reachedArtist = artistTitle.waitForExistence(timeout: 15)
            if reachedArtist { break }
            print("DULCET ARTIST LINK destination-absent-after-tap attempt=\(attempt)")
        }
        guard reachedArtist, artistTitle.label == artist else {
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
        guard toggleHeartAndProve(artistHeart, in: app, what: "\(artist)'s page", read: artistStarred, awaitServer: awaitArtist) else { return }
        print("DULCET ROW HEARTS PROOF PASS destination=\(expectedCompact ? "compact" : "regular")"
            + " track=\(track.debugDescription) artist=\(artist.debugDescription) starred=true->false")
    }

    @MainActor
    private func toggleMenuFavoriteAndProve(
        _ more: XCUIElement, in app: XCUIApplication, what: String,
        read: () -> Bool?, awaitServer: (Bool) -> Bool?
    ) -> Bool {
        guard let initial = read() else { XCTFail("The server must answer before changing Favorite"); return false }
        for expected in [!initial, initial] {
            more.tap()
            let action = app.buttons[expected ? "Favorite" : "Remove Favorite"].firstMatch
            guard action.waitForExistence(timeout: 5) else {
                XCTFail("\(what): More must offer the current Favorite action: " + app.debugDescription)
                return false
            }
            action.tap()
            guard awaitServer(expected) == expected else {
                XCTFail("\(what): Favorite must reach the server")
                return false
            }
        }
        return true
    }

    /// From a known starting point whatever an earlier run left: the heart fills with the tap, the
    /// star reaches the server, and a second tap empties it at once and unstars it there.
    @MainActor
    private func toggleHeartAndProve(
        _ heart: XCUIElement,
        in app: XCUIApplication,
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
        guard waitForLabel("Remove Favorite", of: heart, timeout: 3) else {
            XCTFail("\(what): the heart must fill at once, before the server answers; "
                + "label=\(heart.label) handlers=\(proofMarkers(in: app)): " + app.debugDescription)
            return false
        }
        guard awaitServer(true) == true else {
            XCTFail("\(what): the star must reach the server; handlers=\(proofMarkers(in: app)): " + app.debugDescription)
            return false
        }
        print("DULCET HEART HANDLERS target=\(what.debugDescription) markers=\(proofMarkers(in: app))")
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

    /// Re-query all transport copies, skipping departed or untappable hidden-bar copies.
    @MainActor
    private func visiblePlayerButton(_ label: String, in app: XCUIApplication) -> XCUIElement? {
        app.buttons.matching(NSPredicate(format: "label == %@", label))
            .allElementsBoundByIndex.first { self.playerElementIsHittable($0) }
    }

    /// Returns to the proof's song through actual transport presses, then leaves it paused so
    /// a delayed heart or stars tap cannot reach a song that advanced automatically. Previous
    /// starts its selected song, so pause again after every press. Four presses also cover a
    /// finished queue's first Previous replaying its final selection before stepping backward.
    @MainActor
    private func bringPausedNowPlaying(to track: String, in app: XCUIApplication) -> Bool {
        // Re-query by label: Pause becomes Play, and the hidden bar has another transport row.
        func playerButton(_ label: String) -> XCUIElement? { visiblePlayerButton(label, in: app) }
        func pauseCurrent(waitForStart: Bool) -> Bool {
            let startedDeadline = Date().addingTimeInterval(10)
            var pause = playerButton("Pause")
            while pause == nil, Date() < startedDeadline {
                if !waitForStart, playerButton("Play") != nil { return true }
                RunLoop.current.run(until: Date().addingTimeInterval(0.1))
                pause = playerButton("Pause")
            }
            guard let pause else {
                XCTFail("The selected song must start before it can be paused: " + app.debugDescription)
                return false
            }
            pause.tap()
            let pausedDeadline = Date().addingTimeInterval(10)
            while playerButton("Play") == nil, Date() < pausedDeadline {
                RunLoop.current.run(until: Date().addingTimeInterval(0.1))
            }
            guard playerButton("Play") != nil else {
                XCTFail("Now Playing must pause before its heart or stars are tapped: " + app.debugDescription)
                return false
            }
            return true
        }
        guard pauseCurrent(waitForStart: false) else { return false }
        let title = app.staticTexts["dulcet.now-playing.title"].firstMatch
        for press in 1...4 {
            let before = title.label
            if before == track { return true }
            // OBSERVED in run 38519386586: the old label "Previous" matched no buttons, so all
            // four optional taps were skipped. The actual accessible label is "Previous Track".
            guard let previous = playerButton("Previous Track"), previous.isEnabled else {
                XCTFail("Now Playing must offer tappable Previous Track while returning to \(track): " + app.debugDescription)
                return false
            }
            previous.tap()
            guard pauseCurrent(waitForStart: true) else { return false }
            print("DULCET PAUSED NOW PLAYING previous-press=\(press) from=\(before.debugDescription) to=\(title.label.debugDescription)")
        }
        guard title.label == track else {
            XCTFail("Now Playing must be back on \(track) before its heart or stars are tapped; title=\(title.label)")
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
        openSidebarLibrarySection("playlists", in: app, compact: compact)
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
        app.launchArguments += liveAccountLaunchArguments(
            serverURL: configuration.serverURL, configuration: configuration,
            extraArguments: ["-dulcet-debug-ui-markers"])
        app.launch()

        let window = app.windows.firstMatch
        XCTAssertTrue(window.waitForExistence(timeout: 10), "The app window must exist")
        guard let width = settledWindowWidth(
            window, expecting: "regular", matching: { $0 > 700 }
        ) else { return }
        XCTAssertGreaterThan(
            width,
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
            app.descendants(matching: .any)["dulcet.sidebar.nowPlaying"].exists,
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
            visiblePlayerButton(label, in: app)
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
                guard let snapshot = try? $0.snapshot() else { return false }
                let frame = snapshot.frame
                return frame.width > 0 && frame.height > 0 && frame.maxX <= queueFrame.minX
                    && frame.minY >= title.frame.minY && windowFrame.contains(frame)
                    && snapshot.identifier != "dulcet.debug.ui-markers" && self.playerElementIsHittable($0)
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
            visiblePlayerButton("Next Track", in: app)
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
              dulcetSearchField(in: app).waitForExistence(timeout: 5) else {
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
        // The delivery marker is the other proof of playback below, for a canary that has ended.
        app.launchArguments += ["-dulcet-debug-scrobble-delivery-marker"]
        app.launchArguments += liveAccountLaunchArguments(
            serverURL: configuration.serverURL, configuration: configuration)
        app.launch()

        let window = app.windows.firstMatch
        XCTAssertTrue(window.waitForExistence(timeout: 10), "The app window must exist")
        guard let width = settledWindowWidth(
            window, expecting: windowExpectation == .compactWidth ? "compact" : "regular",
            matching: { windowExpectation == .compactWidth ? $0 <= 700 : $0 > 700 }
        ) else { return }
        switch windowExpectation {
        case .compactWidth:
            XCTAssertLessThanOrEqual(
                width,
                700,
                "This proof requires a compact-width iPhone window; an iPad is invalid evidence"
            )
        case .regularWidth:
            XCTAssertGreaterThan(
                width,
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

        let searchField = dulcetSearchField(in: app)
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

        let searchMarker = app.staticTexts["dulcet.debug.scrobble-delivery"].firstMatch
        guard searchMarker.waitForExistence(timeout: 10),
              let searchBaseline = waitForScrobbleDeliveryCounts(
                in: searchMarker, timeout: 10,
                until: { $0["delivered"] != nil && $0["pending"] == 0 }
              ), let searchPreviouslyDelivered = searchBaseline["delivered"] else {
            XCTFail("Search must observe a settled delivery baseline before activation")
            return
        }
        XCTAssertEqual(searchBaseline["persisted"], 0, "Search has not started a new play before its result is pressed")
        XCTAssertEqual(searchBaseline["now-playing"], 0, "Saved queue restoration does not announce playback")
        guard searchBaseline["persisted"] == 0, searchBaseline["now-playing"] == 0 else { return }

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
        // The canary is 31 s long. On a starved runner the steps from the tap to here have taken
        // longer than that (apple-ci run 37556579512: the queue had finished, Play showing and
        // the bar back at 0:00 of 0:31, and a finished, unseekable track draws a progress bar,
        // not the slider). The app's delivered play is the other proof: it is sent only after
        // 15.5 s of progressing media time (§15.2), which the slider's presence never showed.
        let deliveryMarker = app.staticTexts["dulcet.debug.scrobble-delivery"].firstMatch
        let playbackEvidence = waitForPlaybackEvidence(
            slider: app.sliders["Now Playing"].firstMatch, deliveryMarker: deliveryMarker, timeout: 30
        )
        print("DULCET SEARCH PLAYBACK EVIDENCE \(playbackEvidence ?? "none") marker=\(deliveryMarker.label)")
        XCTAssertNotNil(
            playbackEvidence,
            "Real playback of the activated track must begin and expose progressing media time"
        )
        // This phase plays the same canary as the next threshold proof. Finish its submission
        // before CI records that proof's server baseline, so a later launch cannot drain it then.
        guard let searchDelivery = waitForScrobbleDeliveryCounts(
            in: deliveryMarker, timeout: 60,
            until: { ($0["delivered"] ?? 0) >= searchPreviouslyDelivered + 1 && $0["pending"] == 0 }
        ) else {
            XCTFail("Search playback must finish its submitted-play delivery before this phase returns: " + deliveryMarker.label)
            return
        }
        XCTAssertEqual(searchDelivery["persisted"], 1, "Search activation produces one submitted play")
        XCTAssertEqual(searchDelivery["delivered"], searchPreviouslyDelivered + 1, "The current search play must reach the server once")
        XCTAssertEqual(searchDelivery["refused-dropped"], searchBaseline["refused-dropped"], "No search play may be dropped")
        print(
            "DULCET SEARCH UI PASS width=\(windowExpectation == .compactWidth ? "compact" : "regular")"
                + " query=typed ranks=\(observedLabels)"
                + " activated-rank=\(canaryRank) activation=tap source=search now-playing=\(canaryTitle)"
        )
    }

    /// A regular-width iPad keeps native destination navigation and presents Account as a sheet.
    /// The sheet's real form must be visible, contained, and dismiss back to the content page.
    @MainActor
    func testAccountSheetUsesRegularWidthNativePresentation() {
        let app = XCUIApplication()
        app.launchArguments.append("-dulcet-account-connect-layout-fixture")
        app.launch()
        print("DULCET IPADOS APP LAUNCH PASS layout-assertions-starting=true")
        let window = app.windows.firstMatch
        XCTAssertTrue(window.waitForExistence(timeout: 10), "The app window must exist")
        guard let width = settledWindowWidth(window, expecting: "regular", matching: { $0 > 700 }) else { return }
        XCTAssertGreaterThan(width, 700, "This proof requires a regular-width iPad")
        // The layout fixture opens Account on launch. Reach Library first so its
        // navigation is not covered by the modal whose presentation this test proves.
        guard openDestination("Library", sidebarIdentifier: "dulcet.sidebar.library", in: app, compact: false) else { return }
        let legacySidebar = window.buttons["dulcet.sidebar.library"].firstMatch
        let nativeSidebar = window.descendants(matching: .any)["dulcet.tab.library"].firstMatch
        let sidebar = legacySidebar.exists ? legacySidebar : nativeSidebar
        XCTAssertTrue(sidebar.waitForExistence(timeout: 10) && sidebar.isHittable,
            "Regular width must expose the Library destination")
        let libraryTitle = app.navigationBars.staticTexts["Library"].firstMatch
        XCTAssertTrue(libraryTitle.waitForExistence(timeout: 10) && libraryTitle.isHittable,
            "The selected content page must be visible before opening Account")
        let account = app.buttons["dulcet.account.open"].firstMatch
        guard account.waitForExistence(timeout: 10), account.isHittable else {
            XCTFail("The iPad content toolbar must offer Account: " + app.debugDescription)
            return
        }
        account.tap()
        let accountBar = app.navigationBars["Account"].firstMatch
        let serverField = window.textFields["dulcet.account-connect.server-address"].firstMatch
        let close = app.buttons["dulcet.account.close"].firstMatch
        guard accountBar.waitForExistence(timeout: 10), serverField.waitForExistence(timeout: 10),
              close.waitForExistence(timeout: 10) else {
            XCTFail("Account must present its native title, server field and Close: " + app.debugDescription)
            return
        }
        for (name, element) in [("server field", serverField), ("Close", close)] {
            XCTAssertTrue(element.isHittable, "\(name) must be reachable in the sheet")
            XCTAssertGreaterThan(element.frame.width, 1)
            XCTAssertGreaterThan(element.frame.height, 1)
            XCTAssertTrue(window.frame.contains(element.frame), "\(name) must fit the regular-width window")
        }
        XCTAssertLessThan(serverField.frame.width, width * 0.9,
            "The system iPad sheet must not stretch its field across the entire display")
        close.tap()
        XCTAssertTrue(close.waitForNonExistence(timeout: 10), "Close must dismiss Account")
        XCTAssertTrue(libraryTitle.isHittable, "Dismissing Account must restore the same content page")
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
            app.launchArguments += liveAccountLaunchArguments(
                serverURL: configuration.serverURL, configuration: configuration)
        } else {
            installSystemAlertInterruptionMonitors()
        }
        app.launch()

        let window = app.windows.firstMatch
        XCTAssertTrue(window.waitForExistence(timeout: 10), "The app window must exist")
        if !compact {
            guard let width = settledWindowWidth(
                window, expecting: "regular", matching: { $0 > 700 }
            ) else { return }
            XCTAssertGreaterThan(
                width,
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

        // Saved launch may drain a submitted play queued by an earlier process. Settle that
        // outbox before taking the baseline; a new play or ephemeral now-playing before the tap
        // remains a failure. Only delivered acknowledgements from THIS crossing count below.
        let deliveryMarker = app.staticTexts["dulcet.debug.scrobble-delivery"].firstMatch
        guard deliveryMarker.waitForExistence(timeout: 10) else {
            XCTFail("The app's scrobble delivery marker must exist when its launch argument is passed")
            return
        }
        guard let baseline = waitForScrobbleDeliveryCounts(
            in: deliveryMarker,
            timeout: 10,
            until: { $0["delivered"] != nil && $0["pending"] == 0 }
        ) else {
            XCTFail("The scrobble delivery marker must report counts; last label: \(deliveryMarker.label)")
            return
        }
        let previouslyDelivered = baseline["delivered"] ?? -1
        XCTAssertGreaterThanOrEqual(previouslyDelivered, 0, "The delivery counter must be readable")
        XCTAssertEqual(baseline["pending"], 0, "No play may be waiting in the outbox from an earlier launch")
        XCTAssertEqual(baseline["persisted"], 0, "No play may be persisted by this launch before playback")
        XCTAssertEqual(baseline["now-playing"], 0, "A paused saved queue must not send now-playing before playback")
        guard previouslyDelivered >= 0, baseline["pending"] == 0, baseline["persisted"] == 0,
              baseline["now-playing"] == 0 else {
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
            until: { ($0["delivered"] ?? 0) >= previouslyDelivered + 1 }
        ) else {
            XCTFail(
                "The app must report the scrobble delivered before this proof returns; last marker: \(deliveryMarker.label)"
            )
            return
        }
        XCTAssertEqual(delivered["delivered"], previouslyDelivered + 1, "Exactly one new play is expected for this crossing")
        XCTAssertEqual(delivered["persisted"], 1, "Only this playback may persist a new play")
        XCTAssertEqual(delivered["refused-dropped"], baseline["refused-dropped"], "The new play must be delivered, never dropped")
        print("DULCET THRESHOLD BASELINE delivered=\(previouslyDelivered) final=\(deliveryMarker.label)")
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
        // As in the account-state proof, a dismissed iPad keyboard can retain offscreen AX
        // keys below a zero-height container. Read presence and geometry from one snapshot;
        // an unavailable snapshot is unknown and cannot establish that activation is safe.
        func isShown() -> Bool? {
            guard let root = try? app.snapshot(),
                  let window = root.children.first(where: { $0.elementType == .window && !$0.frame.isEmpty })
            else { return nil }
            var pending: [XCUIElementSnapshot] = [root]
            while let node = pending.popLast() {
                if node.elementType == .keyboard && !node.frame.isEmpty
                    && !node.frame.intersection(window.frame).isEmpty { return true }
                pending.append(contentsOf: node.children)
            }
            return false
        }
        // Search resigns the field on both platforms. Retain the bounded fallbacks when a
        // presented keyboard remains, and require it to stop covering the window before use.
        for attempt in 1...3 {
            let shown = isShown()
            if shown == false { return true }
            if shown == true {
                let submit = keyboard.buttons["Search"].firstMatch
                let hideKeyboard = keyboard.buttons["Hide keyboard"].firstMatch
                if submit.waitForExistence(timeout: 2), submit.isHittable {
                    submit.tap()
                } else if hideKeyboard.waitForExistence(timeout: 2), hideKeyboard.isHittable {
                    hideKeyboard.tap()
                } else {
                    app.swipeDown()
                }
            }
            let deadline = ContinuousClock.now.advanced(by: .seconds(5))
            repeat {
                if isShown() == false { return true }
                Thread.sleep(forTimeInterval: 0.1)
            } while ContinuousClock.now < deadline
            print("DULCET SEARCH keyboard-still-shown-or-unknown attempt=\(attempt)")
        }
        XCTFail("The software keyboard must dismiss before a result is activated: " + app.debugDescription)
        return false
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

    /// `scrollIntoView`, answering how many swipes it took (six by default), or nil when the element
    /// never became reachable. Catalog headings can reserve space for their lazy content below.
    @MainActor
    private func scrollIntoViewCountingSwipes(
        _ element: XCUIElement,
        in app: XCUIApplication,
        probingBlockingSystemAlerts: Bool = true,
        alsoRevealing followingRow: XCUIElement? = nil,
        minimumSpaceBelow: CGFloat = 0,
        maximumSwipes: Int = 6
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

            var targetIsAboveWindow = false
            var targetFrame: CGRect?
            if element.exists {
                let frame = element.frame
                if !frame.isEmpty && !frame.isInfinite { targetFrame = frame }
                let midpoint = CGPoint(x: frame.midX, y: frame.midY)
                targetIsAboveWindow = !frame.isEmpty && !frame.isInfinite && midpoint.y < window.frame.minY
                if !frame.isEmpty && !frame.isInfinite &&
                    window.frame.contains(midpoint) && element.isHittable &&
                    (followingRow?.exists ?? true) &&
                    (minimumSpaceBelow <= 0 || window.frame.maxY - frame.maxY >= minimumSpaceBelow) {
                    return swipeCount
                }
            }

            guard swipeCount < maximumSwipes else { return nil }

            // This must remain a real event rather than an isHittable-gated preflight. Tap paths
            // can still invoke the interruption monitors installed as a backstop.
            // A restored scroll position can place an existing destination above the window.
            // Keep the same swipe budget and reachability checks while scrolling toward it.
            if minimumSpaceBelow > 0, let targetFrame {
                // A fixed quarter-window drag still flung the AX5 heading past the navigation
                // bar; the next full reverse swipe overshot its placement band again. Move by
                // its measured remaining distance and hold before lifting to avoid that fling.
                let windowFrame = window.frame
                let navigation = app.navigationBars.firstMatch
                let tabs = app.tabBars.firstMatch
                let bar = app.buttons["dulcet.mini-player.open"].firstMatch
                var top = windowFrame.minY
                var bottom = windowFrame.maxY
                if navigation.exists, !navigation.frame.isEmpty { top = max(top, navigation.frame.maxY) }
                for overlay in [tabs, bar] where overlay.exists && !overlay.frame.isEmpty {
                    bottom = min(bottom, overlay.frame.minY)
                }
                let movement = targetFrame.minY - (top + 16)
                let distance = min(max(abs(movement), 30), max(30, (bottom - top) * 0.6))
                let fromY = movement >= 0 ? bottom - 20 : top + 20
                let toY = fromY + (movement >= 0 ? -distance : distance)
                print("DULCET CATALOG PLACEMENT heading-y=\(targetFrame.minY) target-y=\(top + 16)"
                    + " drag=\(toY - fromY) swipe=\(swipeCount + 1)/\(maximumSwipes)")
                window.coordinate(withNormalizedOffset: CGVector(dx: 0.5,
                    dy: (fromY - windowFrame.minY) / windowFrame.height))
                    .press(forDuration: 0.1, thenDragTo:
                        window.coordinate(withNormalizedOffset: CGVector(dx: 0.5,
                            dy: (toY - windowFrame.minY) / windowFrame.height)),
                        withVelocity: .slow, thenHoldForDuration: 0.3)
            }
            else if targetIsAboveWindow { app.swipeDown() }
            else { app.swipeUp() }
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

    /// "slider" once Now Playing shows the seekable slider, "delivered" once the app reports a play
    /// delivered (past the scrobble threshold), or nil when neither happens within `timeout`.
    @MainActor
    private func waitForPlaybackEvidence(
        slider: XCUIElement, deliveryMarker: XCUIElement, timeout: TimeInterval
    ) -> String? {
        let deadline = Date().addingTimeInterval(timeout)
        repeat {
            if slider.exists { return "slider" }
            if let counts = scrobbleDeliveryCounts(from: deliveryMarker.label), (counts["delivered"] ?? 0) >= 1 {
                return "delivered"
            }
            RunLoop.current.run(until: Date().addingTimeInterval(0.25))
        } while Date() < deadline
        return nil
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

    // MARK: - Killed-process download handoff (spec §14.5, docs/download-background-blockers.md)

    /// A download started from a reader row is still outstanding at the server when the app dies;
    /// a replacement process reconciles it into the durable downloaded row, the same download and
    /// the same bytes, without fetching the track again. On iPhone.
    @MainActor
    func testAKilledAppsDownloadIsReconciledByTheReplacementProcessOnIPhone() {
        guard requireSimulator(.phone, "The killed-process download handoff proof on iPhone") else { return }
        proveKilledProcessDownloadHandoff(compact: true, platform: "ios")
    }

    /// The same proof in an iPad's regular window, so iPhone evidence does not stand for iPad.
    @MainActor
    func testAKilledAppsDownloadIsReconciledByTheReplacementProcessOnIPadOS() {
        guard requireSimulator(.pad, "The killed-process download handoff proof on iPadOS") else { return }
        proveKilledProcessDownloadHandoff(compact: false, platform: "ipados")
    }

    /// A track's search result offers Download in its menu, and choosing it downloads that track:
    /// the probe's marker file records the promotion, the server's bytes, read back from the
    /// durable row. The run's downloads, session and database are the probe namespace's own, so
    /// nothing is left for a later test to play from.
    @MainActor
    func testASearchResultsMenuDownloadsTheTrackOnIPhone() {
        guard requireSimulator(.phone, "The search result Download proof on iPhone"),
              let configuration = livePlaybackConfiguration() else { return }
        let track = "UI Playback Canary"
        guard let songID = serverSongID(track, album: "Threshold Boundary", configuration: configuration),
              let original = restBytes("download", [URLQueryItem(name: "id", value: songID)], configuration: configuration),
              !original.isEmpty else {
            XCTFail("The server must hand over \(track)'s original file")
            return
        }
        let digest = SHA256.hash(data: original).map { String(format: "%02x", $0) }.joined()
        let namespace = "ios-search-" + UUID().uuidString.prefix(8).lowercased()
        afterTest.append {
            let cleaner = XCUIApplication()
            cleaner.launchArguments = ["-dulcet-debug-download-handoff-dump", namespace,
                                       "-dulcet-debug-download-handoff-clear"]
            cleaner.launch()
            cleaner.terminate()
        }
        guard let app = launchConnected(serverURL: configuration.serverURL, configuration: configuration, compact: true,
                                        extraArguments: ["-dulcet-debug-download-handoff", namespace]),
              openDestination("Search", sidebarIdentifier: "dulcet.sidebar.search", in: app, compact: true) else { return }
        let field = dulcetSearchField(in: app)
        guard field.waitForExistence(timeout: 10) else {
            XCTFail("The search field must exist on the Search destination: " + app.debugDescription)
            return
        }
        field.tap()
        field.typeText(track)
        let result = app.buttons.matching(NSPredicate(
            format: "identifier BEGINSWITH %@ AND label BEGINSWITH %@ AND label ENDSWITH %@",
            "dulcet.search.result.", track + ", ", ", Track"
        )).firstMatch
        guard result.waitForExistence(timeout: 30), dismissKeyboardBeforeActivation(in: app),
              scrollIntoView(result, in: app) else {
            XCTFail("Search must find \(track): " + app.debugDescription)
            return
        }
        let download = app.buttons["Download"].firstMatch
        var offered = false
        withoutIdleWaits(app) {
            result.press(forDuration: 1.2)
            offered = download.waitForExistence(timeout: 5)
            if offered { download.tap() }
        }
        guard offered else {
            XCTFail("The track result's menu must offer Download: " + app.debugDescription)
            return
        }
        let overlay = app.staticTexts["dulcet.debug.download-handoff"].firstMatch
        guard waitForLabelContaining("state=downloaded", of: overlay, timeout: 60) else {
            XCTFail("The track must be downloaded: " + overlay.debugDescription)
            return
        }
        let finished = handoffFields(overlay.label)
        print("DULCET SEARCH DOWNLOAD summary \(overlay.label)")
        XCTAssertEqual(finished["item"], songID, "The result's own track must be downloaded")
        XCTAssertEqual(finished["row"], "downloaded", "The durable row must read back downloaded")
        XCTAssertEqual(finished["bytes"], String(original.count), "The file must hold the server's bytes")
        XCTAssertEqual(finished["sha256"], digest, "The file must hold the server's bytes")
        attachScreenshot(named: "search-result-download-ios", app: app)
    }

    /// The handoff, each step observed on its own:
    /// 1. the proxy holds the track's `stream` read, so the download is outstanding at the server
    ///    while the session reports it running;
    /// 2. the app ends itself by `exit`, which the system does not read as a force quit
    ///    (`simctl terminate` and the app switcher are force quits, and cancel the session's
    ///    tasks), and the held read is still unanswered after the process is gone;
    /// 3. the read is released and answered once, with no app process alive;
    /// 4. a replacement process (another pid) promotes the secured artifact into the durable row:
    ///    the same download, `downloaded` read back from the database, and the server's bytes.
    /// Whether the system itself launched that replacement for the session's events is recorded
    /// and asserted separately, so the two findings fail apart.
    @MainActor
    private func proveKilledProcessDownloadHandoff(compact: Bool, platform: String) {
        guard let configuration = livePlaybackConfiguration(),
              let proxy = lyricsFaultProxyConfiguration(server: configuration) else { return }
        let track = "UI Playback Canary"
        let album = "Threshold Boundary"
        guard let songID = serverSongID(track, album: album, configuration: configuration),
              let original = restBytes("download", [URLQueryItem(name: "id", value: songID)], configuration: configuration),
              !original.isEmpty else {
            XCTFail("The server must hand over \(track)'s original file")
            return
        }
        let originalDigest = SHA256.hash(data: original).map { String(format: "%02x", $0) }.joined()
        let namespace = "\(platform)-" + UUID().uuidString.prefix(8).lowercased()
        print("DULCET HANDOFF namespace=\(namespace) song=\(songID) bytes=\(original.count) sha256=\(originalDigest)")
        afterTest.append { [self] in
            _ = proxyRequest("POST", "/__dulcet/rule?action=hold&state=off", proxy: proxy)
            // The namespace lives in the app's defaults for a quarter hour; a later test must not
            // inherit it.
            let cleaner = XCUIApplication()
            // Supply the namespace independently of defaults: the failing replacement may
            // have lost that record. The cleanup app copies its marker trail to the system
            // log in the xcresult, including steps the overlay could never show.
            cleaner.launchArguments = ["-dulcet-debug-download-handoff-dump", namespace,
                                       "-dulcet-debug-download-handoff-clear"]
            cleaner.launch()
            cleaner.terminate()
        }
        guard let since = proxyRequestLog(since: 0, proxy: proxy)?.total else {
            XCTFail("The proxy must report its request log")
            return
        }
        guard proxyRequest("POST", "/__dulcet/rule?action=hold&state=on&endpoint=stream", proxy: proxy) != nil else {
            XCTFail("The proxy must hold stream reads")
            return
        }
        guard let app = launchConnected(serverURL: proxy.url, configuration: configuration, compact: compact,
                                        extraArguments: ["-dulcet-debug-download-handoff", namespace]),
              openLibraryAlbum(album, containing: track, in: app, compact: compact) else { return }
        let row = app.buttons.matching(identifier: "dulcet.reader.track")
            .matching(NSPredicate(format: "label BEGINSWITH %@", track + ", ")).firstMatch
        guard row.waitForExistence(timeout: 10), scrollIntoView(row, in: app) else {
            XCTFail("The album must list \(track): " + app.debugDescription)
            return
        }
        let download = app.buttons["Download"].firstMatch
        var offered = false
        withoutIdleWaits(app) {
            row.press(forDuration: 1.2)
            offered = download.waitForExistence(timeout: 5)
            if offered { download.tap() }
        }
        guard offered else {
            XCTFail("The row's menu must offer Download: " + app.debugDescription)
            return
        }

        // 1. Outstanding: held at the server, and reported running by the session.
        guard awaitProxyStreams(of: songID, since: since, proxy: proxy, timeout: 60, until: { $0.count == 1 && !$0[0].answered }) != nil else {
            XCTFail("The download's stream read must reach the proxy and be held")
            return
        }
        let overlay = app.staticTexts["dulcet.debug.download-handoff"].firstMatch
        guard waitForLabelContaining("outstanding=download:", of: overlay, timeout: 30) else {
            XCTFail("The session must report the download running: " + overlay.debugDescription)
            return
        }
        let started = handoffFields(overlay.label)
        let outstanding = started["outstanding"] ?? ""
        let firstPID = started["pid"] ?? ""
        print("DULCET HANDOFF outstanding=\(outstanding) pid=\(firstPID)")
        XCTAssertTrue(outstanding.hasSuffix("@" + firstPID), "The running download must be this process's")

        // 2. Death by exit while outstanding, and the read still unanswered afterwards. The app's
        // probe calls exit() when the runner posts its Darwin notification: the runner shares the
        // simulator's notification centre, and on the iPad simulator a synthesized tap on the
        // overlay's Exit button was never delivered to it (three taps, the app still running).
        guard app.buttons["dulcet.debug.download-handoff.exit"].exists else {
            XCTFail("The probe must be running in the app: " + app.debugDescription)
            return
        }
        let exitNotification = "com.legitimateapps.dulcet.debug.download-handoff.exit." + namespace
        CFNotificationCenterPostNotification(CFNotificationCenterGetDarwinNotifyCenter(),
                                             CFNotificationName(exitNotification as CFString), nil, nil, true)
        guard app.wait(for: .notRunning, timeout: 20) else {
            attachScreenshot(named: "download-handoff-exit-not-taken-\(platform)", app: app)
            XCTFail("The app must have ended by exit(); state \(app.state.rawValue): " + app.debugDescription)
            return
        }
        let afterDeath = proxyStreams(of: songID, since: since, proxy: proxy)
        print("DULCET HANDOFF died stream-reads=\(afterDeath.count) answered=\(afterDeath.map(\.answered))")
        XCTAssertEqual(afterDeath.map(\.answered), [false], "The download must still be outstanding after the process died")

        // 3. Release; the read is answered once, by the system on the dead app's behalf.
        guard proxyRequest("POST", "/__dulcet/rule?action=hold&state=off", proxy: proxy) != nil,
              let answered = awaitProxyStreams(of: songID, since: since, proxy: proxy, timeout: 60, until: { $0.allSatisfy(\.answered) }) else {
            XCTFail("The released read must be answered")
            return
        }
        XCTAssertEqual(answered.map(\.status), [200], "The held read must be answered 200, once")

        // 4. The replacement process. The system may launch it for the session's events; the
        // proof waits for that, then brings it forward (or, if none came, launches one itself).
        let relaunch = XCUIApplication()
        relaunch.launchArguments = ["-dulcet-debug-download-handoff", namespace]
        let launchDeadline = Date().addingTimeInterval(120)
        while relaunch.state == .notRunning, Date() < launchDeadline {
            RunLoop.current.run(until: Date().addingTimeInterval(0.5))
        }
        let systemLaunched = relaunch.state != .notRunning
        if systemLaunched {
            // Let the system's process finish the session's events before it is brought forward.
            RunLoop.current.run(until: Date().addingTimeInterval(3))
        }
        print("DULCET HANDOFF os-launch=\(systemLaunched) state=\(relaunch.state.rawValue)")
        if systemLaunched { relaunch.activate() } else { relaunch.launch() }
        let replaced = relaunch.staticTexts["dulcet.debug.download-handoff"].firstMatch
        guard waitForLabelContaining("state=downloaded", of: replaced, timeout: 60) else {
            XCTFail("A replacement process must promote the download: " + replaced.debugDescription)
            return
        }
        let finished = handoffFields(replaced.label)
        print("DULCET HANDOFF summary \(replaced.label)")
        let promoted = finished["promoted"] ?? ""
        let promotedDownload = promoted.split(separator: "@").first.map(String.init) ?? ""
        let promotedPID = promoted.split(separator: "@").last.map(String.init) ?? ""
        XCTAssertEqual(finished["exit"], firstPID, "The process that died must be the one that started the download")
        XCTAssertEqual(promotedDownload, outstanding.split(separator: "@").first.map(String.init),
                       "The replacement must promote the download that was outstanding")
        XCTAssertFalse(promotedPID.isEmpty || promotedPID == firstPID, "The promotion must come from another process")
        XCTAssertEqual(finished["item"], songID)
        XCTAssertEqual(finished["state"], "downloaded")
        XCTAssertEqual(finished["row"], "downloaded", "The durable row must read back downloaded")
        XCTAssertEqual(finished["bytes"], String(original.count), "The artifact must hold the server's bytes")
        XCTAssertEqual(finished["sha256"], originalDigest, "The artifact must hold the server's bytes")
        // As in the Mac proof, let any unintended follow-up transfer reach the proxy before
        // asserting that the handoff made only one stream read.
        RunLoop.current.run(until: Date().addingTimeInterval(3))
        XCTAssertEqual(proxyStreams(of: songID, since: since, proxy: proxy).count, 1,
                       "The handoff must not fetch the track again")
        // The system's own delivery (os-initiated-background-session-delivery), asserted apart.
        XCTAssertTrue(systemLaunched && Int(finished["unattended-launches"] ?? "") ?? 0 >= 1,
                      "The system must launch the app for the session's events")
        XCTAssertGreaterThanOrEqual(Int(finished["background-events"] ?? "") ?? 0, 1,
                                    "The scene must receive the session's background events")

        // The row the person sees.
        guard openLibraryAlbum(album, containing: track, in: relaunch, compact: compact) else { return }
        let shown = relaunch.buttons.matching(identifier: "dulcet.reader.track")
            .matching(NSPredicate(format: "label BEGINSWITH %@", track + ", ")).firstMatch
        let downloaded = NSPredicate(format: "value == %@", "Downloaded")
        XCTAssertEqual(XCTWaiter.wait(for: [XCTNSPredicateExpectation(predicate: downloaded, object: shown)], timeout: 15), .completed,
                       "The row must show the track downloaded: " + shown.debugDescription)
        attachScreenshot(named: "download-handoff-\(platform)", app: relaunch)
    }

    /// The probe overlay's `key=value` fields.
    private func handoffFields(_ label: String) -> [String: String] {
        Dictionary(label.split(separator: " ").compactMap { field -> (String, String)? in
            guard let equals = field.firstIndex(of: "=") else { return nil }
            return (String(field[..<equals]), String(field[field.index(after: equals)...]))
        }, uniquingKeysWith: { first, _ in first })
    }

    private struct ProxyStream {
        let answered: Bool
        let status: Int
    }

    /// The proxy's request log after `since`: its total, and each entry's fields.
    private func proxyRequestLog(since: Int, proxy: LyricsFaultProxy) -> (total: Int, requests: [[String: Any]])? {
        guard let body = proxyRequest("GET", "/__dulcet/requests?since=\(since)", proxy: proxy),
              let total = body["total"] as? Int else { return nil }
        return (total, body["requests"] as? [[String: Any]] ?? [])
    }

    /// The `stream` reads of `songID` the proxy has seen since `since`, oldest first.
    private func proxyStreams(of songID: String, since: Int, proxy: LyricsFaultProxy) -> [ProxyStream] {
        (proxyRequestLog(since: since, proxy: proxy)?.requests ?? [])
            .filter { $0["endpoint"] as? String == "stream" && $0["id"] as? String == songID }
            .map { ProxyStream(answered: $0["answered"] as? Bool ?? false, status: $0["status"] as? Int ?? 0) }
    }

    private func awaitProxyStreams(
        of songID: String,
        since: Int,
        proxy: LyricsFaultProxy,
        timeout: TimeInterval,
        until condition: ([ProxyStream]) -> Bool
    ) -> [ProxyStream]? {
        let deadline = Date().addingTimeInterval(timeout)
        repeat {
            let seen = proxyStreams(of: songID, since: since, proxy: proxy)
            if !seen.isEmpty, condition(seen) { return seen }
            RunLoop.current.run(until: Date().addingTimeInterval(0.5))
        } while Date() < deadline
        return nil
    }

    /// A binary `/rest` answer (`download`, `stream`), refused if it is a Subsonic error envelope.
    /// Credentials ride in the query, so nothing here names the URL.
    private func restBytes(
        _ endpoint: String,
        _ query: [URLQueryItem],
        configuration: LivePlaybackConfiguration
    ) -> Data? {
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
        ] + query
        guard let url = components.url else { return nil }
        final class Outcome: @unchecked Sendable { var data: Data?; var type = "" }
        let outcome = Outcome()
        let done = DispatchSemaphore(value: 0)
        let task = URLSession.shared.dataTask(with: url) { data, response, error in
            if error == nil, let http = response as? HTTPURLResponse, http.statusCode == 200 {
                outcome.data = data
                outcome.type = http.value(forHTTPHeaderField: "Content-Type") ?? ""
            }
            done.signal()
        }
        task.resume()
        guard done.wait(timeout: .now() + 30) == .success else {
            task.cancel()
            print("DULCET REST \(endpoint) timed out")
            return nil
        }
        guard let data = outcome.data, !outcome.type.contains("xml"), !outcome.type.contains("json") else {
            print("DULCET REST \(endpoint) did not return media")
            return nil
        }
        return data
    }
}


extension DulcetiOSUITests {
    /// Same finger path on phone and tablet, with a deterministic paused queue so network and
    /// track endings cannot explain the outcome. Hitch collection targets the application,
    /// including unsuccessful gestures; a missing transition is never reported as a fast one.
    @MainActor
    func testNowPlayingOpenAndCloseGestures() {
        guard #available(iOS 26.0, *) else {
            XCTFail("Gesture hitch evidence requires iOS 26 or newer")
            return
        }
        let app = prepareGesturePlayer()
        let bar = app.buttons["dulcet.mini-player.open"].firstMatch
        let title = app.staticTexts["dulcet.now-playing.title"].firstMatch
        let close = app.buttons["dulcet.now-playing.close"].firstMatch
        var opens = 0
        var closes = 0
        var samples = 0
        let options = XCTMeasureOptions()
        options.iterationCount = 3
        measure(metrics: [XCTClockMetric(), XCTHitchMetric(application: app)], options: options) {
            samples += 1
            let start = bar.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.5))
            let end = start.withOffset(CGVector(dx: 0, dy: -app.windows.firstMatch.frame.height * 0.55))
            let openStart = ProcessInfo.processInfo.systemUptime
            start.press(forDuration: 0.05, thenDragTo: end, withVelocity: .slow, thenHoldForDuration: 0)
            let openEnd = ProcessInfo.processInfo.systemUptime
            let opened = title.waitForExistence(timeout: 2)
            if opened { opens += 1 } else { bar.tap(); XCTAssertTrue(title.waitForExistence(timeout: 5)) }
            // The track title is outside artwork, sliders, queue and lyrics. A title drag
            // distinguishes a whole-player dismissal from the old artwork-only iPad handler.
            let downStart = title.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.5))
            let downEnd = downStart.withOffset(CGVector(dx: 0, dy: app.windows.firstMatch.frame.height * 0.3))
            let closeStart = ProcessInfo.processInfo.systemUptime
            downStart.press(forDuration: 0.05, thenDragTo: downEnd, withVelocity: .fast, thenHoldForDuration: 0)
            let closeEnd = ProcessInfo.processInfo.systemUptime
            print("NP_TIMING sample=\(samples) openStart=\(openStart) openEnd=\(openEnd) closeStart=\(closeStart) closeEnd=\(closeEnd)")
            let closed = title.waitForNonExistence(timeout: 2)
            if closed { closes += 1 } else { close.tap(); XCTAssertTrue(title.waitForNonExistence(timeout: 5)) }
            print("NP_GESTURE sample=\(samples) open=\(opened) close=\(closed)")
        }
        XCTAssertEqual(opens, samples, "Every upward drag must open the player")
        XCTAssertEqual(closes, samples, "Every downward title drag must close the player")
    }

    /// A diagonal downward seek must reach the scrubber without handing the touch to dismissal.
    @MainActor
    func testNowPlayingScrubberDragDoesNotDismiss() {
        let app = prepareGesturePlayer()
        app.buttons["dulcet.mini-player.open"].firstMatch.tap()
        let title = app.staticTexts["dulcet.now-playing.title"].firstMatch
        XCTAssertTrue(title.waitForExistence(timeout: 5))
        let scrubber = app.sliders["Now Playing"].firstMatch
        XCTAssertTrue(scrubber.waitForExistence(timeout: 5))
        let valueBefore = scrubber.value as? String
        let start = scrubber.coordinate(withNormalizedOffset: CGVector(dx: 0.15, dy: 0.5))
        start.press(forDuration: 0.05, thenDragTo: start.withOffset(CGVector(dx: scrubber.frame.width * 0.25, dy: 180)),
                    withVelocity: .fast, thenHoldForDuration: 0)
        XCTAssertTrue(title.exists && title.isHittable, "Seeking must leave the player open")
        XCTAssertNotEqual(scrubber.value as? String, valueBefore, "The drag must actually seek")
        let valueAfter = scrubber.value as? String
        app.buttons["dulcet.now-playing.close"].firstMatch.tap()
        XCTAssertTrue(title.waitForNonExistence(timeout: 5))
        app.buttons["dulcet.mini-player.open"].firstMatch.tap()
        XCTAssertTrue(title.waitForExistence(timeout: 5))
        XCTAssertEqual(scrubber.value as? String, valueAfter,
                       "The seek must survive reopening, not just change a drag preview")
    }

    /// Slow, short gestures return to their starting state and leave the next gesture usable.
    @MainActor
    func testNowPlayingShortDragsReturnAndCanBeRepeated() {
        let app = prepareGesturePlayer()
        let bar = app.buttons["dulcet.mini-player.open"].firstMatch
        let title = app.staticTexts["dulcet.now-playing.title"].firstMatch
        for _ in 0..<2 {
            let up = bar.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.5))
            up.press(forDuration: 0.05, thenDragTo: up.withOffset(CGVector(dx: 0, dy: -65)),
                     withVelocity: .slow, thenHoldForDuration: 0.5)
            XCTAssertTrue(title.waitForNonExistence(timeout: 3), "An early release must return to the mini-player")
            XCTAssertTrue(self.playerElementIsHittable(bar))
            bar.tap()
            XCTAssertTrue(title.waitForExistence(timeout: 5))
            let down = title.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.5))
            down.press(forDuration: 0.05, thenDragTo: down.withOffset(CGVector(dx: 0, dy: 65)),
                       withVelocity: .slow, thenHoldForDuration: 0.5)
            XCTAssertTrue(title.exists && title.isHittable, "An early release must return to the full player")
            app.buttons["dulcet.now-playing.close"].firstMatch.tap()
            XCTAssertTrue(title.waitForNonExistence(timeout: 5))
        }
    }

    /// The queue retains its own scrolling pan, including a downward drag that could otherwise
    /// look like dismissal. Its rows are a known positive before the exclusion is exercised.
    @MainActor
    func testNowPlayingQueueDragDoesNotDismiss() {
        let app = prepareGesturePlayer()
        app.buttons["dulcet.mini-player.open"].firstMatch.tap()
        let title = app.staticTexts["dulcet.now-playing.title"].firstMatch
        XCTAssertTrue(title.waitForExistence(timeout: 5))
        guard let width = settledWindowWidth(app.windows.firstMatch) else { return }
        if width < 700 {
            app.buttons["dulcet.now-playing.up-next"].firstMatch.tap()
        }
        let row = app.buttons.matching(NSPredicate(format: "label CONTAINS %@", "Disc 1 Track 1")).firstMatch
        XCTAssertTrue(row.waitForExistence(timeout: 5), "The queue must actually have a row before dragging")
        let start = row.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.5))
        start.press(forDuration: 0.05, thenDragTo: start.withOffset(CGVector(dx: 0, dy: 240)),
                    withVelocity: .fast, thenHoldForDuration: 0)
        XCTAssertTrue(app.buttons["dulcet.now-playing.close"].firstMatch.exists,
                      "A queue drag must leave the presentation open")
    }

    /// The same distance cancels at rest and finishes when flicked: distance alone is insufficient.
    @MainActor
    func testNowPlayingFastShortCloseUsesVelocity() {
        let app = prepareGesturePlayer()
        app.buttons["dulcet.mini-player.open"].firstMatch.tap()
        let title = app.staticTexts["dulcet.now-playing.title"].firstMatch
        XCTAssertTrue(title.waitForExistence(timeout: 5))
        let slow = title.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.5))
        slow.press(forDuration: 0.05, thenDragTo: slow.withOffset(CGVector(dx: 0, dy: 120)),
                   withVelocity: .slow, thenHoldForDuration: 0.5)
        print("NP_CANCEL window=\(app.windows.firstMatch.frame) title=\(title.frame) hittable=\(title.isHittable) state=\(app.state.rawValue)")
        let canceled = XCTAttachment(screenshot: app.screenshot())
        canceled.name = "Player after short close cancellation"
        canceled.lifetime = .keepAlways
        add(canceled)
        XCTAssertTrue(title.exists && title.isHittable, "A short drag released at rest must return")
        let fast = title.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.5))
        fast.press(forDuration: 0.05, thenDragTo: fast.withOffset(CGVector(dx: 0, dy: 120)),
                   withVelocity: .fast, thenHoldForDuration: 0)
        XCTAssertTrue(title.waitForNonExistence(timeout: 5), "A short fast flick must complete dismissal")
    }

    /// Uses the actual system preference, with restoration, rather than a simulated app flag.
    @MainActor
    func testNowPlayingReduceMotionKeepsGesturesUsable() {
        let settings = XCUIApplication(bundleIdentifier: "com.apple.Preferences")
        settings.launch()
        let accessibility = settings.staticTexts["Accessibility"].firstMatch
        XCTAssertTrue(scrollIntoView(accessibility, in: settings, probingBlockingSystemAlerts: false))
        accessibility.tap()
        let motion = settings.staticTexts["Motion"].firstMatch
        XCTAssertTrue(motion.waitForExistence(timeout: 5))
        motion.tap()
        let reduce = settings.switches["Reduce Motion"].firstMatch
        XCTAssertTrue(reduce.waitForExistence(timeout: 5))
        let switchFrame = reduce.frame
        let original = reduce.value as? String
        if original != "1" {
            reduce.coordinate(withNormalizedOffset: .zero)
                    .withOffset(CGVector(dx: reduce.frame.width - 32, dy: reduce.frame.height / 2)).tap()
        }
        print("NP_REDUCE frame=\(reduce.frame) original=\(original ?? "nil") after=\(reduce.value as? String ?? "nil")")
        XCTAssertEqual(reduce.value as? String, "1", "The actual Settings switch must be on")
        defer {
            // Settings can be terminated while our app is foreground. Navigate afresh instead
            // of assuming that activation preserves its Motion page.
            settings.terminate()
            settings.launch()
            XCTAssertTrue(scrollIntoView(accessibility, in: settings, probingBlockingSystemAlerts: false))
            accessibility.tap()
            XCTAssertTrue(motion.waitForExistence(timeout: 5))
            motion.tap()
            XCTAssertTrue(reduce.waitForExistence(timeout: 5))
            // Activation can expose the app-switch animation's half-size snapshot. Wait for
            // the original rendered bounds before deriving an absolute thumb inset.
            let restoredFrame = XCTNSPredicateExpectation(predicate: NSPredicate { _, _ in
                reduce.exists && reduce.isHittable && reduce.frame == switchFrame
            }, object: nil)
            XCTAssertEqual(XCTWaiter.wait(for: [restoredFrame], timeout: 5), .completed,
                           "Settings must restore the switch's full-size frame before toggling")
            if reduce.value as? String != original {
                reduce.coordinate(withNormalizedOffset: .zero)
                    .withOffset(CGVector(dx: reduce.frame.width - 32, dy: reduce.frame.height / 2)).tap()
            }
            XCTAssertEqual(reduce.value as? String, original, "Restore the system preference after the proof")
            settings.terminate()
        }
        let app = prepareGesturePlayer(additionalArguments: ["-dulcet-debug-ui-markers"])
        let marker = app.staticTexts["dulcet.debug.ui-markers"].firstMatch
        let enabled = XCTNSPredicateExpectation(predicate: NSPredicate { _, _ in
            marker.exists && marker.label.contains("reduce-motion:true")
        }, object: nil)
        XCTAssertEqual(XCTWaiter.wait(for: [enabled], timeout: 5), .completed,
                       "The production app must observe the real system Reduce Motion preference")
        let up = app.buttons["dulcet.mini-player.open"].firstMatch.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.5))
        up.press(forDuration: 0.05, thenDragTo: up.withOffset(CGVector(dx: 0, dy: -app.windows.firstMatch.frame.height * 0.55)),
                 withVelocity: .slow, thenHoldForDuration: 0)
        let title = app.staticTexts["dulcet.now-playing.title"].firstMatch
        XCTAssertTrue(title.waitForExistence(timeout: 5))
        let down = title.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.5))
        down.press(forDuration: 0.05, thenDragTo: down.withOffset(CGVector(dx: 0, dy: app.windows.firstMatch.frame.height * 0.3)),
                   withVelocity: .fast, thenHoldForDuration: 0)
        XCTAssertTrue(title.waitForNonExistence(timeout: 5), "Reduce Motion must preserve opening and dismissal")
    }

    @MainActor
    private func prepareGesturePlayer(additionalArguments: [String] = []) -> XCUIApplication {
        XCUIDevice.shared.orientation = .portrait
        let app = XCUIApplication()
        app.launchArguments = ["-dulcet-account-connect-layout-fixture", "-dulcet-debug-player-frame-metrics"] + additionalArguments
        app.launch()
        guard let width = settledWindowWidth(app.windows.firstMatch) else { return app }
        let compact = width < 700
        XCTAssertTrue(openDestination("Library", sidebarIdentifier: "dulcet.sidebar.library", in: app, compact: compact))
        if !compact {
            XCTAssertTrue(app.staticTexts["Albums"].firstMatch.waitForExistence(timeout: 5),
                          "The regular fixture must show its Albums grid before playing")
        }
        let album = app.buttons.matching(NSPredicate(format: "label BEGINSWITH %@", "Double Lines")).firstMatch
        XCTAssertTrue(album.waitForExistence(timeout: 10))
        XCTAssertTrue(scrollIntoView(album, in: app, probingBlockingSystemAlerts: false))
        album.tap()
        let track = app.buttons.matching(NSPredicate(format: "label BEGINSWITH %@", "Disc 1 Track 1")).firstMatch
        XCTAssertTrue(track.waitForExistence(timeout: 10))
        track.tap()
        let pause = app.buttons["dulcet.mini-player.play-pause"].firstMatch
        XCTAssertTrue(pause.waitForExistence(timeout: 10))
        pause.tap()
        XCTAssertTrue(waitForLabel("Play", of: pause, timeout: 5))
        return app
    }
}
