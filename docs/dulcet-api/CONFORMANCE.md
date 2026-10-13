# Dulcet API conformance suite: plan

This is the plan for a public, runnable suite that proves a server or a client implements Dulcet
API v1. The suite does not exist yet; this document is what its build follows. Profiles are
defined in [README §20](README.md#20-claiming-compatibility).

## Goals

1. **Anyone can run it** against their own server or client with one command, offline, with no
   account anywhere.
2. **A pass means something.** Every test can fail, and the suite proves it: each check has a
   negative control (a deliberately broken reference behaviour) that must make it fail.
3. **A missing capability fails, never skips,** for any profile being claimed. Skips read as green.
4. **The report is evidence.** It is machine-readable, names the suite version, the profiles, the
   server's `instanceId` and version, and every check's result, so a compatibility claim can link
   to it.

## Shape

- **Language and packaging.** A Kotlin/JVM command-line tool in this repository, published as a
  container image and a runnable JAR under Apache-2.0. Kotlin matches the reference server and lets
  the suite reuse its HTTP stack; the container means nobody needs a JVM.
- **Contract-driven.** The suite loads `openapi.yaml` and validates every response it receives
  against the schema of that operation and status (status code, media type, required fields, types,
  formats). Unknown fields are allowed, as the contract says.
- **Fixtures.** A generated, royalty-free audio corpus (tones and noise, in FLAC, ALAC, AAC, MP3 and
  Opus, with known tags, artwork, embedded and sidecar lyrics, multi-disc albums, compilations,
  duplicate titles and non-ASCII names). The suite ships the generator, not the audio, and the
  corpus is identical everywhere.
- **Server under test.** The operator points the suite at a base URL, a library directory the
  server scans, and admin credentials for a disposable instance. The suite creates its own users
  and never runs against a server holding real people's data: it refuses to start unless the server
  reports exactly the fixture corpus.
- **Mutations the suite needs** (retag a file, move a file, delete one, add one, trigger a scan) go
  through a small adapter: by default, file operations on the mounted library directory plus
  `POST /admin/scans`.
- **Output.** JUnit XML (one test case per check id) plus a JSON report and a one-line summary:
  `DULCET-CONFORMANCE suite=1.0.0 profiles=Core,Sync passed=N failed=M`.

## Server checks, by profile

Each check has a stable id (`DA-<area>-<n>`) so reports and claims can cite it.

### Core

| Area | What is proven |
|---|---|
| Discovery | `/.well-known/dulcet` shape; base URL is `/dulcet/v1`; capabilities without a token show only public data, with a token add `permissions`; `apiVersion` matches `1.x`. |
| OAuth | Code flow with S256 PKCE succeeds; `plain` PKCE, a wrong verifier, a reused code and a mismatched redirect URI are refused; refresh rotates; a replayed refresh token revokes the grant; revocation stops the access token and its signed media URLs at once; a client id metadata document is fetched and its name shown. |
| Tokens | Personal tokens carry only requested scopes; a scope above the user's role is refused; the secret is shown once; prefixes are correct. |
| Header-only | A token in any query parameter is refused (never accepted, never echoed). |
| Scopes | Every operation is called once with a token lacking its scope and must answer `403 insufficient-scope` with `requiredScopes`. |
| Catalog | Lists page to completion with no duplicates and no gaps against the known corpus; `nextCursor` null only at the end; `ids` batch fetch; every `sort` value orders correctly with ties broken stably; `scope=library` follows the My Library rule. |
| Ids | Ids are unique across types; a retag (title change) keeps the track id; a file move keeps it; a deleted id is not reused; a merged album answers `308` to the survivor. |
| Pagination under change | Adding and deleting albums in the middle of a walk never makes the walk skip an album that existed throughout (the failure OpenSubsonic offset paging shows). |
| ETags | `If-None-Match` gives `304`; a change gives a new ETag; `If-Match` mismatch gives `412` with `currentRevision`; replacing playlist items without `If-Match` gives `428`. |
| Idempotency | A repeated `POST` with the same key returns the first response and creates one resource; the same key with a different body gives `422`. |
| Artwork | Image bytes with a real image media type; the artwork id changes when the art changes; `immutable` caching. |
| Playback | A direct plan's URL serves the original bytes (checksum) with `Range` 206 and 416; expired and tampered grants give `403 media-grant-expired`; a revoked token kills its grants; the grant never appears in any response other than the plan; a decision records no play. |
| Item state | Favourite, rating and My Library writes are idempotent and keep first timestamps; removal of a missing or unknown id succeeds; `POST /me/batch` reports per-operation results. |
| My Library | All `dulcetLibrary` semantics: per-item membership, no cascade, whole-library counts, newest-first order with id tie-break, isolation between users. |
| Plays | A play is counted once per id however many times it is submitted; an unknown track gives `unknownTrack`; now playing changes no count and expires after its TTL. |
| Playlists | Entry ids allow duplicates to be moved and removed exactly; every edit changes `revision`; a walk across an edit answers `409`; non-owners see `readOnly`. |
| Export | The export parses as JSON Lines and holds exactly the user's favourites, ratings, library, playlists and plays, and no other user's. |
| Errors | Every non-2xx body is `application/problem+json` with a known `type` for its status. |
| CORS | Preflight allows the documented request headers and exposes the documented response headers; no API response sets a cookie. |
| Logging hygiene | Canary tokens, passwords and grants are sent through every endpoint; the server's own log (collected through the adapter) must not contain any canary. |
| Rate limits | When the server's limiter is lowered through the adapter, a burst gets `429` with `Retry-After`. |

### Sync

- `start=now` returns no changes and a usable cursor.
- `start=snapshot` delivers every visible entity of the corpus exactly as the catalog endpoints do,
  then the `changes` phase; changes made **during** the snapshot (adds, deletes, favourites) all
  arrive; nothing is missing at the end, compared against a fresh catalog walk.
- Every kind of change appears: catalog add, retag, delete (tombstone), merge (`replacedBy`), each
  item-state field, playlist edit, play, queue save.
- A `Dulcet-Sync-Cursor` from any read, followed by the feed, reproduces the current state.
- User isolation: user A never sees user B's state, nor a library A cannot access.
- A cursor older than retention (shortened through the adapter) and a cursor from before a database
  restore both give `410 cursor-expired`.
- Atomicity: a scan's changes become visible in the feed and the catalog together, never one
  without the other.

### Other profiles

- **Device:** codes, `authorization_pending`, `slow_down`, expiry, approval and denial.
- **Transcoding:** a profile without the source codec gets a transcode in a requested target; a
  bitrate limit is honoured (bits per second); `lengthExact: false` plans really are estimates;
  `startMs` starts at the right time (within one frame).
- **Lyrics:** sidecar, embedded and absent cases; synced times with offset applied; languages.
- **Queues:** per-device save and read, `If-Match` conflict, listing order.
- **Events:** each event type fires for its trigger; `Last-Event-ID` resumes; a revoked token gets
  `revoked` and a closed stream; heartbeats arrive in time.
- **Webhooks:** signature verifies with the shown secret; retries on 500 with backoff; disable after
  persistent failure; private-network targets refused by default.
- **MCP:** initialization and tool listing per scope (no write tools without `user:write`); every
  tool's result matches the equivalent API call; no tool can delete, administer or read another
  user's data; injected instructions in a track title come back as plain data.
- **Admin:** user create, update, delete; role removal revokes dependent tokens; scans report
  progress and counts.

## Client checks

A client cannot be tested by calling it, so the suite also ships a **reference mock server**: a
scriptable Dulcet API server, built from the contract, that records every request. Client authors
drive their own app (UI tests or a headless harness) through named scenarios, and the mock judges
what the client did.

| Scenario | The client must |
|---|---|
| Discovery fallback | Use Dulcet API when discovery succeeds, and not when it 404s. |
| Header-only | Never send a token in a URL; never send `Authorization` across origins after a redirect; refuse an HTTPS-to-HTTP redirect. |
| Forward compatibility | Keep working when every response carries unknown fields, unknown enum values, unknown change and event types, and unknown problem types. |
| Paging | Follow cursors to the end; not stop on a short or empty page with a cursor. |
| Sync | Store and resume the cursor; react to a `changes` event; restart cleanly on `410`. |
| Retries | Reuse the `Idempotency-Key` and the play id on retry; honour `Retry-After`. |
| Media | Request a new plan when a grant expires mid-session; not treat an estimated length as exact. |
| Plays | Submit only plays past its threshold; never submit for a URL it only resolved. |
| Redaction | Never write a canary token or grant to its logs (checked through the client's own log export). |

## Negative controls

The reference server gets a `--break=<check-id>` switch that disables exactly one behaviour (for
example, accept a token in the query, reuse a deleted id, drop tombstones). The suite's own CI runs
every check against the healthy reference server (must pass) and against the matching broken one
(must fail). A check without a working negative control does not ship.

## Versioning of the suite

The suite is versioned separately (`1.0.0`, …), and each release names the API minor version it
covers. A new suite version may add checks for new minor-version features, and a claim always names
the suite version it passed. Checks for v1 behaviour are never loosened.

## Build order

1. Contract validation harness, discovery, OAuth and tokens.
2. Catalog, ids, pagination under change, ETags, errors.
3. Media and plays.
4. Sync.
5. Item state, My Library, playlists, export.
6. Remaining profiles, client mock, negative controls in CI.

Dulcet Server's own CI runs the suite on every change to `:server`, on `ubuntu-latest`, against a
disposable instance.
