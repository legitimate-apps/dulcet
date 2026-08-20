# Dependency licence audit

The Phase 0 core has no production library dependencies. Its Kotlin test dependency and build plugins
are checked by `./gradlew :core:licensee`; that task is part of `core-ci` and rejects licences outside
the explicit allowlist.

The version catalog also reserves the reviewed Phase 1 coordinates below. Reserving a version is not
the same as shipping it. Each becomes part of the generated transitive report when first declared by a
source set.

| dependency or tool | pin | licence | Phase 0 disposition |
|---|---:|---|---|
| Kotlin Gradle plugin and standard library | 2.4.10 | Apache-2.0 | build and scaffold runtime |
| Android Gradle / Android-KMP plugin | 9.1.0 | Apache-2.0 | build only |
| Licensee Gradle plugin | 1.14.1 | Apache-2.0 | build audit only |
| Ktor | 3.5.2 | Apache-2.0 | reserved, not declared |
| SQLDelight | 2.3.2 | Apache-2.0 | reserved, not declared |
| kotlinx-coroutines | 1.11.0 | Apache-2.0 | reserved, not declared |
| kotlinx-serialization | 1.11.0 | Apache-2.0 | reserved, not declared |
| Compose Multiplatform | 1.11.0 | Apache-2.0 | reserved, not declared |
| Compose for TV Material / Foundation | 1.1.0 / 1.0.0 | Apache-2.0 | reserved, not declared |
| Media3 | 1.11.0 | Apache-2.0 | reserved, not declared |
| Navidrome reference server | 0.63.2 | GPL-3.0 | CI service only; not linked, copied, or distributed in Dulcet |
| Linux ffmpeg in Navidrome image | 6.1.1 | GPL-3.0-or-later build | CI tool only; not linked, copied, or distributed in Dulcet |
| Darwin ffmpeg Homebrew bottle | 9.0.1 | GPL-3.0-or-later build | CI tool only; not linked, copied, or distributed in Dulcet |
| checkout / setup-java / setup-android / setup-gradle Actions | 7.0.1 / 5.7.0 / 4.0.1 / 6.3.0 | MIT | CI automation only |

The GPL-licensed server and transcoder are separate test processes. No GPL code is incorporated into
the Apache-2.0 application or its distributed binaries. Generated reports are build evidence and are
not committed because they include platform-specific resolution details.
