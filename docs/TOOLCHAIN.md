# Toolchain matrix

`gradle/libs.versions.toml` is the machine-readable source of truth. Build files consume its entries;
CI checks the Apple values duplicated in Xcode's native project format.

## Pinned matrix

| component | pin | source and compatibility note |
|---|---:|---|
| Kotlin / Kotlin Multiplatform | 2.4.10 | Kotlin stable release and compatibility guide |
| Gradle wrapper | 9.5.0 | highest Gradle version supported by Kotlin 2.4.10 |
| Android Gradle / Android-KMP plugin | 9.1.0 | highest AGP version supported by Kotlin 2.4.10; dedicated KMP library plugin |
| Xcode | 26.4.1 | Kotlin 2.4.x compatibility line is Xcode 26.4; hosted image path is selected explicitly |
| XcodeGen | 2.46.0 | generates the checked-in Xcode project from `apple/project.yml` |
| JDK | 21 | Gradle and Kotlin build runtime; JVM and Android bytecode target 17 |
| Ktor | 3.5.2 | resolved from Maven Central |
| SQLDelight | 2.3.2 | resolved from Maven Central |
| kotlinx-coroutines | 1.11.0 | resolved from Maven Central |
| kotlinx-serialization | 1.11.0 | resolved from Maven Central |
| Compose Multiplatform | 1.11.0 | current stable JetBrains Compose release |
| Compose for TV Material | 1.1.0 | coordinate is `androidx.tv:tv-material` |
| Compose for TV Foundation | 1.0.0 | resolved from Google Maven |
| Media3 | 1.11.0 | resolved from Google Maven |
| Android compileSdk / targetSdk / minSdk | 36 / 36 / 26 | current stable Android SDK; project support floor remains API 26 |
| macOS / iOS / tvOS deployment | 14.0 / 17.0 / 17.0 | Kotlin framework flags and Xcode targets are checked together in CI |
| Navidrome | 0.63.2 | OCI index `sha256:9012939114fbb1bb641b81cf96dec5ded15f0aafefe8d47a511d7cb919658e40`; Linux amd64 manifest `sha256:38246ebb80d6f7e2724eecab4acafa7b14ec66ae800b2454aa6da4c19f80a9ce`; upstream Darwin arm64 asset `sha256:f621f1b730af93d200d3400e549f60b34dd796d27801ebf9b6ab219df6ac7048` |
| Linux ffmpeg | 6.1.1 | observed inside the pinned Navidrome Linux amd64 image; the image digest pins the full build |
| Darwin ffmpeg | 9.0.1 | Homebrew arm64 Tahoe bottle `sha256:ef92660f6622395d2d5de0c4c5e23747e99cdc5cf82f257f6eb401d222e9f080`; `tools/conformance-env/pins.json` locks the complete 15-formula closure (root plus 14 dependencies), including each version, revision, dependency list, bottle rebuild, URL, and SHA-256 |

The Darwin and Linux ffmpeg builds deliberately differ. Byte-level transcode assertions belong only
to the Linux reference leg. Darwin uses its pinned build for Darwin-specific transport and loader
behavior, not byte identity.

## CI action pins

| action | pin |
|---|---:|
| `actions/checkout` | 7.0.1 · `3d3c42e5aac5ba805825da76410c181273ba90b1` |
| `actions/setup-java` | 5.7.0 · `b6effb05e454b25005698d916606bdc6ffcbf961` |
| `android-actions/setup-android` | 4.0.1 · `40fd30fb8d7440372e1316f5d1809ec01dcd3699` |
| `gradle/actions/setup-gradle` | 6.3.0 · `9c971963bec38e04b3d30dcc455b5382be2fdbfb` |

## Hosted CI baseline

The first complete cold `apple-ci` job on the standard GitHub-hosted `macos-26` runner started at
`2026-08-20T22:11:30Z` and completed successfully at `2026-08-20T22:14:37Z`: 187 seconds wall-clock.
The run built all five Kotlin/Native Apple frameworks, ran the empty-core macOS test, built the macOS,
iOS/iPadOS, and tvOS shells, and checked the linked binaries' deployment floors.

The calibrated `apple-ci` timeout is **15 minutes**. It is the next five-minute boundary above four
times the observed cold duration (12 minutes 28 seconds), leaving 11 minutes 53 seconds of headroom
while still releasing a hosted Apple concurrency slot promptly if the job hangs. Recalibrate from a
representative sample when the Phase 1 suite materially changes the workload.

Evidence: [GitHub Actions run 32423091935](https://github.com/legitimate-apps/dulcet/actions/runs/32423091935).

## Upgrade policy

Upgrade one matrix component per pull request. Resolve every new coordinate against its primary
artifact index, update the licence audit, and require all affected targets to be green before merge.
Never combine a toolchain bump with feature work. Wrapper upgrades include the official distribution
checksum and wrapper validation.
