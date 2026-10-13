# Dulcet Server

Dulcet's own OpenSubsonic music server. The full design is in spec §29. The My Library extension
is described in [`dulcet-library-extension.md`](dulcet-library-extension.md).

## Run it

```sh
./gradlew :server:installDist
server/build/install/dulcet-server/bin/dulcet-server user add admin 'change-me' --admin --music=DIR --data=DIR
server/build/install/dulcet-server/bin/dulcet-server serve --music=DIR --data=DIR    # port 4533
```

### Docker

```sh
./gradlew :server:installDist
docker build -f server/Dockerfile -t dulcet-server server/build/install/dulcet-server
docker run -p 4533:4533 -v /path/to/music:/music:ro -v dulcet-data:/data \
  -e DULCET_ADMIN_USER=admin -e DULCET_ADMIN_PASSWORD=change-me dulcet-server
```

### Settings

Each setting can be given as `--name=value` or in the environment as `DULCET_NAME`.

| Setting | Default | Meaning |
|---|---|---|
| `music` | `./music` | Library root. The server only reads it. |
| `data` | `./data` | Database, key, artwork and transcode caches. |
| `port`, `bind` | `4533`, `0.0.0.0` | Listening address. |
| `ffmpeg`, `ffprobe` | on `PATH` | Tools for reading tags, artwork and transcoding. |
| `lrclib` | `on` | `off` turns off LRCLIB lookups for tracks that have no lyrics of their own. |
| `lrclib-url` | `https://lrclib.net` | LRCLIB endpoint. |
| `rescan-interval` | `0` (off) | Seconds between quiet change checks. |
| `scan-on-start` | `true` | Scan once at start. |

### Commands

```
dulcet-server serve | scan | user add <name> <password> [--admin] | user list
dulcet-server import navidrome --url=URL --user=NAME --password=PW --into=LOCAL_USER [--dry-run]
```

The importer reads the remote server with an ordinary OpenSubsonic login (`DULCET_IMPORT_PASSWORD`
also works). It copies that user's stars, ratings, play counts and playlists into the chosen local
user. Use `--dry-run` first: it prints how many songs matched by path, by MusicBrainz id and by
tags, and changes nothing.

## Conformance against the client's suites

Dulcet Server runs as a second target for `:core-conformance:jvmTest`. It uses the same corpus and
users as the disposable Navidrome, and the same environment (`docs/CONFORMANCE-ENVIRONMENT.md`)
with `DULCET_CONFORMANCE_BASE_URL` pointed at the server.

**Result (OBSERVED, 2026-10-12):**
- Dulcet Server: 66 of 71 JVM tests passed.
- Navidrome on `main` (CI run 38191212887): 71 of 71 passed.

| Suite | Tests | Dulcet Server failures |
|---|---|---|
| AccountConnect | 32 | 2 |
| LibrarySync | 3 | 0 |
| Lyrics | 2 | 0 |
| PlaybackScrobble | 13 | 0 |
| Playlist | 6 | 0 |
| Rating | 1 | 0 |
| ReaderServer | 8 | 2 |
| Search | 1 | 0 |
| StallDiagnostics | 4 | 1 |
| Star | 1 | 0 |

The five failures and why each stands:

| Test | Why |
|---|---|
| CONF-05 envelope fields | It expects `type: "navidrome"`. Dulcet Server reports `dulcet`, and every other envelope field matches. By design. |
| CONF-02 extension set | It records Navidrome's exact extension set, including `indexBasedQueue` and `playbackReport`. The client uses neither (it never calls `playbackReport`, TRAPS 7). Dulcet Server advertises only what it implements, plus `dulcetLibrary`. By design. |
| CONF-74 purge "always" | It expects a removed track's old id to stop resolving once the file is restored. Dulcet Server derives ids from content, so a restored file gets its old id back, and its stars, plays and playlist entries come back with it. By design. |
| CONF-74 server default | This test needs a second server instance (`DULCET_CONFORMANCE_PURGE_DEFAULT_BASE_URL`), and that was not provided. Environmental. |
| StallDiagnostics local auth failure | It checks that the base URL is on port 4533, the CI port. This run used port 4633. Environmental. |

Fixed during bring-up, each found by a suite:
- `lastScan` moved on quiet periodic checks.
- FLAC bitrate counted metadata.
- Decision bitrates were read as kbps instead of bits per second.
- Transcodes had no exact length or byte ranges.
- `getTranscodeStream` error shapes were wrong.
- songLyrics v2 was missing.
- `X-Total-Count` was missing.
- Playlist `readonly`, the admin view and empty-edit semantics were wrong.
- Scrobbles of unknown ids were rejected.
- A 2400-entry playlist replace was cut to 999 entries by Ktor's 1000-parameter limit.
