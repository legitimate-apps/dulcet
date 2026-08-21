import AppKit
import CryptoKit
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
}

private struct CaptureOptions {
    let outputDirectory: URL
    let states: [DulcetPresentationState]
    let appearances: [CaptureAppearance]
    let includeControl: Bool

    init(arguments: [String]) throws {
        var outputDirectory: URL?
        var states = DulcetPresentationState.allCases
        var appearances = CaptureAppearance.allCases
        var includeControl = false
        var index = 0

        while index < arguments.count {
            let argument = arguments[index]
            switch argument {
            case "--output":
                index += 1
                guard index < arguments.count else { throw CaptureError.missingValue(argument) }
                outputDirectory = URL(fileURLWithPath: arguments[index], isDirectory: true)
            case "--state":
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
                includeControl = true
            default:
                throw CaptureError.unknownArgument(argument)
            }
            index += 1
        }

        guard let outputDirectory else { throw CaptureError.outputRequired }
        self.outputDirectory = outputDirectory
        self.states = states
        self.appearances = appearances
        self.includeControl = includeControl
    }
}

private enum CaptureError: Error, CustomStringConvertible {
    case outputRequired
    case missingValue(String)
    case invalidValue(String, String)
    case unknownArgument(String)
    case unsupportedDynamicType(String)
    case outputExists(String)
    case geometryMismatch(String)
    case bitmapAllocation
    case jpegEncoding

    var description: String {
        switch self {
        case .outputRequired:
            "--output is required"
        case let .missingValue(argument):
            "missing value for \(argument)"
        case let .invalidValue(argument, value):
            "invalid value for \(argument): \(value)"
        case let .unknownArgument(argument):
            "unknown argument: \(argument)"
        case let .unsupportedDynamicType(value):
            "macOS capture cannot claim Dynamic Type \(value): SwiftUI dynamicTypeSize does not affect text size on macOS"
        case let .outputExists(path):
            "output directory must not exist: \(path)"
        case let .geometryMismatch(detail):
            "capture geometry mismatch: \(detail)"
        case .bitmapAllocation:
            "could not allocate the fixed capture bitmap"
        case .jpegEncoding:
            "could not encode the capture as JPEG"
        }
    }
}

private struct CaptureRecord: Codable {
    let appearance: String
    let captureBoundsHeightPoints: Int
    let captureBoundsWidthPoints: Int
    let captureBoundsXPoints: Int
    let captureBoundsYPoints: Int
    let controlActiveState: String
    let file: String
    let fixtureState: String
    let jpegBytes: Int
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
    static func main() throws {
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
        _ = try render(
            state: .libraryBrowse,
            appearance: .light,
            variant: .standard,
            outputDirectory: preflightDirectory
        )
        try fileManager.removeItem(at: preflightDirectory)

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
                records.append(try render(
                    state: .libraryBrowse,
                    appearance: appearance,
                    variant: .deliberatelyBadControl,
                    outputDirectory: options.outputDirectory
                ))
            }
        }

        let manifest = CaptureManifest(
            schemaVersion: 6,
            widthPixels: width,
            heightPixels: height,
            captureSurface: "titled-nswindow-with-standard-chrome",
            windowTitlePolicy: "hidden-redundant-window-title-content-headings-visible",
            textSizingPolicy: "macos-system-semantic-fonts-no-dynamic-type-claim",
            preflightRender: "discarded-library-browse-light-before-recording",
            jpegCompression: jpegCompression,
            locale: "en_US_POSIX",
            calendar: "gregorian",
            timeZone: "UTC",
            fixedClock: "2026-08-21T14:32:00Z",
            network: "disabled-by-fixture-source",
            captures: records.sorted { $0.file < $1.file }
        )
        let encoder = JSONEncoder()
        encoder.outputFormatting = [.prettyPrinted, .sortedKeys, .withoutEscapingSlashes]
        let manifestData = try encoder.encode(manifest) + Data("\n".utf8)
        try manifestData.write(to: options.outputDirectory.appendingPathComponent("manifest.json"))

        print(
            "DULCET CAPTURE PASS images=\(records.count) "
                + "frame=\(width)x\(height) capture-bounds=0,0,\(width)x\(height) "
                + "control-active-state=key "
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
        window.titleVisibility = .hidden
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
        captureView.cacheDisplay(in: captureView.bounds, to: bitmap)
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
        let filename = "\(prefix)-\(state.rawValue)-\(appearance.rawValue).jpg"
        try jpeg.write(to: outputDirectory.appendingPathComponent(filename))
        window.contentView = nil
        window.close()

        return CaptureRecord(
            appearance: appearance.rawValue,
            captureBoundsHeightPoints: Int(captureBounds.height),
            captureBoundsWidthPoints: Int(captureBounds.width),
            captureBoundsXPoints: Int(captureBounds.origin.x),
            captureBoundsYPoints: Int(captureBounds.origin.y),
            controlActiveState: "key",
            file: filename,
            fixtureState: state.rawValue,
            jpegBytes: jpeg.count,
            sha256: SHA256.hash(data: jpeg).map { String(format: "%02x", $0) }.joined(),
            variant: variantName,
            windowFrameHeightPoints: Int(windowFrame.height),
            windowFrameWidthPoints: Int(windowFrame.width)
        )
    }
}
