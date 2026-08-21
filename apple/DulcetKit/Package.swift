// swift-tools-version: 6.0
import PackageDescription

let package = Package(
    name: "DulcetKit",
    defaultLocalization: "en",
    platforms: [
        .macOS(.v14),
        .iOS(.v17),
        .tvOS(.v17),
    ],
    products: [
        .library(name: "DulcetKit", targets: ["DulcetKit"]),
        .executable(name: "DulcetCapture", targets: ["DulcetCapture"]),
    ],
    targets: [
        .target(
            name: "DulcetKit",
            resources: [.process("Resources")]
        ),
        .executableTarget(
            name: "DulcetCapture",
            dependencies: ["DulcetKit"],
            resources: [.copy("Resources/PinnedControls")]
        ),
        .testTarget(name: "DulcetKitTests", dependencies: ["DulcetKit"]),
    ]
)
