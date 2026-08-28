# CORPUS · Dulcet

**This file is the centre. Read it first, every session, including after a compaction.**
Written to be read cold by an agent with no conversation history.

**Stable on purpose.** No dates, no status, no metrics, no logs — those live in the design spec
(`docs/superpowers/specs/2026-08-18-dulcet-design.md`), in `FEATURES.yml`, and in the project memory
dir. If you are about to add a date or a number here, it belongs somewhere else.

**Read order, never varies:** `CORPUS.md` -> `CLAUDE.md` -> the design-spec section you are about to
touch -> `FEATURES.yml`. Everything else is on demand.

---

## 1 · What we are building

**Dulcet is an open-source multiplatform client for OpenSubsonic-compatible music servers, with
Navidrome as the reference and primary tested server. The baseline compatibility contract is Subsonic
REST API 1.16.1; OpenSubsonic extensions are negotiated at runtime.**

Use that sentence verbatim in the README, the store listing, and every delegate brief. Short form
where it does not fit: *a native multiplatform OpenSubsonic music client, tested primarily against
Navidrome.*

Surfaces: iOS, iPadOS, macOS, tvOS (SwiftUI) and Android + Android TV (Compose). macOS ships first.

## 2 · The thesis

A Kotlin Multiplatform **fat core** plus **genuinely native UI shells**. The core owns everything that
is a decision; the platform owns everything that is an OS service. One protocol implementation, one
cache, one sync engine, one scrobble policy — six real apps on top of it.

The bet: the expensive, correctness-critical 80% of a music client is platform-independent (protocol,
capability negotiation, cache, sync, queue, download and playback *policy*, scrobble rules, error
model), and the remaining 20% is exactly the part that must not be shared, because a D-pad, a trackpad
and a thumb are not the same input.

## 3 · Terminology, stated correctly (people get this wrong constantly)

1. **Subsonic** — both the original server product and the common name for its REST API.
2. **OpenSubsonic** — an openly and collaboratively maintained, backward-compatible **evolution** of
   that API, developed by participating server and client projects. Not a Navidrome project, not a
   fork, not vendor-controlled. **Not a "strict superset"**: only a small core is mandatory, most
   additions are optional, and extension versions are independent capability axes.
3. **Navidrome** — one server implementing the Subsonic baseline plus a growing set of OpenSubsonic
   extensions.
4. **`v=1.16.1`** — the Subsonic REST **schema version the client implements**. Not the Navidrome
   version, not an OpenSubsonic version, not a feature bitset.
5. **Dulcet is not a "Navidrome client."** We use `/rest` only. Touching Navidrome's private `/api`
   would silently make that label true.

## 4 · The lines we never cross

Binding. A violation is a stop-work, not a style note.

1. **`/rest` only.** Never the server's private API, never its web UI, never an undocumented behavior
   that is not registered as a named quirk with a conformance test pinning it.
2. **HTTP 200 is not proof of binary success.** `/rest/stream` and `/rest/getCoverArt` return binary
   on success and may return an XML or JSON error envelope on failure, and real servers and proxies
   mislabel content types. Validation is **unconditional and ordered**: envelope detection first
   (after skipping whitespace and a BOM), then the endpoint's normative signature table with
   per-format offsets and masks. Envelope detection requires a recognizable Subsonic root; an
   arbitrary binary byte equal to `{` or `<` is not by itself a malformed envelope. "Not an
   envelope" is not success, and there is no "only sniff when the content type looks wrong" path.
3. **Validate the bytes the engine actually plays, not a probe.** A preflight is advisory only; a
   previous request's success says nothing about the request the player makes next. Apple uses an
   `AVAssetResourceLoaderDelegate`, Android a custom `DataSource.Factory`.
4. **Never rely on `stream` to record a play.** Play state comes from `scrobble` with
   `submission=true`, past a threshold, measured from *progressing media time* with buffering, pause
   and forward discontinuities excluded. Resolving a URL is not a play.
5. **Credentials ride in the query string, so the query string is radioactive.** Fresh CSPRNG salt (16
   bytes, 32 hex chars) per request; password only in Keychain/Keystore; the whole query string
   redacted before anything reaches a log, an error or a diagnostic. `DomainError` has no field capable
   of holding a raw URL. Credentials are **stripped** on any cross-origin redirect, and an
   HTTPS-to-HTTP downgrade is never followed.
6. **No telemetry, no analytics, no third-party crash reporter.** The likeliest way a signed stream URL
   leaves a device is inside a crash report.
7. **Capability gates are conjunctions, never a single check:** protocol AND extension AND user
   permission AND device capability AND policy AND no known quirk. An extension list alone never
   decides whether a button exists — and one failed request never revokes an advertised capability.
8. **Never expose SQLDelight entities, raw `Flow` graphs, generic repositories, or arbitrary Kotlin
   collections across the Swift boundary.** The interop path is Objective-C, so exported Kotlin arrives
   in Swift as *classes*: value types are hand-written Swift structs in `DulcetKit` that copy at the
   boundary. Async is completion-handler plus a synchronously-returned `OperationHandle`, callbacks on
   the main thread, and no Kotlin exception ever crosses.
9. **IDs are opaque strings**, never parsed or ranged over, always scoped as
   `ProviderItemId(providerInstanceId, rawId)`.
10. **Three playback identities, never one:** `QueueEntryId`, `PlaybackSessionId`, `AttemptId`. Collapse
    them and the scrobble accounting races.
11. **Reads are pinned to a committed sync generation.** A partially completed scan is never visible.
12. **Identity separation.** Repo under `legitimate-apps` (`github-legit`), commits authored as
    `legitimate-apps` **per-command, never global config**, Apple team 3LTL47SJ8C (Legitimate LLC). The
    maintainer's legal name, home address and system username appear nowhere — not in code, commits,
    listings, plists, DNS or comments. **The literal values are not written down here**, because a
    prohibition that names its target publishes its target.
13. **Everything in this repository is public, so it describes THIS project only.** Private context —
    internal doctrine, machine setup, tooling, other projects, other identities — is **followed**, never
    **described**. Before writing a justification, ask: *am I explaining WHY using knowledge a reader of
    this repository does not have?* If so, the rule stays and the justification gets rewritten to stand
    on public evidence. This has already gone wrong twice; see spec §28, revision 5.
14. **DEV and PROD are separate channels with separate bundle identifiers.** DEV
    (`com.legitimateapps.dulcet.dev`, "Dulcet DEV") ships automatically on every merge to `main` to
    TestFlight *internal* testers — no Beta App Review, expected to break. PROD
    (`com.legitimateapps.dulcet`, "Dulcet") ships from a hand-cut `vX.Y.Z` tag to an *external* group
    through Beta App Review, only when CI is green, conformance passes, and `FEATURES.yml` shows no
    undeclared regression. **The `.dev` record must NEVER be submitted for App Store release, ever.**
    **PROD ships no preconfigured server** — a hardcoded internal address in a published binary leaks a
    private address to every downloader and is wrong for everyone else; the build config must make it
    structurally impossible, not a thing someone remembers. And the dogfooding line is **automation,
    not the server**: automated runs always target the local disposable instance, while a person
    manually using a DEV build against their own real library is expected and is why DEV exists.
    Automated writes to a personal instance are forbidden in every case. (spec §22)
15. **The repo is public, so CI is entirely GitHub-hosted** — Apple on standard `macos-latest`,
    everything else on `ubuntu-latest`. Standard runners are free on public repositories, so the
    global "never hosted macOS, 10x multiplier" policy does not apply here; it is scoped to private
    repos. **No self-hosted runner exists in this project.** Two things still bind: never request a
    *larger* runner label (those are billed even on public repos), and keep the Apple matrix narrow
    because hosted macOS concurrency is capped and a wide matrix queues rather than fans out. Every
    workflow keeps `concurrency: cancel-in-progress` and `timeout-minutes`, now for queue hygiene.
16. **A `shipped` cell in `FEATURES.yml` requires evidence** — one workflow, job and test identity per
    declared CONF id, each in a required check and at the granularity of the claim. "It builds" is
    not evidence, and neither is a delegate's report.
17. **A test that cannot fail is worse than no test.** A Navidrome without ffmpeg does not error — it
    silently direct-plays, so every transcode test would pass while measuring nothing. Every
    conformance test that depends on a server-side capability asserts that capability first and
    **fails, never skips**, when it is absent. Skips read as green. And the transcoder is pinned
    exactly wherever it runs, because it is part of what is under test.
18. **Resolve every dependency coordinate against a live index before writing it into a build file.**
    `androidx.tv:tv-material3` was asserted confidently in three documents and does not exist; the
    artifact is `androidx.tv:tv-material`. A package name is not a coordinate.
19. **No bandaids.** A skipped test, a suppressed warning, a `try/catch` around a symptom, a
    trust-all-certificates toggle, or a performance cap in place of a fix is a stop-work.

## 5 · Settled · do not relitigate

- **Kotlin Multiplatform fat core + native shells.** Not Compose Multiplatform everywhere, not six
  independent apps. The platform layer owns far more than UI and audio — secure storage, filesystem,
  background execution, system media integration, reachability, lifecycle.
- **Objective-C interop for the Swift boundary.** Swift Export is Alpha — a Phase-5 experiment at the
  earliest, never on the critical path.
- **Apple Silicon only.** `macosX64` and `tvosX64` are deprecated in Kotlin/Native. Targets are
  enumerated explicitly — the `ios()`/`tvos()` shortcuts are removed in Kotlin 2.2.0 and must never
  appear.
- **tvOS is supported, not "first-class."** `tvosArm64`/`tvosSimulatorArm64` are Kotlin/Native Tier 2:
  compile-tested, with weaker compatibility and runtime guarantees than Tier 1. Say that, not more.
- **The provider seam exists at v1 with exactly one implementation (Subsonic)**, shaped as `browse` /
  `getItem` / `search` / `getPlaylist` / `mutatePlaylist` / `resolvePlayback` / `reportPlayback` /
  `getArtwork` / `getLyrics` / `getUserCapabilities` / `libraryChangeSource` / `ProviderCapabilities`.
  **`streamURL` and `markPlayed` are banned traps.** The seam is held by six invariants, not by a
  Jellyfin compatibility gate.
- **No Jellyfin adapter.** The Jellyfin mapping is a non-binding architecture note.
- **`PlaybackEngine` is bidirectional**: commands with explicit outcomes and correlation ids, plus a
  required event set. Without events the core cannot tell "URL resolved" from "playback progressed."
  The start-of-play event is `PlaybackProgressBegan` — *media position advancing under an unsuppressed
  playing state*. Neither `timeControlStatus` nor Media3 `isPlaying` proves acoustic output, so nothing
  is named "audible."
- **The `transcoding` extension is `getTranscodeDecision` (POST, `ClientInfo`) + `getTranscodeStream`
  with an opaque `transcodeParams` the client must never reconstruct.** Classic
  `stream?format=&maxBitRate=` is the separate *legacy* path. `transcodeOffset` belongs to the legacy
  path. Two paths, a device capability profile behind both.
- **`playbackReport` is not called in v1.** `scrobble` is the single source of truth. Adopting it
  requires a conformance test proving it does not also increment play count.
- **Scrobble delivery is at-least-once.** A local uniqueness key stops duplicate rows, not duplicate
  server increments. We prefer a possible duplicate play to a lost one, and we say so.
- **Server-side queue sync, `sonicSimilarity` UI, and server bookmarks are not built in v1** — not
  "default off". Each needs a conflict or semantics model that does not exist yet.
- **v1 trusts only OS-trusted TLS.** No pinning, no user-accepted self-signed certificates, no
  trust-all.
- **Do not generate the client from OpenSubsonic's OpenAPI document** — it self-documents as
  work-in-progress, inconsistent with the prose spec, and marks Kotlin generation untested.
- **Do not adopt Chora as the Android foundation.** Its own README disclaims the architecture, and it
  is architecturally incompatible with a KMP core. Reference and regression source only.
- **GPL prior art (Amperfy, Shelv) may be evaluated as products and used as behavioral references; it
  is not a code donor** for an App Store client. "Unusable as a reference" is wrong — reading an app to
  learn what features exist is not copying it.
- **Bundle prefix is `com.legitimateapps.dulcet`, and it does NOT derive from the marketing domain.**
  A bundle identifier is immutable once an App Store Connect record exists, so it must not depend on a
  name that can lapse or get rebranded. `legitimateapps.com` is already owned by the LLC and four
  shipped apps already use the namespace. The marketing domain (`getdulcet.com`, chosen, not yet
  purchased) reaches only support/privacy URLs and marketing, and gates nothing. **Never re-derive the
  bundle prefix from the domain**, never publish this app under any other namespace, and never reuse a
  namespace from an unrelated project that happens to be present in a local build environment.
- **macOS distribution is TestFlight and the Mac App Store**, and no notarized DMG. **This is a choice,
  not a limitation** — and it is proven ground: the team already ships two macOS apps and already holds
  the `DISTRIBUTION` + `MAC_INSTALLER_DISTRIBUTION` certificate pair a Mac App Store submission needs.
  **A Developer ID certificate is absent and that blocks nothing** — Developer ID exists only for
  notarized distribution *outside* the store, which this project does not do, so **do not create one**.
  Never advertise a DMG.

## 5a · Decided 2026-08-18 by the maintainer — all ten, do not reopen

The spec's open-questions register is closed (spec §26). Re-opening one of these is a change proposal
that must argue against the recorded rationale, not a blank to fill in.

1. **Licence: Apache-2.0.** Patent grant, permissive, App Store compatible subject to the dependency
   licence audit that stays a Phase-0 deliverable.
2. **OS floors: macOS 14 / iOS 17 / tvOS 17**, Apple Silicon only.
3. **CI: GitHub-hosted standard runners.** No self-hosted runner exists in this project (see line 14).
4. **App Review demo server: a small public Navidrome on Railway**, seeded with royalty-free audio,
   existing only to give App Review working credentials. **Never a private or personal server.** Needed
   at first-submission time, not Phase 0.
5. **Android: a Google Play organization account for Legitimate LLC** — approved *in principle*, gated
   on a finding about what Play publishes. No individual's legal name on any public surface; the LLC's
   registered-agent address rather than any residential one; **full compliance with Play's verification
   requirements, never evasion**. GitHub Releases with a signed APK is the Phase-4 path regardless.
6. **One active account in v1.** Multi-account browsing deferred; the cache is already namespaced so it
   stays addable.
7. **Domain: `getdulcet.com`** — chosen, not yet purchased, blocks nothing (the bundle prefix is
   decoupled from it).
8. **Dev/test target: a local, disposable Navidrome pinned to 0.63.2.** Conformance and CI run against
   it, never against a personal or production server. See line 13 for the dogfooding distinction.
9. **TLS: OS-trusted only in v1.** Self-signed certificates and private CAs are refused. **Known
   limitation, stated honestly: some self-hosters will not be able to connect** — the remedy is to
   install the CA at OS level. Candidate v2 story is explicit per-server pinning of a user-supplied
   certificate, confirmed once and stored per account. **Never a trust-all toggle.**
10. **The Apple inline-validation loader is accepted as Phase-1 work.** The alternative is a known
    correctness gap.

## 6 · How we know something works

1. **Conformance suite** — the core against a real Navidrome, pinned to the reference version, on a
   generated synthetic corpus. It runs on Linux against a container **and** on `macosArm64` against a
   natively-run Navidrome binary, so the Darwin/NSURLSession path is exercised for real.
2. **Envelope/parser parity** on every target for recorded bodies, and a **wire-pathology** suite
   driven by a low-level scripted socket server for truncation, mislabeled types, malformed headers,
   mid-body reset, redirect chains and TLS failures. None of that is called "transport parity" unless
   real sockets are involved.
3. **`FEATURES.yml`** — one row per user-facing capability, one cell per platform, evidence required
   for `shipped`, CI failing on an undeclared regression, with the regression approval carried by a
   CODEOWNER-applied label rather than text a contributor can type. It is simultaneously the honest
   capability matrix, the CI gate, and the unit of work handed to a delegate.
4. **End-to-end, always.** A feature is done when the real trigger has been driven to the real observed
   effect on a real device or simulator. Mark every link OBSERVED or ASSUMED; never say "verified"
   while any link is assumed.

## 7 · What Dulcet is not

Not a server. Not a library manager. Not a tag editor. Not a video player. Not a podcast or audiobook
app — long-form media gets ordinary-song behavior and nothing more. Not a last.fm client: we scrobble
to the server and its relays are its business. Not a Jellyfin client. Not a wrapper around a web app.
Not an analytics product.
