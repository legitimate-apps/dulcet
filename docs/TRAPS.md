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
   idempotent. The outbox classifies a failed send (spec §15.3): only error 70 is
   "this play's own" and drops it after three refusals while the server accepts other plays; everything else, account refusals included,
   holds the queue in order. Navidrome answers `ok` to a scrobble for an unknown or deleted track
   (CONF-93), so a test of the 70 path has to fake the server — the reference server cannot form it.
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

36. **Homebrew pin drift used to fail 100% of Apple CI; since 2026-10-03 the Darwin ffmpeg is built
    from pinned source instead, and its failure modes are different.** History: the Darwin closure
    (ffmpeg plus 14 Homebrew formulae) resolved through `brew`, so any upstream movement of one formula
    failed every conformance run closed until someone refreshed `pins.json` — about ten refreshes,
    two on 2026-10-03 alone. Pouring the digest-pinned bottles by package path was tried and reverted:
    it makes Homebrew parse the formula embedded in the bottle, which failed on 4 of 4 runs on one
    image generation with `openssl@3: unknown keyword: :overwrite` and then a misleading
    `Cellar/openssl@3/<version> is not a directory` (OBSERVED 2026-09-05). Now
    `tools/conformance-env/install-darwin-ffmpeg` compiles LAME, Opus and FFmpeg from SHA-256-pinned
    tarballs and `actions/cache` keeps the result (design spec §28, 2026-10-03). What can still go
    wrong, and what it looks like:
    - **Every listed URL for a source stops serving the pinned bytes** → `source <name> <version>
      failed verification at every url; nothing extracted`, with each URL's error or observed hash.
      Add a byte-identical mirror; never change the `sha256` without re-deriving it from the upstream
      release (and, for FFmpeg, its release signature).
    - **A cache miss costs the compile**: 151 s with 3 jobs on an M2 Max (OBSERVED 2026-10-03), estimated at 4 to 8 minutes on the 3-core hosted macos-26 runner until a CI miss measures it. The key changes when the pin, the installer or the
      selected Xcode's clang/SDK changes, so an Xcode pin bump rebuilds once.
    - **Building outside CI**: autoconf scripts try `gcc` first, which on a Mac whose Command Line
      Tools SDK is newer than the selected Xcode's linker fails as `C compiler cannot create
      executables` (`ld: tapi error: malformed file`); the installer names `/usr/bin/clang` and the
      selected SDK for that reason. libtool's install step and autoconf's pkg-config call do not quote
      paths, so the installer copies the static libraries out of their build trees and keeps its
      pkg-config stand-in in a space-free temporary directory; a work directory with a space works.
    - **A version or encoder failure** after a build means the source is not what the pin says it is
      — read the `DARWIN FFMPEG` lines, do not refresh the pin to match.
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
    `tools/verify_xcodegen_regeneration` (first step of apple-ci's platform leg) now fails on
    exactly that: any generated file that is not the pinned XcodeGen's output, byte for byte.
    `tools/verify_xcode_script_phases.py` (parity-gate, stdlib-only, no Xcode) compares multisets of
    literal script bodies including duplicate counts; it does not verify target attachment, ordering,
    shellPath, dependency flags or input/output files — the build-order guard covers attachment and
    ordering, and the rest needs regeneration plus review of the generated diff.
43. **Nine Apple targets each own the `Compile Kotlin Framework` phase and Xcode builds independent
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


46. **In a Robolectric host test, a wait whose condition reads app state directly never idles the
    main looper.** Compose's `waitUntil` advances Compose's clock and does not idle the looper; only
    a condition that goes through a Compose finder does (fetching semantics nodes waits for idle).
    Anything delivered by a message posted to the main looper from another thread then never
    arrives. `AndroidPlaybackController` runs on the main dispatcher, and its song read resumes by
    such a message, so a wait on `playback.state.value` saw the read answered (HTTP 200 at the
    forwarder) while the controller showed nothing — no queue, no error — with every dispatcher
    thread idle. It is timing-dependent, so it reads as a flake: 2 of 8 album-play runs passed.
    Idle the main looper inside such a condition (`shadowOf(Looper.getMainLooper()).idle()`), as a
    device's always runs: 8 of 8. The reader's publications also arrive by the main looper; its
    waits work only because they read the screen through finders (spec §28 revision 104 item 36).
    ➡️ The converse trap: a finder on an activity moved to `CREATED` fails at once with "No compose
    hierarchies found", not with the state you wanted. Take any counts read through the screen
    before stopping the activity; while stopped, count at the forwarder and idle the looper directly.
    ➡️ And what no idling reaches: work on the reader's own thread. A screen composed afresh
    (the home, on every return from an album) has rows that draw nothing below their titles until
    their first publication, which that thread builds; a finder straight after the return can run
    first and find nothing. Wait for the content through a finder before acting on it. OBSERVED:
    CONF-76 failed 2 of 30 inside the TV suite, and 10 of 10 on each app with the reader's thread
    slowed; with the wait, 0 of 10 failed on each app with it still slowed, and 0 of 360 tests
    over 30 unslowed TV suite runs (spec §28 revision 104 item 37).

47. **A disabled SwiftUI button takes an XCUITest tap and does nothing, and the reader can disable
    Play for seconds mid-test.** An album page draws Play disabled while the reader is offline (its
    tracks "Unavailable offline"), and the reader goes offline on the platform's reachability report
    alone. OBSERVED in main's conformance run 37229956675: the iPhone lyrics proof's screen recording
    shows the album page offline for 4.5 s from about two seconds after it opened, with the test's
    Find and tap of Play both inside that window; the bar kept the relaunch's restored track and the failure read as
    "Play lost to the restored queue". Nothing in the app logs reachability, so the path monitor's
    report is ASSUMED. Wait for `isEnabled` before tapping a control the reader can disable
    (`tapAlbumPlay`). The failure-diagnostics artifact's `.xcresult` carries a screen recording; read
    its frames (`ffprobe -show_entries frame=pts_time` lists one frame per screen change) before
    theorising about a UI failure.
    Since 2026-10-04 the shells hold an unreachable report for 5 s before telling the reader (spec
    §16.14), so a blip no longer disables Play; a loss that outlasts it still does, and the wait for
    `isEnabled` stays. Reachability is now logged: Apple's unified log, category `reachability`
    (`xcrun simctl spawn <udid> log show --predicate 'category == "reachability"'`), and Android's
    `DulcetReachability` tag. In a Robolectric test the grace runs on the paused main looper's clock:
    `PlatformNetwork.lose()` passes it, `drop()` does not.

48. **On iOS, an open context menu on a row that is also a drag source keeps the app from ever going
    idle, and a custom menu preview kills the drag.** UIKit keeps the drag's lift armed while the
    menu is up: a paused animation sits on a zero-size view the app never draws, so XCUITest's
    quiescence check never passes and every event waits its full 60 s ("App animations complete
    notification not received"). A press on such a row took 61.6 s against 2.7 s with the drag
    source removed; that is the OS, and keeping drag-from-menu keeps it. Tests drive those menus
    inside `withoutIdleWaits` in `DulcetiOSUITests`. Separately, `.contextMenu(menuItems:preview:)`
    with a custom preview keeps the touch in the open menu and the drag never lifts: Up Next stayed
    empty, and gained the track with the system preview or with no menu (OBSERVED, iPhone 17 Pro
    simulator, iOS 26.5). A row that is a drag source uses the system preview;
    `testATrackDraggedOutOfItsContextMenuJoinsUpNextOnIPhone` fails if a custom one comes back.
    The account-connect state proof uses the same interaction scope and waits for each expected
    screen state explicitly. On iPad CI with iOS 26.4.1, the main run loop replied idle and the
    protocol error was drawn after Try Again, but subsequent animation-idle requests received no
    reply (apple-ci 37740342688). A persistent iPad simulator on the same runtime reproduced the
    wait after a password-field tap following the drag/search proofs. Read-only debugger inspection found the main thread
    asleep in its run loop and one animation still counted by XCTest; the window layers had no
    download spinner. The exact animation was not identified. A relaunch also replaces XCTest's
    current process: the application keeps its interaction options, but the replacement process
    starts with ordinary event waits. OBSERVED 2026-10-08 with Xcode 26.6 / iOS 26.5 on the iPad
    simulator: application=3 across two launches, process=3 on the first and 0 on the second. The account-state proof
    reapplies its active scope after each launch; `testAnimationWaitOptionsReachEachLaunchedProcessOnIPadOS`
    checks both actual processes and restoration of the outer scope. This scope changes only
    synthetic event waits: the proof still requires all eleven states, the server's answers, the
    silent port's accepted connection and Cancel, and the real Keychain read. Its regression tool
    models a UI that changes state while animation-idle never arrives, checks both event-wait bits
    and restoration, and rejects missing or changed runtime methods safely. Sign Out also waits
    for the empty form and retries while its confirmation remains: the opening iPad popover can
    expose its button before accepting a tap. A control whose first confirmation tap is ignored
    must still reach the empty form. Before tapping a scrolled control, the proof also requires
    its visible, hittable frame to stop moving for 0.4 s; accessibility can expose the control
    during deceleration, when a tap would only stop the scroll. OBSERVED 2026-10-09: in run
    37980371329, repeated live frame/existence/hittability queries took seconds apiece; the silent
    request reached its 30-second limit before Cancel was tapped, and the failure hierarchy showed
    the timeout error and Try Again instead of the primary action. Run 37974224276 timed out
    evaluating the consent's descendant/value query. Geometry polls now take one window snapshot,
    match immutable attributes locally, and retain a live hittability check after the target settles.
    The consent uses a typed switch query. The control rejects live geometry polling, accepting a
    moving frame, and accepting an occluded target; temporary snapshot absence must be re-observed.
    Host pressure contributing to the query slowdown is ASSUMED, not established by these failures.
    OBSERVED in run 38488956473: the playlist-edit proof on iPad spent twelve 60-second animation
    waits before its 900-second allowance expired; the same proof passed in 237 seconds on main
    run 38480396625. Its complete event sequence now uses the existing interaction scope, with
    every UI and server-write assertion retained. In the iPhone account proof, redundant live
    Cancel-label reads (including logging) consumed 34 seconds after connection started, beyond
    the unchanged 30-second deadline. The recording shows visible Cancel; the failure hierarchy
    later shows Try Again. Match Cancel's label and enabled state in the same immutable snapshots
    used for geometry, then retain the live hittability check and actual cancellation assertion.
    OBSERVED in main run 39191569391: after account restoration the recording still showed
    Library selected, while the first global Sign Out query traversed its catalog and timed out
    before the helper could open Connection. Open Connection first, preserving its form if already
    selected, then wait for Sign Out. The host control rejects querying Sign Out on Library and
    still accepts a slow navigation whose successful arrival consumes the wait deadline.
    OBSERVED in main run 39728604012: a broad Rename `firstMatch` query consumed about 25 s,
    and the playlist proxy's unchanged 45 s hold expired before the prompt was activated.
    The playlist proof now identifies the menu action explicitly, witnesses its enabled, visible,
    stationary frame, and requires the named prompt. A historical `held > 0` is insufficient:
    the read must still be pending under that prompt, and the terminal receipt must prove every
    held read was explicitly released with zero timeouts. The AX stall's internal cause remains
    UNKNOWN. The same run's iPad My Library failure was a snapshot timeout after a 60 s launch
    idle wait; its recording remained Connecting. Native diagnostics confirmed a main-loop work
    request went unanswered for 30 s while animation-idle replied immediately, but contained no
    stack identifying the blocking work. In run 39908667699 attempt 2, launch idle and two
    snapshots succeeded, the reader logged connected with successful Home/catalog responses,
    then a later snapshot's main-loop work stalled for about 38 s. Initial connection failure
    alone cannot explain that case; the blocking work remains UNKNOWN. The fixture
    proxy now records credential-free endpoint/phase timings (`tools/test-my-library-ui-proxy`)
    so a future failure can distinguish arrival, upstream completion and delivery.
    OBSERVED in run 39938927349's iPhone album-tile drag: the bar existed as Loading with Play,
    so a one-time `label == "Pause"` check skipped the toggle entirely. Playback then started
    while the helper waited for Play; the activity tree contains no Pause tap. The shared album
    drag setup now requires the named last track and enabled Pause together, then relaunches and
    requires the same saved selection with enabled Play. A restored paused queue holds the drag's
    baseline without a toggle race or a short fixture ending while the proof works.
49. **A reader tile has no tracks when it lifts, so its drag carries a read, not an addition.** An
    album or playlist in the reader's grids and lists is a `DulcetReaderItem`: a title, an id and
    counts, with its track list read on demand (`resolveTracks`). `dulcetQueueDragSource` needs the
    addition when the drag begins, so these views use `dulcetDeferredQueueDragSource`: the ticket
    holds a resolver, and the read is made when the drop lands on the bar or Up Next
    (`DulcetQueueDragRegistry.dropOntoQueue`). The tracks come back in the reader's own order, the
    one the menu's Add to Queue uses. The drag source is attached by the item's kind, which never
    changes, so no tile changes identity under a read; an artist or a genre has no track list and
    is not a drag source. The context menu of anything that is a drag source takes the system
    preview (trap 48), so `dulcetReaderItemContextMenu` keeps the custom preview only for the kinds
    that are not. `testAnAlbumTileDraggedOntoTheBarJoinsUpNextOnIPhone` and its iPadOS, playlist
    row and search album siblings fail if the source goes. They also fail when the first drag leaves
    the menu open, but on a simulator the custom preview left it open in one run of four, so a
    custom preview put back is not reliably caught; the preview rule rests on trap 48's evidence.

50. **Under Robolectric, a focused text field in a wrap-content Compose `Dialog` never lets the
    window settle.** Every layout pass the field reports its focused rectangle, the dialog window is
    relaid out, and its root view asks for layout again; `isLayoutRequested` stays true. The idling
    strategy keeps laying it out, so the next wait for idle never returns. The process slowly fills
    its heap and ends in an `OutOfMemoryError` far from the cause, inside text layout (that
    Robolectric's trace shadow retaining sections is what fills it is ASSUMED). OBSERVED on the TV's
    Add to Playlist chooser: the Centre press that swaps New Playlist… for the name field returned,
    and the next semantics read hung, with the dialog root's `isLayoutRequested` true in every
    sample. A throwaway minimal Robolectric test, not kept in the repository, held the same field
    for 39 s before failing in a default `Dialog` and settled it in 10 ms in a
    `Dialog(properties = DialogProperties(usePlatformDefaultWidth = false))` whose content is a
    full-size `Box` centring the surface; a button in either dialog settled at once. The kept
    evidence is the chooser's proof, which hangs with the default form and passes with the
    full-size one. On a device the wrap-content form may behave; that is ASSUMED, not tested. TV
    dialogs that hold a text
    field use the full-size form. To find a hang like this, sample the test thread's stack from a
    second thread and print each window root's `isLayoutRequested`. The point where the heap runs
    out says nothing about the cause.

51. **On macOS and the Apple simulators, a loopback port that is bound but not listening is not
    refused.** A test that wants a connection the system refuses at once, and holds a port with
    `bind` alone to keep it from being reused, gets a connect that waits until the request's time
    limit instead (30 s for the account connector), and the app shows the timeout's copy, not the
    unreachable one. OBSERVED on 2026-10-07 in the account-connect states proofs. Close the socket
    right after `bind` and `getsockname` have named a free port: the port is then refused at once.
    The account-connect proofs hold their silent port (one that accepts and never answers) with a
    real `listen`, and release their refused port as soon as it is named.

52. **The Apple TV keyboard's return button carries the field's `submitLabel`, and a field whose
    `onSubmit` moves focus opens the next field's keyboard.** A UI test that waits for `done` on an
    account field finds only `next`; selecting `next` on the server address or username submits it
    and the keyboard comes straight back for the following field. OBSERVED on 2026-10-07 on the
    tvOS 26.5 simulator. The account-connect states proof selects `next`, then presses Menu only
    when another account field holds keyboard focus, so a keyboard that is merely slow to close
    never sends Menu to the app. In the same proofs the system keyboard also kept 10 of 31 typed
    characters under host load: verify each typed field and retype a short one, never accept it.

53. **An AVPlayer item being ready with no `item.error` does not rule out a decode failure.**
    OBSERVED on 2026-10-09 in `AVPlayerDecodeFailureTests` on native macOS and tvOS 26.5 Simulator:
    the corrupt MP3 emitted `AVPlayerItemFailedToPlayToEndTime` with `AVFoundationErrorDomain`
    `decodeFailed` (-11821), wrapping OSStatus 1650549857, while the item remained `readyToPlay`
    with no item error; its error log was empty. The engine already reads that notification.
    OBSERVED in hosted [run 37944337563](https://github.com/legitimate-apps/dulcet/actions/runs/37944337563),
    on tvOS Simulator: the same test requested play but ended its 270 s wait paused at time zero
    without a failure event. The missing AVFoundation verdict was then **undiagnosed**;
    increasing the wait did not explain it.
    Local immediate-play, play-after-ready and play-before-delivery controls did not reproduce
    it; only the suspended-delivery case guarantees play before readiness. The test now records
    independent, timed transport/item transitions, failure/stall/end notifications, numeric error
    chains and error-log codes so a recurrence can distinguish a
    notification the engine missed from none sent during observation, which starts after prepare
    and before play. FigFilePlayer's -12864 is a console signal with no public Apple documentation
    found; do not treat it as an AVError code
    or infer a media failure from paused transport alone. Diagnostic strings exclude error
    descriptions, unknown domain names, URLs and server addresses. Notification counts and the
    latest failure codes survive eviction from the bounded transition history.

    OBSERVED again on 2026-10-14 in hosted
    [run 38535897557](https://github.com/legitimate-apps/dulcet/actions/runs/38535897557),
    with Play preceding byte delivery: ready at 39 ms, paused at 69 ms, no progress or failure
    notification for 270 s, and a responsive engine queue. AudioQueue logged the same converter
    error (1650549857, zero decoded frames) as the passing controls. This exposes a product gap:
    a requested, ready item silently paused before progress had no recovery or terminal bound.
    ASSUMED: a dropped/ignored Play caused this hosted run; the trace establishes the paused
    state and missing verdict, not that cause. The controls inject the lost Play with
    `StartupPlayDroppingPlayer`. The engine reasserts transport once when ready and paused with no
    pending reads. If reassertion does not help, the original watchdog turns the 270 s hang into
    an approximately 122 s `engine` failure; its revised ceiling is 120 s from the Play request,
    measured monotonically. Pending reads defer the reassertion, never the terminal deadline;
    waiting/paused flaps and repeated Play cannot reset it. Deliberate pauses, interruptions,
    progress and retired attempts cancel it. The real decoder's `undecodable` verdict is preserved;
    inactivity alone never invents it. Local native macOS and tvOS Simulator regression controls
    inject a dropped initial Play, then require valid media to progress and corrupt media to
    fail as `undecodable`. A manual-clock control requires the absent-verdict case to fail once as
    `engine`. Physical-device occurrence remains unproven; the engine gap is not simulator-specific.

54. **A live XCUITest query's count does not keep its elements alive.** OBSERVED on tvOS in
    hosted runs 38354526936 and 38470501331: the Search proof failed before reaching Search.
    Launch focus inspection counted Connection's text fields and inspected index 0; the
    successful connection then landed on Library, and resolving index 1 failed with
    "No matches found for Descendants matching type TextField". Both failure hierarchies and
    recordings showed Library with Home focused. Read focus attributes from one immutable
    `app.snapshot()` hierarchy; read the section title from that same hierarchy when pairing
    section and focus. An `exists` check before a live attribute read leaves the same race.

55. **An expression inside a large `run` scalar can prevent the workflow from starting.**
    OBSERVED 2026-10-16: apple-ci [38918627931](https://github.com/legitimate-apps/dulcet/actions/runs/38918627931)
    was rejected before creating jobs when the iPad My Library command added a GitHub expression
    inside the long conformance script. The scalar exceeded GitHub's expression-length limit.
    Pass the destination through the step's `env` and read `$IPAD_UDID` in the shell instead;
    the accepted replacement [38918766366](https://github.com/legitimate-apps/dulcet/actions/runs/38918766366)
    started its jobs. A passing local CI policy check alone does not prove GitHub accepted a workflow.

56. **Stable simulator service counts do not establish that the post-boot paging burst ended.**
    OBSERVED 2026-10-24 in [run 40117378423](https://github.com/legitimate-apps/dulcet/actions/runs/40117378423),
    attempt 2 iPad: after `--settle` succeeded, My Library recorded `pageins_mb=8263`,
    `swap_used_max_mb=1087.19`, `load1_max=671.4` on a 3-CPU, 7 GiB standard runner. Its first
    cold launch failed inside XCTest's accessibility snapshot query, before the enclosing
    `waitForExistence(timeout: 90)` could help. The pressure record names News, Health widgets,
    poster extensions and SpringBoard; the app's three-second stack capture collected only nine
    main-thread samples. This supports host starvation, not an app deadlock. The 33 completed
    iPad/iPhone jobs in the 2026-10-20..24 census include five failures of the first My Library
    test, four at the first connected query; load alone does not separate passes from failures.
    `run-my-library-proofs` now requires `--settle-pressure` after service stability, before the
    first iPhone/iPad proof. The platform leg also
    waits before its first iPhone native test and before its booted iPad destination confirmation
    (the latter query timed out in [run 40083176763](https://github.com/legitimate-apps/dulcet/actions/runs/40083176763)). The sampler's kernel
    counters must show a full minute with every interval at <=20 MiB/s page-ins, <=1 MiB/s combined
    swap I/O, and load <=120 without a renewed rise (>10% across the window). A missing, reset or
    stale reading cannot establish readiness. The 900-second cap logs `settled=false` and fails
    the prerequisite; no proof is skipped, retried or given a larger allowance. Waiting has its
    own `post-boot-pressure-*` phase, so moving the burst out of the proof stays visible. Free
    pages and resident swap are logged but do not independently establish active I/O.

    In [run 40327304186](https://github.com/legitimate-apps/dulcet/actions/runs/40327304186),
    attempts 2 and 3 passed pressure admission but each post-isolation destination query produced
    no output before two 60-second kills; restarting discovery cannot finish a query needing
    more than that cap, so discovery now allows 180 seconds per attempt within 240 seconds,
    records query durations and bounded simctl responsiveness, and My Library reuses the
    job-start Xcode-accepted UDID kept by isolation.

    **OBSERVED in [platform run 40142840023](https://github.com/legitimate-apps/dulcet/actions/runs/40142840023)
    (2026-10-25 UTC):** both pressure gates and every platform proof passed. The table describes
    the final 60-second admission window; elapsed time covers the whole pressure gate.

    | Measurement | iPhone | iPad |
    |---|---:|---:|
    | Gate elapsed seconds | 340.8 | 392.9 |
    | Maximum page-ins, MiB/s | 18.81 | 19.34 |
    | Maximum combined swap I/O, MiB/s | 0.17 | 0.07 |
    | Maximum load1 | 106.8 | 110.5 |
    | Minimum free memory, MiB | 60.0 | 57.8 |
    | Maximum resident swap, MiB | 861.75 | 959.0 |

    The booted iPad's same-device Xcode destination confirmation then passed in 22 seconds.
    Requiring abundant free pages or empty swap would have rejected these passing controls.
    The workflow leaves two minutes of shell/process startup margin around the unchanged
    900-second pressure deadline: run 40083176763 had spent 63 seconds starting its shell.
    Explicit marks after iPad settling, before its layout proof and before tvOS keep the wait,
    destination query and subsequent device tests in separate summary phases. In the first
    implementation, the iPad wait's mark extended through later proofs and reported 30,896 MiB
    of page-ins over 1,720 seconds; that was not the wait's own pressure.
    **OBSERVED in [conformance run 40153793474](https://github.com/legitimate-apps/dulcet/actions/runs/40153793474),
    iPad job 121050704506:** an idle-device quiet window was insufficient. Admission passed after
    320.1 seconds, with maximum page-ins 3.17 MiB/s, swap I/O 0.07 MiB/s and load1 62.4. The
    first My Library phase nevertheless reached `pageins_mb=11019`, `swap_used_max_mb=435.81`,
    `load1_max=354.9`. First automation setup was at t=24.96s, followed by the 60-second app-idle
    warning; the connected query completed, but the browse/relaunch/offline test exhausted its
    unchanged 300-second allowance. The first app stack sample contains just two main-thread
    samples in XCTest snapshot work. Later My Library tests passed. Do not describe this as a
    successful fix just because the gate found a quiet window.

    Device conformance now also installs and launches the already-built Debug shell before the
    pressure window, keeps it alive while the first rendering work settles, and requires its
    termination before `xcodebuild`. This uses the existing `-dulcet-account-connect-layout-fixture`
    path: `DulcetiOSApp.init` returns with an in-memory `DulcetDeterministicDataSource` in
    `.accountConnectIdle` before constructing the production composition, credential store or
    downloads. It supplies no server credentials and performs no account/library operation.
    The original proof launches a new production process with its original arguments and fresh
    account UUID. This warms first installation/rendering without seeding any proof's state.
    **OBSERVED in [conformance run 40164687903](https://github.com/legitimate-apps/dulcet/actions/runs/40164687903)
    on implementation head `20bd6cac` (2026-10-25 UTC):** warm-up launch and termination succeeded
    on both devices, all seven iPhone and five iPad My Library methods passed, and both complete
    device conformance jobs passed. No proof was retried or given a larger allowance.

    | First My Library phase | iPhone | iPad |
    |---|---:|---:|
    | Phase seconds | 891 | 527 |
    | Page-ins, MiB | 8403 | 7911 |
    | Maximum resident swap, MiB | 331.69 | 744.69 |
    | Maximum load1 | 234.4 | 139.9 |
    | First automation marker, seconds | 12.69 | 7.61 |
    | Browse/relaunch/offline test, seconds | 264.376 | 149.933 |

    Pressure admission took 240.3/220.1 seconds; its accepted windows had page-in maxima
    18.61/6.64 MiB/s, combined swap I/O maxima 0.14/0.14 MiB/s, and load maxima 118.2/119.5.
    The separately logged waiting phases reached load 524.9/547.9, keeping the burst's cost
    visible outside the proofs. The failed idle-gate-only iPad phase above had load 354.9,
    11019 MiB of page-ins and first automation at 24.96 seconds. The combined method's iPad
    phase had less paging and load, and completed the original assertions within their bounds.
    Both device phase loads were below the supplied census's approximately 240..671 range.
    Paging remained substantial, and the iPhone was slower than the earlier idle-gate-only
    passing control (load 222.9, 8653 MiB, first automation 7.76 seconds). Runner variability
    prevents attributing every difference to warm-up. These are first-phase pressure and
    execution observations; one passing pair does not estimate the one-in-seven flake rate.

    Proof dispatches on the same branch share the workflow's concurrency group. Dispatching
    the second before the first core job finished canceled that first core job: run 40153793474
    concluded canceled, with iPhone success, iPad failure and core canceled. Its completed
    device evidence above remains valid; it has no completed core result. Wait for the whole
    first run when collecting multiple complete comparisons on one branch.

    **OBSERVED after #391 merged (2026-10-25 UTC); the device allowance is now 600 seconds.** With
    admission and warm-up in place, the browse/relaunch/offline test still exhausted its
    300-second allowance on both devices of dispatch [40232687092](https://github.com/legitimate-apps/dulcet/actions/runs/40232687092)
    (iPhone job 121287619992, iPad job 121287620055), as it had on iPad in 40202061025. In the
    iPhone case XCTest was still issuing a step about once a second when the allowance fired,
    during the check after relaunch. Two single existence checks earlier in the test took 17.6
    and 29.5 seconds. That is a slow test on a starved host, not a hang. The 30 passes of this
    test in the retained device logs took 138.7 to 265.2 seconds, plus one at 379.8. The iPhone
    and iPad My Library proofs now allow 600 seconds per test, which still fails a test that
    stops making progress. macOS and tvOS keep 300. No proof is retried, and the admission gate
    is unchanged.

    **OBSERVED in [run 40261216579](https://github.com/legitimate-apps/dulcet/actions/runs/40261216579),
    Refs #343:** an apsd A/B/A experiment aborted on both devices before any measurement window
    or test arm. Its guard expected `RuntimeRoot/usr/libexec/apsd`; the retained process path
    instead ends in `RuntimeRoot/System/Library/PrivateFrameworks/ApplePushService.framework/apsd`.
    No suspension or accessibility comparison occurred. This is inconclusive, not evidence for
    or against apsd causing starvation. The merge candidate removes the experiment and retains
    per-PID/start/executable CPU-time deltas and raw records on every verdict. On common observed
    intervals, simulator/host diagnosticd consumed 67.56/38.78 CPU-seconds over 212.817 seconds
    (iPhone) and 34.50/20.97 over 156.574 seconds (iPad). These distinguish actual cumulative
    costs hidden by basename peak summaries; they do not name the consumer or establish cause.
    Protocol, coverage and result: [apsd investigation](investigations/2026-10-26-apsd-pressure-experiment.md).

    **OBSERVED in corrected [run 40272074448](https://github.com/legitimate-apps/dulcet/actions/runs/40272074448),
    Refs #343:** the framework-path guard and same-launch restoration passed on both devices.
    Idle persisted apsd events/s changed A1/B/A2 from 3112/0/6182 (iPhone) and 2767/3.78/1722
    (iPad), with simulator and host diagnosticd CPU falling in B and returning in A2. iPad's
    unchanged browse test took 200.087/151.654/195.548 seconds, with maximum query activities
    3.970/2.748/7.784 seconds. Its complete comparison supports a performance contribution on
    that VM; it did not reproduce the earlier approximately 19-second query stalls. iPhone A1
    had a 17.617-second query, but B and A2 failed fixture port preflight before XCTest, so
    its query comparison is inconclusive. Both restored canonical suites passed, which does
    not replace the failed arms or prove suspension safe for every assertion. #395 remains
    measurement-only; no permanent quiescence or starvation fix is established. Full CPU
    coverage, matched-query controls, residual-message limits and the proposed fixture repair
    are recorded in the investigation above.

    **OBSERVED in repaired [run 40327052864](https://github.com/legitimate-apps/dulcet/actions/runs/40327052864),
    Refs #343:** all six comparison arms reached XCTest and passed. Idle suppression and
    diagnosticd CPU reversal now repeat on four VMs. iPad browse duration again falls in B
    and rises in A2 (213.338/158.825/186.492 seconds), but iPhone A2 stays as fast as B
    (192.776/156.405/155.810 seconds). New maximum queries are 3.981/1.652/2.830 seconds
    (iPhone) and 5.358/0.957/2.550 (iPad); these are different activities, not one matched
    query. Neither new VM reproduces the earlier 17 to 19-second stalls. apsd is a repeatable
    CPU contributor and an iPad performance contributor, but is not established as the
    bottleneck for those failures. Close the experiment as a mitigation candidate, preserving
    its evidence; no further unchanged dispatch or permanent suspension is proposed. #395
    stays measurement-only. Full coverage and matched operations are in the investigation.

57. **A trusted certificate still needs an exact hostname/SAN check, including numeric hosts.**
    **OBSERVED 2026-10-28:** the production JVM CIO path with Ktor 3.5.2 sent a public discovery
    request to `127.0.0.1` when the disposable trusted certificate had only `DNS:localhost` in
    its SAN. The same certificate/context passed a `localhost` positive control and failed
    the numeric-host control in `HttpsURLConnection` before HTTP. A route probe's later base
    mapping rejection had hidden this transport difference. `ConnectionTlsEnrollmentTest`
    keeps both controls and requires zero requests at the mismatched-name HTTP handler.
    The JVM request factory now uses its existing strict OkHttp engine for account reads as
    well as media, preserving disabled retries, cookies, ambient authentication and redirects.
    Trusting a fixture root is not permission to bypass hostname verification.

58. **HTTP/1.0 implicit closure must retire its connection without replaying a request.**
    **OBSERVED 2026-10-29:** the disposable Discover proxy returns HTTP/1.0 with a fixed body
    length and no keep-alive header, then closes its socket. OkHttp 4.12 pooled that socket;
    with recovery retries disabled, the next application request failed with EOF or a reset.
    `LibraryStatusTransportTest` reproduces the second-request failure and requires one wire
    request per plain, form and binary operation. JVM and Android use a per-call public
    `EventListener` to close a non-persistent HTTP/1.0 socket after its complete response body,
    before it is released to the pool. Explicit HTTP/1.0 keep-alive and ordinary HTTP/1.1
    retain pooling, with a positive single-connection control. Enabling retry would hide the
    stale connection while changing delivery semantics for state-changing requests.

59. **Wire evidence must decode HTTP framing and negotiated content encoding before reading JSON.**
    **OBSERVED 2026-10-29:** fresh disposable Navidrome returned a gzipped Subsonic error 40
    when asked with `Accept-Encoding: gzip`; the same refusal was plain JSON with `identity`.
    The account UI reached its authentication error, while the Android proof's literal scan of
    raw tapped bytes missed the compressed refusal. `serverAnsweredErrorCode` now requires a
    complete, bounded HTTP body, decodes chunking and gzip, then checks the actual Subsonic JSON
    error code. Persistent connections can contain several responses. Truncated, conflicting,
    corrupt or over-limit bodies cannot supply evidence; response headers cannot masquerade as
    an error envelope. The production transport and exact error-40 requirement stay intact.

60. **Restored cached rows do not prove that a replacement reader has finished reconnecting.**
    **OBSERVED 2026-10-29:** the Android Discover activity-recreation proof saw the restored
    query, cached first-page artists and a search request before the new reader finished its
    epoch/surface revalidation. Its subsequent pagination assertion timed out with only the
    first twenty artists present. Join the current reader's reconnect completion, wait for
    the cached-page notice to clear, and require the pagination action enabled before clicking.
    Reconnect schedules search revalidation; its completion alone need not await those responses.
    The proof then requires a new artist
    request with a cursor and scrolls the lazy list to artist twenty before checking its
    positive title. A row outside the lazy viewport need not exist in semantics. The disposable
    proxy records only the allowed kind and cursor presence, never query or cursor contents.

61. **A fresh account client may reuse an existing TCP connection.** **OBSERVED 2026-10-29:**
    Ktor 3.5.2's OkHttp engine derives clients from a static prototype and shares its connection
    pool. The relay's `hold()` parks new accepts, so an identity ping on an already forwarded
    socket bypasses that hold. Cutting an idle socket can instead make the next probe return
    unreachable without another accept. These are different observations from a held/refused
    request. Identity-sequencing proofs use the relay's fixture-only non-persistent mode, which
    requests `Connection: close`; controls require the disposable peer to close and preserve
    request/response bodies. They also finish any initial reconnect, because a new reconnect
    otherwise joins its screen revalidation after identity has finished. The playback proof
    still requires the exact held ping, suspended validation, refused ping and recovery without
    another press. Production pooling and retry policy are unchanged.

62. **A stream-cache writer factory can need playback Main; creation cannot hold the resource monitor.**
    **OBSERVED 2026-10-29:** durable Linux conformance receipts and two JVM captures found
    playback-service teardown waiting in `AndroidCachingPlaybackResource.close()`, while
    Media3’s loader held that resource monitor in `open()` and waited for
    `AndroidDownloads.controller()` to admit the writer on Main. Reserve the one creation
    attempt under the monitor, invoke the factory outside it, and publish only if the resource
    is still current. If teardown or an invalid range retires it while creation runs, abort
    the late writer exactly once without attaching it or promoting cache bytes. Regression
    controls require teardown to finish before the blocked factory is released and reject
    late writers after both reentrant teardown and range invalidation.


63. **Fixture admin trust lasts for the disposable hosted job VM.** **OBSERVED 2026-10-29:**
    hosted macOS successfully installed the fresh fixture CA with noninteractive root admin
    trust and executed the three native connection proofs. Both `remove-trusted-cert -d`
    and exact `delete-certificate -t` then exceeded their thirty-second bounds; plain
    exact-fingerprint certificate deletion completed. Apple hardcodes the admin trust
    authorization right, so changing the authorization database is not a cleanup strategy.
    The runner deletes only its exact fresh certificate from System.keychain in finally,
    without `-t`, and fails on command error or timeout. The residual admin trust record
    remains until GitHub decommissions the job VM; the helper does not claim earlier untrust.
    [GitHub documents a fresh VM per job and automatic decommission at job completion](https://docs.github.com/en/actions/how-tos/manage-runners/github-hosted-runners/use-github-hosted-runners).
    The unchanged guard requires macOS, `GITHUB_ACTIONS=true` and
    `RUNNER_ENVIRONMENT=github-hosted`; local/shared execution is refused before any trust
    command. The enclosing multipath fixture stops its listeners and deletes every fresh
    private key in finally. Mock controls exercise success, partial setup, timeout,
    cancellation, simulator reset and failed certificate cleanup without local trust writes.


64. **A phone Form can hide connected status until its rows are scrolled into view.**
    **OBSERVED 2026-10-29:** run40838293161 iPhone search failed its live-account readiness
    assertion after the prior My Library suite passed7/7. The app logged a reader opened in
    connected mode; CFNetwork recorded the first three account tasks completing HTTP200.
    Thirty-one Sign Out queries completed with empty results, unlike the unfinished AX query
    in run40826118582. Three retained hierarchies showed saved servers, the new Connections
    section and credentials, ending at local-HTTP consent below the phone viewport; the later
    status section was not realized in the native Form's AX tree. The helper now scrolls only
    the named Settings Form after confirming Connection, at most three upward gestures within
    its existing readiness budget. It still requires the actual Sign Out control and preserves
    visible failure controls; no endpoint shortcut, extra connection retry or longer timeout
    substitutes for that evidence. `tools/test-live-account-status-witness` extracts the real
    helpers: the old implementation fails the lazy-status positive control, and disconnected,
    failed, missing-Form and wrong-destination controls still refuse readiness. This is host
    control evidence; actual simulator execution remains the conformance leg's responsibility.


65. **Every independent UI readiness helper must reveal the lazy status it witnesses.**
    **OBSERVED 2026-10-29:** run40887985432 passed My Library7/7 and the corrected iPhone
    search readiness case, then Design Tour Home failed its separate `awaitConnection`.
    App PID67961 opened a reader in connected mode at15:32:35.298770UTC; thirty-seven
    completed Sign Out queries returned empty results. Its retained402x874 hierarchy ended
    at local-HTTP consent below the credentials, after the new Connections section; the later
    status row was not realized. This helper still queried globally before opening Connection
    and then waited without revealing the Form. It now opens Connection before reading status
    and makes at most three upward gestures on the named Settings Form inside its original
    ten- then thirty-second readiness budgets. It still requires the real Sign Out control,
    preserves visible failure actions and adds no authentication retry. The independent host
    controls extract this actual helper; simulator conformance remains the runtime evidence.


68. **Connection verification, explicit recovery and ownership initialization are different actions.**
    A saved HTTP address remains the ownership anchor after HTTPS adoption; Verify reads the
    authenticated directory through an admitted, nonremoved HTTPS route. With the selected route
    removed, verification can refresh metadata but does not select an alternate automatically.
    The Connections surfaces keep HTTPS Add/re-add available. An explicit Add authenticates an
    admitted directory source, checks that the chosen target is listed, then performs secret-free
    TLS/lineage admission before sending credentials there. If all routes were removed, only an
    explicitly chosen historical HTTPS route may bootstrap recovery, with the same TLS and
    principal checks. The transaction clears only that chosen tombstone; refresh preserves all
    removals. Neither action changes the saved anchor, media epoch or owned rows.
    A fresh Android credential save establishes its initial anchored nonce before automatic
    enrollment, only when accepted identity is the namespace's sole row. It refuses existing
    ownership instead of rotating an epoch or invoking destructive binding. First-sign-in tests
    use the real credential store and SQLite without a preseeded epoch or media controller.
