# Deterministic conformance environment

This is the Phase 1 environment described by design §20.2. It creates disposable Navidrome instances
for protocol tests; it is not a development connection to any existing server.

## Two pinned legs

Both legs run Navidrome 0.63.2 and the same generated corpus:

| leg | Navidrome pin | ffmpeg pin |
|---|---|---|
| Linux/amd64 | `deluan/navidrome` manifest digest in `tools/conformance-env/pins.json` | ffmpeg 6.1.1 inside that immutable image filesystem |
| Darwin/arm64 | upstream release asset and SHA-256 in `tools/conformance-env/pins.json` | ffmpeg 9.0.2 built in CI from the SHA-256-pinned upstream source of LAME 4.0, Opus 1.6.1 and FFmpeg 9.0.2 |

### The Darwin ffmpeg is built from pinned source

`tools/conformance-env/install-darwin-ffmpeg` compiles the Darwin leg's ffmpeg from three upstream
source tarballs listed under `ffmpeg.darwin.sources`: LAME, Opus and FFmpeg, each with its version,
archive name, SHA-256 and one or more https URLs. No package index is consulted, so nothing upstream
can move the pin; the only upstream dependency left is that some listed URL still serves the pinned
bytes, and LAME and Opus each list a byte-identical mirror.

What a run does, in order:

1. **Every pin is validated before any download**: three sources present once each, 64-digit
   lowercase SHA-256, bare archive names, https URLs only, the FFmpeg source version equal to the
   pinned version, and `path` equal to `prefix/bin/ffmpeg`.
2. **Reuse or build.** If the pinned prefix holds a build whose manifest (`DULCET-BUILD.json`) names
   the current build digest and every listed file hashes to its recorded value, with no other file
   present, it is reused. The digest covers the pin, the installer itself, and the selected Apple
   clang and macOS SDK. Otherwise the prefix is discarded and rebuilt.
3. **Build.** Each tarball is downloaded, hashed, and extracted only if it equals the pin; the file
   hashed is the file extracted, and a mismatch at every URL fails the run before any extraction or
   build command. LAME and Opus are built as static libraries; FFmpeg is configured with
   `--disable-autodetect` (only zlib from the SDK, `libmp3lame` and `libopus` are enabled), without
   ffplay, documentation or network protocols, and only `ffmpeg` and `ffprobe` are installed. The
   build's PATH is `/usr/bin:/bin:/usr/sbin:/sbin` plus a pkg-config stand-in that answers for the
   work directory's Opus and nothing else, so no host library or `.pc` file can leak in. The compiler
   and SDK are those of the selected Xcode.
4. **Verify, always**, whether the build is fresh or reused: the exact version token of both `ffmpeg
   -version` and `ffprobe -version` equals the pin; `-encoders` lists every `required_encoders` entry;
   `otool -L` shows only `/usr/lib/` and `/System/Library/` libraries; and a one-second encode through
   each of `libmp3lame` and `libopus` produces output. The run prints `DARWIN FFMPEG PASS` with the
   observed values and both binaries' SHA-256.

`seed-corpus` resolves `ffprobe` as the sibling of the pinned `ffmpeg`, and every consumer reads the
binary's location through `read-pin ffmpeg.darwin.path`.

**Caching.** `apple-ci`'s conformance job and the contention soak compute the key with
`install-darwin-ffmpeg cache-key` after Xcode selection, restore the prefix with
`actions/cache/restore`, run the installer, and save the prefix with `actions/cache/save` only on a
miss and only after verification passed. A cache miss costs the compile: 151 s with 3 jobs on an M2 Max (OBSERVED 2026-10-03), estimated at 4 to 8 minutes on the 3-core hosted macos-26 runner until a CI miss measures it.

**Limits.** The manifest detects a corrupt or partial restore; it is not adversarial provenance,
because it travels in the same cache entry as the binaries. A cache entry is written only by a
workflow run of this repository, and pull requests from forks cannot write to the base branch's
cache scope; the same standard ephemeral hosted runner trust as before applies. With one toolchain
the build is reproducible: two builds in different work directories produced byte-identical `ffmpeg`
and `ffprobe` (OBSERVED locally, 2026-10-03, once `ZERO_AR_DATE=1` stopped archive timestamps from
reaching the linker's UUID; without it the two differed in 48 bytes). Across compilers it is not
claimed to be, which is why the compiler and SDK are part of the key. The
binary is deliberately smaller than Homebrew's (no video encoders, SDL, TLS or network protocols);
the Darwin leg asserts transport and protocol behaviour, not transcoded bytes (design spec §20.2.1).

**Negative controls**, all run in `apple-conformance` before the install:
`test-darwin-pin-prevalidation-gate` (ten malformed pins rejected with zero downloads or
subprocesses), `test-darwin-source-integrity-gate` (a tampered tarball at every URL is rejected with
no extraction and no build command; a tampered first URL falls back to an authentic mirror), and
`test-darwin-ffmpeg-verification-gate` (a wrong `ffprobe` version, a missing `libopus`, an encode
that produces nothing, a Homebrew library in the linkage, and a restored install with a tampered
binary, an extra file or another build digest are each rejected). `test-ffmpeg-version-gate` keeps
the exact-token comparison honest against a prefix collision.

The checked-in `navidrome.toml.template` is rendered only into the hosted runner's temporary
directory. It fixes the scanner, transcoder concurrency, UTC time zone, disabled similarity/external
providers, log redaction, cache sizes, and localhost-only address. The Darwin and Linux path values and
the port are the only substitutions; the second server below also drops exactly one line.

### The second, default-`PurgeMissing` server

The reader suite (`ReaderServerConformanceTest`, CONF-70..75, design spec §16.11) needs a second
server whose configuration differs from the fixture's in exactly one setting: `PurgeMissing` is left
at Navidrome's default instead of `"always"`. Its library differs too: it holds only the two albums
the suite needs, where the fixture serves the whole corpus. `render-config --purge-missing
server-default --port 4534` renders it from the same template, `purge-default-server prepare` copies
the two corpus albums into its own music folder, and `purge-default-server bootstrap` creates the
fixed admin and waits for the first scan.

Only the runs that execute CONF-74 start it. On Linux it is a second container on `127.0.0.1:4534`,
started by `linux-local up --purge-default`; core-ci's conformance job passes that flag, and a plain
`linux-local up` (the Android emulator jobs') starts no second server and declares none of its
variables. In apple-ci's Darwin conformance step it is a second native process that runs only around
the macOS conformance leg: it starts after that leg's cold restart of the fixture server and stops as
soon as the leg ends, so no simulator phase and no other restart runs beside it. The suite moves an
album directory out of each server's music folder and back, and waits for the scanner's watcher, so
it reads three more variables: `DULCET_CONFORMANCE_MUSIC_DIR`,
`DULCET_CONFORMANCE_PURGE_DEFAULT_BASE_URL` and `DULCET_CONFORMANCE_PURGE_DEFAULT_MUSIC_DIR`. A
missing variable fails the test; it never skips.

## One-command Linux environment

The Linux/amd64 environment used by `core-ci.yml` is also the local environment. Docker Desktop (or
Docker Engine on Linux) must be running; no separately installed Navidrome or ffmpeg is used.

```bash
tools/conformance-env/linux-local up --purge-default
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
disposable loopback environment as the host process. The macOS run includes the reader suite, so
`run-apple` refuses to start unless the root was brought up with `up --purge-default`.

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

### Opt-in fixture: the Skip Probe albums

`tools/seed-skip-probe` adds three more albums for the automatic-skip UI proofs (spec §12.12). Each
holds an unplayable MP3 whose bytes are fixed and pinned by SHA-256, then a 40-second playable tone.
"Skip Probe" names its undecodable track "Unplayable Probe"; "Long Title Skip Probe" gives its
undecodable track a 70-character title, for the proof that the notice shows its shorter sentence at
the largest text size. "No Audio Skip Probe" is for Android: its first track is a tag followed by
text, with no MP3 frame at all, because Android's software MP3 decoder plays the undecodable track's
frames as sound and reports no error (spec §12.12 rule 8), while a file in which Media3's extractors
recognise no format fails before any decoder sees it. They are separate albums so that no proof's queue plays on into
another's tracks. None is ever part of the default corpus above, because that corpus is counted
exactly — the health check requires its 314 files and the conformance suites count its albums. The
UI harness runs the tool against a server that has already passed the health check, with
`--base-url`, and the tool rescans and waits until all six tracks are listed. No conformance class
runs against a server they have been added to: in `apple-ci` the conformance composite adds them in
its last phase, after every class and every UI proof that counts or searches the library.

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
`conformance-env-linux` report `success`. `.github/workflows/apple-ci.yml` has the same shape (spec
§21.5): the Darwin preconditions and the macOS, iOS simulator, and tvOS simulator native conformance
tasks run serially in hosted-macOS job `apple-conformance-core`; the play-count canary and the iPadOS
and iPhone app proofs run in a second hosted-macOS job, `apple-conformance-ipad-iphone`, against its
own disposable server; both run in parallel with the platform legs in `apple-platform`, and the
required `apple-ci` context is a Linux aggregator. Since spec §21.6 the conformance jobs run on pushes
to `main` and manual dispatches, not on pull requests, and the platform job runs on a pull request
only when it changes an Apple input; the aggregator passes only when every leg the run planned
reports `success` and every other leg reports `skipped`, and it resolves the Apple evidence
identities on runs that planned every leg. A release build requires every leg green on the commit it
archives. `apple-conformance-core` restarts
the Darwin server against the same root after clearing its stopped transcode cache, then requires an
observed `cached=false` record before every platform task. Both workflows use standard hosted
runners, explicit job timeouts, and cancel-in-progress concurrency.
