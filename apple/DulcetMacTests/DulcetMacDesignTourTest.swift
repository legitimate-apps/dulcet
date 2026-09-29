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

        let artwork = DulcetCoreArtworkFetcher()
        let session = DulcetLibrarySession(
            factory: DulcetCoreLibraryReaderFactory(databaseName: "dulcet-design-tour-reader.db")
        )
        let providerInstanceID = "macos-design-tour"
        let store = DulcetPresentationStore(source: DulcetAccountDataSource(
            connector: DulcetCoreAccountConnector(),
            credentialStore: DesignTourCredentialStore(),
            libraryBrowser: DulcetCoreLibraryBrowser(),
            artworkFetcher: artwork,
            serverSearch: DulcetCoreServerSearch(),
            // Databases of its own, never the installed DEV app's.
            playbackController: DulcetCorePlaybackController(
                databaseName: "dulcet-design-tour-queue.db", downloadController: nil, artworkFetcher: artwork
            ),
            downloadController: nil,
            providerInstanceIDFactory: { providerInstanceID },
            librarySession: session
        ))
        store.accountServerURL = serverURL
        store.accountUsername = username
        store.accountPassword = password
        store.accountAllowLocalHTTP = true
        store.submitAccountConnection()
        guard await waitFor(seconds: 30, { store.snapshot.accountConnected && session.mode == .connected && session.reader != nil }) else {
            XCTFail("The disposable account must connect: state=\(store.snapshot.state)")
            return
        }

        let enhancedUI = NSAccessibility.Attribute(rawValue: "AXEnhancedUserInterface")
        let previousEnhancedUI = NSApp.accessibilityAttributeValue(enhancedUI) ?? false
        NSApp.accessibilitySetValue(true, forAttribute: enhancedUI)
        defer { NSApp.accessibilitySetValue(previousEnhancedUI, forAttribute: enhancedUI) }
        let hosting = NSHostingView(rootView: DulcetMacProduction.makeRootView(store: store))
        hosting.frame = NSRect(x: 0, y: 0, width: 1280, height: 800)
        let window = NSWindow(contentRect: hosting.frame, styleMask: [.titled, .closable, .resizable],
                              backing: .buffered, defer: false)
        window.isReleasedWhenClosed = false
        window.contentView = hosting
        window.makeKeyAndOrderFront(nil)
        defer { window.close() }
        hostingView = hosting
        self.window = window

        await step("library-sidebar") {
            store.selectDestination(.library)
            store.selectLibrarySection(.home)
            return await self.find("dulcet.reader.home.recentlyAdded") != nil
        }
        await step("albums") {
            store.selectLibrarySection(.albums)
            return await self.find("dulcet.library.album", label: "Threshold Boundary") != nil
        }
        await step("album") { await self.openAlbum(store) }
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
        await step("playlists") {
            store.selectDestination(.library)
            store.selectLibrarySection(.playlists)
            return await self.find("dulcet.library.playlist", label: self.playlistName) != nil
        }
        await step("playlist") {
            guard await self.press("dulcet.library.playlist", label: self.playlistName) else { return false }
            return await self.find("dulcet.reader.track", seconds: 20) != nil
        }
        await step("search") {
            store.selectDestination(.search)
            store.searchQuery = "Threshold"
            return await self.find("dulcet.search.result.0", seconds: 20) != nil
        }
        print("DULCET DESIGN TOUR done shots=\(shotNumber)")
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
        let name = String(format: "%02d-%@", shotNumber, screen)
        var png: Data?
        if let directory = ProcessInfo.processInfo.environment["DULCET_DESIGN_TOUR_OUT"], !directory.isEmpty {
            let folder = URL(fileURLWithPath: directory)
            let captured = folder.appendingPathComponent(name + ".png")
            try? "\(window.windowNumber)".write(to: folder.appendingPathComponent(name + ".request"), atomically: true, encoding: .utf8)
            if await waitFor(seconds: 10, { FileManager.default.fileExists(atPath: captured.path) }) {
                png = try? Data(contentsOf: captured)
            } else if let rep = view.bitmapImageRepForCachingDisplay(in: view.bounds) {
                view.cacheDisplay(in: view.bounds, to: rep)
                png = rep.representation(using: .png, properties: [:])
                try? png?.write(to: captured)
            }
        }
        guard let png, let image = NSImage(data: png) else { return }
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

    // MARK: - Accessibility

    private func find(_ identifier: String, label: String? = nil, seconds: Double = 15) async -> Any? {
        let deadline = ContinuousClock.now.advanced(by: .milliseconds(Int(seconds * 1000)))
        repeat {
            if let root = hostingView {
                root.layoutSubtreeIfNeeded()
                if let match = descendants(of: root).first(where: {
                    objectValue("accessibilityIdentifier", of: $0) as? String == identifier
                        && (label == nil || (self.label(of: $0) ?? "").hasPrefix(label!))
                }) {
                    return match
                }
            }
            try? await Task.sleep(for: .milliseconds(100))
        } while ContinuousClock.now < deadline
        return nil
    }

    /// Presses an element the way VoiceOver does. SwiftUI's accessibility nodes answer the informal
    /// protocol's `accessibilityPerformPress` without conforming to the Swift protocol; where the
    /// identifier is on both a control and its text, the one answering the press is the control.
    private func press(_ identifier: String, label: String? = nil) async -> Bool {
        guard await find(identifier, label: label) != nil, let root = hostingView else { return false }
        let press = #selector(NSAccessibilityProtocol.accessibilityPerformPress)
        let candidates = descendants(of: root).compactMap { $0 as? NSObject }.filter {
            objectValue("accessibilityIdentifier", of: $0) as? String == identifier
                && (label == nil || (self.label(of: $0) ?? "").hasPrefix(label!))
                && $0.responds(to: press)
        }
        guard let target = candidates.first else { return false }
        _ = target.perform(press)
        return true
    }

    private func label(of element: Any) -> String? {
        objectValue("accessibilityLabel", of: element) as? String
            ?? objectValue("accessibilityTitle", of: element) as? String
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
