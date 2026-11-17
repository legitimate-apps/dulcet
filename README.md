# Dulcet

Dulcet is an open-source multiplatform client for OpenSubsonic-compatible music servers, with
Navidrome as the reference and primary tested server. The baseline compatibility contract is
Subsonic REST API 1.16.1; OpenSubsonic extensions are negotiated at runtime.

The project uses a Kotlin Multiplatform core with native apps on Mac, iPhone, iPad and Apple TV
(SwiftUI) and on Android phones and Android TV (Jetpack Compose). Dulcet Server is the companion
server; with it, the apps use the Dulcet API, and with every other server they use OpenSubsonic.

[![core-ci](https://github.com/legitimate-apps/dulcet/actions/workflows/core-ci.yml/badge.svg)](https://github.com/legitimate-apps/dulcet/actions/workflows/core-ci.yml)
[![parity-gate](https://github.com/legitimate-apps/dulcet/actions/workflows/parity-gate.yml/badge.svg)](https://github.com/legitimate-apps/dulcet/actions/workflows/parity-gate.yml)

## Status

The apps browse, search, stream and download, and keep playlists, favorites and play counts in sync.
Apple builds are in internal TestFlight testing. What works on each platform is tracked in
[`FEATURES.yml`](FEATURES.yml), and the planned work is in [`ROADMAP.md`](ROADMAP.md).

## Build

Use the checked-in Gradle wrapper:

```sh
./gradlew :core:allMetadataJar :core:jvmTest :core:testAndroidHostTest :core:bundleAndroidMainAar :core:licensee
```

Apple builds also require the Xcode version pinned in [`docs/TOOLCHAIN.md`](docs/TOOLCHAIN.md). The
Xcode targets invoke the Gradle wrapper through a pre-build phase.

## Licence

Copyright 2026 Legitimate LLC. Licensed under the Apache License, Version 2.0. See
[`LICENSE`](LICENSE) and [`NOTICE`](NOTICE).
