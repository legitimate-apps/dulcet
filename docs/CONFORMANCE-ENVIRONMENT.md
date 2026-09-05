# Deterministic conformance environment

This is the Phase 1 environment described by design §20.2. It creates disposable Navidrome instances
for protocol tests; it is not a development connection to any existing server.

## Two pinned legs

Both legs run Navidrome 0.63.2 and the same generated corpus:

| leg | Navidrome pin | ffmpeg pin |
|---|---|---|
| Linux/amd64 | `deluan/navidrome` manifest digest in `tools/conformance-env/pins.json` | ffmpeg 6.1.1 inside that immutable image filesystem |
| Darwin/arm64 | upstream release asset and SHA-256 in `tools/conformance-env/pins.json` | Homebrew arm64 Tahoe ffmpeg 9.0.1 plus its complete 14-formula runtime dependency closure; every formula version, revision, dependency edge, bottle rebuild, immutable GHCR blob URL, and SHA-256 is locked |

The Darwin installer does not perform a name-only resolution. Before any closure member is fetched,
it validates the complete closure: every URL must be an immutable
`ghcr.io/v2/homebrew/core/.../blobs/sha256:<digest>` reference and its digest must equal the separate
SHA-256 pin. Public GHCR pulls still require authentication, so the installer obtains an anonymous
repository-scoped pull token before downloading a missing blob. A previously cached blob can be used
without a token or network request, but is re-hashed before every use; a corrupt cache entry fails
closed rather than being silently replaced. Newly downloaded bytes are written to a temporary file,
verified, and only then atomically promoted into the cache.

The 15 pinned Homebrew bottles do not embed an `INSTALL_RECEIPT.json`. They do embed an SPDX 2.3
document, a single formula/version keg directory, and the formula source. The SPDX document supplies
source identity but not dependency edges, revision, or version scheme. GHCR's tagged OCI index also
publishes `sh.brew.tab` runtime metadata, but that index is outside the checksum-pinned layer bytes and
is not used as an integrity oracle. The installer reads the archive members directly with Python's
standard archive and JSON libraries; it does not invoke Homebrew or evaluate
the formula Ruby. Name and stable version come from the SPDX-described source package and must agree
with the keg path. Revision comes from the keg-version suffix and must agree with the formula's static
`revision` declaration (both default to zero). Version scheme and the direct Darwin/arm64 runtime
dependency declarations are read by a deliberately narrow, non-executing declaration parser. It
understands the dependency forms and platform scopes present in the authenticated closure and fails
closed on ambiguous or unsupported dependency syntax; unrelated formula DSL such as install methods
and post-install keywords is never interpreted.

Every derived fact is checked against the pin. Missing, duplicate, non-regular, oversized, malformed,
or mutually inconsistent metadata fails before `brew install` is attempted. The metadata path cannot
access the formula index by construction, and its mutation control replaces both command and network
entry points with traps while asserting `formula_index_accesses=0` on successful and failing reads.
This does not claim
that Homebrew's later bottle-install code contains no fallback; the installer rejects the fallback's
missing/unreadable preconditions first and re-hashes the same archive immediately before handing it to
Homebrew. The live formula API comparison remains an advisory refresh signal: the main-path controls
observe that both drift and an API outage still reach pinned install preparation.

Homebrew remains responsible for pouring each verified local bottle, relocating its paths and Mach-O
install names, running applicable post-install handling, creating receipts, and linking active `opt`
prefixes. It is not allowed to resolve or upgrade dependencies: the installer pours all 15 local
bottle paths explicitly in pinned topological order with dependency resolution and installed-dependent
upgrades disabled. Each archive is re-hashed immediately before its pour. Homebrew derives mutable
runtime receipt fields from its local formula metadata, which may itself have moved ahead; after the
pour, the installer replaces only that dependency list with the graph already proven from the
immutable bottles and then verifies the complete receipt.

The installer records every locked keg's pre-install filesystem and receipt observation, removes the
complete closure in reverse dependency order, and then requires an observed absence checkpoint. That
checkpoint covers the whole `<cellar>/<formula>` rack, not only the pinned keg path, because a pour
reads the whole rack: an entry at any other version takes part in it, `brew uninstall` does not
remove one Homebrew can no longer parse as an installed keg, and a hosted runner rejected the
openssl@3 pour with `Cellar/openssl@3/3.6.3 is not a directory` while 3.6.4 was the pinned version.
Every rack is observed after the uninstall, anything still there is named with its file type and
removed, and every rack is then observed again; absence comes from that second observation, and a
rack that is still occupied, or an entry that cannot be removed, stops the run naming the path and
its type. The runner-side cause of that leftover entry is not established here, so this closes the
gap on the installer's side rather than claiming the runner condition is understood.

The installer then records the post-install observation, verifies every active keg and bottle
receipt, and checks the `libmp3lame` and `libopus` encoders used by the corpus. It also computes a
hash of every installed keg payload and re-hashes the payloads before reporting one aggregate. Installed payload
hashes are deliberately not treated as cross-run pins: Homebrew's post-pour relocation and signing
made all 15 payload hashes differ across two fresh standard hosted runners while their
checksum-verified source archives remained identical.

**Limit — the post-pour observations do not establish byte-for-byte bottle-to-installed-payload
provenance.** They say that the keg was absent after uninstall, present after a local-bottle pour with
a readable pinned-graph receipt, different from the retained pre-install filesystem/receipt
observation, and unchanged across two payload hashes in this run. A copied keg can preserve its
receipt and payload while changing its inode, so placing one after the absence checkpoint can satisfy
those observations without tying its bytes to the checksum-verified bottle. Homebrew's relocation
means the installed bytes also cannot be compared soundly with the archive or pinned across runners.
The fresh-pour negative control proves only that an untouched retained keg at the pinned path is
rejected; the separate Cellar-rack control builds residue at versions the closure does not pin,
asserts that the pinned-path observation is blind to it, and drives the installer main path with
Homebrew's own rack precondition standing in for the pour, so it fails the way the runner failed
whenever the rack is not cleared first. The separate bottle-integrity control drives the installer
main path, changes a blob after its archive-metadata check, and observes a final failed hash with
zero install attempts; its authentic case observes the
ordered final-hash-success then `brew install` events. This narrower post-pour result is
sufficient for a standard ephemeral hosted runner, whose job state is discarded rather than carried
forward as an adversarial keg cache; no stronger provenance claim is made. The checks run before
either the resource-loader fixture encoder or the corpus encoder, so both use the same installed
closure that passed the archive checksum, authenticated metadata graph, version, receipt, encoder, and
two-read payload checks.

The checked-in `navidrome.toml.template` is rendered only into the hosted runner's temporary
directory. It fixes the scanner, transcoder concurrency, UTC time zone, disabled similarity/external
providers, log redaction, cache sizes, and localhost-only address. The Darwin and Linux path values are
the only substitutions.

## One-command Linux environment

The Linux/amd64 environment used by `core-ci.yml` is also the local environment. Docker Desktop (or
Docker Engine on Linux) must be running; no separately installed Navidrome or ffmpeg is used.

```bash
tools/conformance-env/linux-local up
tools/conformance-env/linux-local run -- ./gradlew --no-daemon --max-workers=2 -Dorg.gradle.workers.max=2 :core-conformance:jvmTest
tools/conformance-env/linux-local reset
tools/conformance-env/linux-local run -- ./gradlew --no-daemon --max-workers=2 -Dorg.gradle.workers.max=2 :core-conformance:testAndroidHostTest
tools/conformance-env/linux-local down
```

The reset between JVM targets is required: both suites contain cold-transcode controls, and the first
suite warms the shared cache. `reset` stops the disposable server, clears only its guarded transcode
cache, restarts it, and requires Navidrome's own log to report `cached=false` before the Android host
suite starts. The Android task compiles the production Android source set and runs the common controls
on the host JVM; it does not start an emulator or claim device-runtime coverage.

`up` is cold by default: it stops the named disposable container, deletes only the marker-guarded
`data` and `cache` directories, then starts and health-checks the pinned image. The generated music
corpus is retained, so a subsequent cold start does not rebuild 314 fixtures. To clear only a warmed
transcode cache and restart the same disposable container against its retained database and corpus,
run:

```bash
tools/conformance-env/linux-local reset
```

Navidrome 0.63.2's cache manager remains bound to its initialized cache while the process is live, so
deleting entries underneath that process does not reset it reliably. `reset` therefore stops only the
disposable server, clears `cache/transcoding`, starts the same container again, and then runs one
fixed 128 kbps transcode. Navidrome's own `Streaming file` record must report `cached=false`; failure
to observe that cold state fails the reset. The probe bitrate is deliberately disjoint from the 64,
73, and 96 kbps cold-cache controls in the suite. A fixed cache observation can also be run twice
without changing its request:

```bash
tools/conformance-env/linux-local probe-cache  # first run reports cached=false
tools/conformance-env/linux-local probe-cache  # second run reports cached=true
```

`probe-cache` reads Navidrome's own `Streaming file` record and requires `transcoding=true`; it does
not infer cache behavior from client timing.

For the same three-platform order used by `apple-ci`, lease or otherwise create dedicated iOS and
tvOS simulators, boot them, and pass their UDIDs to the shared local lifecycle:

```bash
tools/conformance-env/linux-local run-apple \
  --ios-simulator-udid "$IOS_SIMULATOR_UDID" \
  --tvos-simulator-udid "$TVOS_SIMULATOR_UDID"
```

The command runs macOS, iOS simulator, and tvOS simulator sequentially against one local instance
root. Before each Gradle invocation it uses the same marker-guarded stop, cache clear, server restart,
and fail-closed `cached=false` observation as CI. Simulator child processes receive the same
disposable loopback environment as the host process.

`run` supplies the same variables as CI: `TZ=UTC`, the exact loopback Navidrome, redirect, and
untrusted-TLS URLs, and `DULCET_CONFORMANCE_DISPOSABLE=true`. The common conformance suite requires
both the exact loopback URL and that disposable declaration. It will not run against another server.
All image, architecture, server-version, ffmpeg-path, and ffmpeg-version values are read at runtime
from `tools/conformance-env/pins.json` through `read-pin`; the local command and CI contain no second
copy.

## Fresh database per test class

`tools/conformance-env/new-class-root` creates a uniquely named root containing new data, cache,
configuration, logs, and music directories. The environment contract is one root and one Navidrome
process per conformance test class. Reusing a data directory is not supported, so play counts,
playlists, favourites, queues, and users cannot leak between classes.

The account-connect conformance group is one test class. Each CI leg creates one root with
`new-class-root`, launches one Navidrome process, runs the fail-loud health check, executes that class,
and tears the process down in the same shell step. Future conformance classes must receive their own
root and process rather than joining this lifecycle; `new-class-root` remains the required entry point.

## Generated corpus

`tools/seed-corpus` invokes the leg's selected ffmpeg and creates 314 scanner-visible files. It covers
FLAC, MP3, Ogg, and M4A; Unicode metadata; two discs; a semicolon-delimited album-artist set; a track
with no album or album-artist tag; a title longer than 300 characters; a 300-track paging album; and
29- and 31-second threshold tracks plus a dedicated silent UI playback canary. All samples are
synthesized tone or silence. No audio file or
copyrighted recording is committed.

The generator runs `ffprobe` against every non-paging awkward fixture and validates the probed
container, exact tag values, tag-key absence, and threshold durations. A present-but-empty tag is not
absent. The expected suffix-to-container token and the normalized raw `ffprobe` `format_name` are
retained separately, so a multi-token observation remains visible even when it contains the required
token. Paging tracks are not filename-only copies: each of all 300 gets a distinct deterministic
ID3v2.4 title and track number. The generator parses all 300 tags back and also requires ffprobe to
interpret tracks 1, 150, and 300 correctly. The contract values and retained observations are written
to separate fields in `corpus-manifest.json`; the health check compares the complete evidence
structure to the contract. Any encoding, parsing, tag, duration, count, or format mismatch errors the
job.

## Fail-loud precondition gate

`tools/conformance-env/health-check` runs before a conformance test class and exits nonzero unless it
proves all of the following:

- the fresh server is reachable and reports Navidrome 0.63.2 / Subsonic 1.16.1;
- the scan reports complete, reports no scanner error, equals the generated file count exactly, and returns the
  known FLAC health-probe item through `search3`;
- the leg's ffmpeg command exists and the parsed version token in its first line exactly equals the pin;
- fixed admin and restricted fixture roles are read back from the server with the expected persisted
  `isAdmin` values;
- the server advertises the `transcoding` extension but not `sonicSimilarity`;
- `getTranscodeDecision` returns `canTranscode: true` for the known FLAC probe and an MP3-only client;
- the process runtime and effective time zone match the requested leg and UTC;
- every successful Subsonic response envelope reports version 1.16.1, rather than merely receiving
  that version as a request parameter.

There is no skip path. A missing precondition is an error, and every successful assertion is printed
to both the job log and the GitHub Actions job summary.

## CI identities

The Linux preconditions, `:core-conformance:jvmTest`, a fail-loud cold-cache reset, and
`:core-conformance:testAndroidHostTest` are job `conformance-env-linux` in `.github/workflows/core-ci.yml`.
That job uploads a separate Android-only JUnit artifact. The required `core-ci` context downloads that
artifact and resolves every Android/AndroidTV `FEATURES.yml` evidence identity against an executed,
passing, non-skipped testcase before checking the upstream results. It runs even after an upstream
failure or skip and passes only when the evidence verification succeeds and both `core-build` and
`conformance-env-linux` report `success`. The Darwin preconditions and the macOS, iOS simulator, and
tvOS simulator native conformance tasks are a serial tail of the sole `apple-ci` job in
`.github/workflows/apple-ci.yml`; they do not allocate a second hosted-macOS job. That tail restarts
the Darwin server against the same root after clearing its stopped transcode cache, then requires an
observed `cached=false` record before every platform task. Both workflows use standard hosted
runners, explicit job timeouts, and cancel-in-progress concurrency.
