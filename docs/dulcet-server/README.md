# Dulcet Server

Dulcet's own music server serves OpenSubsonic and Dulcet API v1. The design is in spec §§29–30.
The My Library extension is described in [`dulcet-library-extension.md`](dulcet-library-extension.md).

## Run it

```sh
./gradlew :server:installDist
server/build/install/dulcet-server/bin/dulcet-server user add admin --admin --music=DIR --data=DIR
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
| `public-url` | request origin | Public server origin, such as `https://music.example`, for OAuth metadata, redirects and media URLs behind a proxy. |
| `trusted-proxies` | empty | Comma-separated literal proxy IP addresses or IPv4/IPv6 CIDRs allowed to assert client addresses. See reverse proxy setup below. |
| `webhook-allow-private-targets` | `false` | Allow private and loopback HTTPS webhook targets; link-local and metadata addresses remain refused. |
| `webhook-allow-other-ports` | `false` | Allow HTTPS webhook target ports other than 443. |
| `test-mode` | `false` | Enable the Testing profile on a disposable data directory. Data previously served without test mode refuse this setting. |

### Commands

```
dulcet-server serve | scan | user add <name> <password> [--admin] | user list
dulcet-server import navidrome --url=URL --user=NAME --password=PW --into=LOCAL_USER [--dry-run]
```

The importer reads the remote server with an ordinary OpenSubsonic login (`DULCET_IMPORT_PASSWORD`
also works). It copies that user's stars, ratings, play counts and playlists into the chosen local
user. Use `--dry-run` first: it prints how many songs matched by path, by MusicBrainz id and by
tags, and changes nothing.

Put the listener behind a TLS reverse proxy before exposing it to a network: OpenSubsonic sends
credentials in query parameters. Disable access logs containing query strings at the proxy too.
Failed authentication is limited to ten attempts per client IP per minute. Forwarded headers are
ignored unless the socket peer matches an explicitly configured trusted proxy.

The data folder must support POSIX owner-only file permissions for creating `secret.key`.

`user add <name>` prompts for a password on a terminal, or reads `DULCET_USER_PASSWORD`.
The import command reads `DULCET_IMPORT_PASSWORD`. Prefer those paths over password arguments,
which appear in process listings and shell history.

### Behind a reverse proxy or tunnel

Set `public-url` to the external origin and `trusted-proxies` to the proxy addresses **as seen by
Dulcet's socket listener**. For example, replacing the documentation address with your proxy:

```sh
export DULCET_PUBLIC_URL=https://music.example
export DULCET_TRUSTED_PROXIES=192.0.2.10/32,2001:db8:42::10/128
dulcet-server serve --music=DIR --data=DIR
```

The equivalent argument is `--trusted-proxies=192.0.2.10,2001:db8:42::10`. The empty default trusts
no forwarded headers and retains socket-peer limits. Hostnames, invalid addresses and invalid
CIDRs are refused at startup. With Cloudflare Tunnel, the socket peer is normally the connector
or a local network intermediary, rather than a Cloudflare edge address; check the actual peer.

For a trusted peer, the shared client-address policy used by OpenSubsonic failed-login limits,
Dulcet API IP limits and OAuth IP limits selects:

1. `CF-Connecting-IP`, if present.
2. Otherwise `X-Real-IP`, if present.
3. Otherwise the right-most untrusted address in `X-Forwarded-For`, walking past trusted proxy hops
   from right to left. Claims farther left cannot override that boundary.

Values must be literal IPs, without ports, brackets or IPv6 zone identifiers. Equivalent IPv6 and
IPv4-mapped forms share limiter keys. A malformed or repeated selected header, malformed chain,
more than 64 hops, a header over 8 KiB, or a chain containing only trusted hops falls back to the
socket peer. A malformed higher-priority header never enables a lower-priority fallback. Headers
from an untrusted socket peer are always ignored.

Only trust proxies you control, using the narrowest address list. They must overwrite or remove
client-supplied higher-priority headers: a plain reverse proxy that only appends `X-Forwarded-For`
must also strip incoming `CF-Connecting-IP` and overwrite or strip `X-Real-IP`. Cloudflare supplies
`CF-Connecting-IP` for its visitor; do not allow another route to the trusted proxy to supply an
unchecked value. Keep per-client edge limits too. Trusting all addresses would let direct clients
choose their own limiter keys. This setting does not change authentication or trust forwarded
host/scheme headers; `public-url` remains the canonical external origin.

## Dulcet API v1

The server serves `/dulcet/v1` alongside `/rest`, using the same catalog database and ids.
Discovery is at `/.well-known/dulcet`; the [API contract](../dulcet-api/README.md) defines the
resources, scopes and wire formats. Catalog and user-state reads and writes, the change feed,
shared queues and devices, SSE events, signed webhooks and the MCP endpoint are available.
MCP uses `/dulcet/v1/mcp` and the same scopes as the HTTP API.

Clients authenticate through OAuth with PKCE, the device flow, or personal tokens. Dulcet API
credentials go only in the `Authorization: Bearer` header. Signed media and artwork paths carry
short-lived grants so a player can request bytes directly, including ranges.

To mint a personal token for an existing user:

```sh
server/build/install/dulcet-server/bin/dulcet-server user token USER \
  --data=DIR --scopes=catalog:read,media:read,user:read
```

The command prints the secret once. Choose only the scopes the client needs; write and removal
scopes are separate. Back up the entire data folder, including its keys and API timeline files.

The [conformance instructions](../dulcet-api/CONFORMANCE.md) describe the profiles. Testing hooks
require `--test-mode=true` at startup and an admin token; they cannot enable test mode remotely.
Their overrides live in memory and reset on restart. Normal deployments leave test mode disabled.

## Bundled FFmpeg

Dulcet Server is Apache-2.0. The Docker image also distributes Alpine's FFmpeg executables as
separate programs. Alpine 3.20 lists that package as GPL-2.0-or-later AND LGPL-2.1-or-later; those
licenses apply to FFmpeg and its libraries, independently of Dulcet's license.
See the [Alpine package and exact build revision](https://pkgs.alpinelinux.org/package/v3.20/community/aarch64/ffmpeg),
the [Alpine build recipe and patches](https://gitlab.alpinelinux.org/alpine/aports/-/tree/3.20-stable/community/ffmpeg),
and [FFmpeg source releases](https://ffmpeg.org/releases/) for the corresponding sources.
A published image should retain its installed package versions and make the matching source,
patches and license notices available with that image.

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
| CONF-74 purge "always" | It expects a removed track's old id to stop resolving once the file is restored. Dulcet Server persists path identities and content fingerprints, so a restored file gets its old id back, and its stars, plays and playlist entries come back with it. By design. |
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

## Discover and Wishlist

Optional independent version 1 extensions: [dulcetDiscover](dulcet-discover-extension.md) and
[dulcetWishlist](dulcet-wishlist-extension.md). The contracts include methods, payloads, paging,
errors, settings and durable offline operation sequences for client implementations.

Server-only verification for these contracts is recorded in
[Discover and Wishlist evidence](../evidence/discover-wishlist-server.md). The seven feature rows
retain planned app cells until the later core and native lanes supply their own evidence.

### Scan identity and upgrade adoption

The scanner compares each audio file's path, size, precise mtime, Unix inode change time and
file key, in addition to the existing sidecar and audio-fingerprint checks. This detects tag and
embedded-picture edits that preserve size and mtime. Its versioned `track_file_identity_v1`
table is separate from catalog rows so an older scanner cannot reset the stronger baseline.
Failed probes retain the previous identity and retry on the next scan.

On upgrade, an existing file that passes the old unchanged checks adopts its current identity
without a tag read or catalog event. This intentionally cannot discover a same-size,
preserved-mtime edit made before that file's first baseline capture, including one racing that
capture. Reading every tag would be required to recover that information. Subsequent identity
changes are detected normally. Ordinary size/mtime changes and missing fingerprints still
require reads during adoption.

`chmod`, `chown` and hard-link changes can advance ctime without changing audio. Each affected
catalog path is conservatively re-probed once; the new identity then becomes its baseline.
Reads, atime updates and edits to a different sidecar do not change the audio inode's ctime.
A bulk permission/ownership operation can therefore require a library-wide re-read. Providers
without Unix ctime use the existing content-digest fallback, which reads whole source files;
check mount behavior before relying on metadata-only scan cost.

Scanner logs include aggregate `tracks`, `tags_read` and `identities_adopted` work counts.
Listen Now warms selected originals with four shared permits before serial resource rendering.
It retains content-addressed artwork IDs and current per-user source checks; repeated references
to one embedded source share extraction work.

### Discover upstream diagnostics

Logger `dulcet-discover-upstream` emits `discover_upstream` records with bounded per-origin
counters. Known providers have fixed labels; custom/redirect origins have opaque labels,
limited to 64 individual buckets plus overflow. Records contain no raw hostnames, paths,
queries, search text, user IDs, response bodies, credentials or exception messages.

`event` distinguishes HTTP completion, decode failure, refresh failure and stale serving.
`outcome` distinguishes HTTP status failure, timeout, transport/DNS failure, active backoff,
waiting-cap refusal, malformed body, parse failure, cancellation and local cache-write failure.
Each record includes status class and duration where available, cumulative status classes and
duration, applied/refused backoff counts, timeout counts, waiting-cap refusals, parse failures,
refresh failures and stale serves. `attempts` includes refusals/cancellations; `requests` counts
wire starts. HTTP timeouts and failed-refresh timeouts are separate counters, not additive.
These process-local counters reset on restart. Cancellation propagates and never serves stale.

A freshness gate should save the response's `stale` boolean and the allowlisted upstream records
from the request's log window even on failure. `tools/server/discover_verification.py` supplies
that projection. It deliberately omits response items and raw log lines. Concurrent or adjacent requests in an overlapping log window can contribute to the same
origin's evidence, so it attributes an origin outcome, not a particular query. A log-read
failure records diagnostics as unavailable without masking the primary HTTP outcome. A missing stale flag is reported as unknown rather than false.
