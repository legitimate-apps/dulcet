import AppKit
import CryptoKit
import Darwin
import Dispatch
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
    let diagnosticsOutput: URL?
    let states: [DulcetPresentationState]
    let appearances: [CaptureAppearance]
    let includeControl: Bool
    let generateControlCandidates: Bool

    init(arguments: [String]) throws {
        var outputDirectory: URL?
        var diagnosticsOutput: URL?
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
            case "--diagnostics-output":
                index += 1
                guard index < arguments.count else { throw CaptureError.missingValue(argument) }
                diagnosticsOutput = URL(fileURLWithPath: arguments[index], isDirectory: false)
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
        if let diagnosticsOutput {
            let outputPath = outputDirectory.standardizedFileURL.path
            let diagnosticsPath = diagnosticsOutput.standardizedFileURL.path
            if diagnosticsPath == outputPath
                || diagnosticsPath.hasPrefix(outputPath + "/") {
                throw CaptureError.diagnosticsInsideComparedOutput(diagnosticsPath)
            }
        }
        if generateControlCandidates && usedCaptureSelectionOption {
            throw CaptureError.incompatibleControlGenerationOptions
        }
        self.outputDirectory = outputDirectory
        self.diagnosticsOutput = diagnosticsOutput
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

private enum CaptureRenderingPolicy {
    // One scale owns layout, layer display, and raster allocation. Keeping these as uses of one
    // value, rather than three independently configurable policies, makes a mixed-grid capture
    // unrepresentable by this renderer.
    static let captureScale: CGFloat = 1
}

private enum CaptureError: Error, CustomStringConvertible {
    case outputRequired
    case missingValue(String)
    case invalidValue(String, String)
    case unknownArgument(String)
    case unsupportedDynamicType(String)
    case incompatibleControlGenerationOptions
    case outputExists(String)
    case diagnosticsOutputExists(String)
    case diagnosticsInsideComparedOutput(String)
    case geometryMismatch(String)
    case renderingEnvironmentMismatch(String)
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
        case let .diagnosticsOutputExists(path):
            "diagnostics output must not exist: \(path)"
        case let .diagnosticsInsideComparedOutput(path):
            "diagnostics output must be outside the byte-compared capture directory: \(path)"
        case let .geometryMismatch(detail):
            "capture geometry mismatch: \(detail)"
        case let .renderingEnvironmentMismatch(detail):
            "capture rendering environment mismatch: \(detail)"
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

private struct CapturePointSize: Codable {
    let width: Double
    let height: Double
}

private struct CapturePointRect: Codable {
    let x: Double
    let y: Double
    let width: Double
    let height: Double
}

private struct CaptureInsets: Codable {
    let top: Double
    let left: Double
    let bottom: Double
    let right: Double
}

private struct CaptureFontMetrics: Codable {
    let fontName: String
    let familyName: String?
    let pointSize: Double
    let ascender: Double
    let descender: Double
    let leading: Double
    let capHeight: Double
    let xHeight: Double
    let boundingRect: CapturePointRect
}

private struct CaptureNativeControlLayoutFact: Codable {
    let hierarchyPath: String
    let semanticKey: String
    let controlKind: String
    let className: String
    let cellClassName: String?
    let accessibilityIdentifier: String?
    let accessibilityLabel: String?
    let placeholder: String?
    let buttonTitle: String?
    let frameInCapturePoints: CapturePointRect
    let boundsSizePoints: CapturePointSize
    let intrinsicContentSizePoints: CapturePointSize
    let cellSizePoints: CapturePointSize?
    let cellDrawingRectPoints: CapturePointRect?
    let cellTitleRectPoints: CapturePointRect?
    let alignmentRectInsetsPoints: CaptureInsets
    let firstBaselineOffsetFromTopPoints: Double
    let lastBaselineOffsetFromBottomPoints: Double
    let fontMetrics: CaptureFontMetrics?
    let layerContentsScale: Double?
    let effectiveLayerContentsScale: Double
    let effectiveLayerContentsScaleSource: String
    let isFlipped: Bool
}

private struct CaptureRootLayoutFacts: Codable {
    let coordinateSystem: String
    let captureViewIsFlipped: Bool
    let windowFramePoints: CapturePointRect
    let windowContentLayoutRectPoints: CapturePointRect
    let captureViewFramePoints: CapturePointRect
    let captureViewBoundsPoints: CapturePointRect
    let hostingViewFrameInCapturePoints: CapturePointRect
    let hostingViewBoundsPoints: CapturePointRect
    let hostingViewIntrinsicContentSizePoints: CapturePointSize
    let hostingViewFirstBaselineOffsetFromTopPoints: Double?
    let hostingViewLastBaselineOffsetFromBottomPoints: Double?
    let hostingViewLayerContentsScale: Double?
}

private struct CaptureLayoutDiagnostics: Codable {
    let collectionStage: String
    let root: CaptureRootLayoutFacts
    let nativeControls: [CaptureNativeControlLayoutFact]
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
    let hostLayerContentsScale: Double?
    let jpegBytes: Int
    let layoutDisplayScale: Double?
    let bitmapPixelsPerPoint: Double?
    let layoutDiagnostics: CaptureLayoutDiagnostics?
    let pinnedControlSha256: String?
    let settleAttempts: Int?
    let firstComparisonMatched: Bool?
    let sha256: String
    let variant: String
    let windowBackingScaleFactor: Double?
    let windowFrameHeightPoints: Int
    let windowFrameWidthPoints: Int
}

private struct CaptureRenderTiming: Codable {
    let appearance: String
    let file: String
    let firstComparisonMatched: Bool
    let fixtureState: String
    let phase: String
    let renderStartToFirstFrameMilliseconds: Double
    let renderStartToStableFrameMilliseconds: Double
    let mainEntryToStableFrameMilliseconds: Double
    let settleAttempts: Int
    let variant: String
}

private struct CaptureProcessDiagnostics: Codable {
    let schemaVersion: Int
    let clock: String
    let durationUnit: String
    let comparedSurfacePolicy: String
    let preflightDurationMilliseconds: Double
    let mainEntryToFirstRecordedStableFrameMilliseconds: Double?
    let mainEntryToDiagnosticsWriteMilliseconds: Double
    let renders: [CaptureRenderTiming]
}

private struct RenderedCapture {
    let record: CaptureRecord
    let timing: CaptureRenderTiming
}

private struct CaptureManifest: Codable {
    let schemaVersion: Int
    let widthPixels: Int
    let heightPixels: Int
    let captureSurface: String
    let windowTitlePolicy: String
    let textSizingPolicy: String
    let preflightRender: String
    let appearanceResolutionPolicy: String
    let layoutDiagnosticsPolicy: String
    let settlePathPolicy: String
    let bitmapPixelsPerPoint: Double
    let fontSmoothingPolicy: String
    let fontSubpixelPositioningPolicy: String
    let fontSubpixelQuantizationPolicy: String
    let hostLayerContentsScale: Double
    let jpegCompression: Double
    let layoutDisplayScale: Double
    let locale: String
    let calendar: String
    let timeZone: String
    let fixedClock: String
    let network: String
    let windowBackingScaleFactor: Double
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
    private static var capturePixelWidth: Int {
        Int(CGFloat(width) * CaptureRenderingPolicy.captureScale)
    }
    private static var capturePixelHeight: Int {
        Int(CGFloat(height) * CaptureRenderingPolicy.captureScale)
    }

    @MainActor
    static func main() {
        let mainEntryUptimeNanoseconds = DispatchTime.now().uptimeNanoseconds
        do {
            try run(mainEntryUptimeNanoseconds: mainEntryUptimeNanoseconds)
        } catch {
            FileHandle.standardError.write(Data("DULCET CAPTURE ERROR \(error)\n".utf8))
            exit(EXIT_FAILURE)
        }
    }

    @MainActor
    private static func run(mainEntryUptimeNanoseconds: UInt64) throws {
        let options = try CaptureOptions(arguments: Array(CommandLine.arguments.dropFirst()))
        let fileManager = FileManager.default
        if fileManager.fileExists(atPath: options.outputDirectory.path) {
            throw CaptureError.outputExists(options.outputDirectory.path)
        }
        if let diagnosticsOutput = options.diagnosticsOutput,
           fileManager.fileExists(atPath: diagnosticsOutput.path) {
            throw CaptureError.diagnosticsOutputExists(diagnosticsOutput.path)
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
        // Keep first-use resource work out of the recorded set. This cannot resolve cross-process
        // layout differences: every render below must independently establish and validate the same
        // explicit backing, layout, appearance, and bitmap context before constructing its host.
        var renderTimings: [CaptureRenderTiming] = []
        let preflightStarted = DispatchTime.now().uptimeNanoseconds
        for state in DulcetPresentationState.allCases {
            for appearance in CaptureAppearance.allCases {
                let rendered = try render(
                    state: state,
                    appearance: appearance,
                    variant: .standard,
                    outputDirectory: preflightDirectory,
                    timingPhase: "preflight",
                    mainEntryUptimeNanoseconds: mainEntryUptimeNanoseconds
                )
                renderTimings.append(rendered.timing)
            }
        }
        let preflightFinished = DispatchTime.now().uptimeNanoseconds
        try fileManager.removeItem(at: preflightDirectory)

        if options.generateControlCandidates {
            for appearance in CaptureAppearance.allCases {
                let rendered = try render(
                    state: .libraryBrowse,
                    appearance: appearance,
                    variant: .deliberatelyBadControl,
                    outputDirectory: options.outputDirectory,
                    timingPhase: "control-candidate",
                    mainEntryUptimeNanoseconds: mainEntryUptimeNanoseconds
                )
                renderTimings.append(rendered.timing)
            }
            try writeProcessDiagnostics(
                options: options,
                mainEntryUptimeNanoseconds: mainEntryUptimeNanoseconds,
                preflightStarted: preflightStarted,
                preflightFinished: preflightFinished,
                renderTimings: renderTimings
            )
            print(
                "DULCET CONTROL CANDIDATES PASS images=2 policy=review-candidates-only "
                    + "output=\(options.outputDirectory.lastPathComponent)"
            )
            return
        }

        var records: [CaptureRecord] = []
        for state in options.states {
            for appearance in options.appearances {
                let rendered = try render(
                    state: state,
                    appearance: appearance,
                    variant: .standard,
                    outputDirectory: options.outputDirectory,
                    timingPhase: "recorded-reference",
                    mainEntryUptimeNanoseconds: mainEntryUptimeNanoseconds
                )
                records.append(rendered.record)
                renderTimings.append(rendered.timing)
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

        let observedWindowBackingScaleFactors = Set(
            records.compactMap(\.windowBackingScaleFactor)
        )
        guard observedWindowBackingScaleFactors.count == 1,
              let observedWindowBackingScaleFactor = observedWindowBackingScaleFactors.first else {
            throw CaptureError.renderingEnvironmentMismatch(
                "capture records do not share one resolved window backing scale: observed="
                    + "\(observedWindowBackingScaleFactors.sorted())"
            )
        }
        let captureScale = Double(CaptureRenderingPolicy.captureScale)
        let observedLayoutDisplayScales = Set(records.compactMap(\.layoutDisplayScale))
        let observedHostLayerContentsScales = Set(records.compactMap(\.hostLayerContentsScale))
        let observedBitmapPixelsPerPoint = Set(records.compactMap(\.bitmapPixelsPerPoint))
        guard observedLayoutDisplayScales == [captureScale],
              observedHostLayerContentsScales == [captureScale],
              observedBitmapPixelsPerPoint == [captureScale] else {
            throw CaptureError.renderingEnvironmentMismatch(
                "capture records do not share the capture scale: expected=\(captureScale) "
                    + "layout=\(observedLayoutDisplayScales.sorted()) "
                    + "host-layer=\(observedHostLayerContentsScales.sorted()) "
                    + "bitmap=\(observedBitmapPixelsPerPoint.sorted())"
            )
        }

        let manifest = CaptureManifest(
            schemaVersion: 12,
            widthPixels: capturePixelWidth,
            heightPixels: capturePixelHeight,
            captureSurface: "titled-nswindow-with-standard-chrome",
            windowTitlePolicy: "visible-centered-standard-window-title",
            textSizingPolicy: "macos-system-semantic-fonts-no-dynamic-type-claim",
            preflightRender: "discarded-all-states-all-appearances-before-recording",
            appearanceResolutionPolicy: "requested-appearance-current-before-host-construction",
            layoutDiagnosticsPolicy: "resolved-root-and-native-controls-after-frame-convergence",
            settlePathPolicy: "per-render-comparisons-until-first-identical-consecutive-frame-pair",
            bitmapPixelsPerPoint: captureScale,
            fontSmoothingPolicy: "disabled-explicit-bitmap-context",
            fontSubpixelPositioningPolicy: "disabled-explicit-bitmap-context",
            fontSubpixelQuantizationPolicy: "disabled-explicit-bitmap-context",
            hostLayerContentsScale: captureScale,
            jpegCompression: jpegCompression,
            layoutDisplayScale: captureScale,
            locale: "en_US_POSIX",
            calendar: "gregorian",
            timeZone: "UTC",
            fixedClock: "2026-08-21T14:32:00Z",
            network: "disabled-by-fixture-source",
            windowBackingScaleFactor: observedWindowBackingScaleFactor,
            controlBaselinePolicy: "bundled-reviewed-resources-explicit-regeneration-only",
            captures: records.sorted { $0.file < $1.file }
        )
        let encoder = JSONEncoder()
        encoder.outputFormatting = [.prettyPrinted, .sortedKeys, .withoutEscapingSlashes]
        let manifestData = try encoder.encode(manifest) + Data("\n".utf8)
        try manifestData.write(to: options.outputDirectory.appendingPathComponent("manifest.json"))
        try writeProcessDiagnostics(
            options: options,
            mainEntryUptimeNanoseconds: mainEntryUptimeNanoseconds,
            preflightStarted: preflightStarted,
            preflightFinished: preflightFinished,
            renderTimings: renderTimings
        )

        print(
            "DULCET CAPTURE PASS images=\(records.count) "
                + "max-settle-attempts=\(CaptureSettleStatistics.maximumAttempts) "
                + "frame=\(width)x\(height) capture-bounds=0,0,\(width)x\(height) "
                + "layout-display-scale=\(captureScale) "
                + "window-backing-scale=\(observedWindowBackingScaleFactor) "
                + "bitmap-pixels-per-point=\(captureScale) "
                + "font-smoothing=disabled font-subpixel-positioning=disabled "
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
        outputDirectory: URL,
        timingPhase: String,
        mainEntryUptimeNanoseconds: UInt64
    ) throws -> RenderedCapture {
        let renderStarted = DispatchTime.now().uptimeNanoseconds
        var calendar = Calendar(identifier: .gregorian)
        calendar.locale = Locale(identifier: "en_US_POSIX")
        calendar.timeZone = TimeZone(secondsFromGMT: 0)!

        let store = DulcetPresentationStore(
            source: DulcetDeterministicDataSource(initialState: state)
        )
        guard let requestedAppearance = NSAppearance(named: appearance.appKitName) else {
            throw CaptureError.renderingEnvironmentMismatch(
                "requested appearance is unavailable: \(appearance.appKitName.rawValue)"
            )
        }

        let window = CaptureWindow(
            contentRect: .zero,
            styleMask: [.titled, .closable, .miniaturizable, .resizable],
            backing: .buffered,
            defer: false
        )
        window.appearance = requestedAppearance
        window.backgroundColor = NSColor.windowBackgroundColor
        window.title = "Dulcet"
        window.titleVisibility = .visible
        window.titlebarAppearsTransparent = false
        window.isMovableByWindowBackground = false
        window.isReleasedWhenClosed = false
        window.setFrame(NSRect(x: 0, y: 0, width: width, height: height), display: false)
        window.makeKeyAndOrderFront(nil)
        NSApplication.shared.activate(ignoringOtherApps: true)
        RunLoop.main.run(until: Date(timeIntervalSinceNow: 0.08))
        let initialResolvedScale = try observeResolvedRenderingScale(
            stage: "window-before-host-construction-initial",
            window: window
        )
        RunLoop.main.run(until: Date(timeIntervalSinceNow: 0.02))
        let resolvedWindowBackingScale = try observeResolvedRenderingScale(
            stage: "window-before-host-construction-settled",
            window: window
        )
        guard resolvedWindowBackingScale == initialResolvedScale else {
            throw CaptureError.renderingEnvironmentMismatch(
                "window backing scale changed before host construction: "
                    + "initial=\(initialResolvedScale) settled=\(resolvedWindowBackingScale)"
            )
        }

        let scene = DulcetCaptureView(store: store, variant: variant)
            .frame(maxWidth: .infinity, maxHeight: .infinity)
            .environment(\.colorScheme, appearance.colorScheme)
            .environment(\.locale, Locale(identifier: "en_US_POSIX"))
            .environment(\.calendar, calendar)
            .environment(\.timeZone, TimeZone(secondsFromGMT: 0)!)
            .environment(\.controlActiveState, .key)
            .environment(\.displayScale, CaptureRenderingPolicy.captureScale)
            .background(Color(nsColor: NSColor.windowBackgroundColor))
        let hostingView = makeHostingView(
            rootView: scene,
            appearance: requestedAppearance,
            captureScale: CaptureRenderingPolicy.captureScale
        )
        window.contentView = hostingView
        RunLoop.main.run(until: Date(timeIntervalSinceNow: 0.08))
        window.layoutIfNeeded()
        hostingView.layoutSubtreeIfNeeded()
        hostingView.displayIfNeeded()

        let attachedRenderingScale = try observeResolvedRenderingScale(
            stage: "window-after-host-attachment",
            window: window
        )
        guard attachedRenderingScale == resolvedWindowBackingScale else {
            throw CaptureError.renderingEnvironmentMismatch(
                "window backing scale changed across host attachment: "
                    + "before=\(resolvedWindowBackingScale) after=\(attachedRenderingScale)"
            )
        }
        try validateRenderingEnvironment(
            stage: "window-after-host-attachment",
            captureScale: CaptureRenderingPolicy.captureScale,
            hostLayerContentsScale: hostingView.layer?.contentsScale,
            effectiveAppearance: hostingView.effectiveAppearance,
            requestedAppearance: appearance.appKitName
        )

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
            pixelsWide: capturePixelWidth,
            pixelsHigh: capturePixelHeight,
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
        let bitmapPixelsPerPoint = CGFloat(bitmap.pixelsWide) / bitmap.size.width
        guard bitmapPixelsPerPoint == CaptureRenderingPolicy.captureScale else {
            throw CaptureError.renderingEnvironmentMismatch(
                "bitmap pixels-per-point expected="
                    + "\(CaptureRenderingPolicy.captureScale) "
                    + "observed=\(bitmapPixelsPerPoint)"
            )
        }
        guard let bitmapContext = NSGraphicsContext(bitmapImageRep: bitmap) else {
            throw CaptureError.bitmapAllocation
        }
        bitmapContext.cgContext.setAllowsFontSmoothing(false)
        bitmapContext.cgContext.setAllowsFontSubpixelPositioning(false)
        bitmapContext.cgContext.setAllowsFontSubpixelQuantization(false)

        // This loop detects within-process movement only. Before the host was constructed, the
        // window was ordered on-screen and its backing scale was observed equal to its screen's
        // scale twice, then required to remain unchanged across host attachment. The ambient window
        // scale does not drive rendering: one fixed capture scale owns SwiftUI layout, the host
        // layer, and bitmap pixels-per-point so layout and rasterization share the same grid.
        func renderFrame() throws -> Data {
            captureView.layoutSubtreeIfNeeded()
            captureView.displayIfNeeded()
            bitmapContext.cgContext.setShouldSmoothFonts(false)
            bitmapContext.cgContext.setShouldSubpixelPositionFonts(false)
            bitmapContext.cgContext.setShouldSubpixelQuantizeFonts(false)
            captureView.displayIgnoringOpacity(captureView.bounds, in: bitmapContext)
            guard let tiff = bitmap.representation(using: .tiff, properties: [:]) else {
                throw CaptureError.bitmapAllocation
            }
            return tiff
        }

        var previousFrame = try renderFrame()
        let firstFrameFinished = DispatchTime.now().uptimeNanoseconds
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
        let stableFrameFinished = DispatchTime.now().uptimeNanoseconds
        // Reported on the PASS line so a within-process movement is observable without conflating
        // it with the stable cross-process fork this loop cannot repair.
        CaptureSettleStatistics.record(attempts: settledAttempts)
        let layoutDiagnostics = collectLayoutDiagnostics(
            window: window,
            captureView: captureView,
            hostingView: hostingView
        )

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

        let record = CaptureRecord(
            appearance: appearance.rawValue,
            captureProvenance: "rendered-current-run",
            captureBoundsHeightPoints: Int(captureBounds.height),
            captureBoundsWidthPoints: Int(captureBounds.width),
            captureBoundsXPoints: Int(captureBounds.origin.x),
            captureBoundsYPoints: Int(captureBounds.origin.y),
            controlActiveState: "key",
            file: filename,
            fixtureState: state.rawValue,
            hostLayerContentsScale: Double(CaptureRenderingPolicy.captureScale),
            jpegBytes: boundJPEG.count,
            layoutDisplayScale: Double(CaptureRenderingPolicy.captureScale),
            bitmapPixelsPerPoint: Double(bitmapPixelsPerPoint),
            layoutDiagnostics: layoutDiagnostics,
            pinnedControlSha256: nil,
            settleAttempts: settledAttempts,
            firstComparisonMatched: settledAttempts == 1,
            sha256: sha256Hex(boundJPEG),
            variant: variantName,
            windowBackingScaleFactor: Double(resolvedWindowBackingScale),
            windowFrameHeightPoints: Int(windowFrame.height),
            windowFrameWidthPoints: Int(windowFrame.width)
        )
        let timing = CaptureRenderTiming(
            appearance: appearance.rawValue,
            file: filename,
            firstComparisonMatched: settledAttempts == 1,
            fixtureState: state.rawValue,
            phase: timingPhase,
            renderStartToFirstFrameMilliseconds: milliseconds(
                from: renderStarted,
                to: firstFrameFinished
            ),
            renderStartToStableFrameMilliseconds: milliseconds(
                from: renderStarted,
                to: stableFrameFinished
            ),
            mainEntryToStableFrameMilliseconds: milliseconds(
                from: mainEntryUptimeNanoseconds,
                to: stableFrameFinished
            ),
            settleAttempts: settledAttempts,
            variant: variantName
        )
        return RenderedCapture(record: record, timing: timing)
    }

    @MainActor
    private static func collectLayoutDiagnostics<Content: View>(
        window: NSWindow,
        captureView: NSView,
        hostingView: NSHostingView<Content>
    ) -> CaptureLayoutDiagnostics {
        var nativeControls: [CaptureNativeControlLayoutFact] = []

        func visit(_ view: NSView, path: String) {
            if view is NSTextField || view is NSButton {
                let control = view as? NSControl
                let cell = control?.cell
                let textField = view as? NSTextField
                let button = view as? NSButton
                let placeholder = nonempty(textField?.placeholderString)
                let buttonTitle = nonempty(button?.title)
                let controlKind: String
                if view is NSSecureTextField {
                    controlKind = "secure-text-field"
                } else if view is NSTextField {
                    controlKind = "text-field"
                } else {
                    controlKind = "button"
                }
                let font: NSFont?
                if let textField {
                    font = textField.font
                } else if let button {
                    font = button.font
                } else {
                    font = nil
                }
                let effectiveLayer = effectiveLayerContentsScale(for: view, window: window)
                nativeControls.append(CaptureNativeControlLayoutFact(
                    hierarchyPath: path,
                    semanticKey: "\(controlKind):\(placeholder ?? buttonTitle ?? path)",
                    controlKind: controlKind,
                    className: String(describing: type(of: view)),
                    cellClassName: cell.map { String(describing: type(of: $0)) },
                    accessibilityIdentifier: nonempty(view.accessibilityIdentifier()),
                    accessibilityLabel: nonempty(view.accessibilityLabel()),
                    placeholder: placeholder,
                    buttonTitle: buttonTitle,
                    frameInCapturePoints: pointRect(view.convert(view.bounds, to: captureView)),
                    boundsSizePoints: pointSize(view.bounds.size),
                    intrinsicContentSizePoints: pointSize(view.intrinsicContentSize),
                    cellSizePoints: cell.map { pointSize($0.cellSize) },
                    cellDrawingRectPoints: cell.map {
                        pointRect($0.drawingRect(forBounds: view.bounds))
                    },
                    cellTitleRectPoints: cell.map {
                        pointRect($0.titleRect(forBounds: view.bounds))
                    },
                    alignmentRectInsetsPoints: captureInsets(view.alignmentRectInsets),
                    firstBaselineOffsetFromTopPoints: Double(view.firstBaselineOffsetFromTop),
                    lastBaselineOffsetFromBottomPoints: Double(view.lastBaselineOffsetFromBottom),
                    fontMetrics: font.map(captureFontMetrics),
                    layerContentsScale: view.layer.map { Double($0.contentsScale) },
                    effectiveLayerContentsScale: Double(effectiveLayer.scale),
                    effectiveLayerContentsScaleSource: effectiveLayer.source,
                    isFlipped: view.isFlipped
                ))
            }
            for (index, subview) in view.subviews.enumerated() {
                visit(subview, path: "\(path).\(index)")
            }
        }
        visit(hostingView, path: "host")

        return CaptureLayoutDiagnostics(
            collectionStage: "after-two-identical-frames-before-jpeg-encoding",
            root: CaptureRootLayoutFacts(
                coordinateSystem: "appkit-capture-view-points",
                captureViewIsFlipped: captureView.isFlipped,
                windowFramePoints: pointRect(window.frame),
                windowContentLayoutRectPoints: pointRect(window.contentLayoutRect),
                captureViewFramePoints: pointRect(captureView.frame),
                captureViewBoundsPoints: pointRect(captureView.bounds),
                hostingViewFrameInCapturePoints: pointRect(
                    hostingView.convert(hostingView.bounds, to: captureView)
                ),
                hostingViewBoundsPoints: pointRect(hostingView.bounds),
                hostingViewIntrinsicContentSizePoints: pointSize(
                    hostingView.intrinsicContentSize
                ),
                hostingViewFirstBaselineOffsetFromTopPoints: meaningfulBaselineOffset(
                    hostingView.firstBaselineOffsetFromTop
                ),
                hostingViewLastBaselineOffsetFromBottomPoints: meaningfulBaselineOffset(
                    hostingView.lastBaselineOffsetFromBottom
                ),
                hostingViewLayerContentsScale: hostingView.layer.map {
                    Double($0.contentsScale)
                }
            ),
            nativeControls: nativeControls
        )
    }

    private static func pointSize(_ size: NSSize) -> CapturePointSize {
        CapturePointSize(width: Double(size.width), height: Double(size.height))
    }

    private static func pointRect(_ rect: NSRect) -> CapturePointRect {
        CapturePointRect(
            x: Double(rect.origin.x),
            y: Double(rect.origin.y),
            width: Double(rect.size.width),
            height: Double(rect.size.height)
        )
    }

    private static func captureInsets(_ insets: NSEdgeInsets) -> CaptureInsets {
        CaptureInsets(
            top: Double(insets.top),
            left: Double(insets.left),
            bottom: Double(insets.bottom),
            right: Double(insets.right)
        )
    }

    private static func captureFontMetrics(_ font: NSFont) -> CaptureFontMetrics {
        CaptureFontMetrics(
            fontName: font.fontName,
            familyName: font.familyName,
            pointSize: Double(font.pointSize),
            ascender: Double(font.ascender),
            descender: Double(font.descender),
            leading: Double(font.leading),
            capHeight: Double(font.capHeight),
            xHeight: Double(font.xHeight),
            boundingRect: pointRect(font.boundingRectForFont)
        )
    }

    private static func nonempty(_ value: String?) -> String? {
        guard let value, !value.isEmpty else { return nil }
        return value
    }

    private static func meaningfulBaselineOffset(_ value: CGFloat) -> Double? {
        guard value != .leastNormalMagnitude else { return nil }
        return Double(value)
    }

    @MainActor
    private static func effectiveLayerContentsScale(
        for view: NSView,
        window: NSWindow
    ) -> (scale: CGFloat, source: String) {
        var candidate: NSView? = view
        while let current = candidate {
            if let layer = current.layer {
                return (
                    layer.contentsScale,
                    "view:\(String(describing: type(of: current)))"
                )
            }
            candidate = current.superview
        }
        return (window.backingScaleFactor, "window-backing-scale")
    }

    @MainActor
    private static func makeHostingView<Content: View>(
        rootView: Content,
        appearance: NSAppearance,
        captureScale: CGFloat
    ) -> NSHostingView<Content> {
        var hostingView: NSHostingView<Content>!
        appearance.performAsCurrentDrawingAppearance {
            hostingView = NSHostingView(rootView: rootView)
            hostingView.sizingOptions = []
            hostingView.appearance = appearance
            hostingView.wantsLayer = true
            hostingView.layer?.contentsScale = captureScale
            hostingView.layer?.backgroundColor = NSColor.windowBackgroundColor.cgColor
        }
        return hostingView
    }

    private static func validateRenderingEnvironment(
        stage: String,
        captureScale: CGFloat,
        hostLayerContentsScale: CGFloat? = nil,
        effectiveAppearance: NSAppearance? = nil,
        requestedAppearance: NSAppearance.Name? = nil
    ) throws {
        if let hostLayerContentsScale,
           hostLayerContentsScale != captureScale {
            throw CaptureError.renderingEnvironmentMismatch(
                "\(stage) host layer contents scale expected="
                    + "\(captureScale) "
                    + "observed=\(hostLayerContentsScale)"
            )
        }
        if let effectiveAppearance, let requestedAppearance,
           effectiveAppearance.bestMatch(from: [requestedAppearance]) != requestedAppearance {
            throw CaptureError.renderingEnvironmentMismatch(
                "\(stage) appearance expected=\(requestedAppearance.rawValue) "
                    + "observed=\(effectiveAppearance.name.rawValue)"
            )
        }
    }

    @MainActor
    private static func observeResolvedRenderingScale(
        stage: String,
        window: NSWindow
    ) throws -> CGFloat {
        guard window.isVisible else {
            throw CaptureError.renderingEnvironmentMismatch(
                "\(stage) window is not visible"
            )
        }
        guard let screen = window.screen else {
            throw CaptureError.renderingEnvironmentMismatch(
                "\(stage) visible window is not attached to a screen"
            )
        }
        let windowScale = window.backingScaleFactor
        let screenScale = screen.backingScaleFactor
        guard screenScale > 0, windowScale == screenScale else {
            throw CaptureError.renderingEnvironmentMismatch(
                "\(stage) window backing scale is not resolved to its attached screen: "
                    + "window=\(windowScale) screen=\(screenScale)"
            )
        }
        return windowScale
    }

    private static func milliseconds(from start: UInt64, to end: UInt64) -> Double {
        Double(end - start) / 1_000_000
    }

    private static func writeProcessDiagnostics(
        options: CaptureOptions,
        mainEntryUptimeNanoseconds: UInt64,
        preflightStarted: UInt64,
        preflightFinished: UInt64,
        renderTimings: [CaptureRenderTiming]
    ) throws {
        guard let diagnosticsOutput = options.diagnosticsOutput else { return }
        let diagnosticsWriteStarted = DispatchTime.now().uptimeNanoseconds
        let firstRecordedStableFrame = renderTimings.first {
            $0.phase == "recorded-reference"
        }?.mainEntryToStableFrameMilliseconds
        let diagnostics = CaptureProcessDiagnostics(
            schemaVersion: 1,
            clock: "dispatch-monotonic-uptime",
            durationUnit: "milliseconds",
            comparedSurfacePolicy: "external-sidecar-outside-byte-compared-capture-root",
            preflightDurationMilliseconds: milliseconds(
                from: preflightStarted,
                to: preflightFinished
            ),
            mainEntryToFirstRecordedStableFrameMilliseconds: firstRecordedStableFrame,
            mainEntryToDiagnosticsWriteMilliseconds: milliseconds(
                from: mainEntryUptimeNanoseconds,
                to: diagnosticsWriteStarted
            ),
            renders: renderTimings
        )
        let encoder = JSONEncoder()
        encoder.outputFormatting = [.prettyPrinted, .sortedKeys, .withoutEscapingSlashes]
        let data = try encoder.encode(diagnostics) + Data("\n".utf8)
        try FileManager.default.createDirectory(
            at: diagnosticsOutput.deletingLastPathComponent(),
            withIntermediateDirectories: true
        )
        try data.write(to: diagnosticsOutput, options: .atomic)
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
            hostLayerContentsScale: nil,
            jpegBytes: data.count,
            layoutDisplayScale: nil,
            bitmapPixelsPerPoint: nil,
            layoutDiagnostics: nil,
            pinnedControlSha256: observedHash,
            settleAttempts: nil,
            firstComparisonMatched: nil,
            sha256: observedHash,
            variant: "deliberately-bad-control",
            windowBackingScaleFactor: nil,
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
