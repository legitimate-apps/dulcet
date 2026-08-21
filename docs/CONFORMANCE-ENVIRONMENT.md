# Deterministic conformance environment

This is the Phase 1 environment described by design §20.2. It creates disposable Navidrome instances
for protocol tests; it is not a development connection to any existing server.

## Two pinned legs

Both legs run Navidrome 0.63.2 and the same generated corpus:

| leg | Navidrome pin | ffmpeg pin |
|---|---|---|
| Linux/amd64 | `deluan/navidrome` manifest digest in `tools/conformance-env/pins.json` | ffmpeg 6.1.1 inside that immutable image filesystem |
| Darwin/arm64 | upstream release asset and SHA-256 in `tools/conformance-env/pins.json` | Homebrew arm64 Tahoe ffmpeg 9.0.1 plus its complete 14-formula runtime dependency closure; every formula version, revision, dependency edge, bottle rebuild, URL, and SHA-256 is locked |

The Darwin installer does not accept a name-only resolution. It first compares the complete current
official formula API graph to the lock. Only after that succeeds does it update Homebrew's possibly
stale runner metadata, and the refreshed local resolver must match the same lock before any bottle is
fetched or installed. It hashes every bottle and fails on any extra, missing, or changed dependency.
It records every locked keg's pre-install filesystem and receipt observation, removes the complete
closure in reverse dependency order, and requires an observed absence checkpoint for every locked keg.
It then asks Homebrew to install every locked formula from a bottle in dependency order and records
the post-install filesystem and receipt observation. Installing only the root is insufficient because
Homebrew can leave an already-installed nested dependency at an older version, while accepting an
existing matching-version keg would not bind its payload to the verified archive.
After installation it verifies every active keg, every bottle receipt, ffmpeg's recorded runtime
closure, and the `libmp3lame` and `libopus` encoders used by the corpus. The installer also computes a
hash of every installed keg payload, requires an absent-after-uninstall checkpoint and a
present-after-install observation with a readable receipt, and re-hashes the payloads before
reporting one aggregate. Installed payload hashes are deliberately not treated as cross-run pins:
Homebrew's post-pour relocation and signing made all 15 payload hashes differ across two fresh
standard hosted runners while their checksum-verified source archives remained identical.

**Limit — these observations do not establish bottle-to-installed-payload provenance.** They say
that the keg was absent after uninstall, present after install with a readable receipt, different from
the retained pre-install filesystem/receipt observation, and unchanged across two payload hashes in
this run. A copied keg can preserve its receipt and payload while changing its inode, so placing one
after the absence checkpoint can satisfy those observations without tying its bytes to the
checksum-verified bottle. Homebrew's relocation means the installed bytes also cannot be compared
soundly with the archive or pinned across runners. The negative control proves only that an untouched
retained keg is rejected. This narrower result is sufficient for a standard ephemeral hosted runner,
whose job state is discarded rather than carried forward as an adversarial keg cache; no stronger
provenance claim is made. The checks run before either the resource-loader fixture encoder or the
corpus encoder, so both use the same installed closure that passed the version, receipt, runtime,
encoder, and two-read payload checks.

The checked-in `navidrome.toml.template` is rendered only into the hosted runner's temporary
directory. It fixes the scanner, transcoder concurrency, UTC time zone, disabled similarity/external
providers, log redaction, cache sizes, and localhost-only address. The Darwin and Linux path values are
the only substitutions.

## Fresh database per test class

`tools/conformance-env/new-class-root` creates a uniquely named root containing new data, cache,
configuration, logs, and music directories. The environment contract is one root and one Navidrome
process per conformance test class. Reusing a data directory is not supported, so play counts,
playlists, favourites, queues, and users cannot leak between classes.

**This is not yet a CI-enforced per-class property.** The current PR has no CONF-xx classes, and
`new-class-root` is a utility rather than a test-runner hook. Its `linux-self-check` and
`darwin-self-check` callers each prove one isolated root and a fresh-admin bootstrap. Every future
Phase 1 test-class fixture must call this utility, start its own Navidrome process from that root, run
the fail-loud health check, and tear the process down. The reset claim becomes enforced only when the
class runner owns that lifecycle; until then it is a normative caller contract.

## Generated corpus

`tools/seed-corpus` invokes the leg's selected ffmpeg and creates 313 scanner-visible files. It covers
FLAC, MP3, Ogg, and M4A; Unicode metadata; two discs; a semicolon-delimited album-artist set; a track
with no album or album-artist tag; a title longer than 300 characters; a 300-track paging album; and
29- and 31-second threshold tracks. All samples are synthesized tone or silence. No audio file or
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

The Linux self-assertion is job `conformance-env-linux` in `.github/workflows/core-ci.yml`. The required
`core-ci` context is a final fail-closed aggregator: it runs even after an upstream failure or skip and
passes only when both `core-build` and `conformance-env-linux` report `success`. The Darwin
self-assertion is a serial tail of the sole `apple-ci` job in `.github/workflows/apple-ci.yml`; it does
not allocate a second hosted-macOS job. Both workflows use standard hosted runners, explicit job
timeouts, and cancel-in-progress concurrency.
