import AppKit
import CryptoKit
import DulcetCore
import SwiftUI
import XCTest
@testable import DulcetKit
@testable import DulcetMac

/// A screenshot tour of the macOS app for design review, against a live disposable server.
///
/// An instrument, not a proof. The production root view is hosted in a 1280x800 window over the
/// production account, reader, playback and artwork parts; only the credential store is in
/// memory, because an ad-hoc-signed test host cannot write the Keychain. Sidebar places are chosen
/// through the store, as the sidebar itself does; everything on a page is pressed through its
/// accessibility element. Each shot is the window as the screen shows it, named
/// `<nn>-<screen>`, or `<nn>-<screen>-UNREACHED` with a recorded failure when the screen was not
/// reached. `tools/design-tour-apple` runs it; it returns at once unless `DULCET_DESIGN_TOUR=1`.
@MainActor
final class DulcetMacDesignTourTest: XCTestCase {
    private let playlistName = "Design Tour Mix"
    private var shotNumber = 0
    private var hostingView: NSView?
    private var window: NSWindow?
    private var appearance: NSAppearance?

    func testDesignTour() async throws {
        let environment = ProcessInfo.processInfo.environment
        guard environment["DULCET_DESIGN_TOUR"] == "1" else {
            print("DULCET DESIGN TOUR not requested; it runs only from tools/design-tour-apple")
            return
        }
        let serverURL = try XCTUnwrap(environment["DULCET_UI_TEST_SERVER_URL"], "The tour needs DULCET_UI_TEST_SERVER_URL")
        let username = try XCTUnwrap(environment["DULCET_UI_TEST_USERNAME"])
        let password = try XCTUnwrap(environment["DULCET_UI_TEST_PASSWORD"])
        guard URLComponents(string: serverURL)?.host == "127.0.0.1" else {
            XCTFail("The design tour runs only against a loopback disposable server")
            return
        }
        continueAfterFailure = true
        await preparePlaylist(serverURL, username, password)

        let run = UUID().uuidString
        let databaseName = "dulcet-design-tour-\(run).db"
        let downloadRoot = FileManager.default.temporaryDirectory.appendingPathComponent("dulcet-design-tour-\(run)")
        let downloads = DulcetCoreDownloadController(databaseName: databaseName, downloadRootURL: downloadRoot,
                                                      sessionConfiguration: .ephemeral)
        let artwork = DulcetCoreArtworkFetcher()
        let credentials = DesignTourCredentialStore()
        let session = DulcetLibrarySession(factory: DulcetCoreLibraryReaderFactory(databaseName: databaseName, readsDownloads: true))
        let providerInstanceID = "macos-design-tour-\(run)"
        let playback = DulcetCorePlaybackController(databaseName: databaseName, downloadController: downloads, artworkFetcher: artwork)
        let defaultsSuite = "dulcet-design-tour-\(run)"
        let quality = DulcetCoreStreamingQuality(defaults: try XCTUnwrap(UserDefaults(suiteName: defaultsSuite)))
        playback.streamingQuality = quality
        let store = DulcetPresentationStore(source: DulcetAccountDataSource(
            connector: DulcetCoreAccountConnector(), credentialStore: credentials,
            libraryBrowser: DulcetCoreLibraryBrowser(), artworkFetcher: artwork,
            serverSearch: DulcetCoreServerSearch(), playbackController: playback,
            downloadController: downloads, providerInstanceIDFactory: { providerInstanceID }, librarySession: session
        ))
        store.streamingQualitySetting = quality
        defer {
            playback.disconnect()
            downloads.disconnect()
            try? FileManager.default.removeItem(at: downloadRoot)
            UserDefaults.standard.removePersistentDomain(forName: defaultsSuite)
        }
        appearance = NSAppearance(named: environment["DULCET_DESIGN_TOUR_APPEARANCE"] == "dark" ? .darkAqua : .aqua)

        let enhancedUI = NSAccessibility.Attribute(rawValue: "AXEnhancedUserInterface")
        let previousEnhancedUI = NSApp.accessibilityAttributeValue(enhancedUI) ?? false
        NSApp.accessibilitySetValue(true, forAttribute: enhancedUI)
        defer { NSApp.accessibilitySetValue(previousEnhancedUI, forAttribute: enhancedUI) }
        let hosting = NSHostingView(rootView: DulcetMacProduction.makeRootView(store: store))
        hosting.frame = NSRect(x: 0, y: 0, width: 1280, height: 800)
        let window = NSWindow(contentRect: hosting.frame, styleMask: [.titled, .closable, .resizable, .fullSizeContentView],
                              backing: .buffered, defer: false)
        window.isReleasedWhenClosed = false
        window.toolbarStyle = .unifiedCompact
        window.appearance = appearance
        hosting.appearance = appearance
        hosting.sceneBridgingOptions = [.toolbars]
        window.contentView = hosting
        window.makeKeyAndOrderFront(nil)
        defer { window.close() }
        hostingView = hosting
        self.window = window

        if environment["DULCET_DESIGN_TOUR_PHASE"] == "home" {
            store.accountServerURL = serverURL
            store.accountUsername = username
            store.accountPassword = password
            store.accountAllowLocalHTTP = true
            store.submitAccountConnection()
            let connected = await waitFor(seconds: 30, { store.snapshot.accountConnected && session.mode == .connected })
            XCTAssertTrue(connected)
            XCTAssertEqual(store.selectedDestination, .listenNow)
            await step("listen-now") { await self.find("dulcet.library.album") != nil }
            let pressed = await press("dulcet.library.album")
            XCTAssertTrue(pressed)
            let opened = await waitFor(seconds: 20, { !store.readerPath.isEmpty })
            XCTAssertTrue(opened)
            await shoot("listen-now-album")
            store.navigate(to: .library)
            XCTAssertEqual(store.selectedDestination, .library)
            store.navigate(to: .listenNow)
            store.popReader(to: [])
            await step("listen-now-return") { await self.find("dulcet.library.album") != nil }
            return
        }

        if environment["DULCET_DESIGN_TOUR_PHASE"] == "saved" {
            store.accountServerURL = serverURL
            store.accountUsername = username
            store.accountPassword = password
            store.accountAllowLocalHTTP = true
            store.submitAccountConnection()
            guard await waitFor(seconds: 30, { store.snapshot.accountConnected && session.mode == .connected }) else {
                XCTFail("The saved-state tour setup must connect")
                return
            }
            await step("connection-saved-disconnected") {
                await self.showSavedDisconnected(databaseName: databaseName, providerInstanceID: providerInstanceID,
                    credentials: credentials, frame: hosting.frame, window: window)
            }
            return
        }

        await step("first-run-library") {
            store.selectDestination(.library)
            return await self.findLabel("Connect a Server") != nil
        }
        await step("first-run-search") {
            store.selectDestination(.search)
            return await self.find("dulcet.search.field") != nil
        }
        await step("now-playing-empty") {
            store.selectDestination(.nowPlaying)
            return await self.findLabel("Nothing is playing") != nil
        }
        await step("connection-empty") {
            store.selectDestination(.settings)
            return await self.find("dulcet.account-connect.title") != nil
        }
        func enter(_ url: String, password enteredPassword: String = password, allowHTTP: Bool = true) {
            store.accountServerURL = url
            store.accountUsername = username
            store.accountPassword = enteredPassword
            store.accountAllowLocalHTTP = allowHTTP
        }
        await step("connection-filled") {
            enter(serverURL)
            return await self.find("dulcet.account-connect.primary-action") != nil
        }
        let refused = try XCTUnwrap(DesignTourLoopbackPort(listening: false))
        let silent = try XCTUnwrap(DesignTourLoopbackPort(listening: true))
        defer { refused.release(); silent.release() }
        let errors: [(String, String, String, Bool, DulcetPresentationState)] = [
            ("input", "ftp://127.0.0.1", password, true, .accountErrorInput),
            ("security", serverURL, password, false, .accountErrorSecurity),
            ("transport", "http://127.0.0.1:\(refused.port)", password, true, .accountErrorTransport),
            ("authentication", serverURL, password + "-wrong", true, .accountErrorAuthentication),
            ("protocol", serverURL + "/app", password, true, .accountErrorProtocol),
        ]
        for (name, url, enteredPassword, allowHTTP, expected) in errors {
            await step("connection-error-" + name) {
                enter(url, password: enteredPassword, allowHTTP: allowHTTP)
                store.submitAccountConnection()
                return await self.waitFor(seconds: 45) { store.snapshot.state == expected }
            }
        }
        await step("connection-error-tls") {
            guard let url = environment["DULCET_DESIGN_TOUR_BAD_TLS_URL"], self.isLoopback(url) else { return false }
            enter(url)
            store.submitAccountConnection()
            return await self.waitFor(seconds: 45) { store.snapshot.state == .accountErrorSecurity }
        }
        await step("connection-error-server") {
            guard let url = environment["DULCET_UI_TEST_LYRICS_FAULT_PROXY_URL"], self.isLoopback(url),
                  await self.proxyRule(url, enabled: true) else { return false }
            enter(url)
            store.submitAccountConnection()
            let reached = await self.waitFor(seconds: 45) { store.snapshot.state == .accountErrorServer }
            _ = await self.proxyRule(url, enabled: false)
            return reached
        }
        await step("connection-connecting") {
            enter("http://127.0.0.1:\(silent.port)")
            store.submitAccountConnection()
            return await self.waitFor(seconds: 5) { store.snapshot.state == .accountConnecting }
        }
        store.cancelAccountConnection()
        enter(serverURL)
        store.submitAccountConnection()
        guard await waitFor(seconds: 30, { store.snapshot.accountConnected && session.mode == .connected && session.reader != nil }) else {
            XCTFail("The disposable account must connect: state=\(store.snapshot.state)")
            return
        }
        await step("connection-connected") {
            store.selectDestination(.settings)
            return await self.find("dulcet.account-connect.title") != nil
        }
        if environment["DULCET_DESIGN_TOUR_PHASE"] != "account" {
            let settingsHosting = NSHostingView(rootView: DulcetSettingsView(store: store))
            settingsHosting.appearance = appearance
            settingsHosting.frame = NSRect(x: 0, y: 0, width: 480, height: 320)
            let settingsWindow = NSWindow(contentRect: settingsHosting.frame, styleMask: [.titled, .closable], backing: .buffered, defer: false)
            settingsWindow.isReleasedWhenClosed = false
            settingsWindow.appearance = appearance
            settingsWindow.contentView = settingsHosting
            settingsWindow.makeKeyAndOrderFront(nil)
            self.window = settingsWindow
            hostingView = settingsHosting
            await step("streaming-quality") { await self.find("dulcet.streaming-quality.unmetered") != nil }
            if !(await chooseMenuItem("Original", photographMenu: true, photographScreen: "quality-options", opening: {
                await self.press("dulcet.streaming-quality.unmetered")
            })) {
                print("DULCET DESIGN TOUR screen=quality-options reached=false reason=quality-picker-menu-not-opened")
                XCTFail("The production streaming quality picker must open its native choices")
            }
            settingsWindow.close()
            self.window = window
            hostingView = hosting
            window.makeKeyAndOrderFront(nil)

            await step("library-sidebar") {
                store.selectDestination(.library)
                store.selectLibrarySection(.home)
                return await self.find("dulcet.reader.home.recentlyAdded") != nil
            }
            for (screen, row, type) in [
                ("recently-added-all", "recentlyAdded", DulcetAlbumListType.newest),
                ("recently-played-all", "recentlyPlayed", DulcetAlbumListType.recent),
                ("most-played-all", "mostPlayed", DulcetAlbumListType.frequent),
            ] {
                await step(screen) {
                    store.selectLibrarySection(.home)
                    let identifier = "dulcet.reader.home.\(row).all"
                    if await self.find(identifier, seconds: 2) != nil {
                        guard await self.press(identifier) else { return false }
                        print("DULCET DESIGN TOUR navigation=home-see-all screen=\(screen)")
                    } else {
                        // A home shelf omits See All while it has no albums. The same full list is
                        // reachable through Albums > Sort; open the production navigation route.
                        store.pushReaderPage(.albumList(type))
                        print("DULCET DESIGN TOUR navigation=album-list-route screen=\(screen)")
                    }
                    return await self.waitFor(seconds: 20) {
                        store.readerPath.last == .albumList(type) && self.interactionElements().contains {
                            self.objectValue("accessibilityIdentifier", of: $0) as? String == "dulcet.library.album"
                                || self.label(of: $0) == "Nothing here yet."
                        }
                    }
                }
            }
            await step("albums") {
                store.selectLibrarySection(.albums)
                return await self.find("dulcet.library.album", label: "Threshold Boundary") != nil
            }
            await photographMenu("album-sort-options", expectedItem: "Title") {
                await self.press("dulcet.reader.albums.sort")
            }
            await photographMenu("album-menu", expectedItem: "Play") {
                await self.rightClick("dulcet.library.album", label: "Threshold Boundary")
            }
            await step("album") { await self.openAlbum(store) }
            await step("add-to-playlist") {
                guard await self.chooseMenuItem("Add to Playlist…", photographMenu: true, opening: {
                    await self.rightClick("dulcet.reader.track", label: "Twenty Nine Seconds")
                }) else { return false }
                return await self.find("dulcet.addToPlaylist.playlist", label: self.playlistName) != nil
            }
            if window.attachedSheet != nil {
                guard await pressLabel("Cancel"), await waitFor(seconds: 5, { window.attachedSheet == nil }) else {
                    XCTFail("The real playlist chooser Cancel button must dismiss its sheet")
                    return
                }
                print("DULCET DESIGN TOUR navigation=playlist-chooser-cancel-button")
            }
            await step("artists") {
                store.selectDestination(.library)
                store.selectLibrarySection(.artists)
                return await self.find("dulcet.library.artist", label: "Dulcet Fixtures") != nil
            }
            await photographMenu("artist-menu", expectedItem: "Favorite") {
                await self.rightClick("dulcet.library.artist", label: "Dulcet Fixtures")
            }
            await step("genres") {
                store.selectLibrarySection(.genres)
                return await self.find("dulcet.library.genre") != nil
            }
            await step("genre") {
                guard await self.press("dulcet.library.genre") else { return false }
                return await self.find("dulcet.reader.track") != nil
            }
            await step("artist") {
                store.selectLibrarySection(.artists)
                guard await self.press("dulcet.library.artist", label: "Dulcet Fixtures") else { return false }
                return await self.find("dulcet.library.album", label: "Double Lines") != nil
            }
            await step("now-playing") {
                guard await self.openAlbum(store), await self.press("dulcet.album.play") else { return false }
                guard await self.waitFor(seconds: 20, { store.showsNowPlayingBar }) else { return false }
                store.selectDestination(.nowPlaying)
                return await self.find("dulcet.now-playing.title") != nil
            }
            await step("lyrics") {
                guard await self.press("dulcet.now-playing.lyrics") else { return false }
                return await self.find("dulcet.lyrics.line.current", seconds: 25) != nil
            }
            await step("up-next") {
                _ = await self.press("dulcet.now-playing.lyrics")
                if await self.find("dulcet.now-playing.up-next", seconds: 2) != nil {
                    _ = await self.press("dulcet.now-playing.up-next")
                }
                return await self.find("dulcet.upNext") != nil
            }
            store.sendPlaybackControl(.pause)
            await step("favourites") {
                if store.playingTrackFavourite?.isFavourite == false { _ = store.togglePlayingTrackFavourite() }
                store.selectDestination(.library)
                store.selectLibrarySection(.favourites)
                return await self.find("dulcet.reader.track") != nil
            }
            await step("playlists") {
                store.selectDestination(.library)
                store.selectLibrarySection(.playlists)
                return await self.find("dulcet.library.playlist", label: self.playlistName) != nil
            }
            await photographMenu("playlist-menu", expectedItem: "Play") {
                await self.rightClick("dulcet.library.playlist", label: self.playlistName)
            }
            await step("playlist") {
                guard await self.press("dulcet.library.playlist", label: self.playlistName) else { return false }
                return await self.find("dulcet.reader.track", seconds: 20) != nil
            }
            await step("playlist-edit") {
                guard await self.press("dulcet.playlist.edit") else { return false }
                return await self.find("dulcet.playlist.done") != nil
            }
            _ = await press("dulcet.playlist.done")
            await step("playlist-rename") {
                guard await self.chooseMenuItem("Rename…", photographMenu: true, photographScreen: "playlist-more", opening: {
                    await self.press("dulcet.playlist.more")
                }) else { return false }
                return await self.waitFor(seconds: 5) { self.sheetHasText("Rename") }
            }
            await dismissAlert()
            await step("playlist-delete-confirmation") {
                guard await self.chooseMenuItem("Delete Playlist", opening: "dulcet.playlist.more") else { return false }
                return await self.find("dulcet.playlist.delete.confirm") != nil
            }
            await dismissAlert()
            guard window.attachedSheet == nil else {
                XCTFail("The actual Delete confirmation Cancel button must dismiss its alert")
                return
            }
            await step("playlist-create") {
                store.selectLibrarySection(.playlists)
                guard await self.press("dulcet.playlists.new") else { return false }
                return await self.waitFor(seconds: 5) { self.sheetHasText("New Playlist") }
            }
            await step("playlist-create-filled") {
                guard let sheet = self.window?.attachedSheet,
                      let field = sheet.contentView.flatMap({ self.descendants(of: $0).compactMap { $0 as? NSTextField }.first { $0.isEditable } }) else {
                    return false
                }
                guard sheet.makeFirstResponder(field) else { return false }
                field.currentEditor()?.selectAll(nil)
                for character in "Evening Mix" {
                    let text = String(character)
                    for type in [NSEvent.EventType.keyDown, .keyUp] {
                        guard let event = NSEvent.keyEvent(
                            with: type, location: .zero, modifierFlags: [], timestamp: ProcessInfo.processInfo.systemUptime,
                            windowNumber: sheet.windowNumber, context: nil, characters: text,
                            charactersIgnoringModifiers: text, isARepeat: false, keyCode: 0
                        ) else { return false }
                        NSApp.sendEvent(event)
                    }
                }
                return field.stringValue == "Evening Mix"
            }
            await dismissAlert()
            await step("search") {
                store.selectDestination(.search)
                store.searchQuery = "Threshold"
                return await self.find("dulcet.search.result.0", seconds: 20) != nil
            }
            await step("downloads-populated") {
                guard await self.openAlbum(store), let nowPlaying = store.snapshot.nowPlaying else { return false }
                store.requestDownload(nowPlaying.current)
                guard await self.waitFor(seconds: 45, { downloads.status(for: nowPlaying.current.id) == .downloaded }) else { return false }
                return await self.find("dulcet.reader.track") != nil
            }
            await step("downloads-offline") {
                session.disconnect()
                return await self.find("dulcet.reader.track") != nil
            }
        }
        await step("connection-saved-disconnected") {
            await self.showSavedDisconnected(databaseName: databaseName, providerInstanceID: providerInstanceID,
                credentials: credentials, frame: hosting.frame, window: window)
        }
        await step("connection-error-persistence") {
            let key = "com.legitimateapps.dulcet.active-account-id"
            let previous = UserDefaults.standard.string(forKey: key)
            UserDefaults.standard.removeObject(forKey: key)
            defer {
                if let previous { UserDefaults.standard.set(previous, forKey: key) }
                else { UserDefaults.standard.removeObject(forKey: key) }
            }
            let shippingStore = DulcetMacProduction.makePresentationStore()
            let shippingHosting = NSHostingView(rootView: DulcetMacProduction.makeRootView(store: shippingStore))
            shippingHosting.frame = hosting.frame
            shippingHosting.appearance = self.appearance
            window.contentView = shippingHosting
            self.hostingView = shippingHosting
            shippingStore.accountServerURL = serverURL
            shippingStore.accountUsername = username
            shippingStore.accountPassword = password
            shippingStore.accountAllowLocalHTTP = true
            shippingStore.submitAccountConnection()
            return await self.waitFor(seconds: 45) { shippingStore.snapshot.state == .accountErrorPersistence }
        }
        print("DULCET DESIGN TOUR absent=downloads-screen,songs-list (downloads appear on track rows; library has no Songs destination)")
        print("DULCET DESIGN TOUR done shots=\(shotNumber)")
    }

    private func showSavedDisconnected(databaseName: String, providerInstanceID: String,
                                       credentials: DesignTourCredentialStore, frame: NSRect,
                                       window: NSWindow) async -> Bool {
        let savedSession = DulcetLibrarySession(factory: DulcetCoreLibraryReaderFactory(databaseName: databaseName, readsDownloads: true))
        let savedStore = DulcetPresentationStore(source: DulcetAccountDataSource(
            connector: DulcetCoreAccountConnector(), credentialStore: credentials,
            providerInstanceIDFactory: { providerInstanceID }, librarySession: savedSession
        ))
        // This shot is the saved state after the person cancels the launch reconnect.
        // A reachable saved launch otherwise connects automatically.
        savedStore.cancelAccountConnection()
        savedStore.selectDestination(.settings)
        let savedHosting = NSHostingView(rootView: DulcetMacProduction.makeRootView(store: savedStore))
        savedHosting.frame = frame
        savedHosting.appearance = self.appearance
        window.contentView = savedHosting
        self.hostingView = savedHosting
        guard savedStore.snapshot.state == .accountSavedDisconnected else { return false }
        return await self.find("dulcet.account-connect.title") != nil
    }

    // MARK: - Steps and shots

    private func step(_ screen: String, _ reach: () async -> Bool) async {
        let reached = await reach()
        await settle()
        await shoot(reached ? screen : screen + "-UNREACHED")
        if !reached { XCTFail("The design tour could not reach \(screen)") }
        print("DULCET DESIGN TOUR screen=\(screen) reached=\(reached)")
    }

    /// The window as the screen shows it, when `tools/design-tour-apple` answers the request with
    /// `screencapture` (materials and selection highlights render only there); otherwise the view
    /// hierarchy drawn into an image, which leaves vibrant materials out.
    private func shoot(_ screen: String) async {
        guard let view = hostingView, let window else { return }
        shotNumber += 1
        let requested = ProcessInfo.processInfo.environment["DULCET_DESIGN_TOUR_SCREENS"]?
            .split(separator: " ").map(String.init) ?? []
        guard requested.isEmpty || requested.contains(screen.replacingOccurrences(of: "-UNREACHED", with: "")) else { return }
        NSApp.activate()
        window.makeKeyAndOrderFront(nil)
        // Scene toolbar bridging installs the toolbar after the window mounts. Match the
        // production WindowGroup's compact unified style once that toolbar exists.
        if window.toolbarStyle != .unifiedCompact { window.toolbarStyle = .unifiedCompact }
        // Flush the hosted window after native sidebar rows are inserted or removed.
        await settle(seconds: 1.2)
        guard await waitFor(seconds: 5, { NSApp.isActive }) else {
            XCTFail("Mac photography requires the hosted app to be active")
            return
        }
        window.display()
        let name = String(format: "%02d-%@", shotNumber, screen)
        let captureWindow = window.attachedSheet ?? window
        let captureView = captureWindow.contentView ?? view
        var png: Data?
        if let directory = ProcessInfo.processInfo.environment["DULCET_DESIGN_TOUR_OUT"], !directory.isEmpty {
            let folder = URL(fileURLWithPath: directory)
            let captured = folder.appendingPathComponent(name + ".png")
            try? "\(captureWindow.windowNumber)".write(to: folder.appendingPathComponent(name + ".request"), atomically: true, encoding: .utf8)
            let fallback = folder.appendingPathComponent(name + ".fallback")
            _ = await waitFor(seconds: 10) {
                FileManager.default.fileExists(atPath: captured.path) || FileManager.default.fileExists(atPath: fallback.path)
            }
            if FileManager.default.fileExists(atPath: captured.path) {
                png = try? Data(contentsOf: captured)
                print("DULCET DESIGN TOUR capture=screen screen=\(screen)")
            } else if let rep = captureView.bitmapImageRepForCachingDisplay(in: captureView.bounds) {
                captureView.cacheDisplay(in: captureView.bounds, to: rep)
                png = rep.representation(using: .png, properties: [:])
                try? png?.write(to: captured)
                print("DULCET DESIGN TOUR capture=view-render screen=\(screen)")
            }
        }
        guard let png, let image = NSImage(data: png) else { XCTFail("Could not photograph \(screen)"); return }
        let attachment = XCTAttachment(image: image)
        attachment.name = name
        attachment.lifetime = .keepAlways
        add(attachment)
    }

    private func settle(seconds: Double = 1.2) async {
        try? await Task.sleep(for: .milliseconds(Int(seconds * 1000)))
        hostingView?.layoutSubtreeIfNeeded()
    }

    private func waitFor(seconds: Double, _ condition: () -> Bool) async -> Bool {
        let deadline = ContinuousClock.now.advanced(by: .milliseconds(Int(seconds * 1000)))
        while !condition(), ContinuousClock.now < deadline {
            try? await Task.sleep(for: .milliseconds(100))
        }
        return condition()
    }

    private func openAlbum(_ store: DulcetPresentationStore) async -> Bool {
        store.selectDestination(.library)
        store.selectLibrarySection(.albums)
        guard await press("dulcet.library.album", label: "Threshold Boundary") else { return false }
        return await find("dulcet.album.title") != nil
    }

    private func isLoopback(_ url: String) -> Bool {
        let host = URLComponents(string: url)?.host
        return host == "127.0.0.1" || host == "localhost"
    }

    private func proxyRule(_ base: String, enabled: Bool) async -> Bool {
        guard isLoopback(base), let url = URL(string: base + "/__dulcet/subsonic-error?endpoint=ping&code=0&state=" + (enabled ? "on" : "off")) else { return false }
        var request = URLRequest(url: url)
        request.httpMethod = "POST"
        guard let (_, response) = try? await URLSession.shared.data(for: request) else { return false }
        return (response as? HTTPURLResponse)?.statusCode == 200
    }

    private func interactionElements() -> [Any] {
        if let sheet = window?.attachedSheet {
            return descendants(of: sheet) + ((sheet.contentView?.superview ?? sheet.contentView).map { descendants(of: $0) } ?? [])
        }
        return (window.map { descendants(of: $0) } ?? [])
            + ((window?.contentView?.superview ?? hostingView).map { descendants(of: $0) } ?? [])
    }

    private func sheetHasText(_ text: String) -> Bool {
        guard window?.attachedSheet != nil else { return false }
        return interactionElements().contains { element in
            label(of: element) == text || objectValue("accessibilityValue", of: element) as? String == text
                || (element as? NSTextField)?.stringValue == text
        }
    }

    private func findLabel(_ text: String) async -> Any? {
        let deadline = ContinuousClock.now.advanced(by: .seconds(10))
        repeat {
            if let match = interactionElements().first(where: { self.label(of: $0) == text }) { return match }
            try? await Task.sleep(for: .milliseconds(100))
        } while ContinuousClock.now < deadline
        return nil
    }

    private func dismissAlert() async {
        guard let sheet = window?.attachedSheet, let root = sheet.contentView?.superview ?? sheet.contentView else { return }
        let cancel = descendants(of: root).compactMap { $0 as? NSButton }.first { $0.title == "Cancel" }
        cancel?.performClick(nil)
        _ = await waitFor(seconds: 5) { self.window?.attachedSheet == nil }
    }

    private func pressLabel(_ text: String) async -> Bool {
        if let button = interactionElements().compactMap({ $0 as? NSButton }).first(where: { $0.title == text && $0.isEnabled }) {
            button.performClick(nil)
            return true
        }
        guard await findLabel(text) != nil else { return false }
        let press = #selector(NSAccessibilityProtocol.accessibilityPerformPress)
        guard let target = interactionElements().compactMap({ $0 as? NSObject }).first(where: {
            label(of: $0) == text && $0.responds(to: press)
        }) else { return false }
        _ = target.perform(press)
        return true
    }

    /// Sends a secondary click to the actual track row, matching the hosted playlist proof.
    private func rightClick(_ identifier: String, label: String) async -> Bool {
        guard let window, let element = await find(identifier, label: label),
              let accessible = element as? any NSAccessibilityElementProtocol else { return false }
        let frame = accessible.accessibilityFrame()
        guard window.convertToScreen(window.contentLayoutRect).contains(frame) else { return false }
        let location = window.convertPoint(fromScreen: NSPoint(x: frame.midX, y: frame.midY))
        for type in [NSEvent.EventType.rightMouseDown, .rightMouseUp] {
            guard let made = NSEvent.mouseEvent(
                with: type, location: location, modifierFlags: [], timestamp: ProcessInfo.processInfo.systemUptime,
                windowNumber: window.windowNumber, context: nil, eventNumber: 0, clickCount: 1,
                pressure: type == .rightMouseDown ? 1 : 0
            ), let secondary = made.cgEvent else { return false }
            secondary.setIntegerValueField(.mouseEventButtonNumber, value: 1)
            guard let event = NSEvent(cgEvent: secondary) else { return false }
            window.sendEvent(event)
        }
        return true
    }

    private func chooseMenuItem(_ title: String, opening identifier: String) async -> Bool {
        await chooseMenuItem(title, opening: { await self.press(identifier) })
    }

    private func photographMenu(_ screen: String, expectedItem: String, opening open: () async -> Bool) async {
        if !(await chooseMenuItem(expectedItem, photographMenu: true, photographScreen: screen, performAction: false, opening: open)) {
            print("DULCET DESIGN TOUR screen=\(screen) reached=false reason=native-menu-not-opened")
            XCTFail("The design tour could not open the actual \(screen)")
        }
    }

    private func chooseMenuItem(_ title: String, photographMenu: Bool = false, photographScreen: String = "track-menu", performAction: Bool = true, opening open: () async -> Bool) async -> Bool {
        guard let window else { return false }
        // Hosted tests do not receive the activation handoff that launching the app normally
        // supplies. Match the existing hosted Settings proof before asking AppKit for a menu.
        NSApp.activate(ignoringOtherApps: true)
        window.makeKeyAndOrderFront(nil)
        if !(await waitFor(seconds: 2, { NSApp.isActive })) {
            print("DULCET DESIGN TOUR native-menu-inactive activation-policy=\(NSApp.activationPolicy().rawValue)")
        }
        @MainActor final class Tracking {
            let tour: DulcetMacDesignTourTest
            var performed = false
            var menu: NSMenu?
            var photographAttempted = false
            var timer: Timer?
            init(tour: DulcetMacDesignTourTest) { self.tour = tour }
        }
        // NotificationCenter delivers on .main. AppKit's NSMenu is not annotated Sendable;
        // this immutable event bridge stays on that queue and hands the reference to its actor.
        final class MenuEvent: @unchecked Sendable {
            let menu: NSMenu
            init?(_ notification: Notification) {
                precondition(Thread.isMainThread)
                guard let menu = notification.object as? NSMenu else { return nil }
                self.menu = menu
            }
        }
        let tracking = Tracking(tour: self)
        let observer = NotificationCenter.default.addObserver(forName: NSMenu.didBeginTrackingNotification, object: nil, queue: .main) { notification in
            guard let event = MenuEvent(notification) else { return }
            MainActor.assumeIsolated {
                tracking.menu = event.menu
                if photographMenu, !tracking.photographAttempted {
                    // A tracking notification precedes the native menu's first layout. Let its
                    // event-tracking run loop draw before reading bounds and requesting pixels.
                    // The Sendable actor bridge carries Tracking across Timer's nonisolated
                    // callback; the XCTestCase itself stays behind Tracking's main-actor storage.
                    let captureAndPerform: @MainActor @Sendable () -> Void = {
                        guard let menu = tracking.menu else { return }
                        tracking.photographAttempted = true
                        let index = menu.items.firstIndex(where: {
                            $0.isEnabled && !$0.isSeparatorItem && (performAction ? $0.title == title : !$0.title.isEmpty)
                        })
                        let observedTitle = index.map { menu.items[$0].title } ?? title
                        _ = tracking.tour.shootNativeMenu(menu, screen: photographScreen, expectedItemTitle: observedTitle)
                        if let index {
                            if performAction { menu.performActionForItem(at: index) }
                            tracking.performed = true
                        }
                        menu.cancelTracking()
                    }
                    let timer = Timer(timeInterval: 0.3, repeats: false) { _ in
                        MainActor.assumeIsolated { captureAndPerform() }
                    }
                    tracking.timer = timer
                    RunLoop.main.add(timer, forMode: .eventTracking)
                    RunLoop.main.add(timer, forMode: .common)
                } else {
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
            }
        }
        defer {
            tracking.timer?.invalidate()
            NotificationCenter.default.removeObserver(observer)
        }
        for _ in 0..<3 {
            guard await open() else { return false }
            if await waitFor(seconds: 2, { tracking.performed }) { return true }
        }
        return false
    }

    /// Photographs an observed native menu while its event-tracking loop remains open. A
    /// window-only capture or view render would omit the overlay, so neither is a fallback here.
    private func shootNativeMenu(_ menu: NSMenu, screen: String, expectedItemTitle: String) -> Bool {
        print("DULCET DESIGN TOUR native-menu app-active=\(NSApp.isActive)")
        shotNumber += 1
        let name = String(format: "%02d-%@", shotNumber, screen)
        func fail(_ reason: String) -> Bool {
            if let directory = ProcessInfo.processInfo.environment["DULCET_DESIGN_TOUR_OUT"], !directory.isEmpty {
                try? "UNREACHED: \(reason)".write(
                    to: URL(fileURLWithPath: directory).appendingPathComponent(name + "-UNREACHED.txt"),
                    atomically: true, encoding: .utf8
                )
            }
            print("DULCET DESIGN TOUR screen=\(screen) reached=false reason=\(reason)")
            XCTFail("The design tour could not photograph \(screen): \(reason)")
            return false
        }
        guard let window, let directory = ProcessInfo.processInfo.environment["DULCET_DESIGN_TOUR_OUT"],
              !directory.isEmpty, menu.items.contains(where: { $0.title == expectedItemTitle && $0.isEnabled }) else {
            return fail("native-menu-not-observed")
        }
        let menuFrame = menu.accessibilityFrame()
        guard menuFrame.minX.isFinite, menuFrame.minY.isFinite,
              menuFrame.width.isFinite, menuFrame.height.isFinite,
              menuFrame.width > 0, menuFrame.height > 0, window.frame.intersects(menuFrame),
              let primaryScreen = NSScreen.screens.first else {
            return fail("native-menu-bounds-not-visible")
        }
        // AppKit's screen coordinates have a bottom-left origin. screencapture -R uses Quartz's
        // top-left origin relative to the primary display. Include only the app and observed menu.
        let region = window.frame.union(menuFrame).integral
        let x = Int(region.minX)
        let y = Int(primaryScreen.frame.maxY - region.maxY)
        let rectangle = "\(x),\(y),\(Int(region.width)),\(Int(region.height))"
        let folder = URL(fileURLWithPath: directory)
        let captured = folder.appendingPathComponent(name + ".png")
        let fallback = folder.appendingPathComponent(name + ".fallback")
        do {
            try "region=\(rectangle)".write(to: folder.appendingPathComponent(name + ".request"), atomically: true, encoding: .utf8)
        } catch {
            return fail("region-request-write-failed")
        }
        // This deliberately waits inside the menu's tracking callback: the shell capture helper
        // runs independently, and the native menu stays visible until it returns the actual pixels.
        let deadline = ContinuousClock.now.advanced(by: .seconds(10))
        while !FileManager.default.fileExists(atPath: captured.path),
              !FileManager.default.fileExists(atPath: fallback.path), ContinuousClock.now < deadline {
            Thread.sleep(forTimeInterval: 0.05)
        }
        guard let png = try? Data(contentsOf: captured), let image = NSImage(data: png) else {
            return fail("native-menu-region-capture-denied-or-timed-out")
        }
        let attachment = XCTAttachment(image: image)
        attachment.name = name
        attachment.lifetime = .keepAlways
        add(attachment)
        print("DULCET DESIGN TOUR capture=screen-region screen=\(screen) rectangle=\(rectangle)")
        print("DULCET DESIGN TOUR screen=\(screen) reached=true")
        return true
    }

    // MARK: - Accessibility

    private func find(_ identifier: String, label: String? = nil, seconds: Double = 15) async -> Any? {
        let deadline = ContinuousClock.now.advanced(by: .milliseconds(Int(seconds * 1000)))
        repeat {
            hostingView?.layoutSubtreeIfNeeded()
            if let match = interactionElements().first(where: {
                    objectValue("accessibilityIdentifier", of: $0) as? String == identifier
                        && (label == nil || (self.label(of: $0) ?? "").hasPrefix(label!))
                }) {
                return match
            }
            try? await Task.sleep(for: .milliseconds(100))
        } while ContinuousClock.now < deadline
        return nil
    }

    /// Presses an element the way VoiceOver does. SwiftUI's accessibility nodes answer the informal
    /// protocol's `accessibilityPerformPress` without conforming to the Swift protocol; where the
    /// identifier is on both a control and its text, the one answering the press is the control.
    private func press(_ identifier: String, label: String? = nil) async -> Bool {
        guard await find(identifier, label: label) != nil else { return false }
        let press = #selector(NSAccessibilityProtocol.accessibilityPerformPress)
        let candidates = interactionElements().compactMap { $0 as? NSObject }.filter {
            objectValue("accessibilityIdentifier", of: $0) as? String == identifier
                && (label == nil || (self.label(of: $0) ?? "").hasPrefix(label!))
                && $0.responds(to: press)
        }
        guard let target = candidates.first else { return false }
        _ = target.perform(press)
        return true
    }

    private func label(of element: Any) -> String? {
        // Native toolbar buttons can expose an attributed title instead of a String.
        for name in ["accessibilityLabel", "accessibilityTitle", "accessibilityValue"] {
            let value = objectValue(name, of: element)
            if let text = value as? String, !text.isEmpty { return text }
            if let text = value as? NSAttributedString, !text.string.isEmpty { return text.string }
        }
        return nil
    }

    private func descendants(of root: Any) -> [Any] {
        var result: [Any] = []
        var visited = Set<ObjectIdentifier>()
        var pending: [Any] = [root]
        while let current = pending.popLast() {
            guard let object = current as AnyObject? else { continue }
            guard visited.insert(ObjectIdentifier(object)).inserted else { continue }
            var children = objectValue("accessibilityChildren", of: current) as? [Any] ?? []
            if let view = current as? NSView { children.append(contentsOf: view.subviews) }
            result.append(current)
            pending.append(contentsOf: children)
        }
        return result
    }

    /// Object-valued accessibility getters only; SwiftUI's nodes answer the selectors without
    /// conforming to the whole protocol.
    private func objectValue(_ name: String, of element: Any) -> Any? {
        guard let object = element as? NSObject else { return nil }
        let selector = NSSelectorFromString(name)
        guard object.responds(to: selector) else { return nil }
        return object.perform(selector)?.takeUnretainedValue()
    }

    // MARK: - Server fixture

    private func preparePlaylist(_ server: String, _ username: String, _ password: String) async {
        func call(_ endpoint: String, _ query: [URLQueryItem]) async -> [String: Any]? {
            let salt = (0..<16).map { _ in String(format: "%02x", UInt8.random(in: 0...255)) }.joined()
            let token = Insecure.MD5.hash(data: Data((password + salt).utf8)).map { String(format: "%02x", $0) }.joined()
            guard var components = URLComponents(string: server) else { return nil }
            components.path += "/rest/" + endpoint
            components.queryItems = [
                URLQueryItem(name: "u", value: username), URLQueryItem(name: "t", value: token),
                URLQueryItem(name: "s", value: salt), URLQueryItem(name: "v", value: "1.16.1"),
                URLQueryItem(name: "c", value: "dulcet-design-tour"), URLQueryItem(name: "f", value: "json"),
            ] + query
            guard let url = components.url, let (data, _) = try? await URLSession.shared.data(from: url),
                  let document = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
                  let envelope = document["subsonic-response"] as? [String: Any],
                  envelope["status"] as? String == "ok" else { return nil }
            return envelope
        }
        let existing = (await call("getPlaylists", [])?["playlists"] as? [String: Any])?["playlist"] as? [[String: Any]] ?? []
        for playlist in existing where playlist["name"] as? String == playlistName {
            if let id = playlist["id"] as? String { _ = await call("deletePlaylist", [URLQueryItem(name: "id", value: id)]) }
        }
        var ids: [String] = []
        for query in ["Threshold", "Dulcet"] {
            let songs = (await call("search3", [
                URLQueryItem(name: "query", value: query), URLQueryItem(name: "songCount", value: "6"),
                URLQueryItem(name: "albumCount", value: "0"), URLQueryItem(name: "artistCount", value: "0"),
            ])?["searchResult3"] as? [String: Any])?["song"] as? [[String: Any]] ?? []
            ids += songs.compactMap { $0["id"] as? String }
        }
        let created = await call("createPlaylist", [URLQueryItem(name: "name", value: playlistName)]
            + ids.prefix(8).map { URLQueryItem(name: "songId", value: $0) })
        XCTAssertNotNil(created, "The tour's playlist must be made on the disposable server")
    }
}

/// In memory, remembering the provider instance as the Keychain store does.
private final class DesignTourCredentialStore: DulcetProviderInstanceCredentialStoring {
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

/// Reserved loopback ports for real transport refusal and connecting states.
private final class DesignTourLoopbackPort {
    let port: UInt16
    private var descriptor: Int32

    init?(listening: Bool) {
        let socketDescriptor = socket(AF_INET, SOCK_STREAM, 0)
        guard socketDescriptor >= 0 else { return nil }
        var address = sockaddr_in()
        address.sin_len = UInt8(MemoryLayout<sockaddr_in>.size)
        address.sin_family = sa_family_t(AF_INET)
        address.sin_port = 0
        address.sin_addr.s_addr = inet_addr("127.0.0.1")
        let length = socklen_t(MemoryLayout<sockaddr_in>.size)
        let bound = withUnsafePointer(to: &address) {
            $0.withMemoryRebound(to: sockaddr.self, capacity: 1) { bind(socketDescriptor, $0, length) }
        }
        guard bound == 0, !listening || listen(socketDescriptor, 16) == 0 else {
            close(socketDescriptor)
            return nil
        }
        var assigned = sockaddr_in()
        var assignedLength = length
        let named = withUnsafeMutablePointer(to: &assigned) {
            $0.withMemoryRebound(to: sockaddr.self, capacity: 1) { getsockname(socketDescriptor, $0, &assignedLength) }
        }
        guard named == 0 else {
            close(socketDescriptor)
            return nil
        }
        port = UInt16(bigEndian: assigned.sin_port)
        if listening {
            descriptor = socketDescriptor
        } else {
            close(socketDescriptor)
            descriptor = -1
        }
    }

    /// Accepts, and closes, every connection waiting in the backlog; answers how many there were.
    func drainAcceptedConnections() -> Int {
        guard descriptor >= 0 else { return 0 }
        _ = fcntl(descriptor, F_SETFL, fcntl(descriptor, F_GETFL) | O_NONBLOCK)
        var count = 0
        while true {
            let connection = accept(descriptor, nil, nil)
            guard connection >= 0 else { break }
            close(connection)
            count += 1
        }
        return count
    }

    func release() {
        guard descriptor >= 0 else { return }
        close(descriptor)
        descriptor = -1
    }

    deinit { release() }
}
