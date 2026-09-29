# Traps that cost real time

Moved out of `CLAUDE.md` on 2026-09-29 so sessions load it on demand. Read the entry for the
subsystem you are about to touch. Numbers are stable references, not an order of importance.

1. **`ios()` / `tvos()` / `watchos()` target shortcuts are gone** (removed in Kotlin 2.2.0). Enumerate:
   `macosArm64() iosArm64() iosSimulatorArm64() tvosArm64() tvosSimulatorArm64() androidTarget() jvm()`.
   The `jvm` target exists only for the conformance suite.
2. **Apple Silicon only.** `macosX64`/`tvosX64` are deprecated; do not add them "just in case."
3. **The static framework goes in Link Binary With Libraries and NOT in Embed Frameworks.** Embedding a
   static framework as a runtime payload ships dead bytes and can fail submission validation.
4. **Binary `/rest` endpoints return an error envelope on failure.** `/rest/stream` and
   `/rest/getCoverArt` both require unconditional, ordered validation: detect XML or JSON envelopes
   first (skip whitespace and BOM), then require a positive endpoint-specific signature. "Not an
   envelope" is never sufficient. A leading `{` or `<` is only an envelope candidate; require a
   recognizable Subsonic root before classifying it as a malformed envelope, because those byte
   values occur naturally inside ranged binary media. For audio, get the offsets right — `ftyp` is
   at **offset 4**, WAV needs `RIFF` at 0 **and** `WAVE` at 8, MP3 sync is a **mask** not a string, and
   an ID3 tag can precede FLAC. Downloads use the audio table; artwork uses its image-signature table.
5. **Do not validate with a preflight and call it proof.** The engine makes a *second* request. Inline
   validation via `AVAssetResourceLoaderDelegate` / a custom `DataSource.Factory` is the mechanism
   (spec §12.4). This is real Phase-1 Apple work — see OQ-10.
6. **The `transcoding` extension is not `stream?format=`.** It is `getTranscodeDecision` (POST, a
   `ClientInfo` body) then `getTranscodeStream` with an **opaque** `transcodeParams` you must never
   parse or rebuild. Classic `stream?format=&maxBitRate=` is the separate legacy path, and
   `transcodeOffset` belongs to that legacy path (spec §12.5).
7. **`stream` does not record a play.** `scrobble submission=true`, past the threshold, measured from
   *progressing media time* with buffering, pause and forward discontinuities excluded (spec §15.2).
   Scrobble delivery is **at-least-once**; the local dedupe key does not make the network call
   idempotent.
8. **`playbackReport` is not called in v1.** Adopting it without CONF-21 double-counts plays.
9. **Never collapse the three playback identities.** `QueueEntryId` / `PlaybackSessionId` / `AttemptId`
   (spec §12.1). A refresh, a retry and a server-offset seek keep the session; a next-item advance and
   repeat-one end it.
10. **Nothing is named "audible."** `PlaybackProgressBegan` = media position advancing under an
    unsuppressed playing state. `timeControlStatus` and Media3 `isPlaying` are transport state and do
    not prove sound.
11. **Media3 has no position-progress callback.** The Android adapter owns a periodic sampler on the
    monotonic clock; "the core never polls" refers to the core, not the adapter.
12. **Credentials are in the query string.** Redact the whole query string before anything reaches a
    log, an error or a diagnostic. Fresh CSPRNG salt (16 bytes / 32 hex) per request. Never log
    password, token or salt. Wrap AVFoundation/ExoPlayer errors before surfacing them — both can carry
    the URL. **Strip credentials on cross-origin redirects; never follow an HTTPS-to-HTTP downgrade.**
    Test with **canary values**, not a hex-pattern scan — Navidrome ids are themselves hash-like.
13. **`apiKeyAuthentication` is not advertised by Navidrome 0.63.2.** Do not design around it and do
    not speculatively try an API key.
14. **`getOpenSubsonicExtensions` is unauthenticated and may 404.** A 404 means `legacySubsonic`, not a
    login failure — **but do not trust that classification until an authenticated `ping` succeeds**, or
    a reverse-proxy login page becomes a "Subsonic server" you then send credentials to. "Extension
    list unavailable", "not a Subsonic server", "server unreachable", "TLS untrusted" and "auth failed"
    are five distinguishable outcomes (spec §10.3).
15. **Extension discovery is not the only UI gate**, and one failed request never revokes an advertised
    capability — use the circuit breaker (spec §10.4).
16. **IDs are opaque strings.** Navidrome's are hashes/UUIDs. Any integer parse is a bug.
17. **The Swift boundary is Objective-C.** Exported Kotlin arrives as *classes*, not structs; value
    types are hand-written Swift structs in `DulcetKit`. Async is completion-handler plus a
    synchronously-returned `OperationHandle`; callbacks on the main thread; no Kotlin exception may
    cross (it terminates the process). Review the generated ObjC header diff on every facade change.
18. **There is no change token, and offset paging is not a snapshot.** Dedupe cannot recover an
    omitted row. Dulcet is a reader, not a mirror (spec §16.8): a page is one server read, and a
    window of pages is extended only when the scan-status readings taken **before** the page's
    request and **after** its response **both** show the window's stamp unchanged and
    `scanning == false` — never on *after* alone, which the race probe
    (`tools/probes/window-epoch-race`) caught accepting a page read during a scan (spec §16.12;
    CONF-70 pins the bracketed check). A window whose stored stamp differs from the
    current one is torn at its first live read and rebased around the viewport — never stitched.
    While the server scans, pages append marked unverified and the list says so (spec §16.12).
    Bounded concurrency 4.
19. **The catalog epoch is a scan clock, not a change feed** (spec §16.11). `lastScan` is compared as
    a raw string for equality only, together with the `getMusicFolders` id set; the sentinel
    `0001-01-01T00:00:00Z`, an absent value or a failed read is "no epoch", never "unchanged". It never
    covers user state (stars, ratings, play counts), so the visible screen is re-read when online.
    `/rest` has no ETags or conditional requests. Gone-ness comes from a successful `getAlbum` no
    longer listing a track, or code 70 — **never** from `getSong` answering `ok` or from `songCount`,
    because a server keeping missing files answers both as if the file still existed.
20. **Two clocks.** Monotonic for accumulation, timeouts, backoff and cadence; wall clock for scrobble
    timestamps and retention. Never persist a monotonic value.
21. 🚨 **The Compose-for-TV artifact is `androidx.tv:tv-material` (1.1.0), NOT `androidx.tv:tv-material3`.**
    `androidx.tv.material3` is the *package*; there is no such *coordinate* — verified 404 against
    Google Maven with a positive control. Do not mix it with `androidx.compose.material3:material3` in
    the TV module (each has its own `MaterialTheme`). TV Lazy Layouts are deprecated out of
    `tv-foundation`. **Resolve every dependency coordinate against a live index before writing it into
    a build file** — this one sat wrong in three documents and would have failed the first Gradle sync.
22. **`ios()`/`tvos()` shortcuts are REMOVED** (2.1.0 error, 2.2.0 removal). Not deprecated — gone.
23. **The Apple deployment-target override is a raw compiler flag**, not a Gradle DSL property:
    `freeCompilerArgs += "-Xoverride-konan-properties=minVersion.macos=14.0"`. And
    `embedAndSignAppleFrameworkForXcode` **only registers if `binaries.framework` is declared** — a
    scaffold with targets but no framework binaries calls a task that does not exist.
24. **`getTranscodeStream` fails with standard HTTP status codes; legacy `stream` fails with an
    envelope at HTTP 200.** They are different conventions. And the reference server returns **HTTP 429
    + `Retry-After: 5`** with an envelope carrying the *generic* code 0 when its transcode cap is hit —
    so map `Server.Busy` from the **status**, never the envelope code, and honour `Retry-After` instead
    of your own backoff. **Preload is the behaviour most likely to trip the limiter** (spec §12.8).
25. **The seam method is `recordPlaybackEvent`, never `reportPlayback`** — the latter is literally the
    endpoint v1 forbids calling, and a one-line brief naming it would wire up the wrong thing.
26. **Bundle identifiers freeze on the first BUILD UPLOAD**, not on app-record creation. Records, App
    IDs and profiles are freely revisable before that.
27. **Mac App Store requires App Sandbox** (Guideline 2.4.5(i)). Everything we write lives in the
    container; security-scoped bookmarks are not needed for v1, and **no self-updater on macOS, ever**
    (2.4.5(vii)).
28. **Never let a missing dependency degrade into a pass.** A Navidrome without ffmpeg silently
    direct-plays instead of erroring, so transcode tests would report green while measuring nothing.
    Every test depending on a server-side capability asserts that capability first and **fails, never
    skips** (spec §20.2.2).
29. **A zero result is not a finding until the query has been validated against a known positive.**
    A search that must find nothing should first be run once where it *should* find something.
    Every expensive wrong turn in this project's history has this shape — a correct instrument
    answering a question nobody asked, returning a clean-looking negative. Grepping `/rest/stream`
    against a server that logs `msg="Streaming file"`; filtering by `--include='*.py'` when the
    tools are extensionless; querying SpringBoard for a dialog another process owns.
30. **Two queries that share a root are one instrument.** Ask what two attempts have in *common* —
    process, substrate, transport, endpoint — not what differs. Changing `alerts` to `buttons` while
    keeping the same application root is not a second opinion; it is the same probe twice.
31. **Ask what the previous run left behind before crediting a change with a pass.** The thing under
    test can mutate the environment it runs in: a failing run may connect and store a credential, so
    the next run passes because a prompt no longer appears and the fix under test never executes.
    Pooled simulators, warm DerivedData, persisted databases and populated caches all carry this.
    ➡️ **A test whose purpose is to handle a condition must prove it encountered that condition** —
    a marker the handler itself emits, asserted as part of the pass. "It passed" is not evidence the
    handled path ran, and the more plausible the fix looks, the less a bare pass tells you.
32. **Assertions that check the experiment is the one you think you are running earn the most.**
    Destination, device class, server identity, fixture identity. `("402.0") is not > 700` — an
    iPhone where an iPad was required — turned a meaningless run into an obviously-invalid one
    instead of a plausible refutation. A failure that names *why the setup was wrong* is worth far
    more than one that says the test did not pass.
33. **The iOS save-password dialog belongs to `com.apple.AuthenticationServicesUI`, not SpringBoard.**
    `springboard.alerts` and `springboard.buttons` both return nothing while it is plainly on screen,
    and querying the owning bundle from XCUITest does not reach it either. Do not spend time
    dismissing it: a UI test that needs an account should configure it through the `#if DEBUG`
    launch-argument hook in `DulcetiOSApp` instead, so no password is ever typed and the dialog
    cannot occur. That hook drives `submitAccountConnection()` — the real connector and Keychain
    store — so it skips the typing, never the connecting.
34. **Env vars reach an XCUITest runner only as `TEST_RUNNER_*` in xcodebuild's own environment.**
    Trailing `KEY=value` on the `xcodebuild` line is consumed by the build-settings parser and never
    arrives. ⚠️ The failure shape is the trap: **fast, confident, several assertions at once**. A run
    that dies in under two seconds with three "Missing …" failures is missing variables, not a broken
    product. (A process under `simctl spawn` needs `SIMCTL_CHILD_` instead — same idea, not
    interchangeable.)
35. **`xcodebuild` often idles 8+ minutes in "Finalize test log" AFTER the test has finished.** You
    do not have to wait: the full transcript is already plain text at
    `$BUNDLE/Staging/StandardOutputAndStandardError*` (use `grep -a`). Screen recordings in these
    bundles have broken timestamps, so `-ss` and `-sseof` silently produce nothing — use
    `ffmpeg -fflags +genpts -i <mp4> -vf scale=740:-1 -update 1 out.png`. ⚠️ Killing a hung
    `xcodebuild` destroys the recording, which is frequently the only instrument that distinguishes
    *occlusion* from *unreachability* — an assertion naming reachability can be reporting a dialog
    on top, with the accessibility tree looking entirely normal underneath.

36. **A pinned Homebrew formula drifting upstream fails 100% of Apple CI, and presents as an unrelated
    red on whatever pull request runs next.** The Darwin conformance closure resolves through `brew`,
    so `brew update` + `brew fetch` install the *live* formula — a pin that disagrees with live
    verifies nothing, and the check is right to fail closed. Four drifts so far (openssl, x265, an
    ffmpeg revision, sdl3). Refresh the pin from `https://formulae.brew.sh/api/formula/<name>.json`,
    not from the CI error text, and update both the url digest and the `sha256`. ➡️ **Check this
    first when several unrelated pull requests go red together** — it looks like flakiness and is
    deterministic. `pins.json` already records each bottle's ghcr blob digest, and fetching that blob
    directly does retire pin drift — but **installing from the fetched path is a different thing and
    reintroduces a worse failure**. A package-path install makes Homebrew parse the formula embedded in
    the bottle, which fails on any Homebrew generation that rejects a keyword argument appearing in it.
    OBSERVED 2026-09-05: the pinned `openssl@3` 3.6.4 bottle embeds
    `symlink "…", "…", overwrite: true`; a package-path install failed with
    `openssl@3: unknown keyword: :overwrite` and then a misleading
    `Cellar/openssl@3/<older version> is not a directory` on 4 of 4 runs on one runner-image generation,
    while a by-name install succeeded on that same image. **The digest fetch is not the problem; the
    package-path install is.** Retiring this class therefore needs a way to pour a digest-fetched bottle
    *without* routing through Homebrew's own formula parse — not simply fetching by digest.
37. **The capture step runs BEFORE the iPadOS steps, so a capture divergence SKIPS them.** Measured:
    capture at step 25 `failure`, iPadOS boot and layout at 31-32 `skipped`. A ~20%-per-pair capture
    flake therefore gates every later step in the job, and an iPadOS fix cannot be validated at all
    while capture is red — regardless of whether the fix works. Read the *step* conclusions, not just
    the job's, before concluding a downstream fix failed.
38. **A readiness probe that gives each attempt "the remaining deadline" is not a poll.** Measured:
    `attempts=1 elapsed=76.08s` against a 60s budget — the first attempt consumed the whole window.
    Per-attempt timeout must be small and bounded **independently** of the total. And bound the
    cleanup too: a `communicate()` after `SIGKILL` silently restores the unbounded wait.
    ➡️ **Assert the attempt COUNT in the control.** Every existing control passed while the poll made
    exactly one attempt, because they all verified the outcome and none verified the process.
39. **`simctl spawn <udid> /usr/bin/true` runs the HOST's binary, not the simulator's** — a leading
    `/` is a path on the host root. The iOS 26.5 runtime has `bin/launchctl` and **no**
    `usr/bin/true`, and bare `true` is not on the device PATH either. Worse, `simctl spawn` may never
    return on a hosted runner even after `bootstatus -b` succeeds, while XCUITest launches the app
    fine — different paths. **Do not gate a job on a process-launch probe without evidence the runner
    can satisfy one**; a gate that blocks runs which would otherwise pass is a false blocker, not
    safety.
40. **Reason about the capture flake from a SOAK, never from a single failure.** `capture-soak.yml`
    (`workflow_dispatch`, 30 independent pairs, ~40 min) draws in one run what weeks of merges would.
    Measured base rate is **20% per pair**, not the ~15% inferred from CI failure classification —
    that undercounts, because runs dying earlier never reach the capture step. Two findings were
    invisible to single-failure analysis: `dy` and the differing-region start are **perfectly
    correlated** (`-3`↔`y=208`, `+3`↔`y=168`, 3 of 3 each), which rules out one mechanism with a
    random direction; and at least **two distinct divergence kinds** exist — a 3-point translation
    whose mechanism is still unknown, and a **focus/control-state** difference where glyphs do not
    move at all and the field *background* differs. Do not average them together.
41. **A negative control must be able to prove it fired.** Twice in one session a control passed
    while the thing it guarded was broken — the poll that made one attempt, and an assertion
    satisfiable by an earlier identical event. Where a control checks an outcome, add one that checks
    the *process*: the attempt count, the ordered suffix after a recorded index, the marker the
    handler itself emits.
42. 🚨 **`apple/project.yml` is the SOURCE; `apple/Dulcet.xcodeproj` is generated from it by the
    pinned XcodeGen and committed. Nothing regenerates it during a build.** Editing project.yml alone
    changes what the repository documents and NOT what Xcode runs — and
    `tools/verify_dulcet_core_build_order.py` reads the **pbxproj**, so it keeps reporting PASS about
    the old script. Regenerate with `cd apple && xcodegen generate` (version pinned in
    `docs/TOOLCHAIN.md`; 2.46.0 reproduces the committed project byte-for-byte), and note that a
    rebase may textually merge the pbxproj into something XcodeGen would not produce.
    `tools/verify_xcode_script_phases.py` (parity-gate, stdlib-only, no Xcode) compares multisets of
    literal script bodies including duplicate counts; it does not verify target attachment, ordering,
    shellPath, dependency flags or input/output files — the build-order guard covers attachment and
    ordering, and the rest needs regeneration plus review of the generated diff.
43. **Eight Apple targets each own the `Compile Kotlin Framework` phase and Xcode builds independent
    targets in parallel**, so two Gradle invocations start together. Gradle does queue behind its own
    locks, but only for about 60 s: if the owner has not yielded by then it FAILS the build. Most
    pairs finish inside that window (a tvOS pair on green run 34596556005 ran concurrently for over
    four minutes and both succeeded); the failure is the long tail. Two different locks have lost
    that race on `main`: the checkout-scoped **configuration cache** (`.gradle/configuration-cache`, run
    34635969077, `Timeout waiting to lock Configuration Cache`) and the user-home-scoped **journal**
    (`caches/journal-1`, run 34127121022), both with `DulcetiOS` and `DulcetKitIOSTests` building
    together. It reads as a red required check on a product-unrelated commit. Every phase goes
    through `tools/run-gradle-exclusive`, which holds one flock beside each resource for the whole
    invocation; do not reintroduce a bare `./gradlew` there, and do not key a replacement lock on
    only one of the two resources.

44. **Robolectric's TLS provider differs by host architecture.** It disables Conscrypt on macOS
    Apple Silicon and enables it on Linux. **OBSERVED 2026-09-08:** the Android playback downgrade
    fixture passed on ARM JDK 17/21 but failed on x64 Temurin 21 before recording a request:
    Conscrypt reflected into `java.net.InetAddress.holder()` and hit `InaccessibleObjectException`.
    The host-socket fixture uses method-scoped `@ConscryptMode(OFF)` to retain ordinary certificate
    and hostname checks without opening JDK modules. Keep the exact source-request-count assertion:
    a TLS failure also throws the expected playback exception and would otherwise counterfeit a pass.

45. **A freshly installed Android app can have its foreground notification cancelled mid-test.**
    The notification service answers `PACKAGE_ADDED` by cancelling *every* notification the
    package holds, foreground-service ones included, and the service stays in the foreground with
    no notification record. On a freshly booted emulator the app's own install broadcast can land
    15 s into the first test. It presents as a flaky "no foreground notification" with the session
    still playing. Device proofs wait on `am wait-for-broadcast-barrier` before playing
    (`awaitQueuedBroadcastsDelivered`). Measured: phone 11/12 without the wait, 12/12 with it
    (docs/verification/android-playback-surfaces.md).

