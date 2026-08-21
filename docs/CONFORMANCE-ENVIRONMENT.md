# Deterministic conformance environment

This is the Phase 1 environment described by design §20.2. It creates disposable Navidrome instances
for protocol tests; it is not a development connection to any existing server.

## Two pinned legs

Both legs run Navidrome 0.63.2 and the same generated corpus:

| leg | Navidrome pin | ffmpeg pin |
|---|---|---|
| Linux/amd64 | `deluan/navidrome` manifest digest in `tools/conformance-env/pins.json` | ffmpeg 6.1.1 inside that immutable image filesystem |
| Darwin/arm64 | upstream release asset and SHA-256 in `tools/conformance-env/pins.json` | Homebrew arm64 Tahoe ffmpeg 9.0.1 bottle; formula metadata, cached bottle SHA-256, and installed version are all asserted |

The checked-in `navidrome.toml.template` is rendered only into the hosted runner's temporary
directory. It fixes the scanner, transcoder concurrency, UTC time zone, disabled similarity/external
providers, log redaction, cache sizes, and localhost-only address. The Darwin and Linux path values are
the only substitutions.

## Fresh database per test class

`tools/conformance-env/new-class-root` creates a uniquely named root containing new data, cache,
configuration, logs, and music directories. The environment contract is one root and one Navidrome
process per conformance test class. Reusing a data directory is not supported, so play counts,
playlists, favourites, queues, and users cannot leak between classes.

The current PR has no CONF-xx tests. Its `linux-self-check` and `darwin-self-check` roots exercise the
same creation path that future test classes use.

## Generated corpus

`tools/seed-corpus` invokes the leg's selected ffmpeg and creates 313 scanner-visible files. It covers
FLAC, MP3, Ogg, and M4A; Unicode metadata; two discs; multiple album artists; a track without an album;
a title longer than 300 characters; a 300-track paging album; and 29- and 31-second threshold tracks.
All samples are synthesized tone or silence. No audio file or copyrighted recording is committed.

The generator runs `ffprobe` against representative files, verifies their titles and durations, and
writes `corpus-manifest.json`. Generation errors fail the job.

## Fail-loud precondition gate

`tools/conformance-env/health-check` runs before a conformance test class and exits nonzero unless it
proves all of the following:

- the fresh server is reachable and reports Navidrome 0.63.2 / Subsonic 1.16.1;
- the scan reports complete, reports no scanner error, covers the generated file count, and returns the
  known FLAC health-probe item through `search3`;
- the leg's ffmpeg command exists and its first version line matches the pin;
- fixed admin and restricted fixture roles were created in the new database;
- the server advertises the `transcoding` extension but not `sonicSimilarity`;
- `getTranscodeDecision` returns `canTranscode: true` for the known FLAC probe and an MP3-only client;
- the time zone is UTC and the generated-corpus manifest matches the contract.

There is no skip path. A missing precondition is an error, and every successful assertion is printed
to both the job log and the GitHub Actions job summary.

## CI identities

The environment is wired into `.github/workflows/core-ci.yml` as jobs `conformance-env-linux` and
`conformance-env-darwin`. Both use standard GitHub-hosted runners, explicit job timeouts, and the
workflow's cancel-in-progress concurrency rule.
