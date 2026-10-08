#if DEBUG && (os(macOS) || os(iOS))
import DulcetKit
import Foundation
import notify
import SwiftUI

/// DEBUG ONLY: the shells' half of the killed-process download handoff proof's instrument
/// (`DulcetDownloadHandoffProbe`, spec §14.5, `docs/download-background-blockers.md`).
///
/// Active only while the probe's namespace is. It records each launch, whether the proof's harness
/// made it, and any delivery of the background session's events through the scene's
/// `.backgroundTask(.urlSession)`, which only the system starts; it watches the background session
/// and records each download the system reports running; and it draws what the marker file says,
/// with a button that ends the process by `exit`, which the system does not read as a force quit.
@MainActor
@Observable
final class DulcetDebugDownloadHandoff {
    static let accessibilityIdentifier = "dulcet.debug.download-handoff"
    static let exitIdentifier = "dulcet.debug.download-handoff.exit"
    /// macOS: the title of a track to download once the account's reader can search, since the
    /// proof drives the Mac app as a separate process and cannot reach its menus.
    static let trackArgument = "-dulcet-debug-download-handoff-track"
    /// Ends the process by `exit` as soon as the system reports a download running: a death at a
    /// moment no harness has to catch.
    static let exitWhenOutstandingArgument = "-dulcet-debug-download-handoff-exit-when-outstanding"
    /// The Darwin notification that ends the process by `exit`, followed by the probe's namespace.
    /// A UI test posts it from its runner, which shares the simulator's notification centre: on the
    /// iPad simulator a synthesized tap on the overlay's Exit was not delivered to the button.
    static let exitNotificationPrefix = "com.legitimateapps.dulcet.debug.download-handoff.exit."

    private(set) var summary = ""
    @ObservationIgnored private weak var controller: DulcetCoreDownloadController?
    @ObservationIgnored private var seenRunning: Set<String> = []
    @ObservationIgnored private var monitor: Task<Void, Never>?
    @ObservationIgnored private var exitsWhenOutstanding = false
    @ObservationIgnored private var exitNotificationToken: Int32 = 0

    /// Nil unless the probe's namespace is active in this process.
    static func start(
        controller: DulcetCoreDownloadController?,
        store: DulcetPresentationStore,
        arguments: [String] = ProcessInfo.processInfo.arguments
    ) -> DulcetDebugDownloadHandoff? {
        guard DulcetDownloadHandoffProbe.namespace != nil else { return nil }
        let harness = arguments.contains(DulcetDownloadHandoffProbe.launchArgument)
        DulcetDownloadHandoffProbe.record("launch harness=\(harness ? "yes" : "no")")
        let probe = DulcetDebugDownloadHandoff(controller: controller)
        probe.exitsWhenOutstanding = arguments.contains(exitWhenOutstandingArgument)
        if let namespace = DulcetDownloadHandoffProbe.namespace {
            notify_register_dispatch(exitNotificationPrefix + namespace, &probe.exitNotificationToken, .main) { _ in
                MainActor.assumeIsolated { probe.exitProcess() }
            }
        }
        if let title = launchArgumentValue(trackArgument, in: arguments) {
            probe.download(title: title, store: store)
        }
        return probe
    }

    private init(controller: DulcetCoreDownloadController?) {
        self.controller = controller
        summary = Self.summarize(DulcetDownloadHandoffProbe.markers(), currentPID: getpid())
        monitor = Task { @MainActor [weak self] in
            while !Task.isCancelled {
                await self?.poll()
                try? await Task.sleep(for: .milliseconds(500))
            }
        }
    }

    /// The scene's `.backgroundTask(.urlSession)` ran: the system delivered the session's events.
    nonisolated static func recordBackgroundEvents() {
        DulcetDownloadHandoffProbe.record("background-events")
    }

    func exitProcess() {
        DulcetDownloadHandoffProbe.record("exit")
        exit(0)
    }

    private func poll() async {
        if let controller {
            for task in await controller.debugSessionTasks() where task.state == .running {
                if seenRunning.insert(task.download).inserted {
                    DulcetDownloadHandoffProbe.record("outstanding download=\(task.download) state=running")
                    if exitsWhenOutstanding { exitProcess() }
                }
            }
        }
        let next = Self.summarize(DulcetDownloadHandoffProbe.markers(), currentPID: getpid())
        if next != summary { summary = next }
    }

    /// Searches the reader for the track titled `title` and downloads it through the store, as
    /// the row's Download does.
    private func download(title: String, store: DulcetPresentationStore) {
        Task { @MainActor in
            let search = DulcetReaderSearchModel()
            defer { search.close() }
            var opened = false
            for _ in 0 ..< 240 {
                if !opened, store.downloadsEnabled, let session = store.librarySession, session.reader != nil {
                    search.open(in: session, query: title)
                    opened = true
                }
                if opened, let track = search.current?.rows
                    .compactMap({ $0.result.kind == .track && $0.result.title == title ? $0.result.playableTrack : nil })
                    .first {
                    DulcetDownloadHandoffProbe.record("download-requested item=\(track.id.rawID)")
                    store.requestDownload(track)
                    return
                }
                try? await Task.sleep(for: .milliseconds(250))
            }
            DulcetDownloadHandoffProbe.record(
                "download-request-failed downloads=\(store.downloadsEnabled) session=\(store.librarySession != nil)"
                    + " reader=\(store.librarySession?.reader != nil) searched=\(opened)"
                    + " rows=\(search.current?.rows.count ?? -1)"
            )
        }
    }

    /// The marker file in one line of `key=value` fields, which the proof reads:
    /// `launches`, `unattended-launches` (launches the harness did not make), `background-events`,
    /// `outstanding` (`<download>@<pid>`, the first the system reported running), `exit` (the
    /// pid that recorded it), `promoted` (`<download>@<pid>`), `item`, `state` (the promotion's
    /// answer), `row` (the durable row read back), `bytes`, `sha256` and `pid` (this process).
    static func summarize(_ lines: [String], currentPID: Int32) -> String {
        var launches = 0
        var unattended = 0
        var backgroundEvents = 0
        var outstanding = "none"
        var exitPID = "none"
        var promoted = "none"
        var item = "none"
        var state = "none"
        var row = "none"
        var bytes = "none"
        var digest = "none"
        for line in lines {
            let fields = line.split(separator: " ").map(String.init)
            guard fields.count >= 3, fields[1].hasPrefix("pid=") else { continue }
            let pid = String(fields[1].dropFirst(4))
            let values = Dictionary(
                fields.dropFirst(3).compactMap { field -> (String, String)? in
                    guard let equals = field.firstIndex(of: "=") else { return nil }
                    return (String(field[..<equals]), String(field[field.index(after: equals)...]))
                },
                uniquingKeysWith: { first, _ in first }
            )
            switch fields[2] {
            case "launch":
                launches += 1
                if values["harness"] == "no" { unattended += 1 }
            case "background-events":
                backgroundEvents += 1
            case "outstanding":
                if outstanding == "none", let download = values["download"] { outstanding = "\(download)@\(pid)" }
            case "exit":
                exitPID = pid
            case "promoted":
                promoted = "\(values["download"] ?? "none")@\(pid)"
                item = values["item"] ?? "none"
                state = values["state"] ?? "none"
                row = values["row"] ?? "none"
                bytes = values["bytes"] ?? "none"
                digest = values["sha256"] ?? "none"
            default:
                break
            }
        }
        return [
            "launches=\(launches)", "unattended-launches=\(unattended)", "background-events=\(backgroundEvents)",
            "outstanding=\(outstanding)", "exit=\(exitPID)", "promoted=\(promoted)", "item=\(item)",
            "state=\(state)", "row=\(row)", "bytes=\(bytes)", "sha256=\(digest)", "pid=\(currentPID)",
        ].joined(separator: " ")
    }

    static func launchArgumentValue(_ flag: String, in arguments: [String]) -> String? {
        guard let index = arguments.firstIndex(of: flag), arguments.indices.contains(index + 1) else { return nil }
        let value = arguments[index + 1].trimmingCharacters(in: .whitespacesAndNewlines)
        return value.isEmpty ? nil : value
    }
}

/// What the probe draws over the app: the marker file's summary, and the button that ends the
/// process the way the system does.
struct DulcetDebugDownloadHandoffOverlay: View {
    let probe: DulcetDebugDownloadHandoff

    var body: some View {
        HStack(spacing: 6) {
            Text(probe.summary)
                .font(.system(size: 6).monospaced())
                .lineLimit(2)
                .accessibilityIdentifier(DulcetDebugDownloadHandoff.accessibilityIdentifier)
            Button("Exit") { probe.exitProcess() }
                .font(.caption2)
                .accessibilityIdentifier(DulcetDebugDownloadHandoff.exitIdentifier)
        }
        .padding(.horizontal, 6)
        .background(.thinMaterial, in: Capsule())
    }
}

/// DEBUG ONLY: an account kept in a file of the probe's own, for the macOS proof, whose app is
/// signed ad hoc without the entitlement the data-protection Keychain needs, and whose relaunch
/// must still find the account it connected. 🚨 Disposable canary credentials only: the proof
/// connects the local disposable server, and the file is removed with the probe's directory.
@MainActor
final class DulcetDebugHandoffCredentialStore: DulcetProviderInstanceCredentialStoring {
    private struct Record: Codable {
        let serverURL: String
        let username: String
        let password: String
        let allowLocalHTTP: Bool
        let providerInstanceID: String
        let credentialGeneration: Int64
    }

    private let fileURL: URL
    private(set) var credentialGeneration: Int64 = 0

    static func whenProbing() -> DulcetDebugHandoffCredentialStore? {
        DulcetDownloadHandoffProbe.directoryURL.map {
            DulcetDebugHandoffCredentialStore(fileURL: $0.appendingPathComponent("account.json"))
        }
    }

    private init(fileURL: URL) {
        self.fileURL = fileURL
    }

    private func read() -> Record? {
        guard let data = try? Data(contentsOf: fileURL) else { return nil }
        return try? JSONDecoder().decode(Record.self, from: data)
    }

    var providerInstanceID: String? { read()?.providerInstanceID }

    func load() throws -> DulcetAccountConnectRequest? {
        guard let record = read() else {
            credentialGeneration = 0
            return nil
        }
        credentialGeneration = record.credentialGeneration
        return DulcetAccountConnectRequest(
            serverURL: record.serverURL,
            username: record.username,
            password: record.password,
            allowLocalHTTP: record.allowLocalHTTP
        )
    }

    func save(_ request: DulcetAccountConnectRequest) throws {
        try save(request, providerInstanceID: providerInstanceID ?? UUID().uuidString)
    }

    func save(_ request: DulcetAccountConnectRequest, providerInstanceID: String) throws {
        let previous = read()
        let unchanged = previous.map {
            $0.serverURL == request.serverURL && $0.username == request.username
                && $0.password == request.password && $0.allowLocalHTTP == request.allowLocalHTTP
        } ?? false
        let generation = unchanged ? (previous?.credentialGeneration ?? 1) : (previous?.credentialGeneration ?? 0) + 1
        let record = Record(
            serverURL: request.serverURL,
            username: request.username,
            password: request.password,
            allowLocalHTTP: request.allowLocalHTTP,
            providerInstanceID: providerInstanceID,
            credentialGeneration: generation
        )
        try FileManager.default.createDirectory(
            at: fileURL.deletingLastPathComponent(),
            withIntermediateDirectories: true
        )
        try JSONEncoder().encode(record).write(to: fileURL, options: .atomic)
        try FileManager.default.setAttributes([.posixPermissions: 0o600], ofItemAtPath: fileURL.path)
        credentialGeneration = generation
    }

    func delete() throws {
        try? FileManager.default.removeItem(at: fileURL)
        credentialGeneration = 0
    }
}

/// DEBUG ONLY: connects the account named by launch arguments, as the iOS shell's hook does.
/// 🚨 Disposable canary credentials only: launch arguments are echoed into test transcripts.
@MainActor
enum DulcetDebugLaunchAccount {
    static func connect(_ store: DulcetPresentationStore, arguments: [String] = ProcessInfo.processInfo.arguments) {
        guard arguments.contains("-dulcet-debug-connect-account"),
              let serverURL = DulcetDebugDownloadHandoff.launchArgumentValue(
                  "-dulcet-debug-account-server-url", in: arguments),
              let username = DulcetDebugDownloadHandoff.launchArgumentValue(
                  "-dulcet-debug-account-username", in: arguments),
              let password = DulcetDebugDownloadHandoff.launchArgumentValue(
                  "-dulcet-debug-account-password", in: arguments) else { return }
        store.accountServerURL = serverURL
        store.accountUsername = username
        store.accountPassword = password
        store.accountAllowLocalHTTP = serverURL.lowercased().hasPrefix("http://")
        store.submitAccountConnection()
    }
}
#endif
