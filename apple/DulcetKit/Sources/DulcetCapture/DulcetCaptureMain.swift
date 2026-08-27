import AppKit
import CryptoKit
import Darwin
import DulcetKit
import Foundation
import SwiftUI

private enum CaptureAppearance: String, CaseIterable {
    case light
    case dark

    var colorScheme: ColorScheme {
        switch self {
        case .light: .light
        case .dark: .dark
        }
    }

    var appKitName: NSAppearance.Name {
        switch self {
        case .light: .aqua
        case .dark: .darkAqua
        }
    }

    var pinnedControlFilename: String {
        "macos-CONTROL-DELIBERATELY-BAD-library-browse-\(rawValue).jpg"
    }

    var pinnedControlSHA256: String {
        switch self {
        case .light: "3c46bfa842033834d417f276c43ee29ce85e1f4eefd8cbea17faedecf1d6c60f"
        case .dark: "ba23a4b9b8f257a747cf9050a03b54e5fb2e1f8f18ecca97ec1db8fce2cc74f6"
        }
    }
}

private struct CaptureOptions {
    let outputDirectory: URL
    let states: [DulcetPresentationState]
    let appearances: [CaptureAppearance]
    let includeControl: Bool
    let generateControlCandidates: Bool

    init(arguments: [String]) throws {
        var outputDirectory: URL?
        var states = DulcetPresentationState.allCases
        var appearances = CaptureAppearance.allCases
        var includeControl = false
        var generateControlCandidates = false
        var usedCaptureSelectionOption = false
        var index = 0

        while index < arguments.count {
            let argument = arguments[index]
            switch argument {
            case "--output":
                index += 1
                guard index < arguments.count else { throw CaptureError.missingValue(argument) }
                outputDirectory = URL(fileURLWithPath: arguments[index], isDirectory: true)
            case "--state":
                usedCaptureSelectionOption = true
                index += 1
                guard index < arguments.count else { throw CaptureError.missingValue(argument) }
                let value = arguments[index]
                if value == "all" {
                    states = DulcetPresentationState.allCases
                } else if let state = DulcetPresentationState(rawValue: value) {
                    states = [state]
                } else {
                    throw CaptureError.invalidValue(argument, value)
                }
            case "--appearance":
                usedCaptureSelectionOption = true
                index += 1
                guard index < arguments.count else { throw CaptureError.missingValue(argument) }
                let value = arguments[index]
                if value == "all" {
                    appearances = CaptureAppearance.allCases
                } else if let appearance = CaptureAppearance(rawValue: value) {
                    appearances = [appearance]
                } else {
                    throw CaptureError.invalidValue(argument, value)
                }
            case "--dynamic-type":
                index += 1
                guard index < arguments.count else { throw CaptureError.missingValue(argument) }
                throw CaptureError.unsupportedDynamicType(arguments[index])
            case "--include-control":
                usedCaptureSelectionOption = true
                includeControl = true
            case "--generate-control-candidates":
                generateControlCandidates = true
            default:
                throw CaptureError.unknownArgument(argument)
            }
            index += 1
        }

        guard let outputDirectory else { throw CaptureError.outputRequired }
        if generateControlCandidates && usedCaptureSelectionOption {
            throw CaptureError.incompatibleControlGenerationOptions
        }
        self.outputDirectory = outputDirectory
        self.states = states
        self.appearances = appearances
        self.includeControl = includeControl
        self.generateControlCandidates = generateControlCandidates
    }
}

/// Tracks how much settling the renders actually needed, so the PASS line can report it.
private enum CaptureSettleStatistics {
    nonisolated(unsafe) private(set) static var maximumAttempts = 0

    static func record(attempts: Int) {
        maximumAttempts = max(maximumAttempts, attempts)
    }
}

private enum CaptureError: Error, CustomStringConvertible {
    case outputRequired
    case missingValue(String)
    case invalidValue(String, String)
    case unknownArgument(String)
    case unsupportedDynamicType(String)
    case incompatibleControlGenerationOptions
    case outputExists(String)
    case geometryMismatch(String)
    case bitmapAllocation
    case jpegEncoding
    case invalidJPEGPayload
    case pinnedControlMissing(String)
    case pinnedControlHashMismatch(String, String, String)
    case layoutDidNotConverge(String)

    var description: String {
        switch self {
        case .outputRequired:
            "--output is required"
        case let .layoutDidNotConverge(detail):
            "layout never reached two identical consecutive frames: \(detail)"
        case let .missingValue(argument):
            "missing value for \(argument)"
        case let .invalidValue(argument, value):
            "invalid value for \(argument): \(value)"
        case let .unknownArgument(argument):
            "unknown argument: \(argument)"
        case let .unsupportedDynamicType(value):
            "macOS capture cannot claim Dynamic Type \(value): SwiftUI dynamicTypeSize does not affect text size on macOS"
        case .incompatibleControlGenerationOptions:
            "--generate-control-candidates cannot be combined with --state, --appearance, or --include-control"
        case let .outputExists(path):
            "output directory must not exist: \(path)"
        case let .geometryMismatch(detail):
            "capture geometry mismatch: \(detail)"
        case .bitmapAllocation:
            "could not allocate the fixed capture bitmap"
        case .jpegEncoding:
            "could not encode the capture as JPEG"
        case .invalidJPEGPayload:
            "could not bind capture labels to the JPEG payload"
        case let .pinnedControlMissing(filename):
            "pinned control resource is missing: \(filename)"
        case let .pinnedControlHashMismatch(filename, expected, observed):
            "pinned control resource hash mismatch: \(filename) expected=\(expected) observed=\(observed)"
        }
    }
}

private struct CaptureRecord: Codable {
    let appearance: String
    let captureProvenance: String
    let captureBoundsHeightPoints: Int
    let captureBoundsWidthPoints: Int
    let captureBoundsXPoints: Int
    let captureBoundsYPoints: Int
    let controlActiveState: String
    let file: String
    let fixtureState: String
    let jpegBytes: Int
    let pinnedControlSha256: String?
    let sha256: String
    let variant: String
    let windowFrameHeightPoints: Int
    let windowFrameWidthPoints: Int
}

private struct CaptureManifest: Codable {
    let schemaVersion: Int
    let widthPixels: Int
    let heightPixels: Int
    let captureSurface: String
    let windowTitlePolicy: String
    let textSizingPolicy: String
    let preflightRender: String
    let jpegCompression: Double
    let locale: String
    let calendar: String
    let timeZone: String
    let fixedClock: String
    let network: String
    let controlBaselinePolicy: String
    let captures: [CaptureRecord]
}

private final class CaptureWindow: NSWindow {
    override func constrainFrameRect(_ frameRect: NSRect, to screen: NSScreen?) -> NSRect {
        frameRect
    }
}

@main
private struct DulcetCaptureMain {
    private static let width = 1180
    private static let height = 760
    private static let jpegCompression = 0.72

    @MainActor
    static func main() {
        do {
            try run()
        } catch {
            FileHandle.standardError.write(Data("DULCET CAPTURE ERROR \(error)\n".utf8))
            exit(EXIT_FAILURE)
        }
    }

    @MainActor
    private static func run() throws {
        let options = try CaptureOptions(arguments: Array(CommandLine.arguments.dropFirst()))
        let fileManager = FileManager.default
        if fileManager.fileExists(atPath: options.outputDirectory.path) {
            throw CaptureError.outputExists(options.outputDirectory.path)
        }
        try fileManager.createDirectory(
            at: options.outputDirectory,
            withIntermediateDirectories: true
        )

        let application = NSApplication.shared
        application.setActivationPolicy(.accessory)

        let preflightDirectory = options.outputDirectory.appendingPathComponent(".preflight")
        try fileManager.createDirectory(
            at: preflightDirectory,
            withIntermediateDirectories: false
        )
        // Warm EVERY state, not just one, and discard the output.
        //
        // The preflight previously rendered only .libraryBrowse, so the first *recorded* capture of
        // any other surface was also the first time that surface's text was measured. The gate's
        // intermittent failure is a 3-pixel vertical shift of the account connection Grid, which is
        // aligned on first-text-baseline and so has row origins that depend on resolved font
        // metrics -- a surface the old preflight never touched.
        //
        // Warming is the deterministic half of the fix: it removes the pending async work rather
        // than trying to out-wait it. The convergence loop in render() is the timing half, and on
        // its own it is not sufficient, because two consecutive frames can both be sampled before a
        // resolution completes and so agree on a layout that is about to change. That is consistent
        // with the one local reproduction, which happened while the machine was under compile load.
        for state in DulcetPresentationState.allCases {
            for appearance in CaptureAppearance.allCases {
                _ = try render(
                    state: state,
                    appearance: appearance,
                    variant: .standard,
                    outputDirectory: preflightDirectory
                )
            }
        }
        try fileManager.removeItem(at: preflightDirectory)

        if options.generateControlCandidates {
            for appearance in CaptureAppearance.allCases {
                _ = try render(
                    state: .libraryBrowse,
                    appearance: appearance,
                    variant: .deliberatelyBadControl,
                    outputDirectory: options.outputDirectory
                )
            }
            print(
                "DULCET CONTROL CANDIDATES PASS images=2 policy=review-candidates-only "
                    + "output=\(options.outputDirectory.lastPathComponent)"
            )
            return
        }

        var records: [CaptureRecord] = []
        for state in options.states {
            for appearance in options.appearances {
                records.append(try render(
                    state: state,
                    appearance: appearance,
                    variant: .standard,
                    outputDirectory: options.outputDirectory
                ))
            }
        }

        if options.includeControl {
            for appearance in options.appearances {
                records.append(try copyPinnedControl(
                    appearance: appearance,
                    outputDirectory: options.outputDirectory
                ))
            }
        }

        let manifest = CaptureManifest(
            schemaVersion: 9,
            widthPixels: width,
            heightPixels: height,
            captureSurface: "titled-nswindow-with-standard-chrome",
            windowTitlePolicy: "visible-centered-standard-window-title",
            textSizingPolicy: "macos-system-semantic-fonts-no-dynamic-type-claim",
            preflightRender: "discarded-library-browse-light-before-recording",
            jpegCompression: jpegCompression,
            locale: "en_US_POSIX",
            calendar: "gregorian",
            timeZone: "UTC",
            fixedClock: "2026-08-21T14:32:00Z",
            network: "disabled-by-fixture-source",
            controlBaselinePolicy: "bundled-reviewed-resources-explicit-regeneration-only",
            captures: records.sorted { $0.file < $1.file }
        )
        let encoder = JSONEncoder()
        encoder.outputFormatting = [.prettyPrinted, .sortedKeys, .withoutEscapingSlashes]
        let manifestData = try encoder.encode(manifest) + Data("\n".utf8)
        try manifestData.write(to: options.outputDirectory.appendingPathComponent("manifest.json"))

        print(
            "DULCET CAPTURE PASS images=\(records.count) "
                + "max-settle-attempts=\(CaptureSettleStatistics.maximumAttempts) "
                + "frame=\(width)x\(height) capture-bounds=0,0,\(width)x\(height) "
                + "control-active-state=key "
                + "control-baseline=pinned-resource "
                + "output=\(options.outputDirectory.lastPathComponent)"
        )
    }

    @MainActor
    private static func render(
        state: DulcetPresentationState,
        appearance: CaptureAppearance,
        variant: DulcetRenderVariant,
        outputDirectory: URL
    ) throws -> CaptureRecord {
        var calendar = Calendar(identifier: .gregorian)
        calendar.locale = Locale(identifier: "en_US_POSIX")
        calendar.timeZone = TimeZone(secondsFromGMT: 0)!

        let store = DulcetPresentationStore(
            source: DulcetDeterministicDataSource(initialState: state)
        )
        let scene = DulcetCaptureView(store: store, variant: variant)
            .frame(maxWidth: .infinity, maxHeight: .infinity)
            .environment(\.colorScheme, appearance.colorScheme)
            .environment(\.locale, Locale(identifier: "en_US_POSIX"))
            .environment(\.calendar, calendar)
            .environment(\.timeZone, TimeZone(secondsFromGMT: 0)!)
            .environment(\.controlActiveState, .key)
            .background(Color(nsColor: NSColor.windowBackgroundColor))

        let hostingView = NSHostingView(rootView: scene)
        hostingView.sizingOptions = []
        hostingView.appearance = NSAppearance(named: appearance.appKitName)
        hostingView.wantsLayer = true
        hostingView.layer?.backgroundColor = NSColor.windowBackgroundColor.cgColor

        let window = CaptureWindow(
            contentRect: .zero,
            styleMask: [.titled, .closable, .miniaturizable, .resizable],
            backing: .buffered,
            defer: false
        )
        window.appearance = hostingView.appearance
        window.backgroundColor = NSColor.windowBackgroundColor
        window.title = "Dulcet"
        window.titleVisibility = .visible
        window.titlebarAppearsTransparent = false
        window.isMovableByWindowBackground = false
        window.contentView = hostingView
        window.isReleasedWhenClosed = false
        window.makeKeyAndOrderFront(nil)
        NSApplication.shared.activate(ignoringOtherApps: true)
        RunLoop.main.run(until: Date(timeIntervalSinceNow: 0.08))
        window.setFrame(NSRect(x: 0, y: 0, width: width, height: height), display: true)
        window.layoutIfNeeded()
        hostingView.layoutSubtreeIfNeeded()
        hostingView.displayIfNeeded()

        guard let captureView = hostingView.superview else {
            throw CaptureError.bitmapAllocation
        }
        captureView.layoutSubtreeIfNeeded()
        captureView.displayIfNeeded()

        let windowFrame = window.frame
        let captureBounds = captureView.bounds
        let expectedSize = NSSize(width: width, height: height)
        guard windowFrame.size == expectedSize,
              captureBounds.origin == .zero,
              captureBounds.size == expectedSize else {
            throw CaptureError.geometryMismatch(
                "expected window=\(width)x\(height) capture-bounds=0,0,\(width)x\(height); "
                    + "observed window=\(windowFrame.width)x\(windowFrame.height) "
                    + "capture-bounds=\(captureBounds.origin.x),\(captureBounds.origin.y),"
                    + "\(captureBounds.width)x\(captureBounds.height)"
            )
        }

        guard let bitmap = NSBitmapImageRep(
            bitmapDataPlanes: nil,
            pixelsWide: width,
            pixelsHigh: height,
            bitsPerSample: 8,
            samplesPerPixel: 4,
            hasAlpha: true,
            isPlanar: false,
            colorSpaceName: .deviceRGB,
            bytesPerRow: 0,
            bitsPerPixel: 0
        ) else {
            throw CaptureError.bitmapAllocation
        }
        bitmap.size = NSSize(width: width, height: height)

        // Capture until two consecutive frames are byte-identical, rather than capturing once and
        // hoping a fixed settle was long enough.
        //
        // OBSERVED 2026-08-27, hosted apple-ci run 33119117481: two runs of this binary produced
        // macos-error-tls-untrusted-dark.jpg differing by 49,748 pixels, and the difference is a
        // clean 3-pixel vertical TRANSLATION -- shifting one image by dy=-3 collapses the residual
        // from 5.929 to 1.464, and every column in the band is affected, so a block moved rather
        // than glyphs changing. The moved block is the baseline-aligned account Grid.
        //
        // A Grid aligned on first-text-baseline measures text, so its row origins depend on font
        // metrics being resolved. Both runs execute the same sequence on one machine, so the
        // nondeterminism is within a process: the fixed settle below could expire before text
        // layout had settled, and cacheDisplay then recorded a frame that was still moving.
        // Waiting for stability makes that unrepresentable instead of unlikely, and does it for
        // every surface at once rather than for whichever view is currently baseline-aligned.
        //
        // Failing loudly matters as much as converging: a byte-exact gate that occasionally emits a
        // non-converged frame trains everyone to re-run on red, which is how a real regression gets
        // waved through.
        func renderFrame() throws -> Data {
            captureView.layoutSubtreeIfNeeded()
            captureView.displayIfNeeded()
            captureView.cacheDisplay(in: captureView.bounds, to: bitmap)
            guard let tiff = bitmap.representation(using: .tiff, properties: [:]) else {
                throw CaptureError.bitmapAllocation
            }
            return tiff
        }

        var previousFrame = try renderFrame()
        var settledAttempts = 0
        let maximumSettleAttempts = 40
        var converged = false
        while settledAttempts < maximumSettleAttempts {
            RunLoop.main.run(until: Date(timeIntervalSinceNow: 0.02))
            let frame = try renderFrame()
            settledAttempts += 1
            if frame == previousFrame {
                converged = true
                break
            }
            previousFrame = frame
        }
        guard converged else {
            throw CaptureError.layoutDidNotConverge(
                "\(maximumSettleAttempts) attempts at 20ms produced no two identical consecutive frames"
            )
        }
        // Reported on the PASS line so the gate says whether this wait is load-bearing. If it is
        // always 1, the frame was already stable and this loop is costing time for nothing; if it
        // is ever higher, it caught a frame that a fixed settle would have captured mid-layout.
        // Without this number the fix would be unfalsifiable on a machine that never reproduces
        // the race -- which is every machine except the one that happens to lose it.
        CaptureSettleStatistics.record(attempts: settledAttempts)

        guard let jpeg = bitmap.representation(
            using: .jpeg,
            properties: [.compressionFactor: jpegCompression]
        ) else {
            throw CaptureError.jpegEncoding
        }

        let variantName: String
        let prefix: String
        switch variant {
        case .standard:
            variantName = "reference"
            prefix = "macos"
        case .deliberatelyBadControl:
            variantName = "deliberately-bad-control"
            prefix = "macos-CONTROL-DELIBERATELY-BAD"
        }
        let boundJPEG = try bindJPEGPayload(
            jpeg,
            fixtureState: state.rawValue,
            appearance: appearance.rawValue,
            variant: variantName
        )
        let filename = "\(prefix)-\(state.rawValue)-\(appearance.rawValue).jpg"
        try boundJPEG.write(to: outputDirectory.appendingPathComponent(filename))
        window.contentView = nil
        window.close()

        return CaptureRecord(
            appearance: appearance.rawValue,
            captureProvenance: "rendered-current-run",
            captureBoundsHeightPoints: Int(captureBounds.height),
            captureBoundsWidthPoints: Int(captureBounds.width),
            captureBoundsXPoints: Int(captureBounds.origin.x),
            captureBoundsYPoints: Int(captureBounds.origin.y),
            controlActiveState: "key",
            file: filename,
            fixtureState: state.rawValue,
            jpegBytes: boundJPEG.count,
            pinnedControlSha256: nil,
            sha256: sha256Hex(boundJPEG),
            variant: variantName,
            windowFrameHeightPoints: Int(windowFrame.height),
            windowFrameWidthPoints: Int(windowFrame.width)
        )
    }

    private static func copyPinnedControl(
        appearance: CaptureAppearance,
        outputDirectory: URL
    ) throws -> CaptureRecord {
        let filename = appearance.pinnedControlFilename
        guard let resourceURL = Bundle.module.url(
            forResource: filename,
            withExtension: nil,
            subdirectory: "PinnedControls"
        ) else {
            throw CaptureError.pinnedControlMissing(filename)
        }
        let data = try Data(contentsOf: resourceURL)
        let observedHash = sha256Hex(data)
        guard observedHash == appearance.pinnedControlSHA256 else {
            throw CaptureError.pinnedControlHashMismatch(
                filename,
                appearance.pinnedControlSHA256,
                observedHash
            )
        }
        try data.write(to: outputDirectory.appendingPathComponent(filename))

        return CaptureRecord(
            appearance: appearance.rawValue,
            captureProvenance: "bundled-pinned-resource",
            captureBoundsHeightPoints: height,
            captureBoundsWidthPoints: width,
            captureBoundsXPoints: 0,
            captureBoundsYPoints: 0,
            controlActiveState: "key",
            file: filename,
            fixtureState: DulcetPresentationState.libraryBrowse.rawValue,
            jpegBytes: data.count,
            pinnedControlSha256: observedHash,
            sha256: observedHash,
            variant: "deliberately-bad-control",
            windowFrameHeightPoints: height,
            windowFrameWidthPoints: width
        )
    }

    private static func bindJPEGPayload(
        _ jpeg: Data,
        fixtureState: String,
        appearance: String,
        variant: String
    ) throws -> Data {
        guard jpeg.starts(with: [0xFF, 0xD8]) else {
            throw CaptureError.invalidJPEGPayload
        }
        let commentText = "DULCET-CAPTURE-BINDING-V1\n"
                + "fixtureState=\(fixtureState)\n"
                + "appearance=\(appearance)\n"
                + "variant=\(variant)\n"
                + "jpegPayloadSha256=\(sha256Hex(jpeg))\n"
        let comment = Data(commentText.utf8)
        let segmentLength = comment.count + 2
        guard segmentLength <= Int(UInt16.max) else {
            throw CaptureError.invalidJPEGPayload
        }

        var bound = Data([0xFF, 0xD8, 0xFF, 0xFE])
        bound.append(UInt8((segmentLength >> 8) & 0xFF))
        bound.append(UInt8(segmentLength & 0xFF))
        bound.append(comment)
        bound.append(jpeg.dropFirst(2))
        return bound
    }

    private static func sha256Hex(_ data: Data) -> String {
        SHA256.hash(data: data).map { String(format: "%02x", $0) }.joined()
    }
}
