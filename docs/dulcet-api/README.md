# Dulcet API v1

Dulcet API is an open standard for music servers and the apps that talk to them. It covers a
catalog, streaming, per-user state (favourites, ratings, plays, playlists, My Library), sync,
live events, webhooks and a tool endpoint for AI agents. Anyone may implement it, on either side,
under Apache-2.0.

- **The contract** is [`openapi.yaml`](openapi.yaml) (OpenAPI 3.1). When this guide and the
  contract disagree, the contract wins, and this guide gets fixed.
- **For language models**, [`llms.txt`](llms.txt) is a compact version of this guide.
- **To prove compatibility**, see [`CONFORMANCE.md`](CONFORMANCE.md).
- **Dulcet Server** is the reference implementation. The Dulcet apps speak Dulcet API to any
  server that offers it, and OpenSubsonic to every other server.

Lint the contract with the pinned linter:

```sh
npx --yes @redocly/cli@2.54.3 lint --config docs/dulcet-api/redocly.yaml docs/dulcet-api/openapi.yaml
```

## Contents

1. [Principles](#1-principles)
2. [Discovery](#2-discovery)
3. [Auth](#3-auth)
4. [Resources and ids](#4-resources-and-ids)
5. [Pagination](#5-pagination)
6. [Caching, conditional requests and idempotency](#6-caching-conditional-requests-and-idempotency)
7. [Sync: the change feed](#7-sync-the-change-feed)
8. [My Library](#8-my-library)
9. [Media URLs and playback](#9-media-urls-and-playback)
10. [Plays and now playing](#10-plays-and-now-playing)
11. [Lyrics, playlists, queues and search](#11-lyrics-playlists-queues-and-search)
12. [Events and webhooks](#12-events-and-webhooks)
13. [AI and MCP](#13-ai-and-mcp)
14. [Errors](#14-errors)
15. [Security and privacy](#15-security-and-privacy)
16. [Capabilities and extensions](#16-capabilities-and-extensions)
17. [Evolution and deprecation](#17-evolution-and-deprecation)
18. [Building a server](#18-building-a-server)
19. [Building a client](#19-building-a-client)
20. [Claiming compatibility](#20-claiming-compatibility)
21. [Prior art, and why not extend OpenSubsonic](#21-prior-art-and-why-not-extend-opensubsonic)

Each design choice below is followed by a **Why** line. Argue with the reason, not the rule.

---

## 1. Principles

1. **Resources over methods.** Every thing has a URL (`/albums/{id}`), and HTTP verbs do the
   obvious thing to it. *Why:* anyone who has used an HTTP API can read it, generic tools (caches,
   proxies, OpenAPI generators, AI agents) understand it, and new resources slot in without new
   conventions.
2. **HTTPS and JSON only.** No XML, no JSONP, no alternate formats. *Why:* one encoding means one
   parser and one set of edge cases for every implementer.
3. **Secrets never go in a URL.** Tokens travel in `Authorization`. The only URLs that grant
   access are signed media URLs, which reach one resource for a short time. *Why:* URLs end up in
   logs, proxies, browser history, crash reports and screenshots.
4. **Ignore what you do not know.** Clients ignore unknown fields, unknown enum values, unknown
   change types and unknown event types. *Why:* this is what lets v1 grow for years without breaking
   a deployed client.
5. **Additive only within v1.** Nothing documented is removed or changes meaning. *Why:* clients
   ship through app stores and live on devices for years; a server upgrade must never break them.
6. **One way to do each thing.** One sync mechanism, one pagination style, one error format, one
   auth header. *Why:* every option is a branch every client must write and test.
7. **Built for the people who use it, not for lock-in.** Every user can export everything the
   server holds about them (`GET /me/export`). Catalog items carry MusicBrainz ids and ISRCs, so
   data moves between servers and services. *Why:* a personal music library and its history belong
   to the person.
8. **Units are fixed.** Durations are integer milliseconds (`durationMs`), timestamps are RFC 3339
   UTC strings, bitrates are bits per second, ratings are integers 1 to 10. *Why:* a unit in the
   field name, fixed across the API, removes a whole class of bug. A real server once read a bits-
   per-second profile as kilobits.

## 2. Discovery

A client starts from a host name and asks `GET /.well-known/dulcet`. That document names the API
versions, their base URLs (`/dulcet/v1` for v1), the OAuth authorization server, the OpenAPI
document and the MCP endpoint. A server that does not answer it does not speak Dulcet API. The
Dulcet apps then try OpenSubsonic at `/rest`.

`GET /capabilities` then says which optional features, limits and minor version the server has.
It works without a token. With one, it also says what that user may do.

*Why a well-known URI:* it costs one request, works behind any reverse proxy path, and lets a
server serve other APIs (OpenSubsonic, its own UI) beside this one. *Why `/dulcet/v1` and not
`/api`:* a server that also implements other APIs will not collide, and the major version is in the
path where every tool can see it.

## 3. Auth

### Flows

| Who | Flow |
|---|---|
| Phone, desktop and web apps | OAuth 2.1 authorization code with PKCE (`S256` only). The app opens the server's own sign-in page in a system browser or authentication session. |
| TV, set-top box, command line | OAuth 2.0 device authorization grant (RFC 8628). The device shows a short code and a QR code; the person approves on their phone. |
| Scripts and integrations | A personal access token, created by the user with `POST /me/tokens` or in the server's UI, with only the scopes it needs. |
| AI agents (MCP clients) | The same OAuth flows, discovered through `/.well-known/oauth-protected-resource` (RFC 9728). |

The app never sees the password. Clients identify themselves with a **client id metadata
document**: the `client_id` is an HTTPS URL that serves the client's name, logo and redirect URIs.
A server may also offer dynamic client registration (RFC 7591) for clients without a web presence.
*Why:* a self-hosted server cannot pre-register every app in the world, and an HTTPS client id
lets the consent screen show a verifiable name.

### Tokens

- **Access tokens** (`dulcet_at_…`) are short-lived, about an hour.
- **Refresh tokens** (`dulcet_rt_…`) come only with the `offline_access` scope. They rotate on every
  use. Replaying a used one revokes the whole grant, because a replay means it was stolen.
- **Personal access tokens** (`dulcet_pat_…`) last until they expire or are revoked.
- **Revocation** takes effect at once, through RFC 7009 or `DELETE /me/tokens/{id}`. Every signed
  media URL issued under a revoked token stops working too. Changing a password or losing a role
  revokes the tokens that depended on it.

*Why prefixes:* secret scanners (on code hosts, in CI) can recognise and report a leaked token.
*Why opaque tokens and not JWTs:* the server is the only party that checks them, so self-contained
tokens buy nothing and make revocation harder.

### Scopes

| Scope | Allows |
|---|---|
| `library:read` | Browse and search the catalog, read lyrics, fetch artwork, read the change feed and events for what the token can read. |
| `media:read` | Stream and download audio (playback decisions and signed URLs). |
| `user:read` | Read favourites, ratings, My Library, playlists, queues, play history and now playing. |
| `user:write` | Change favourites, ratings, My Library, playlists and queues. |
| `plays:write` | Record plays and report now playing. Nothing else. |
| `account` | Manage the user's own tokens, app sessions and webhooks. |
| `admin` | Manage users, scans and settings. Granted only to admins. |
| `offline_access` | Receive a refresh token. |

A token never holds more than the user may do. The Dulcet apps ask for `library:read media:read
user:read user:write plays:write offline_access`. *Why so few:* each scope is a real boundary a
person can understand on a consent screen. `plays:write` stands alone because a scrobbling device
or a smart speaker should be able to report listening without being able to rewrite a library.

### Transport

HTTPS is required. Plain HTTP is allowed only to loopback and private-network addresses, and only
after the person explicitly agrees in the client. A client never follows a redirect from HTTPS to
HTTP, and never sends `Authorization` to a different origin after a redirect. Servers should send
HSTS. Clients trust the operating system's certificate store and nothing else.

## 4. Resources and ids

The catalog has **artists**, **albums** (with discs), **tracks**, **genres** and **libraries**
(top-level music folders). Users own **playlists**, **queues**, **plays** and **item state**
(favourite, rating, play count, My Library membership). The schemas are in the contract.

**Ids** are opaque strings. Clients never parse them, sort them or build them.

- **Unique across types.** No artist shares an id with an album. *Why:* a reference is one string,
  `PUT /me/favorites/{itemId}` works for any type, and tools and agents never need a type to go with
  an id.
- **Stable.** An id names the same item for the life of the server. A retag, a file move, a
  re-encode that the server can match, or a rescan keeps the id. *Why:* favourites, plays,
  playlists and caches on every device point at ids. An id that changes when someone fixes a typo
  in a tag silently orphans all of them.
- **Never reused.** A deleted id never comes back as a different item.
- **Merges are explicit.** When two items become one (duplicate albums merged), the old id answers
  `308 Permanent Redirect` to the survivor, and the change feed carries a tombstone with
  `replacedBy`. Clients rewrite stored references.

**External ids** (MusicBrainz artist, release, release-group, recording and track ids, ISRC,
barcode) are included when known, so items can be matched across servers, imports and services
like ListenBrainz.

**User state rides along.** Every catalog read includes the requesting user's `userState`, so an
album screen needs one request. The change feed carries that state separately as `itemState`, so a
favourite does not look like a catalog change.

**Credits are structured.** Each artist credit has a role (`albumArtist`, `artist`, `featured`,
`composer`, …) and the name as tagged. `artistDisplay` is the line to show. *Why:* multi-artist
tracks, classical music and compilations are not one string.

**Vendor data** goes in an `extensions` object keyed by a reverse-DNS name (section 16), never in
new top-level fields. *Why:* a vendor field can never collide with a field a later v1 adds.

## 5. Pagination

Every collection pages by cursor:

```
GET /dulcet/v1/albums?limit=100
→ { "items": [ … ], "nextCursor": "c_TmlnaHQgTGluZXM", "total": 412 }
GET /dulcet/v1/albums?limit=100&cursor=c_TmlnaHQgTGluZXM
→ { "items": [ … ], "nextCursor": null }
```

- `nextCursor` is null on the last page. A short or empty page with a non-null cursor is not the
  end.
- Cursors are opaque. Never parse or build one. They are tied to the query that made them.
- `total` is the size when the page was read, present when the server can count cheaply.
- Servers page by key (the sort key plus the id), not by position.

*Why never offset:* offset paging over a list that changes skips or repeats rows, and nothing in
the response says so. On a real OpenSubsonic server, deleting one album before the cursor made a
full walk skip a row that existed before and after, with no error and no short page. A keyset
cursor never skips a row that existed for the whole walk. A row added during a walk is caught by
the change feed (section 7), and a row removed shows up as a tombstone.

## 6. Caching, conditional requests and idempotency

- **ETags.** Single resources carry a strong `ETag` equal to their `revision`. Send
  `If-None-Match` and get `304 Not Modified` when nothing changed.
- **Optimistic concurrency.** Writes that could overwrite someone else's change take `If-Match`.
  A mismatch answers `412` with the current revision. Replacing a playlist's entries *requires*
  `If-Match` (`428` without it), so one device can never silently erase another's edit.
- **Artwork is immutable.** An artwork id names image content, so a response never changes and is
  served `immutable`. New art means a new artwork id.
- **Idempotency keys.** A `POST` that creates something accepts `Idempotency-Key` (a UUID). The
  server keeps each key and a fingerprint of its request for at least 24 hours. A repeat returns the
  first response; the same key with a different request is a `422`. `PUT` and `DELETE` are
  idempotent by definition: adding a favourite twice keeps the first `favoritedAt`, removing a
  missing one succeeds.
- **Plays** carry their own client-generated id, which is stronger than a key (section 10).

*Why:* mobile networks drop responses. A client must be able to retry anything without creating
duplicates or losing edits.

## 7. Sync: the change feed

Each user has one change feed, `GET /sync/changes`. It covers the catalog (artists, albums,
tracks, genres, libraries), the user's item state (favourites, ratings, play counts, My Library),
playlists, plays and queues, and deletions as tombstones. It is the only sync mechanism, for every
kind of client.

### The model

The server keeps an ordered journal of changes. A **cursor** is an opaque position in it. Each
entry says an entity was upserted (with its current representation) or deleted (with `replacedBy`
if it was merged). A server may coalesce several changes to one entity into its latest state, so an
entry means "this entity now looks like this", and applying entries is idempotent.

```json
{
  "changes": [
    { "op": "upsert", "type": "itemState", "id": "trk_4f1", "revision": "r_10452",
      "at": "2026-10-12T10:02:00Z", "resource": { "itemId": "trk_4f1", "favoritedAt": "…" } },
    { "op": "delete", "type": "album", "id": "alb_dup", "revision": "r_10454",
      "at": "2026-10-12T10:05:00Z", "replacedBy": "alb_7d" }
  ],
  "nextCursor": "sc_AQAAAAAAKNY",
  "hasMore": false,
  "phase": "changes"
}
```

Follow `nextCursor` while `hasMore` is true; when it is false, store `nextCursor`. Every read of
syncable data also returns a `Dulcet-Sync-Cursor` header: the feed position the response was read
at.

### Three ways in

| Start | Use | What comes back |
|---|---|---|
| `start=now` | A **reader** that reads screens live and caches what it saw (the Dulcet apps). | No changes, and a cursor at the current position. |
| `start=snapshot` | A **mirror** that keeps a whole copy (an offline-first player, a backup tool). | Every visible entity as an upsert, page by page (`phase: snapshot`), then every change made while the snapshot ran (`phase: changes`). |
| `cursor=…` | Incremental sync for either. | Changes after the cursor. |

**A reader** keeps the cursor from its first read (or from `start=now`), and each time it comes to
the foreground, reconnects or gets a `changes` event, it pulls the feed. It updates or evicts the
cached entities the changes name, re-reads any visible list whose type changed, and ignores
entities it never saw. Item state is in the feed, so a favourite made on another device reaches
every screen without re-reading it.

**A mirror** takes the snapshot once. The snapshot is consistent because the server records the
journal position when it starts and enumerates entities by key. Anything that changes during the
snapshot is in the journal after that position and arrives in the `changes` phase. An entity may
be seen twice; never zero times.

### Cursor too old

A server keeps at least 30 days of journal (`limits.syncRetentionDays`; Dulcet Server keeps 90). A
cursor older than that, or one from before the server's database was restored or replaced
(`instanceId` changes), answers `410` with type `urn:dulcet:problem:cursor-expired`. The client
discards state derived from the feed and starts again with `start=now` or `start=snapshot`. A
cursor stays valid for any `types` filter, so a client can widen what it syncs without starting
over.

### What it fixes

OpenSubsonic offers no change token, so a client has to infer change. Dulcet's own client records
what that costs (spec §16):

| Without a feed | With the feed |
|---|---|
| A scan clock (`lastScan`), compared for equality, says only "something in the catalog changed". | Each change names the entity. |
| The scan clock never covers user state, so every visible screen is re-read to catch a favourite made elsewhere. | Item state is in the feed. |
| Offset pages can skip rows during a scan, so a reader checks the scan clock before and after every page. | Keyset pages never skip; each page is read in one transaction and stamped with its cursor; changes after it are in the feed. |
| Deletion is inferred from an album no longer listing a track, because a server keeping missing files answers `getSong` as if it existed. | Deletions are tombstones. |
| A restored server has no way to tell clients their state is from another timeline. | `instanceId` changes and old cursors answer `410`. |

### Server obligations

- Commit catalog changes atomically per scan batch, and append to the journal in the same
  transaction as the data, so a reader never sees data the journal does not explain.
- Read every page of a list in one transaction and stamp it with the cursor of that snapshot.
- Filter the feed per user: a user never sees entities from libraries they cannot access, or
  another user's state.
- Keep tombstones as long as the journal.

## 8. My Library

My Library is the user's personal collection: the artists, albums and tracks they have added,
separate from everything the server holds (Browse). It belongs to the user, like favourites, and
adding an album to one user's library changes nothing for anyone else.

- `PUT /me/library/{itemId}` adds; `DELETE` removes. Both are idempotent. Adding a member keeps
  its original `libraryAddedAt`. Removing a non-member, or an id the catalog no longer has,
  succeeds, so a member whose files were deleted can still be removed.
- **Membership is per item.** Adding an album does not add its tracks; adding a track does not add
  its album.
- **Browsing My Library** uses the ordinary catalog lists with `scope=library`, which match direct
  members plus artists and albums that contain a member. So `GET /albums?scope=library&sort=title`
  is "my albums, by title", including an album where only one song was added. Search takes the
  same `scope`.
- `GET /me/library` lists direct members, newest first, with whole-library counts.
- Every catalog item's `userState.libraryAddedAt` is set when it is a direct member.
- Membership changes travel in the change feed as `itemState`, with tombstone-free semantics: an
  `itemState` upsert with `libraryAddedAt: null` means removed. *This fixes the extension's one
  gap:* the OpenSubsonic `dulcetLibrary` extension has no removal record, so a changed library
  clock forces a full re-read.
- A batch of offline changes goes through `POST /me/batch`.

*Why first-class and not favourites or a playlist:* favourite means "I love this"; collecting
means "this is mine to browse". People do one without the other. A playlist holds only tracks,
carries ordering semantics, and shows up as clutter in other apps. This carries over the semantics
of the `dulcetLibrary` OpenSubsonic extension
([`../dulcet-server/dulcet-library-extension.md`](../dulcet-server/dulcet-library-extension.md))
unchanged, except for the removal record.

## 9. Media URLs and playback

Native players (AVPlayer, ExoPlayer, a browser's `<audio>`, a car, a cast receiver) open a URL and
cannot be relied on to send an `Authorization` header. So media is served through **short-lived
signed URLs**.

### Streaming

```
POST /dulcet/v1/playback/decisions
{ "trackId": "trk_4f1", "purpose": "stream",
  "profile": { "maxBitrate": 320000, "network": "cellular",
               "directPlay": [ {"container":"flac","codec":"flac","maxSampleRate":96000} ],
               "transcodeTargets": [ {"container":"mp4","codec":"aac"} ] } }
→ { "mode": "transcode", "reasons": ["bitrateAboveLimit"],
    "url": "https://music.example.com/dulcet/v1/media/mg_…", "expiresAt": "…",
    "mimeType": "audio/mp4", "bitrate": 256000, "contentLength": 9088000,
    "lengthExact": false, "byteRanges": true }
```

- **The client declares, the server decides.** The client says what it can play and its limits;
  the server picks the original file or a transcode and says why. The client never builds encoder
  parameters. *Why:* only the server knows the source; only the client knows the device. A server
  that let clients name arbitrary encodes would be a free transcoding service.
- **Asking is not playing.** A decision records nothing. Plays are submitted separately
  (section 10).
- **The plan says what the bytes will be:** MIME type, codec, bitrate, whether the length is exact
  or estimated, and whether byte ranges work yet. *Why:* a player that assumes an estimated length
  is exact fails at the end of a cold transcode, which really happened.
- **Downloads** use `purpose: download` (possibly transcoded for the device) or, for the untouched
  file, `POST /media/urls` with `kind: original`, which needs the user's `download` permission.

### Signed URLs

- `GET /media/{grant}` serves the bytes, with `Range` support. The grant is an opaque path segment
  that authorizes one resource, for one user, until `expiresAt`.
- Lifetime is at least an hour (`limits.mediaUrlTtlSeconds`; six hours on Dulcet Server), so a
  paused track still resumes. Expiry is checked when a request starts; a response already flowing
  is not cut off. An expired grant answers `403` `media-grant-expired`, and the client asks for a
  new plan.
- A grant dies early when its token is revoked.
- The grant sits in the path, not the query, so servers and proxies can redact it with one rule:
  log `/dulcet/v1/media/***`.
- Responses carrying a signed URL are `Cache-Control: no-store`.
- Success is always binary with a real media type, and failure is always an HTTP error status with
  a problem body. *Why:* OpenSubsonic answers some binary failures with an HTTP 200 error envelope,
  so a client has to inspect every body. Here, the status code is the truth.

### Artwork

`GET /artwork/{artworkId}?size=600` with the normal `Authorization` header works for image loaders
that can send one. For lock screens, car displays and web `<img>`, `POST /media/urls` signs up to
500 artwork URLs in one call. *Why not signed URLs inside every response:* signatures expire and
would make every list response different on every read, which breaks ETags and caching. Artwork
ids are stable; signing happens only where it is needed.

## 10. Plays and now playing

**Plays.** `POST /me/plays` takes one play or an offline batch of up to 500. Each play has:

- `id`: a UUID the client generates when the listen begins;
- `trackId`, `startedAt` (wall clock) and `listenedMs` (progressing media time, excluding pauses,
  buffering and skipped spans);
- optionally `completed`, `deviceId`, `client`, `context` (album, playlist, …) and `offline`.

The server records each `id` once, ever. Resubmitting answers `duplicate` and counts nothing again.
*Why:* this turns at-least-once delivery into exactly-once recording. OpenSubsonic's `scrobble` has
no such key, so a retried submission after a lost response can count twice, and a client must
choose between a possible duplicate and a possible loss.

The client decides when a listen counts (for example, half the track or four minutes of progressing
media time) and submits only those. The server may ignore a play it judges invalid and says so per
play (`ignored`). A play of a track the server does not hold answers `unknownTrack`. `GET
/me/plays` returns history, newest first.

**Now playing.** `PUT /me/now-playing` is a heartbeat with device, track, state and position, and a
time to live (default two minutes). It never changes a play count. `GET /now-playing` lists the
user's active devices, plus other users who chose to share. *Why separate from plays:* "what is on
right now" and "what was listened to" have different lifetimes, privacy and semantics, as
ListenBrainz's `playing_now` versus `single` showed.

## 11. Lyrics, playlists, queues and search

**Lyrics.** `GET /tracks/{id}/lyrics` returns documents: the main lyrics and any translations or
pronunciations, each with a BCP 47 language. Lines may have `startMs` and `endMs`; each line may
have `words` with their own timing, for karaoke-style display; a `voice` label marks duets. An
offset in the source is already applied. An empty `documents` list is an answer, not an error.
Where lyrics came from a provider, `provider` names it for attribution.

**Playlists.** Users own playlists; `visibility: public` lets every user of the server read one.
Each entry has its own `entryId`, so the same track can appear twice and still be moved or removed
exactly. Edits: insert (append, or before an entry), move one entry, remove one entry, or replace
all (with a required `If-Match`). Every edit changes the playlist's `revision`. Paging a playlist's
entries is pinned to the revision it started at; if the playlist changes mid-walk, the next page
answers `409` and the walk restarts. *Why entry ids:* position-based edits from two devices
corrupt a list; entry ids keep edits exact.

**Queues.** Each device saves its own queue at `/me/queues/{queueId}`, normally keyed by its device
id: entries, the current entry, position, shuffle and repeat. `GET /me/queues` lists every device's
queue, most recent first. To pick up on a tablet what the phone was playing, the tablet reads the
phone's queue and plays it as its own. *Why one queue per device rather than one per user:* two
devices playing at once is normal in a household, and a single shared queue would need a
conflict model for every skip. Saving with `If-Match` keeps two windows of the same app from
clobbering each other. Live remote control of another device is left for a later capability.

**Search.** `GET /search?q=…&types=album,track&scope=all` returns results grouped by type, best
match first, each type with its own cursor. Matching and ranking are the server's choice.

## 12. Events and webhooks

**Server-Sent Events.** `GET /events` keeps a `text/event-stream` open, with the token in the
`Authorization` header. (Browsers use a fetch-based reader; `EventSource` cannot send headers, and
a token in the URL is not allowed.) Event types:

| Event | Data |
|---|---|
| `changes` | The feed advanced: the new cursor and the entity types involved. Pull `/sync/changes`. |
| `nowPlaying` | A device the user can see started, paused, moved or stopped. |
| `queue` | One of the user's queues was saved. |
| `scan` | A library scan started, progressed or finished. |
| `revoked` | The token was revoked; the stream then closes. |
| `heartbeat` | Sent at least every 30 seconds so proxies keep the connection. |

Events are hints, not data of record. A client that misses some loses nothing: the feed has
everything. Reconnect with `Last-Event-ID`.

**Webhooks.** `POST /webhooks` subscribes an HTTPS URL to events (`play.recorded`,
`nowPlaying.changed`, `itemState.changed`, `playlist.changed`, `library.changed`, `scan.completed`).
Deliveries are signed with the [Standard Webhooks](https://www.standardwebhooks.com/) scheme:
`webhook-id`, `webhook-timestamp` and `webhook-signature` (HMAC-SHA256 over
`id.timestamp.body`, with a `whsec_` secret shown once). Receivers reject stale timestamps and
dedupe on `webhook-id`. Failed deliveries retry with backoff for about a day, then the hook is
disabled with a reason. A webhook only ever carries what its creator's token could read.

Targets must be HTTPS and must not resolve to private or loopback addresses, unless an admin
allows private targets (home automation on the same network is a real use). *Why:* without that
rule, a webhook is a way to make the server probe its own network.

## 13. AI and MCP

Every conforming server can expose a [Model Context Protocol](https://modelcontextprotocol.io)
endpoint at `/dulcet/v1/mcp` (Streamable HTTP transport). An AI assistant connects to it with the
same OAuth flow as any app, and gets tools built on the same API and the same scopes.

| Tool | Scope | Does |
|---|---|---|
| `search_catalog` | `library:read` | Search artists, albums, tracks and playlists. |
| `get_artist`, `get_album`, `get_track` | `library:read` | Read details, including credits and external ids. |
| `get_lyrics` | `library:read` | Read a track's lyrics. |
| `list_my_library`, `list_favorites`, `list_playlists`, `get_playlist` | `user:read` | Read the user's collection. |
| `list_recent_plays`, `get_listening_stats` | `user:read` | Read play history and simple totals. |
| `get_now_playing` | `user:read` | What the user's devices are playing. |
| `create_playlist`, `add_to_playlist` | `user:write` | Make or extend a playlist. |
| `set_favorite`, `set_rating`, `add_to_my_library`, `remove_from_my_library` | `user:write` | Change item state. |

Rules:

- **Read-only by default.** A token without `user:write` sees no write tools at all.
- **No destructive or administrative tools in v1.** No tool deletes a playlist, removes playlist
  entries, touches other users or administers the server. Every write a tool makes can be undone
  by a person in an app. Tools carry MCP's `readOnlyHint` and `destructiveHint` annotations.
- **Tool results are data.** Titles, tags and lyrics are user-supplied text and may contain
  instructions. Servers return them as structured content, never as instructions, and AI clients
  must treat them as untrusted.
- **Same limits.** MCP calls count against the same rate limits and appear in the same audit trail
  as API calls.

*Why MCP and not a bespoke AI API:* it is an open protocol that many assistants already speak, it
reuses OAuth and scopes rather than inventing a new permission model, and it keeps a person's
assistant inside the same boundaries as their apps. Beyond MCP, the contract is written to be read
by machines: descriptive `operationId`s, examples on every major operation, stable problem types,
and [`llms.txt`](llms.txt).

## 14. Errors

Every error is RFC 9457 problem details, `application/problem+json`:

```json
{ "type": "urn:dulcet:problem:insufficient-scope", "title": "The token does not hold the required scope.",
  "status": 403, "requiredScopes": ["user:write"], "instance": "req_8c1e" }
```

Clients switch on `type`; `title` and `detail` are for people and may be localised. An unknown
`type` is handled by its `status`. `errors` lists per-field problems with JSON Pointers. `instance`
is an id the server's own logs also carry.

| `urn:dulcet:problem:…` | Status | Meaning |
|---|---|---|
| `invalid-request` | 400 | Malformed request or parameter. |
| `unauthenticated` | 401 | No valid token. |
| `insufficient-scope` | 403 | The token lacks a scope (`requiredScopes`). |
| `permission-denied` | 403 | The user lacks a permission (download, admin, …). |
| `media-grant-expired` | 403 | A signed media URL expired or was revoked. |
| `not-found` | 404 | No such resource, or the user cannot see it. |
| `conflict` | 409 | The request conflicts with current state. |
| `cursor-expired` | 410 | The sync cursor can no longer be served. |
| `revision-mismatch` | 412 | `If-Match` did not match. |
| `unknown-item` | 422 | An id in the body is not in the catalog. |
| `idempotency-key-reused` | 422 | Same key, different request. |
| `precondition-required` | 428 | This write needs `If-Match`. |
| `rate-limited` | 429 | Wait `Retry-After` seconds. |
| `server-error` | 500 | The server failed. |
| `unavailable` | 503 | Temporarily unavailable; `Retry-After` may say when. |

The OAuth token endpoints use the OAuth error format instead (RFC 6749 §5.2), so stock OAuth
libraries work. *Why URNs and not URLs for `type`:* a problem type must never change, and a URN
does not depend on a domain staying registered.

## 15. Security and privacy

- **Rate limits.** A limited request answers `429` with `Retry-After`. Servers may also send the
  IETF `RateLimit` and `RateLimit-Policy` headers. Clients back off and honour `Retry-After` on
  `503` as well.
- **CORS.** The API authenticates only by header, never by cookie, so there is no ambient
  credential for another site to ride. Servers may therefore answer API requests with
  `Access-Control-Allow-Origin: *`, allowing the request headers `Authorization`, `Content-Type`,
  `Idempotency-Key`, `If-Match`, `If-None-Match` and `Last-Event-ID`, and exposing `ETag`,
  `Location`, `Retry-After`, `Dulcet-Sync-Cursor`, `Deprecation`, `Sunset` and the `RateLimit`
  headers. An admin may narrow the allowed origins. The server's own sign-in pages use cookies and
  are never CORS-enabled. *Why:* this lets anyone build a web client for any server without the
  server admin's help, without opening a CSRF hole.
- **No credential reaches a log.** Servers never log `Authorization`, request bodies of the token
  endpoints, refresh tokens, webhook secrets, passwords or media grants (`/media/***`). Clients
  redact the same before anything reaches a log, a diagnostic or a crash report. Conformance tests
  check this with canary values.
- **TLS.** HTTPS everywhere except loopback and consented private-network HTTP (section 3).
  Clients use the operating system's trust store. Servers should send HSTS on HTTPS.
- **Least data.** Now playing is visible only to the user, plus others they choose to share with,
  and admins. Play history is private to the user. Exports contain only the requesting user's data.
- **Passwords** are stored with a slow, salted hash (Argon2id or bcrypt). Dulcet API never needs the
  plaintext. A server that also serves OpenSubsonic token authentication must keep a recoverable
  form for that API, and should say so to its admins.

## 16. Capabilities and extensions

`GET /capabilities` lists capabilities by name, each with the integer versions supported:

```json
"capabilities": {
  "dulcet.sync": { "versions": [1] },
  "dulcet.transcoding": { "versions": [1] },
  "org.example.radio": { "versions": [2], "stations": 12 }
}
```

- **Core names** start with `dulcet.` and are defined only by this standard.
- **Vendor names** are reverse-DNS names the vendor controls (`org.example.radio`). A vendor never
  uses `dulcet.`.
- **Versions are integers**, and a capability version, once published, never changes meaning. A
  breaking change to a capability is a new version, advertised beside the old one.
- **Vendor paths** live under `/dulcet/v1/x/{vendor-name}/…`, and vendor fields live in the
  resource's `extensions` object under the vendor name.
- A client acts only on names and versions it knows. A missing capability means the feature is not
  there; one failed request never means a capability went away.

**Plugins.** Future metadata and lyrics providers, radio, sharing and remote control fit this
shape: each becomes a named capability with its own version, its own paths under the base and, if
it needs one, its own scope. Lyrics already carry a `source` and `provider`; catalog items already
carry `externalIds` with room for more registries. Nothing about plugins is designed yet, and
nothing in v1 has to change to add them.

## 17. Evolution and deprecation

- **Within v1, only additions.** New endpoints, new optional request fields, new response fields,
  new enum values, new change and event types, new problem types, new capabilities. Each bumps the
  minor version reported in `apiVersion` (`1.0`, `1.1`, …). A minor version never removes or
  changes the meaning of anything.
- **What clients must do** so that this works: ignore unknown fields; treat every enumerated string
  as open (map an unknown value to a neutral default); skip unknown change, event and op types;
  handle an unknown problem type by its status; never send a field the server's `apiVersion` does
  not define and expect it to be honoured.
- **What servers must do:** never require a new request field in v1; never change a field's type,
  unit or meaning; keep accepting every documented value.
- **Deprecation inside v1** marks a feature with the `Deprecation` header (RFC 9745), a `Link` to
  its replacement, and a note in the contract. A deprecated v1 feature keeps working for the life
  of v1.
- **A breaking change means v2**, served at `/dulcet/v2` beside v1. Both appear in
  `/.well-known/dulcet`. v1 keeps working for at least **24 months** after v2 is published, and
  its responses carry `Sunset` (RFC 8594) once a date is set. A server may run v1 longer, never
  shorter.
- **Changes to this standard** are proposed as pull requests against `openapi.yaml`, with the
  README and conformance suite changed in the same pull request, and recorded in the design spec's
  revision log.

*Why 24 months:* clients ship through app stores and people do not update apps on old devices; a
server operator should be able to upgrade without stranding a family member's tablet.

## 18. Building a server

1. Serve `/.well-known/dulcet`, the RFC 8414 and RFC 9728 metadata, and `GET /capabilities`.
2. Implement OAuth 2.1 (authorization code with S256 PKCE, refresh rotation, revocation) and
   personal access tokens. Add the device grant if you want TVs and command lines to work.
3. Implement the Core profile (section 20): catalog, search, artwork, playback decisions and signed
   media URLs, item state, My Library, plays, playlists, export.
4. Give items stable ids: key track identity to the file (a content fingerprint of the audio,
   ignoring tags, plus its path), keep a mapping when files move, and never derive an id from tags
   alone, because tags get edited.
5. Write a journal row in the same transaction as every change, and build the feed on it.
6. Run the conformance suite against your server and publish the report.

Generate server stubs from `openapi.yaml` if your stack has a generator; the contract avoids
constructs generators struggle with (no polymorphic request bodies, no closed enums in responses).

## 19. Building a client

1. Discover: `GET /.well-known/dulcet`, then `GET /capabilities`.
2. Sign in with OAuth (authorization code + PKCE, or the device grant on a TV). Ask only for the
   scopes you use. Store tokens in the platform's secure storage.
3. Read screens with the catalog endpoints. Keep the `Dulcet-Sync-Cursor` of your first read, and
   pull `/sync/changes` on foreground, reconnect and `changes` events. Handle `410` by starting
   over.
4. Play with `POST /playback/decisions`, hand the URL to the native player, and ask again when it
   expires. Submit plays with your own UUIDs; retry freely.
5. Write with idempotent `PUT`/`DELETE`, `If-Match` where offered, and `Idempotency-Key` on `POST`.
6. Ignore what you do not know (section 17).
7. Run the client conformance scenarios (`CONFORMANCE.md`) against the reference mock.

## 20. Claiming compatibility

Compatibility is claimed per **profile**, by passing the conformance suite for that profile at a
named suite version and publishing its report.

| Profile | Covers |
|---|---|
| **Core** (required for any claim) | Discovery, capabilities, OAuth code + PKCE, refresh, revocation, personal tokens, catalog, search, artwork, playback decisions, signed media URLs, item state, My Library, plays, playlists, export, errors, pagination, ETags, idempotency, CORS, logging hygiene. |
| **Sync** | The change feed, snapshots, `410` handling. |
| **Device** | The device authorization grant. |
| **Transcoding** | Transcoded playback plans, estimated lengths, `startMs`. |
| **Lyrics** | Lyrics documents, synced lines. |
| **Queues** | Per-device queues. |
| **Events** | Server-Sent Events. |
| **Webhooks** | Signed webhooks with retries. |
| **MCP** | The MCP endpoint and its scope rules. |
| **Admin** | Users and scans. |

A claim reads, for example: "Dulcet API v1.0 compatible: Core, Sync, Device, Transcoding (suite
1.0.0, report linked)." The name "Dulcet API compatible" means exactly that the suite passed; it is
not a certification body or a trademark licence.

## 21. Prior art, and why not extend OpenSubsonic

We read these, took ideas, and copied no text.

| Source | What we took | What we did differently |
|---|---|---|
| **OpenSubsonic** | Extension negotiation by name and integer version; the client-declares, server-decides transcoding model (`getTranscodeDecision` with opaque parameters); synced lyrics with languages; play submission separate from streaming. | A resource-oriented HTTPS + JSON API instead of `/rest/<method>` with query-string parameters. |
| **Jellyfin** (OpenAPI) | A device profile describing what a client can direct-play and what it accepts from a transcode; a published OpenAPI document generated from the server. | Header-only auth (Jellyfin also accepts a token in the query); one sync model. |
| **Plex** | Per-device play queues that can be resumed elsewhere; multiple libraries per server. | An open standard any server can implement, rather than one product's own API. |
| **Spotify Web API** | OAuth with PKCE for public clients; small, readable scopes; a playlist revision (`snapshot_id`) that guards concurrent edits. | Cursors everywhere instead of offsets for most lists; no ambient player state. |
| **Apple Music API** | Clear resource types with nested relationships; a developer-facing guide that reads like documentation, not a list of methods. | One bearer token per user instead of a developer token plus a user token. |
| **ListenBrainz** | Listens with a real start time and listen duration; `playing_now` kept apart from finished listens; a token in a header. | A client-generated listen id, so retries never double-count. |
| **MusicBrainz** | Stable, opaque, never-reused identifiers, with merges that redirect; MBIDs as the cross-service join key. | Carried as `externalIds` on every catalog item. |

### Why a new API instead of more OpenSubsonic extensions

OpenSubsonic is actively and openly maintained, many servers and clients implement it, and the
Dulcet apps keep speaking it to every server other than Dulcet Server. Dulcet Server keeps serving
it in full. Its strength is the reason it cannot be reshaped: it is a backward-compatible
evolution of the Subsonic API, and every server and client depends on that compatibility. The
things this standard needs are changes to its foundations, not additions:

- **Credentials in the URL.** Subsonic authentication puts a password-derived token or an API key
  in the query string. OpenSubsonic's newer `apiKeyAuthentication` extension removes the password,
  but the key still travels in the URL. Header-only bearer tokens, OAuth, scopes and revocation do
  not fit the base protocol's request shape.
- **Errors inside HTTP 200.** Failures arrive as envelopes in a successful response, including for
  binary endpoints, so every client inspects every body.
- **Offset paging and no change token.** There is no way to sync incrementally or to page without
  skipping rows during a scan. Adding a change feed as an extension would still leave every
  existing endpoint without cursors, ETags or conditional requests.
- **Methods, not resources.** Each operation is a verb at `/rest/<method>`, so generic HTTP
  tooling (caches, ETags, `Location`, OpenAPI generators) has little to work with.

Fixing those in OpenSubsonic would break the compatibility that makes it valuable. A separate
standard can get them right from the start, and a server can, like Dulcet Server, serve both.
