// swift-tools-version: 6.2

import PackageDescription

let package = Package(
    name: "ResourceLoaderSpike",
    platforms: [.macOS(.v14)],
    products: [
        .executable(name: "resource-loader-spike", targets: ["ResourceLoaderSpike"]),
    ],
    targets: [
        .executableTarget(name: "ResourceLoaderSpike"),
    ],
    swiftLanguageModes: [.v5]
)
