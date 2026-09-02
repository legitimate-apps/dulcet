# Dulcet — how to work in this repo

This file contains the repository-specific working agreement for Dulcet.

**Phase 0 scaffold exists.** It establishes the Kotlin Multiplatform targets, native Apple shell
targets, CI baseline, licence audit, and parity gate. No OpenSubsonic production behavior is
implemented yet.

## Read order

1. **`CORPUS.md`** — what Dulcet is, the settled decisions, the lines we never cross. Read it every
   session, including after a compaction.
2. **This file** — how to work here.
3. **`docs/superpowers/specs/2026-08-18-dulcet-design.md`** — the full design. Read the section you are
   about to touch, not the whole thing. **§28 is the revision record**: it lists the eight foundation
   contracts revision 1 got wrong. Read it before re-proposing anything that sounds simpler.
4. **`FEATURES.yml`** — what actually works where, and the unit of work (once it exists).

Do not re-derive the architecture. If you believe the spec is wrong, say so and change the spec in the
same session — never work around it silently.

## 🚨 Everything in this repo is PUBLIC — read this before writing a line of it

`CORPUS.md`, this file, and everything under `docs/` ship in a public repository. So:

- **This repo describes THIS project only.** Private context — your global agent instructions,
  internal doctrine, machine setup, tooling, other projects, other identities — is to be **followed**,
  never **described**, quoted, paraphrased, or alluded to here.
- **Never write a forbidden value down in order to forbid it.** A prohibition that names its target
  publishes its target. State the rule positively instead: say what this project *does* use, and add
  "never anything else."
- **The tell, before you write any justification:** *am I explaining WHY using knowledge a reader of
  this repository does not have?* If yes, keep the rule and rewrite the justification so it stands on
  public evidence.
- This has already gone wrong twice in these files. Both times it arrived disguised as a helpful
  warning, which is the most persuasive possible carrier for a leak. See spec §28, revision 5.

## Keep the docs living

When you discover anything that extends or contradicts `CORPUS.md` or the design spec — a server
behavior, a toolchain trap, a changed API — update the doc in the same session and write or update the
matching file in the project memory dir. Memory holds the deep detail; `CORPUS.md` holds the map. A
spec claim that turns out to be wrong is corrected in place and recorded in §28, not appended to.

Every technical claim in the spec is marked **OBSERVED** (verified against a named primary source) or
**ASSUMED**. Preserve those markers. Do not promote ASSUMED to OBSERVED without naming what you
measured — several CONF tests exist precisely to do that promotion.

## Identity — binding, no exceptions

- Repo: `legitimate-apps/dulcet`. Push via the **`github-legit`** SSH alias.
- **Commit as `legitimate-apps`, per-command, never global git config:**
  ```
  git -c user.name='legitimate-apps' \
      -c user.email='309192374+legitimate-apps@users.noreply.github.com' commit -m "..."
  ```
- Apple signing: **Legitimate LLC, team 3LTL47SJ8C**. Bundle IDs under
  **`com.legitimateapps.dulcet`** — decided, and **deliberately independent of the marketing domain**,
  because a bundle identifier is immutable once an App Store Connect record exists and must not be
  hostage to a domain that could lapse. It matches the namespace already used by the LLC's published
  applications on a domain the LLC already owns. Android `applicationId` is the same value.
- 🚨 **Never publish this app under any other namespace, and never reuse a namespace from an unrelated
  project that happens to be present in a local build environment.** A bundle id is the most permanent
  place such a mistake can land.
- The marketing domain is **`getdulcet.com` — chosen but not yet purchased** (spec OQ-7). It reaches
  only support/privacy URLs and marketing, and **gates nothing** — not the signing dry run, not an App
  Store Connect record. `${DOMAIN}` and `${BUNDLE_PREFIX}` are defined in exactly one place, the spec's
  header block; never hard-code either value anywhere else.
- Public-safe: `legitimate-apps`, `Legitimate LLC`, the team id, the `com.legitimateapps.*` namespace, and
  the product domain once purchased.
- **Never anywhere public** — including code comments, plists, commit metadata, listing copy, DNS and
  cert metadata: the maintainer's legal name, home address, system username, and any prior online
  handle. **The literal values are deliberately not written here** — this file ships in the repo, so
  naming them would publish exactly what the rule forbids. Check against your own agent instructions,
  never against a copy committed here.
  Practical consequence: scrub absolute home-directory paths out of anything committed (build logs,
  `.xcodeproj` derived paths, README snippets) — the system username rides in every one of them.
- Never sign in to a Dulcet-related service with Google or GitHub SSO from a browser logged in as a
  different identity; it links the accounts silently.

## Commands

```sh
./gradlew :core:allMetadataJar :core:jvmTest :core:testAndroidHostTest :core:bundleAndroidMainAar :core:licensee
python3 tools/parity_gate.py
python3 tools/verify_ci_policy.py
python3 tools/verify_os_floors.py --configuration-only
```

**Xcode builds are not hermetic.** They invoke Gradle through a Run Script phase, so the pinned JDK
and Gradle wrapper must be present on any build machine. Apple compilation and binary floor evidence
run in `apple-ci` on the pinned hosted image.

## Release channels — DEV and PROD (spec §22)

| | DEV | PROD |
|---|---|---|
| trigger | every merge to `main`, automatic | a hand-cut `vX.Y.Z` tag, never automatic |
| bundle id | `com.legitimateapps.dulcet.dev` | `com.legitimateapps.dulcet` |
| display name | **Dulcet DEV** (distinct icon) | **Dulcet** |
| TestFlight | **internal** testers, no Beta App Review, minutes | **external** group, Beta App Review, slower **by design** |
| expectation | expected to break — that is the point | someone else relies on it |

- 🚨 **The `.dev` App Store Connect record must NEVER be submitted for App Store release** — not "not
  yet", never, for the life of the project. It is a TestFlight-only artifact carrying development
  logging and possibly a preconfigured server.
- 🚨 **PROD ships NO preconfigured server URL.** A hardcoded internal address in a published binary
  leaks a private address to everyone who downloads the app and is broken for every user who is not the
  person who hardcoded it. The DEV target may ship one for convenience; the build configuration must
  make it **structurally impossible** for PROD to compile that value in — not a thing someone remembers.
- **Cutting PROD is gated**: CI green, conformance suite passing, `FEATURES.yml` showing no undeclared
  regression. A tag failing any of those is deleted and re-cut, never shipped with a note.
- **Only these settings differ per channel**: bundle id, display name, icon, logging verbosity,
  diagnostics visibility, preconfigured server. Everything correctness-relevant is identical — a DEV
  build that behaves differently because of a build flag is not dogfooding, it is a different program.
- **Dogfooding vs testing — the line is automation, not the server.** Automated runs (CI, conformance,
  any test suite, a new sync generation's first pass) target the **local disposable** Navidrome, always.
  A person manually using a DEV build against their own real library is **expected** and is why DEV
  exists. **Automated writes to a personal or production instance are forbidden in every case.**

## CI split — binding

**This repo is PUBLIC, and that changes the CI economics. Do not apply the global "never hosted
`macos-*`, 10x multiplier" policy here — it is scoped to private repositories.**

- **Apple builds (macOS/iOS/iPadOS/tvOS: build, simulator tests, archive) run on GitHub-hosted
  `macos-latest`.** **OBSERVED 2026-08-18:** GitHub states "Standard GitHub-hosted or self-hosted
  runner usage on public repositories will remain free"
  (https://github.com/resources/insights/2026-pricing-changes-for-github-actions), with the only
  carve-out being "The larger runners are not free for public repositories"
  (https://docs.github.com/en/billing/reference/actions-minute-multipliers). **There is no self-hosted
  runner in this project** and none should be created — see spec §21.3, where OQ-1 is closed.
- **Never request a larger or premium macOS runner label.** Those are billed even on public repos and
  included minutes cannot be applied to them. `runs-on` for Apple jobs is `macos-latest` (or a pinned
  `macos-<version>` when the toolchain matrix demands it) and nothing else. This is the one place in
  CI where real money can appear, and it fails as a bill, not as a red build.
- **Keep the Apple matrix narrow.** Hosted macOS concurrency is capped well below Linux, so a wide
  matrix queues rather than fans out and makes CI slower. One job per destination that genuinely needs
  its own; no cross-product over configurations or OS versions. Widening it is a spec change (§21.1).
- **Kotlin core, Android, lint, the conformance suite and the parity gate run on `ubuntu-latest`.**
  GitHub Actions **service containers require a Linux runner**, so the `services:`-based Navidrome
  cannot run in the Apple job — which is why the Apple leg runs Navidrome as a **native pinned binary**
  instead (spec §20.3).
- Every workflow: `concurrency: cancel-in-progress` + per-job `timeout-minutes`. On a capped hosted
  pool these are **queue hygiene** — a superseded or hung job holds a macOS slot away from the run that
  replaced it.
- Release signing material lives in Actions secrets scoped to a manual-approval environment, and
  `release.yml` is `workflow_dispatch`-only. Fork PRs cannot read secrets at all.

## Building locally

CI is entirely hosted, so nothing here depends on a particular workstation. A standard Xcode and
JDK/Gradle setup builds every target. Two Apple-toolchain failure modes are worth knowing because they
present as something else:

- **A wedged CoreSimulator hangs every Xcode build with no error output** — including device and
  archive builds — freezing at `CompileAssetCatalogVariant`. It looks like a corrupt asset catalog and
  is not. `xcrun simctl list devicetypes | head` returning nothing is the tell; run it before any
  archive.
- **`xcodebuild test` clones the destination simulator by default.** Where cloning is unavailable the
  clone fails *after* a successful build, so the run reads as a test failure when no test executed.
  `-parallel-testing-enabled NO` is the fix.

Concurrent simulator and Xcode builds are memory-hungry enough to trigger OOM kills on a machine doing
anything else; serialise them rather than fanning out locally.

**If you are working on a shared or managed build machine, follow that machine's own operational rules.
They are deliberately not reproduced in this repository.**

## Traps that will cost real time — read before touching the relevant subsystem

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
18. **Sync has no change token, and offset paging is not a snapshot.** Dedupe cannot recover an omitted
    row. Consistency comes from row versioning with reads pinned to a committed generation, one atomic
    commit, and a stability witness with bounded retries (spec §16.3–§16.4). Bounded concurrency 4.
19. **The freshness pass is a heuristic, not incremental sync** — it cannot see a tag change that
    preserves count and duration. Do not describe it as incremental sync in UI copy.
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

## Review and delegation

**Architecture decisions and verification stay with the maintainer; implementation of a
`FEATURES.yml` row is delegable.**

**Every change gets an independent adversarial review before merge**, and the reviewer is asked to
check the **commit message and comments against the code**, not only the code — nothing verifies a
sentence, so claims about ordering, recoverability and impossibility rot first, and a wrong comment
does not merely fail, it stops the check that would have caught the failure. Reviewing your own diff
is the weakest check available. This is not ceremony: revision 2 of the spec exists because such a pass
found eight wrong foundation contracts in revision 1, and revision 5 exists because one caught a
disclosure defect in revision 4.

A work brief is one `FEATURES.yml` row: *"Implement row `<id>` on `<platform>`. Contract: spec
§`<n>`. Must satisfy `<CONF-ids>`. When done, move the cell to `shipped` and fill `evidence` with one
workflow, job and test identity per declared CONF id."* Batch the whole ask into one brief rather than trickling
follow-ups — a reviewer or implementer who sees the whole job decides coherently across it, where
isolated asks produce locally-sensible answers that do not fit together.

### Merging

**What `main` actually enforces — OBSERVED 2026-08-26, read from
`GET /repos/{owner}/{repo}/branches/main/protection` with an admin token:**

| setting | live value |
|---|---|
| required status checks | `core-ci`, `parity-gate`, `apple-ci` |
| `strict` (branch must be up to date with `main`) | **true** |
| `required_pull_request_reviews` | **null — no review is required** |
| `enforce_admins` | **true** — admin bypass is off |
| force pushes to `main` | disabled |

🚨 **Correction, 2026-08-26.** This section previously stated that `main` "requires a code-owner
review" and that "an approval must post-date the last push by a different actor." **Neither is
enforced.** `required_pull_request_reviews` is null, so `CODEOWNERS` advertises ownership and
requests reviewers — it gates nothing. A pull request showing an empty `reviewDecision` and
`mergeStateStatus: BLOCKED` is blocked on **status checks alone**; reading that as a review deadlock
sends you looking for a second approving account that the branch never asked for. Re-measure before
re-asserting either claim.

**`strict: true` is the setting that shapes day-to-day work.** Every pull request must be rebased or
updated onto the current `main` before it can merge, and `apple-ci` is the slow leg. With more than
one pull request in flight this **serialises**: merging one invalidates the others' up-to-date
status and each must re-run. Land them deliberately in dependency order, and tell the other branches
when `main` moves so they rebase once instead of twice.

**History, so nobody re-derives the two-account dance.** There really was a wall here: on 2026-08-20
an approving review from a pull request's own author was refused with
`422 Unprocessable Entity — "Review Can not approve your own pull request"`, and the workaround was
to have one maintainer account open the pull request and the other approve it. That cost real time
and is worth remembering — but it was a workaround for a **required review that is no longer
configured**, so the dance is obsolete, not merely optional. If you meet the 422 again you have gone
looking for an approval nothing asked you for. **Do not "fix" a blocked pull request by enabling
required reviews.**

**Which account opens matters, and not only for the review.** GitHub attributes a **squash-merge**
commit to the *pull request's* author, not to the commit author, and it uses that account's **profile
email** — the `noreply` address only when the account has email privacy enabled. **Open pull requests
as `legitimate-apps`.**

🚨 **Corrected 2026-09-01. This previously said the exposure "happened once, commit `0e3566e`". That
is wrong, and understating it is what let it persist.** Measured on `origin/main` with
`git log --format='%ae'`: **31 of the last 40 commits** carry the opening account's profile address
rather than its `noreply` one, spanning **2026-08-26 to 2026-09-01** — that is *every squash merge in
the repository's active history*, not an isolated slip. Our own direct commits are unaffected and
correctly carry the `noreply` address, which is why the per-command identity convention in *Identity*
looked like it was working.

**The convention cannot fix this, and that is the point.** A squash merge does not preserve the
authorship of the commits it squashes — GitHub synthesises a new commit and sets the author itself.
So no amount of care at `git commit` time changes the result. The two things that do are enabling
email privacy on the opening account, or merging with **rebase** (which replays the original commits
with their original authorship) instead of squash. Both are repository-policy decisions; neither is
something a commit-time convention can substitute for.

✅ **Rebase-merge is CONFIRMED to fix it — OBSERVED 2026-09-01 on `main`.** PR #35 was a single
commit already authored `legitimate-apps <...noreply...>`; merging it with **rebase** replayed that
commit unchanged, so `main` gained a commit carrying the `noreply` address, directly above two
squash-merged commits carrying the profile address:

```
f254e2e  Promote account.connect on Android …   309192374+legitimate-apps@users.noreply.github.com   <- rebase
c98581b  Implement the six account-connect …    <profile address>                                    <- squash
074691f  Implement the download policy core …   <profile address>                                    <- squash
```

For a single-commit pull request, rebase produces the **same one-commit history shape** as squash
with none of the exposure, so there is no trade-off to weigh in that case. A multi-commit branch is a
genuine judgement call between history hygiene and exposure, and remains one.

**Re-measure before restating any figure here.** The count above is dated because it grows with every
merge, and the previous version of this paragraph was accurate when written and wrong within days.

Pull-request authorship and commit authorship are separate fields. Commits are authored
`legitimate-apps` by the convention in *Identity* above; that is an instruction here, not something
GitHub enforces.

`@legitimate-apps` is a GitHub **User** account, not an Organization, so there are no teams —
`CODEOWNERS` entries must resolve to individual collaborators while ownership stays as it is.

⚠️ **Nothing in this repository's configuration provides independent review.** Branch protection
requires no approval at all, and ownership sits with a single maintainer. The adversarial review
demanded above is therefore an obligation the maintainer owes the code, not something the merge
button verifies — a green pull request means the checks passed and nothing more.

## Definition of done

A feature is done when the real trigger has been driven to the real observed effect on a real device or
simulator, and the `FEATURES.yml` cell carries evidence at the granularity of the claim — a core unit
test does not evidence a platform UI capability, and an iPhone run does not evidence iPad layout. Trace
each link **OBSERVED** or **ASSUMED**; never claim "verified" or "end-to-end" while any link is assumed
— name the assumed ones instead. A delegate's report is not evidence.
