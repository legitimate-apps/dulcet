# Dulcet

Dulcet is an open-source multiplatform client for OpenSubsonic-compatible music servers, with
Navidrome as the reference and primary tested server. The baseline compatibility contract is
Subsonic REST API 1.16.1; OpenSubsonic extensions are negotiated at runtime.

The project uses a Kotlin Multiplatform core with native platform shells. macOS ships first.

[![core-ci](https://github.com/legitimate-apps/dulcet/actions/workflows/core-ci.yml/badge.svg)](https://github.com/legitimate-apps/dulcet/actions/workflows/core-ci.yml)
[![parity-gate](https://github.com/legitimate-apps/dulcet/actions/workflows/parity-gate.yml/badge.svg)](https://github.com/legitimate-apps/dulcet/actions/workflows/parity-gate.yml)

## Phase 0 scaffold

The scaffold contains the shared core targets, native Apple shell targets, dependency licence gate,
feature-parity gate, and hosted CI baseline. No OpenSubsonic production behavior is implemented yet.

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
