# Dulcet API v1

Dulcet API is an open standard for music servers and the apps that talk to them. It covers a
catalog, streaming, per-user state (favourites, ratings, plays, playlists, My Library), one shared
play queue across a person's devices, sync, live events, webhooks and a tool endpoint for AI agents. Anyone may implement it, on either side,
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
11. [Lyrics, playlists, the queue and search](#11-lyrics-playlists-the-queue-and-search)
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
5. **Built to last for years.** v1 grows only by addition, and there is no planned v2 (section 17).
   *Why:* clients ship through app stores and live on devices for years; a server upgrade must
   never break them.
6. **One way to do each thing.** One sync mechanism, one pagination style, one error format, one
   auth header. *Why:* every option is a branch every client must write and test.
7. **Built for the people who use it, not for lock-in.** Every user can export everything the
   server holds about them (`GET /me/export`). Catalog items carry MusicBrainz ids and ISRCs, so
   data moves between servers and services. *Why:* a personal music library and its history belong
   to the person.
8. **Units are fixed.** Durations are integer milliseconds (`durationMs`), timestamps are RFC 3339
   UTC strings with millisecond precision, sizes are 64-bit integers of bytes, bitrates are bits per second, ratings are integers 1 to 10. *Why:* a unit in the
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

The app never sees the password. The authorization response carries `iss` (RFC 9207), and the
client checks it against the server it started with, so one server cannot answer for another.

**Client identity, online or not.** A client identifies itself with a **client id metadata
document**: the `client_id` is an HTTPS URL that serves the client's name, logo and redirect URIs.
Many self-hosted servers have no internet, so the server handles three cases:

- **Verified.** The server fetches the document safely (HTTPS only, no redirects, no private,
  loopback or link-local addresses unless an admin allows them, a 5-second timeout, a 64 KiB cap,
  cached for at most a day) and accepts only a redirect URI listed there, matched exactly.
- **Unverified.** When the fetch fails, the server may still sign the person in, but the consent
  screen says "Unverified app" and shows where the code will go, and only a loopback redirect or a
  private-use scheme in reverse-DNS form (`com.example.player:/oauth/callback`) is accepted. An
  unverified client is never granted `admin` or `account`.
- **Registered.** A server may offer dynamic client registration (RFC 7591), and may preload the
  metadata of clients it trusts so they show as verified offline. A preloaded client is verified
  under every rule above (exact redirects from its document, `admin` and `account` allowed),
  without a fetch.

*Why:* a self-hosted server cannot pre-register every app in the world, an HTTPS client id lets the
consent screen show a verifiable name when it can, and a server with no internet still works,
saying plainly what it could not check.

**Hardening.** The device grant's user codes carry at least 20 bits of entropy from an unambiguous
alphabet, expire with the device code and lock after five wrong entries; the verification page
shows the client's name, whether it is verified, and the scopes. The polling endpoint answers
`slow_down` to a client that polls too fast. Sign-in and token endpoints are
rate-limited per client and per address.

### Tokens

- **Access tokens** (`dulcet_at_…`) are short-lived, about an hour.
- **Refresh tokens** (`dulcet_rt_…`) come only with the `offline_access` scope. They rotate on every
  use. Within 60 seconds of a rotation, presenting the old token again returns the same new pair,
  so a response lost on a flaky network does not sign the person out; after that window, a replay
  revokes the whole grant, because it means the token was stolen. A refresh token unused for 90
  days expires.
- **Personal access tokens** (`dulcet_pat_…`) last until their chosen expiry (at most the server's
  maximum, `limits.maxTokenLifetimeDays`, 365 days on Dulcet Server; more answers `422`). `noExpiry` may be asked for only by a personal token or a
  first-party session, and the server may refuse it.
- **Minted tokens stay inside their parent.** A token or webhook created through the API holds at
  most the scopes of the token that created it, and records it as `parentId`. Revoking an app's
  grant revokes everything created under it. *Why:* otherwise any app granted `account` could mint
  itself a broader, longer-lived token and survive being signed out.
- **Revocation** takes effect at once, through RFC 7009 or `DELETE /me/tokens/{id}`. Every signed
  media URL issued under a revoked token stops working too. Changing a password or losing a role
  revokes the tokens that depended on it.

*Why prefixes:* secret scanners (on code hosts, in CI) can recognise and report a leaked token.
*Why opaque tokens and not JWTs:* the server is the only party that checks them, so self-contained
tokens buy nothing and make revocation harder.

### Scopes

| Scope | Allows |
|---|---|
| `catalog:read` | Browse and search the catalog, read lyrics, fetch artwork, read the catalog part of the change feed and events. |
| `media:read` | Stream and download audio (playback decisions and signed URLs). |
| `user:read` | Read favourites, ratings, My Library, playlists, the queue, devices, play history and now playing. |
| `user:write` | Add and change: favourites, ratings, My Library additions, playlists (create, rename, insert, move), the queue and playback. |
| `user:delete` | Remove what cannot be put back exactly: delete a playlist or its entries, remove from My Library, delete a play. |
| `plays:write` | Record plays and report now playing. Nothing else. |
| `account` | Manage the user's own profile, tokens, app sessions and webhooks. Deleting the account also needs `user:delete` and a sign-in within the last 10 minutes. |
| `admin` | Manage users, scans and settings. Granted only to admins. |
| `offline_access` | Receive a refresh token. |

A token never holds more than the user may do. The Dulcet apps ask for `catalog:read media:read
user:read user:write user:delete plays:write offline_access`. *Why so few:* each scope is a real
boundary a person can understand on a consent screen. `plays:write` stands alone because a
scrobbling device or a smart speaker should be able to report listening without being able to
rewrite a library. `user:delete` stands alone because a person can sensibly let an assistant or a
script add to their collection without letting it remove anything: a removed playlist, entry or
play loses dates and order that no later write restores. The queue is a working list, so taking an
entry off it needs only `user:write`.

### Transport

HTTPS is required. Plain HTTP is allowed only to a **local address**, and only after the person
explicitly agrees in the client. A local address is one that resolves to loopback, RFC 1918
private space, `100.64.0.0/10`, IPv6 unique-local or link-local space, or a `.local` name; a client
checks the resolved address, not the spelling of the host. A client never follows a redirect from
HTTPS to HTTP, never sends `Authorization` to a different origin after a redirect, and keeps the
`Authorization` header only on same-origin redirects. Servers should send HSTS.

**Certificates.** A client trusts the operating system's certificate store. A self-hosted server
often has a certificate the store does not know, so a client may also offer, after explicit
consent:

- **Pin on first use.** The client shows the certificate's SHA-256 public-key fingerprint, the
  person confirms it, and the client pins that key for the server. A different key later is an
  error with the old and new fingerprints shown, never a silent re-pin.
- **Pairing.** The server's UI shows a QR code `dulcet://pair?url=https%3A%2F%2Fmusic.local&spki=sha256%2F…`;
  a client that scans it pins the key without a prompt, because the person carried it from a
  screen they trust.
- **An imported CA**, for a household that runs its own.

Which of these a client offers is its choice; a server supports all of them by doing nothing
special. The Dulcet apps trust the operating system's store only. *Why:* a self-hosted server
without a public name is normal, and "trust any certificate" is the habit these options replace.

## 4. Resources and ids

The catalog has **artists**, **albums** (with discs), **tracks**, **genres** and **sources** (the
top-level music folders an admin configures, which decide what each user can see). Users own
**playlists**, one **queue**, **devices**, **plays** and **item state** (favourite, rating, play
count, My Library membership). "Library" in this standard means only My Library (section 8). The
schemas are in the contract.

A user's `sourceIds` lists the sources they can see. Left out when the user is created, it means
every source that exists then; an empty list means none, so the user sees no catalog.

**Ids** are opaque strings. Clients never parse them, sort them or build them.

- **Unique across types.** No artist shares an id with an album. *Why:* a reference is one string,
  `PUT /me/favorites/{itemId}` works for any type, and tools and agents never need a type to go with
  an id.
- **Stable.** An id names the same item for the life of the server. A retag, a file move, a
  re-encode that the server can match, or a rescan keeps the id. *Why:* favourites, plays,
  playlists and caches on every device point at ids. An id that changes when someone fixes a typo
  in a tag silently orphans all of them.
- **Never reused, even after a restore.** A deleted id never comes back as a different item. Ids
  are random, not counters, so a server restored from an old backup cannot hand out an id that a
  client already holds for something else.
- **Merges are explicit.** When two items become one (duplicate albums merged), the old id answers
  `308 Permanent Redirect` to the survivor, and the change feed carries a tombstone with
  `reason: merged` and `replacedBy`. A split lists every successor. Two albums that come to carry
  the same MusicBrainz release id after a scan are always merged, and the survivor is the id issued
  first; a server may merge in other cases it can justify, never across different release ids. The server keeps this mapping
  for as long as it runs, not only for the feed's retention, so a device offline for a year still
  finds its references. Clients rewrite stored references.
- **`410` and `404` mean different things.** `410 Gone` means the server knows the id and it was
  deleted (with `replacedBy` when it was merged or split); `404` means the id never existed or the
  user cannot see it.

**External ids** (MusicBrainz artist, release, release-group, recording and track ids, ISRC,
barcode) are included when known, so items can be matched across servers, imports and services
like ListenBrainz.

**User state rides along.** Every catalog read includes the requesting user's `userState`, so an
album screen needs one request. The change feed carries that state separately as `itemState`, with
its own revision, so a favourite does not look like a catalog change.

**Required fields are few.** An album or track requires only `id`, `type`, `title` and
`revision`; everything else may be missing when the server does not know it (an untagged file has
no artist). Clients render what is there.

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
the response says so: deleting one row before the current offset shifts every later row back by
one, so the next page starts one row late. A keyset cursor never skips a row that existed for the
whole walk. A row added during a walk is caught by
the change feed (section 7), and a row removed shows up as a tombstone.

## 6. Caching, conditional requests and idempotency

- **ETag and revision are different things.** A `revision` names one version of one entity, for
  every user alike; the feed and `If-Match` use it. An `ETag` names one *representation*: what
  this user got from this URL, including their `userState`. So an album's ETag changes when the
  user favourites it, while its revision does not. Responses with an ETag are `Cache-Control:
  private` with `Vary: Authorization`. Send `If-None-Match` and get `304 Not Modified` when
  nothing changed. On the resources that take `If-Match` (playlists and the queue), the ETag is
  exactly the quoted revision, so either can be sent back.
- **Optimistic concurrency.** Writes that could overwrite someone else's change take `If-Match`.
  A mismatch answers `412` with the current state in `current`. Replacing a playlist's entries
  or the queue *requires* `If-Match` (`428` without it), so one device can never silently erase
  another's edit.
- **Tags from another timeline never match.** Revisions, ETags and cursors embed the server's
  `instanceId` (section 7), so after a restore no stale tag answers `304` and no stale revision
  passes `If-Match`.
- **Artwork is immutable.** An artwork id names image content, so a response never changes and is
  served `Cache-Control: private, max-age=31536000, immutable`. New art means a new artwork id.
- **Offline writes carry their time.** A write made offline and sent later carries
  `Dulcet-Action-Time` (or `at` per batch op): when the person acted. For favourites, ratings and
  My Library the latest action wins; a write older than one already applied answers success with
  `applied: false` and changes nothing. *Why:* without it, a phone that reconnects after a week
  would undo everything the person did elsewhere since.
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
tracks, genres, sources), the user's item state (favourites, ratings, play counts, My Library),
playlists, plays and the queue, and deletions as tombstones. It is the only sync mechanism, for every
kind of client.

### The model

The server keeps an ordered journal of changes. A **cursor** is an opaque position in it. Each
entry says an entity was upserted (with its current representation) or deleted (with a `reason`,
and `replacedBy` if it was merged). A server may coalesce several changes to one entity into its
latest state, so an entry means "this entity now looks like this", and applying entries is
idempotent.

```json
{
  "changes": [
    { "op": "upsert", "type": "itemState", "id": "trk_4f1", "revision": "r_10452",
      "at": "2026-10-12T10:02:00Z", "resource": { "itemId": "trk_4f1", "favoritedAt": "…" } },
    { "op": "delete", "type": "album", "id": "alb_dup", "revision": "r_10454",
      "at": "2026-10-12T10:05:00Z", "reason": "merged", "replacedBy": ["alb_7d"] }
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

### Feed rules

- **Cursors are replayable.** Sending the same cursor again returns the same changes (or their
  later coalesced state) plus newer ones, so a client that crashed before storing a cursor just
  asks again.
- **A cursor is bound to its `types`.** A different `types` value answers `400`
  `cursor-types-mismatch`. To sync more types, run a second feed for the new ones
  (`start=snapshot&types=track`) and keep both cursors. *Why:* a cursor that silently widened would
  skip every past change of the new type.
- **Order and pages.** Changes come in commit order, and one entity appears at most once per
  page. A page may be empty while `hasMore` is true (the server skipped changes this user cannot
  see).
- **Visibility.** When an entity stops being visible to the user (an admin takes a source away, a
  shared playlist goes private), the feed carries a `delete` with `reason: hidden`; when it comes
  back, an `upsert`. A server that cannot work out which entities those are answers `410` instead.
- **Merges carry the user's data.** Before a `reason: merged` tombstone, the server has already
  moved the user's state onto the survivor and emitted it: the earliest `favoritedAt`, the
  survivor's rating (or else the merged one), summed play counts, the latest `lastPlayedAt`, the
  earliest `libraryAddedAt`; playlists and the queue have their entries rewritten (entry ids kept)
  and are emitted with new revisions; plays point at the survivor. A client that applies the feed
  in order never holds a reference to a dead id.
- **Scopes.** Catalog types need `catalog:read`; user types need `user:read`. Asking for a type the
  token cannot read answers `403`.

### Cursor too old, or another timeline

A server keeps at least 30 days of journal (`limits.syncRetentionDays`; Dulcet Server keeps 90). A
cursor older than that answers `410` with type `urn:dulcet:problem:cursor-expired`.

**Restores.** The server's `instanceId` names one continuous history of its data. It is kept
outside the database it describes, and it changes whenever the server cannot prove its journal is
continuous: a restore from backup, a copy to another host, a rollback, an import. Every cursor,
revision and ETag embeds it. So a cursor from another instance, or one ahead of the journal's head,
answers `410`; an old ETag never matches; an old revision never passes `If-Match`. *Why outside the
database:* a restored database would bring back its old `instanceId` too, and clients would accept
cursors into a history that diverged from what they saw.

On `410` the client discards state derived from the feed, keeps its unsent writes, and starts again
with `start=now` or `start=snapshot`. Ids are never reused even across a restore (section 4), so
unsent writes still point at the right items or fail cleanly with `404`.

### What it fixes

OpenSubsonic offers no change token, so a client has to infer change. What that costs a client:

| Without a feed | With the feed |
|---|---|
| A scan clock (`lastScan`), compared for equality, says only "something in the catalog changed". | Each change names the entity. |
| The scan clock never covers user state, so every visible screen is re-read to catch a favourite made elsewhere. | Item state is in the feed. |
| Offset pages can skip rows during a scan, so a reader checks the scan clock before and after every page. | Keyset pages never skip; each page is read in one transaction and stamped with its cursor; changes after it are in the feed. |
| Deletion is inferred from an album no longer listing a track, because a server keeping missing files answers `getSong` as if it existed. | Deletions are tombstones. |
| A restored server has no way to tell clients their state is from another timeline. | `instanceId` changes, and old cursors, ETags and revisions stop matching. |

### Server obligations

- Commit catalog changes atomically per scan batch, and append to the journal in the same
  transaction as the data, so a reader never sees data the journal does not explain.
- Read every page of a list in one transaction and stamp it with the cursor of that snapshot.
- Filter the feed per user: a user never sees entities from sources they cannot access, or
  another user's state.
- Keep tombstones as long as the journal, and the merge mapping for as long as the server runs.
- Keep `instanceId` outside the database and change it on any restore.

## 8. My Library

My Library is the user's personal collection: the artists, albums and tracks they have added,
separate from everything the server holds (Browse). It belongs to the user, like favourites, and
adding an album to one user's library changes nothing for anyone else.

- `PUT /me/library/{itemId}` adds (`user:write`); `DELETE` removes (`user:delete`). Both are
  idempotent. Adding a member keeps
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
- A batch of offline changes goes through `POST /me/batch`, each op with the time the person acted
  (`at`). A `libraryRemove` op without `user:delete` answers `403` for that op alone.

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
- **Offsets are explicit.** A plan that starts mid-track says `offsetMs` (the media time at stream
  time zero), `durationMs` (the whole track) and `streamDurationMs` (what the stream holds), so a
  player never confuses stream time with track time. Each plan has an `id`.
- **Reserved for later, not designed yet:** gapless metadata (`encoderDelaySamples`,
  `paddingSamples`, `totalSamples` are already defined and optional), adaptive streaming (`mode`
  values `hls` and `dash`), and crossfade. Each will arrive as a capability.
- **Downloads** use `purpose: download` (possibly transcoded for the device) or, for the untouched
  file, `POST /media/urls` with `kind: original`, which needs the user's `download` permission.

### Signed URLs

- `GET /media/{grant}` serves the bytes, with `Range` support. The grant is an opaque path segment
  that authorizes one resource, for one user, until `expiresAt`.
- Lifetime is at least an hour (`limits.mediaUrlTtlSeconds`; six hours on Dulcet Server), so a
  paused track still resumes. Expiry is checked when a request starts; a response already flowing
  is not cut off. An expired grant answers `403` `media-grant-expired`, and the client asks for a
  new plan.
- A grant dies early when its grant is revoked; refreshing the access token does not kill it.
  *Why:* a queue of signed URLs must survive an hourly token refresh.
- **Resolve lazily.** A client asks for a plan when it is about to play a track, not for a whole
  queue at once, so URLs do not expire while waiting.
- The grant sits in the path, not the query, so servers and proxies can redact it with one rule:
  log `/dulcet/v1/media/***`.
- Responses carrying a signed URL are `Cache-Control: no-store`.
- Success is always binary with a real media type, and failure is always an HTTP error status with
  a problem body. *Why:* OpenSubsonic answers some binary failures with an HTTP 200 error envelope,
  so a client has to inspect every body. Here, the status code is the truth.

### Artwork

`GET /artwork/{artworkId}?size=600` with the normal `Authorization` header works for image loaders
that can send one. For lock screens, car displays and web `<img>`, `POST /media/urls` signs up to
500 artwork URLs in one call. A signed artwork URL stays the same for one user, artwork and size for
`limits.artworkUrlTtlSeconds` (a week on Dulcet Server), so image caches keyed by URL keep working.
*Why not signed URLs inside every response:* signatures expire and would make every list response
different on every read, which breaks ETags and caching. Artwork ids are stable; signing happens
only where it is needed.

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
/me/plays` returns history, newest first. `DELETE /me/plays/{playId}` (`user:delete`) removes a
play from history and counts; its id stays recorded, so a late retry of the same play is still a
`duplicate` and does not bring it back.

**Now playing.** `PUT /me/now-playing` is a heartbeat with device, track, state and position, and a
time to live (default two minutes). It never changes a play count. `GET /now-playing` lists the
user's active devices, plus other users who turned on `settings.shareNowPlaying` (off by default).
Entries carry ids; the track is embedded only for a token with `catalog:read`. *Why separate from plays:* "what is on
right now" and "what was listened to" have different lifetimes, privacy and semantics, as
ListenBrainz's `playing_now` versus `single` showed.

## 11. Lyrics, playlists, the queue and search

**Lyrics.** `GET /tracks/{id}/lyrics` returns documents: the main lyrics and any translations or
pronunciations, each with a BCP 47 language. Lines may have `startMs` and `endMs`; each line may
have `words` with their own timing, for karaoke-style display; a `voice` label marks duets. An
offset in the source is already applied. An empty `documents` list is an answer, not an error.
Where lyrics came from a provider, `provider` names it for attribution.

**Playlists.** Users own playlists; `visibility: public` lets every user of the server read one.
Each entry has its own `entryId`, so the same track can appear twice and still be moved or removed
exactly. Edits: insert (append, or before an entry), move one entry, remove one entry
(`user:delete`), or replace all (with a required `If-Match`, and `user:delete` if it drops
entries); deleting a playlist needs `user:delete`. Every edit changes the playlist's `revision`. Paging a playlist's
entries is pinned to the revision it started at; if the playlist changes mid-walk, the next page
answers `409` and the walk restarts. *Why entry ids:* position-based edits from two devices
corrupt a list; entry ids keep edits exact.

**The queue.** Each user has **one queue**, shared by all their devices, and at most one device
plays it at a time, as in Spotify Connect. A phone can hand playback to the living-room speaker,
and the tablet shows and controls what is playing.

- **Devices.** Each client registers once with `PUT /me/devices/{deviceId}` (a UUID it keeps) and
  sends `Dulcet-Device-Id` on every request. A device is online while it holds an `/events` stream
  or has made any authenticated request carrying its `Dulcet-Device-Id` in the last two minutes.
  Registering is such a request, so a newly registered device is online for two minutes.
- **State and revision.** `GET /me/queue` returns the queue's `revision`, entry count, current
  entry, shuffle, repeat and `activeDeviceId`. Entries page from `/me/queue/entries`, each with its
  own `entryId`. The revision changes on every change to those, and never on a position report.
- **Playback is separate.** `/me/queue/playback` holds state, position (with the wall-clock time it
  was true), the current entry and volume. Only the active device writes it, at least every 30
  seconds while playing; a write from any other device answers `409` `not-active-device` with the
  current queue, and that device stops its audio. *Why separate:* position changes every few
  seconds; if it bumped the revision, every controller's `If-Match` would fail all the time.
- **Edits.** Replacing the queue or changing the current entry, shuffle or repeat takes `If-Match`;
  a stale revision answers `412` with the current queue in `current`. Inserts and removals need no
  `If-Match`, because they cannot lose another device's edit. A person's explicit action retries a
  `412` once on the new revision; an automatic write never retries.
- **Handoff.** `POST /me/queue/transfer` makes a device active. The old one gets a `queue` event and
  stops within a second; the new one starts at the last reported position. Two devices pressing
  play at once: the server applies transfers one at a time, the last wins, and the loser finds out
  from the event or from its next report. An automatic resume sends `If-Match` and gets `412`
  instead of taking over.
- **Remote control.** `POST /me/queue/commands` (play, pause, seek, next, previous, skip to an
  entry, volume) is relayed to the active device as a `command` event and answers `202`; the effect
  shows up as `playback` and `queue` events. With no reachable active device it answers `409`
  `device-unavailable`. A command not carried out within 10 seconds is dropped.
- **Offline.** A device that loses the server keeps playing its local copy of the queue and
  remembers the revision it was based on. On reconnect it sends its changes with `If-Match` set to
  that base revision. If nothing changed meanwhile, its changes apply. If the queue moved on, the
  server's queue wins by default: the device shows it and offers "Continue here", which replaces
  the queue with the local one as a person's explicit action. Plays made offline are submitted as
  usual and are never lost.

*Why one shared queue:* people expect what they started on one device to be there on the next, and
to control a speaker from their phone. The conflict cases that made a shared queue look hard
(two devices starting at once, a controller acting on stale state, a device coming back from
offline) each have one rule above. A household with two people listening at once uses two
accounts.

**Search.** `GET /search?q=…&types=album,track&scope=all` returns results grouped by type, best
match first, each type with its own cursor. Matching and ranking are the server's choice.

## 12. Events and webhooks

**Server-Sent Events.** `GET /events` keeps a `text/event-stream` open, with the token in the
`Authorization` header. (Browsers use a fetch-based reader; `EventSource` cannot send headers, and
a token in the URL is not allowed.) Event types:

| Event | Data |
|---|---|
| `changes` | The feed advanced: the new cursor and the entity types involved. Pull `/sync/changes`. |
| `queue` | The queue's revision changed: the new revision, the active device, and what changed. |
| `playback` | The active device reported its playback state. |
| `command` | A remote-control command, sent only to the active device's stream. |
| `nowPlaying` | A device the user can see started, paused, moved or stopped. |
| `scan` | A library scan started, progressed or finished. |
| `revoked` | The token was revoked; the stream then closes. |
| `heartbeat` | Sent at least every 30 seconds so proxies keep the connection. |

Each event's `data` is one JSON object with a schema in the contract (`EventData`). Events are
hints, not data of record. A client that misses some loses nothing: the feed has everything.
Reconnect with `Last-Event-ID`.

**Webhooks.** `POST /webhooks` subscribes an HTTPS URL to events (`play.recorded`,
`nowPlaying.changed`, `itemState.changed`, `playlist.changed`, `queue.changed`, `catalog.changed`,
`scan.completed`). The delivery body is defined in the contract's `webhooks` section.
Deliveries are signed with the [Standard Webhooks](https://www.standardwebhooks.com/) scheme:
`webhook-id`, `webhook-timestamp` and `webhook-signature` (HMAC-SHA256 over
`id.timestamp.body`, with a `whsec_` secret shown once). Receivers reject stale timestamps and
dedupe on `webhook-id`. Failed deliveries retry with backoff for about a day, then the hook is
disabled with `disabledReason: delivery-failures`; an admin may also disable one (`admin`). A
webhook only ever carries what its creating token could read: asking for an event type outside its
scopes answers `403 insufficient-scope`, naming those events in `events` (the `/events` stream
instead leaves out, silently, what the token cannot read). A webhook belongs to the grant or token
that created it and is deleted, not disabled, when that grant or token is revoked or expires.

Targets must be HTTPS and must not resolve to private, loopback or link-local addresses, unless an
admin allows private targets (home automation on the same network is a real use). The server
checks the address it actually connects to, on every delivery, and follows no redirects. A refused
target answers `422 webhook-target-refused`. *Why:*
without that rule, a webhook is a way to make the server probe its own network.

## 13. AI and MCP

Every conforming server can expose a [Model Context Protocol](https://modelcontextprotocol.io)
endpoint at `/dulcet/v1/mcp` (Streamable HTTP transport). An AI assistant connects to it with the
same OAuth flow as any app, and gets tools built on the same API and the same scopes.

| Tool | Scope | Does |
|---|---|---|
| `search_catalog` | `catalog:read` | Search artists, albums, tracks and playlists. |
| `get_artist`, `get_album`, `get_track` | `catalog:read` | Read details, including credits and external ids. |
| `get_lyrics` | `catalog:read` | Read a track's lyrics. |
| `list_my_library`, `list_favorites`, `list_playlists`, `get_playlist` | `user:read` | Read the user's collection. |
| `list_recent_plays`, `get_listening_stats` | `user:read` | Read play history and simple totals. |
| `get_now_playing`, `get_queue` | `user:read` | What is playing, and what is next. |
| `create_playlist`, `add_to_playlist` | `user:write` | Make or extend a playlist. |
| `set_favorite`, `set_rating`, `add_to_my_library` | `user:write` | Change item state. |
| `add_to_queue`, `control_playback` | `user:write` | Queue tracks; play, pause, skip or seek on the active device. |
| `remove_from_my_library`, `remove_from_playlist`, `delete_playlist`, `delete_play` | `user:delete` | Remove things. |

The exact definitions, with input and output schemas and annotations, are in
[`mcp-tools.json`](mcp-tools.json). A server's `tools/list` returns exactly those the token's scopes
allow, and no others. Each is returned with the file's `x-dulcet-scope` replaced by
`_meta: {"dulcet/scopes": [...]}`, naming the scopes it needs.

Rules:

- **Tools follow scopes.** A token without `user:write` sees no write tools, and one without
  `user:delete` sees no removal tools. Giving an assistant `user:write` lets it add and change
  things; letting it remove anything is a separate grant on the consent screen.
- **Annotations are honest.** Every tool carries MCP's `readOnlyHint`, `destructiveHint` and
  `idempotentHint`. Every `user:delete` tool is destructive, and so are `set_rating` and
  `set_favorite`, because they overwrite a value (and unfavouriting discards a date) that no later
  write restores. Clients should confirm destructive calls with the person.
- **No administrative tools in v1.** No tool touches other users or administers the server.
- **Tool results are data.** Titles, tags and lyrics are user-supplied text and may contain
  instructions. Servers return them as structured content, never as instructions, and AI clients
  must treat them as untrusted.
- **Same limits.** MCP calls count against the same rate limits and appear in the same audit trail
  as API calls. Lists page at 100 items.
- **Transport safety.** The endpoint validates `Origin` on every request and binds each MCP
  session to the token that opened it. Its protected-resource metadata is at
  `/.well-known/oauth-protected-resource/dulcet/v1/mcp`.

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
| `reauthentication-required` | 403 | The person must sign in again first (deleting the account). |
| `not-found` | 404 | No such resource, or the user cannot see it. |
| `conflict` | 409 | The request conflicts with current state. |
| `not-active-device` | 409 | Only the queue's active device may do this (`current` holds the queue). |
| `device-unavailable` | 409 | The target device is offline or cannot play. |
| `cursor-types-mismatch` | 400 | A sync cursor was sent with different `types` than it was made for. |
| `cursor-expired` | 410 | The sync cursor can no longer be served. |
| `gone` | 410 | The item was deleted; `replacedBy` lists successors if it was merged or split. |
| `revision-mismatch` | 412 | `If-Match` did not match (`current` holds the current state). |
| `unsupported-media` | 415 | The request body's media type is not accepted. |
| `unknown-item` | 422 | An id in the body is not in the catalog. |
| `idempotency-key-reused` | 422 | Same key, different request. |
| `token-lifetime-too-long` | 422 | A token asked for a lifetime past `limits.maxTokenLifetimeDays`. |
| `webhook-target-refused` | 422 | A webhook URL is not HTTPS, or resolves to an address the server refuses. |
| `precondition-required` | 428 | This write needs `If-Match`. |
| `rate-limited` | 429 | Wait `Retry-After` seconds. |
| `server-error` | 500 | The server failed. |
| `unavailable` | 503 | Temporarily unavailable; `Retry-After` may say when. |

Every operation documents its specific errors, and also a `default` response: any operation may
answer `429`, `500` or `503` as a problem.

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
  `Idempotency-Key`, `If-Match`, `If-None-Match`, `Last-Event-ID`, `Dulcet-Device-Id` and
  `Dulcet-Action-Time`, and exposing `ETag`,
  `Location`, `Retry-After`, `Dulcet-Sync-Cursor`, `Deprecation`, `Sunset` and the `RateLimit`
  headers. An admin may narrow the allowed origins. The server's own sign-in pages use cookies and
  are never CORS-enabled. *Why:* this lets anyone build a web client for any server without the
  server admin's help, without opening a CSRF hole.
- **No credential reaches a log.** Servers never log `Authorization`, request bodies of the token
  endpoints, refresh tokens, webhook secrets, passwords or media grants (`/media/***`). Clients
  redact the same before anything reaches a log, a diagnostic or a crash report. Conformance tests
  check this with canary values.
- **TLS.** HTTPS everywhere except consented HTTP to a local address (section 3). Clients use the
  operating system's trust store, and may offer pinning, pairing or an imported CA with consent.
  Servers should send HSTS on HTTPS.
- **Least data.** Now playing is visible only to the user, to admins, and to other users only when
  the user turns sharing on. Play history is private to the user. Exports contain only the
  requesting user's data. A user can delete single plays and their whole account.
- **Outbound requests are guarded.** Fetching a client's metadata document and delivering a webhook
  are the only requests a server makes on a client's behalf, and both refuse private, loopback and
  link-local destinations unless an admin allows them, check the address actually connected to,
  and follow no redirects.
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

**Reserved names.** `dulcet.artist-info` (biographies and similar artists), `dulcet.folders`
(browsing by file path), `dulcet.bookmarks` (resume points in long tracks), `dulcet.gapless`,
`dulcet.hls` and `dulcet.crossfade` are reserved for later versions of this standard. Local network
discovery, when it is specified, will use the DNS-SD service type `_dulcet._tcp`.
`/.well-known/dulcet` is always served at the host's root, never under a path prefix.

**Plugins.** Future metadata and lyrics providers, radio, sharing and similar features fit this
shape: each becomes a named capability with its own version, its own paths under the base and, if
it needs one, its own scope. Lyrics already carry a `source` and `provider`; catalog items already
carry `externalIds` with room for more registries. Nothing about plugins is designed yet, and
nothing in v1 has to change to add them.

## 17. Evolution and deprecation

**v1 is meant to last for years. There is no planned v2.** Everything below exists so that a client
written against v1.0 keeps working against every later v1 server, and a server can grow without
waiting for every app to update.

- **Within v1, only additions.** New endpoints, new optional request fields, new response fields,
  new enum values, new change and event types, new problem types, new capabilities. Each bumps the
  minor version reported in `apiVersion` (`1.0`, `1.1`, …). A minor version never removes or
  changes the meaning of anything.
- **What clients must do** so that this works: ignore unknown fields; treat every enumerated string
  as open (map an unknown value to a neutral default); skip unknown change, event and op types;
  handle an unknown problem type by its status; tolerate a missing optional field; never send a
  field the server's `apiVersion` does not define and expect it to be honoured.
- **What servers must do:** never require a new request field in v1; never change a field's type,
  unit or meaning; keep accepting every documented value; ignore unknown query parameters and
  unknown body fields rather than rejecting them; return the resulting representation from every
  write, so a client never has to guess what the server did with fields it did not send.
- **Room was left on purpose.** Timestamps have millisecond precision, sizes are 64-bit, few fields
  are required, every enumerated string is open, every operation has a `default` problem response,
  and every resource has an `extensions` object. These are the places a contract usually has to
  break; v1 does not.
- **Deprecation inside v1** marks a feature with the `Deprecation` header (RFC 9745), a `Link` to
  its replacement, and a note in the contract. A deprecated v1 feature keeps working for the life
  of v1.
- **If a v2 ever exists**, it is served at `/dulcet/v2` beside v1, both appear in
  `/.well-known/dulcet`, and v1 keeps working for **at least three years** after v2 is published.
  Servers keep v1 for as long as is practical after that; v1 responses carry `Sunset` (RFC 8594)
  only once a date is set.
- **Changes to this standard** are proposed as pull requests against `openapi.yaml`, with the
  README and conformance suite changed in the same pull request, and recorded in the design spec's
  revision log. Core names (`dulcet.*`, `urn:dulcet:problem:*`, scope names) are assigned only
  that way; anyone may propose one, and a name once published is never reassigned.

**Why no per-resource representation versions.** Some APIs version each resource's shape (a media
type parameter like `version=2`). v1 does not need it: a new field is added beside the old one, a
new behaviour is a new capability or endpoint, and the old shape stays valid. If a resource ever
truly needs a second shape, it will arrive as a capability that lets a client ask for it with a
media-type parameter, so clients that do not ask keep the original.

*Why years:* clients ship through app stores and people do not update apps on old devices; a server
operator should be able to upgrade without stranding a family member's tablet.

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
4. Play with `POST /playback/decisions` when a track is about to play, hand the URL to the native
   player, and ask again when it expires. Submit plays with your own UUIDs; retry freely.
5. Write with idempotent `PUT`/`DELETE`, `If-Match` where offered, `Idempotency-Key` on `POST`,
   and `Dulcet-Action-Time` on writes sent after the fact.
6. To follow the shared queue, register the device, send `Dulcet-Device-Id`, hold `/events`, and
   report playback only while you are the active device.
7. Ignore what you do not know (section 17).
8. Run the client conformance scenarios (`CONFORMANCE.md`) against the reference mock.

## 20. Claiming compatibility

Compatibility is claimed per **profile**, by passing the conformance suite for that profile at a
named suite version and publishing its report.

| Profile | Covers |
|---|---|
| **Core** (required for any claim) | Discovery, capabilities, OAuth code + PKCE with `iss`, offline client identity, refresh with its retry window, revocation, personal tokens and their scope limits, catalog, search, artwork, playback decisions, signed media URLs, item state with action times, My Library, plays, playlists, the delete scope, account deletion, export, errors, pagination, ETags, idempotency, CORS, logging hygiene. |
| **Sync** | The change feed, snapshots, replay, type-bound cursors, hidden and merged tombstones, restores, `410` handling. |
| **Device** | The device authorization grant. |
| **Transcoding** | Transcoded playback plans, estimated lengths, `offsetMs`. |
| **Lyrics** | Lyrics documents, synced lines. |
| **Queue** | The shared queue, devices, active-device handoff, remote commands, offline reconnect. Requires Events. |
| **Events** | Server-Sent Events. |
| **Webhooks** | Signed webhooks with retries. |
| **MCP** | The MCP endpoint and its scope rules. |
| **Admin** | Users and scans. |
| **Testing** | The test hooks below. Needed only to run the full suite; never claimed by a production server. |

### Testing profile

Some behaviour can be proven only if a test can make it happen: a cursor older than retention, an
expired media URL, a restore, a rate limit, a sign-in with no person present. The **Testing
profile** adds the few hooks for that, and nothing else.

- **Off unless started on.** Test mode is a server-start setting (on Dulcet Server, an environment
  variable), never an API call. A server refuses to start in test mode on data that was ever served
  without it, so a production library cannot be switched into test mode by mistake. A server in
  test mode says `testMode: true` in `/.well-known/dulcet`, and the conformance suite refuses to
  run against a server that does not.
- **Admin only.** Every hook needs the `admin` scope as well as test mode. Outside test mode the
  hook paths answer `404`, like any unknown path.
- **The hooks.** `PUT /admin/test/settings` sets in-memory overrides: auto-approval of sign-in and
  device codes for named client ids (every other OAuth rule still applies), journal retention in
  seconds, signed-URL lifetimes in seconds, a rate limit, trusted clients (`trustedClients`, client
  id metadata documents given inline, so a verified client can be signed in with no network), and
  webhook delivery (`webhooks.allowPrivateTargets` for an `http://` or private receiver beside the
  suite, `webhooks.retryScheduleSeconds` and `webhooks.disableAfterFailures`, so retries and
  disabling happen in seconds). `POST /admin/test/checkpoints` saves
  the data as a backup would; `POST /admin/test/checkpoints/{id}/restore` restores it through the
  server's real restore path, so `instanceId` changes as after any restore.

Things that need no hook: merges and retags are made by changing files in the test library and
running `POST /admin/scans`; the server's own log is read from where the operator captured it
(a file or the container's output); the token lifetime limit is published in
`limits.maxTokenLifetimeDays`. The device-code lockout on the verification page stays a manual
check: that page is HTML for people, and the standard does not define it as an API.

A claim reads, for example: "Dulcet API v1.0 compatible: Core, Sync, Device, Transcoding (suite
1.0.0, report linked)." The name "Dulcet API compatible" means exactly that the suite passed; it is
not a certification body or a trademark licence.

## 21. Prior art, and why not extend OpenSubsonic

We read these, took ideas, and copied no text.

| Source | What we took | What we did differently |
|---|---|---|
| **OpenSubsonic** | Extension negotiation by name and integer version; the client-declares, server-decides transcoding model (`getTranscodeDecision` with opaque parameters, and `transcodeOffset` for starting mid-track); synced lyrics with languages; play submission separate from streaming; `formPost`, which moves parameters out of the URL; `playbackReport`, a separate now-playing report; `indexBasedQueue`, a saved queue addressed by position. | A resource-oriented HTTPS + JSON API instead of `/rest/<method>`; credentials only in a header; queue entries addressed by entry id rather than index, so concurrent edits stay exact. |
| **Jellyfin** (OpenAPI) | A device profile describing what a client can direct-play and what it accepts from a transcode; a published OpenAPI document generated from the server. | Header-only auth (Jellyfin also accepts a token in the query); one sync model. |
| **Plex** | Play queues that can be resumed elsewhere; multiple libraries per server. | An open standard any server can implement, rather than one product's own API. |
| **Spotify Web API and Connect** | OAuth with PKCE for public clients; small, readable scopes; a playlist revision (`snapshot_id`) that guards concurrent edits; one queue per user with one active device and transfer between devices. | Cursors instead of offsets; queue edits guarded by a revision and `If-Match`; remote commands relayed over the user's own event stream. |
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
  and `formPost` lets a client send parameters in a form body, but every server must still accept
  credentials in the URL, and media URLs handed to a native player carry them. Header-only bearer
  tokens, OAuth, scopes and revocation do not fit the base protocol's request shape.
- **Errors inside HTTP 200.** Failures arrive as envelopes in a successful response, including for
  binary endpoints, so every client inspects every body.
- **Offset paging and no change token.** There is no way to sync incrementally or to page without
  skipping rows during a scan. Adding a change feed as an extension would still leave every
  existing endpoint without cursors, ETags or conditional requests.
- **Methods, not resources.** Each operation is a verb at `/rest/<method>`, so generic HTTP
  tooling (caches, ETags, `Location`, OpenAPI generators) has little to work with.

Fixing those in OpenSubsonic would break the compatibility that makes it valuable. A separate
standard can get them right from the start, and a server can, like Dulcet Server, serve both.
