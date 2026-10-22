# Discover and Wishlist: design

**Status:** proposed, 2026-10-22. Not yet approved; nothing is built.
**Touches:** Dulcet Server (§29), Dulcet API (§30), the core, all six app surfaces, `FEATURES.yml`.

## 1. Intent

A person using Dulcet can only find music their server already holds. They should be able to look
past it: search all recorded music, see what is trending and newly released, follow suggestions
and public playlists, and say "I want this" from inside the app.

Dulcet does not store, download or buy that music. A wish is a row in the server's database. The
server exposes it through Dulcet API, its change feed and its webhooks, so other software the
server's owner runs can act on it: fetch the album, buy it, or simply notify someone. When the
music later appears in the library, the server closes the wish by itself and puts the item in
that person's My Library.

So the loop is: **discover → wish → (someone else's code acquires it) → scan → arrived, in My
Library.** Dulcet owns the first, second and last steps and a clean contract for the middle one.

## 2. Goals

1. **Search everything.** Artists, albums and songs from the open MusicBrainz database, with cover
   art from the Cover Art Archive. Every result says whether the server already holds it; one that
   it holds opens or plays the real library item.
2. **Discover shelves.**
   - *Trending this week*: ListenBrainz sitewide top artists, albums and songs.
   - *New releases*: ListenBrainz fresh releases, with releases by artists already in the person's
     library first.
   - *Artists like yours that you don't have*: ListenBrainz similar-artist data, seeded from the
     person's most played and starred artists on this server.
   - *Public playlists*: ListenBrainz public playlist search and pages. Songs the server holds are
     playable in place; the rest can be wished for, one by one or all missing at once.
3. **Pages for music the server does not hold.** An artist page (discography by album, newest
   first) and an album page (tracklist), each with links out to where the music can be heard or
   bought (Bandcamp, YouTube, SoundCloud, streaming and purchase links, from MusicBrainz URL
   relationships).
4. **Wishlist.** Add an artist, album or song from any of the above. Each person has their own
   wishlist. It appears in Library and on Discover, and each wish shows its state: *Wanted*,
   *Requested*, *Searching*, *Not found*, *Arrived*.
5. **API and webhooks.** Wishes are a Dulcet API resource with change-feed entries, webhook events
   and MCP tools. A server admin, or an integration holding an admin token, can read every user's
   wishes and set a wish's state and a short note ("Searching", "Not found: no lossless copy").
6. **Fulfilment.** When a scan adds a matching item, the wish becomes *Arrived*, links to the
   library item, and the item is added to that person's My Library.
7. **Every surface.** iPhone, iPad, Mac, Apple TV, Android phone and Android TV.

## 3. Non-goals

- **No acquisition in Dulcet.** No downloading, purchasing, file-sharing or store code in the
  apps or the server. CORPUS §7 ("not a library manager") stands: the wishlist records a want; what
  happens next is the business of whatever the server's owner connects to the webhooks.
- **No audio previews in v1.** The open databases carry no audio. Links out cover listening. A
  preview source is a separate later decision that needs its own terms review.
- **No personal ListenBrainz or MusicBrainz accounts in v1.** No "created for you" playlists, no
  per-person ListenBrainz sign-in. Suggestions come from this server's own history plus open data.
- **Not on plain OpenSubsonic servers in v1.** Discover and Wishlist exist only where a server
  advertises them. On every other server the apps look and behave exactly as before.
- **No social layer.** People do not see each other's wishlists in the apps.
- **No editing of MusicBrainz data.**

## 4. Anti-goals: it fails if

- **A device talks to a third party.** Every outside lookup is made by the server. Devices talk
  only to their server, as they do now. The server sends no user name, account id or device id
  upstream.
- **It hammers the open services.** MusicBrainz allows about one request per second per address
  and asks for a descriptive User-Agent. A household shares one server, so it shares one cache and
  one polite queue.
- **A wish is lost or doubled.** Adding the same thing twice keeps one wish; an add made offline
  survives a relaunch; a removal is final.
- **Discover blurs into the library.** Music the server holds and music it does not must look
  different at a glance, and what the server holds always routes to the real library item.
- **It costs Navidrome users anything.** No new requests, no new UI, no slower start.

## 5. Constraints that already bind

- **Two contracts only** (CORPUS §4.1). The apps reach Discover and Wishlist through the server:
  `/rest` extensions for the apps today (they speak `/rest` to Dulcet Server, §30.3) and Dulcet API
  resources for everything else. Calls from the server to open databases follow the LRCLIB
  precedent (§29.5): a User-Agent naming Dulcet Server and its repository, requests serialized per
  host, `Retry-After` honoured, answers cached, an admin switch to turn it off.
- **Capability gates are conjunctions** (CORPUS §4.7). Discover and Wishlist are two independent
  capabilities, so an admin can turn off outside lookups and keep wishes, or the reverse.
- **Ids are opaque** (CORPUS §4.9). A wish has a server-minted id. MusicBrainz ids travel as
  `externalIds`, the same field catalog items already use.
- **Dulcet API v1 is additive** (§30.2). Everything here is new optional capabilities, paths,
  event types and scopes; nothing existing changes meaning.
- **The reader rules hold** (CORPUS §4.11). Discover answers are cached on the device with their
  age, an error never replaces a cached answer, and nothing is shown as live that is not.
- **Wishes ride an outbox.** Add and remove follow the favourites outbox's guarantees (§18.6), as
  My Library does (§29.9): shown at once, durable, idempotent, rolled back on a permanent refusal.
- **Automated runs never reach the live services.** CI and conformance use a local fixture server
  that replays recorded MusicBrainz, ListenBrainz and Cover Art Archive answers, and both are
  pointed at it by base-URL settings. Nothing automated writes anywhere but the disposable server.

## 6. Decided

### 6.1 Sources (OBSERVED 2026-10-22 unless marked)

| Need | Source | Note |
|---|---|---|
| Search, artist and album pages | MusicBrainz WS/2 (`/ws/2/{artist,release-group,recording}`, JSON) | Core data CC0. `inc=url-rels` returned Bandcamp, YouTube, SoundCloud, streaming and purchase links. Rate limit, per MusicBrainz's published page: an average of one request per second per source address; above that, **every** request from the address is refused with 503 until the rate falls. The User-Agent must carry enough to contact the maintainers. |
| Artwork | Cover Art Archive (`/release-group/{mbid}/front-250`) | Answers with a redirect to the image. Images belong to their owners; the server caches thumbnails, as other self-hosted music software does. |
| Trending | ListenBrainz `/1/stats/sitewide/{artists,recordings,release-groups}?range=week` | No token needed. Reflects ListenBrainz's own listeners, so it skews toward whoever is most active there (one artist held the top two songs in the week checked). Shown as "Trending on ListenBrainz", never as a general chart. |
| New releases | ListenBrainz `/1/explore/fresh-releases/?days=N` | No token. 1,984 releases for seven days, each with release-group MBID and Cover Art Archive id. |
| Similar artists | ListenBrainz labs similar-artists | No token. Seeded by artist MBID. |
| Public playlists | ListenBrainz `/1/playlist/search?query=` and `/1/playlist/{mbid}` | No token; 565 public results for "jazz". JSPF with recording MBIDs. |
| Radio-style suggestions | ListenBrainz LB Radio | **Needs a ListenBrainz token** (answered 401 without one). Out of v1; an optional admin token can enable it later. |

### 6.2 Server

- A `discover` part of `:server` with one upstream client per source. Each client serializes its
  requests, keeps MusicBrainz at or under one per second, honours `Retry-After` and 503, and caches
  answers on disk. When the upstream is unreachable, a cached answer is served with its age; with
  no cached answer, the request fails as temporarily unavailable, never as empty.
- Settings: an on/off switch for outside lookups (on by default, like LRCLIB), base URLs per source
  (for the test fixture server, and for owners who run their own MusicBrainz mirror), and an
  optional ListenBrainz token kept for later use.
- **The one-request-per-second budget shapes search.** *Everywhere* asks for artists first, then
  albums, then songs, one request each, and the apps show each kind as it arrives. The apps
  debounce typing; the server never has more than one MusicBrainz request in flight and answers
  repeats from its cache. The server's address may be shared with other MusicBrainz clients, so a
  503 backs the whole client off rather than retrying. Owners who run a MusicBrainz mirror point
  the base URL at it and the limit no longer applies.
- **Matching** a discover item to the library: by MusicBrainz id first, then by normalized artist
  and title. The scanner stores artist, album-artist, release, recording and track MBIDs but **no
  release-group MBID** (`scan/TrackInfo.kt`). An album's release group comes from the
  `MUSICBRAINZ_RELEASEGROUPID` tag where a file carries it, otherwise from a cached
  release-to-release-group lookup made only for albums that are candidates for an open wish, so
  no library-wide rescan or lookup sweep is needed. Name matching must exist, because many files
  carry no MBIDs. Per kind:
  - **Song:** same recording MBID; or, when the track has none, same normalized artist and title.
    Versions whose titles mark them as live, remix, demo, acoustic or instrumental do not satisfy a
    wish whose title doesn't say the same.
  - **Album:** any edition of the release group. The wish is *Arrived* when the library album holds
    every track of at least one edition. Fewer than that, it stays open and shows "8 of 12 here".
  - **Artist:** the first album by that artist (MBID or name) arriving fulfils it. What an
    integration fetches for an artist wish is the integration's decision.
- **The "on this server" flag** on discover items has three values: *held*, *partly held* (an
  album with some of its tracks here) and *not held*. MBID matches and name matches both count as
  held; a person who finds a wrong match uses **This isn't it** on the item or the arrived wish.
  That reopens the wish and records the pair so the matcher never repeats it for that user.
- **Wish storage:** one table of wishes per user. Each holds kind (artist, album, song), the
  MusicBrainz ids that identify it, a display snapshot (title, artist, year, artwork reference),
  where it was added from, the time added (wall clock), state, an optional note with the time it
  was set, and the library item once fulfilled. One wish per user per kind and MBID, so a repeat
  add is a no-op that returns the existing wish. Every change writes a journal row in the same
  transaction (§30.4).
- **States** are an open enum: `wanted` (set by the person), `requested`, `searching`,
  `unavailable` (set by admins and integrations), `fulfilled` (set by the server's matcher only).
  - Admins and integrations move a wish freely among `wanted`, `requested`, `searching` and
    `unavailable`, with an optional note. The person sees the state and the note.
  - `fulfilled` is final. No integration can change it, and deleting the library item later does
    not reopen it. Only **This isn't it** reopens it, as `wanted`.
  - A person can remove a wish in any state. Removal leaves a tombstone in the feed. Adding the
    same thing again makes a new wish with a new id and fires `wishlist.requested` again, because
    it is a new request.
- **Where wishes come from.** In the apps, every add starts from a discover item, so with outside
  lookups turned off the apps show the existing wishlist with remove only. Through the API, a
  caller with `user:write` can add a wish by MBID with its own display snapshot, without any
  upstream call.
- **Fulfilment** runs after every scan: open wishes are matched against what the scan added. A
  match sets `fulfilled`, links the item, and adds it to that person's My Library (artist wishes
  add the artist; album and song wishes add the album or song). A wish that is fulfilled stays
  visible as *Arrived* for 30 days and then drops out of the default list. It stays readable
  through the API.
- **Dulcet API**, additive to v1:
  - capabilities `dulcet.discover` v1 and `dulcet.wishlist` v1, advertised in `GET /capabilities`;
  - discover reads (search, shelves, artist, album, playlist search, playlist) under `catalog:read`;
  - the person's own wishes: list, add, remove under `user:read`, `user:write`, `user:delete`;
  - every user's wishes, and setting a wish's state and note, under `admin`;
  - webhook events: `wishlist.changed` for the person's own wishes (`user:read`), and
    `wishlist.requested` for any user's new wish (`admin`; the event an integration subscribes to),
    signed and delivered by the existing Standard Webhooks machinery;
  - change-feed entries for wishes, with removal tombstones;
  - MCP tools to search discover, list wishes and add one (`user:write`), none for admin actions.
- **`/rest` extensions for the apps**, specified in `docs/dulcet-server/` like
  `dulcet-library-extension.md`: `dulcetDiscover` v1 and `dulcetWishlist` v1, each advertised by
  `getOpenSubsonicExtensions`. Discover and wish payloads carry the same "on this server" flag and
  library ids as the Dulcet API forms.
- **Artwork for discover items** is served by the server, so devices never contact the Cover Art
  Archive directly.
- **Per-person shelves from a shared cache.** "Artists like yours" asks ListenBrainz about one seed
  artist per request and caches the answer by seed artist for the whole server; each person's
  shelf is assembled on the server from those answers. ListenBrainz therefore learns which artists
  this server asks about, from the server's address, and never who listens to them. The
  Discover screen's credit line says that suggestions use ListenBrainz.
- **Auth reuses what exists.** The `admin` scope and personal access tokens already exist in Dulcet
  API (§30.2); no new auth path is needed. The apps on `/rest` read wishes, add and remove them,
  and never set an integration state.

### 6.3 Core

- One seam per capability (`DiscoverTransport`, `WishlistTransport`) with an OpenSubsonic
  implementation, following `MyLibraryTransport` (§29.9). Detection, caching per account namespace,
  sign-out removal and the rebind purge work as they do for My Library.
- Wishlist add and remove are outbox mutations with My Library's guarantees.
- The core exposes shelf, search, page and wishlist read models to the shells through the Swift
  boundary rules (CORPUS §4.8).

### 6.4 The apps (my calls on layout; each can change)

- **Discover** becomes a top-level destination: a fifth phone tab (Listen Now, Discover, Library,
  Browse, Search), a sidebar item on iPad and Mac, a tab on Apple TV and Android TV. Its shelves are
  Trending, New releases, Artists like yours, Public playlists and the person's recent wishes.
- **Search** gains a scope switch, *Your server* | *Everywhere*, like Apple Music's *Your Library* |
  *Apple Music*. Everywhere searches Discover. Typing never sends a request per keystroke to the
  outside world: the server caches, and the apps debounce.
- **Library** gains a **Wishlist** section beside Playlists, Artists, Albums and Songs.
- Items the server holds look exactly like library items today. Items it does not hold carry a
  clear "Not in your library" treatment and a wish button whose icon cannot be mistaken for
  favourite or Add to My Library.
- Credit line on Discover: "Data from MusicBrainz and ListenBrainz".

## 7. Order of work

1. Server: sources, cache, matching, wishes, fulfilment, Dulcet API, webhooks, `/rest` extensions,
   with the fixture upstream for tests.
2. Core, then iPhone, iPad, Mac and Android phone: Search *Everywhere*, artist and album pages,
   wishes, Library › Wishlist.
3. Discover shelves on those four.
4. Apple TV and Android TV.

Each step is its own pull request with its own evidence.

## 8. Evidence

- **Fixtures.** The fixture upstream replays answers recorded once from the live services by a
  person, during development. MusicBrainz and ListenBrainz data is CC0, so the recorded JSON is
  committed. Cover art is not: the fixture serves generated placeholder images, never recorded
  ones.
- Server: tests for rate limiting, `Retry-After`, cache and stale-answer behaviour, matching by MBID
  and by name, idempotent adds, every state transition, fulfilment after a scan, scopes, and
  webhook signing and delivery, all against the fixture upstream.
- Core: transport, read model and outbox tests, including the native run (`:core:macosArm64Test`).
- Conformance: the disposable Dulcet Server with the fixture upstream; and a Navidrome control
  proving that nothing new is requested or shown.
- Apps: UI tests per surface for search Everywhere, adding a wish, removing it, and seeing it
  arrive after a scan.
- `FEATURES.yml` rows: `discover.search`, `discover.shelves`, `discover.pages`, `wishlist.add`,
  `wishlist.state`, `wishlist.fulfil`, `wishlist.api`, one cell per surface.

## 9. Left to the builder

Exact paths, method and field names; cache lifetimes; table layout; name normalization rules; how
many items each shelf shows; icons and layout inside each platform's conventions.

## 10. To confirm before the PROD release

- MetaBrainz's terms for free software that many independent servers run. ASSUMED acceptable,
  since Dulcet charges nothing and each server makes its own small number of requests; read the
  current terms. If they rule it out, outside lookups default to off and the rest ships unchanged.
- ListenBrainz's rate-limit headers and current values; the clients obey whatever headers it sends.
- Cover Art Archive's terms for caching thumbnails. If caching is not allowed, the server proxies
  without keeping a copy.

## 11. Calls made without the maintainer

Each can be reversed before or after building: Discover only on servers that advertise it; no
previews in v1, links out instead; a fifth phone tab named Discover; Library › Wishlist; search
scopes *Your server* | *Everywhere*; arrived items go into My Library automatically; arrived wishes
leave the default list after 30 days; the matching rules in §6.2; fixtures of recorded CC0 JSON
with placeholder art.
