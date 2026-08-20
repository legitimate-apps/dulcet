// swift-tools-version: 6.0
import PackageDescription

let package = Package(
    name: "DulcetKit",
    platforms: [
        .macOS(.v14),
        .iOS(.v17),
        .tvOS(.v17),
    ],
    products: [
        .library(name: "DulcetKit", targets: ["DulcetKit"]),
    ],
    targets: [
        .target(name: "DulcetKit"),
        .testTarget(name: "DulcetKitTests", dependencies: ["DulcetKit"]),
    ]
)
