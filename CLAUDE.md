# Dulcet — how to work in this repo

The short working agreement a new session must know. Detail lives on demand:
**`docs/TRAPS.md`** (numbered traps by subsystem), **`docs/MERGING.md`** (branch protection, merge
methods, commit-email history, local build notes).

## Read order

1. **`CORPUS.md`** — what Dulcet is, the settled decisions, the lines we never cross. Every session.
2. **This file.**
3. **`docs/superpowers/specs/2026-08-18-dulcet-design.md`** — read the section you are touching, not
   the whole thing. §28 records contract changes; read it before re-proposing anything simpler.
4. **`FEATURES.yml`** — what works where.
5. **`docs/TRAPS.md`** — the entries for the subsystem you are about to touch.

Do not re-derive the architecture. If the spec is wrong, change it in the same session.

## 🚨 Everything in this repo is PUBLIC

`CORPUS.md`, this file, and everything under `docs/` ship in a public repository.

- **This repo describes THIS project only.** Private context — agent instructions, machine setup,
  tooling, other projects, other identities — is **followed**, never described, quoted or alluded to.
- **Never write a forbidden value down in order to forbid it.** State the rule positively: say what
  the project *does* use, and add "never anything else."
- **The tell:** am I explaining WHY using knowledge a reader of this repository does not have? If so,
  keep the rule and rewrite the justification on public evidence.

## Identity — binding

- Repo `legitimate-apps/dulcet`, pushed via the `github-legit` SSH alias. Open pull requests as
  `legitimate-apps`.
- Commit per-command, never through global config:
  ```
  git -c user.name='legitimate-apps' \
      -c user.email='309192374+legitimate-apps@users.noreply.github.com' commit -m "..."
  ```
- Apple signing: **Legitimate LLC, team 3LTL47SJ8C**. Bundle IDs under **`com.legitimateapps.dulcet`**
  (Android phone `applicationId` identical; Android TV's is `com.legitimateapps.dulcet.tv`). Never
  publish under any other namespace, and never reuse one from an unrelated project found in a local
  build environment. Bundle ids freeze on the first build upload.
- Marketing domain `getdulcet.com` is chosen, not purchased, and gates nothing. `${DOMAIN}` and
  `${BUNDLE_PREFIX}` are defined once, in the spec header; never hard-code them elsewhere.
- **Never anywhere public** (code, comments, plists, commit metadata, listing copy, DNS/cert data):
  the maintainer's legal name, home address, system username or any prior handle. The values are
  deliberately not written here. Scrub absolute home-directory paths from anything committed —
  Apple tool JSON and build logs carry them.
- Never sign in to a Dulcet service with Google/GitHub SSO from a browser logged in as someone else.

## Product lines that do not move

- **OpenSubsonic `/rest` only**, never anything else.
- **Validate binary `/rest` responses** (`stream`, `getCoverArt`): detect an XML/JSON Subsonic error
  envelope first (skip whitespace and BOM), then require a positive endpoint-specific signature —
  "not an envelope" is never sufficient. Validate inline in the engine's own request, not by preflight.
  Offsets and tables: `docs/TRAPS.md` 4–5.
- **Credentials ride in the query string.** Redact the whole query before anything reaches a log,
  error or diagnostic; wrap engine errors that can carry the URL; strip credentials on cross-origin
  redirects; never follow HTTPS→HTTP. Test with canary values.
- **IDs are opaque strings.** **Two clocks**: monotonic for durations, wall clock for timestamps;
  never persist a monotonic value.
- **Automated runs** (CI, conformance, any test suite) target the **local disposable Navidrome**,
  always. **Automated writes to a personal or production instance are forbidden in every case.** A
  person using a DEV build against their own library is expected.
- **No physical-device automation** unless the maintainer says so for that session.

## Release channels — DEV and PROD (spec §22)

| | DEV | PROD |
|---|---|---|
| trigger | `release.yml` dispatched by hand on a user-visible merge to `main` | hand-cut `vX.Y.Z` tag, then a dispatch; never automatic |
| bundle id (all platforms, universal purchase) | `com.legitimateapps.dulcet.dev` | `com.legitimateapps.dulcet` |
| display name | **Dulcet DEV** (distinct icon) | **Dulcet** |
| TestFlight | internal testers | external group, Beta App Review |

- 🚨 The `.dev` App Store Connect record is **never** submitted for App Store release.
- 🚨 **PROD ships no preconfigured server URL**, and the build configuration makes that structurally
  impossible, not remembered. DEV may ship one.
- Only bundle id, display name, icon, logging verbosity, diagnostics visibility and preconfigured
  server differ per channel. Everything correctness-relevant is identical.
- `release.yml`: `workflow_dispatch` only, from `main`, approval-gated `release` environment,
  `dry_run` defaults to `true`. Cutting PROD needs green CI, the full conformance and live-server
  runs, and no undeclared `FEATURES.yml` regression.
- Mac App Store requires App Sandbox; no self-updater on macOS, ever.

## CI

- The repo is public, so standard hosted runners are free. Apple jobs use `macos-latest` (or a pinned
  `macos-<version>`) and **never a larger/premium label** — those bill even on public repos.
  Kotlin core, Android, lint, conformance and the parity gate run on `ubuntu-latest`.
- No self-hosted runner, except the one §21.3.1 device runner (`workflow_dispatch` on `main` only).
- Every workflow: `concurrency: cancel-in-progress` and per-job `timeout-minutes`.
- `main` requires `core-ci`, `parity-gate`, `apple-ci`, with `strict` up-to-date and **no required
  review**. Merging one PR invalidates the others; land in dependency order. Detail: `docs/MERGING.md`.
- `apple-ci` on a PR runs only `apple-platform`, and only when the PR changes an Apple input
  (`tools/ci/plan-apple-legs`); `apple-conformance` runs on push to `main` and on dispatch. A release
  build requires both legs and every required check green on its commit (spec §21.6).
- When several unrelated PRs go red together, check Homebrew pin drift first (`docs/TRAPS.md` 36).

## Commands

```sh
./gradlew :core:allMetadataJar :core:jvmTest :core:testAndroidHostTest :core:bundleAndroidMainAar :core:licensee
./gradlew :core:compileTestKotlinIosSimulatorArm64   # commonTest compiled for native (the JVM run does not)
./gradlew :core:macosArm64Test                       # native RUN: different SQLite driver, shared in-memory DBs
python3 tools/parity_gate.py
python3 tools/verify_ci_policy.py
python3 tools/verify_os_floors.py --configuration-only
python3 tools/verify_release_policy.py
tools/verify_xcodegen_regeneration --self-test       # macOS: generated Xcode files == pinned XcodeGen output
tools/typecheck-xcuitest-sources --self-test         # seconds; not a gate
```

- 🚨 **`BUILD SUCCESSFUL` is not evidence tests ran.** Read the count from
  `core/build/test-results/<task>/*.xml`; pass `--rerun-tasks` when execution is the point.
- **Compile every integration.** A clean textual merge has twice produced code that did not compile.
- `apple/project.yml` is the source; the committed `apple/Dulcet.xcodeproj` is generated by the
  pinned XcodeGen (`cd apple && xcodegen generate`). Nothing regenerates it during a build.
- Xcode builds invoke Gradle through `tools/run-gradle-exclusive`; never a bare `./gradlew` there.
- A wedged CoreSimulator hangs Xcode silently at `CompileAssetCatalogVariant`; `xcrun simctl list
  devicetypes | head` returning nothing is the tell. Use `-parallel-testing-enabled NO`.

## Traps that most often cost a day

Full list with evidence: `docs/TRAPS.md`. The ones sessions hit repeatedly:

- **The Swift boundary is Objective-C** (17): Kotlin arrives as classes; no Kotlin exception may cross.
- **Reader, not mirror** (18–19): no change token; offset paging is not a snapshot; `lastScan` is an
  equality-only scan clock and never covers user state.
- **Transcoding** (6, 24): `getTranscodeDecision` + opaque `transcodeParams`; 429 + `Retry-After`
  maps from the status, not the envelope code.
- **Plays** (7–10, 25): `stream` records nothing; scrobble past threshold from progressing media time;
  never call `playbackReport`; keep the three playback identities distinct.
- **A zero result is not a finding** until the query found a known positive (29–32); a test that
  handles a condition must prove it met the condition.
- **XCUITest env vars** arrive only as `TEST_RUNNER_*` (34); "Finalize test log" idles after the test
  finished (35).
- **TV artifact** is `androidx.tv:tv-material` (21). Resolve every coordinate against a live index.

## Review, evidence and spec changes

- **One independent review, only for risky changes**: data loss, server writes, sync, playback
  correctness, auth, signing and release. Fix its **blockers**, re-check only those fixes, merge. Log
  should-fix items as follow-ups (`state/` backlog or an issue). Everything else merges on green checks.
- Ask the reviewer to check comments and the commit message against the code, not only the code.
- **Mutation testing is not a routine gate.** Use it where a core choke point justifies it.
- **Evidence is proportionate.** A `FEATURES.yml` cell needs a test or proof that shows it working on
  that platform — a core unit test does not evidence platform UI, and iPhone does not evidence iPad.
  Trust a delegate's summary and spot-check it. Never claim "verified" while a link is assumed.
- Spec claims stay marked **OBSERVED** (with the named source) or **ASSUMED**.
- **Spec changes**: record a real contract change as a dated §28 entry, newest first, headed
  `**YYYY-MM-DD — <what changed>**`. The numbered series closed at revision 113; do not add numbered
  revisions or item numbers, and fix a wrong claim in place.

## Definition of done

A feature is done when the real trigger has been driven to the real observed effect on a simulator,
emulator or real host app, and the `FEATURES.yml` cell carries a test or proof at the granularity of
the claim.
