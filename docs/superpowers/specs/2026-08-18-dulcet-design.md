# Dulcet — Design Spec

**Status: PHASE 0 COMPLETE (2026-08-20).** Every open question is answered — §26 is now a decision
record rather than a queue — and the design has been through independent adversarial review. The
public Kotlin Multiplatform and Xcode scaffold, hosted CI baseline, measured timeout, and enforced
default-branch controls satisfy the Phase 0 exit criteria in §25.

**Date:** 2026-08-18
**Revision:** 13 — adversarial review narrowed the Phase 1 resource-loader claim to the manifest-
rewrite contract the strengthened spike actually measures. Revision 12 recorded the original,
insufficient first-segment observation. Revision 11 recorded Phase 0 completion from hosted CI and repository API evidence. Revision
10 removed the remaining identifiers of unrelated applications. Revision 9 verified the Apple signing path by API and promoted it from ASSUMED to OBSERVED; the
only remaining unknown is the API key's CREATE permission, now the Phase-2 dry run's first act.
Revision 8 re-confirmed and closed OQ-3's OS floors and removed the last private context from the
repository tree. Revision 7 folded in a primary-source premise audit: a non-existent Maven coordinate, the
missing App Sandbox requirement, Guideline 4.2.7, transcode backpressure (HTTP 429 + `Retry-After`),
an unpinned transcoder that would have made every transcode test a silent pass, and an unenforceable
regression gate. §28 records what changed, what was already fixed, and where we disagree with the
audit. Revision 6 answered every open question (§26) and specified DEV/PROD release channels (§22). Revision 5 scrubbed private context and a second product identity from these
public files; the rule that prevents it is recorded in §28. Revision 4 decided `${BUNDLE_PREFIX}` as
`com.legitimateapps.dulcet`, decoupled from the marketing domain, demoting OQ-7 out of Phase 0. Revision 3 moved CI to GitHub-hosted runners and closed
OQ-1 by correction (§21.3). Revision 2 was the rewrite after an
adversarial review pass, which found revision 1's transcoding contract, sync consistency
model, playback identity model, stream-validation strategy, Apple facade contract, transport-test
design, `FEATURES.yml` evidence cycle and public-repo CI guard all wrong or incomplete. §28 records
every change.

**Author:** the Dulcet maintainers. Every revision after the first has been through an independent
adversarial review pass; §28 records what each one changed.
**Repo:** `legitimate-apps/dulcet`
**Domain and identifiers — two INDEPENDENT constants, defined here and nowhere else.** Every product
URL in this document is written as `https://${DOMAIN}/...` and every bundle/application identifier as
`${BUNDLE_PREFIX}.*`, so each can be settled with a one-line edit rather than a sweep.

| constant | value | status |
|---|---|---|
| `${BUNDLE_PREFIX}` | **`com.legitimateapps.dulcet`** | **DECIDED.** Not pending, and **not derived from `${DOMAIN}`** |
| `${DOMAIN}` | **`getdulcet.com`** | **Chosen, pending purchase** — see OQ-7 |

**Why `${BUNDLE_PREFIX}` is decoupled from the marketing domain, and why this one.** A bundle
identifier is **immutable once an App Store Connect record exists**, so tying it to a marketing domain
would make a permanent identifier hostage to a name that can lapse, get rebranded, or fail to renew.
`com.legitimateapps.dulcet` avoids that:

- **OBSERVED 2026-08-18:** `legitimateapps.com` is already registered and owned by the LLC (on
  Cloudflare nameservers), so the namespace cannot lapse out from under the app.
- **OBSERVED 2026-08-18:** the `com.legitimateapps.*` namespace is already in use by other published
  Legitimate LLC applications, so Dulcet joining it is the consistent choice rather than a new
  precedent.
- It keeps the app inside the **Legitimate LLC** identity that actually signs it (team 3LTL47SJ8C),
  which is where an identifier belongs.
- It is **stable regardless of what `${DOMAIN}` ends up being**, including if the marketing domain
  changes later.

🚨 **The identifier rule, stated positively:** the bundle identifier prefix is
`com.legitimateapps.dulcet`. It is immutable once an App Store Connect record exists. **Never publish
this app under any other namespace, and never reuse a namespace from an unrelated project that happens
to be present in a local build environment.** (§24.1)

**Anything needing a stable, non-churning identifier derives it from `${BUNDLE_PREFIX}`, never from
`${DOMAIN}`** — the Keychain service identifier (§13.1) and the Android `applicationId` (§23.2) both
do. `${DOMAIN}` reaches only support/privacy URLs, the review demo server, and marketing surfaces,
none of which are permanent.

(`dulcet.fm` was the earlier working assumption and was dropped: $87.85/yr to register *and* renew.
Nothing was ever purchased.)

**How to read this:** §1–§4 are the contract with the outside world. §5–§9 are the architecture.
§10–§18 are the subsystems where this project will get hurt if the design is wrong. §19–§25 are
process. §26 is what is still undecided. §27 lists sources. §28 is the revision record.

Every technical claim is marked **OBSERVED** (verified against a named primary source) or
**ASSUMED** (a design decision or an inference). An unmarked sentence is a design decision. Do not
promote ASSUMED to OBSERVED without naming what you measured.

---

## 1. What Dulcet is

**One-sentence definition — use this wording verbatim in the README, the store listing, and every
delegate brief:**

> **Dulcet is an open-source multiplatform client for OpenSubsonic-compatible music servers, with
> Navidrome as the reference and primary tested server. The baseline compatibility contract is
> Subsonic REST API 1.16.1; OpenSubsonic extensions are negotiated at runtime.**

Short form: *a native multiplatform OpenSubsonic music client, tested primarily against Navidrome.*

### 1.1 Terminology, stated correctly

**OBSERVED** (https://opensubsonic.netlify.app/docs/ and .../opensubsonic-changes/):

- **Subsonic** is both the original server product and the common name for its REST API.
- **OpenSubsonic** is an openly and collaboratively maintained, backward-compatible evolution of that
  API, developed by participating server and client projects. It is **not** a Navidrome project, not
  a fork, and not vendor-controlled.
- **Navidrome** is one server implementing the Subsonic baseline plus a growing set of OpenSubsonic
  extensions.

**Do not call OpenSubsonic a "strict superset."** Its change taxonomy is clarifications, non-breaking
extensions and new additions; the mandatory core is small (expanded response metadata, a publicly
accessible `getOpenSubsonicExtensions`, defined token-auth behavior) and nearly everything else is
optional per server. Extension versions are independent capability axes, not one "OpenSubsonic level."

**Do not call Dulcet a "Navidrome client."** That would only be accurate if we depended on
Navidrome's private `/api` surface. §2.4 forbids it.

### 1.2 Product goals

1. Genuinely native on every surface — not one web view in six wrappers, not one Compose UI stretched
   onto an Apple TV.
2. Correctness against the protocol as specified, proven by an executable conformance suite against a
   real server rather than by "it worked on my library."
3. Offline-capable: a local metadata cache from Phase 2, downloadable media from Phase 3 (§25), and a
   play history that survives being offline and reconciles when the server returns.
4. Honest about capability: if the server or the device cannot do a thing, the UI for that thing is
   absent, not present-and-broken.
5. Private by construction: no analytics, no telemetry, no third-party crash reporter (§13.4), and a
   defined policy for what the local caches expose to the OS (§13.6).

### 1.3 Non-goals for v1

- No Jellyfin adapter (§9.5 explains why the seam still exists).
- No server-side management: no library administration, no user administration, no tag editing.
- No video.
- **Podcasts and audiobooks receive ordinary-song behavior in v1** and nothing more — no chapters, no
  long-form completion rules, no podcast endpoints (§18.7). We do not claim to support them.
- No last.fm / ListenBrainz direct scrobbling. Dulcet scrobbles to the server; the server's relays
  are the server's business.
- No server-side play-queue sync (§14.4). Not "default off" — not built.
- No `sonicSimilarity` UI (§18.5). Negotiated and recorded only.
- No CarPlay / Android Auto (deferred, not precluded — §12.11).
- No Intel Macs, no Intel simulators (§4).
- No telemetry or analytics, ever. A goal, not a deferral.

---

## 2. Server compatibility contract

### 2.1 The wire contract

**ASSUMED — binding, reproduce in the README:**

> **Server requirements:** Subsonic REST API 1.16.1 with salted-token authentication. The client
> sends `v=1.16.1`. OpenSubsonic servers are detected through the OpenSubsonic response metadata and
> `getOpenSubsonicExtensions`; unavailable extensions are disabled individually. Classic Subsonic
> 1.16.1 servers operate in reduced-compatibility (`legacySubsonic`) mode.

**OBSERVED** (https://www.subsonic.org/pages/api.jsp): `v` is the Subsonic REST **schema version the
client implements** — not the Navidrome version, not an OpenSubsonic version, not a feature bitset.
The original rule is that major versions must match and the client's minor version must be no greater
than the server's.

Accepted consequences: we hard-code `v=1.16.1`, so we cannot claim support for servers implementing
an older API, and there is no downgrade ladder in v1. Equally, we may not send a lower `v` "for
safety" while calling endpoints introduced later.

### 2.2 Reference server

> **Reference compatibility target: Navidrome 0.63.2.** Other server/version combinations are
> supported per `docs/COMPATIBILITY.md`.

We do **not** say "0.63.2 or later required." Nothing in the design depends on behavior first
available there. The floor is §2.1. A future feature that creates a version floor records it on its
own `FEATURES.yml` row, never as a blanket product requirement.

**OBSERVED** (https://github.com/navidrome/navidrome/releases, checked 2026-08-17): v0.63.2, released
2026-07-11 at commit `be10f89`, is the newest release; there is no later one.

### 2.3 Extensions the reference server advertises

**OBSERVED at the pinned release.** Revision 2 sourced this from `master` and hedged it as
"ASSUMED-for-0.63.2" pending CONF-02. That hedge is no longer needed: `server/subsonic/opensubsonic.go`
was read **at tag `v0.63.2`** and every row below matches exactly, `sonicSimilarity` really is gated on
server configuration, and `apiKeyAuthentication` really is absent. **The table is OBSERVED at
v0.63.2**, and **CONF-02's job is therefore regression detection, not discovery** — which is what it was
always actually for.

| extension | versions | what Dulcet does with it |
|---|---|---|
| `transcodeOffset` | 1 | `timeOffset` seek on the **legacy** `stream` path (§12.5) |
| `formPost` | 1 | POST-with-form-body for non-media API calls, keeping credentials out of query strings (§13.2) |
| `songLyrics` | 1, 2 | structured / synced lyrics (§18.4) |
| `indexBasedQueue` | 1 | **recorded only**, no code path in v1 (§14.4) |
| `transcoding` | 1 | `getTranscodeDecision` + `getTranscodeStream` (§12.5) — **not** `stream?format=` |
| `playbackReport` | 1 | **not called in v1** (§15.4) |
| `sonicSimilarity` | 1 | **recorded only**, no UI in v1 (§18.5) |

**`sonicSimilarity` is conditional on server configuration**, so "the server advertises exactly this
set" is only true for a stated configuration. CONF-02 asserts the set **for the pinned container with
the pinned configuration**, and treats an unknown newly advertised extension as informational drift,
not a failure (§20.3).

**OBSERVED:** Navidrome does not advertise `apiKeyAuthentication`. Dulcet does not implement API-key
auth and never speculatively tries an API key against a server that does not advertise the extension.

### 2.4 Hard rule: no server-private surfaces

Dulcet talks to `/rest/*` only. Never Navidrome's private `/api`, never its web UI, and never an
undocumented behavior that is not registered as a named quirk in §17 with a conformance test pinning
it. Violating this silently converts "OpenSubsonic client" into "Navidrome client."

---

## 3. Surfaces

| surface | UI toolkit | shell | phase |
|---|---|---|---|
| macOS | SwiftUI (AppKit-backed) | `DulcetMac` target | Phase 2 — **ships first** |
| iOS | SwiftUI | `DulcetiOS` target | Phase 3 |
| iPadOS | SwiftUI, adaptive layout, same target as iOS | `DulcetiOS` | Phase 3 |
| tvOS | SwiftUI focus engine | `DulcetTV` target | Phase 5 |
| Android phone/tablet | Compose + Material 3 | `android/app` | Phase 4 |
| Android TV | Compose + Compose for TV (`androidx.tv:tv-material`) | `android/tv` | Phase 5 |

**OBSERVED** (https://developer.android.com/design/ui/tv/): Android TV navigation is D-pad-driven,
focus is the primary state, and TV layouts require explicit focus order and immediate visual focus
feedback. Android TV is therefore a **separate module with its own composables**, not an
`isTelevision()` branch. Same reasoning for tvOS: the focus engine makes a shared iOS/tvOS view layer
a false economy.

🚨 **The Compose-for-TV Gradle coordinate is `androidx.tv:tv-material`, NOT `androidx.tv:tv-material3`.**
The *package* is `androidx.tv.material3`; the *artifact* is `tv-material`. Conflating the two produces a
build file that cannot resolve, and it is the kind of error that survives review because the package
name looks like a coordinate. **OBSERVED 2026-08-18** by direct probe of Google's Maven index, with a
positive control to rule out a blind instrument:

| coordinate | result |
|---|---|
| `androidx.tv:tv-material3` | **HTTP 404 — does not exist** |
| `androidx.tv:tv-material` | HTTP 200 — `latest=1.1.0 release=1.1.0` |
| `androidx.tv:tv-foundation` | HTTP 200 — `latest=1.0.0 release=1.0.0` |
| `androidx.compose.material3:material3` *(control)* | HTTP 200 |

Two related rules, both **OBSERVED** from Google's TV guidance
(https://developer.android.com/training/tv/playback/compose):

- **Do not mix `androidx.tv:tv-material` with `androidx.compose.material3:material3` in the TV module.**
  Verbatim: *"mixing Compose Material with Compose Material from Compose for TV can result in
  unexpected behavior"* — each library carries its own `MaterialTheme` object. The TV module depends on
  `tv-material` **instead of** `material3`, not alongside it.
- **TV Lazy Layouts were deprecated out of `tv-foundation`.** Use core Compose foundation lazy lists on
  TV. `tv-foundation` remains a dependency for other TV primitives, but nothing new should be written
  against its lazy layouts.

⚠️ Google's own guide page still shows `tv-material:1.0.0` while the release channel is at 1.1.0 — the
guide lags the artifact. **Pin from the Maven index (§4.4), never from a guide page.**

**tvOS support level — do not oversell it.** **OBSERVED**
(https://kotlinlang.org/docs/native-target-support.html): `macosArm64` is Kotlin/Native **Tier 1**;
`tvosArm64` and `tvosSimulatorArm64` are **Tier 2**. The primary source is sharper than a paraphrase,
so quote it: **Tier 1** — *"The target is regularly tested on CI to be able to **compile and run**. We
**provide** a source and binary compatibility between compiler releases."* **Tier 2** — *"The target is
regularly tested on CI to be able to compile but **may not be automatically tested to be able to
run**. We're **doing our best** to provide source and binary compatibility between compiler
releases."*

**And within Tier 2 there is a further asymmetry that matters for Phase 5:** Kotlin's support table
marks `tvosSimulatorArm64` as able to run tests, and leaves `tvosArm64` **blank** — you can run Kotlin
tests on the tvOS *simulator*, never on an Apple TV *device*. §25's Phase-5 exit criterion ("focus
navigation verified on a real Apple TV") is a **Swift-side UI test**, not a Kotlin one, so the two are
compatible — but the spec must say which layer is verified where rather than leave it implied:
**Kotlin core logic is verified on the tvOS simulator; the tvOS shell's focus behaviour is verified on
real hardware.**

The correct phrasing:

> **Kotlin Multiplatform supports tvOS device and simulator ARM targets. `tvosArm64` and
> `tvosSimulatorArm64` are Tier 2 — compile-tested, with weaker compatibility and runtime guarantees
> than Tier 1. The Intel-simulator `tvosX64` target exists but is deprecated.**

**OBSERVED — prior-art correction.** It is false to claim there is no open-source native tvOS
Subsonic client. [Shelv](https://github.com/gatzenga/Shelv) is a public GPL-3.0 SwiftUI client with a
tvOS app target and a tvOS Siri extension. The defensible statement is that *the tvOS Subsonic client
market is sparse and most catalogued apps are proprietary or paid, but at least one active
open-source native implementation exists.* Shelv is GPL-3.0 and is not a code donor (§24.2). The
Navidrome client directory is not proof of nonexistence; its metadata lags.

---

## 4. Supported OS, architecture, and toolchain

### 4.1 Apple

| | minimum OS | architectures | Kotlin/Native target |
|---|---|---|---|
| macOS | **14.0** | **arm64 only (Apple Silicon)** | `macosArm64` (Tier 1) |
| iOS / iPadOS | **17.0** | arm64 device, arm64 simulator | `iosArm64`, `iosSimulatorArm64` |
| tvOS | **17.0** | arm64 device, arm64 simulator | `tvosArm64`, `tvosSimulatorArm64` (Tier 2) |

- **Apple Silicon only.** `macosX64` and `tvosX64` are deprecated in Kotlin/Native and we will not
  start a new product on a deprecated target. No universal binary, no Rosetta story.
- **Why 14.0 / 17.0 — corrected 2026-08-18, and the correction matters.** Revision 2 justified the
  floor with "the `@Observable` macro **and the modern navigation APIs**." **Only the first half is
  true.** **OBSERVED**, from Apple's availability data:

  | API | iOS | macOS | tvOS |
  |---|---|---|---|
  | `Observation.Observable` | **17.0** | **14.0** | **17.0** |
  | `SwiftUI.NavigationStack` | 16.0 | 13.0 | 16.0 |
  | `SwiftUI.NavigationSplitView` | 16.0 | 13.0 | 16.0 |

  Navigation is available a full major version **below** the floor on every platform. **`@Observable`
  is the sole thing setting it**, and the honest statement of the trade is therefore narrower than it
  was: we are spending one major version of installed base to get fine-grained observation.

  That is still defensible for this app — `ObservableObject`/`@Published` invalidates an entire view
  on any published change, and the library screens here scroll tens of thousands of rows, where
  per-property invalidation is a real difference rather than an ergonomic one. **The floor stands at
  macOS 14 / iOS 17 / tvOS 17 on that basis alone.**

  ✅ **RE-CONFIRMED 2026-08-18 on the corrected facts. The floor stands. Do not reopen.** OQ-3 was
  originally answered while half its justification was wrong, so it was flagged and put back to the
  maintainer rather than kept quietly. It was re-examined on `@Observable` alone and survived:

  - **`ObservableObject` invalidates an entire view on any published change; `@Observable` invalidates
    per property.** The hardest screen in this app scrolls tens of thousands of rows, so this is a real
    performance difference **on the exact surface that must never stutter** — not an ergonomic
    preference.
  - **The reach cost is close to nil.** iOS 17 shipped September 2023 and is three OS generations old.
    Dropping to 16/13/16 buys a sliver of installed base and pays for it with the coarser observation
    model **permanently**.

  **The 16/13/16 alternative was considered and rejected on that trade.** 🚫 A future session must not
  re-derive this floor from the old (false) navigation premise — and must not reopen it on the grounds
  that "the stated reason was wrong." **The reason was wrong; the conclusion stands, and it has now
  been checked against the right reason.**
- **The OS floor is set in two places and both must agree.** **OBSERVED:** Kotlin/Native's defaults are
  *"For iOS and tvOS, **15.0**. For macOS, **12.0**."* The Xcode app target has its own deployment
  target. The produced framework's minimum must be **compatible with** the app's — the app target does
  not override it. Our floors sit above Kotlin's defaults, so nothing conflicts in practice, but the
  Phase-0 scaffold must set both explicitly and a CI check must assert the linked framework's
  `LC_BUILD_VERSION` minimum matches the app target. (Revision 1 claimed the framework "will not itself
  block a lower OS"; that was wrong.)
- 🚨 **The Kotlin-side override is a raw compiler flag, not a Gradle DSL property.** This is named
  because "there must be a DSL knob for this" is the first thing an implementer assumes, and it costs
  them an afternoon. The mechanism is:

  ```kotlin
  binaries.configureEach {
      freeCompilerArgs += "-Xoverride-konan-properties=minVersion.macos=14.0"
      freeCompilerArgs += "-Xoverride-konan-properties=minVersion.tvos=17.0"
  }
  ```

  Phase 0's exit criterion — "both OS-floor settings asserted by a CI check" — refers to this flag and
  the Xcode deployment target agreeing.
- Simulator targets exist only for tests and local development. They are never shipped.

**Targets are enumerated explicitly. Never use the `ios()` / `tvos()` / `watchos()` shortcuts.**
**OBSERVED** (https://kotlinlang.org/docs/multiplatform-compatibility-guide.html): those shortcuts are
deprecated "due to complexity and potential confusion," the recommended practice is to list targets so
the Kotlin Gradle plugin creates `iosMain`/`iosTest` intermediate source sets from the default
hierarchy template. **The removal already happened** — this is history, not a roadmap item, and
revision 2 of this spec had it as a future event while `CLAUDE.md` had it right. **OBSERVED**, the
full deprecation ladder verbatim: *"1.9.20: report a warning when `ios()`, `watchos()`, and `tvos()`
target shortcuts are used… **2.1.0**: report an error when target shortcuts are used… **2.2.0**:
**remove** target shortcut DSL from the Kotlin Multiplatform Gradle plugin"*. Current stable Kotlin is
far past that (§4.4), so writing `ios()` is not a deprecation warning — the DSL symbol is gone. The
sibling `fromPreset` presets API was removed in the same release. The declaration is:

```
macosArm64(); iosArm64(); iosSimulatorArm64(); tvosArm64(); tvosSimulatorArm64()
androidTarget(); jvm()   // jvm exists only for the conformance suite (S20)
```

### 4.2 Android

| | value | reason |
|---|---|---|
| `minSdk` | **26** (Android 8.0) | a **support-scope decision**, not a technical impossibility below it. Notification channels can be applied conditionally and much of `java.time` is available through core-library desugaring; 26 is chosen to avoid maintaining two background-execution and media-notification paths for a device population we are not targeting. Revisit with real reach data, not with a claim of inevitability. |
| `targetSdk` / `compileSdk` | pinned in the toolchain matrix (§4.4) | not "latest at implementation time" |
| ABIs shipped | whatever transitive deps require | **we ship no native libraries of our own** — the core compiles to JVM bytecode on Android, and SQLDelight's `AndroidSqliteDriver` uses the platform's framework SQLite, so Dulcet contributes zero `.so` files |
| Android TV | same `minSdk`, `LEANBACK_LAUNCHER`, `android.software.leanback` | separate module (§3) |

### 4.3 Apple framework packaging and Xcode linkage

- **Static framework** (`isStatic = true`). **OBSERVED** (KMP framework configuration docs): framework
  configuration lives in `<target>.binaries.framework { baseName; isStatic }`, optionally aggregated by
  `XCFramework(...)`.
- **Linkage rule, stated precisely because "embed" and "static" are routinely conflated:** the static
  framework appears in **Link Binary With Libraries** and **must not** appear in **Embed Frameworks**.
  Embedding a static framework as a runtime payload produces an app that ships bytes it never loads
  and, on some configurations, a validation failure at submission. **ASSUMED / to be configured:** the
  Run Script phase must be ordered **before** the compile phase and write into `$BUILT_PRODUCTS_DIR`.
  That is a project configuration Phase 0 has to create — not a property of the tooling — and a
  delegate will otherwise read it as already true.
- **How Xcode gets it:** direct integration via the Gradle `embedAndSignAppleFrameworkForXcode` task
  from a Run Script build phase. ⚠️ **OBSERVED caveat:** that task *"only registers if the
  `binaries.framework` configuration option is declared."* A scaffold that declares targets but not
  framework binaries produces a Run Script phase calling a task that does not exist — a Phase-0 trap
  with a confusing error message. **Consequence, stated out loud:** Xcode builds are not hermetic —
  they require a JDK and the Gradle wrapper on the build machine. GitHub's macOS runner images ship
  both, so this is free in CI; it is a stated contributor prerequisite in `CLAUDE.md`.
- **`assembleXCFramework` is not part of the app build** and is not run in app CI. It exists only to
  produce a distributable artifact if we ever publish the core separately, and until we do, the task
  is not wired into any workflow. (Revision 1 listed it in `apple-ci.yml`; that was redundant build
  cost.)
- **Not** CocoaPods, and **not** a checked-in binary XCFramework.

### 4.4 Toolchain matrix — a Phase 0 deliverable

**"Latest stable at implementation time" is not a reproducible decision.** Kotlin, Gradle, AGP, Xcode,
Ktor, SQLDelight, coroutines, Compose and Media3 are mutually constrained, and Kotlin publishes a
compatibility guide for exactly this reason.

Phase 0 produces `gradle/libs.versions.toml` plus a short `docs/TOOLCHAIN.md` pinning: Kotlin, Gradle,
AGP (or Google's KMP Android plugin, whichever the Kotlin compatibility guidance recommends at pinning
time), Xcode, JDK, Ktor, SQLDelight, kotlinx-coroutines, kotlinx-serialization, Compose, Compose-for-TV,
Media3, `compileSdk`/`targetSdk`, **the pinned ffmpeg build (§20.2)**, and the Apple deployment targets
— **with the upgrade policy**: one version bump per PR, CI green on all targets before merge, and the
matrix is the single source of truth referenced by every build file. No version literal appears
anywhere else.

**Starting values, OBSERVED 2026-08-18 by direct probe of Google Maven and Maven Central** (not from
documentation, which lags — see §3). Phase 0 begins from these rather than from a search:

| component | version | note |
|---|---|---|
| Kotlin | 2.4.x stable | far past the 2.2.0 shortcut removal (§4.1) |
| Ktor | **3.5.2** | `ktor-client-darwin-tvosarm64` and `-tvossimulatorarm64` both publish at this version |
| SQLDelight | **2.3.2** | `native-driver-tvosarm64` and `-tvossimulatorarm64` both publish at this version |
| kotlinx-coroutines | **1.11.0** | |
| Media3 | **1.11.0** | `minSdk` 23 |
| `androidx.tv:tv-material` | **1.1.0** | the coordinate, **not** `tv-material3` (§3) |
| `androidx.tv:tv-foundation` | **1.0.0** | TV Lazy Layouts deprecated out of it (§3) |

**Every dependency coordinate in this document has been resolved against a live index**, not asserted
from memory. Any coordinate added later gets the same treatment before it lands in a build file: the
`tv-material3` error (§3) existed in three documents and would have failed on the first Gradle sync.

**`minSdk 26` has headroom on every one of these** — read from the published artifact manifests:
media3-exoplayer 23, media3-session 23, `tv-material` 23, compose material3 21, SQLDelight
android-driver 23. Nothing in the dependency set requires above 26, so §4.2's floor is a support-scope
choice and nothing else.

---

## 5. Architecture: fat core, native shells

```
                 +-------------------------------------------------------+
                 |            core  (Kotlin Multiplatform)               |
                 |  provider/  transport/  auth/  cache/  sync/          |
                 |  queue/     playback/   scrobble/ download/  error/   |
                 |  facade/  (narrow Apple-facing boundary)              |
                 +----------+--------------------------------+-----------+
                            |                                |
        Kotlin framework (ObjC interop)              Kotlin/JVM (.aar)
                            |                                |
            +---------------v---------------+   +------------v--------------+
            |  Apple shells (SwiftUI)       |   |  Android shells (Compose) |
            |  macOS / iOS / iPadOS / tvOS  |   |  phone-tablet / TV        |
            |  AVPlayer + resource loader   |   |  Media3 + DataSource      |
            |  Keychain / URLSession / MPNP |   |  Keystore / FGS / Session |
            +-------------------------------+   +---------------------------+
```

### 5.1 The boundary, stated correctly

An earlier formulation said the platform owns "only UI and the audio engine." **That is wrong and it
fails first on downloads and background playback.** The binding statement:

> **The shared core owns provider logic, cache schema, synchronization, download and playback policy,
> queue state, scrobbling rules, and domain errors. Platform adapters own UI, audio execution, secure
> storage, filesystem access, lifecycle and background execution, system media integration, and other
> OS services.**

Platform adapters own or adapt: secure credential storage; sandboxed filesystem locations and file
handles; background download **execution**; background playback and audio-session lifecycle;
interruption/route/headset/HDMI/focus handling; system media controls and Now Playing; network
reachability, cost and constrained-network signals; power and lifecycle events; logging and redaction
sinks; OS notifications and background scheduling; and URL/resource loading wherever the engine needs
authenticated or validated bytes (§12.4).

The core owns download *policy, queueing, retry state, records, prioritization and reconciliation*. It
cannot own the executor: iOS background `URLSession`, Android foreground services and `WorkManager`,
macOS sandboxing and tvOS storage rules are not interchangeable.

### 5.2 Two different extension mechanisms — do not conflate them

Revision 1 said "the core declares small `expect`/interface contracts." That merged two mechanisms
with different constraints. Precisely:

- **`expect`/`actual`** is for adapters implemented **in Kotlin**, compiled per target: `PlatformClock`
  (§18.8), `FileStore`, `LogSink`, the SQLDelight `DriverFactory`, and the HTTP engine selection.
  These never cross the ObjC boundary.
- **Exported Kotlin interfaces implemented in Swift** are for adapters that must be written in
  Swift because they wrap Apple frameworks: `PlaybackEngine`, `SecureStore`, `DownloadExecutor`,
  `NowPlayingSink`, `Reachability`, `BackgroundScheduler`. These are ObjC-interop protocols, are
  subject to exportability rules, and their threading and lifetime contracts are part of §7.
- On Android those same six are plain Kotlin implementations of the same interfaces. **The interface
  is one; only the implementation language differs.**

Any adapter that could be written in Kotlin on all targets should be — Swift implementations exist
only where an Apple framework requires them.

---

## 6. Repository layout

```
dulcet/
  CORPUS.md  CLAUDE.md  FEATURES.yml  LICENSE  NOTICE
  gradle/libs.versions.toml
  docs/
    superpowers/specs/2026-08-18-dulcet-design.md   <- this file
    TOOLCHAIN.md  COMPATIBILITY.md  CONFORMANCE.md
    notes/provider-seam-and-jellyfin.md             <- non-binding (S9.5)
  core/                     KMP library (commonMain, appleMain, androidMain, jvmMain)
  core-conformance/         test module; drives a real Navidrome (S20)
  apple/
    Dulcet.xcodeproj        three app targets
    DulcetKit/              Swift Package: the Swift half of the facade + shared SwiftUI
    DulcetMac/ DulcetiOS/ DulcetTV/
  android/
    app/  tv/  shared-ui/
  tools/                    seed-corpus generator, wire-fixture server, parity-gate script
  .github/workflows/
```

**`DulcetKit` is a Swift Package** (`Package.swift`, platforms macOS 14 / iOS 17 / tvOS 17, one
library product). It does **not** link the Kotlin framework itself — the framework is produced per app
target by the Run Script phase (§4.3), and `DulcetKit` declares the Kotlin module as an
`unsafeFlags`-free dependency resolved at app-target link time. `DulcetKit` owns: the Swift value
types the UI consumes, the conversion layer from exported Kotlin classes to those value types, the
Swift-concurrency wrappers around the callback facade (§7.3), and the Swift implementations of the
adapter protocols in §5.2.

---

## 7. The Apple-facing facade

**OBSERVED** (https://kotlinlang.org/docs/native-swift-export.html): direct **Swift Export is Alpha**
— generic type erasure, no cross-language inheritance, collection-derived-type restrictions,
direct-Xcode-integration requirements, no migration tooling.

**OBSERVED** (https://kotlinlang.org/docs/native-objc-interop.html): the production path is framework
export through Objective-C interop. It works, but produces less idiomatic Swift, boxes nullable
primitives, copies collections when bridging, exposes awkward top-level names, and cannot import
arbitrary pure-Swift APIs into Kotlin unless they are Objective-C-visible.

**Decision:** ObjC interop. Swift Export is an experiment for Phase 5 at the earliest, never on the
critical path.

### 7.1 What the interop actually produces — correcting revision 1

Revision 1 said the facade exposes "immutable, Swift-friendly DTOs — flat structs of primitives."
**That is not what ObjC interop produces.** Exported Kotlin classes arrive in Swift as **classes**,
not value types. So the contract is two-layer:

- **Kotlin side:** small, final, immutable classes with primitive/`String` properties, `List` of such
  classes where unavoidable, and no generics in the exported signature.
- **`DulcetKit` side:** hand-written Swift **structs** that copy from those classes at the boundary.
  The UI never holds an exported Kotlin object. This is the only way the UI gets value semantics, and
  it is also what stops a Kotlin object graph being retained by SwiftUI state.

The copy is explicit, is generated by no tool, and is covered by a Swift test per type.

### 7.2 Async: completion handlers plus an operation handle

**OBSERVED:** Kotlin's ObjC export renders a `suspend` function as a completion-handler method; Swift
can import that as `async`, but Kotlin documents the Swift-concurrency mapping as experimental and it
does not by itself give us cancellation with defined semantics.

**Decision — one concrete pattern, no alternatives:**

```
// Kotlin, exported
fun loadAlbum(id: String, completion: (AlbumDto?, DomainErrorDto?) -> Unit): OperationHandle
interface OperationHandle { fun cancel() }
```

- Every asynchronous facade operation is a **non-suspending function taking a completion closure and
  returning an `OperationHandle` synchronously**, before the operation completes.
- `DulcetKit` wraps each one in `withTaskCancellationHandler` to present a Swift `async` API to the UI.
  Swift `Task` cancellation therefore calls `OperationHandle.cancel()`.
- **`cancel()` semantics, specified so two implementers cannot disagree:** cancellation is
  best-effort and idempotent; the completion closure is **always** invoked exactly once; a cancelled
  operation completes with `DomainErrorDto` of kind `Transport.Cancelled`; a cancellation that races a
  success delivers the success (last writer at the completion barrier wins, and the barrier is a
  single atomic in the Kotlin implementation); `cancel()` after completion is a no-op; `cancel()`
  cancels child work including in-flight requests but **never** rolls back a committed database
  transaction; `stop()` on the owning subsystem cancels all outstanding handles it issued.
- **Threading:** every completion and every event callback is invoked on the **main thread**. The core
  does its work on its own dispatchers and hops to main at the boundary. This is stated as a hard rule
  because the alternative — "callbacks on an unspecified thread" — produces UI races that appear only
  under load.
- **No Kotlin exception ever crosses the boundary.** The facade catches everything and maps it to
  `DomainErrorDto`. An uncaught Kotlin exception in an exported function terminates the process, so
  this is a correctness rule, not a style rule.
- **Event streams** are `subscribeX(listener) -> Subscription`, with `Subscription.close()`.
  Listeners are held weakly on the Swift side and strongly on the Kotlin side until closed;
  `DulcetKit` closes them in `deinit`. High-frequency streams (position) are **coalesced in the core**
  to the documented cadence (§12.3) so the boundary is never the backpressure point.

### 7.3 Forbidden across the facade — review-blocking

SQLDelight entities, generic repositories, raw `Flow`, `Result`/exception-heavy signatures, arbitrary
Kotlin collections, Kotlin `sealed` hierarchies with generic payloads, and any name that mangles into
an unusable Swift symbol. The facade module sets `@ObjCName` wherever mangling would produce one, and
CI diffs the generated Objective-C header on every facade change (§20.5).

---

## 8. Android integration

The core is consumed as a Kotlin/JVM `.aar`; the Android shells use its Kotlin API directly, including
`Flow`. **This asymmetry is deliberate and must not shape the core**: the public API is designed for
the constrained Apple facade and Android simply gets a nicer view of it. An API that is convenient on
Android and impossible on Apple is wrong.

Playback: Media3 `ExoPlayer` behind a `MediaSessionService`. **OBSERVED** (androidx/media): a
`MediaSession` wraps any `Player` via `MediaSession.Builder`; `MediaController.addListener` receives
player events; and `setAudioAttributes(attributes, handleAudioFocus)` delegates audio focus to the
player. (`setAudioAttributes` is declared on `Player` and inherited by `ExoPlayer`; there is also an
`ExoPlayer.Builder` overload.)

**Controller authorization uses `onConnectAsync`, not `onConnect`.** **OBSERVED** — `onConnect`'s own
javadoc says *"It's **strongly recommended to override `onConnectAsync(MediaSession, ControllerInfo)`**
instead of this method. This method will be deprecated as soon as the `@UnstableApi` annotation is
removed."* Cheap to get right now, annoying to migrate once six shells exist.

Two further Media3 facts worth building on rather than rediscovering:

- **`media3-ui-compose` and `media3-ui-compose-material3` exist** and ship state holders
  (`PlayPauseButtonState`, `CurrentMediaItemState`, and siblings) that the Android shells would
  otherwise hand-roll. Evaluate them before writing player-UI state by hand.
- Media3's `minSdk` is **23**, comfortably below our `minSdk 26` (§4.2), so nothing in the media stack
  constrains the floor.

**OBSERVED** (https://developer.android.com/reference/androidx/media3/common/Player): Media3 has **no
callback for ordinary position progression** — clients needing progress query the current position at
intervals. Therefore the Android adapter **owns a periodic sampler** that emits `PositionChanged`
(§12.2). "The core never polls the engine" remains true; the *adapter* samples. The sampler runs only
while the session is playing, uses the monotonic clock (§18.8), is suspended while paused or
buffering, and continues at the same cadence during background playback.

---

## 9. The provider seam

### 9.1 Why it exists now with exactly one implementation

The seam is cheap **before** the domain model spreads through SQLDelight, queue policy, UI state and
download records, and very expensive afterwards. Introduce it at v1, ship exactly one production
implementation (Subsonic), and hold six invariants (§9.5) rather than a speculative adapter.

### 9.2 Identifiers

```
ProviderItemId(providerInstanceId: String, rawId: String)
```

- `rawId` is **always a String**. **OBSERVED** (https://www.navidrome.org/docs/developers/subsonic-api/):
  Navidrome IDs are strings, commonly hashes or UUIDs. Any integer parse is a bug.
- `providerInstanceId` is a **locally generated UUID** minted when the account is added — not the URL
  and not the username, because both change and neither is unique.
- IDs are opaque: never constructed, parsed, ranged over, or type-inferred.

### 9.3 The interface

Deliberately **not** shaped like the Subsonic endpoint list. `streamURL` and `markPlayed` are banned:
the first collapses a resolution *decision* into a string and cannot carry expiry, headers, container,
codec, range support or a transcode decision; the second collapses a playback *session* into one
terminal event and cannot express progress, resume position, or "played" versus "position saved."

```
interface MusicProvider {
    val capabilities: ProviderCapabilities

    suspend fun browse(request: BrowseRequest): Page<MusicItem>
    suspend fun getItem(id: ProviderItemId): MusicItem
    suspend fun search(request: SearchRequest): SearchPage

    suspend fun getPlaylist(id: ProviderItemId): Playlist
    suspend fun mutatePlaylist(mutation: PlaylistMutation): Playlist

    suspend fun resolvePlayback(request: PlaybackRequest): PlaybackPlan
    suspend fun recordPlaybackEvent(event: PlaybackEvent)

    suspend fun getArtwork(request: ArtworkRequest): ArtworkResult
    suspend fun getLyrics(request: LyricsRequest): LyricsResult

    suspend fun getUserCapabilities(): UserCapabilities
    fun libraryChangeSource(): LibraryChangeSource
}
```

🚨 **`recordPlaybackEvent` is deliberately NOT named `reportPlayback`.** The OpenSubsonic
`playbackReport` extension's endpoint is literally called **`reportPlayback`**, and §15.4 forbids
calling it in v1. §19.4 hands delegates one-line briefs, so a brief reading *"implement
`reportPlayback`"* is an obvious route to wiring up the exact endpoint the spec forbids. The collision
was a name away from a real defect; the rename removes it.

`libraryChangeSource()` returns a **source with checkpoint/resume semantics, not a push stream**,
because Subsonic has no change feed (§16.1). Naming it a source rather than `Flow<Change>` keeps the
interface honest that nothing is being pushed.

### 9.4 `PlaybackPlan` — what a bare URL cannot be

`resolvePlayback` returns a plan that is one of two variants:

**`RemotePlan`**

| field | why |
|---|---|
| `url`, `headers`, `queryAuth: Boolean` | how to fetch it; Subsonic media URLs use query auth |
| `expiresAt: Instant?`, `refresh: RefreshPolicy` | what to do on expiry or a mid-stream 401 |
| `mediaSourceId: String?` | which of possibly several sources for one logical item |
| `deliveryProtocol` | `HttpProgressive` or `Hls` — the transcoding extension can describe HLS (§12.5) |
| `container`, `codec`, `mimeType` | what the validator and engine must agree to expect |
| `expectedBitrateKbps`, `channels`, `sampleRate`, `bitDepth` | UI display and transcode surfacing |
| `supportsRangePredicted: Boolean` | a **prediction**; the engine's own report is authoritative (§12.7) |
| `transcode: TranscodeDecision` | `DirectPlay` \| `Transcoded(opaqueParams, reasons, targetStream)` \| `LegacyHint(format, maxBitRate)` \| `Unknown` |
| `seekStrategyPredicted` | `NativeSeek` \| `ServerOffset(param)` \| `NotSeekable` — also a prediction |
| `validation: ValidationMode` | `Inline` (the loader validates the real response) or `PreflightAdvisory(evidence)` (§12.4) |

**`LocalPlan`** — for a downloaded file (§14.5). Carries a file handle/URL, the container/codec of the
**file on disk** (which may differ from the server's original if it was downloaded transcoded), the
byte length, an integrity marker, and nothing about auth, expiry or refresh. **This is a first-class
variant, not a `file://` URL squeezed into `RemotePlan`.** Every code path that consumes a plan must
handle both, and the compiler enforces it.

### 9.5 Seam invariants (binding) and the Jellyfin note (not binding)

**Binding — six invariants. These are what the seam is actually for:**

1. IDs are opaque, provider-scoped strings (§9.2).
2. An item has **no single `parentId`**; parentage belongs to the browse path, not the item.
3. An item has **`credits: List<Credit(role, name, id?)>`**, never a single `artistId`.
4. One logical item may have **several media sources**; `mediaSourceId` is always present in the model
   even when Subsonic never populates it.
5. Playback is reported as a **session of events**, never one terminal "played" call.
6. **Every duration and position in the domain model is a `Duration`**, never a bare number, because
   other providers express time in ticks.

**Not binding:** the detailed Jellyfin mapping table lives in `docs/notes/provider-seam-and-jellyfin.md`
as an architecture note. It is a sanity check when the interface changes, **not** a gate — requiring
every interface change to remain expressible for an unbuilt adapter would produce speculative
abstractions over real Subsonic needs.

**OBSERVED context for that note:** Jellyfin does not provide built-in Subsonic/OpenSubsonic support
and it is not on its currently visible public roadmap — issue #1308 is closed with no milestone and no
linked implementation. Third-party attempts exist (`JellySonic`, archived, last target 10.8;
`jellysub`, archived proxy, last release 2022; `johagan94/jellyfin-subsonic`, May 2026, MIT, a
two-commit scaffold implementing only the envelope, `ping` and `getLicense`) — none is a current,
mature compatibility layer. Do not claim Jellyfin has "promised never to support it." Jellyfin uses a
broad `BaseItemDto` hierarchy rather than a fixed artist/album/track tree; its playback resolution
takes a device profile and returns media-source identity, direct-play/direct-stream/transcode
decisions and stream selection; and its user state (favorite, liked, played, position) is a separate
structure, not one `scrobble` call.

---

## 10. Account setup and capability negotiation

### 10.1 URL normalization

A self-hosted client's first failure is almost always the URL, so this is specified rather than left
to the implementation. Given user input, Dulcet:

1. trims whitespace; rejects empty.
2. if no scheme, tries **`https` first**, then `http` only if the host qualifies for the local
   exception (§13.5).
3. accepts `host`, `host:port`, IPv6 literals in brackets, IDNs (converted to punycode for the
   request, displayed as entered), and an arbitrary base path.
4. **strips a trailing `/rest`, `/rest/`, or a trailing `.view` component** if the user pasted an
   endpoint URL, and says so in the UI.
5. normalizes the base path to have no trailing slash; endpoint URLs are built as
   `base + "/rest/" + endpoint + ".view"`. **`.view` is always appended** — it is the classic form and
   is accepted by servers that also accept the bare name.
6. preserves percent-encoding as given; never double-encodes.
   ⚠️ On step 5: appending `.view` is **OBSERVED** for the reference server — Navidrome registers both
   `/<path>` and `/<path>.view` for *every* endpoint, including the OpenSubsonic-only ones. The
   generalisation to *all* Subsonic-compatible servers is **ASSUMED and unsourced**. Low risk, but it
   is stated as a universal and is not one, so it is registered as **QUIRK-01** (§17) rather than
   carried as a fact.
7. shows the user the exact final base URL before the first request.

### 10.2 The login sequence

1. Normalize the URL (§10.1).
2. **Probe `getOpenSubsonicExtensions` without authentication parameters.** **OBSERVED**
   (https://opensubsonic.netlify.app/docs/endpoints/getopensubsonicextensions/): the endpoint must be
   publicly accessible. We still send `c` and `f=json`; we send **no** `u`/`t`/`s`, and we send `v`
   because the envelope requires it. A non-envelope response (HTML, a proxy login page, a WAF
   challenge, a captive portal) is recorded but **not yet interpreted**.
3. **Authenticate with `ping`** using `v=1.16.1` and the salted token (§13.1). **The extension-probe
   result is not trusted until `ping` succeeds** — otherwise a reverse-proxy login page in front of an
   unknown server gets classified as `legacySubsonic` and we start sending credentials to it.
4. Read envelope metadata: `openSubsonic`, `type`, `serverVersion`, `version`.
5. Fetch user capabilities/roles (`getUser`): download, playlist, share, jukebox, admin.
6. Construct a typed `CapabilitySet` from four independent inputs: protocol level, OpenSubsonic
   extensions, user permissions, known quirks (§17).
7. Cache it per account. Refresh after each login and on the evidence rules in §10.4.

### 10.3 The failure modes must be distinguishable

| observation | classification | UI |
|---|---|---|
| DNS/TCP/TLS failure, timeout, no HTTP response | `ServerUnreachable` | "Can't reach the server" + the normalized URL + retry |
| TLS chain/hostname/expiry failure | `TlsUntrusted(reason)` | the specific reason + §13.5 guidance. **Never** an option to ignore it |
| HTTP reached; extension probe returns 404 or a non-envelope body; **and** `ping` then succeeds | `ExtensionListUnavailable` | login proceeds, server marked `legacySubsonic` |
| HTTP reached; extension probe non-envelope; **and** `ping` also fails to parse | `NotASubsonicServer` | "This doesn't look like a Subsonic server" — do not retry with credentials |
| envelope parsed; error code indicates bad credentials | `AuthenticationFailed` | "Wrong username or password" — never "server down" |
| envelope parsed; error code indicates a version mismatch | `ProtocolIncompatible` | show **what Dulcet sent** and **what the server reported** in its envelope. Do not promise a "required version" — the protocol does not reliably supply one |

**OBSERVED:** a missing `getOpenSubsonicExtensions` is consistent with a classic pre-OpenSubsonic
server. On `ExtensionListUnavailable`: do not fail login; mark `legacySubsonic`; mark every extension
unsupported; proceed on the classic baseline; hide extension-only UI; retain baseline capabilities
from protocol version and user roles.

### 10.4 Gates are conjunctions, and one error does not revoke a capability

```
featureEnabled = protocolSupports && extensionAdvertised && userPermitted
                 && deviceCapable && policyAllows && !knownQuirkBlocks
```

- "Can this user edit a playlist?" depends on the user's role **and** the playlist's `readonly` flag.
- "Can this device play this item?" depends on codecs, engine support, network policy and the server's
  transcode decision.
- `sonicSimilarity` presence is a server-**configuration** fact, not a version fact.

**Advertised capability and observed operational health are stored separately.** A single
unsupported-endpoint error does **not** mutate the advertised set — it may be transient,
permission-scoped, item-scoped, malformed-request-scoped, or caused by a proxy. Instead, each
capability carries a **circuit breaker**: three consecutive failures of the same endpoint with the same
error class, within one session, opens the breaker for that endpoint for the rest of the session and
surfaces a diagnostic. The advertised set is refreshed only at login and on explicit user action.

Every `FEATURES.yml` row names which inputs gate it (§19).

---

## 11. Data model, cache, and recovery

### 11.1 Storage engine

SQLDelight. **OBSERVED** (https://sqldelight.github.io/sqldelight/latest/multiplatform_sqlite): the
multiplatform pattern is `expect class DriverFactory { fun createDriver(): SqlDriver }` with
`AndroidSqliteDriver`, `NativeSqliteDriver(Schema, "name.db")` and `JdbcSqliteDriver`, with driver
dependencies declared per source set. We use `native-driver` on the four Apple targets,
`android-driver` on Android, and `sqlite-driver` on the `jvm` target used by the conformance suite —
which means the whole cache layer is testable on `ubuntu-latest` with no simulator.

### 11.2 Namespacing and multi-account

Every cached row is keyed by `server_id` (§9.2). Two accounts on one physical server are two
`server_id`s.

**Decision (resolves what revision 1 left open):** **queues are per-account, and exactly one account
is "active" for playback at a time.** A queue may not mix items from different `server_id`s. Switching
the active account stops playback, finalizes the current scrobble session, and swaps the queue. This
is chosen because a mixed queue would require per-item credential and capability switching mid-playback
with no user-visible model for what happens offline. Multi-account browsing (viewing library B while
playing from library A) is deferred and is not a v1 feature.

### 11.3 Core tables (schema intent)

`server_account` · `artist` / `album` / `track` (with the generation columns of §16.3) · `credit` ·
`playlist` / `playlist_entry` (explicit integer order) · `queue_entry` · `download` · `scrobble_outbox`
· `mutation_outbox` (§18.3) · `artwork_cache` · `resume_position` (§15.5) · `sync_checkpoint` ·
`schema_meta` (SQLDelight schema version, cache-format version, `committed_generation`).

### 11.4 Migrations and protected data

- SQLDelight `.sqm` migrations, with `verifySqlDelightMigration` in CI on every PR.
- **Protected data — must survive every migration:** `scrobble_outbox`, `mutation_outbox`,
  `download` rows plus their files, and `resume_position`. These are the only things not re-derivable
  from the server.
- **The gate is semantic, not textual.** Revision 1 proposed failing a migration that "touches" a
  protected table; that is unenforceable — destruction happens through table recreation, cascades,
  trigger changes, column reinterpretation and failed conversions while the name is preserved. The
  real gate: CI keeps a set of **fixture databases** from every released schema version, each seeded
  with protected rows and matching files on disk; every PR migrates all of them and asserts row-level
  semantic preservation plus file reconciliation.
- A cache rebuild is permitted when a migration is genuinely hard, but only through the tested path in
  §11.5, never as a silent `DROP`.

### 11.5 Corruption and recovery

Detection and behavior for each of: SQLite corruption (`SQLITE_CORRUPT`/`NOTADB`), schema open failure,
disk full during migration or sync, a `download` row whose file is missing, a file with no row
(orphan), an unreadable artwork entry, a crash between temp-file write and atomic promotion, and the
split state where the Keychain has credentials but the database was reset (or vice versa).

The **cache rebuild** path is a first-class, tested operation: quarantine the damaged database file
(kept for one release cycle for diagnosis), salvage protected data where readable, create a fresh
database, replay salvaged protected rows, re-scan the download directory to rebuild `download` rows
from files on disk, and run a full sync. The user is told what happened and what, if anything, was
lost.

---

## 12. Playback

### 12.1 Identity — three distinct ids, not one

Revision 1 used one `planId` for three different things and produced races. The model is:

| id | lifetime | owns |
|---|---|---|
| `QueueEntryId` | as long as the entry is in the queue | position in the queue, source context |
| `PlaybackSessionId` | one *play* of one entry | the scrobble accumulator, play start wall time, now-playing state |
| `AttemptId` | one resolved plan handed to the engine | engine events, validation evidence, transcode params |

Relationships: a `QueueEntry` may have many sessions over time (repeat-one, replay). A session has one
or more attempts (initial resolve, refresh after expiry, retry after failure, re-resolve for a
server-offset seek). **Every engine event carries its `AttemptId`; the core maps it to a session.**

**Transition table (normative):**

| transition | session | attempt | scrobble accumulator |
|---|---|---|---|
| start playing an entry | new | new | starts at zero |
| plan refresh (expiry / mid-stream 401) | same | new | preserved |
| retry after `FailedBeforeStart` | same | new | preserved (still zero) |
| server-offset seek (§12.7) | same | new | preserved |
| next queue item (manual or auto) | outgoing finalized, then new | new | outgoing evaluated, then new at zero |
| repeat-one | outgoing finalized, then new | new | outgoing evaluated, then new at zero |
| queue replaced wholesale | outgoing finalized | new | outgoing evaluated |

**Event acceptance rule (this is the fix for the drop-stale-events race):** an event for a superseded
`AttemptId` is **not** discarded outright. It is routed to its **session**, which is still live during a
same-session attempt change, and is applied only to session-scoped state (accumulator finalization,
terminal outcome). It may never mutate engine-scoped state of the current attempt. An event for a
session that has already been finalized is dropped and counted in a diagnostic.

### 12.2 `PlaybackEngine` — commands with outcomes, and events

A command-only engine is the most expensive shortcut available here. **Without an event contract the
core cannot distinguish "URL resolved" from "playback actually progressed," and the scrobble policy
will be wrong.**

**Commands are asynchronous operations with correlation ids and explicit outcomes**, not fire-and-forget
calls:

`prepare(attemptId, plan)` · `play()` · `pause()` · `stop()` · `seek(position)` · `setVolume(v)` ·
`setRate(r)` · `replaceCurrent(attemptId, plan)` · `preloadNext(attemptId, plan)` · `release()`

Each takes a `commandId` and produces exactly one of `CommandAccepted(commandId)`,
`CommandRejected(commandId, reason)`, or `CommandCompleted(commandId, result)`. A stale acknowledgement
is therefore distinguishable from a current one. Retries are owned by the **core**, never the adapter.

**`Server.Busy` overrides the core's own backoff schedule.** When a response carries `Retry-After`, the
core waits **at least** that long — the server has stated its own recovery time and a client that
substitutes a shorter schedule of its own is simply re-attacking the limiter. Rules: honour the header
value; apply jitter **on top of** it, never below it; cap the total wait at 60 s before surfacing the
failure to the user; and never let a `Server.Busy` retry silently occupy a playback session for longer
than the user would tolerate without UI feedback.

**Events (engine -> core).** This list is the **required minimum**, not a claim of completeness; adding
an event is a spec change, and no implementation may fake one to fit the list.

| event | payload | why the core needs it |
|---|---|---|
| `Preparing` | attemptId | show progress without claiming playback |
| `Ready` | attemptId, duration?, `seekability` | the engine's own `seekability` **overrides** the plan's prediction |
| `PlaybackProgressBegan` | attemptId, wallClock, mediaPosition | **starts the scrobble clock** (see the naming note below) |
| `Buffering` / `BufferingEnded` | attemptId, position | stall UI, and excluding stall time from listen time |
| `Paused` / `Resumed` | attemptId, position | pause accounting |
| `PositionChanged` | attemptId, mediaPosition, monotonicTime | threshold evaluation; coalesced to the §12.3 cadence |
| `DurationChanged` | attemptId, duration | some containers only report duration after decode starts |
| `SeekCompleted` / `SeekFailed` | attemptId, from, to | prevents seek-based completion cheating |
| `EndedNaturally` | attemptId, finalPosition | the clean completion signal |
| `Skipped` | attemptId, position, reason | user skip vs auto-advance |
| `FailedBeforeStart` | attemptId, DomainError | **never scrobble**; drives retry / next-source policy |
| `FailedAfterPartial` | attemptId, position, DomainError | may scrobble if the threshold was met |
| `RouteChanged` | old, new, `didPause` | Bluetooth / HDMI / speaker changes |
| `InterruptionBegan` / `InterruptionEnded` | shouldResume | call, alarm, another app taking focus |
| `AttemptReplaced` | oldAttemptId, newAttemptId | **same session** — refresh, retry, server-offset seek |
| `AdvancedToPreloaded` | oldAttemptId, newAttemptId | **session boundary** — gapless advance to the next entry |
| `RateChanged` | rate | a non-1.0 rate changes what "listened for N seconds" means |
| `EngineTornDown` | reason | OS reclaimed the engine (background limits, lifecycle) |
| `SourceRefreshRequired` | attemptId, reason | the engine hit a 401/expiry and needs a new plan |
| `ObservationResynced` | attemptId, state snapshot | emitted immediately after a listener attaches, so a late observer is not stuck waiting for the next transition |

**Naming note — why not `StartedAudible`.** Revision 1 called this `StartedAudible` and defined it via
`AVPlayer.timeControlStatus == .playing` / Media3 `isPlaying`. **Neither signal proves acoustic
output** — both are transport state and are true for silent material, zero volume, a muted output, or
a route with no hardware volume. The event is therefore named `PlaybackProgressBegan` and is defined
as: **media position is advancing under an unsuppressed playing state.** On Apple that is
`timeControlStatus == .playing` together with an observed increase in `currentTime`; on Android it is
`onIsPlayingChanged(true)` together with an observed position increase. If product copy says "audible,"
that is an operational approximation and must be labelled as one.

**Delivery rules.** Adapters may emit from any thread; **the core serializes every engine event onto a
single dispatcher** before the reducer sees it, so the reducer is a single-threaded state machine and
its test vectors (§15.2) are exhaustive. Ordering is preserved per attempt. An event arriving for an
attempt the core has never seen is dropped and counted — that is an adapter bug, not a race to
tolerate.

### 12.3 Position cadence

Two numbers, named because §15.2 depends on both: **`cadenceTarget = 0.5 s`** (the nominal 2 Hz
emission interval) and **`cadenceMax = 2 s`** (the hard guarantee — while progressing, an adapter
**must** emit at least this often). `PositionChanged` is coalesced in the core to `cadenceTarget` and
carries the **media** position plus the **monotonic** time it was sampled (§18.8). The core never
queries the engine for position.

### 12.4 Validating the stream — inline, because a preflight cannot do it

**OBSERVED** (https://www.subsonic.org/pages/api.jsp and
https://opensubsonic.netlify.app/docs/endpoints/stream/): `stream` "returns binary data on success, or
an XML document on error (in which case the HTTP content type will start with `text/xml`)." With
`f=json`, compatible implementations may return a **JSON** envelope instead. **ASSUMED (defensive):**
that such an error arrives with HTTP 200 is the compatibility-defensive assumption we design for; the
reference server's actual status codes are measured by CONF-12 and only then become OBSERVED.

**HTTP 200 is not proof of audio, and content type alone is not proof either** — real servers and
reverse proxies mislabel responses.

🚨 **The two delivery paths of §12.5 do NOT fail the same way, and revision 2 treated them uniformly.**
**OBSERVED** (https://opensubsonic.netlify.app/docs/endpoints/gettranscodestream/): `getTranscodeStream`
returns *"The raw transcoded media stream. **In case of an error, a standard HTTP error code is
returned with a descriptive message.**"* That is a different convention from legacy `stream`, which is
documented as binary-on-success and an envelope-on-error. So the validator must recognise **three**
shapes, not two:

| shape | where it occurs | how it is detected |
|---|---|---|
| **envelope at HTTP 200** | legacy `stream` (Path B) | body parses as JSON/XML after BOM and whitespace |
| **HTTP error status, no envelope** | `getTranscodeStream` (Path A) | non-2xx status with a plain descriptive body |
| **envelope at a non-2xx status** | **the reference server does this** — see below | non-2xx status **and** a parseable envelope. Revision 2 did not admit this case at all |

The third row is not hypothetical. **OBSERVED**, Navidrome at v0.63.2 rejects a request that exceeds
its transcode concurrency cap with **HTTP 429**, a `Retry-After` header, **and** a Subsonic envelope
carrying the generic error code `0`. A validator that reads only the status, or only the envelope, gets
half the story: the status says "retry me", the envelope says "generic error". **Read both, always, and
prefer the status for the retry decision and the envelope for the user-facing message.**

**Revision 1 was wrong about how to handle this.** It proposed a ranged *preflight*, then handing the
raw URL to `AVPlayerItem(url:)`, and called the result a type-level guarantee. That is a
time-of-check/time-of-use gap: the engine issues a **second** request that can be redirected
differently, authenticate differently, hit another backend, start another transcoder, return a
different container, or fail after the validated prefix. A previous probe's success is not the current
stream's validity.

**Decision — inline validation is the correctness mechanism on both platforms:**

**OBSERVED 2026-08-21 — the original measurement was insufficient and its general conclusion is
withdrawn.** Apple's `AVAssetResourceLoaderDelegate` reference describes methods for handling
resource-loading requests from a URL asset, but does not specify custom-scheme HLS segment routing.
Revision 12 observed one relative segment callback and stopped; that could not distinguish complete
routing from a first-segment-only failure. Adversarial review reopened OQ-10 and required a negative
canary for exactly that powerless-test shape.

**OBSERVED — the strengthened measurement closes only a narrower, enforced contract.** A progressive
MP3 produces delegate byte-range requests. For HLS, the delegate resolves two absolute media URIs on
different source origins, records their original URLs, and rewrites both into opaque relative routes
that resolve under the manifest's `dulcet-stream://` URL before returning it. `AVPlayer` then requests
**both named segments through the
delegate and plays beyond the first segment boundary without a swallowed error**. A fault-injection
invocation lets the first segment route and fails the second; `apple-ci` proves that the measurement
rejects that state before running the passing case.

This does **not** establish what AVFoundation does with arbitrary **unrewritten** relative, absolute,
cross-origin, redirected, or key URIs, and Dulcet does not make that behavior a product dependency.
The production loader resolves every fetchable manifest URI — media segment, nested playlist,
encryption key, and initialization map — before returning the manifest, stores the original absolute
URL in loader-owned routing state, and substitutes an opaque route that resolves under the manifest's
custom scheme. Redirects then occur
inside the loader's `URLSession` request and remain subject to §13.3 rather than AVFoundation's
unobserved behavior.

The result is recorded in `docs/COMPATIBILITY.md` and runs serially inside the branch-protection-
required `apple-ci` job. The required job enforces the first-segment-only negative canary and the two-
segment rewritten-manifest result; it does not claim to enforce the unmeasured production-loader
behaviors listed below. Under this rewrite contract Dulcet proceeds with HLS support on Apple. If the
contract cannot be preserved, the documented fallback still refuses `protocol: "hls"` plans and
forces Path A to a progressive container.

- **Apple:** a custom scheme (`dulcet-stream://`) plus an `AVAssetResourceLoaderDelegate` that performs
  the fetch through `URLSession`. Dulcet therefore sees status, headers and bytes of the *actual*
  playback request, controls redirects (§13.3), can attach headers, and can surface a structured
  server error instead of an opaque `AVFoundation` failure. Cost, stated honestly: we must satisfy
  `contentInformationRequest` correctly and implement range handling ourselves, or seeking breaks.
  **This is Phase-1 work, not a deferred fallback.**
- **Android:** a custom `DataSource.Factory` wrapping the HTTP client, giving the same visibility for
  substantially less work.
- **Preflight is demoted to an optional, advisory fast-fail** used only where inline validation is
  impossible (a platform path we do not currently have) or as a pre-queue health check. When used,
  `PlaybackPlan.validation` is `PreflightAdvisory(evidence)` and the code must not treat it as proof.
  There is no `validated: ValidationEvidence` field that claims otherwise.
- **HLS** (`deliveryProtocol == Hls`, possible under the transcoding extension) is validated as a
  **manifest**, not as audio: the loader checks it parses as an M3U8, resolves every fetchable URI,
  applies the redirect/origin policy of §13.3, stores the approved absolute URL in its routing table,
  and rewrites the manifest reference to an opaque route token that resolves under the manifest's
  `dulcet-stream://` URL. Audio magic-byte rules
  do not apply to the manifest.

**The validator table is normative.** Prose bullets are not implementable; the implementation carries a
table with, per container: accepted MIME types, minimum bytes required, signature bytes with offset and
mask, and whether a structured manifest is expected instead. Requirements the table must satisfy —
revision 1's prose got several of these wrong:

- **MP4/M4A:** `ftyp` appears at **offset 4**, not offset 0, preceded by a 4-byte box size.
- **WAV:** `RIFF` at offset 0 **and** `WAVE` at offset 8 — two separated fields.
- **MP3:** either an `ID3` tag at offset 0, or a frame sync matched by **mask** (`0xFFE0 & word ==
  0xFFE0`), not a textual comparison.
- **FLAC:** `fLaC` at offset 0, but an ID3 tag may precede it, so the scan must skip a leading ID3v2
  block.
- **Ogg:** `OggS` at offset 0.
- **ADTS/AAC:** syncword by mask; this container is reachable through the transcode path.
- **Envelope precedence:** a body that parses as JSON or XML **after skipping leading whitespace and a
  BOM** is an envelope, and envelope detection is evaluated **before** audio sniffing.
- **Truncation:** fewer bytes than the container's minimum is `Protocol.UnexpectedBinary`, not a
  successful validation.
- The table is exercised by unit tests with byte fixtures for every row, including the negative cases.

### 12.5 Transcoding — two distinct paths

**Revision 1 specified this wrongly.** It said `stream?format=&maxBitRate=` was "gated on the
`transcoding` extension." That is not what the extension is.

**OBSERVED** (https://opensubsonic.netlify.app/docs/extensions/transcoding/ and
.../endpoints/gettranscodedecision/, fetched 2026-08-17): the `transcoding` extension introduces two
endpoints. `getTranscodeDecision` **must be accessed by POST**, because "client playback capabilities
can be complex, they must be supplied in the request body as a JSON-encoded payload" (a `ClientInfo`
payload carrying client name/platform, audio bitrate limits, direct-play profiles of containers /
codecs / protocols, transcoding profiles, and codec profiles with audio limitations). It returns a
`transcodeDecision` containing `canDirectPlay`, `canTranscode`, a `transcodeReason` array, an opaque
`transcodeParams` string, a `sourceStream` description and a `transcodeStream` description.
`getTranscodeStream` "returns a transcoded media stream" using those parameters. **The server
determines the parameters; the client does not construct or modify them.**

**Path A — extension present (`transcoding` v1):**

1. Build `ClientInfo` from the device profile (§12.6).
2. `POST getTranscodeDecision`.
3. If `canDirectPlay`, resolve a `RemotePlan` against the direct source described by `sourceStream`.
4. Else if `canTranscode`, resolve a `RemotePlan` against `getTranscodeStream` carrying
   **`transcodeParams` verbatim, as an opaque token**. Never parse it, never rebuild it, never cache it
   across items or across `ClientInfo` changes.
5. `transcodeReason` is surfaced in a "why is this transcoding?" diagnostic.
6. `transcodeStream.protocol` sets `deliveryProtocol` (§9.4) — including HLS.

**Path B — extension absent (legacy):** classic `stream?format=&maxBitRate=` **preference hints**. They
are hints, not a contract; the server may ignore them.

**Path B also sends `estimateContentLength` whenever the plan is transcoded** — the lever revision 2
missed. **OBSERVED:** `stream` takes `estimateContentLength`, which *"Sets Content-Length header for
transcoded media."* Without it a legacy transcoded stream has no declared length, which is what makes
it unseekable and what makes §14.5's "validated against the expected byte length" impossible for a
transcoded download. Both §12.7 (seeking) and §14.5 (atomic promotion) quietly assumed that information
existed; this is where it comes from. Record the returned length on the plan. `TranscodeDecision.LegacyHint` records that we
are on this path so the UI never claims a negotiated result.

**`transcodeOffset` applies to Path B.** **OBSERVED** for the extension name and version only; the
precise offset behavior on Path A is **ASSUMED** and is measured by **CONF-14b** before any Path-A seek
code is written. Do not assume `timeOffset` carries over.

**Path selection is per item, recorded on the plan, and visible in diagnostics.** A user preference
("prefer original quality on Wi-Fi, cap at 192 kbps on cellular") feeds the `ClientInfo` bitrate limit
on Path A and the `maxBitRate` hint on Path B — one preference, two encodings, never two settings.

### 12.6 Device capability profile

Path A cannot work without one, and revision 1 omitted it entirely. `core` defines a normative
`DeviceProfile` (containers, codecs, channel counts, sample rates, bit depths, protocols, max bitrate)
and each platform adapter supplies it. The profile:

- is **conservative by default** — a codec is claimed only if the adapter has a positive reason to
  believe the engine decodes it on this OS version;
- varies by OS version and by current audio route (an Apple TV over HDMI is not an iPhone on
  Bluetooth), so it is re-queried on `RouteChanged` and a material change invalidates cached transcode
  decisions;
- is verified by a **direct-play probe suite** in the app's diagnostics screen that actually attempts
  each claimed codec once and records the result;
- is versioned, so a profile change can invalidate cached decisions.

### 12.7 Seeking

`PlaybackPlan.seekStrategyPredicted` is a **prediction**. `Ready.seekability` from the engine is
**authoritative for the UI**, because seekability also depends on container indexing, duration
metadata and engine interpretation — a server can support byte ranges while an item remains
practically unseekable, and an engine can seek buffered content with no server range support at all.
On a prediction/report mismatch the core adopts the engine's value, updates the UI, and records a
diagnostic; a repeated mismatch for a given container is a candidate quirk (§17).

A `ServerOffset` seek re-resolves the plan and produces `AttemptReplaced` **within the same session**
(§12.1), not `SeekCompleted`.

### 12.8 Gapless and preload

**🚨 Preload is the single behaviour most likely to trip the server's transcode limiter, and revision 2
made it unconditional.** Resolving *and validating* the next plan while the current one plays means
**two concurrent transcodes per client**. Against a per-user cap of 1, gapless preload is
self-defeating: it reliably 429s, and the request it starves may be the one currently playing.

**Therefore the core keeps a per-server transcode-concurrency budget**, and it is learned rather than
assumed, because the protocol does not expose the cap:

- Start optimistic — assume the server tolerates playback **plus** one preload.
- On the **first `Server.Busy` attributable to a preload**, drop that server's budget to **1** and stop
  preloading *transcoded* streams for the rest of the session. Log it; surface it in diagnostics.
- **Preloading a direct-play stream never consumes a transcode slot**, so it continues regardless. The
  budget applies to transcoded plans only, which is also why `PlaybackPlan.transcode` (§9.4) has to be
  known before preload is scheduled.
- **Strict priority when the budget is contended: current playback > preload > downloads.** Downloads
  of transcoded files yield immediately on `Server.Busy` and resume after `Retry-After`; they may never
  starve playback (§14.5). A batch download that saturates a global cap while the user is listening is
  a bug, not a tuning question.
- The budget resets when the account's capability set is refreshed (§10.2).

`preloadNext` resolves and validates the next plan while the current one plays; the transition emits
`AdvancedToPreloaded`, which is a **session boundary** (§12.1). Gapless *output* is not a toolkit
checkbox: `AVQueuePlayer` and ExoPlayer concatenation remove application-level replacement latency, but
seamless boundaries also depend on encoder delay/padding metadata, decoder behavior, container, and
whether a transcoder produced a clean boundary. Gapless is therefore an **empirically measured,
per-format, per-path capability** recorded in `FEATURES.yml`, not a claimed feature.

### 12.9 Audio session, focus, and route policy

Listing route and interruption events is not a policy. The normative policy:

- **Apple:** playback category with the default (non-mixing) option; the session is activated on the
  first `prepare` of a session and deactivated on `stop` or after a grace period with no queue.
  AirPlay is permitted. On `InterruptionBegan` playback pauses; on `InterruptionEnded` it resumes
  **only** if the system indicates resumption is appropriate.
- **Android:** `AudioAttributes` usage `MEDIA` / content type `MUSIC`, with
  `setAudioAttributes(attrs, handleAudioFocus = true)` so Media3 owns focus. Transient loss ducks or
  pauses per the system's request; **permanent loss pauses and does not auto-resume.**
- **Becoming noisy** (headphones unplugged, Bluetooth disconnect) pauses on every platform.
- **tvOS/HDMI route changes** are treated as route changes, not interruptions.
- **Another controller starting playback** (a system media control, another app's session) is handled
  by pausing and finalizing the current session.
- Every one of these paths must leave the scrobble accumulator in a defined state; the transition table
  in §12.1 is the authority.

### 12.10 Remote commands and system media controls

The supported command set is declared once in the core and mirrored by both `NowPlayingSink`
implementations: play, pause, toggle, next, previous, seek (**only when the engine reports the item
seekable**), and rating/favourite where the user's role permits it. Not supported in v1: queue editing
from system UI, playback-rate control, and chapter navigation. Metadata updates are ordered — Now
Playing is updated after `Ready` and after each `AttemptReplaced`, never speculatively at `Preparing`,
so the system UI never shows a track that failed to start. Commands arriving for a stale session are
rejected, not applied to the current one.

### 12.11 Deferred but not precluded

CarPlay and Android Auto are out of scope. The constraint they impose — the queue and metadata must be
fully expressible without the app's own UI — is already satisfied by the core owning queue state and by
`NowPlayingSink`. No v1 decision may violate that.

---

## 13. Security

### 13.1 Authentication

**OBSERVED** (https://www.subsonic.org/pages/api.jsp): the API requires a **random salt for each REST
call**, specifies at least six characters, and defines the token as the lowercase hexadecimal MD5 of
the UTF-8 password concatenated with the salt.

- Parameters: `u`, `t`, `s`, `v=1.16.1`, `c=Dulcet`, `f=json`.
- **A fresh salt per request**, from a cryptographically secure RNG (`SecureRandom` on the JVM,
  `SecRandomCopyBytes` on Apple), **16 random bytes rendered as 32 hex characters**. Six characters is
  the protocol floor, not a target.
- **No API-key auth** (§2.3), and no speculative API-key attempts.
- Plaintext-password auth (`p=`) is never used, even where a server accepts it.

**Secure storage, specified rather than gestured at:**

| | Apple | Android |
|---|---|---|
| item | Keychain generic password | Keystore-backed encrypted store |
| accessibility | `kSecAttrAccessibleAfterFirstUnlock` — not `...ThisDeviceOnly` | key usable once the device has been unlocked after boot; no biometric/auth-bound key |
| key | `service = "${BUNDLE_PREFIX}"`, `account = server_id` (the local UUID, not the username or URL) | alias `${BUNDLE_PREFIX}.<server_id>` |
| iCloud Keychain sync | off (`kSecAttrSynchronizable = false`) | n/a |
| device migration / backup restore | credentials do not migrate; the user re-enters the password on a new device | same |
| key invalidation (lock screen removed, keystore reset) | detected on read; the account enters a re-auth state and cached library data is retained | same |
| logout | credential deleted before any other cleanup (§14.7) | same |

**Background downloads after a reboot** read the credential like any other consumer;
`kSecAttrAccessibleAfterFirstUnlock` is chosen precisely so a background task can run before the user
opens the app. A download whose credential read fails is **paused with a re-auth reason**, not failed.

### 13.2 The threat model, stated correctly

Revision 1 said salted MD5 "protects the password in transit." **That is too strong and it is
security-significant.** The accurate statement:

> Salted-token authentication avoids sending the plaintext password and changes the token between
> calls. It is **not** transport security and **not** replay resistance — the salt is client-chosen and
> is not a server-enforced nonce, so an observer who captures `u`, `t` and `s` can replay that tuple
> and can mount an offline guessing attack against the password. **HTTPS** supplies confidentiality
> and server authentication; the token scheme supplies only the absence of a plaintext password on the
> wire.

`u`, `t` and `s` ride in the **query string** on many calls including media URLs, which is exactly
where URLs leak: logs, crash reports, proxy access logs, media-framework diagnostics. Therefore:

1. **HTTPS is required for non-local servers** (§13.5).
2. **Use `formPost` when advertised** — every non-media API call becomes a POST with a form-encoded
   body, keeping `u`/`t`/`s` out of query strings and access logs.
   ⚠️ **Revision 2 said "media URLs still require query auth." That reason is false at the reference
   server.** **OBSERVED:** Navidrome mounts its form-to-query middleware at the **router root**, above
   every route group, so it accepts form-POSTed credentials on `stream`, `download`,
   `getTranscodeStream` and `getCoverArt` as well as on metadata endpoints. And Dulcet already controls
   the media request — §12.4's whole architecture exists so that nothing hands a raw URL to a player.
   **So the mitigation is broader for playback than revision 2 claimed.**
   **The conclusion still holds for one path, for a different reason:** background `URLSession`
   download tasks with POST bodies and resume data are genuinely awkward, and `Range` with POST is
   unusual — so **§14.5 downloads may still need query auth**, and that is a *platform* constraint, not
   a protocol one. State it per path; do not bake a false universal into the security section.
   **CONF-07b** measures whether the reference server honours form-POSTed credentials on `stream`,
   because the answer changes this threat analysis.
3. **Redact the entire query string** everywhere (§13.4).

### 13.3 Redirects and credential leakage

Signed media URLs must never carry credentials to another origin. Reverse proxies routinely redirect
HTTP to HTTPS, add or strip trailing slashes, or redirect to object storage on a different host.
Normative policy, enforced in the transport layer and in the resource loader (§12.4):

- Follow at most **5** redirects.
- A redirect changing **scheme, host or port** is cross-origin: credentials are **stripped** from the
  redirected request. If the target then returns 401 the request fails with
  `Auth.RedirectCredentialLoss`, with the URL surfaced redacted, rather than being retried with
  credentials attached.
- A downgrade from `https` to `http` is **rejected outright**, never followed.
- Same-origin redirects preserve the query string as issued; credentials are not regenerated
  mid-redirect, since a fresh salt would invalidate an already-signed URL for no benefit.
- The final resolved URL is **never** handed to `AVPlayer` directly; the resource loader owns the whole
  chain (§12.4) — a second reason inline validation is the right mechanism.

### 13.4 Redaction as a mechanism, not a discipline

- A single `Redactor` rewrites any URL to `scheme://host/path?<redacted>` before it can reach a
  `LogSink` or a `DomainError`.
- `DomainError` carries **`redactedUrl: String`** and has **no field capable of holding a raw URL** — a
  type-level guarantee.
- Adapters wrap platform playback errors before surfacing them: `AVFoundation` error `userInfo` and
  ExoPlayer's `PlaybackException` can both carry the failing URL.
- Never log the password, token or salt, at any level, in any build.
- **Tests use canary values, not pattern matching.** Revision 1 proposed failing on any 32-hex run in
  the logs; that would flag ordinary Navidrome item and artwork IDs, which are themselves hash-like.
  Instead the conformance environment uses a known canary password, and the tests assert that the
  canary, its derived tokens, and the exact salts issued during the run appear **nowhere** in captured
  log output, and that structured log fields carrying URLs are redacted. A second test asserts
  `DomainError.toString()` on a synthetic signed URL does not contain the token.

### 13.5 TLS trust and the local-HTTP exception

**v1 supports OS-trusted TLS only.** No trust-all, no per-server pinning, and **no user-accepted
self-signed certificates**. A chain, hostname or expiry failure produces `TlsUntrusted(reason)` with
the specific reason and a documented remedy (install the CA at OS level, or fix the certificate).
There is no "continue anyway" button.

This is a deliberate v1 restriction on a real self-hosted use case — private CAs and self-signed certs
are common — and it is recorded as **OQ-9** rather than pretended away. What we will not do is ship a
trust-all path and call it a preference.

**The local-HTTP exception** permits plain `http` only when the host is IPv4 RFC1918, IPv4 loopback,
IPv6 loopback (`::1`), IPv6 unique-local (`fc00::/7`), or a `.local` name — and only after an explicit
per-server opt-in with a visible warning. Classification is performed on the **resolved address at
login** and re-checked on every subsequent connection: if a name that resolved privately later
resolves publicly, requests fail with `Security.LocalExceptionViolated` rather than silently sending
credentials over the open internet. A redirect from a local host to a public one is rejected (§13.3).
A Cloudflare-terminated TLS front does not encrypt the final LAN hop, and the docs must not imply it
does.

### 13.6 Local data privacy

"No telemetry" says nothing about what sits on the device.

- Downloaded media and artwork live in an app-private directory, are **excluded from OS backups**, and
  on Apple carry a file-protection class that keeps them unreadable on a locked device while still
  allowing an already-open background download to complete.
- Downloads are not exposed to Android shared storage and are not indexed by the system media scanner
  (a `.nomedia` marker in the download root).
- File names are opaque — derived by hashing `server_id` + `raw_id` + transcode profile — and contain
  **no server hostname, username, artist or title**.
- All of it is deleted on account removal (§14.7).

### 13.7 No crash reporting, no analytics

Dulcet ships no third-party crash reporter and no analytics SDK. This is a security decision as much as
a privacy one: the likeliest way a signed stream URL leaves a device is inside a crash report. Apple's
own opt-in crash reporting is acceptable — the user consents at OS level and it does not carry our log
buffer — and we still keep URLs out of log buffers (§13.4).

---

## 14. Queue, downloads, offline, account removal

### 14.1 The queue lives in the core

`QueueState` = ordered `QueueEntry(queueEntryId, providerItemId, sourceContext, addedBy)`, a current
index, repeat mode (`off`/`all`/`one`), and shuffle state. Persisted in `queue_entry`, survives app
death, single source of truth for every surface. Per-account, single active account (§11.2).

`sourceContext` records where the entry came from (album X, playlist Y, search Z) so the UI can say
"playing from" and so "play next" behaves sensibly.

### 14.2 Shuffle — persist the order, not a seed

**The shuffled order is persisted explicitly** as an integer column on `queue_entry`. Revision 1
proposed a stored seed; a seed alone cannot reproduce a permutation once entries are inserted and
removed, which happens constantly. With a persisted order, turning shuffle off restores the original
order exactly, the "up next" list is real, the order survives a restart, and insertion is well defined:
a track added while shuffled goes **immediately after the current entry** when added via "play next",
otherwise at the end of the shuffled order. Removal closes the gap; already-played entries keep their
positions.

### 14.3 Repeat and completion interact

`repeat one` starts a **new session** (§12.1) — a new scrobble clock and a new eligible scrobble. This
is stated because the naive implementation (seek to zero) produces no scrobble at all.

### 14.4 Server-side queue sync is not in v1

Not "default off" — **not built**. It requires a conflict model that does not exist (which side wins on
first enable; what happens when another client changes the server queue; how indices remap after local
mutations; what a revision is when the protocol has none), and its failure mode is user-visible data
loss. The `indexBasedQueue` extension is recorded in the capability set and nothing reads it. Revisit
after local queue correctness ships.

### 14.5 Downloads

The core owns **policy**: what to download, in what order, retry and backoff, disk budget, eviction,
and reconciliation against a changed server item. The platform owns the **executor**: background
`URLSession` on Apple, foreground service plus `WorkManager` on Android.

**The durable protocol between them is the part that gets skipped, so it is specified:**

- **Identity:** a download is `(server_id, raw_id, transcode_profile)` — "the FLAC" and "the 320 kbps
  MP3" of one track are two rows. Each row holds a stable `downloadId` that is **also** the platform
  task's identifier (`URLSessionTask.taskDescription`, `WorkManager` tag), so a relaunched process can
  map a delivered task back to its row.
- **Relaunch reconciliation** runs before anything else touches the subsystem: enumerate the platform's
  outstanding tasks, match them to rows, mark rows with no task as interrupted, and mark tasks with no
  row for cancellation. Duplicate delivery after relaunch is harmless because promotion is keyed on the
  destination path.
- **Atomic promotion:** bytes land in a temp file; the file is validated with the §12.4 validator table
  **and** against the expected byte length; only then is it atomically renamed into place and the row
  marked complete. A crash between validation and rename leaves a temp file that reconciliation deletes.
- **Partial files** keep platform-supplied resume data; resume data older than 7 days, or rejected by
  the platform, causes a restart from zero rather than a stuck row.
- **Credential change mid-flight** invalidates outstanding tasks; the reconciler re-issues them.
- **Server-side change** (duration or size changed since download) is detected at the next sync and
  marks the file stale; stale files still play but are flagged for re-download.
- **Disk pressure** pauses new downloads and never evicts a completed explicit download to make room
  (§14.6).
- **Transcoded downloads yield to playback.** They participate in the per-server transcode budget
  (§12.8), pause on `Server.Busy` for at least `Retry-After`, and are never scheduled at a
  concurrency that could exhaust the server's cap while a session is playing.
- **tvOS ships no downloads.** Its `FEATURES.yml` cells are `n/a` with a reason, not `planned`.

### 14.6 Storage budget and eviction priority

One disk budget per account, user-configurable, with a strict priority order:

| class | evictable |
|---|---|
| currently playing / next preloaded | never |
| explicit user downloads (complete) | only by explicit user action or account removal |
| explicit user downloads (partial, stale > 30 days) | yes, with notice |
| streamed-media cache | yes, LRU |
| artwork cache | yes, LRU, evicted first |

Artwork and stream cache share a separate, smaller budget, so an artwork sweep can never delete a
download.

### 14.7 Signing out and account removal

One transactional operation with a defined order, resumable if the app dies partway:

1. mark the account `removing` — it disappears from the UI immediately and no new work is scheduled;
2. **offer to submit the scrobble and mutation outboxes first**, and block on the user's choice; this
   is user-authored data and silently discarding it is not acceptable;
3. delete the secure-store credential;
4. cancel platform download tasks;
5. delete downloaded media, artwork and stream cache for that `server_id`;
6. delete all database rows for that `server_id`;
7. clear the `server_account` row.

Undo is available only before step 3. If the app dies mid-removal, the `removing` flag resumes the
sequence at launch.

---

## 15. Scrobbling, play reporting, resume position

### 15.1 The rule

**OBSERVED** (https://opensubsonic.netlify.app/docs/endpoints/stream/, fetched 2026-08-17):
"OpenSubsonic servers **must not** count access to this endpoint as a play and increase playcount";
clients use `scrobble` instead. **OBSERVED** (https://www.navidrome.org/docs/developers/subsonic-api/):
Navidrome does not mark a song played on `stream` and updates play state only on `scrobble` with
`submission=true`. **OBSERVED** (https://opensubsonic.netlify.app/docs/endpoints/scrobble/): the
specification assigns play-count and last-played updates to `scrobble`.

The binding statement is about **clients**, because the "must not" binds OpenSubsonic servers and says
nothing about every legacy Subsonic-compatible server:

> **Clients must not rely on `stream` to record a play. They must call `scrobble` according to client
> playback policy. OpenSubsonic servers must not count `stream` as a play, and Navidrome does not.**

### 15.2 The accumulator is a reducer, and it is specified as one

"Accumulate from events" is not enough for two implementers to agree. The accumulator is a pure
function `(state, event) -> (state, effects)`:

- **State:** `accruedMediaTime`, `lastPosition`, `lastMonotonic`, `progressing`, `rate`, `submitted`,
  `sessionStartWallClock`, `durationKnown`.
- **Accrual:** on `PositionChanged`, `delta = newPosition - lastPosition`. Accrue **only if**
  `0 < delta <= maxPlausibleDelta`, where `maxPlausibleDelta = 2 x cadenceMax x max(rate, 1)` — i.e.
  **4 seconds** at rate 1, given `cadenceMax = 2 s` (§12.3). It is derived from `cadenceMax`, not from
  `cadenceTarget`, because an adapter is permitted to emit as slowly as `cadenceMax` and a legitimate
  2-second delta must not be discarded. A larger delta is a **discontinuity, not listening**: it is
  discarded and counted, whether or not a `SeekCompleted` arrived. One rule covers app suspension, a
  missed callback, a decoder timestamp jump, and a seek whose event was late or absent.
- **Backward delta** accrues nothing and resets `lastPosition`. Replaying a segment accrues normally —
  the accumulator measures time listened, not coverage of the track.
- **Not progressing:** during `Buffering`, `Paused` or after `InterruptionBegan` nothing accrues, and
  `lastPosition`/`lastMonotonic` are refreshed on resume so the gap is not accrued retroactively.
- **Rate:** accrual is in **media time**, so 2x speed accrues media seconds at half the wall-clock
  cost. That is intended: the listener heard the whole track. Rate 0 or negative accrues nothing.
- **Threshold:** `min(0.5 x duration, 4 minutes)`, evaluated on every accrual, requiring
  `duration >= 30 s`.
- **Duration changes:** `DurationChanged` recomputes the threshold. The threshold may move upward and
  un-meet a previously met condition **only while nothing has been submitted**; once `submitted` is
  true it is never retracted. A track whose server duration said 29 s and whose decoded duration says
  31 s becomes eligible; the reverse makes it ineligible only pre-submission.
- **Duration unknown:** no submission is possible; the session is recorded in diagnostics.
- **Terminal:** `EndedNaturally`, `Skipped`, `FailedAfterPartial` and session finalization each evaluate
  the threshold once. `FailedBeforeStart` never submits.
- **Unit-test vectors are a deliverable**: every case above, plus suspension mid-track, a seek to 99%,
  repeat-one, and a transcode-offset `AttemptReplaced`.

**Now-playing** is `scrobble?id=&submission=false`, sent on `PlaybackProgressBegan` — never on
`resolvePlayback` or `prepare` — and re-sent every 60 seconds **of progressing playback**, measured on
the monotonic clock, suspended while paused or buffering, cancelled on session end. A failed
now-playing send is **dropped, not queued**: it is ephemeral state and a stale now-playing is worse than
none. It does **not** share the submitted-play outbox.

**Submitted play** is `scrobble?id=&time=<wall-clock epoch ms of session start>&submission=true`,
emitted at most once per session.

### 15.3 Offline queue and honest delivery semantics

Unsubmitted completed plays go to `scrobble_outbox` with the session's wall-clock start time, retried
with exponential backoff on reachability and on foreground.

**The local uniqueness key `(server_id, raw_id, session_start_wall_clock)` prevents two local rows. It
does not make the network call idempotent.** If the request reaches the server but the response is
lost, a retry can increment the play count twice unless that server deduplicates. Revision 1 claimed
otherwise and was wrong. The honest statement:

> **Delivery is at-least-once.** Dulcet prefers a possible duplicate play to a lost one. Exactly-once
> is unavailable without a server idempotency guarantee, which the protocol does not define.
> **CONF-23** measures what the reference server does with a repeated `scrobble` carrying the same
> `time`; the result is recorded per server in `docs/COMPATIBILITY.md`, never assumed globally.

Entries older than **30 days are dropped** — a **product retention decision**, not a server fact.
Dropping one loses user-authored play history, so it is surfaced in diagnostics rather than done
silently. The outbox is protected data (§11.4).

### 15.4 `playbackReport` is not called in v1

The reference server advertises it (§2.3) and it offers richer timeline reporting; it also creates an
obvious double-counting hazard. **v1: `scrobble` is the single source of truth and no production code
path calls `playbackReport`.** ⚠️ One correction to revision 2's framing, which was over-cautious: the
hazard is **a documented parameter, not an unknown**. **OBSERVED**, the extension's own page: *"When
`ignoreScrobble=true`, the server should only update now playing display/state fields and **should not
perform playcount/scrobble side effects**."* So the double-count is controllable by design. CONF-21 is
still the right gate — "should" is not "does", and this is the reference server's behaviour we are
betting play counts on — but the adoption work is smaller than "unknown territory". Adoption later requires **CONF-21** proving that a `playbackReport`
submission does not itself increment play count and last-played — and the adopting change must remove
the corresponding `scrobble` call in the same commit, not add a suppression flag. **CONF-21 is not a
Phase-1 deliverable**; it belongs to the feature that adopts the extension.

### 15.5 Resume position

Play position and "played" are distinct (§9.5 invariant 5), so the behavior is defined rather than
implied.

**v1 scope: local only.** `resume_position` is written on pause, on session finalization, and on a
30-second cadence while progressing; restored when the same item is started again; cleared on
`EndedNaturally` and on a submitted play that reached the end. It is protected data (§11.4).

**Server-side bookmarks (`getBookmarks` / `createBookmark` / `deleteBookmark`) are not implemented in
v1**, so cross-device resume is not a v1 feature and is not claimed. This is stated explicitly because
§1.3 accepts audiobooks-as-songs and a user with a nine-hour file will notice.

---

## 16. Library sync

Target scale — the design is sized against a large real-world library of **~31,500 tracks across
~2,950 albums**, and every figure below is derived from that.

### 16.1 There is no change token, and offset paging is not a snapshot

Subsonic has no delta-sync contract. Do not assume any list endpoint gives a reliable total count, a
stable cursor, or a change token. `getIndexes` accepts `ifModifiedSince`, but whether the reference
server honors it and at what granularity is **CONF-31**, not an assumption — and even honored it is
artist-level and cannot detect a changed track under an unchanged artist.

**The honest statement revision 1 was missing:** offset pagination over a collection that mutates
during the pass **cannot** be made snapshot-consistent by deduplication. Dedupe removes repeats; it
cannot recover an item that shifted past the cursor and was never returned. `alphabeticalByName` is not
a stable cursor either, because duplicate album names are ordinary and the API exposes no tie-break.

So the design does not claim snapshot consistency from the paging. It gets consistency from the
**commit model** (§16.3) and detects the paging problem with a **stability witness** (§16.4).

### 16.2 Shape of a full import

| stage | endpoint | approx. calls at target scale |
|---|---|---|
| folders | `getMusicFolders` | 1 |
| artists | `getArtists` | 1 |
| albums | `getAlbumList2?type=alphabeticalByName&size=500&offset=N` | ~6 |
| tracks | `getAlbum?id=` per album | **~2,950** |
| playlists | `getPlaylists` + `getPlaylist?id=` per playlist | 1 + P |
| starred | `getStarred2` | 1 |
| genres | `getGenres` | 1 |

The track stage dominates. At bounded concurrency 4 it is a few thousand small requests — fine on a LAN
or a healthy remote server, and rude if issued unbounded.

### 16.3 Commit model: row versioning, reads pinned to a committed generation

Revision 1 proposed in-place updates plus two-pass tombstoning, then claimed users never see a mixed
snapshot. Those contradict: in-place updates make a partially completed pass visible immediately.

- `schema_meta.committed_generation` = G. **Every read query is filtered to G.**
- Rows carry `valid_from_generation` and `valid_to_generation` (null = open). An update during pass G+1
  **closes** the old version (`valid_to = G+1`) and **inserts** a new one (`valid_from = G+1`). Reads
  pinned at G continue to see the old version.
- A deletion detected during G+1 closes the row at G+1 and inserts nothing.
- **Commit is a single atomic transaction** setting `committed_generation = G+1`. Before it the pass is
  invisible; after it the pass is entirely visible. "Interrupted but marked complete" is therefore not
  a reachable state — which is why revision 1's second deletion pass was unnecessary and is removed.
- **Pruning:** versions closed at or before `G-1` are deleted by a background pass once
  `committed_generation > G`. Only changed rows are ever duplicated, so at 31.5k tracks the overhead is
  small.
- **Resumability:** `sync_checkpoint` holds `(generation, stage, cursor, attempt)`. A killed app resumes
  the album stage at offset 2,000 in generation G+1 while reads stay pinned at G. A checkpoint is
  invalidated — and its stage restarted — if the stage's stability witness changed since it was taken.
- **Downloads and queue entries reference the stable `(server_id, raw_id)`**, never a version, so a
  generation change never orphans them.

### 16.4 Stability witness and repeat-until-stable

Because paging is not a snapshot, each list stage records a **witness**: the complete set of ids
returned and the number of pages consumed. After the stage completes, the index is re-walked (cheap —
about 6 calls for albums) and the witness recomputed.

- Witness identical: the stage is **stable**.
- Witness differs: the delta is fetched and merged and the witness recomputed. Up to **3** attempts.
- Still unstable after 3: the generation is committed with `stability = unverified`, and a diagnostic
  tells the user the library was changing during the scan and offers a rescan. **We do not loop forever
  against a server that is actively importing.**

### 16.5 Other required properties

1. Deterministic ordering where offered (`alphabeticalByName` over `random`).
2. Page until a **short or empty** page; never trust a total count.
3. Dedupe by opaque server id within a pass.
4. Deletion reconciliation: a closed row that has a downloaded file or a queue entry produces a
   user-visible reconciliation ("12 tracks are no longer on the server"), never a silent disappearance.
5. Server-instance namespacing (§11.2).
6. **Bounded concurrency** — configurable, default 4 in-flight per server, with a global ceiling. This
   is politeness to the server, not a performance knob, and is not raised to improve a benchmark.
7. Explicit user-triggered full rescan, resetting checkpoints and incrementing the generation.
8. Tested against mutations during pagination (**CONF-33**).
9. **First sync** has no committed generation, so the UI is explicitly in a "first sync" state with
   progress. Progressive population is allowed only where the UI says so.

### 16.6 Freshness pass — a heuristic, not incremental sync

After the first full import the cheap periodic pass runs `getAlbumList2?type=newest` (bounded),
`getStarred2`, `getPlaylists`, and targeted `getAlbum` for albums whose `songCount` or `duration`
changed.

**Call it what it is: a freshness heuristic.** It cannot detect a title or tag change preserving count
and duration, a track swap of equal length, a credit change, artwork changes, a removal offset by an
addition, a reordering, or per-track changes such as replay gain. **Maximum staleness is therefore
bounded only by the next full pass**, which runs on user request, on capability change, and on a
cadence measured in days. Every library screen exposes a manual refresh and states when the library was
last fully scanned. Sync is never triggered by scrolling.

---

## 17. Server quirks register

**QUIRK-01 — `.view` suffix acceptance.** Navidrome registers both `/<path>` and `/<path>.view` for
every endpoint, so §10.1's "always append `.view`" is safe there. Whether every Subsonic-compatible
server does the same is unsourced. Handling: keep appending `.view`; if a server 404s every endpoint at
login, retry the `ping` probe without the suffix before reporting `NotASubsonicServer`. Pinned by
CONF-03.

Anything we rely on that is not plainly in the specification is a named quirk with an id (`QUIRK-nn`),
the server and version observed on, the behavior, our handling, a conformance test that fails when the
quirk stops being true, and an entry in `docs/COMPATIBILITY.md`. A quirk without a test is a rumour,
and rumours are how this app category accumulates "mysteriously does not work with server X."

---

## 18. Remaining subsystems

### 18.1 Search

`search3` with per-type counts and offsets, merged with an instant local-cache query.

- **Debounce 250 ms**; a new keystroke cancels the in-flight server request.
- **Minimum query length 2** for the server query; local search runs from the first character.
- **Normalization** is shared by both sides: Unicode NFKD, case folding, diacritic stripping, with the
  normalized form stored in an index column.
- ⚠️ **The local ranker's contract is "same-shaped, not same-result", and revision 2 got this wrong**
  by claiming local matching "approximates the server's". **OBSERVED** (navidrome.org Subsonic API
  compatibility): `search2`/`search3` *"Doesn't support Lucene queries, **only simple auto complete
  queries**."* An autocomplete matcher and a four-tier local ranker **disagree about what matches at
  all**, not merely about order — and §18.1's merge rule lets a server result *replace* a local row, so
  that disagreement is user-visible. **CONF-43** issues a fixed query set against the pinned container
  and records the returned id sets, so the divergence is measured rather than assumed.
- **Ranking** is explicit and identical in shape for both sources: exact, prefix, word-start, substring,
  weighted by type (track > album > artist by default).
- **Merging:** identity is the opaque id, so a server result **replaces** the local row of the same id
  (refreshing the cached object) rather than appearing twice. Late results never reorder items above the
  user's current scroll position; they append or replace in place.
- Each result type pages independently.

### 18.2 Artwork

`getCoverArt?id=&size=`. Disk cache keyed by `(server_id, artwork_key, size_bucket)` with a bounded LRU
(§14.6). `size_bucket` is a small fixed set (96/256/512/1024 px) so the cache does not fragment across
every layout's exact pixel size. Artwork keys are opaque and may be album- or song-scoped — never
derived from an item id.

### 18.3 Favourites, ratings, and the mutation outbox

`star`/`unstar` and `setRating` are user-authored data, so they get an ordered outbox rather than a
retry loop.

- A mutation is `(server_id, target_id, field, value, local_sequence, wall_clock)`.
- **Compaction:** successive mutations of the same `(target_id, field)` collapse to the last before
  send — star then unstar sends only unstar; rating 5 then 2 sends only 2.
- **Conflict:** on reconnect the server's value wins **unless** a local mutation is newer than the last
  successful sync for that item, in which case the local mutation is sent and the server's echoed value
  is then adopted. Last-writer-wins with an explicit ordering key, not "whatever arrives".
- An ambiguous send is retried; these operations are set-to-value rather than increment, so
  at-least-once is safe here — unlike scrobbles (§15.3).
- On logout the outbox is offered for submission (§14.7).

### 18.4 Lyrics

Classic `getLyrics` is unsynced and artist/title-keyed. The `songLyrics` extension (v1 and v2) provides
song-id-keyed and structured/synced lyrics. Gated per §10.4: synced-lyrics UI appears only when the
extension is advertised **and** the item actually has synced lyrics.

### 18.5 Similar / radio — deferred

`sonicSimilarity` is negotiated and recorded. **No UI and no conformance test in v1** — its
provider-configuration dependency would weaken the deterministic test environment (§20.2) for a feature
not needed before browse, play and offline are correct.

### 18.6 Playlists

`mutatePlaylist` takes an explicit operation, not a whole playlist: `Add(items, atIndex?)`,
`Remove(indices)`, `Move(from, to)`, `Rename`, `SetComment`, `SetPublic`, `Delete`.

- **Ordering is by explicit index**, never implied.
- **Duplicates are permitted** — a playlist may legitimately contain a track twice — so operations are
  index-based, never id-based.
- **Lost-update handling:** each mutation carries the playlist's last-known entry count and a hash of
  its id sequence. If the server's current state does not match, the mutation is **rejected locally**,
  the playlist is refetched, and the user is told it changed elsewhere. We do not blind-write over
  another client.
- A playlist that has become `readonly` since it was cached rejects mutations with a clear reason.
- **Offline playlist mutation is not supported in v1** — the operation needs the server's current state,
  so the UI disables editing while offline rather than queuing something that will be rejected.

### 18.7 Podcasts and audiobooks

Visible if the server presents them as music items, and they receive **ordinary-song behavior**: the
§15.2 threshold, local-only resume position (§15.5), no chapters, no rate presets, no separate
retention. We do not claim podcast or audiobook support; adding it means a long-form media type, server
bookmarks and a different completion rule — a future feature, not a tweak.

### 18.8 Time model

Two clocks, never interchanged:

- **`MonotonicClock`** — accumulation, timeouts, backoff, cadence, stall detection. Unaffected by
  wall-clock changes, time zones and NTP steps. Not valid across process restarts, and a monotonic value
  is never persisted.
- **`WallClock`** — scrobble `time`, outbox timestamps, "last synced" display, retention windows.

Rules: a wall-clock jump never affects an in-flight accumulator; a device reboot invalidates monotonic
baselines, so the reducer resets `lastMonotonic` instead of accruing a huge delta; a scrobble whose
recorded start is in the future (clock corrected backwards) is clamped to "now" before submission and
flagged in diagnostics.

### 18.9 Accessibility, localization, input

Set now, because six shells that diverge first cannot be retrofitted cheaply.

- **Every interactive element has an accessible label and a stated role**; playback controls announce
  state changes. VoiceOver on Apple, TalkBack on Android, verified per surface.
- **Dynamic Type / font scaling honored** up to the platform's accessibility sizes; no fixed-height rows
  containing scalable text.
- **Full keyboard operability on macOS and iPadOS**, with a visible focus ring and media-key support.
  **Full D-pad operability on tvOS and Android TV** with a visible focus indicator on every focusable
  element (§3).
- **Reduced motion and increased contrast** respected.
- **Right-to-left layout** works from Phase 2 — layouts use leading/trailing, never left/right.
- **Localization architecture from Phase 2** (string catalogs on Apple, resources on Android; no string
  literals in view code), with English the only shipped language in v1.

### 18.10 State restoration

A persisted queue is not playback restoration. On relaunch Dulcet restores the active account, the queue
with its shuffle order and repeat mode, the current entry and its saved position, the source context for
the "playing from" label, and the **paused** state — playback never auto-starts on launch. **Playback
plans are never restored**; they are re-resolved, because a stale plan may carry expired credentials or
a stale transcode decision.

### 18.11 Observability without telemetry

Local, redacted diagnostics are still needed, because the user must be able to report a failure.

- Levels error/warn/info/debug; debug off by default, enabled from a diagnostics screen for one session.
- A rotating on-device buffer with a hard size cap; oldest dropped.
- **The user can export the log**, and the export runs through the same `Redactor` (§13.4) plus a final
  pass replacing the server hostname with `<server>` and the username with `<user>`, so a bug report can
  be pasted publicly.
- Production and conformance logging use the same redaction path — the tests in §13.4 would not be
  evidence otherwise.

### 18.12 Error model

A sealed hierarchy in the core, mapped from the wire in exactly one place:

- `Transport.Unreachable | Timeout | Cancelled`
- `Security.TlsUntrusted(reason) | LocalExceptionViolated`
- `Protocol.MalformedEnvelope | UnexpectedContentType(actual, expected) | UnexpectedBinary`
- **`Server.Busy(retryAfter: Duration?, message, redactedUrl)`** — **backpressure, and it must be its
  own class.** **OBSERVED:** the reference server enforces a transcode concurrency cap (a global cap
  plus an optional per-user cap) and rejects over-cap requests with **HTTP 429 and
  `Retry-After: 5`**, carrying a Subsonic envelope whose code is the generic `0`. Without this class,
  the **most retryable failure the reference server produces collapses into `Server.Known(0, …)` —
  "a generic error" — and becomes indistinguishable from the least retryable.** `Server.Busy` is
  derived from the **HTTP status**, never from the envelope code, precisely because the envelope code
  is uninformative here.
- `Server.Known(code, message, redactedUrl)` **and `Server.Unknown(code, message, redactedUrl)`** — the
  model **must** carry an unrecognized numeric code. **ASSUMED:** the documented Subsonic set is
  expected to be `{0, 10, 20, 30, 40, 41, 50, 60, 70}`; **CONF-06 tests the mappings it can actually
  trigger and tests that an unknown code round-trips**, because one server instance cannot establish a
  closed universe of codes and other compatible servers may return others.
- `Auth.InvalidCredentials | TokenAuthUnsupported | Forbidden | RedirectCredentialLoss`
- `Capability.Unsupported(featureId)` — carries the `FEATURES.yml` id so the UI can say which capability
  is missing.
- `Playback.NoPlayableSource | ValidationFailed(detail) | EngineFailed(detail) | CommandRejected(reason)`

Every error is user-presentable: a short user string, a redacted technical detail, and a suggested
action. "Unknown error" is not a permitted terminal state.

---

## 19. `FEATURES.yml` — the parity gate and the delegate brief format

### 19.1 Purpose

One file, one row per user-facing capability, one cell per platform. Three jobs: the honest capability
matrix; the CI regression gate; and the unit of work handed to a delegate.

### 19.2 Row schema

```yaml
- id: playback.transcode_seek
  title: Seek within a transcoded stream
  spec: docs/superpowers/specs/2026-08-18-dulcet-design.md#127-seeking
  gates: [extension:transcodeOffset, engine:seekable]
  conformance: [CONF-14a]
  platforms:
    macos:     { status: shipped, evidence: { workflow: apple-ci, job: macos-tests,  test: PlaybackSeekTests/testServerOffsetSeek } }
    ios:       { status: shipped, evidence: { workflow: apple-ci, job: ios-sim-tests, test: PlaybackSeekTests/testServerOffsetSeek } }
    ipados:    { status: shipped, evidence: { workflow: apple-ci, job: ipad-sim-tests, test: PlaybackSeekUITests/testSeekOnIPadLayout } }
    tvos:      { status: planned }
    android:   { status: shipped, evidence: { workflow: android-ci, job: instrumented, test: PlaybackSeekTest#serverOffsetSeek } }
    androidtv: { status: planned }
```

- `status` is one of `shipped | partial | planned | blocked | n/a`.
- `n/a` requires a `reason` (e.g. tvOS downloads, §14.5).
- `blocked` requires `blocked_by` naming a CONF id, an open question, or an upstream issue.

**Evidence is a stable identity, not a run id.** Revision 1 required a CI run number in the `evidence`
string, which is circular: a commit cannot name the run that verifies that same commit, and inserting
the number afterwards produces a commit nobody verified. Evidence is therefore
`{workflow, job, test}` — knowable at commit time — and the gate asserts that (a) the named workflow
and job exist, (b) the named test exists in the repo, and (c) the workflow is a **required check** on
the default branch. Whether that test passed on this commit is answered by the required check itself,
which is exactly the mechanism designed for the question.

**Evidence must match the claim's granularity.** A core unit test does not evidence a platform UI
capability, and an iPhone simulator run does not evidence iPad navigation, resizing, pointer/keyboard
input or split view. Rows carry `evidence_kind` implicitly through the job they name, and the gate
enforces the mapping: platform-integration and interaction claims must name a job that runs on that
platform's device or simulator, not a shared core job.

### 19.3 The gate

A `parity-gate` job on `ubuntu-latest` on every PR:

1. **No silent downgrade.** Diff against the merge base. **Any cell moving from `shipped` to a lower
   status fails the job, unconditionally. The gate itself has no escape hatch.**

   ⚠️ **Revision 2's mechanism does not exist and has been replaced.** It said a protected
   `regression-approved` label "may only be applied by a CODEOWNER." **OBSERVED** (GitHub, Managing
   labels): *"Anyone with **triage** access to a repository can apply and dismiss labels."* **GitHub has
   no per-label ACL**, and `CODEOWNERS` governs review requirements on file paths — it confers no label
   permission whatsoever. That substitution was the same class of weakness it was introduced to fix:
   an authorization that is announced but not enforced. (Mitigating, and the reason this was not
   catastrophic: on a public repo an outside fork contributor has no triage permission, so the exposure
   was org members rather than drive-by PRs.)

   **The replacement rests on two mechanisms GitHub enforces server-side, and nothing else:**

   a. **An intentional regression is declared in-repo, not in PR metadata.** It goes in an
      `accepted_regressions:` block in `FEATURES.yml` itself — id, the cell it covers, a reason, and the
      PR that introduced it. The gate reads that block; a downgrade with no matching entry fails.
      PR-body text and labels are not inputs to the gate at all, because a contributor can author both.

   b. **`FEATURES.yml` is CODEOWNER-protected, and that IS enforced.** `CODEOWNERS` assigns
      `/FEATURES.yml` to the **maintainer accounts**, and the branch rule enables **"Require review
      from Code Owners"**. ⚠️ **Corrected 2026-08-20 — revision 5 said "the maintainers team", and
      there is no team to assign to:** the repository owner is a GitHub **User** account, not an
      Organization, so `CODEOWNERS` entries must resolve to individual collaborators unless ownership
      changes. **OBSERVED** (GitHub, About protected branches), verbatim: *"any pull request that
      affects code with a code owner **must be approved by that code owner** before the pull request can
      be merged into the protected branch."* So declaring a regression **requires touching a
      CODEOWNER-protected file**, and the PR cannot merge without a maintainer's approving review.
      The authorization is a review, which GitHub actually gates on — not a label, which it does not.

      ⚠️ **Why two code owners, stated without overstatement.** GitHub will not accept an approving
      review from a pull request's own author — **OBSERVED 2026-08-20**, `422 Unprocessable Entity —
      "Review Can not approve your own pull request"`, and GitHub documents the same rule. With a
      single code owner, that account can never merge a pull request it opened, and it is the account
      that does the work. **A pull request opened by the other admin collaborator and approved by the
      sole code owner does merge** — that path was used for PR #1 and was never blocked, so "one code
      owner is unmergeable" would be false. It was abandoned for a different, measured reason:
      **GitHub attributes a squash-merge commit to the pull request's author**, so merging that way
      wrote that account's non-`noreply` address into a public commit on `main` (**OBSERVED**, commit
      `0e3566e`). Naming both accounts lets the working account open the pull request and the other
      approve it, which keeps commit authorship and contact addresses where §24.1 wants them.

      ⚠️ **And state the cost: this broadens the gate.** Where a path has several owners, an approval
      from **any one** of them satisfies the requirement — so the credentials able to approve a
      `FEATURES.yml` change went from one account to two. `require_last_push_approval` is enabled to
      partly offset that: the approval must come from an actor other than whoever made the last push.
      **With one person holding both accounts this records an account switch, not an independent
      human review.** What (b) actually buys is that declaring a regression is a deliberate step
      against a protected file. The independent adversarial review required by §25 is a separate
      obligation and is **not** discharged by approving from the second account.

   c. **`parity-gate` is a required status check** on the default branch, so the gate cannot be skipped
      by merging around it: *"Required status checks must have a `successful`, `skipped`, or `neutral`
      status before collaborators can make changes to a protected branch."* The job must therefore never
      exit `neutral` — a gate that neutral-passes on error is not a gate.

   d. 🚨 **"Do not allow bypassing the above settings" must be ON.** **OBSERVED:** *"By default, the
      restrictions of a branch protection rule do not apply to people with admin permissions."* Without
      this setting the whole mechanism is advisory for exactly the people most likely to be in a hurry.
2. **Evidence required and well-formed** for every `shipped` cell, per §19.2.
3. **Referential integrity.** Every `spec:` anchor resolves to a real heading in this document; every
   `conformance:` id exists in `docs/CONFORMANCE.md`; every `blocked_by` id exists.
4. **Schema validity.** Unknown keys, statuses or platform names fail.

There is no source-annotation scheme. (Revision 1 floated one as a "phase 2 extension"; naming it
invited building an annotation framework before the basic evidence cycle worked.)

### 19.4 As a delegate brief

*"Implement `FEATURES.yml` row `<id>` on `<platform>`. The contract is spec §`<n>`. It must satisfy
`<CONF-ids>`. When done, move the cell to `shipped` and fill `evidence` with the workflow, job and test
that prove it."* A complete, verifiable, self-contained unit of work, with the gate mechanically
rejecting "claimed done, not done."

---

## 20. Conformance and test architecture

### 20.1 What the suite is, and what it is not

`core-conformance` runs the **core** — the same code the apps run — against a **real Navidrome**, and
asserts protocol behavior. **Scope: protocol and server behavior only.** It cannot test Kotlin target
tiers, App Store signing, Actions billing, licence compatibility or Apple API availability; revision 1
promised that "every OBSERVED claim" would be pinned by a test, which was an impossible boundary.
Toolchain and platform claims are verified by the build itself (§4.4) or are marked ASSUMED and left
so.

The CONF list in §20.4 is **initial and representative, not exhaustive.** Ids are allocated in blocks —
0x negotiation and auth, 1x streaming and playback, 2x reporting, 3x sync, 4x media metadata, 5x
errors and edge cases — and a new test takes the next free id in its block, recorded in
`docs/CONFORMANCE.md`.

### 20.2 A deterministic environment, or the tests are noise

The Navidrome instance is **pinned at the reference version, never `:latest`** — by **image digest** on
Linux, and by **release-asset SHA-256** for the native macOS binary used by the Apple leg (§20.3). Both
pins name the same upstream release. The harness specifies, in a checked-in configuration:

- the complete server configuration file, including scanner settings, transcoding configuration and
  whether a similarity provider is configured (**not** configured — §18.5);
- a **scanner-readiness gate**: the harness waits for the scan to report complete and for a known
  probe item to be retrievable, rather than sleeping;
- **database reset between test classes**, so no test depends on another's play counts, playlists,
  favourites or queue;
- fixed credentials and roles, including a canary password (§13.4) and a second, restricted user for
  permission tests;
- a fixed time zone (UTC) and a controlled clock where a test needs one;
- container architecture matched to the runner;
- a health check and an explicit timeout.

#### 20.2.1 The transcoder is part of what is under test, and it is pinned

🚨 **This is a consequence of moving Apple CI to hosted runners (§21.1), not a pre-existing defect.**
The hosted-runner decision was right on cost and on security, and it introduced this: the Darwin
conformance leg runs a **natively-launched Navidrome**, and Navidrome transcodes by invoking **ffmpeg**.

**OBSERVED 2026-08-18**, from the `actions/runner-images` image inventories: `ffmpeg`, `ffprobe`,
`avconv` and `gstreamer` appear **zero** times in the `macos-15-arm64` and `macos-26-arm64` readmes.
⚠️ **Stated precisely, because the naive reading of that probe is wrong:** the same readmes list tools
as small as `zstd` and enumerate installed packages, so the instrument is not blind — but an image
inventory is not a filesystem probe, and **the hosted Ubuntu readme reports zero for ffmpeg too.** The
defensible claim is therefore *"ffmpeg is not part of the documented hosted-runner toolset on either
platform"*, not *"the binary is provably absent"*. **Which is exactly why the fix is a runtime
assertion rather than a belief about an image.**

**Without this section, the failure mode is the worst kind available:** a Navidrome with no transcoder
does not error — it falls back to direct play. Every transcode test would then pass while measuring
nothing. That is a **powerless test**: control and treatment identical, zero evidence, reported green.
It is strictly worse than having no transcode test, because it manufactures confidence.

**NORMATIVE — ffmpeg is pinned to an exact build wherever it runs:**

| leg | transcoder | how it is pinned |
|---|---|---|
| Linux (`ubuntu-latest`) | the one **bundled in the Navidrome container** | **by the container's image digest.** A digest pins the exact filesystem, so it pins that ffmpeg build exactly. The resolved ffmpeg version is recorded in `docs/TOOLCHAIN.md` alongside the digest |
| Darwin (`macos-latest`) | an **explicitly installed** ffmpeg | an explicit workflow step installing a **named version**, verified by checksum. Never "whatever `brew install ffmpeg` gives today" |

⚠️ **We disagree with one line of the premise audit here, and the difference is load-bearing.** The
audit states the container's ffmpeg "floats with the Alpine base and is not part of the pin." That is
true when pinning by **tag** and false when pinning by **digest** — and §20.2 already specifies a
digest. So the Linux leg's transcoder is pinned today; only the Darwin leg was unpinned. The
remediation is smaller than the audit implies, and the *reason* matters: a future session must not
"simplify" the digest pin back to a tag, which would silently unpin the transcoder.

**Consequence for what each leg is allowed to assert** — this is the substantive design decision, and
neither of the two obvious options is sufficient alone:

- **Byte-level transcode assertions run on the Linux leg only.** Transcoded output is
  ffmpeg-dependent, so two legs running two different ffmpeg builds produce two different answers and
  neither is authoritative. One pinned transcoder, one place, one truth.
- **The Darwin leg keeps every transcode-*adjacent* test that is genuinely Darwin-specific** — ranged
  requests through `NSURLSession`, the resource-loader validation path against a transcoded stream
  (unknown length, possibly not range-capable), and server-offset seeking. These need *a* working
  transcoder but not byte-identical output, and moving them to Linux would delete exactly the coverage
  the resource loader (§12.4) most needs. **So the Darwin leg installs a pinned ffmpeg too**, and
  asserts transport and protocol behaviour rather than encoded bytes.

#### 20.2.2 The suite asserts its own preconditions and fails loudly — a general rule

Generalising from ffmpeg, because it will not be the last dependency of this shape:

> **Every conformance test that depends on a server-side capability must verify that capability is
> present before asserting on its output, and must FAIL — not skip — when it is absent.**

- The harness health check runs **before any test class**, and asserts: the server is reachable, the
  scan is complete, the pinned ffmpeg binary is present **and reports the pinned version**, and the
  server actually reports the capability under test (for transcoding, a known item returning
  `canTranscode: true` from `getTranscodeDecision`).
- A failed precondition **errors the affected test class**. It never skips it. A skip is reported as
  green by most CI surfaces, which reproduces the exact failure this section exists to prevent.
- The precondition results are printed in the run summary, so a green run states *what it proved*, not
  merely that it finished.

**Media corpus:** generated at setup by `tools/seed-corpus` — synthesized tone and silence files in
FLAC, MP3, Ogg and M4A with known tags, known durations, and deliberately awkward cases (unicode
titles, multi-disc albums, several album artists, a track with no album, a very long title, a 300-track
album for paging, a 29-second track and a 31-second track for the threshold boundary). **No
copyrighted audio and no binary blobs in the repository.**

### 20.3 Three test layers, each with an honest name

Revision 1 claimed a fixture-replay suite proved "transport parity." It does not: an in-process Ktor
server cannot reproduce malformed or duplicate headers, unusual header casing, truncated chunked
bodies, socket reset after N bytes, TLS failures, redirect chains, HTTP/2 versus HTTP/1.1, connection
reuse or `NSURLSession` cancellation behavior — and if the replay server is itself Ktor it may normalize
away the exact anomalies under test. The corrected architecture:

| layer | where it runs | what it actually proves |
|---|---|---|
| **Conformance** — core against real Navidrome | `ubuntu-latest` (Docker) via the `jvm` target, **and** the same suite on `macosArm64` on `macos-latest` against the **pinned Navidrome release binary run natively** (the hosted macOS image has no Docker, which is why a native binary is the right instrument rather than a workaround) | protocol behavior, and that the **Darwin/NSURLSession** engine makes the same real network calls with the same results |
| **Envelope and parser parity** — replay of recorded bodies | every target, in-process | that envelope detection, JSON/XML parsing, unknown-field tolerance and the §12.4 validator table behave identically on all targets |
| **Wire pathology** — a low-level scripted socket/TLS server in `tools/` (raw sockets, not Ktor) | every target | truncated bodies, mislabeled content types, duplicate and malformed headers, mid-body reset, redirect chains, TLS chain failures, and cancellation of a ranged request |

Running the real conformance suite on macOS against a native Navidrome binary is what closes the Darwin
gap; it needs no Docker and no fixture-fidelity argument.

### 20.4 Representative tests

| id | pins |
|---|---|
| CONF-01 | `getOpenSubsonicExtensions` reachable **unauthenticated** |
| CONF-02 | the advertised extension set **for the pinned image and configuration**; an unknown new extension is informational drift, **not** a failure (a client that must ignore unknown extensions cannot also fail when one appears); a **missing** previously-required extension **is** a failure |
| CONF-03 | `ping` with a salted token succeeds; a wrong password yields `AuthenticationFailed`, not `ServerUnreachable` |
| CONF-04 | a fresh salt on every request, from a request recorder |
| CONF-05 | `openSubsonic`, `type`, `serverVersion` present in the envelope |
| CONF-06 | mappings for every error the harness can trigger, **plus** an unknown code round-tripping to `Server.Unknown` |
| CONF-07 | `formPost` calls succeed and no credential appears in any request line |
| CONF-11 | `/rest/stream` success returns binary with a plausible content type and correct signature bytes |
| CONF-12 | Error shape **per delivery path**: `/rest/stream` with a bad id, **and** `getTranscodeStream` with a bad id. Records the actual HTTP status for each, since the two paths use different error conventions (§12.4). This is what promotes §12.4's defensive assumption to an observation |
| CONF-07b | whether the reference server honours **form-POSTed credentials on `stream`** — changes §13.2's threat analysis (C6) |
| CONF-16 | **drives the transcode concurrency limiter** until it rejects: asserts HTTP **429**, a `Retry-After` header, an envelope body, and that the client maps it to `Server.Busy` rather than a generic error (§18.12) |
| CONF-17 | `estimateContentLength` on a transcoded legacy stream returns a usable `Content-Length` (§12.5) |
| CONF-43 | a fixed query set through `search3`, recording returned id sets, to measure local-vs-server matching divergence (§18.1) |
| CONF-13 | `Range` support on raw and on transcoded streams |
| CONF-14a | `transcodeOffset` seek on the **legacy** `stream` path returns audio at the requested offset |
| CONF-14b | offset behavior on the **`transcoding` extension** path — measured before any Path-A seek code is written |
| CONF-15 | `getTranscodeDecision` POST with a `ClientInfo` payload returns a decision; `transcodeParams` is opaque and round-trips to `getTranscodeStream` unmodified |
| CONF-21 | does `playbackReport` increment play count? (gates §15.4 — **not a Phase-1 test**) |
| CONF-22 | `scrobble submission=false` does not change play count; `submission=true` does |
| CONF-23 | repeated `scrobble` with the same `time` — measures whether the server deduplicates (§15.3) |
| CONF-31 | `getIndexes?ifModifiedSince` behavior and granularity (§16.1) |
| CONF-32 | paging past the end returns an empty list, not an error |
| CONF-33 | library mutated mid-import: the committed generation is internally consistent and the stability witness detects the change |
| CONF-41 | `getCoverArt` size behavior and content types |
| CONF-42 | `songLyrics` v2 structured response shape |
| CONF-51 | permission errors for the restricted user map to `Auth.Forbidden`, not a generic failure |
| CONF-52 | unknown fields in a response are preserved and ignored |

### 20.5 Facade header review

On every change under `core/facade`, CI regenerates the Objective-C header and **fails if the diff is
not committed**. The diff therefore appears in the PR as reviewable content. An unreviewed facade
change is how a Swift-side compile break reaches the operator instead of CI.

### 20.6 Fixture and recording hygiene

Recorded bodies used by the parser-parity layer are **canonicalized before storage**: credentials, salts
and tokens are stripped and replaced with fixed placeholders; timestamps, ports and any server-generated
id that varies per run are normalized; fixture files are named deterministically from the request shape.
A fixture is therefore stable across runs and contains no credential material even though the harness
password is synthetic (§13 applies regardless).

Fixture updates enter the branch **as an explicit commit by a maintainer**, prompted by a CI job that
uploads the diff as an artifact and fails with a message naming the command to regenerate. There is no
bot that pushes to a PR branch. A changed reference server therefore produces a visible, reviewed
change rather than either a silent pass or a permanently red build.

---

## 21. CI and build

### 21.1 The split, and why — corrected 2026-08-18

**Apple builds run on GitHub-hosted `macos-latest`. Kotlin, Android, lint, conformance and the parity
gate run on `ubuntu-latest`. There is no self-hosted runner in this project.**

Revision 2 specified a self-hosted macOS runner on the premise that hosted macOS had to be avoided on
cost grounds — the well-known 10x billing multiplier for `macos-*` runners. **That premise is false for
a public repository.**

**OBSERVED** (GitHub, *2026 pricing changes for GitHub Actions*,
https://github.com/resources/insights/2026-pricing-changes-for-github-actions, fetched 2026-08-18),
verbatim:

> "Standard GitHub-hosted or self-hosted runner usage on public repositories will remain free."

**OBSERVED** (GitHub Docs, *Actions minute multipliers*,
https://docs.github.com/en/billing/reference/actions-minute-multipliers, fetched 2026-08-18), the only
carve-out:

> "The larger runners are not free for public repositories."

So the 10x macOS multiplier is a **private-repository** cost. On a public repository a standard hosted
macOS runner costs nothing, which inverts the economics entirely: hosted CI is both cheaper (free
either way) and safer (no long-lived machine is involved at all), and the self-hosted design bought
nothing while carrying the fork-PR exposure that made §21.3 hard.

**Two caveats, normative:**

1. **Only STANDARD runners are free on public repositories. Larger and premium macOS runners are always
   billed** — included minutes cannot be applied to them. **No workflow in this repo may request a
   larger-runner label.** `runs-on` for Apple jobs is exactly `macos-latest` (or a pinned
   `macos-<version>` when a toolchain pin demands it, §4.4) and never a larger-runner label. A CI lint
   step asserts this, because the failure mode is a silent bill rather than a broken build.
2. **Hosted macOS concurrency is capped well below Linux**, so a wide Apple matrix **queues rather than
   fans out** and a broad matrix makes CI slower, not faster. **The Apple matrix is deliberately
   narrow:** one job per surface that actually needs its own destination (macOS, iOS simulator, iPad
   simulator, tvOS simulator), sharing one framework build, and never a cross-product over
   configurations, Xcode versions or OS versions. Adding an Apple matrix axis is a design decision that
   belongs in this section, not a convenience in a workflow file.

| workflow | runner | contents |
|---|---|---|
| `core-ci.yml` | `ubuntu-latest` | Gradle build of `common` + `jvm` + `androidTarget`; unit tests; ktlint/detekt; `verifySqlDelightMigration` plus the migration fixture databases (§11.4); **conformance suite** against the Navidrome service container; parser-parity and wire-pathology layers on the JVM target |
| `android-ci.yml` | `ubuntu-latest` | assemble; instrumented tests on an emulator |
| `apple-ci.yml` | `macos-latest` (standard) | one serial macOS job: the Phase-0 Kotlin/Native and Xcode shell build, OS-floor assertion, then the §12.4 resource-loader negative canary and strengthened measurement. Native Navidrome conformance joins this same job with the §20.2 environment; it never creates a second hosted-macOS job. |
| `parity-gate.yml` | `ubuntu-latest` | the `FEATURES.yml` gate (§19.3) |
| `release.yml` | `macos-latest` (standard) | archive + TestFlight upload for **both channels** (§22): `push` to `main` ships DEV, a `v*` tag ships PROD. The only workflow able to read signing secrets |

**Note on the Linux-only claim:** GitHub Actions **service containers** require a Linux runner, so the
`services:`-based Navidrome cannot run in the Apple job. That is a statement about the Actions feature,
and it is why the Apple leg runs Navidrome as a native pinned binary instead (§20.3).

Every workflow carries:

```yaml
concurrency:
  group: ${{ github.workflow }}-${{ github.ref }}
  cancel-in-progress: true
```

with per-job `timeout-minutes`: 20 `core-ci`, 25 `android-ci`, 15 `apple-ci`, 5 `parity-gate`, 60
`release`. **OBSERVED 2026-08-20:** the first complete standard-hosted `macos-26` job ran from
`22:11:30Z` to `22:14:37Z`, 187 seconds wall-clock. The 15-minute budget is the next five-minute
boundary above four times that cold duration; `docs/TOOLCHAIN.md` links the run. The hosted macOS
runner is **3 vCPU / 7 GB**, and a cold Gradle KMP build producing five
Kotlin/Native targets plus four Xcode targets plus simulator tests is a lot for that machine. Run one
complete build and set the number from the measurement — a timeout that is too low fails green builds,
and one that is too high holds a capped macOS concurrency slot while a hung job burns an hour. **These are now queue hygiene, not machine protection.** With a capped hosted-macOS
concurrency pool, a superseded run that keeps holding a macOS slot delays the run that replaced it, and
a hung job holds a slot until its timeout. Both settings matter more on hosted runners than they did on
a dedicated Mac, not less.

### 21.2 Secrets and release credentials

The only privileged material in CI is the Apple distribution signing identity and the App Store Connect
API key used by `release.yml`. Both live in **GitHub Actions secrets scoped to an environment**. The
PROD path additionally requires manual environment approval; the DEV path runs unattended on merge to
`main`, which is safe because it can only reach internal testers on the maintainer's own devices
(§22.1). No other workflow can read the secrets, so a fork PR — which cannot access secrets at all —
has no path to them even in principle.

### 21.3 OQ-1 is CLOSED — resolved by correction, do not reopen

**Status: closed 2026-08-18. The question was malformed, not merely unanswered.**

OQ-1 asked for a choice between attaching a self-hosted runner to a public repo — which is a known bad
practice, since a fork PR can compromise a persistent runner — and standing up a separate private
CI/release repository. **Both options existed only because revision 2 assumed hosted macOS had to be
avoided on cost grounds.** That premise was false for a public repository (see the two OBSERVED quotes
in §21.1), so:

- **There is no fork-PR code-execution exposure**, because no persistent machine is involved in CI at
  all. The class of risk that made revision 2's job-level `if:` guard inadequate — a fork PR can modify
  the very workflow that contains the guard — simply does not arise on ephemeral hosted runners.
- **The separate-private-CI-repo option is withdrawn.** It solved a problem that no longer exists and
  cost a permanent mirror.
- **The repository is public from the start.** Nothing in this design requires it to stay private, and
  the phasing (§25) no longer gates going public on anything.

If a later change proposes self-hosted Apple CI again, the burden is to show why free hosted runners
are insufficient — cite this section and the two URLs in §21.1. What still binds regardless: keep
non-Apple CI on `ubuntu-latest`, and never reach for a larger runner (§21.1 caveat 1), which is the one
place real money can still appear.

### 21.4 Building locally

CI is entirely hosted, so nothing in this project depends on a particular workstation. For contributors:
a standard Xcode and JDK/Gradle setup builds every target, and `README.md` carries the exact commands
once the scaffold exists.

Two build-environment failure modes are worth knowing because they present as something else, and both
are properties of the Apple toolchain rather than of any one machine:

- **A wedged CoreSimulator hangs every Xcode build with no error output**, including device and archive
  builds, freezing at `CompileAssetCatalogVariant`. It looks like a corrupt asset catalog and is not.
  `xcrun simctl list devicetypes | head` returning nothing is the tell — a two-second preflight worth
  running before any archive.
- **`xcodebuild test` clones the destination simulator by default.** Where cloning is unavailable, the
  clone fails *after* a successful build and the run reads as a test failure when no test executed.
  `-parallel-testing-enabled NO` is the fix.

Concurrent simulator and Xcode builds are memory-hungry enough to trigger OOM kills on a workstation
that is doing anything else; serialise them rather than fanning out locally.

**Maintainers building on a shared or managed machine follow that machine's own operational rules,
which are deliberately not reproduced in this repository.**

---

## 22. Release channels: DEV and PROD

Two channels, both TestFlight, deliberately different in speed and in audience. **DEV exists to be
broken; PROD exists to be trusted.** The mechanics below are chosen because they map onto how
TestFlight actually works rather than onto a naming convention.

### 22.1 The two channels

| | **DEV** | **PROD** |
|---|---|---|
| trigger | **every merge to `main`**, fully automatic | **a `vX.Y.Z` tag**, cut by hand every few days or few iterations, once DEV has accumulated genuinely finished features. **Never automatic** |
| TestFlight group | **internal** testers (up to 100; maintainer devices only) | **external** group (the wider trusted testers) |
| Beta App Review | **not required** — internal builds are available within minutes | **required** — so PROD is slower by design |
| purpose | dogfooding against a real library (§22.4) | a build other people are asked to rely on |
| breakage | expected, and the point | a defect here costs someone else's afternoon |
| marketing version | the in-progress `vX.Y.Z-dev` | `vX.Y.Z` |
| build number | auto-incremented per build | auto-incremented per build |

**The external-review latency is a feature, not friction to engineer around.** It is the thing that
stops a bad afternoon on `main` reaching anyone who is not the person who caused it. Do not add a
mechanism that routes around it.

**PROD cut gate — all three, no exceptions:** CI green on the tagged commit; the conformance suite
passing (§20); and `FEATURES.yml` showing no undeclared regression (§19.3). A tag that fails any of
them is deleted and re-cut, never shipped with a note.

### 22.2 Separate bundle identifiers, so both install side by side

| channel | bundle identifier | display name |
|---|---|---|
| PROD | `${BUNDLE_PREFIX}` = `com.legitimateapps.dulcet` | **Dulcet** |
| DEV | `${BUNDLE_PREFIX}.dev` = `com.legitimateapps.dulcet.dev` | **Dulcet DEV** |

This matters more than it sounds for a media app: **comparing playback behaviour between a known-good
build and a candidate requires both installed at once**, and a single identifier makes that impossible
— installing one uninstalls the other, taking its queue, cache and downloads with it. Separate
identifiers also mean separate caches, so a DEV migration bug cannot corrupt a PROD library.

**The two builds must be visually distinguishable at a glance.** Distinct app icons and distinct
display names, so a screenshot, a bug report or a Now Playing entry is never ambiguous about which
build produced it.

🚨 **NORMATIVE: the `.dev` App Store Connect record is a TestFlight-only artifact for the life of the
project and must NEVER be submitted for App Store release.** Not "not yet" — never. It carries
development logging, may carry a preconfigured server (§22.3), and is not built to the standard PROD
is. Anyone who finds themselves preparing a `.dev` submission has misunderstood the channel.

### 22.3 What actually differs per channel

Exactly these settings vary, and they are the complete list — anything else differing between the two
channels is a bug, because DEV would then stop predicting PROD's behaviour:

| setting | DEV | PROD |
|---|---|---|
| bundle identifier | `${BUNDLE_PREFIX}.dev` | `${BUNDLE_PREFIX}` |
| display name | Dulcet DEV | Dulcet |
| app icon | distinct DEV variant | production icon |
| logging verbosity | debug enabled by default (§18.11) | debug off by default, opt-in per session |
| diagnostics surfaces | direct-play probe, sync diagnostics and the transcode-reason view always visible | reachable, but behind the diagnostics screen |
| preconfigured server URL | **may** ship one, for convenience | **must ship NONE** |

🚨 **PROD ships with no preconfigured server, and this is not a preference.** A hardcoded internal
address in a published binary is **both a disclosure and a defect**: it leaks a private network address
to everyone who downloads the app, and it is wrong for every user who is not the person who hardcoded
it. **Anything of that shape belongs to the DEV target only, and never to a build that leaves the
maintainer's own devices.** The build configuration must make this structurally impossible rather than
relying on someone remembering — the field is read from a DEV-only configuration file that the PROD
target does not compile.

Optimization level, assertions, and every correctness-relevant flag are **identical** across channels.
A DEV build that behaves differently from PROD because of a build setting is not dogfooding, it is
testing a different program.

### 22.4 Dogfooding target — reconciling with OQ-8, which only looks like a contradiction

Two different activities that must not be conflated:

- **Automated testing — CI, the conformance suite, every test run — targets the local, disposable
  Navidrome pinned to 0.63.2, and never a personal or production server.** Unchanged and still
  normative (§20.2, OQ-8).
- **Manual dogfooding of DEV builds targets the maintainer's own real music library**, which is the
  entire reason DEV exists. A library of ~31,500 tracks surfaces sync, paging, artwork-cache and
  scroll-performance problems that a synthetic corpus never will.

**The line is automation, not the server.** A person opening a DEV build and pressing play on their own
server is a user using a music player. A test harness writing to that server is automation against
production data, and is forbidden. Concretely:

- **Forbidden:** any automated run — CI, a test suite, a scripted sweep, a sync-engine first pass —
  pointed at a personal or production instance.
- **Forbidden:** any automated **write** to such an instance at all: scrobbles, playlist mutations,
  ratings, favourites.
- **Expected:** a human using a DEV build normally against their own library, including the writes that
  normal use produces.
- **First run of a new sync generation goes to the local instance**, never to a real library, because
  that is the pass most likely to be wrong.

Machine-specific paths and addresses for either instance live in maintainer-local operational notes,
**not in this repository** (§28 revision 5).

### 22.5 Release notes and App Store Connect hygiene

- **DEV release notes are generated**, not written: the merged PR titles since the previous DEV build,
  plus the short commit range. Nobody hand-writes notes for a build that ships several times a day.
- **PROD release notes are written by hand** for the people receiving them — what changed that they
  will notice, and what is known to be broken. They are not a changelog dump.
- **Two separate App Store Connect records**, one per bundle identifier, never one record with two
  groups. Internal testers are attached to the DEV record; the external group is attached to PROD.
  Keeping them as separate records is what makes "never submit `.dev`" enforceable by inspection
  rather than by vigilance.
- `CHANGELOG.md` in the repository is the source for PROD notes and is updated as part of cutting the
  tag, not afterwards.
- The `release.yml` workflow (§21.1) handles both channels, selected by trigger: `push` to `main`
  produces DEV, a `v*` tag produces PROD. It remains the only workflow able to read signing secrets.

---

## 23. Distribution

### 23.1 macOS ships first

**Binding statement:**

> **Initial macOS distribution: TestFlight and Mac App Store. Direct notarized distribution is not
> initially supported.**

**This is a deliberate choice, not a limitation** — one distribution path to get right, no notarization
pipeline, no self-update mechanism (§23.3 forbids one on macOS anyway), and it is the path the team is
already provisioned for.

#### macOS submission on this team is PROVEN, not theoretical

Revision 2 treated Mac App Store provisioning as an open risk and gated Phase 2 on discovering whether
it was possible. **It is not a risk. OBSERVED 2026-08-18** via read-only App Store Connect API calls
against team 3LTL47SJ8C:

| checked | result |
|---|---|
| team roles | an **`ACCOUNT_HOLDER`** exists (also carrying `ADMIN`), plus one further `ADMIN` and two `DEVELOPER`s |
| macOS app records | **two existing apps already ship `MAC_OS` `appStoreVersions`** alongside iOS |
| Mac installer signing | a **`MAC_INSTALLER_DISTRIBUTION`** certificate is provisioned, expiring **2027-07-21** |
| distribution signing | **two `DISTRIBUTION` certificates**, expiring **2027-06-06** and **2027-05-20** — the unified type that also signs macOS App Store builds |
| existing records | 10 app records, 18 registered bundle ids |
| our identifiers | **neither `com.legitimateapps.dulcet` nor `com.legitimateapps.dulcet.dev` exists yet** — both are free, and both must be created |

**So the macOS app-record path has been walked twice on this exact team, and the certificate pair a Mac
App Store submission actually needs is already in place.** Nothing about macOS submission is unproven
here, and Phase 2 should not spend time re-establishing it.

🚫 **`DEVELOPER_ID_APPLICATION` is confirmed ABSENT — and that blocks nothing. Do not create one.**
Developer ID exists solely for **notarized distribution outside the store**, which §22 and this section
rule out. A later session reading "no Developer ID certificate" as a blocker and provisioning one would
be spending a certificate slot on a distribution channel this project does not use. The pair that
matters — `DISTRIBUTION` + `MAC_INSTALLER_DISTRIBUTION` — **both exist**. (Revision 7 framed the absence
as "a ten-minute action for the Account Holder"; true, and beside the point, because the action is not
needed.)

**Agreements are not a gate either. OBSERVED** (Apple): the Program License Agreement covers *"the
distribution of **free apps**"*, while *"To sell your apps on the App Store or offer In-App Purchases,
the Account Holder must sign the **Paid Apps Agreement**."* **Dulcet is free with no IAP**, so it needs
only the Program License Agreement accepted at enrolment — no Paid Applications Agreement, no banking,
no tax setup.

#### Two limits recorded honestly rather than smoothed over

1. **Whether the API key used is literally the Account Holder's key or a separately-provisioned
   Admin-role team key is UNVERIFIABLE.** `/v1/apiKeys` returns **404 `PATH_ERROR`**; Apple provides no
   introspection for a team key's own bound role. **What IS observed:** the key successfully called
   `/v1/users`, `/v1/certificates`, `/v1/apps` and `/v1/bundleIds` — all Admin-tier for team keys — so
   its privilege is **at least Admin-equivalent**. That is sufficient for everything below, since
   *"Only the Account Holder or Admin role can create distribution certificates."*
2. 🚨 **Write and create permission was NOT tested. The check was strictly read-only.** Admin-tier team
   keys typically carry create rights, but that is **ASSUMED, not observed**, and it is now **the only
   real unknown left in the signing path**. It is cheap to discover in the Phase-2 dry run and expensive
   to discover at submission, so **the dry run's first act is to exercise CREATE** — register the bundle
   id, create the App ID, generate a provisioning profile — before it archives anything.

⚠️ **One correction to a claim carried in from the premise audit.** That audit stated distribution
certificates are *"one of each type per team"*, so the existing one must never be revoked. **The
observation contradicts the count: two `DISTRIBUTION` certificates exist**, against a typical quota of
three — roughly one slot of headroom. The **operational advice still stands and is if anything more
important**: do **not** revoke or regenerate the existing certificates, because other projects on this
team depend on them, and the headroom is one slot, not none.

**Phase 2 still begins with a signing dry run**, and per §23.3 it archives a **sandboxed** hello-world
rather than a bare one. Its purpose is now narrower and sharper: **prove CREATE permission and prove
the sandbox entitlements**, not prove that macOS submission is possible.

**Do not advertise a downloadable `.app` or `.dmg`.** An open-source README promising a DMG that will
not be produced is a support burden that arrives immediately.

### 23.2 Signing identity and when identifiers freeze

- Apple Developer team **3LTL47SJ8C (Legitimate LLC)**. **OBSERVED 2026-08-18:** neither
  `com.legitimateapps.dulcet` nor `com.legitimateapps.dulcet.dev` is among the team's 18 registered
  bundle ids, so **both are free and both must be created** — and creating them is the first CREATE
  operation the Phase-2 dry run exercises (§23.1).
- Bundle identifiers under `${BUNDLE_PREFIX}` = **`com.legitimateapps.dulcet`** (header constants):
  `${BUNDLE_PREFIX}.mac`, `${BUNDLE_PREFIX}.ios`, `${BUNDLE_PREFIX}.tv`, plus `${BUNDLE_PREFIX}.dev`
  for the DEV channel (§22.2). Android `applicationId` `${BUNDLE_PREFIX}` and `${BUNDLE_PREFIX}.tv`.
- 🚨 **A bundle identifier freezes on the first BUILD UPLOAD, not on app-record creation.** Revision 2
  said "once an App Store Connect app record exists," which is wrong and removes a real escape hatch.
  **OBSERVED**, Apple verbatim: *"A unique identifier for your app that is used throughout the system…
  **You can't change this property after you upload a build.**"* Corroborating: *"You cannot delete an
  explicit App ID for an app you uploaded to App Store Connect"* and *"…if you've uploaded a build,
  your bundle ID can't be reused."*
  **Practical consequence:** App Store Connect records, App IDs and provisioning profiles may be
  created and revised **freely** before the first upload — a record with no build is fully reversible.
  The §23.1 dry run *does* upload a build, so the gate still lands in roughly the right place, but the
  team keeps an escape hatch it would otherwise think it had already spent.
- App Store seller: **Legitimate LLC**. Support URL `https://${DOMAIN}/support`; privacy policy
  `https://${DOMAIN}/privacy` (contents: Dulcet collects nothing — §13.7). **Both must be reachable at
  submission**, which puts OQ-7's purchase on the Phase-6 critical path even though it gates nothing
  earlier.
- **No individual's legal name, home address or system username appears anywhere** — not in
  `Info.plist`, a copyright string, commit metadata, listing copy, a code comment, a package `author`
  field, DNS, or certificate metadata.
- **The app name is clear.** **OBSERVED** (iTunes Search API, US store, `entity=software`,
  `term=dulcet`): one unrelated result, no App Store app named Dulcet. Apple's real naming constraints
  are the **30-character limit** (2.3.7), exact-name uniqueness at record creation, and the trademark
  and copycat rules (4.1(c), 5.2.1). **There is no guideline rejecting a name for mere similarity** —
  that belief is folklore, and 4.3(b)'s saturated-category rule lists dating, flashlight, sound
  effects, wallpaper, timers and fortune telling, not music clients.

### 23.3 App Sandbox — a hard Mac App Store requirement (was missing entirely)

**OBSERVED**, App Store Review Guideline **2.4.5(i)**, verbatim:

> "Apps distributed via the Mac App Store have some additional requirements to keep in mind:
> **(i) They must be appropriately sandboxed**, and follow macOS File System Documentation."

Revision 2 mentioned "entitlements" once in a list and never named App Sandbox as binding. For a music
client that downloads media, this constrains real design, so it is stated normatively here and
reconciled with §13.6 and §14.5.

**What the sandbox means for Dulcet's storage design — and the good news is that nothing breaks:**

- **Everything Dulcet writes lives in its own container**: the SQLDelight database, downloaded media,
  the artwork cache, and logs. An app may read and write its own container **without any user
  consent**, so the entire download and cache design in §14.5–§14.6 is legal under the sandbox as
  specified. **No part of the current storage design is impossible; none of it needed to change.**
- **`com.apple.security.network.client` is required** and is the only network entitlement needed — we
  make outbound connections and never listen.
- **Security-scoped bookmarks are NOT needed for v1**, because v1 never touches a user-chosen location
  outside the container. This is worth stating because it is the first thing an implementer reaches for
  on macOS. **The moment a "choose my download folder" feature is proposed, it acquires
  `com.apple.security.files.user-selected.read-write` plus security-scoped bookmark persistence, and
  that is a real feature with real cost — not a preference toggle.** Recorded here so the cost is
  visible before someone promises it.
- **A sandboxed app cannot write to shared locations**, so no design may place a cache, a log or a
  download outside the container. §13.6's "app-private directory" is therefore the sandbox container,
  not a convention we chose.
- Two adjacent 2.4.5 clauses that constrain the build and any future updater, both verbatim:
  *"(ii) They must be packaged and submitted using technologies provided in Xcode; no third-party
  installers allowed… cannot install code or resources in shared locations."* and *"(vii) They must
  use the Mac App Store to distribute updates; **other update mechanisms are not allowed**."*
  **So Dulcet ships no self-updater on macOS, ever**, and the Gradle-driven build must produce a
  standard Xcode-archived bundle (§4.3 already does).

**ASSUMED, and settled cheaply:** the exact entitlement keys and container semantics above are stated
from working knowledge — Apple's documentation site is JavaScript-rendered and could not be quoted
verbatim in this pass, so unlike guideline 2.4.5 they are **not** OBSERVED. **The Phase-2 signing dry
run (§23.1) settles all of them at once by archiving a hello-world that is sandboxed, declares
`com.apple.security.network.client`, and writes a file into its container** — rather than a bare
hello-world, which would prove nothing about the constraint that actually matters.

### 23.4 App Review: Guideline 4.2.7 is the sharp hazard, not 4.2

Revision 2 named Guideline 4.2 (minimum functionality). That is true but not the dangerous one.

**OBSERVED**, Guideline **4.2.7 Remote Desktop Clients**, verbatim — note the conditional preamble,
which is the whole argument:

> "**If your remote desktop app acts as a mirror of specific software or services rather than a generic
> mirror of the host device**, it must comply with the following: (a) The app must only connect to a
> user-owned host device that is a personal computer or dedicated game console owned by the user, and
> **both the host device and client must be connected on a local and LAN-based network**… (b) Any
> software or services appearing in the client are fully executed on the host device, rendered on the
> screen of the host device… (e) **Thin clients for cloud-based apps are not appropriate for the App
> Store.**"

**Our position, and it should be written into the review notes rather than improvised if challenged:**
**4.2.7 does not apply to Dulcet, because Dulcet is not a remote desktop app.** It does not mirror a
host device's screen, does not render remote software, and does not stream a UI. It is a **native
client speaking a documented, openly specified REST protocol** (§1.1), with its own UI, its own domain
model, its own local cache and its own offline behaviour — the same relationship an email client has to
an IMAP server. Clause (a)'s LAN-only restriction would be fatal if it applied, since users reach their
servers over the internet; that clause is precisely why the classification argument must be made
proactively and not discovered at rejection.

**OBSERVED**, Guideline **4.2.3(i)**, verbatim, and this is the sharper of the two:

> "Your app should work on its own **without requiring installation of another app to function**."

Dulcet genuinely does not function without a server. The mitigation is the demo server (§23.5) — which
is now a permanent service rather than a submission-time task, and is the single highest-value
review-readiness artifact.

**Phase-0 deliverable, not a Phase-6 scramble:** write `docs/APP-REVIEW-NOTES.md` now, containing the
4.2.7 non-applicability argument, the 4.2.3(i) mitigation, the demo credentials, and a one-paragraph
plain-English description of what an OpenSubsonic server is. Reviewers change; the argument should not
have to be re-derived under time pressure.

### 23.5 The demo server is permanent infrastructure, not a review-window task

**OBSERVED**, Guideline **2.1(a)**, verbatim: *"…include demo account info **(and turn on your back-end
service!)** if your app includes a login."* And App Store Connect's App Review Information: **"The demo
account is used during the App Review process and must not expire."**

**So revision 2's "stood up for review windows" was a rejection risk on every update review, not just
the first.** Corrected:

- **The demo server runs permanently** — a small public Navidrome on Railway, seeded with royalty-free
  audio from the synthetic corpus (§20.2), with a non-expiring demo account.
- **Never a private or personal server instance.** That would expose a private service URL to a third
  party and link the app to infrastructure it should have no connection to — a security and identity
  decision, not a convenience.
- 💰 **Flagged cost, so it is seen rather than discovered:** this is a **small but ongoing hosting
  bill** and a piece of **standing infrastructure to maintain** — patching, uptime, and a corpus that
  must stay legally clean. OQ-4's original framing ("costs money and a little standing infrastructure")
  understated it. It is now a **Phase-6 blocker with a recurring cost**, and it must be up before every
  submission, including updates.
- Its uptime is worth a trivial external check, because a reviewer hitting a dead server produces a
  rejection that costs more than the monitoring.

### 23.6 Android distribution

GitHub Releases with a signed APK at Phase 4. Play Store and F-Droid deferred; Play in particular has
developer-identity display requirements that must be checked against §24.1 before an account is created
(**OQ-5**).

---

## 24. Repo conventions, identity, licensing

### 24.1 Identity — binding, no exceptions

- Repo under the **`legitimate-apps`** org; push via the **`github-legit`** SSH alias.
- **Commits authored as `new-usemame`, per-command, never global git config:**
  ```
  git -c user.name='new-usemame' \
      -c user.email='248195428+new-usemame@users.noreply.github.com' commit ...
  ```
- Public-safe: the `new-usemame` handle, `Legitimate LLC`, the Apple team id, the
  `com.legitimateapps.*` bundle namespace, and `${DOMAIN}` once purchased.
- 🚨 **Namespace discipline.** Dulcet ships under `com.legitimateapps.dulcet` (Legitimate LLC), and
  that prefix is immutable once an App Store Connect record exists. **Never publish this app under any
  other namespace, and never reuse a namespace from an unrelated project that happens to be present in
  a local build environment** — not as a bundle id, not in an entitlement, not in a provisioning
  profile name, not in a code comment. A bundle identifier is the most permanent place such a mistake
  can land.
- **Never public:** the operator's legal name, home address, macOS username, and prior GitHub handle.
  **The literal values are deliberately not written in this repo** — this document ships with it, and
  naming them here would publish exactly what the rule forbids. The enumerated list lives in the
  operator's global `~/.claude/CLAUDE.md` and that is the authority. Practical consequence: absolute
  home-directory paths must be scrubbed from anything committed — committed build logs, generated
  `.xcodeproj` paths, README snippets — because the macOS username rides in every one of them, and
  that is the most likely way this rule gets broken by accident.
- Never sign in to a Dulcet-related third-party service with Google or GitHub SSO from a browser logged
  in as another identity — it links the accounts silently.

### 24.2 Licensing

**Recommendation: Apache-2.0** (operator confirmation needed — **OQ-2**). Reasons: an explicit patent
grant, which MIT lacks; it matches Chora's licence (§24.3) so any small attributed component reuse
stays clean rather than requiring a licence-laundering pretence; and it is a permissive licence widely
used in App Store software.

**Phrase the App Store point carefully.** Apache-2.0 is commonly used in App Store apps, but whether
the *final binary* may be distributed depends on the **complete** dependency, licence and NOTICE set,
not on the top-level licence alone. So: Apache-2.0 is the selected permissive licence, subject to
dependency compliance, and a licence audit of the dependency graph is a Phase-0 deliverable.

**GPL-3.0 prior art — correct wording:**

> **Amperfy and Shelv are GPL-3.0 and cannot be used as code bases or code donors for our permissively
> licensed App Store client without separate permission or licensing. They may be evaluated as products
> and used as behavioral references; copied or derivative implementation code is out of scope.**

Do not write "unusable as a reference" — reading an app to learn which features and failure cases exist
is not copying its implementation. GPL matters here specifically because GPLv3's redistribution rights
conflict with Apple's additional distribution restrictions, a well-known incompatibility. Not legal
advice; if it becomes load-bearing it needs a lawyer, not an agent.

**Never paste Apache-2.0 code into an MIT-only file and treat it as MIT.** Material reuse keeps the
Apache licensing and `NOTICE` obligations for those components.

### 24.3 Chora — reference only, not a foundation

**OBSERVED** (https://github.com/CraftWorksMC/Chora, checked 2026-08-17): Apache-2.0, Kotlin, genuine
Android TV support (a `LEANBACK_LAUNCHER`, television feature detection, dedicated TV screens and
dialogs, Compose TV Material dependencies, D-pad/focus components), actively maintained (last commit
`8e06462`, 2026-07-25; v1.31/v1.31.1 released June 2026 with Android TV fixes).

**OBSERVED and decisive:** its README says *"Please do not use as a learning resource. This was my first
Kotlin project, and the code is not well-organized at all."*

**Verdict: do not adopt Chora as the Android foundation.** The justification is **architectural
incompatibility, not maintenance status**: the maintainer disclaims the architecture; it is a standalone
Android app with its own provider/data/cache/playback organization; our commitment is a KMP fat core;
retrofitting it around SQLDelight, shared queue policy, shared sync, shared errors and a narrow engine
boundary would cost more than building the Android shell correctly; and taking it wholesale would make
Android structurally different from Apple before the shared core stabilized.

**Legitimate uses:** test it as a competing client; inspect product behavior and TV interaction
coverage; harvest Android TV edge cases; take isolated Apache-2.0 components with attribution and
preserved notices; mine it for regression scenarios.

### 24.4 Do not generate the client from the OpenSubsonic OpenAPI document

**OBSERVED** (https://opensubsonic.netlify.app/docs/openapi/): OpenSubsonic labels its OpenAPI schema
work-in-progress, documents inconsistencies with the prose specification, and marks Kotlin generation as
untested.

Hand-build a focused, tolerant wire layer. DTOs may be generated **only** after the schema is reviewed
and patched, with the generated output checked in and reviewed like hand-written code. Always: preserve
unknown fields, tolerate known server deviations, and keep **wire DTOs separate from domain objects** —
the domain model must never inherit the wire's shape.

---

## 25. Phasing

A phase ends when its `FEATURES.yml` rows are `shipped` **with evidence** (§19.2), not when the code
compiles.

| phase | deliverable | exit criteria |
|---|---|---|
| **0** | spec approved; toolchain matrix (§4.4) **incl. the pinned ffmpeg**; dependency licence audit; **public** repo scaffold; CI skeleton; branch protection + `CODEOWNERS` + required checks per §19.3; `docs/APP-REVIEW-NOTES.md` (§23.4); `FEATURES.yml` seeded at `planned`; `apple-ci` timeout calibrated from one real run (§21.1) | `core-ci` and `parity-gate` green on an empty core; both OS-floor settings (§4.1) asserted by a CI check |
| **1** | **the §12.4 resource-loader spike first**, then core: transport, auth, capability negotiation, cache, sync, the inline-validation loaders, playback policy | the **Phase-1 conformance subset** green: CONF-01..07, 11..15, 22, 23, 31, 32, 33, 41, 51, 52. **Out of Phase 1: CONF-21** (a v1 non-goal, §15.4) and **CONF-42** (lyrics — no Phase-1 code consumes it). Reducer test vectors (§15.2) present and passing |
| **2** | **macOS app**, TestFlight | **Signing dry run first, and its job is narrow: prove CREATE permission** (register `com.legitimateapps.dulcet`, create the App ID, generate a profile) **and prove the sandbox entitlements**, by archiving a *sandboxed* hello-world that declares `com.apple.security.network.client` and writes into its container (§23.1, §23.3). macOS submission itself is already proven on this team and is not re-established here. Then: browse, search, play, queue, scrobble, offline **metadata** cache; installed from TestFlight on a real Mac and driven end to end. **No media downloads in Phase 2** — offline means the library browses, not that it plays offline |
| **3** | iOS + iPadOS; **media downloads on all three Apple surfaces** | background download and background playback observed on a real device; iPad evidence from an iPad job, not an iPhone one |
| **4** | Android phone/tablet | Media3 engine parity; parser-parity and wire-pathology green on every target |
| **5** | tvOS + Android TV | focus navigation verified on a real Apple TV and a real Android TV device or emulator |
| **6** | Mac App Store submission; polish | review passed; OQ-4 resolved |

**How work is parcelled out:** one `FEATURES.yml` row per unit of work (§19.4). Architecture decisions
and verification stay with the maintainer; implementation of a row is delegable. **Every change gets an
independent adversarial review before merge, and the reviewer is asked to check the commit message and
comments against the code, not only the code** — prose claims about ordering, recoverability and
impossibility are unverified by anything and rot first. Reviewing your own diff is the weakest check
available.

---

## 26. Decisions (formerly the open-questions register)

**All ten are answered. OQ-1 was closed by correction on 2026-08-18 (§21.3); OQ-2 through OQ-10 were
answered by the maintainer on 2026-08-18.** This section is now a decision record, not a queue. Nothing
here is open, and a later session should treat re-opening one of these as a change proposal that must
argue against the recorded rationale — not as filling in a blank.

| id | decision | notes |
|---|---|---|
| **OQ-1** | **CLOSED by correction.** Apple CI runs on GitHub-hosted standard runners; there is no self-hosted runner in this project. | The premise was false — standard hosted runners are free on public repositories, so there was no cost reason to use self-hosted hardware and therefore no fork-PR exposure to mitigate. Reasoning and both primary sources: **§21.3**. Do not reopen. |
| **OQ-2** | **Apache-2.0.** | Approved. Explicit patent grant; permissive; App Store compatible subject to the dependency licence audit, which stays a Phase-0 deliverable (§24.2). |
| **OQ-3** | **macOS 14 / iOS 17 / tvOS 17.** ✅ **DECIDED, and re-confirmed 2026-08-18 on corrected facts.** | Apple Silicon only. ⚠️ **The original justification was half wrong** — it cited `@Observable` *and* "the modern navigation APIs", but `NavigationStack` and `NavigationSplitView` are available at iOS 16 / macOS 13 / tvOS 16, a full major version below the floor. That error was caught, flagged, and the question returned to the maintainer rather than kept silently. **Re-examined on `@Observable` alone, the floor survived:** `ObservableObject` invalidates a whole view on any published change while `@Observable` invalidates per property, which is a real difference on a screen scrolling tens of thousands of rows — the one surface that must never stutter. And the reach cost is close to nil: iOS 17 shipped September 2023 and is three OS generations old, so 16/13/16 would buy a sliver of installed base and pay for it with the coarser observation model forever. **The 16/13/16 alternative was considered and rejected.** 🚫 Do not re-derive this from the old navigation premise, and do not reopen it because the original reason was false — the reason was false, the conclusion stands (§4.1). |
| **OQ-4** | **A small public Navidrome on Railway**, seeded with royalty-free audio, existing solely to give App Review working credentials. ⚠️ **It must run PERMANENTLY, not per review window.** | **Never a private or personal server instance** (§23.5). **OBSERVED:** Apple requires *"(and turn on your back-end service!)"* and App Store Connect states **"The demo account… must not expire."** An ephemeral server is a rejection risk on **every update review**, not just the first. 💰 **So this is standing infrastructure with a small ongoing hosting bill and a maintenance burden** — patching, uptime, and a corpus that stays legally clean — not a submission-time task. The original framing understated it. Still a Phase-6 blocker; Phase 2's TestFlight work does not depend on it. |
| **OQ-5** | **Approved in principle, pending the identity-disclosure finding.** Proceed toward a Google Play **organization** account for Legitimate LLC, registered with business documents. | Hard constraints: no individual's legal name on any public-facing surface; the LLC's registered-agent address in preference to any residential one; **full compliance with Play's legal and verification requirements — never evasion of them**; credentials stored in the secret broker with the accounts ledger updated. Research is establishing what Play actually requires for an organization account and whether an individual's legal name can be kept off public surfaces. **That finding gates signup**: if Play would publish an individual's name, the decision returns to the maintainer before an account is created. GitHub Releases with a signed APK remains the Phase-4 path regardless (§23.4). |
| **OQ-6** | **Deferred. One active account in v1.** | Queues stay per-account with a single active account (§11.2). The cache and IDs are already namespaced per server, so multi-account browsing remains addable without a migration. |
| **OQ-7** | **`getdulcet.com` — chosen, not yet purchased.** | $11.08 register and renew, verified available. **Blocks nothing**: `${BUNDLE_PREFIX}` is decoupled from it (header), so it reaches only support/privacy URLs, the review demo server and marketing. Purchase needs a person because the registrar's web login requires a passkey and its API has no add-funds endpoint. |
| **OQ-8** | **A local, disposable Navidrome pinned to 0.63.2 is the development and CI target.** | **Normative: conformance and CI run against the local instance, never against a personal or production server.** The complementary rule — that a person manually dogfooding a DEV build against their own real library is expected, because the line is *automation*, not the server — is set out in **§22.4**, which reconciles the two. Automated writes to a personal instance are forbidden in all cases, and a new sync generation's first pass always goes to the local instance. Machine-specific paths belong in maintainer-local operational notes, not in this repository (§28 revision 5). |
| **OQ-9** | **OS-trusted TLS only in v1.** Self-signed certificates and private CAs are refused. | **The user-facing consequence, stated honestly: some self-hosters will not be able to connect** — private-CA and self-signed deployments are common in this audience, and for them Dulcet v1 will fail at login with `TlsUntrusted`. The documented remedy is to install the CA at OS level. **Known v1 limitation.** Candidate v2 story: explicit per-server pinning of a user-supplied certificate, shown and confirmed once, stored per account — **never** a trust-all toggle, which would silently defeat the protection for every server (§13.5). |
| **OQ-10** | **REOPENED by review, then resolved only for Dulcet's manifest-rewrite contract. Build that Apple inline-validation loader in Phase 1.** | Revision 12's one-segment callback did not answer the general routing question. **OBSERVED 2026-08-21:** the strengthened `apple-ci` measurement requires both absolute, cross-origin source segments to be rewritten into Dulcet routes, requested through the delegate, and played beyond their boundary; its first-segment-only fault injection must fail. Unrewritten arbitrary topology remains deliberately unanswered and is not the product contract. The production loader must rewrite all fetchable manifest URIs; otherwise Apple HLS plans fall back to a progressive Path A container (§12.4; `docs/COMPATIBILITY.md`). |

---

## 27. Sources

- OpenSubsonic: overview, changes, `getOpenSubsonicExtensions`, `stream`, `scrobble`, the `transcoding`
  extension, `getTranscodeDecision`, `getTranscodeStream`, OpenAPI status —
  https://opensubsonic.netlify.app/docs/
- Subsonic API reference (versioning, authentication, `stream` error behavior) —
  https://www.subsonic.org/pages/api.jsp
- Navidrome Subsonic compatibility notes — https://www.navidrome.org/docs/developers/subsonic-api/
- Navidrome releases and `server/subsonic/opensubsonic.go` — https://github.com/navidrome/navidrome
- Kotlin/Native target support tiers — https://kotlinlang.org/docs/native-target-support.html
- Kotlin multiplatform compatibility guide (removed `ios()`/`tvos()` shortcuts) —
  https://kotlinlang.org/docs/multiplatform/multiplatform-compatibility-guide.html
  ⚠️ kotlinlang.org moved every `/docs/multiplatform-*.html` page under `/docs/multiplatform/`; the
  old flat URLs are now redirect stubs. Cite the current path.
- Kotlin Swift/ObjC interop and Swift Export status —
  https://kotlinlang.org/docs/native-objc-interop.html · https://kotlinlang.org/docs/native-swift-export.html
- Kotlin framework / XCFramework configuration — https://kotlinlang.org/docs/multiplatform/
- Ktor client engines (Darwin / NSURLSession) — https://ktor.io/docs/client-engines.html
- SQLDelight multiplatform SQLite drivers —
  https://sqldelight.github.io/sqldelight/latest/multiplatform_sqlite
- AndroidX Media3 `MediaSession` / `MediaController` / `Player` — https://github.com/androidx/media ·
  https://developer.android.com/reference/androidx/media3/common/Player
- Android TV design and Compose for TV — https://developer.android.com/design/ui/tv/ ·
  https://developer.android.com/training/tv/playback/compose
- Google Maven and Maven Central artifact indexes — dependency coordinates in §4.4 were resolved by
  direct probe with a positive control, not from documentation
- GitHub: protected branches and CODEOWNERS enforcement (§19.3) —
  https://docs.github.com/en/repositories/configuring-branches-and-merges-in-your-repository/managing-protected-branches/about-protected-branches
- `actions/runner-images` image inventories (the ffmpeg finding, §20.2.1) —
  https://github.com/actions/runner-images
- OpenSubsonic `getTranscodeStream` — the "standard HTTP error code" convention and the
  "should not try to reconstruct the `transcodeParams`" rule both live on **this** page, not on the
  extension overview or `getTranscodeDecision` —
  https://opensubsonic.netlify.app/docs/endpoints/gettranscodestream/
- Apple: `AVPlayer.timeControlStatus`, resource loading, provisioning profiles, TN3125 —
  https://developer.apple.com/documentation/
- GitHub Actions on public repositories (the §21.3 correction) —
  https://github.com/resources/insights/2026-pricing-changes-for-github-actions ·
  https://docs.github.com/en/billing/reference/actions-minute-multipliers
- Jellyfin: issue #1308, TypeScript SDK, `PlaybackInfoDto`, `UpdateUserItemDataDto`
- Chora — https://github.com/CraftWorksMC/Chora · Shelv — https://github.com/gatzenga/Shelv

---

## 28. Revision record

**Revision 13 (2026-08-21)** — adversarial review removed the overbroad resource-loader claim.

1. Revision 12 stopped after one segment callback and therefore did not establish complete media-
   segment routing. OQ-10 was reopened rather than treating that callback as proof.
2. The strengthened spike requires both fixture segments, playback beyond the first boundary, and an
   un-swallowed success status. A first-segment-only fault injection is required to fail.
3. Dulcet now owns routing deterministically by rewriting every manifest resource URI into its custom
   scheme. The measurement covers rewritten absolute, cross-origin media URIs; keys, redirects and
   unrewritten topology are not promoted to observations.
4. The spike moved into the single serial `apple-ci` job, which branch protection requires.

**Revision 12 (2026-08-20)** — the Apple resource-loader gap closed by measurement.

1. A checked-in macOS spike proved that progressive MP3 byte requests reach
   `AVAssetResourceLoaderDelegate` under the custom scheme.
2. The same spike proved that an HLS manifest and a relative media-segment request reach the
   delegate. The identical runtime-generated two-segment HLS fixture plays over localhost HTTP first,
   preventing a broken fixture from masquerading as a negative result.
3. OQ-10 now selects the inline HLS loader path; the progressive-only fallback remains documented as
   the rejected branch of the measured decision.

**Revision 11 (2026-08-20)** — Phase 0 completion evidence.

1. Recorded the green hosted `core-ci`, `parity-gate`, and Apple scaffold runs and closed the Phase 0
   status after the repository controls were applied through GitHub's API.
2. Replaced the unmeasured Apple timeout with 15 minutes, calibrated from a successful 187-second
   cold run on the pinned standard `macos-26` image. The calculation and durable run link live in
   `docs/TOOLCHAIN.md`.
3. Updated the repository locator after the public repository was created.

**Revision 10 (2026-08-20)** — final public-readiness scan.

1. Removed the remaining identifiers of unrelated applications from this spec and `CLAUDE.md`. The
   supporting observation remains stated at the required granularity: the LLC already uses this
   namespace and has an established Mac App Store distribution path. No Dulcet decision or
   implementation contract changed.
2. Updated the status and working agreement to reflect the Phase 0 scaffold. This is a status
   correction only; §25 remains the authority for completion.

**Revision 9 (2026-08-18)** — the signing path moved from ASSUMED to OBSERVED, and the last Phase-0
unknown was answered by API rather than by asking a person.

1. **macOS submission on team 3LTL47SJ8C is proven, not theoretical.** Read-only App Store Connect API
   calls confirm an `ACCOUNT_HOLDER` (also `ADMIN`) on the team, **two apps already shipping `MAC_OS`
   `appStoreVersions`**, a provisioned `MAC_INSTALLER_DISTRIBUTION` certificate and two `DISTRIBUTION`
   certificates. **The certificate pair a Mac App Store submission actually requires already exists**,
   and the macOS record path has been walked twice on this exact team. Phase 2's dry run no longer
   exists to discover whether macOS submission is possible.
2. **The "is the maintainer the Account Holder?" open item is answered and removed.** It was a Phase-0
   task requiring a human to look at a Membership page; the API answered it. Nothing gates certificate
   creation on a missing role.
3. **`DEVELOPER_ID_APPLICATION` is confirmed absent, and that is now explicitly recorded as blocking
   nothing.** Developer ID exists only for notarized outside-the-store distribution, which §22 rules
   out. Stated plainly so a later session does not read the absence as a blocker and spend a
   certificate slot on a channel this project does not use. Revision 7's framing ("a ten-minute action
   for the Account Holder") was true and beside the point.
4. **A premise-audit claim is corrected by observation.** The audit stated distribution certificates
   are *"one of each type per team"*; **two `DISTRIBUTION` certificates exist**, against a typical quota
   of three. The operational advice — never revoke or regenerate them, other projects depend on them —
   **stands and matters more**, since the headroom is one slot rather than none.
5. **Two limits recorded rather than smoothed over.** Whether the API key is the Account Holder's own or
   a separately-provisioned Admin team key is **UNVERIFIABLE** — `/v1/apiKeys` returns 404 `PATH_ERROR`
   and Apple offers no introspection for a team key's bound role; what is observed is that it made four
   Admin-tier calls, so its privilege is **at least Admin-equivalent**. And **write/create permission
   was never tested — the whole check was read-only.** That is now **the only real unknown left in the
   signing path**, so the Phase-2 dry run's *first* act is to exercise CREATE (register the bundle id,
   create the App ID, generate a profile) before archiving anything. Cheap to find there, expensive at
   submission.
6. **Both Dulcet bundle identifiers are confirmed unregistered** among the team's 18, so both are free
   and must be created (§23.2).

**Revision 8 (2026-08-18)** — two loose ends closed; no design change.

1. **OQ-3 re-confirmed and closed.** Revision 7 flagged it because the floor had been accepted on a
   justification that was half wrong (`NavigationStack`/`NavigationSplitView` are a full major version
   below it; `@Observable` alone carries it). The maintainer re-examined it on the corrected facts and
   **the floor stands at macOS 14 / iOS 17 / tvOS 17**, with the 16/13/16 alternative explicitly
   considered and rejected — per-property invalidation matters on the largest scrolling surface in the
   app, and iOS 17 is three generations old so the reach cost is negligible. **The reasoning is now
   recorded in both §4.1 and §26 with the original error named**, so a later session neither re-derives
   the floor from the false premise nor reopens it *because* the premise was false. **Wrong reason,
   right conclusion — and now checked against the right reason.**
2. **`GOAL.md` removed from the repository tree**, not `.gitignore`d. It held a routable LAN address,
   external-volume paths, lease tooling and private routing detail, in a tree that goes public at Phase
   0. **Relocation rather than ignoring, on the same principle as the two separate App Store Connect
   records (§22.5): enforceable by inspection rather than by vigilance** — a `.gitignore` entry is one
   `git add -f` away from failing, while a file outside the tree cannot be committed at all. Verified
   after the move: the tree holds only `CORPUS.md`, `CLAUDE.md`, the premise audit and this spec; a scan
   for LAN addresses, external-volume paths, lease tooling, model and delegate names and identity tokens
   returns zero; and no `.git` exists, so nothing was ever committed. The premise audit's header, which
   named the auditing model, was likewise generalised — **internal tooling detail is private context and
   does not belong in a public artifact either (§28 revision 5).**

**Revision 7 (2026-08-18)** — a premise audit (`docs/PREMISE-AUDIT-2026-08-18.md`, run against
revision 2 with primary sources only) was folded in. **Every claim below was re-verified against the
current text and against live sources before being applied** — the audit predates revisions 3–6, so
several of its findings were already fixed and are recorded as such rather than "fixed" twice.

**Already resolved before the audit was read (recorded, not re-applied):**

- **C1 — the 10x macOS multiplier scoped to private repos, and OQ-1.** Fixed in revision 3 from the
  same two primary sources the audit cites. OQ-1 is closed (§21.3).
- **C9 — §20.3 and §21.1 disagreeing about where the Apple conformance leg runs.** Dissolved by the
  same change; both now say `macos-latest`.
- **C11 — the `${DOMAIN}` contract being broken inside the spec.** Fixed in revisions 3 and 4; the
  constants are defined once and `${BUNDLE_PREFIX}` no longer derives from the domain at all. The
  audit's RDAP probe independently confirms nothing is registered, which matches OQ-7.

**Correctness and shippability blockers, now fixed:**

1. **C4 — `androidx.tv:tv-material3` does not exist.** The Android TV module was written against an
   unresolvable Maven coordinate in three documents; it would have failed on the first Gradle sync.
   **Re-verified independently with a positive control before writing**: `tv-material3` → HTTP 404,
   `tv-material` → 1.1.0, and the control `androidx.compose.material3:material3` → 200, ruling out a
   blind instrument. The **artifact** is `androidx.tv:tv-material`; `androidx.tv.material3` is only the
   **package**. Also recorded: do not mix `tv-material` with `material3` (each carries its own
   `MaterialTheme`), TV Lazy Layouts are deprecated out of `tv-foundation`, and Google's guide page
   lags the artifact — so **pin from the index, never from a guide**. Every other coordinate in the
   document was probed the same way (§4.4); all resolve.
2. **A2 — App Sandbox was never named, and it is a hard Mac App Store requirement.** New §23.3, from
   Guideline 2.4.5(i) verbatim. Reconciled with the storage design, and **the reconciliation is good
   news: nothing in §14.5–§14.6 is impossible under the sandbox**, because everything Dulcet writes
   lives in its own container, which needs no user consent. Recorded explicitly: security-scoped
   bookmarks are **not** needed for v1, and the moment "choose my download folder" is proposed it
   becomes a real feature with a real cost. Also 2.4.5(vii): **no self-updater on macOS, ever.**
3. **A3 — Guideline 4.2.7, not 4.2, is the sharp review hazard.** New §23.4 with 4.2.7 and 4.2.3(i)
   quoted verbatim. Our position — that 4.2.7 does not apply because Dulcet is not a remote desktop app
   and mirrors no host screen — is now written down as a pre-drafted argument, because clause (a)'s
   LAN-only restriction would be fatal if a reviewer misclassified us. `docs/APP-REVIEW-NOTES.md`
   becomes a **Phase-0** artifact rather than a Phase-6 scramble.
4. **C3 — transcode error handling had no vocabulary for what the reference server actually does.**
   `getTranscodeStream` uses **standard HTTP error codes**, unlike legacy `stream`; the reference server
   returns **HTTP 429 with `Retry-After` and an envelope carrying the generic code 0** when its
   transcode concurrency cap is hit. Fixed in four places: §12.4 now distinguishes **three** error
   shapes (envelope-at-200, HTTP-error-no-envelope, and **envelope-at-non-200**, which revision 2 did
   not admit); §18.12 gains **`Server.Busy(retryAfter)`** derived from the status rather than the
   uninformative envelope code; §12.2's retry policy now **honours `Retry-After` instead of substituting
   its own schedule**; and §12.8 gains a **learned per-server transcode budget** — because unconditional
   preload means two concurrent transcodes per client and is self-defeating against a per-user cap of 1.
   Strict contention priority: **playback > preload > downloads** (§14.5).

**Consequence of the hosted-runner move, not a pre-existing defect (§20.2.1):**

5. **A1 — ffmpeg is what the transcode tests measure, and it was pinned nowhere on the Darwin leg.**
   Moving Apple CI to hosted runners was right on cost and security; this followed from it. A Navidrome
   with no transcoder does not error, it **falls back to direct play** — so every transcode test would
   pass while measuring nothing. A powerless test reported green, which is worse than no test.
   **Chosen fix: a combination, because neither obvious option is sufficient alone.** Byte-level
   transcode assertions run on **Linux only** (two legs with two different ffmpeg builds give two
   answers, neither authoritative), while the Darwin leg **keeps** the transcode-*adjacent* tests that
   are genuinely Darwin-specific — ranged requests through `NSURLSession`, the resource-loader path
   against a transcoded stream, server-offset seeking — and therefore **also installs a pinned ffmpeg**.
   Generalised into a standing rule (§20.2.2): **every test depending on a server-side capability must
   assert that capability first and FAIL, never skip, when it is absent** — a skip reads as green.

**Also applied:** C2 (identifiers freeze on first **build upload**, not record creation — which
restores a real escape hatch), C5 (the `ios()`/`tvos()` shortcuts were **removed**, not "planned for
removal"; the spec had it as a future event while `CLAUDE.md` had it right), C6 (the reference server
**does** accept form-POSTed credentials on media endpoints — the security section had baked in a false
universal; the download caveat survives for a *platform* reason), C7 (below), C8 (below), C10 (the demo
account **must not expire**, so OQ-4's server is permanent infrastructure with an ongoing cost),
A4 (`onConnectAsync`, plus `media3-ui-compose` state holders worth using), A5 (the Apple deployment
override is a **raw compiler flag**, not a DSL property, and `embedAndSignAppleFrameworkForXcode` only
registers if `binaries.framework` is declared), A6 (Tier 1 vs Tier 2 quoted exactly, plus the asymmetry
that **`tvosArm64` is not run-tested at all** — only the simulator is), A7 (§4.4 seeded with probed
versions), A8 (Navidrome search is **autocomplete-only, not Lucene**, so the local ranker's contract is
"same-shaped, not same-result"), A10 (`estimateContentLength` — the documented lever that makes
transcoded streams seekable and transcoded downloads length-verifiable), A11 (`.view` is OBSERVED for
the reference server and ASSUMED elsewhere → QUIRK-01), A12 (the seam method is renamed
**`recordPlaybackEvent`**, because `reportPlayback` is literally the name of the endpoint §15.4
forbids and §19.4 hands delegates one-line briefs), A13/A14 (the signing risk is **materially lower**
than stated — the distribution and Mac-installer certificates already exist, and a free app with no IAP
needs only the Program License Agreement; and "no Developer ID" is a **current state, not a
constraint**. **Partly SUPERSEDED by revision 9:** the certificate inventory is now observed
rather than inferred, and the operative point is that a Developer ID certificate is **not needed at
all** for this project’s distribution channels — do not create one),
A15 (the app name is clear, and same-name-similarity rejection is folklore), A16 (the Run Script
ordering is a configuration Phase 0 must create, not a property of the tooling).

**C7 — the regression gate was unenforceable and has been redesigned.** GitHub has **no per-label
ACL** — *"Anyone with triage access… can apply and dismiss labels"* — and `CODEOWNERS` confers no
label permission, so revision 2's "protected label appliable only by a CODEOWNER" was the same class of
weakness it replaced. **The gate now has no escape hatch at all**; an intentional regression is
declared in an `accepted_regressions:` block **inside `FEATURES.yml`**, that file is CODEOWNER-owned,
and branch protection's "Require review from Code Owners" makes approval mandatory — verbatim, *"any
pull request that affects code with a code owner must be approved by that code owner before the pull
request can be merged."* The authorization is a **review**, which GitHub gates on, not a label, which
it does not. Plus: `parity-gate` is a required status check that must never exit `neutral`, and **"Do
not allow bypassing the above settings" must be ON**, since branch rules do not apply to admins by
default.

**C8 — the OS floors were justified on a half-wrong reason.** `NavigationStack` and
`NavigationSplitView` are iOS 16 / macOS 13 / tvOS 16, a full major version **below** the floor;
**`@Observable` alone sets it**. The floor **stands** at macOS 14 / iOS 17 / tvOS 17 on that basis —
per-property invalidation is a real difference on screens scrolling tens of thousands of rows, not an
ergonomic one — but §26's OQ-3 is now **flagged for re-confirmation**, because the maintainer accepted
a reach trade-off partly on a claim that was not true. Lowering to 13/16/16 remains available at the
cost of the coarser `ObservableObject` model. **Product call, flagged rather than decided unilaterally.**

**Where we disagree with the audit, with evidence:**

- **A1's claim that the container's ffmpeg "floats with the Alpine base and is not part of the pin" is
  true for a *tag* pin and false for a *digest* pin** — and §20.2 already specified a digest, which pins
  the exact filesystem and therefore the exact ffmpeg build. The Linux leg was already pinned; only
  Darwin was not. The remediation is smaller than the audit implies, and the distinction is recorded so
  nobody "simplifies" the digest back to a tag and silently unpins the transcoder.
- **A1's supporting probe is weaker than presented.** ffmpeg's absence was inferred from zero mentions
  in the hosted macOS image readme — but **the hosted Ubuntu readme also reports zero**, so that
  instrument cannot distinguish "absent" from "unlisted". The readme *is* a real inventory (it lists
  tools as small as `zstd`), so absence on both is likely; but the defensible claim is *"not part of the
  documented toolset"*, not *"provably absent"*. **This strengthens rather than weakens the fix**: the
  remedy is a runtime assertion, not a belief about an image.
- **§2.3 was more cautious than it needed to be**, and the audit is right: reading
  `server/subsonic/opensubsonic.go` at tag `v0.63.2` confirms every row exactly. The table is promoted
  to **OBSERVED at v0.63.2** and **CONF-02 is demoted from discovery to regression detection**.
- **The audit's own citation for "clients should not reconstruct `transcodeParams`" points at the wrong
  page** — that sentence lives on the `getTranscodeStream` page, not on the extension overview or
  `getTranscodeDecision`. §27 now cites it correctly.

**Left open deliberately:** the audit could not verify `AVAssetResourceLoaderDelegate` custom-scheme
behaviour or its HLS segment handling, because Apple does not document either. Rather than assert it,
**OQ-10 is now gated on a one-day measurement spike that runs before any loader code** (§12.4), with a
defined fallback if HLS segments do not route through the delegate.

**Revision 6 (2026-08-18)** — decisions recorded and release channels added.

1. **All ten open questions answered and §26 converted from a queue into a decision record.** OQ-1 was
   closed by correction; OQ-2 through OQ-10 were answered by the maintainer on 2026-08-18. Re-opening
   one is now a change proposal that must argue against the recorded rationale, not a blank to fill.
   The document's status is **ready for implementation**.
2. **New §22, DEV and PROD release channels.** DEV ships automatically on every merge to `main` to
   TestFlight *internal* testers — no Beta App Review, minutes to arrive, expected to break. PROD ships
   from a hand-cut `vX.Y.Z` tag to an *external* group through Beta App Review, gated on green CI, a
   passing conformance suite and no undeclared `FEATURES.yml` regression. The external-review latency
   is recorded as a deliberate feature.
3. **Separate bundle identifiers** so both install side by side — `com.legitimateapps.dulcet` and
   `com.legitimateapps.dulcet.dev`, with distinct icons and display names. Recorded normatively: **the
   `.dev` record must never be submitted for App Store release, for the life of the project.**
4. **PROD ships no preconfigured server**, and the build configuration must make that structurally
   impossible rather than a thing someone remembers. A hardcoded internal address in a published binary
   is both a disclosure and a defect — it leaks a private address to every downloader and is wrong for
   every user who is not the person who hardcoded it. This is the §28-revision-5 failure mode in
   executable form: private context escaping into a public artifact, this time as a shipped string
   rather than a sentence.
5. **§22.4 reconciles the dogfooding target with OQ-8**, which reads as a contradiction and is not:
   automated runs target the local disposable instance, always; a person manually using a DEV build
   against their own real library is expected and is the reason DEV exists. **The line is automation,
   not the server**, and automated writes to a personal instance are forbidden in every case.
6. **§22 renumbering:** the former §22–§27 became §23–§28, and all cross-references in all three files
   were updated mechanically.

**Revision 5 (2026-08-18)** — a disclosure fix. **These files ship in a public repository, and
revision 4 had written private context into them.**

1. **An unrelated product identity was named** in five places — in the header, in §24.1, in the §28
   revision-4 record, and in both `CORPUS.md` and `CLAUDE.md` — as a warning not to reuse its bundle
   namespace. The warning was correct; naming the thing it warned about was not. Published, it would
   have asserted a relationship between this project and an unrelated one, which is exactly what
   deliberate separation exists to prevent. One of the five was worse than the rest because its
   phrasing also described a local environment.
   **The fix is to state the rule positively and name nothing:** *the bundle identifier prefix is
   `com.legitimateapps.dulcet`; it is immutable once an App Store Connect record exists; never publish
   this app under any other namespace, and never reuse a namespace from an unrelated project that
   happens to be present in a local build environment.* Same protective force, zero disclosure.
2. **Other private context was scrubbed** in the same pass: how the namespace claim was sourced (a
   local inventory rather than public evidence); an attribution of the target library size to a
   specific person's collection; references to private infrastructure (§23.5, OQ-4, OQ-8, now written
   as "any private or personal server instance"); a workstation-hygiene section built on
   maintainer-private tooling and machine measurements (§21.4, reduced to the toolchain facts a
   contributor actually needs); named private tooling handles in the author line, the revision records
   and §25; and, throughout §21, allusions to an internal cross-project cost policy — the CI decision
   now rests on the two published GitHub sources alone, which is stronger anyway.

**The general rule, so this is not repeated:**

> **Maintainer-private context — global agent instructions, internal doctrine, machine setup, other
> projects, other identities — must never be quoted, paraphrased, or alluded to in any file destined
> for a public repository. Those rules are to be FOLLOWED in public artifacts, never DESCRIBED in
> them.** A public document describes *this project only*. If a constraint matters, state it as a
> positive requirement of this project, with a justification that stands on public evidence.

**The tell, because the pattern is structural rather than careless.** This was the second identity
defect in these files; the first (a legal name, a personal username and a prior handle, written into a
"never publish these" list) was caught during self-review. Both arose the same way: **the author holds
private context while writing public files, and the private context resurfaces disguised as a
helpful warning.** A warning is the most persuasive possible carrier for a leak, because suppressing it
feels like negligence.

The check that catches it: **"am I explaining WHY using knowledge a reader of this repository does not
have?"** If the justification for a rule draws on anything outside this project, the rule is fine and
the justification must be rewritten. Corollary: **never write a forbidden value down in order to
forbid it** — a prohibition that names its target publishes its target.

**Revision 4 (2026-08-18)** — identifier decoupling. Revision 3 defined `${BUNDLE_PREFIX}` as the
reverse-DNS of `${DOMAIN}`, and correctly noticed that a bundle identifier is immutable once an App
Store Connect record exists — but drew the wrong conclusion from it, promoting the domain choice into
a Phase-0 blocker. **The right fix is to decouple the permanent identifier from the marketing domain,
not to gate the domain earlier.**

1. **`${BUNDLE_PREFIX}` is now `com.legitimateapps.dulcet`, DECIDED, and independent of `${DOMAIN}`.**
   Justification is recorded inline in the header: `legitimateapps.com` is already registered and owned
   by the LLC; four shipped apps already use the namespace (read from the provisioning profiles on the
   build machine); it stays valid whatever the marketing domain turns out to be; and it keeps the app
   inside the Legitimate LLC identity that signs it.
2. **The two constants no longer have a derivation relationship.** Anything needing a stable,
   non-churning identifier derives from `${BUNDLE_PREFIX}` — the Keychain service id (§13.1) and the
   Android `applicationId` (§23.2). `${DOMAIN}` reaches only support/privacy URLs, the review demo
   server and marketing.
3. **OQ-7 demoted out of Phase 0** and its exit criterion removed from §25. It gates nothing in Phases
   0–2, including the signing dry run. **Do not re-promote it**: the reason it was ever a blocker —
   the permanent identifier depending on it — no longer holds.
4. **OQ-7 restated as "chosen, pending purchase":** `getdulcet.com`, $11.08 register and renew,
   verified available. Not bought and nothing spent, because Porkbun's web login needs a passkey an
   agent cannot use and its API has no add-funds endpoint.
5. **Namespace discipline recorded** (§24.1 and the header), stated as a positive constraint: publish
   only under `com.legitimateapps.dulcet`, and never reuse a namespace belonging to an unrelated
   project that happens to be present in a local build environment.

**Revision 3 (2026-08-18)** — a factual correction, not a design change, plus a naming cleanup:

1. **CI moved to GitHub-hosted runners and the self-hosted runner was removed from the design.**
   Revision 2 applied the operator's standing "never hosted `macos-*`, 10x multiplier" policy. That
   policy is scoped to **private** repositories; Dulcet is public, and GitHub states that "Standard
   GitHub-hosted or self-hosted runner usage on public repositories will remain free," with the only
   carve-out being that "the larger runners are not free for public repositories." Both quotes verified
   against the primary sources on 2026-08-18 and cited in §21.1. Hosted CI is therefore free *and*
   safer here, and the self-hosted design was buying nothing.
2. **OQ-1 is closed — by correction, not by an operator decision.** Its two options existed only to
   work around a cost premise that was false for this repo. With no machine of the operator's in CI,
   the fork-PR code-execution risk that made a job-level `if:` guard inadequate does not arise at all.
   The separate-private-CI-repo option is withdrawn. §21.3 records the reasoning and both URLs so a
   later session does not reopen it.
3. **Two normative caveats added** (§21.1): only *standard* runners are free on public repos, so no
   workflow may request a larger-runner label and a CI lint asserts it; and hosted macOS concurrency is
   capped below Linux, so the Apple matrix is deliberately narrow — a wide matrix queues rather than
   fans out. `concurrency: cancel-in-progress` and `timeout-minutes` are kept, now justified as queue
   hygiene rather than as protection for a long-lived machine.
4. **The repository is public from the start.** Phase 0 creates a public scaffold; Phase 3 no longer
   gates anything on going public. §21.4 was reduced to the toolchain facts a contributor actually
   needs.
5. **`dulcet.fm` is dropped** ($87.85/yr to register and renew; nothing was purchased). Every product
   URL is now `https://${DOMAIN}/...` and every identifier `${BUNDLE_PREFIX}.*`, with both constants
   defined **once** in the header block. OQ-7 is reframed from "is dulcet.fm registered?" to "which
   domain?", and promoted to a Phase-0 decision because **a bundle identifier cannot be changed after
   an App Store Connect record exists**. **The promotion is SUPERSEDED by revision 4 item 3** — the
   immutability observation was right, the conclusion was not. Read this as history; the header
   constants table and OQ-7 are current.

**Revision 2 (2026-08-18)** — after an independent adversarial review pass. What changed, and why it
matters, so nobody re-derives revision 1's mistakes:

1. **Transcoding was specified as the wrong protocol.** Revision 1 gated `stream?format=&maxBitRate=`
   on the `transcoding` extension. Verified independently: the extension introduces `getTranscodeDecision`
   (POST, `ClientInfo` body) and `getTranscodeStream` with an **opaque** `transcodeParams` the client
   must not reconstruct; the classic parameters are a separate legacy path. Now two explicit paths
   (§12.5) plus a device capability profile (§12.6) that revision 1 omitted entirely.
2. **Preflight validation was a TOCTOU fiction.** A probe cannot validate the request the engine will
   later make. Inline validation through a resource loader / data source is now the correctness
   mechanism on both platforms; preflight is advisory only (§12.4). The byte-signature rules were also
   wrong (`ftyp` offset, `RIFF`/`WAVE`, MP3 masking, ID3 before FLAC) and are now a normative table.
3. **One `planId` was doing three jobs.** Split into `QueueEntryId`, `PlaybackSessionId` and
   `AttemptId`, with a normative transition table and an event-acceptance rule that no longer drops the
   events needed to finalize an outgoing session (§12.1).
4. **`StartedAudible` was not observable.** Neither `timeControlStatus` nor Media3 `isPlaying` proves
   acoustic output. Renamed `PlaybackProgressBegan` and defined as advancing media position under an
   unsuppressed playing state (§12.2). The event list is now a required minimum, with command outcomes
   and correlation ids, rather than a claimed-complete set.
5. **The sync model could not meet its own guarantee.** Offset paging plus dedupe cannot detect
   omissions, and in-place updates make a partial pass visible. Replaced with row versioning, reads
   pinned to a committed generation, one atomic commit, and a stability witness with bounded retries
   (§16.3–§16.4). Two-pass tombstoning is gone; it was compensating for a missing commit protocol.
6. **The scrobble accumulator was not deterministic** and the outbox claimed an idempotency it cannot
   have. Now a specified reducer with test vectors, and honest at-least-once delivery (§15.2–§15.3).
7. **The Apple facade described artifacts ObjC interop does not produce.** No "flat structs" across the
   boundary; a concrete completion-handler + `OperationHandle` pattern with specified cancellation,
   threading and exception rules, and Swift structs built in `DulcetKit` (§7).
8. **Fixture replay was called transport parity.** It is not. Three honestly-named layers, with the
   real conformance suite now also running on `macosArm64` against a natively-run Navidrome binary —
   which closes the Darwin gap without Docker on the Mac (§20.3).
9. **`FEATURES.yml` evidence was circular** (a commit naming the run that verifies it) and its
   regression approval was self-service (a line in the PR body). Now stable `{workflow, job, test}`
   evidence plus a CODEOWNER-applied protected label (§19).
10. **The public-repo runner guard was unsafe.** A fork PR can modify the workflow that contains the
    guard, so revision 2 replaced it with two options and kept the repo private meanwhile.
    **SUPERSEDED by revision 3 item 2** — the whole question dissolved once the cost premise was
    corrected. Read this line as history; §21.3 is current.
11. **Security wording corrected** — salted MD5 is not transport security and not replay resistance
    (§13.2); redirect credential policy added (§13.3); the 32-hex log scan replaced with canaries
    because Navidrome ids are themselves hash-like (§13.4); a TLS trust policy added (§13.5).
12. **Cut from v1:** server-side queue sync (not built, not "default off"), `sonicSimilarity` UI, the
    `FEATURES.yml` source-annotation scheme, and CONF-21 from Phase 1. **Added because a client of this
    category always needs them:** TLS trust policy, device capability profile, local-file playback
    plans, audio-session and focus policy, remote-command model, account removal, corruption recovery,
    storage eviction priority, resume position, a two-clock time model, accessibility and localization,
    state restoration, and local observability.
13. **Jellyfin paper-validation demoted** from a binding gate to six seam invariants plus a non-binding
    architecture note, so the seam stops generating speculative abstractions (§9.5).
