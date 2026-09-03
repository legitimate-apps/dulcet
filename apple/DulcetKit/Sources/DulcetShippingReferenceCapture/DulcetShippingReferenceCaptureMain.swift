import AppKit
import CoreVideo
import CryptoKit
import Darwin
import DulcetKit
import Foundation
import ScreenCaptureKit
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
        case .light: "39b7219b78a17a4d8b9f5843939af7dce969151ffb976211c34cc519524299d8"
        case .dark: "f7bdf2e0adab37f4afe82a1f7d3744d6c0e4a282f3df37148b36348f63484371"
        }
    }
}

private struct CaptureOptions {
    let outputDirectory: URL
    let pinnedControlDirectory: URL

    init(arguments: [String]) throws {
        var outputDirectory: URL?
        var pinnedControlDirectory: URL?
        var index = 0

        while index < arguments.count {
            let argument = arguments[index]
            switch argument {
            case "--output":
                index += 1
                guard index < arguments.count else { throw CaptureError.missingValue(argument) }
                outputDirectory = URL(fileURLWithPath: arguments[index], isDirectory: true)
            case "--pinned-control-directory":
                index += 1
                guard index < arguments.count else { throw CaptureError.missingValue(argument) }
                pinnedControlDirectory = URL(fileURLWithPath: arguments[index], isDirectory: true)
            default:
                throw CaptureError.unknownArgument(argument)
            }
            index += 1
        }

        guard let outputDirectory else { throw CaptureError.outputRequired }
        guard let pinnedControlDirectory else { throw CaptureError.pinnedControlDirectoryRequired }
        self.outputDirectory = outputDirectory
        self.pinnedControlDirectory = pinnedControlDirectory
    }
}

private enum CaptureError: Error, CustomStringConvertible {
    case outputRequired
    case pinnedControlDirectoryRequired
    case missingValue(String)
    case unknownArgument(String)
    case outputExists(String)
    case geometryMismatch(String)
    case bitmapAllocation
    case screenCaptureWindowMissing(Int)
    case screenshotGeometryMismatch(Int, Int, Int, Int)
    case jpegEncoding
    case invalidJPEGPayload
    case pinnedControlMissing(String)
    case pinnedControlHashMismatch(String, String, String)

    var description: String {
        switch self {
        case .outputRequired:
            "--output is required"
        case .pinnedControlDirectoryRequired:
            "--pinned-control-directory is required"
        case let .missingValue(argument):
            "missing value for \(argument)"
        case let .unknownArgument(argument):
            "unknown argument: \(argument)"
        case let .outputExists(path):
            "output directory must not exist: \(path)"
        case let .geometryMismatch(detail):
            "capture geometry mismatch: \(detail)"
        case .bitmapAllocation:
            "could not resolve the AppKit theme-frame capture boundary"
        case let .screenCaptureWindowMissing(windowNumber):
            "ScreenCaptureKit did not expose capture window \(windowNumber)"
        case let .screenshotGeometryMismatch(observedWidth, observedHeight, expectedWidth, expectedHeight):
            "ScreenCaptureKit returned \(observedWidth)x\(observedHeight), expected \(expectedWidth)x\(expectedHeight)"
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

private struct MissingCapture: Codable {
    let appearance: String
    let error: String
    let expectedFile: String
    let fixtureState: String
    let variant: String
}

private struct CaptureManifest: Codable {
    let schemaVersion: Int
    let artifactClass: String
    let warning: String
    let determinismPolicy: String
    let admissibleUses: [String]
    let prohibitedUses: [String]
    let widthPixels: Int
    let heightPixels: Int
    let captureSurface: String
    let captureMethod: String
    let referenceRootComposition: String
    let fallbackComposition: String
    let windowTitlePolicy: String
    let textSizingPolicy: String
    let preflightRender: String
    let compositorSettlingMilliseconds: Int
    let jpegCompression: Double
    let locale: String
    let calendar: String
    let timeZone: String
    let fixedClock: String
    let network: String
    let controlBaselinePolicy: String
    let expectedShippingCaptureCount: Int
    let successfulShippingCaptureCount: Int
    let expectedPinnedControlCount: Int
    let successfulPinnedControlCount: Int
    let missingCaptures: [MissingCapture]
    let captures: [CaptureRecord]
}

private final class CaptureWindow: NSWindow {
    override func constrainFrameRect(_ frameRect: NSRect, to screen: NSScreen?) -> NSRect {
        frameRect
    }
}

@main
private struct DulcetShippingReferenceCaptureMain {
    private static let width = 1180
    private static let height = 760
    private static let jpegCompression = 0.72
    private static let settlingMilliseconds = 500
    private static let warning =
        "NON-DETERMINISTIC DESIGN-RATING REFERENCE ONLY — NOT ADMISSIBLE AS REGRESSION EVIDENCE"

    @MainActor
    static func main() async {
        do {
            try await run()
        } catch {
            FileHandle.standardError.write(Data("DULCET SHIPPING REFERENCE ERROR \(error)\n".utf8))
            exit(EXIT_FAILURE)
        }
    }

    @MainActor
    private static func run() async throws {
        let options = try CaptureOptions(arguments: Array(CommandLine.arguments.dropFirst()))
        let fileManager = FileManager.default
        if fileManager.fileExists(atPath: options.outputDirectory.path) {
            throw CaptureError.outputExists(options.outputDirectory.path)
        }
        try fileManager.createDirectory(at: options.outputDirectory, withIntermediateDirectories: true)

        let application = NSApplication.shared
        application.setActivationPolicy(.accessory)

        var preflightResult = "not-attempted"
        let preflightDirectory = options.outputDirectory.appendingPathComponent(".preflight")
        do {
            try fileManager.createDirectory(at: preflightDirectory, withIntermediateDirectories: false)
            _ = try await render(
                state: .libraryBrowse,
                appearance: .light,
                outputDirectory: preflightDirectory
            )
            preflightResult = "discarded-library-browse-light-succeeded"
        } catch {
            preflightResult = "discarded-library-browse-light-failed: \(error)"
            reportFailure(preflightResult)
        }
        try? fileManager.removeItem(at: preflightDirectory)

        var records: [CaptureRecord] = []
        var missing: [MissingCapture] = []
        for state in DulcetPresentationState.allCases {
            for appearance in CaptureAppearance.allCases {
                do {
                    records.append(try await render(
                        state: state,
                        appearance: appearance,
                        outputDirectory: options.outputDirectory
                    ))
                } catch {
                    let failure = MissingCapture(
                        appearance: appearance.rawValue,
                        error: String(describing: error),
                        expectedFile: filename(state: state, appearance: appearance),
                        fixtureState: state.rawValue,
                        variant: "shipping-reference"
                    )
                    missing.append(failure)
                    reportFailure("state=\(state.rawValue) appearance=\(appearance.rawValue) error=\(error)")
                }
            }
        }

        for appearance in CaptureAppearance.allCases {
            do {
                records.append(try copyPinnedControl(
                    appearance: appearance,
                    sourceDirectory: options.pinnedControlDirectory,
                    outputDirectory: options.outputDirectory
                ))
            } catch {
                let failure = MissingCapture(
                    appearance: appearance.rawValue,
                    error: String(describing: error),
                    expectedFile: appearance.pinnedControlFilename,
                    fixtureState: DulcetPresentationState.libraryBrowse.rawValue,
                    variant: "deliberately-bad-control"
                )
                missing.append(failure)
                reportFailure("control appearance=\(appearance.rawValue) error=\(error)")
            }
        }

        let shippingCount = records.filter { $0.variant == "shipping-reference" }.count
        let controlCount = records.filter { $0.variant == "deliberately-bad-control" }.count
        let manifest = CaptureManifest(
            schemaVersion: 1,
            artifactClass: "NON-DETERMINISTIC-SHIPPING-DESIGN-REFERENCE",
            warning: warning,
            determinismPolicy: "not-claimed-no-byte-comparison-no-regression-gate",
            admissibleUses: [
                "pairwise-design-rating",
                "shipping-composition-visual-review",
            ],
            prohibitedUses: [
                "pixel-parity",
                "regression-testing",
                "run-to-run-diffing",
            ],
            widthPixels: width,
            heightPixels: height,
            captureSurface: "shipping-root-titled-nswindow-with-standard-chrome",
            captureMethod: "screen-capture-kit-desktop-independent-window",
            referenceRootComposition: "dulcet-root-view-navigation-split-view-balanced",
            fallbackComposition: "none-never-substitute-dulcet-capture-view",
            windowTitlePolicy: "visible-centered-standard-window-title",
            textSizingPolicy: "macos-system-semantic-fonts-no-dynamic-type-claim",
            preflightRender: preflightResult,
            compositorSettlingMilliseconds: settlingMilliseconds,
            jpegCompression: jpegCompression,
            locale: "en_US_POSIX",
            calendar: "gregorian",
            timeZone: "UTC",
            fixedClock: "2026-08-21T14:32:00Z",
            network: "disabled-by-fixture-source",
            controlBaselinePolicy: "copied-byte-for-byte-from-hash-pinned-deterministic-capture-resources",
            expectedShippingCaptureCount: DulcetPresentationState.allCases.count * CaptureAppearance.allCases.count,
            successfulShippingCaptureCount: shippingCount,
            expectedPinnedControlCount: CaptureAppearance.allCases.count,
            successfulPinnedControlCount: controlCount,
            missingCaptures: missing.sorted { $0.expectedFile < $1.expectedFile },
            captures: records.sorted { $0.file < $1.file }
        )
        let encoder = JSONEncoder()
        encoder.outputFormatting = [.prettyPrinted, .sortedKeys, .withoutEscapingSlashes]
        try (encoder.encode(manifest) + Data("\n".utf8)).write(
            to: options.outputDirectory.appendingPathComponent("manifest.json")
        )

        let notice = """
        \(warning)

        These images capture the shipping DulcetRootView NavigationSplitView composition.
        ScreenCaptureKit/WindowServer output has measured run-to-run pixel variance.
        Use this artifact for design rating and visual review only.
        Do not use it for pixel parity, regression testing, or run-to-run diff claims.
        See manifest.json for successful and missing captures.
        """
        try Data((notice + "\n").utf8).write(
            to: options.outputDirectory.appendingPathComponent("ARTIFACT-NOTICE.txt")
        )

        print(
            "DULCET SHIPPING REFERENCE COMPLETE shipping=\(shippingCount)/14 "
                + "controls=\(controlCount)/2 missing=\(missing.count) "
                + "determinism=not-claimed regression-admissible=false "
                + "output=\(options.outputDirectory.lastPathComponent)"
        )
    }

    @MainActor
    private static func render(
        state: DulcetPresentationState,
        appearance: CaptureAppearance,
        outputDirectory: URL
    ) async throws -> CaptureRecord {
        var calendar = Calendar(identifier: .gregorian)
        calendar.locale = Locale(identifier: "en_US_POSIX")
        calendar.timeZone = TimeZone(secondsFromGMT: 0)!

        let store = DulcetPresentationStore(
            source: DulcetDeterministicDataSource(initialState: state)
        )
        let scene = DulcetRootView(store: store, variant: .standard)
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
        defer {
            window.contentView = nil
            window.close()
        }
        window.appearance = hostingView.appearance
        window.backgroundColor = NSColor.windowBackgroundColor
        window.title = "Dulcet"
        window.titleVisibility = .visible
        window.titlebarAppearsTransparent = false
        window.isMovableByWindowBackground = false
        window.contentView = hostingView
        window.isReleasedWhenClosed = false
        window.animationBehavior = .none
        window.setFrame(NSRect(x: 0, y: 0, width: width, height: height), display: false)
        window.makeKeyAndOrderFront(nil)
        NSApplication.shared.activate(ignoringOtherApps: true)
        window.layoutIfNeeded()
        hostingView.layoutSubtreeIfNeeded()
        hostingView.displayIfNeeded()

        guard let captureView = hostingView.superview else {
            throw CaptureError.bitmapAllocation
        }
        captureView.layoutSubtreeIfNeeded()
        captureView.displayIfNeeded()
        try await Task.sleep(for: .milliseconds(settlingMilliseconds))

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

        let shareableContent = try await SCShareableContent.excludingDesktopWindows(
            false,
            onScreenWindowsOnly: true
        )
        guard let captureWindow = shareableContent.windows.first(where: {
            $0.windowID == CGWindowID(window.windowNumber)
        }) else {
            throw CaptureError.screenCaptureWindowMissing(window.windowNumber)
        }
        let filter = SCContentFilter(desktopIndependentWindow: captureWindow)
        let configuration = SCStreamConfiguration()
        configuration.width = width
        configuration.height = height
        configuration.pixelFormat = kCVPixelFormatType_32BGRA
        configuration.showsCursor = false
        configuration.capturesAudio = false
        configuration.ignoreShadowsSingleWindow = true
        let screenshot = try await SCScreenshotManager.captureImage(
            contentFilter: filter,
            configuration: configuration
        )
        guard screenshot.width == width, screenshot.height == height else {
            throw CaptureError.screenshotGeometryMismatch(
                screenshot.width,
                screenshot.height,
                width,
                height
            )
        }
        let bitmap = NSBitmapImageRep(cgImage: screenshot)
        bitmap.size = expectedSize
        guard let jpeg = bitmap.representation(
            using: .jpeg,
            properties: [.compressionFactor: jpegCompression]
        ) else {
            throw CaptureError.jpegEncoding
        }

        let boundJPEG = try bindJPEGPayload(
            jpeg,
            fixtureState: state.rawValue,
            appearance: appearance.rawValue
        )
        let outputFilename = filename(state: state, appearance: appearance)
        try boundJPEG.write(to: outputDirectory.appendingPathComponent(outputFilename))

        return CaptureRecord(
            appearance: appearance.rawValue,
            captureProvenance: "rendered-current-run-via-window-server",
            captureBoundsHeightPoints: Int(captureBounds.height),
            captureBoundsWidthPoints: Int(captureBounds.width),
            captureBoundsXPoints: Int(captureBounds.origin.x),
            captureBoundsYPoints: Int(captureBounds.origin.y),
            controlActiveState: "key",
            file: outputFilename,
            fixtureState: state.rawValue,
            jpegBytes: boundJPEG.count,
            pinnedControlSha256: nil,
            sha256: sha256Hex(boundJPEG),
            variant: "shipping-reference",
            windowFrameHeightPoints: Int(windowFrame.height),
            windowFrameWidthPoints: Int(windowFrame.width)
        )
    }

    private static func copyPinnedControl(
        appearance: CaptureAppearance,
        sourceDirectory: URL,
        outputDirectory: URL
    ) throws -> CaptureRecord {
        let filename = appearance.pinnedControlFilename
        let source = sourceDirectory.appendingPathComponent(filename)
        guard FileManager.default.fileExists(atPath: source.path) else {
            throw CaptureError.pinnedControlMissing(filename)
        }
        let data = try Data(contentsOf: source)
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
            captureProvenance: "copied-hash-pinned-deterministic-control-resource",
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

    private static func filename(
        state: DulcetPresentationState,
        appearance: CaptureAppearance
    ) -> String {
        "macos-\(state.rawValue)-\(appearance.rawValue).jpg"
    }

    private static func bindJPEGPayload(
        _ jpeg: Data,
        fixtureState: String,
        appearance: String
    ) throws -> Data {
        guard jpeg.starts(with: [0xFF, 0xD8]) else {
            throw CaptureError.invalidJPEGPayload
        }
        let commentText = "DULCET-SHIPPING-REFERENCE-BINDING-V1\n"
                + "fixtureState=\(fixtureState)\n"
                + "appearance=\(appearance)\n"
                + "variant=shipping-reference\n"
                + "regressionAdmissible=false\n"
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

    private static func reportFailure(_ message: String) {
        FileHandle.standardError.write(Data("DULCET SHIPPING REFERENCE MISSING \(message)\n".utf8))
    }
}
