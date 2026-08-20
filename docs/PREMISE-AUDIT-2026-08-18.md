# Premise Audit — Dulcet design spec revision 2

**Audited:** `docs/superpowers/specs/2026-08-18-dulcet-design.md` (rev 2), `CORPUS.md`, `CLAUDE.md`
**Date:** 2026-08-17/18 · **Auditor:** independent read-only review pass — **no spec files were edited**

> ⚠️ **The spec was being revised by another agent while this audit ran.** Findings were developed
> against the text as read at the start of the pass and then **re-checked against the 23:47
> revision** before publication. Where the concurrent revision already fixed something it is marked
> **RESOLVED**; everything else was confirmed still present. Two items moved location without
> changing substance (C2) or changed form without changing the defect (C4) — read those carefully
> rather than assuming they are stale.

**Why this audit exists.** The brief that produced this spec carried at least one confidently-wrong
premise inherited from an auto-loading policy document. It was found. There are more, and the
largest one is from the same family.

**Method.** Primary sources only: GitHub Docs, Apple Developer documentation and the App Store
Review Guidelines, opensubsonic.netlify.app, subsonic.org, navidrome.org, kotlinlang.org, ktor.io,
sqldelight.github.io, developer.android.com, and **Navidrome's own source read at tag `v0.63.2`**
via the GitHub API. Plus live artifact probes against Google Maven and Maven Central, and local
probes of this Mac's keychain, the `actions/runner-images` manifests, RDAP, and the iTunes Search
API.

---

## 1 · CONTRADICTED

### C1 — 🔴 The 10x macOS multiplier does not apply to a public repo, and OQ-1 is mostly an artifact of that error

**Spec §21.1, verbatim:**

> **Binding operator policy:** Apple builds run on the **self-hosted macOS runner** — GitHub-hosted
> `macos-*` runners bill at a **10x multiplier** and were about 92% of another project's Actions
> spend.

Repeated in `CORPUS.md` §4.13 ("Hosted macOS bills at 10x") and in `CLAUDE.md` ("Never on
GitHub-hosted `macos-*`").

**GitHub, verbatim** (https://docs.github.com/en/billing/concepts/product-billing/github-actions):

> "GitHub Actions usage is **free** for **self-hosted runners** and for **public repositories** that
> use standard GitHub-hosted runners."
>
> "Larger runners are always charged for, even when used by public repositories or when you have
> quota available from your plan."

The multiplier is real — but only for **private** repos. Current per-minute rates from the same
page: macOS 3/4-core **$0.062**, Linux 2-core x64 **$0.006** — a ratio of ~10.3x. So the number was
right and the **scope was wrong**, exactly as with the original planted error.

**Dulcet is specified as a public open-source repo** (§1 "open-source", §21.3, §24 Phase 3 "repo
goes public"). On a public repo, `macos-latest` costs nothing.

**Supporting probes — the hosted runner can actually do the whole Apple job:**

| checked | result | source |
|---|---|---|
| `macos-latest` → | `macos-15`, **arm64 (M1)**, 3 vCPU / 7 GB | GitHub hosted-runners reference |
| Apple Silicon? | yes — `macos-latest/14/15/26` are arm64; only `macos-*-intel` is x64 | same |
| JDK present? | **yes** — Temurin 11 / 17 / **21 (default)** / 25 | `actions/runner-images` `macos-26-arm64-Readme.md` |
| Gradle present? | **yes — 9.6.1** | same |
| Xcode | 26.6 default on `macos-26` (matches this Mac's 26.6) | same |
| iOS + tvOS SDKs and simulators? | **yes**, iOS 26.0–26.5 and tvOS 26.0–26.5, device + simulator | same |
| Docker? | **no** — zero occurrences in either macOS image readme | same |

So §4.3's "GitHub's macOS runner images ship both [a JDK and Gradle]" is **confirmed**, and the
whole `apple-ci.yml` job set (macOS build, iOS sim, iPad sim, tvOS sim) runs on a hosted runner for
$0 on a public repo.

**Blast radius.** OQ-1 is currently the spec's flagship open question. It forces the repo private
through Phase 2, contorts §21.3 into two unpleasant options, gates Phase 3, and is repeated as a
binding rule in both companion documents. Almost all of it exists to protect a cost that is zero.

**Fix:**
1. Rescope the multiplier claim in §21.1, `CORPUS.md` §4.13 and `CLAUDE.md` to *private repos*.
2. Move `apple-ci.yml` to `macos-latest`. This is strictly better than the self-hosted runner for a
   public repo: it is free, it is isolated, and it removes the fork-PR-executes-on-the-operator's-
   workstation risk that §21.3 correctly identifies but then has to mitigate with review.
3. Shrink OQ-1 to its real residue: **where does release signing run?** `release.yml` needs signing
   identities and stays `workflow_dispatch`-only on the Mac (or is run locally). That is a one-line
   decision, not a repo-visibility strategy.

**Two constraints that survive and must be carried into the rewrite:**

- **macOS job concurrency is capped at 5** on Free/Pro/Team plans, "shared across standard
  GitHub-hosted runners and GitHub-hosted larger runners" (GitHub Actions limits reference).
  `apple-ci.yml` as specified names macOS build + iOS sim + iPad sim + tvOS sim + `macosArm64Test`
  conformance + parser-parity + wire-pathology + header diff. If those are separate jobs they will
  queue behind the cap.
- **§21.3's security reasoning is correct and should be kept**, just no longer load-bearing. GitHub:
  "Self-hosted runners should almost never be used for public repositories… Forks of your public
  repository can potentially run dangerous code on your self-hosted runner machine by creating a
  pull request that executes the code in a workflow."

---

### C2 — 🔴 The bundle identifier freezes on first **build upload**, not on app-record creation

**Still present in the 23:47 revision — it moved and is now asserted twice**, verbatim:

> "A bundle identifier is **immutable once an App Store Connect record exists**, so tying it to a
> marketing domain would make a permanent identifier hostage to a name that can lapse…"
>
> "🚨 **The identifier rule, stated positively:** the bundle identifier prefix is
> `com.legitimateapps.dulcet`. **It is immutable once an App Store Connect record exists.**"

(The earlier phrasing — "a bundle identifier cannot be changed once an App Store Connect app record
exists… **Consequence that makes OQ-7 a hard Phase-2 gate**" — is gone, but the underlying claim was
carried forward unchanged into the replacement text.)

**Apple, verbatim** (App Store Connect help → App Information):

> "A unique identifier for your app that is used throughout the system… **You can't change this
> property after you upload a build.**"

Corroborating, on deletion and reuse:

> "You cannot delete an explicit App ID for an app you uploaded to App Store Connect." *(Manage
> identifiers → Delete an App ID)*
>
> "…the SKU can't be reused in the same organization and **if you've uploaded a build, your bundle
> ID can't be reused**." *(Create an app record → Remove an app)*

**Why it matters.** The gate is the first upload, so a record with no build is fully reversible —
bundle ID still editable, App ID still deletable, record still removable. The revision's new
architecture (§22.2's two records, one per channel, `${BUNDLE_PREFIX}.dev` and `${BUNDLE_PREFIX}`)
makes this *more* relevant, not less: **two** records now get created, and the team will want to
iterate on both before either receives a build.

**Fix:** restate as "**immutable once a build has been uploaded**", and note that App Store Connect
records, identifiers and provisioning profiles may be created and revised freely before that point.
The correction makes the spec's own position stronger — the identifier decision is well-founded on
its merits (LLC-owned namespace, decoupled from a lapsing marketing domain) and does not need an
overstated irreversibility claim propping it up.

---

### C3 — 🔴 `getTranscodeStream` uses **standard HTTP error codes**, and Navidrome returns **HTTP 429 + `Retry-After`** for transcode backpressure. The spec's error model has no representation for either.

**Spec §12.4, verbatim:**

> **ASSUMED (defensive):** that such an error arrives with HTTP 200 is the compatibility-defensive
> assumption we design for

and `CORPUS.md` line 4.2: "There is no 'only sniff when the content type looks wrong' path."

**OpenSubsonic, verbatim** (https://opensubsonic.netlify.app/docs/endpoints/gettranscodestream/):

> "The raw transcoded media stream. **In case of an error, a standard HTTP error code is returned
> with a descriptive message.**"

That is a *different* error convention from legacy `stream`, which subsonic.org documents as
"binary data on success, or an XML document on error (in which case the HTTP content type will start
with `text/xml`)". Path A and Path B do not fail the same way, and §12.4 treats them uniformly.

**Worse — the reference server has a transcode concurrency limiter.** Navidrome
`core/stream/limiter.go` at tag **v0.63.2**, verbatim:

```go
// ErrTooManyTranscodes is returned by TranscodeLimiter.Acquire when the
// configured concurrency cap has been reached. Callers should translate this
// into an HTTP 429 response so well-behaved clients back off and retry.
var ErrTooManyTranscodes = errors.New("too many concurrent transcodes")

// RetryAfterSeconds is the value returned in the HTTP Retry-After header when
// a request is rejected with ErrTooManyTranscodes.
const RetryAfterSeconds = 5
```

It enforces "both a global cap… and an optional **per-user cap**". And `server/subsonic/api.go`:

```go
case errors.Is(err, stream.ErrTooManyTranscodes):
    err = newError(responses.ErrorGeneric, "too many concurrent transcodes, please retry shortly")
...
w.Header().Set("Retry-After", strconv.Itoa(stream.RetryAfterSeconds))
sendResponseWithStatus(w, r, errorResponse(err), http.StatusTooManyRequests)
```

So the wire reality is: **HTTP 429, with an envelope body, carrying Subsonic error code 0
(`ErrorGeneric`)**.

**Four consequences, all load-bearing:**

1. **§18.12 loses the signal entirely.** The sealed hierarchy has no rate-limit / backpressure class,
   so a 429 maps to `Server.Known(0, message, redactedUrl)` — "a generic error". The most
   retryable failure the reference server produces is indistinguishable from the least.
2. **§12.2 "Retries are owned by the **core**"** has no `Retry-After` handling anywhere.
3. **§12.8 `preloadNext` actively causes this failure.** Preload resolves *and validates* the next
   plan while the current one plays — that is two concurrent transcodes per client. With a per-user
   cap of 1, gapless preload is self-defeating.
4. **§14.5 batch downloads of transcoded files can starve playback** through the same global cap.

**Fix:** add `Server.Busy(retryAfter: Duration)` to §18.12; honour `Retry-After` in the core's retry
policy; budget transcode concurrency per server across playback + preload + downloads; add a CONF
test that drives the limiter. Also split §12.4's error-detection table per delivery path: envelope-
at-200 for legacy `stream`, HTTP status for `getTranscodeStream`, and **envelope-at-non-200** as a
third real case the spec currently does not admit.

---

### C4 — 🔴 There is no `androidx.tv:tv-material3` artifact. The Android TV module names a package and never a coordinate, and the coordinate it implies is a 404.

**Spec §3, still present in the 23:47 revision, verbatim:**

> | Android TV | Compose + **`androidx.tv.material3`** | `android/tv` | Phase 5 |

`androidx.tv.material3` is the **Kotlin package**, and it is correct. The spec never names a **Gradle
coordinate** anywhere — §4.4 defers that to the toolchain matrix as "Compose/Compose-TV". The
coordinate a reader naturally infers from the package name is `androidx.tv:tv-material3`, and it does
not exist.

**Live probe of Google Maven, 2026-08-17, with a positive control:**

| coordinate | result |
|---|---|
| `androidx.tv:tv-material3` | **HTTP 404** |
| `androidx.tv:tv-material` | HTTP 200 — `latest=1.1.0 release=1.1.0` |
| `androidx.tv:tv-foundation` | HTTP 200 — `latest=1.0.0 release=1.0.0` |
| `androidx.compose.material3:material3` *(control)* | HTTP 200 |

The control returning 200 rules out a blind instrument. The **Gradle coordinate is
`androidx.tv:tv-material` (1.1.0)**; the **package** is `androidx.tv.material3`.

**Why this still matters even though the spec is not literally wrong.** §4.4 makes the toolchain
matrix a Phase-0 deliverable and states "**No version literal appears anywhere else**", so the
coordinate has to be written down exactly once, correctly, by whoever seeds
`gradle/libs.versions.toml` — and §19.4 hands that job to a delegate working from a one-line brief.
The package/coordinate mismatch here is precisely the kind that produces a confident wrong guess.
**Pin `androidx.tv:tv-material:1.1.0` in the matrix explicitly**, with a comment that the package
name deliberately differs.

Google's guidance confirms the library is still the right call, so only the coordinate is wrong —
`developer.android.com/training/tv/playback/compose`, verbatim:

> "Use the TV version of APIs wherever possible… While it is technically possible to use the mobile
> version of Compose Material, it is not optimized for the unique style of interactions on Android
> TV. In addition, **mixing Compose Material with Compose Material from Compose for TV can result in
> unexpected behavior** … because each library has its own `MaterialTheme` object."

Its dependency table says "`androidx.tv:tv-material` **instead of** `androidx.compose.material3:material3`".

**Related trap, also worth recording:** TV Lazy Layouts were **deprecated out of `tv-foundation`**
("Tv Lazy Layouts have been deprecated from tv-foundation library", 1.0.0-alpha11) — use core
Compose foundation lazy lists on TV. And the Google guide page still shows `tv-material:1.0.0`
while the releases page is at 1.1.0; the guide lags.

---

### C5 — 🟠 The `ios()`/`tvos()` shortcuts were removed two years of releases ago, and the spec contradicts `CLAUDE.md`

**Spec §4.1, verbatim:** "removal is **planned** for **Kotlin 2.2.0**."
**`CLAUDE.md` trap 1, verbatim:** "`ios()` / `tvos()` / `watchos()` target shortcuts are gone
(**removed** in Kotlin 2.2.0)."

**Kotlin, verbatim** (multiplatform compatibility guide):

> "1.9.20: report a warning when `ios()`, `watchos()`, and `tvos()` target shortcuts are used…
> **2.1.0**: report an error when target shortcuts are used …
> **2.2.0**: **remove** target shortcut DSL from the Kotlin Multiplatform Gradle plugin"

**Current stable Kotlin is 2.4.10** (kotlinlang.org releases). Removal is done history, not a
roadmap item. `CLAUDE.md` is right and the spec is wrong, which is the worse direction — the spec is
the document delegates are told to treat as the contract. The sibling `fromPreset` presets API was
removed in the same release.

**Also stale, same section:** §26 cites
`https://kotlinlang.org/docs/multiplatform-compatibility-guide.html`. kotlinlang.org has moved every
`/docs/multiplatform-*.html` page under `/docs/multiplatform/`; the old URLs are now redirect stubs.

---

### C6 — 🟠 "Media URLs still require query auth" is false at the reference server

**Spec §13.2, verbatim:**

> **Use `formPost` when advertised** — every non-media API call becomes a POST with a form-encoded
> body… **Media URLs still require query auth, so this is a partial mitigation and must not be
> described otherwise.**

**Navidrome `server/subsonic/api.go` at v0.63.2**, first line of `routes()`:

```go
r.Use(postFormToQueryParams)
```

That middleware is mounted at the **router root**, above every group — so it applies to `stream`,
`download`, `getTranscodeStream` and `getCoverArt` as well as to metadata endpoints. Navidrome
accepts form-POSTed credentials on media endpoints.

And Dulcet already controls the media request: §12.4's whole architecture is an
`AVAssetResourceLoaderDelegate` + `URLSession` on Apple and a custom `DataSource.Factory` on
Android, precisely so nothing hands a raw URL to the engine.

**Caveat, stated so this is not over-claimed:** background `URLSession` download tasks with POST
bodies and resume data are genuinely awkward, and `Range` + POST is unusual. The *conclusion*
("partial mitigation") may still hold for §14.5 downloads. The *reason given* is wrong for playback,
and the security section is the wrong place to bake in a false constraint.

**Fix:** restate as a per-path fact, and add a CONF test asserting whether the reference server
honours form-POSTed credentials on `stream` (this is cheap and it changes §13.2's threat analysis).

---

### C7 — 🟠 GitHub has no CODEOWNER-scoped label permission

**Spec §19.3, verbatim:**

> Revision 1 accepted a `Regression-Approved:` line in the PR body, which any contributor can type;
> that is not an approval mechanism. … **The label is the authorization, checked through the GitHub
> API, and it may only be applied by a CODEOWNER.**

**GitHub, verbatim** (Managing labels): "Anyone with **triage** access to a repository can apply and
dismiss labels."

There is no per-label ACL, and `CODEOWNERS` governs review requirements on file paths — it confers
no label permission. The substitution is the same class of weakness it rejects, unless the workflow
itself reads **who applied the label** (the `labeled` timeline event's actor) and checks that actor
against `CODEOWNERS` — which the spec does not say.

Mitigating: on a **public** repo an outside fork contributor has no triage permission, so the real
exposure is org members rather than drive-by PRs.

**Fix:** specify the actor check explicitly, or move the authorization to a mechanism GitHub
actually enforces (a required review from a CODEOWNER on `FEATURES.yml`, via branch protection).

---

### C8 — 🟠 "Modern navigation APIs land at macOS 14 / iOS 17" — they landed a year earlier

**Spec §4.1, verbatim:**

> **Why 14.0 / 17.0:** the SwiftUI `@Observable` macro and the modern navigation APIs land at
> macOS 14 / iOS 17 / tvOS 17.

**Apple availability data** (fetched from Apple's documentation JSON API):

| API | iOS | macOS | tvOS |
|---|---|---|---|
| `Observation.Observable` | **17.0** | **14.0** | **17.0** |
| `SwiftUI.NavigationStack` | **16.0** | **13.0** | **16.0** |
| `SwiftUI.NavigationSplitView` | **16.0** | **13.0** | **16.0** |

Only `@Observable` supports the floor. Navigation is available one major version lower on every
platform. **Fix:** decide OQ-3 on `@Observable` alone, and drop the navigation half of the argument
— the operator is being asked to accept a reach trade-off on a reason that is half wrong.

---

### C9 — 🟡 §20.3 and §21.1 disagree about where the Apple conformance leg runs

§20.3's layer table places the macOS conformance run on **`macos-latest`** — a hosted runner label.
§21.1's workflow table places `apple-ci.yml`, including "`macosArm64Test` running the **real**
conformance suite", on **self-hosted `dulcet-mac`**. Under C1, §20.3 is the one that is right.

---

### C10 — 🟡 The demo server "stood up for review windows" conflicts with Apple's stated requirement

**Spec §22.3 / OQ-4, verbatim:** "a small, disposable Navidrome with the synthetic conformance corpus
(§20.2) under `dulcet.fm`, **stood up for review windows**."

**Apple, verbatim:**

> Guideline 2.1(a): "…include demo account info (**and turn on your back-end service!**) if your app
> includes a login."
>
> App Store Connect, App Review Information: "**The demo account is used during the App Review
> process and must not expire.**"

An ephemeral server is a rejection risk on every update review, not just the first. OQ-4's cost line
("costs money and a little standing infrastructure") understates it: this is a **permanently running
service**, and it is now a Phase-6 blocker with a recurring bill.

---

### C11 — ✅ RESOLVED in the 23:47 revision — the `${DOMAIN}` inconsistency, with residue

**What I originally found.** The header claimed the two constants were "defined here and nowhere
else", while `dulcet.fm` / `fm.dulcet` were hard-coded in §22.2 (three bundle IDs, two Android
application IDs, `https://dulcet.fm/support`, `https://dulcet.fm/privacy`), §22.3 and OQ-4 — and
`CORPUS.md` and `CLAUDE.md` both stated "Bundle IDs under `fm.dulcet`" as settled fact. OQ-7 was
also phrased two incompatible ways.

**The concurrent revision fixed this properly**, and better than I would have: `${BUNDLE_PREFIX}` is
now `com.legitimateapps.dulcet`, **decoupled from `${DOMAIN}` entirely**, on the argument that a
permanent identifier should not be hostage to a marketing domain that can lapse. That is the right
call. `${DOMAIN}` now "reaches only support/privacy URLs, the review demo server, and marketing
surfaces."

**I verified the revision's two new OBSERVED claims — both check out.** `legitimateapps.com`:

| checked | result |
|---|---|
| registered | **yes** — created 2026-02-19, expires 2027-02-19 |
| nameservers | `bill.ns.cloudflare.com`, `vera.ns.cloudflare.com` — matches "on Cloudflare nameservers" |
| resolves | yes, to Cloudflare addresses |

**Residue that is still live.** `${DOMAIN}` remains undecided, and §22.2 still specifies
`https://${DOMAIN}/support` and `https://${DOMAIN}/privacy`. **App Store Connect requires a reachable
support URL and privacy policy URL at submission**, so a domain must exist and serve two pages before
Phase 6 — and per C10 it must also host a permanently-running demo server. My RDAP sweep, kept
because it is the input to that decision:

| domain | status |
|---|---|
| `dulcet.fm` | available (dropped anyway — $87.85/yr) |
| `getdulcet.com` | **available** |
| `dulcet.dev` | **available** |
| `dulcet.page` | **available** |
| `dulcet.fyi` | **available** |
| `dulcet.app` | **registered** (2023-09-06) — not a candidate, noted so nobody proposes it |

Worth considering given the revision's own logic: `legitimateapps.com` is already owned and on
Cloudflare, so `dulcet.legitimateapps.com` would satisfy the support/privacy URLs at **zero cost and
zero new registration**, leaving `${DOMAIN}` as a pure marketing decision that can be deferred past
Phase 6 instead of gating it.

---

## 2 · ASSUMED / load-bearing — ranked by blast radius

### A1 — 🔴 ffmpeg is the thing under test and it is pinned nowhere

**Spec §20.2, verbatim:** "**A deterministic environment, or the tests are noise.**" It then pins the
server two ways — image digest on Linux, release-asset SHA-256 for the macOS binary.

**What it does not pin is the transcoder**, and every transcode conformance test (CONF-13 ranges on
transcoded streams, CONF-14a and CONF-14b offsets, CONF-15 `getTranscodeDecision`/`getTranscodeStream`)
measures bytes that ffmpeg produced.

Measured:

- The Navidrome container **bundles ffmpeg** — `Dockerfile` at v0.63.2 line 163:
  `RUN apk add -U --no-cache ffmpeg mpv sqlite libwebp libwebpdemux libwebpmux`. Its version floats
  with the Alpine base and is not part of the pin.
- The hosted macOS runner images have **no ffmpeg at all** (zero occurrences in
  `macos-26-arm64-Readme.md`). Running `navidrome_0.63.2_darwin_arm64` natively there gives a
  Navidrome **with no transcoder**.
- `dulcet-mac` would use whatever Homebrew ffmpeg happens to be installed.

**Consequence:** §20.3's central claim — "Running the real conformance suite on macOS against a
native Navidrome binary is what closes the Darwin gap" — is false for exactly the tests that most
need Darwin coverage, and they would **silently degrade to direct-play** rather than fail loudly.

**Cheapest way to settle:** run `getTranscodeDecision` against a Navidrome with no ffmpeg on PATH and
record what it returns. Then pin an ffmpeg version explicitly for both legs and assert it in the
harness health check.

### A2 — 🔴 App Sandbox is a hard Mac App Store requirement and the spec never names it

**Apple, verbatim**, App Store Review Guidelines 2.4.5(i):

> "Apps distributed via the Mac App Store have some additional requirements to keep in mind:
> (i) They must be **appropriately sandboxed**, and follow macOS File System Documentation."

And *Protecting user data with App Sandbox*: "App Sandbox — **a requirement for distributing your app
on the App Store**". Entitlement `com.apple.security.app-sandbox`.

§22.1 mentions "entitlements" once in a list; App Sandbox is never stated as binding, and §13.6
(local data privacy, app-private directories, backup exclusion, file-protection classes) is written
without reference to sandbox container semantics. §5.1 mentions "macOS sandboxing" once in passing.

Also verbatim from 2.4.5, and relevant to a Gradle-driven build and any future updater:

> "(ii) They must be packaged and submitted using technologies provided in Xcode; no third-party
> installers allowed… cannot install code or resources in shared locations."
> "(vii) They must use the Mac App Store to distribute updates; other update mechanisms are not
> allowed."

**Cheapest way to settle:** make the §22.1 signing dry run archive a **sandboxed** hello-world with
`com.apple.security.network.client` and a download to the container, not a bare hello-world.

### A3 — 🔴 Guideline **4.2.7**, not 4.2, is the sharp review hazard for this app category

**Spec §22.3:** "A client for self-hosted servers is exposed to Guideline 4.2 (minimum
functionality) and to a reviewer who cannot log in."

True but incomplete. **Apple, verbatim**, 4.2.7 Remote Desktop Clients:

> "(a) The app must only connect to a user-owned host device that is a personal computer or dedicated
> game console owned by the user, and **both the host device and client must be connected on a local
> and LAN-based network**…
> (e) **Thin clients for cloud-based apps are not appropriate for the App Store.**"

And 4.2.3(i): "Your app should work on its own **without requiring installation of another app to
function**."

Whether 4.2.7 applies turns on whether Dulcet reads as a *mirror of specific software* (it should
not — it has its own native UI and its own domain model) but this is a reviewer judgment call and
the spec should carry the argument, pre-written, in the review notes. 4.2.3(i) is the sharper one:
the app genuinely does not function without a server the user must stand up.

**Cheapest way to settle:** write the review-notes paragraph now, as a Phase-0 artifact, and pair it
with the demo server from C8.

### A4 — 🟠 `MediaSession.Callback.onConnect` is soft-deprecated; target `onConnectAsync`

**Spec §8, verbatim:** "`MediaSession.Callback.onConnect` authorizes controllers".

It exists, but **its own javadoc says not to use it** (androidx/media `release` branch):

> "It's **strongly recommended to override `onConnectAsync(MediaSession, ControllerInfo)`** instead
> of this method. This method will be deprecated as soon as the `@UnstableApi` annotation is
> removed."

Cheap to fix now, annoying to migrate after six shells exist.

Two adjacent facts worth folding into §8 while it is open: Media3 current version is **1.11.0**
(2026-08-05) with `minSdk` **23**; and Media3 now ships **`media3-ui-compose`** and
**`media3-ui-compose-material3`** with state holders (`PlayPauseButtonState`, `CurrentMediaItemState`,
…) that the Android shells would otherwise hand-roll.

### A5 — 🟠 §4.1's Apple deployment-target override is named but not specified, and it is a Phase-0 CI gate

**Spec §4.1, verbatim:** "Kotlin/Native has its own default minimum Apple deployment versions
(currently macOS 12 / tvOS 15) and **can be given an explicit override**" — and Phase 0's exit
criterion is "both OS-floor settings (§4.1) asserted by a CI check".

The **numbers are confirmed** (kotlinlang.org): "the default minimum supported versions of Apple
targets are: For iOS and tvOS, **15.0**. For macOS, **12.0**." But the override is **not a Gradle DSL
property** — it is a raw compiler flag:

```kotlin
binaries.configureEach {
    freeCompilerArgs += "-Xoverride-konan-properties=minVersion.macos=14.0"
    freeCompilerArgs += "-Xoverride-konan-properties=minVersion.tvos=17.0"
}
```

A spec that makes this a Phase-0 CI assertion should name the mechanism, because "there must be a
DSL knob for this" is the first thing an implementer will assume and it will cost them an afternoon.

**Related caveat for §4.3:** `embedAndSignAppleFrameworkForXcode` "**only registers if the
`binaries.framework` configuration option is declared**" (kotlinlang.org, direct integration). A
scaffold that declares targets but not framework binaries produces a Run Script phase calling a task
that does not exist.

### A6 — 🟠 Tier 2 is weaker than §4.1 says, and `tvosArm64` is not run-tested at all

**Spec §4.1:** "Kotlin's Tier 2 guarantee is that targets are regularly **compile**-tested and that
source and binary compatibility are maintained on a best-effort basis."

Substantially right, and the primary source is sharper. **Kotlin, verbatim**
(native-target-support.html, page dated 2026-05-29):

> **Tier 1:** "The target is regularly tested on CI to be able to **compile and run**. We **provide**
> a source and binary compatibility between compiler releases."
> **Tier 2:** "The target is regularly tested on CI to be able to compile but **may not be
> automatically tested to be able to run**. We're **doing our best** to provide source and binary
> compatibility between compiler releases."

Confirmed tiers: `macosArm64`, `iosArm64`, `iosSimulatorArm64` = **Tier 1**; `tvosArm64`,
`tvosSimulatorArm64` = **Tier 2**. And the table's *Running tests* column marks
`tvosSimulatorArm64` ✅ but leaves **`tvosArm64` blank** — you can run Kotlin tests on the tvOS
simulator, not on an Apple TV device. §24's Phase 5 exit criterion is "focus navigation verified on a
**real Apple TV**", which is a Swift-side UI test, not a Kotlin one — so this is compatible, but the
spec should say which layer is verified where rather than leaving it implied.

`macosX64` and `tvosX64` deprecation is **confirmed**: "Starting with Kotlin **2.3.20**, the
following targets are deprecated: `macosX64` …, `watchosX64` …, `tvosX64`". Note this is recent — a
model answering from memory would have called `macosX64` healthy and marked the spec wrong. It is
not wrong. (The spec omits `watchosX64`, which is deprecated too and which nothing here targets.)

### A7 — 🟠 §4.4's toolchain matrix must be pinned against these current versions

§4.4 correctly refuses "latest stable at implementation time" and makes the matrix a Phase-0
deliverable. Measured current values, so Phase 0 starts from facts rather than a search:

| component | current | note |
|---|---|---|
| Kotlin | **2.4.10** | 2.4.20 due Sept 2026, 2.5.0 Dec 2026 |
| Ktor | **3.5.2** | `ktor-client-darwin-tvosarm64` and `-tvossimulatorarm64` both publish at 3.5.2 |
| SQLDelight | **2.3.2** | `native-driver-tvosarm64` and `-tvossimulatorarm64` both publish at 2.3.2 |
| Media3 | **1.11.0** | `minSdk` 23 |
| `androidx.tv:tv-material` | **1.1.0** | see C4 — the coordinate, not `tv-material3` |
| `androidx.tv:tv-foundation` | **1.0.0** | TV Lazy Layouts deprecated out of it |
| Gradle on hosted macOS runner | 9.6.1 | §4.3's contributor prerequisite |

**`minSdk 26` (§4.2) has headroom on every dependency** — read from the published AAR manifests, not
from docs: media3-exoplayer 23, media3-session 23, tv-material 23, compose material3-android 21,
sqldelight android-driver 23. Nothing requires above 26.

### A8 — 🟠 §18.1's "local matching approximates the server's" is contradicted by Navidrome's own docs

**Spec §18.1:** ranking is "exact, prefix, word-start, substring", with "the normalized form stored
in an index column **so local matching approximates the server's**."

**Navidrome, verbatim** (navidrome.org Subsonic API compatibility): `search2`/`search3` "Doesn't
support Lucene queries, **only simple auto complete queries**."

An autocomplete matcher and a four-tier local ranker will disagree, which matters because §18.1's
merge rule lets a server result **replace** a local row — so the two sources' *disagreement about
what matches at all* is user-visible, not just a reordering.

**Cheapest way to settle:** one CONF test issuing a fixed query set against the pinned container and
recording the returned id sets; make the local ranker's contract "same-shaped, not same-result" and
say so in the spec.

### A9 — 🟠 `apple-ci` at 45 minutes on a 3-vCPU / 7 GB runner is an untested budget

§21.1 sets `timeout-minutes: 45` for `apple-ci`. Under C1 that job moves to `macos-latest`, which is
**3 vCPU (M1) / 7 GB RAM**. A cold Gradle KMP build producing five Kotlin/Native targets plus four
Xcode targets plus simulator tests in 45 minutes is optimistic and unmeasured. **Settle with one
throwaway run before the number is written into a workflow.**

### A10 — 🟠 `estimateContentLength` is documented and unused

`stream` takes `estimateContentLength` ("Sets Content-Length header for transcoded media" —
OpenSubsonic stream endpoint). That is the documented lever that makes a legacy transcoded stream
seekable and makes §14.5's "validated… **against the expected byte length**" achievable for
transcoded downloads. The spec never mentions it. Not wrong — missing, in a place where §12.7
(seeking) and §14.5 (atomic promotion) both quietly assume the information exists.

### A11 — 🟠 `.view` is always appended: OBSERVED for Navidrome, ASSUMED for everything else

**Spec §10.1.5:** "`.view` **is always appended** — it is the classic form and is accepted by servers
that also accept the bare name."

Confirmed for the reference server — Navidrome `server/subsonic/api.go` `addHandler()`:

```go
r.HandleFunc("/"+path, handle)
r.HandleFunc("/"+path+".view", handle)
```

Both forms registered for **every** endpoint, including the OpenSubsonic-only ones. The
generalisation to other servers is unsourced. Low risk, but it is stated as a universal and it is
not one — record it as a quirk-register entry rather than a fact.

### A12 — 🟡 §9.3's `reportPlayback` collides by name with the endpoint v1 forbids

The provider seam declares `suspend fun reportPlayback(event: PlaybackEvent)`. The OpenSubsonic
`playbackReport` extension's endpoint is literally named **`reportPlayback`**, and §15.4 says "no
production code path calls `playbackReport`". §19.4 hands delegates one-line briefs. A brief saying
"implement `reportPlayback`" is an obvious way to wire up the exact endpoint the spec forbids.
**Cheapest fix: rename the seam method** (`recordPlaybackEvent`).

Related, and it makes §15.4 slightly over-cautious: the extension **already documents the control**.
OpenSubsonic playbackReport: "When `ignoreScrobble=true`, the server should only update now playing
display/state fields and **should not perform playcount/scrobble side effects**." CONF-21 is still
the right gate, but the hazard is a documented parameter, not an unknown.

### A13 — 🟡 §22.1's signing-assets risk is materially lower than stated (local probe)

**Spec §22.1:** "**ASSUMED**… that App Store Connect distribution certificates, identifiers,
provisioning profiles and entitlements can be created for team 3LTL47SJ8C."

`security find-identity -v` on this Mac, 2026-08-17:

- `Apple Distribution: Legitimate Limited Liability Company (3LTL47SJ8C)` — **present, with private key**
- `3rd Party Mac Developer Installer: Legitimate Limited Liability Company (3LTL47SJ8C)` — **present**

The second is the **Mac App Store installer-signing certificate**. Its existence means the team has
already been provisioned for Mac App Store distribution at some point. Combined with the agreements
finding below, §22.1's stated risk is largely discharged.

**Agreements — Apple, verbatim** (developer.apple.com/support/terms/ and App Store Connect help):

> Apple Developer Program License Agreement applies to "Membership in the Apple Developer Program and
> the **distribution of free apps**."
> "To sell your apps on the App Store or offer In-App Purchases, the Account Holder must sign the
> **Paid Apps Agreement**."

A **free app with no IAP needs only the Program License Agreement**, accepted at enrolment. No Paid
Applications Agreement, no banking or tax setup. The §22.1 worry about "agreements in force" can be
narrowed accordingly.

⚠️ Two constraints that do bind: distribution certificates are **one of each type per team**
(so the existing Apple Distribution cert is the team's only one — do not revoke it, other projects
share it), and **"Only the Account Holder or Admin role can create distribution certificates."**

### A14 — 🟡 "No Developer ID certificate" is a current state, presented as a constraint

**Spec §22.1:** "**Why:** there is **no Developer ID certificate** on the build Mac, which blocks
direct outside-the-store distribution."

The fact is **confirmed** by my probe — no `Developer ID Application` or `Developer ID Installer`
identity exists in this keychain. But Apple, verbatim (Create Developer ID certificates):

> "You can create up to **five Developer ID Application certificates** and up to five Developer ID
> Installer certificates using either your developer account or Xcode."
> "Required role: **Account Holder**."

So this is a 10-minute action for the Account Holder, not an immovable constraint. The
"never advertise a DMG" rule is a sound *choice*; the spec presents it as forced. Worth saying which
it is, because "we can't" and "we chose not to" produce different roadmaps.

### A15 — 🟡 "Dulcet" as an App Store name looks clear, and the similar-name fear is folklore

Checked so nobody spends a session on it. **iTunes Search API**, US store, `entity=software`,
`term=dulcet`: **1 result**, unrelated (a Romanian tourism app). No App Store app named Dulcet.

**On naming rules — Apple, verbatim:**

> 2.3.7: "Choose a unique app name… **App names must be limited to 30 characters.**"
> 4.1(c): "**You cannot use another developer's icon, brand, or product name** in your app's icon or
> name, without approval from the developer."
> 4.3(b): "Don't submit apps that are indistinguishable from what's already widely available…
> Certain kinds of apps, such as dating, flashlight, sound effects, wallpaper, simple timers, and
> fortune telling, are well established on the App Store and we will not accept new submissions
> unless they offer a meaningfully different or improved experience."

**There is no guideline rejecting a name for mere similarity.** What is real: the 30-character
limit; *exact* name uniqueness enforced at record creation; trademark and copycat rules (4.1, 5.2.1);
and 4.3(b) for saturated categories — a list that does not include music clients. "Dulcet" is six
characters and unclaimed.

### A16 — 🟡 Small unsourced statements worth a marker

- **§4.3** "The Run Script phase that produces it runs **before** the compile phase" — that is a
  project configuration the team must create, written as though it were a property of the tooling. A
  delegate will read it as already true.
- **§16 target scale** "~31,500 tracks across ~2,950 albums" — the operator's own library; not
  independently checkable here, and the arithmetic that depends on it is correct (2,950 albums ÷
  `size=500` = 6 `getAlbumList2` calls; ~2,950 `getAlbum` calls). `size` max 500 is confirmed
  against subsonic.org.
- **§21.1 "~92% of another project's Actions spend"** — the operator's own historical datum. Now
  scoped to private repos by C1 and no longer load-bearing here.
- **`dulcet.fm` at "$87.85/yr to register *and* renew"** — unverified, and moot: the domain is
  unregistered and has been dropped anyway.

---

## 3 · OBSERVED — what is properly sourced and can be relied on

The protocol chapter is in good shape. This is the strongest part of the document.

| claim | verdict |
|---|---|
| `getOpenSubsonicExtensions` "**must** be publicly accessible" (§10.2, CONF-01) | ✅ quoted from the OpenSubsonic endpoint page **and** confirmed in Navidrome source — registered above the auth middleware under a `// Public` comment |
| Salted-token auth: salt "at least six characters"; `token = md5(password + salt)`, lowercase hex (§13.1) | ✅ verbatim from subsonic.org |
| Version rule — major must match, client minor ≤ server minor (§2.1) | ✅ verbatim from subsonic.org |
| Subsonic error codes `{0,10,20,30,40,41,50,60,70}` (§18.12) | ✅ **exactly** the documented set, code 41 included. Marked ASSUMED in the spec; it is OBSERVED. The spec's caution that other servers may return others remains correct |
| `stream` returns "binary data on success, or an XML document on error (in which case the HTTP content type will start with `text/xml`)" (§12.4) | ✅ verbatim, both sources |
| "OpenSubsonic servers **must not** count access to this endpoint as a play and increase playcount" (§15.1) | ✅ verbatim |
| Navidrome "does not mark songs as played by calls to `stream`, only when `scrobble` is called with `submission=true`" (§15.1) | ✅ verbatim from navidrome.org |
| `scrobble` `time` is "milliseconds since 1 Jan 1970"; `submission` defaults true (§15.2) | ✅ verbatim |
| "IDs in Navidrome are always strings, normally MD5 hashes or UUIDs" (§9.2) | ✅ verbatim from navidrome.org |
| Envelope fields `openSubsonic` / `type` / `serverVersion` mandatory for OpenSubsonic (§10.2, CONF-05) | ✅ verbatim |
| `transcoding` extension = `getTranscodeDecision` (POST, `ClientInfo` body) + `getTranscodeStream`; response carries `canDirectPlay`, `canTranscode`, `transcodeReason[]`, `transcodeParams`, `sourceStream`, `transcodeStream`; `transcodeStream.protocol` can be `"hls"` (§12.5, §9.4) | ✅ all confirmed on the OpenSubsonic pages |
| `transcodeParams` is opaque — "Clients **should not try to reconstruct** the `transcodeParams`. Instead, they must use the `transcodeParams` provided in the response of the `getTranscodeDecision` endpoint" (§12.5) | ✅ **sourced** — but the sentence lives on the **`getTranscodeStream`** page, not on the two pages §12.5 cites. Fix the citation |
| `transcodeOffset` belongs to the **legacy** `stream` path (§12.5) | ✅ the OpenSubsonic `stream` page documents `timeOffset` as "video by default; **music if Transcode Offset extension supported**" |
| `songLyrics` versions **1 and 2**, v2 adding `enhanced`, `kind`, `agents`, `cueLine` (§2.3, §18.4) | ✅ verbatim |
| OpenSubsonic OpenAPI is work-in-progress, inconsistent with the prose docs, Kotlin generation "🚧 Untested" (§23.4) | ✅ verbatim |
| Navidrome **v0.63.2** is the newest release, published **2026-07-11**, tag → commit **`be10f89`** | ✅ exact match to the spec, via the GitHub API |
| **§2.3's extension table is correct at the pinned tag, not just at `master`** | ✅ see below |
| Shelv — GPL-3.0, Swift, active (pushed 2026-08-16), has `Shelv TV` and `Shelv TV Siri Extension` targets (§3) | ✅ confirmed via the GitHub API |
| Chora — Apache-2.0, Kotlin, last commit **`8e06462`** on **2026-07-25**, v1.31/v1.31.1 June 2026 (§23.3) | ✅ exact match |
| Service containers require a Linux runner (§21.1) | ✅ "If your workflows use… service containers, then you must use a Linux runner" |
| Hosted macOS images have no Docker (§20.3) | ✅ zero occurrences in `macos-15-arm64` and `macos-26-arm64` readmes |
| Hosted macOS images ship a JDK and Gradle (§4.3) | ✅ Temurin 11/17/21/25, Gradle 9.6.1 |
| Self-hosted runners "should almost never be used for public repositories"; fork PRs can run code on them (§21.3) | ✅ verbatim; §21.3's security reasoning is sound |
| macOS TestFlight exists and needs a provisioning profile (§22.1) | ✅ App Store Connect help lists macOS; Apple (WWDC21): "**Native Mac apps require a provisioning profile to be distributed with TestFlight on Mac.**" |
| TN3125 is a real technote about provisioning profiles (§26) | ✅ "TN3125: Inside Code Signing: Provisioning Profiles" |
| No Developer ID certificate on this Mac (§22.1) | ✅ confirmed by `security find-identity -v` |
| `@Observable` is macOS 14 / iOS 17 / tvOS 17 (§4.1, half of it) | ✅ Apple availability data |
| `ghcr.io/navidrome/navidrome:0.63.2` is a multi-arch OCI index (digest-pinnable) and `navidrome_0.63.2_darwin_arm64.tar.gz` exists as a release asset (§20.2, §20.3) | ✅ both pinning mechanisms are feasible as specified |
| Kotlin/Native tiers: `macosArm64`/`iosArm64`/`iosSimulatorArm64` **Tier 1**, `tvosArm64`/`tvosSimulatorArm64` **Tier 2** (§4.1) | ✅ verbatim from kotlinlang.org — see A6 for the sharper wording |
| `macosX64` and `tvosX64` are deprecated (§4.1, `CORPUS.md` §5, `CLAUDE.md` trap 2) | ✅ "Starting with Kotlin **2.3.20**, the following targets are deprecated: `macosX64`… `tvosX64`". Recent enough that memory would have called this wrong |
| Kotlin/Native default Apple minimums are macOS 12 / tvOS 15 (§4.1) | ✅ verbatim — "For iOS and tvOS, 15.0. For macOS, 12.0." |
| `<target>.binaries.framework { baseName; isStatic }` and `XCFramework(...)` (§4.3) | ✅ current DSL — "`isStatic` — For Objective-C frameworks. Includes a static library instead of a dynamic one." |
| Gradle tasks `embedAndSignAppleFrameworkForXcode` and `assembleXCFramework` (§4.3) | ✅ both real and current — see A5 for the registration caveat |
| Swift Export is Alpha; ObjC interop is the production path (§7) | ✅ kotlinlang.org, as cited |
| SQLDelight: `AndroidSqliteDriver` / `NativeSqliteDriver(Schema, "name.db")` / `JdbcSqliteDriver`, the `expect class DriverFactory` pattern, `.sqm` migrations, and the **`verifySqlDelightMigration`** task (§11.1, §11.4) | ✅ all verbatim from sqldelight.github.io. Current version **2.3.2** |
| SQLDelight `native-driver` publishes **tvOS** artifacts (§11.1 → Phase 5) | ✅ `native-driver-tvosarm64` and `-tvossimulatorarm64` both present at 2.3.2 on Maven Central |
| Ktor's Darwin/NSURLSession engine supports tvOS (§20.3's Darwin leg → Phase 5) | ✅ "The Darwin engine targets Darwin-based operating systems, such as macOS, iOS, **tvOS**, and watchOS." Artifacts `ktor-client-darwin-tvosarm64` / `-tvossimulatorarm64` present at 3.5.2 |
| Media3 has **no callback for ordinary position progression** (§8) | ✅ `Player.java` javadoc verbatim: "there are no callbacks for normal playback progression… should query the current position in appropriate intervals" |
| `MediaSession.Builder`, `MediaController.addListener`, `setAudioAttributes(attrs, handleAudioFocus)` (§8, §12.9) | ✅ all exist. Note `setAudioAttributes` is declared on **`Player`** (inherited by `ExoPlayer`), and there is also an `ExoPlayer.Builder` overload — §8's phrasing is imprecise, not wrong |
| Android TV is D-pad-driven with focus as primary state, needing explicit focus order (§3) | ✅ developer.android.com, and Google still recommends the TV library over mobile Material3 — see C4 |

**§2.3 — the spec is more cautious than it needs to be.** It says the extension table came from
`master` and "Until CONF-02 runs, treat this table as ASSUMED-for-0.63.2". One API call reads
`server/subsonic/opensubsonic.go` **at tag `v0.63.2`**:

```go
{Name: "transcodeOffset",  Versions: []int32{1}},
{Name: "formPost",         Versions: []int32{1}},
{Name: "songLyrics",       Versions: []int32{1, 2}},
{Name: "indexBasedQueue",  Versions: []int32{1}},
{Name: "transcoding",      Versions: []int32{1}},
{Name: "playbackReport",   Versions: []int32{1}},
}
if api.sonic != nil && api.sonic.HasProvider() {
    extensions = append(extensions, responses.OpenSubsonicExtension{
        Name: "sonicSimilarity", Versions: []int32{1},
    })
}
```

Every row of §2.3 is **exactly right at the pinned release**, `sonicSimilarity` really is gated on
server configuration, and `apiKeyAuthentication` really is not advertised. Promote the table to
OBSERVED-at-`v0.63.2` and demote CONF-02 from *discovery* to *regression detection* — that is what
it is actually for.

---

## 4 · What I could not check, and why

1. **Nothing, in the Kotlin/library block — that pass completed.** Every claim in audit category 3
   was checked against kotlinlang.org, ktor.io, sqldelight.github.io, developer.android.com and live
   artifact metadata on Google Maven and Maven Central. Results are in C4, C5, A4–A7 and the OBSERVED
   table. Two notes on how close this came to going the other way:
   - **`macosX64` deprecated looked like the obvious false claim and it is true.** Deprecation landed
     in Kotlin **2.3.20**, recently enough that an answer from memory would have marked the spec
     wrong. This is the counterexample to the audit's own bias: not every inherited claim is stale.
   - **The failure was where nobody was looking** — not a tier, not a version floor, but a Gradle
     coordinate that has never existed (C4), asserted confidently in three documents.

2. **`AVAssetResourceLoaderDelegate` custom-scheme behaviour and HLS limits (§12.4, OQ-10).**
   **UNVERIFIABLE from Apple.** I pulled the class reference through Apple's documentation JSON API:
   the entire discussion is *"A class should adopt this protocol when associated with the asset's
   resource loader… The resource loader works with your delegate to process the request."* Apple
   does not document the custom-scheme requirement, and does not document whether the delegate can
   supply **HLS media segments** or only the playlist and encryption keys.

   This matters because §12.4 makes the loader the correctness mechanism *and* says HLS "is
   validated as a **manifest**… the loader checks it parses as an M3U8 and that segment URIs resolve
   within the expected origin" — while §12.5 Path A can return `protocol: "hls"`. Under a custom
   scheme, AVPlayer resolves segment URIs relative to that scheme, so either they come back through
   the loader (and Dulcet owns segment loading, range handling and validation for every segment) or
   they escape it entirely. OQ-10 asks the operator to accept "the largest single piece of Apple-side
   work in Phase 1" on a mechanism with no primary documentation.

   **What would settle it:** a one-day spike — a custom-scheme `AVURLAsset` playing (a) progressive
   MP3 and (b) an HLS manifest, with the loader logging every request it receives. That is a
   measurement, not a decision, and it should happen before OQ-10 is answered.

3. **Whether a tvOS top-shelf image is mandatory at submission.** The asset slot is documented
   ("App Icon & Top Shelf Image" folder); that upload is *rejected* without it is not stated on any
   current Apple page. Settle with an actual Transporter/`altool` validation run at Phase 5.

4. **Navidrome's real HTTP status on a bad-id `stream`.** That is CONF-12's job and it needs a live
   server. Note C3 changes what CONF-12 should record: it must capture status **per delivery path**
   (`stream` vs `getTranscodeStream`), and it should be extended to cover the 429 case.

5. **Whether the operator is Account Holder on team 3LTL47SJ8C.** Gates A14 (Developer ID) and the
   Paid-Apps question in A13. One look at the Apple Developer account's Membership page settles it.

---

## Bottom line

**11 contradicted claims — 10 still standing, 1 already fixed by the concurrent revision — plus 16
unsourced load-bearing ones.** The **protocol layer is the best-sourced
part of this spec** and mostly survives contact with the primary sources — several claims are more
solid than their own ASSUMED markers admit (§2.3's extension table, the Subsonic error-code set).
The damage is concentrated where the audit predicted: **inherited platform policy** (C1 — the same
private-vs-public scope error as the planted one, currently driving the spec's flagship open
question) and **Apple distribution mechanics** (C2, C10, A2, A3).

**Fix first, in this order:**

1. **C4** — `androidx.tv:tv-material3` is a 404. One-line fix, and it is asserted in three documents,
   so it would have survived to the first Android TV build and been blamed on the build config.
2. **C1** — rescope the multiplier to private repos and collapse OQ-1. It unblocks the repo-visibility
   decision, removes a fork-PR risk to the operator's workstation, and costs nothing.
3. **C3 + A1 together** — the most expensive item. The reference server has a transcode concurrency
   limiter returning an HTTP status and a header the error model has no vocabulary for, `preloadNext`
   is the thing most likely to trip it, and the ffmpeg that produces every byte the transcode tests
   measure is pinned nowhere and **absent from the hosted macOS image entirely**. These surface as
   flaky playback and quietly-passing tests — exactly the failure mode this spec's conformance
   chapter exists to prevent.

**One methodological note for whoever revises this.** The audit's own bias nearly produced a false
positive: `macosX64` deprecated *looks* like a stale-memory claim and is true as of Kotlin 2.3.20.
Meanwhile the real defect was a Gradle coordinate nobody thought to type into a browser. The
lesson matches the spec's own doctrine — **confirm a negative with a second instrument, and check
the boring thing you assume exists.**
