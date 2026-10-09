import AppKit
import CryptoKit
import DulcetCore
import SwiftUI
import XCTest
@testable import DulcetKit
@testable import DulcetMac

// The Mac app hosted in a test: the production root view in a window over the production
// controller, reader and settings, each with storage of the run's own, driven by accessibility;
// and the disposable server read directly over `/rest`. Shared by the hosted Mac proofs.

enum HostedAppError: Error {
    case refused
    case unexpected(String)
}

// MARK: - The hosted app

/// The production root view in a window, over the production controller, reader and setting,
/// each with storage of this run's own.
@MainActor
final class HostedApp {
    let store: DulcetPresentationStore
    let streamingQuality: DulcetCoreStreamingQuality
    private let session: DulcetLibrarySession
    private let controller: DulcetCorePlaybackController
    private let window: NSWindow
    let hostingView: NSView
    private let defaultsSuite: String
    private let previousEnhancedUI: Any

    /// `run` names the run's storage: a second app with the same `run` reopens the first's reader
    /// and playback databases and account, as a relaunch does. `toolbar` bridges the root view's
    /// toolbar into the window's, as the app's window scene does, for a proof of what the Mac
    /// draws there. `downloadController` stands in for the device's downloads, for a proof of
    /// what the rows offer and show; with none, the app keeps no downloads.
    init(serverURL: String, username: String, password: String, run: String = UUID().uuidString,
         toolbar: Bool = false, downloadController: (any DulcetDownloadControlling)? = nil) async throws {
        defaultsSuite = "dulcet-mac-hosted-\(run)"
        let defaults = try XCTUnwrap(UserDefaults(suiteName: defaultsSuite))
        streamingQuality = DulcetCoreStreamingQuality(defaults: defaults)
        controller = DulcetCorePlaybackController(databaseName: "dulcet-mac-hosted-playback-\(run).db", downloadController: downloadController,
                                                 artworkFetcher: DulcetCoreArtworkFetcher())
        controller.streamingQuality = streamingQuality
        session = DulcetLibrarySession(factory: DulcetCoreLibraryReaderFactory(databaseName: "dulcet-mac-hosted-reader-\(run).db", readsDownloads: downloadController != nil))
        let providerInstanceID = "macos-hosted-\(run)"
        store = DulcetPresentationStore(source: DulcetAccountDataSource(
            connector: DulcetCoreAccountConnector(),
            credentialStore: HostedCredentialStore(),
            playbackController: controller,
            downloadController: downloadController,
            providerInstanceIDFactory: { providerInstanceID },
            librarySession: session
        ))
        store.streamingQualitySetting = streamingQuality
        store.accountServerURL = serverURL
        store.accountUsername = username
        store.accountPassword = password
        store.accountAllowLocalHTTP = true
        store.submitAccountConnection()

        let enhancedUI = NSAccessibility.Attribute(rawValue: "AXEnhancedUserInterface")
        previousEnhancedUI = NSApp.accessibilityAttributeValue(enhancedUI) ?? false
        NSApp.accessibilitySetValue(true, forAttribute: enhancedUI)
        let hosting = NSHostingView(rootView: DulcetMacProduction.makeRootView(store: store))
        if toolbar { hosting.sceneBridgingOptions = [.toolbars] }
        hosting.frame = NSRect(x: 0, y: 0, width: 1180, height: 760)
        hostingView = hosting
        window = NSWindow(contentRect: hosting.frame, styleMask: [.titled, .closable, .resizable],
                          backing: .buffered, defer: false)
        window.isReleasedWhenClosed = false
        window.contentView = hosting
        window.makeKeyAndOrderFront(nil)

        let session = session
        let store = store
        try await waitUntil(timeout: .seconds(20),
                            "connect: state=\(store.snapshot.state) mode=\(session.mode)") {
            store.snapshot.accountConnected && session.mode == .connected && session.reader != nil
        }
    }

    /// Keeps the open reader page while putting both library and playback into device-only
    /// operation, as a saved launch does before Reconnect.
    func enterDeviceOnlyMode() {
        session.disconnect()
        guard let saved = session.account else { return XCTFail("The hosted reader must have its account") }
        controller.configureOffline(account: DulcetPlaybackAccount(
            providerInstanceID: saved.providerInstanceID, normalizedServerURL: saved.normalizedServerURL,
            username: saved.username, password: saved.password, allowLocalHTTP: saved.allowLocalHTTP
        ))
    }

    func close() {
        controller.disconnect()
        window.orderOut(nil)
        NSApp.accessibilitySetValue(previousEnhancedUI, forAttribute: NSAccessibility.Attribute(rawValue: "AXEnhancedUserInterface"))
        UserDefaults.standard.removePersistentDomain(forName: defaultsSuite)
    }

    /// Search from the sidebar, the query typed into the field, the track's row selected and
    /// Return pressed; then the now-playing bar pressed, opening Now Playing on that track.
    func play(query: String, track: String) async throws {
        let destination = try await element(identifiedBy: "dulcet.sidebar.search", timeout: .seconds(10))
        _ = try selectTableRow(destination)
        try await waitUntil(timeout: .seconds(5), "search destination: \(store.selectedDestination)") {
            self.store.selectedDestination == .search
        }
        let fieldElement = try await element(identifiedBy: "dulcet.search.field", timeout: .seconds(10))
        let field = try XCTUnwrap(
            (fieldElement as? NSTextField) ?? (fieldElement as? NSCell)?.controlView as? NSTextField,
            "dulcet.search.field must resolve to NSTextField; observed \(type(of: fieldElement))"
        )
        // Typed keys go to the key window; a Settings window an earlier step made key would take them.
        window.makeKeyAndOrderFront(nil)
        print("OBSERVED play: app window key=\(window.isKeyWindow) keyWindow=\(NSApp.keyWindow?.title ?? "nil")")
        XCTAssertTrue(window.makeFirstResponder(field), "The search field must take focus")
        // A query an earlier step typed is selected, so the typing replaces it.
        field.currentEditor()?.selectAll(nil)
        try sendText(query)
        try await waitUntil(timeout: .seconds(5), "typed=\(query.debugDescription) bound=\(store.searchQuery.debugDescription)") {
            self.store.searchQuery == query
        }
        let row = try await element(timeout: .seconds(30), described: "the \(track) search row") {
            (self.identifier($0) ?? "").hasPrefix("dulcet.search.result.")
                && (self.label($0) ?? "").hasPrefix("\(track), ") && (self.label($0) ?? "").hasSuffix(", Track")
        }
        let table = try selectTableRow(row)
        XCTAssertTrue(window.makeFirstResponder(table), "The results must take focus")
        try sendReturn()
        try await waitUntil(timeout: .seconds(15), "now playing=\(String(describing: store.snapshot.nowPlaying?.current.title))") {
            self.store.snapshot.nowPlaying?.current.title == track
        }
        let bar = try await element(identifiedBy: "dulcet.mini-player.open", timeout: .seconds(10))
        try press(bar, named: "dulcet.mini-player.open")
        try await waitUntil(timeout: .seconds(5), "state=\(store.snapshot.state)") {
            self.store.snapshot.state == .nowPlaying
        }
        // In one column (a window narrowed to its screen) lyrics left open take the player's place,
        // and the title returns when they are hidden.
        if await element(identifiedBy: "dulcet.now-playing.title", within: .seconds(10)) == nil,
           let toggle = firstElement(where: { self.identifier($0) == "dulcet.now-playing.lyrics" }),
           label(toggle) == "Hide Lyrics" {
            try press(toggle, named: "dulcet.now-playing.lyrics")
        }
        let title = try await element(identifiedBy: "dulcet.now-playing.title", timeout: .seconds(10))
        XCTAssertEqual(label(title), track, "Now Playing must show \(track)")
    }

    /// Presses Now Playing's lyrics toggle when lyrics are not already shown.
    func showLyrics() async throws {
        let toggle = try await element(identifiedBy: "dulcet.now-playing.lyrics", timeout: .seconds(10))
        if label(toggle) == "Show Lyrics" {
            try press(toggle, named: "dulcet.now-playing.lyrics")
        }
        _ = try await element(identifiedBy: "dulcet.lyrics.panel", timeout: .seconds(10))
    }

    /// The Settings scene's content in a window of its own, as Dulcet > Settings… shows it.
    func openSettingsWindow() -> NSWindow {
        let hosting = NSHostingView(rootView: DulcetSettingsView(store: store))
        hosting.frame = NSRect(x: 0, y: 0, width: 480, height: 320)
        let settings = NSWindow(contentRect: hosting.frame, styleMask: [.titled, .closable], backing: .buffered, defer: false)
        settings.isReleasedWhenClosed = false
        settings.contentView = hosting
        // Key and active, as Dulcet > Settings… leaves it: a menu picker opens its menu on AXPress
        // only in a key window of the active app, and a CI host's test runner is not active.
        NSApp.activate(ignoringOtherApps: true)
        settings.makeKeyAndOrderFront(nil)
        hosting.layoutSubtreeIfNeeded()
        return settings
    }

    /// Chooses `option` in the identified menu picker the way a click does: AXPress opens its
    /// menu, and the item titled `option` is performed from inside the menu's tracking, which then
    /// ends. The picker must read back the option afterwards.
    ///
    /// SwiftUI draws the picker without an AppKit pop-up button (OBSERVED on macOS 26: the
    /// identifier is on an `AccessibilityNode` and no `NSPopUpButton` exists in the window), so the
    /// menu is the only AppKit object a choice goes through.
    func choose(_ option: String, picker identifier: String, in settings: NSWindow) async throws {
        let root = try XCTUnwrap(settings.contentView)
        let picker = try await element(timeout: .seconds(10), described: identifier, in: root) {
            self.identifier($0) == identifier
        }
        // Main-thread only: the notification and the run-loop block both arrive on the main thread.
        final class Tracking: @unchecked Sendable {
            var offered: [String] = []
            var performed = false
            var opened = false
            var ended = false
            var menu: NSMenu?
        }
        let tracking = Tracking()
        let observer = NotificationCenter.default.addObserver(
            forName: NSMenu.didBeginTrackingNotification, object: nil, queue: nil
        ) { notification in
            guard let menu = notification.object as? NSMenu else { return }
            tracking.opened = true
            tracking.offered = menu.items.map(\.title)
            tracking.menu = menu
            // Performed once tracking runs, in the run-loop mode tracking uses.
            RunLoop.main.perform(inModes: [.eventTracking, .common]) {
                MainActor.assumeIsolated {
                    guard let menu = tracking.menu else { return }
                    if let index = menu.items.firstIndex(where: { $0.title == option }) {
                        menu.performActionForItem(at: index)
                        tracking.performed = true
                    }
                    menu.cancelTracking()
                }
            }
        }
        let endObserver = NotificationCenter.default.addObserver(
            forName: NSMenu.didEndTrackingNotification, object: nil, queue: nil
        ) { notification in
            if tracking.menu != nil, notification.object as? NSMenu === tracking.menu { tracking.ended = true }
        }
        defer {
            NotificationCenter.default.removeObserver(observer)
            NotificationCenter.default.removeObserver(endObserver)
        }
        // AXPress returns before the menu opens: SwiftUI opens it on a later turn of the main run
        // loop (OBSERVED on macOS 26), which the wait below spins.
        // On the CI macOS host a press can be dropped (OBSERVED: the second picker's AXPress opened
        // no menu while the first picker's had), so the press is repeated, up to three times, until a
        // menu begins tracking. Every choice goes through the opened menu; there is no other path.
        var presses = 0
        while presses < 3 {
            presses += 1
            try press(picker, named: identifier)
            if await poll(for: .seconds(5), { tracking.opened }) { break }
        }
        try await waitUntil(timeout: .seconds(1), "\(identifier) opens its menu on AXPress (\(presses) presses)") {
            tracking.opened
        }
        print("OBSERVED \(identifier): menu opened on press \(presses)")
        XCTAssertTrue(tracking.offered.contains(option), "\(identifier) must offer \(option); offers \(tracking.offered)")
        // The menu must have finished closing before anything else is pressed.
        try await waitUntil(timeout: .seconds(5), "\(identifier)'s menu ends tracking") { tracking.ended }
        try await waitUntil(timeout: .seconds(5), "\(identifier) shows \(option); reads \(self.label(picker) ?? "nil")") {
            root.layoutSubtreeIfNeeded()
            return tracking.performed && ((self.label(picker) ?? "").contains(option)
                || (self.value("accessibilityValue", of: picker) as? String)?.contains(option) == true)
        }
    }

    private func poll(for timeout: Duration, _ condition: () -> Bool) async -> Bool {
        let deadline = ContinuousClock.now.advanced(by: timeout)
        repeat {
            if condition() { return true }
            try? await Task.sleep(for: .milliseconds(50))
        } while ContinuousClock.now < deadline
        return condition()
    }

    // MARK: The window's toolbar

    /// The identified element anywhere in the window, its toolbar included: the toolbar is drawn
    /// outside the content view, in the window's frame view.
    func windowElement(identifiedBy identifier: String, timeout: Duration) async throws -> Any {
        let frameView = try XCTUnwrap(window.contentView?.superview, "The window must have a frame view")
        return try await element(timeout: timeout, described: identifier, in: frameView) { self.identifier($0) == identifier }
    }

    /// The first element anywhere in the window, its toolbar included, that matches; nil if none.
    func windowFirstElement(where matches: (Any) -> Bool) -> Any? {
        guard let frameView = window.contentView?.superview else { return nil }
        frameView.layoutSubtreeIfNeeded()
        return descendants(of: frameView).first(where: matches)
    }

    /// A left click at a point on screen, sent to the window as the event a mouse makes, so it
    /// reaches whichever control is drawn there by the window's own hit testing. The mouse-up is
    /// queued before the mouse-down is sent: a control that tracks the mouse (a toolbar's
    /// NSButton) takes its mouse-up from the event queue inside the mouse-down's `sendEvent`, and
    /// one sent after that call returns never arrives.
    func click(atScreenPoint point: NSPoint) throws {
        let location = window.convertPoint(fromScreen: point)
        func event(_ type: NSEvent.EventType) throws -> NSEvent {
            try XCTUnwrap(NSEvent.mouseEvent(
                with: type, location: location, modifierFlags: [], timestamp: ProcessInfo.processInfo.systemUptime,
                windowNumber: window.windowNumber, context: nil, eventNumber: 0, clickCount: 1, pressure: type == .leftMouseDown ? 1 : 0
            ))
        }
        let down = try event(.leftMouseDown)
        NSApp.postEvent(try event(.leftMouseUp), atStart: false)
        window.sendEvent(down)
    }

    // MARK: The sidebar, menus and alerts

    /// Selects the identified sidebar row the way a click does: the row the window's table draws
    /// at that element's centre.
    func selectSidebarRow(identifiedBy identifier: String) async throws {
        let row = try await element(identifiedBy: identifier, timeout: .seconds(10))
        _ = try selectTableRow(row)
    }

    /// A secondary click at the element's centre, sent to the window as a mouse's, so the context
    /// menu of whatever the window hit-tests there opens.
    func rightClick(_ element: Any) throws {
        let screenFrame = try frame(element)
        let location = window.convertPoint(fromScreen: NSPoint(x: screenFrame.midX, y: screenFrame.midY))
        for type in [NSEvent.EventType.rightMouseDown, .rightMouseUp] {
            let made = try XCTUnwrap(NSEvent.mouseEvent(
                with: type, location: location, modifierFlags: [], timestamp: ProcessInfo.processInfo.systemUptime,
                windowNumber: window.windowNumber, context: nil, eventNumber: 0, clickCount: 1, pressure: type == .rightMouseDown ? 1 : 0
            ))
            // A made event says button 0, the primary, whatever its type; macOS 26 then takes it
            // as a click and opens the tile. A real secondary click says button 1.
            let secondary = try XCTUnwrap(made.cgEvent)
            secondary.setIntegerValueField(.mouseEventButtonNumber, value: 1)
            window.sendEvent(try XCTUnwrap(NSEvent(cgEvent: secondary)))
        }
    }

    /// Opens a menu with `open` -- a secondary click, or AXPress on a menu button -- and performs
    /// the item titled `title` from inside the menu's tracking, which then ends. Returns what the
    /// menu offered. A context menu tracks inside the click's `sendEvent`; a SwiftUI menu button
    /// opens on a later turn of the run loop; the block below runs in either.
    @discardableResult
    /// `attempts` above 1 opens again when no menu began tracking within two seconds: a press on an
    /// element the view replaced reaches nothing and opens no menu, so pressing again is harmless.
    func chooseMenuItem(
        _ title: String, described description: String, attempts: Int = 1,
        opening open: () async throws -> Void
    ) async throws -> [String] {
        final class Tracking: @unchecked Sendable {
            var offered: [String] = []
            var performed = false
            var opened = false
            var menu: NSMenu?
        }
        let tracking = Tracking()
        let observer = NotificationCenter.default.addObserver(
            forName: NSMenu.didBeginTrackingNotification, object: nil, queue: nil
        ) { notification in
            guard !tracking.opened, let menu = notification.object as? NSMenu else { return }
            tracking.opened = true
            tracking.offered = menu.items.map(\.title)
            tracking.menu = menu
            RunLoop.main.perform(inModes: [.eventTracking, .common]) {
                MainActor.assumeIsolated {
                    guard let menu = tracking.menu else { return }
                    if let index = menu.items.firstIndex(where: { $0.title == title && $0.isEnabled }) {
                        menu.performActionForItem(at: index)
                        tracking.performed = true
                    }
                    menu.cancelTracking()
                }
            }
        }
        defer { NotificationCenter.default.removeObserver(observer) }
        do {
            for attempt in 1..<attempts {
                try await open()
                if await poll(for: .seconds(2), { tracking.opened }) { break }
                print("DULCET MAC MENU RETRY \(description) attempt=\(attempt) opened=false")
            }
            if !tracking.opened {
                try await open()
                try await waitUntil(timeout: .seconds(5), "\(description) opens a menu") { tracking.opened }
            }
        } catch {
            attachWindowImage(named: "\(description) did not open a menu")
            throw error
        }
        try await waitUntil(timeout: .seconds(5), "\(description) performs \(title.debugDescription); offers \(tracking.offered)") {
            tracking.performed
        }
        return tracking.offered
    }

    /// The window's content as a PNG attached to the running test, for a failure that only CI shows.
    func attachWindowImage(named name: String) {
        guard let view = window.contentView, let rep = view.bitmapImageRepForCachingDisplay(in: view.bounds) else { return }
        view.cacheDisplay(in: view.bounds, to: rep)
        guard let png = rep.representation(using: .png, properties: [:]) else { return }
        let attachment = XCTAttachment(data: png, uniformTypeIdentifier: "public.png")
        attachment.name = name
        attachment.lifetime = .keepAlways
        XCTContext.runActivity(named: name) { $0.add(attachment) }
    }

    /// The alert the window shows as a sheet: `text` typed into its field when given, then the
    /// button titled `button` pressed; waits for the sheet to go. Returns the alert's static texts.
    @discardableResult
    func answerAlert(typing text: String? = nil, pressing button: String, described description: String) async throws -> [String] {
        try await waitUntil(timeout: .seconds(10), "\(description): the window must show an alert sheet") {
            self.window.attachedSheet != nil
        }
        let sheet = try XCTUnwrap(window.attachedSheet)
        let root = try XCTUnwrap(sheet.contentView?.superview ?? sheet.contentView)
        root.layoutSubtreeIfNeeded()
        let views = descendants(of: root).compactMap { $0 as? NSView }
        let texts = views.compactMap { ($0 as? NSTextField).flatMap { $0.isEditable ? nil : $0.stringValue } }.filter { !$0.isEmpty }
        if let text {
            let field = try XCTUnwrap(views.compactMap { $0 as? NSTextField }.first { $0.isEditable },
                "\(description): the alert must hold a text field; texts \(texts)")
            XCTAssertTrue(sheet.makeFirstResponder(field), "\(description): the alert's field must take focus")
            field.currentEditor()?.selectAll(nil)
            try sendText(text, to: sheet)
        }
        let buttons = views.compactMap { $0 as? NSButton }
        let target = try XCTUnwrap(buttons.first { $0.title == button },
            "\(description): the alert must offer \(button.debugDescription); offers \(buttons.map(\.title)) texts \(texts)")
        XCTAssertTrue(target.isEnabled, "\(description): \(button.debugDescription) must be enabled")
        target.performClick(nil)
        try await waitUntil(timeout: .seconds(10), "\(description): the alert must close") { self.window.attachedSheet == nil }
        return texts
    }

    /// The sheet the window shows -- a chooser, not an alert -- searched like the window.
    func sheetElement(timeout: Duration, described description: String, where matches: @escaping (Any) -> Bool) async throws -> Any {
        try await waitUntil(timeout: timeout, "\(description): the window must show a sheet") { self.window.attachedSheet != nil }
        let sheet = try XCTUnwrap(window.attachedSheet)
        let root = try XCTUnwrap(sheet.contentView?.superview ?? sheet.contentView)
        return try await element(timeout: timeout, described: description, in: root, where: matches)
    }

    /// Whether the window shows a sheet.
    var showsSheet: Bool { window.attachedSheet != nil }

    /// Performs the element's accessibility custom action named `name`, as VoiceOver's Actions
    /// menu does. Returns the names the element offered.
    @discardableResult
    func performCustomAction(_ name: String, on element: Any, described description: String) throws -> [String] {
        let actions = (value("accessibilityCustomActions", of: element) as? [NSAccessibilityCustomAction]) ?? []
        let action = try XCTUnwrap(actions.first { $0.name == name },
            "\(description) must offer the action \(name.debugDescription); offers \(actions.map(\.name))")
        if let handler = action.handler {
            XCTAssertTrue(handler(), "\(description): \(name.debugDescription) must succeed")
        } else {
            let target = try XCTUnwrap(action.target as? NSObject, "\(description): \(name.debugDescription) has no handler or target")
            _ = target.perform(try XCTUnwrap(action.selector), with: action)
        }
        return actions.map(\.name)
    }

    /// Every element anywhere in the window, its toolbar included, that matches.
    func windowElements(where matches: (Any) -> Bool) -> [Any] {
        guard let frameView = window.contentView?.superview else { return [] }
        frameView.layoutSubtreeIfNeeded()
        return descendants(of: frameView).filter(matches)
    }

    // MARK: Accessibility

    func element(identifiedBy identifier: String, timeout: Duration) async throws -> Any {
        try await element(timeout: timeout, described: identifier) { self.identifier($0) == identifier }
    }

    func element(
        timeout: Duration,
        described description: String,
        in root: NSView? = nil,
        where matches: (Any) -> Bool
    ) async throws -> Any {
        let root = root ?? hostingView
        let deadline = ContinuousClock.now.advanced(by: timeout)
        repeat {
            root.layoutSubtreeIfNeeded()
            if let match = descendants(of: root).first(where: matches) { return match }
            try await Task.sleep(for: .milliseconds(100))
        } while ContinuousClock.now < deadline
        let tree = descendants(of: root).compactMap { element -> String? in
            guard let id = identifier(element), id.hasPrefix("dulcet.") else { return nil }
            return "\(id)=\(label(element) ?? "nil")"
        }
        XCTFail("\(description) never appeared; dulcet elements: \(tree.joined(separator: "; "))")
        throw HostedAppError.unexpected(description)
    }

    /// The hosted window's content width, after AppKit fitted the window to the screen.
    var contentWidth: CGFloat { hostingView.bounds.width }

    /// The element with this identifier if it appears within `timeout`; nil (no failure) if not.
    func element(identifiedBy id: String, within timeout: Duration) async -> Any? {
        let deadline = ContinuousClock.now.advanced(by: timeout)
        repeat {
            if let match = firstElement(where: { identifier($0) == id }) { return match }
            try? await Task.sleep(for: .milliseconds(100))
        } while ContinuousClock.now < deadline
        return nil
    }

    func firstElement(where matches: (Any) -> Bool) -> Any? {
        hostingView.layoutSubtreeIfNeeded()
        return descendants(of: hostingView).first(where: matches)
    }

    func press(_ element: Any, named name: String) throws {
        let press = #selector(NSAccessibilityProtocol.accessibilityPerformPress)
        let object = try XCTUnwrap(element as? NSObject, "\(name) is not an accessibility object")
        XCTAssertTrue(object.responds(to: press), "\(name) does not answer AXPress")
        _ = object.perform(press)
    }

    /// The window's content area in screen coordinates, the space `frame(_:)` reports in.
    var contentScreenFrame: NSRect { window.convertToScreen(window.contentLayoutRect) }

    func frame(_ element: Any) throws -> NSRect {
        let accessible = try XCTUnwrap(element as? any NSAccessibilityElementProtocol, "no frame on \(type(of: element))")
        return accessible.accessibilityFrame()
    }

    func identifier(_ element: Any) -> String? {
        value("accessibilityIdentifier", of: element) as? String
    }

    /// A menu button's title arrives as an attributed string, not a string.
    func label(_ element: Any) -> String? {
        ["accessibilityLabel", "accessibilityTitle", "accessibilityValue"].lazy.compactMap { name -> String? in
            let found = self.value(name, of: element)
            return found as? String ?? (found as? NSAttributedString)?.string
        }.first
    }

    func value(_ name: String, of element: Any) -> Any? {
        guard let object = element as? NSObject else { return nil }
        let selector = NSSelectorFromString(name)
        guard object.responds(to: selector) else { return nil }
        return object.perform(selector)?.takeUnretainedValue()
    }

    private func descendants(of root: Any) -> [Any] {
        var result: [Any] = []
        var visited = Set<ObjectIdentifier>()
        var pending: [Any] = [root]
        while let current = pending.popLast() {
            guard let object = current as AnyObject? else { continue }
            guard visited.insert(ObjectIdentifier(object)).inserted else { continue }
            var children = value("accessibilityChildren", of: current) as? [Any] ?? []
            if let view = current as? NSView { children.append(contentsOf: view.subviews) }
            result.append(current)
            pending.append(contentsOf: children)
        }
        return result
    }

    private func selectTableRow(_ element: Any) throws -> NSTableView {
        let screenFrame = try frame(element)
        let point = window.convertPoint(fromScreen: NSPoint(x: screenFrame.midX, y: screenFrame.midY))
        let content = try XCTUnwrap(window.contentView)
        // The view hit can be one drawn inside the row (a section header's own control) rather than
        // the table; the row belongs to the table that encloses it, or else to the table drawn there.
        let hit = content.hitTest(content.convert(point, from: nil))
        let enclosing = sequence(first: hit, next: { $0?.superview }).lazy.compactMap { $0 as? NSTableView }.first
        let drawn = descendants(of: content).lazy.compactMap { $0 as? NSTableView }
            .first { $0.convert($0.bounds, to: nil).contains(point) }
        if !(hit is NSTableView) {
            print("OBSERVED selectTableRow \(identifier(element) ?? "nil"): hit \(hit.map { String(describing: type(of: $0)) } ?? "nil")"
                + " at \(point); enclosing table=\(enclosing != nil) drawn table=\(drawn != nil)")
        }
        let table = try XCTUnwrap(enclosing ?? drawn,
            "Expected a table at \(point) for \(identifier(element) ?? "nil"); hit \(hit.map { String(describing: type(of: $0)) } ?? "nil")")
        let index = table.row(at: table.convert(point, from: nil))
        let rows = try XCTUnwrap(value("accessibilityRows", of: table) as? [Any])
        guard rows.indices.contains(index) else {
            throw HostedAppError.unexpected("\(identifier(element) ?? "nil"): row \(index) of \(rows.count)")
        }
        table.perform(NSSelectorFromString("setAccessibilitySelectedRows:"), with: [rows[index]])
        XCTAssertEqual(table.selectedRow, index, "Accessibility selection for \(identifier(element) ?? "nil")")
        return table
    }

    private func sendText(_ text: String, to target: NSWindow? = nil) throws {
        let target = target ?? window
        for character in text {
            let value = String(character)
            for type in [NSEvent.EventType.keyDown, .keyUp] {
                let event = try XCTUnwrap(NSEvent.keyEvent(
                    with: type, location: .zero, modifierFlags: [], timestamp: ProcessInfo.processInfo.systemUptime,
                    windowNumber: target.windowNumber, context: nil, characters: value,
                    charactersIgnoringModifiers: value, isARepeat: false, keyCode: 0
                ))
                NSApp.sendEvent(event)
            }
        }
    }

    private func sendReturn() throws {
        for type in [NSEvent.EventType.keyDown, .keyUp] {
            let event = try XCTUnwrap(NSEvent.keyEvent(
                with: type, location: .zero, modifierFlags: [], timestamp: ProcessInfo.processInfo.systemUptime,
                windowNumber: window.windowNumber, context: nil, characters: "\r",
                charactersIgnoringModifiers: "\r", isARepeat: false, keyCode: 36
            ))
            NSApp.sendEvent(event)
        }
    }
}

@MainActor
func waitUntil(
    timeout: Duration,
    _ failureMessage: @autoclosure () -> String,
    condition: @MainActor () -> Bool
) async throws {
    let deadline = ContinuousClock.now.advanced(by: timeout)
    while !condition() {
        if ContinuousClock.now >= deadline {
            XCTFail(failureMessage())
            throw HostedAppError.unexpected(failureMessage())
        }
        try await Task.sleep(for: .milliseconds(50))
    }
}

/// An in-memory credential store that remembers the provider instance, as the Keychain store does.
final class HostedCredentialStore: DulcetProviderInstanceCredentialStoring {
    private var persisted: DulcetAccountConnectRequest?
    private(set) var providerInstanceID: String?
    private(set) var credentialGeneration: Int64 = 0

    func load() throws -> DulcetAccountConnectRequest? { persisted }
    func save(_ request: DulcetAccountConnectRequest) throws {
        persisted = request
        credentialGeneration += 1
    }
    func save(_ request: DulcetAccountConnectRequest, providerInstanceID: String) throws {
        persisted = request
        self.providerInstanceID = providerInstanceID
        credentialGeneration += 1
    }
    func delete() throws {
        persisted = nil
        providerInstanceID = nil
        credentialGeneration += 1
    }
}

/// The disposable server, read directly over `/rest` with its published fixture account.
struct LiveServer {
    let baseURL: String
    let username: String
    let password: String

    struct LyricsLayer {
        let synced: Bool
        let lines: [String]
    }

    func call(_ endpoint: String, _ query: [URLQueryItem]) async throws -> [String: Any] {
        let salt = (0..<16).map { _ in String(format: "%02x", UInt8.random(in: 0...255)) }.joined()
        let token = Insecure.MD5.hash(data: Data((password + salt).utf8)).map { String(format: "%02x", $0) }.joined()
        var components = try XCTUnwrap(URLComponents(string: baseURL))
        components.path = "/rest/" + endpoint
        components.queryItems = [
            URLQueryItem(name: "u", value: username), URLQueryItem(name: "t", value: token),
            URLQueryItem(name: "s", value: salt), URLQueryItem(name: "v", value: "1.16.1"),
            URLQueryItem(name: "c", value: "dulcet-mac-hosted-test"), URLQueryItem(name: "f", value: "json"),
        ] + query
        let (data, _) = try await URLSession.shared.data(from: try XCTUnwrap(components.url))
        // The URL carries a token, so a failure names the endpoint only.
        guard let document = try JSONSerialization.jsonObject(with: data) as? [String: Any],
              let envelope = document["subsonic-response"] as? [String: Any],
              envelope["status"] as? String == "ok" else {
            throw HostedAppError.unexpected("/rest/\(endpoint) did not return an ok envelope")
        }
        return envelope
    }

    func songID(_ title: String, album: String) async throws -> String {
        let result = try await call("search3", [
            URLQueryItem(name: "query", value: title), URLQueryItem(name: "songCount", value: "20"),
            URLQueryItem(name: "albumCount", value: "0"), URLQueryItem(name: "artistCount", value: "0"),
        ])
        let songs = (result["searchResult3"] as? [String: Any])?["song"] as? [[String: Any]] ?? []
        let matches = songs.filter { $0["title"] as? String == title && $0["album"] as? String == album }
        guard matches.count == 1, let id = matches[0]["id"] as? String else {
            throw HostedAppError.unexpected("\(matches.count) songs named \(title) on \(album); exactly one is required")
        }
        return id
    }

    /// The server's rating of one song, 0 when it carries none.
    func rating(_ songID: String) async throws -> Int {
        let song = try await call("getSong", [URLQueryItem(name: "id", value: songID)])["song"] as? [String: Any]
        _ = try XCTUnwrap(song, "getSong must return the song")
        // Subsonic omits `userRating` on an unrated song.
        return song?["userRating"] as? Int ?? 0
    }

    /// Whether the server holds one song as a favourite: Subsonic carries `starred` only then.
    func starred(_ songID: String) async throws -> Bool {
        let song = try await call("getSong", [URLQueryItem(name: "id", value: songID)])["song"] as? [String: Any]
        _ = try XCTUnwrap(song, "getSong must return the song")
        return song?["starred"] != nil
    }

    /// Stars or unstars one song as another client would.
    func setStarred(_ songID: String, _ starred: Bool) async throws {
        _ = try await call(starred ? "star" : "unstar", [URLQueryItem(name: "id", value: songID)])
    }

    /// Writes a rating as another client would.
    func setRating(_ songID: String, _ rating: Int) async throws {
        _ = try await call("setRating", [URLQueryItem(name: "id", value: songID), URLQueryItem(name: "rating", value: String(rating))])
    }

    struct Playlist {
        let id: String
        let name: String
    }

    func playlists() async throws -> [Playlist] {
        let list = try await call("getPlaylists", [])["playlists"] as? [String: Any]
        return (list?["playlist"] as? [[String: Any]] ?? []).compactMap { entry in
            guard let id = entry["id"] as? String, let name = entry["name"] as? String else { return nil }
            return Playlist(id: id, name: name)
        }
    }

    /// The playlist's entries' titles, in order; nil when the server holds no such playlist.
    func playlistEntries(_ id: String) async throws -> [String]? {
        guard let playlist = try? await call("getPlaylist", [URLQueryItem(name: "id", value: id)])["playlist"] as? [String: Any] else {
            return nil
        }
        return (playlist["entry"] as? [[String: Any]] ?? []).compactMap { $0["title"] as? String }
    }

    /// Makes a playlist as another client would.
    func createPlaylist(_ name: String, songID: String) async throws -> String {
        try await createPlaylist(name, songIDs: [songID])
    }

    /// Makes a playlist holding `songIDs` in that order, as another client would.
    func createPlaylist(_ name: String, songIDs: [String]) async throws -> String {
        let items = [URLQueryItem(name: "name", value: name)] + songIDs.map { URLQueryItem(name: "songId", value: $0) }
        let playlist = try await call("createPlaylist", items)["playlist"] as? [String: Any]
        return try XCTUnwrap(playlist?["id"] as? String, "createPlaylist must return the playlist's id")
    }

    func deletePlaylist(_ id: String) async throws {
        _ = try await call("deletePlaylist", [URLQueryItem(name: "id", value: id)])
    }

    func bitRate(_ songID: String) async throws -> Int {
        let song = try await call("getSong", [URLQueryItem(name: "id", value: songID)])["song"] as? [String: Any]
        return try XCTUnwrap(song?["bitRate"] as? Int, "The server must report the source bitrate")
    }

    func lyricsLayers(_ songID: String) async throws -> [LyricsLayer] {
        let list = try await call("getLyricsBySongId", [URLQueryItem(name: "id", value: songID)])["lyricsList"] as? [String: Any]
        return (list?["structuredLyrics"] as? [[String: Any]] ?? []).map { layer in
            LyricsLayer(
                synced: layer["synced"] as? Bool ?? false,
                lines: (layer["line"] as? [[String: Any]] ?? []).compactMap { $0["value"] as? String }
            )
        }
    }
}
