# Discover and Wishlist Server Implementation Plan

> **Execution review, 2026-10-22:** Implement directly in the owning thread, task by task, test first and commit each task. The app-specific superpowers execution sub-skill does not apply. Independent review follows the PR and is arranged by the owning session. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Deliver design §7 step 1: server-only discovery, per-user wishes and scan fulfilment, with complete Dulcet API and OpenSubsonic extension contracts.

**Architecture:** Source clients share a per-origin polite transport and persistent public-answer cache. A matcher applies current catalog permissions and each user's rejected pairs; one wishlist service owns durable mutations, transitions and atomic fulfilment into My Library. Dulcet API, `/rest` and MCP adapt those services, reusing journal, sync, events and Standard Webhooks delivery.

**Tech Stack:** Kotlin/JVM 17, Ktor CIO, SQLite JDBC, kotlinx.serialization JSON, coroutines, JDK HttpClient/HttpServer, kotlin.test/JUnit Platform. No new runtime dependency; `:server` remains independent of `:core`.

**Spec:** `docs/superpowers/specs/2026-10-22-discover-and-wishlist-design.md`, especially §§5–8 and settled §11; `docs/superpowers/specs/2026-08-18-dulcet-design.md` §§29–30. Maintainer approval dated 2026-10-22 in the lane brief supersedes the older “proposed” status line. Implementation follows review of this plan.

## Global Constraints

- “Every outside lookup is made by the server.” Devices receive server artwork references. No user name, account id or device id goes upstream.
- “No acquisition in Dulcet.” No previews, personal upstream accounts, LB Radio, acquisition clients or admin MCP tools.
- “Automated runs never reach the live services.” Every upstream and server base URL is a setting. Automated behavioral tests use local disposable fixtures, including redirects and failures; fixtures never contain recorded cover images.
- “Discover and Wishlist are two independent capabilities.” Advertise `dulcet.discover` v1 / `dulcetDiscover` v1 and `dulcet.wishlist` v1 / `dulcetWishlist` v1 independently.
- MusicBrainz: at most one in flight, public-origin starts at least 1,000 ms apart, whole-host backoff on 503/429, honor `Retry-After`. Send existing `USER_AGENT`, naming Dulcet Server and its repository.
- “Ids are opaque.” Wishes have server-minted ids; MBIDs travel in `externalIds`. Wire states/enums remain open for readers.
- “Every change writes a journal row in the same transaction.” Reuse 90-day journal retention, timeline continuity, scoped cursors and durable Standard Webhooks delivery.
- Exact states: `wanted`, `requested`, `searching`, `unavailable`, `fulfilled`. Only matcher sets `fulfilled`; only “This isn't it” reopens it as `wanted`. Removal leaves a feed tombstone. Re-add creates a new id and `wishlist.requested`.
- Album arrival means every track of at least one edition, not a matching id or count alone. Partial albums report e.g. `8` of `12`. Artist fulfilment requires an album.
- Arrived wishes leave the default list 30 days after fulfilment but remain readable through API/history/sync.
- “Dulcet API v1 is additive.” Use existing `catalog:read`, `user:read`, `user:write`, `user:delete`, `admin` and `account`; no new auth path.
- Public text describes Dulcet only. GitHub text contains no em dashes. Use the supplied commit identity/trailers. One eventual implementation PR from `feat/discover-server` supersedes docs-only #352. The approved execution authorizes push, PR and CI, but no merge or review requests.

## Review Focus

1. Late add retries after removal, including lost responses: removal stays final and an intentional newer re-add works (Tasks 9, 13, 15).
2. Same-named editions, bonus tracks, duplicate recordings and multi-disc positions: equal counts cannot manufacture completeness (Tasks 4, 8, 11).
3. Hidden sources and shared upstream cache: matches, partial counts, seeds and rejection pairs remain specific to the caller (Tasks 5, 8, 10, 12).
4. Restart, malformed JSON, truncated browse pages and unavailable metadata: retain good cache and never turn uncertainty into empty results or arrival (Tasks 2–5, 11).
5. Scan racing removal, rejection, catalog replacement or shutdown: no resurrection, duplicate membership, uncommitted webhook or lost evaluation (Tasks 10–11, 14, 17).

---

## Repository map and execution conventions

Read both specs, `CORPUS.md` and `CLAUDE.md` before implementation. Build on this branch base while #349 remains open. Before opening the PR, fetch origin and merge origin/main with a merge commit, reconcile actual changes, compile integrations and rerun the server suite. Do not anticipate #349 edits. No Gradle run is needed for this plan-only turn.

OBSERVED documentation checked 2026-10-22: [MusicBrainz API](https://musicbrainz.org/doc/MusicBrainz_API) documents browse paging, maximum 100 rows, and shortened release pages under its 500-track budget. Advance offsets by returned rows. [ListenBrainz API source](https://github.com/metabrainz/listenbrainz-server/blob/master/docs/users/api/index.rst) documents one request per second and `X-RateLimit-{Limit,Remaining,Reset-In,Reset}`. [Labs similar artists](https://labs.api.listenbrainz.org/similar-artists?algorithm=session_based_days_7500_session_300_contribution_5_threshold_10_limit_100_filter_True_skip_30&artist_mbids=8f6bd1e4-fbe1-4f50-aa9b-94c450ec0f11) exposes the algorithm selected below. These are upstream documentation, not fixture evidence. The lane brief supplies the other live shapes. Design §10 terms/thumbnail-caching checks remain PROD conditions.

New `discover/` files separate models/settings, polite HTTP, disk cache, each source client, matching, shelves and read orchestration. New `wishlist/` files separate models, durable storage, state/mutation policy and scan fulfilment. Existing `Main.kt`, scanner and schema supply settings, tag capture and one post-scan seam. New `v1/Discover.kt`, `v1/Wishlist.kt`, `api/DiscoverApi.kt` and `api/WishlistApi.kt` are adapters; existing capability/scope/sync/event/MCP files extend their plumbing. Exact paths and responsibilities appear in each Files block. Do not restructure unrelated server code.

Use these shell helpers for task commands. Acquire/release build capacity around **every** Gradle invocation according to the lane brief. Tests must target the named fixture upstream. Red runs must fail on the named missing behavior/symbol, not an unrelated build error. Green runs require positive counts and zero failures/errors/skips in `server/build/test-results/test/TEST-*.xml`.

```sh
server_test() {
  ./gradlew :server:test --rerun --tests "com.legitimateapps.dulcet.server.$1"
}
commit_lane() {
  message=$1; shift
  git add -- "$@"
  git -c user.name='legitimate-apps' \
    -c user.email='309192374+legitimate-apps@users.noreply.github.com' \
    commit -m "$message" \
    -m 'Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01JyU1hzJfLh11sBj9oZtxTM'
}
```

Each task's commit step means `commit_lane "<specified message>" <exact Files paths>`, staging only that task. Include listed modifications from dependencies explicitly, rather than staging the worktree wholesale.

## Builder decisions and shared contracts

These fill design §9 without reopening §11.

**Settings and traffic.** `discover=on`, `wishlist=on`, `musicbrainz-url=https://musicbrainz.org`, `cover-art-archive-url=https://coverartarchive.org`, `listenbrainz-url=https://api.listenbrainz.org`, `listenbrainz-labs-url=https://labs.api.listenbrainz.org`, `listenbrainz-token` absent, `musicbrainz-min-gap-ms=1000`. Lower gaps only for non-public mirrors. Other origins default to 1,000 ms too. Token is reserved and unused in v1; never forward it on redirects or log it. CLI names map to `DULCET_...` using existing Config rules. Bases accept path prefixes; reject userinfo/query/fragment and non-HTTP(S). Defaults are HTTPS; loopback fixtures use HTTP. Shared origins share one gate even across clients.

**Cache policy.** Search/playlist search 1 h; trending/fresh releases 6 h; similar artists 7 days; metadata/discography/editions/playlist pages 24 h; definitive 404 or missing artwork 24 h; thumbnail bytes 30 days. Valid empty lists cache; failures/malformed responses do not. Stale last-good metadata survives up to 90 days. Disk cache budget 256 MiB with LRU pruning after writes. JSON cap 8 MiB, image cap 5 MiB; connect/request timeouts 5/10 s. No proactive polling or whole-library metadata sweep. Cache raw public data by source base, path, canonical query and parser version `1`, never personalized matches or wish state. Return `fetchedAt`, nonnegative `ageSeconds`, `stale`; fallback preserves original fetched time/generation. No cache plus outage is temporary unavailable, not empty.

**Shared domain.** Kinds `artist|album|song`; song library refs map to API catalog `track`. Canonical target is artist, release-group or recording MBID respectively. Release ids are auxiliary. Discover ids and artwork ids are persistently server-minted, not parsed MBIDs. Item fields: `id`, `kind`, `title`, optional `artist/year/releaseDate/artworkId`, `externalIds`, `links:[{relation,url}]`, `wishable`, `availability`. Preserve date precision. Only explicit web links use upstream HTTP(S); omit userinfo and non-web schemes. Do not expose upstream image URLs. JSPF rows with no recording MBID stay visible as `wishable=false`, without per-track search fanout.

**Availability.** `status=held|partlyHeld|notHeld`, `libraryItems:[{id,type}]`, optional `matchedBy=mbid|name`, `matchedTrackCount`, `totalTrackCount`, `editionReleaseId`; `completeness=known|unknown`. Hidden items never contribute. Visible album tracks with unknown edition metadata give `partlyHeld`, unknown total omitted, no fulfilment. Artist availability may be held for visible songs, but artist fulfilment still requires an album. No edition hydration for every search result: search uses local/tagged/cached knowledge; explicit album reads and candidate wishes hydrate lazily.

**Paging and shelves.** List pages: `items`, nullable `nextCursor`, `fetchedAt`, `ageSeconds`, `stale`; default limit 20, range `1..100`. Signed opaque cursors bind caller/query/order/source cache generation; missing or evicted generation is `410 cursor-expired`, never mixed pages. No public offsets. Search takes one kind per request; clients request artist, album, song in that order. Shelf ids: `trendingArtists`, `trendingAlbums`, `trendingSongs`, `newReleases`, `similarArtists`, `publicPlaylists`. Recent wishes use wishlist list. Exact copy: “Trending on ListenBrainz”, “Data from MusicBrainz and ListenBrainz”.

**Wish representation.** `id,userId,kind,externalIds,snapshot:{title,artist?,year?,artworkId?},source:{type,id?},addedAt,state,updatedAt,note,noteAt,fulfilledAt,libraryItem,availability,revision`. Note/fulfilment/link fields are nullable. Source types: `search,shelf,artist,album,playlist,api`. Snapshot title `1..500`, artist/source id at most 500, note at most 1,000 Unicode code points. Canonical UUID MBIDs are case normalized. Unknown input fields ignored. No arbitrary caller artwork URL. API can add with canonical MBID plus caller snapshot without lookup; no MBID-less wishes. Name matching covers untagged *local files*.

**Durable offline mutation.** Every add has `operationId` UUID reused on retry, plus API `Idempotency-Key` when provided. Remove has its own UUID and either wish id or unresolved `addOperationId`. Receipts and canonical-target action clocks outlive wish deletion and ordinary 24 h API retry caching, retained until user deletion. Reusing operation id with different input is conflict. Retrying a removed add returns `{applied:false,wish:null,removed:true}`. An early remove by addOperationId cancels the not-yet-received add. Concurrent adds dedupe; remove by old wish id never deletes a new generation. New intentional add after deletion gets a new id and event. API `Dulcet-Action-Time` / REST `actionTime` preserves offline `addedAt`; receipt/update and fulfilled age use server wall clock. Distinct conflicting target operations use latest action wins, removal wins equal timestamps; stale operations return `applied=false`. Receipt order breaks add ties without changing original added time. Intentional re-add must be newer.

**Wrong match.** Reject canonical target plus library pair; retain `(user,kind,canonical MBID,library type,id)` independent of wish lifetime. Album rejection blocks that album pair, not every song in it for unrelated wishes. Reopen only linked fulfilled wish, clear fulfilment and old integration note, retain existing My Library membership. Another user's pair remains eligible.

**Independent disable.** Discover off: no outgoing traffic, independent wishlist read/remove/reject and API snapshot adds work; REST app add answers 50. Fulfilment uses local/cached metadata, unknown completeness stays open. Wishlist off: retain rows/jobs/receipts, hide wish resource routes/capability, suspend fulfilment until re-enabled. Wrong-match correction remains available while either capability is enabled, without creating/reopening wishes when Wishlist is off. Disabled API operation 404, REST 70 (except REST add with Discover alone off: 50). Sync can still deliver existing tombstones after capability withdrawal. API upstream miss: 503 `upstream-unavailable`, Retry-After if known. REST: HTTP 200 error 0, `reason=upstream-unavailable`, optional `retryAfterSeconds`. Required-field error 10; invalid inputs error 0; unknown/other-user data error 70. API uses existing RFC 9457 conventions.

### Task 1: Settings, models and reusable fixture upstream

**Files:**
- Create: `server/src/main/kotlin/com/legitimateapps/dulcet/server/discover/Models.kt`
- Create: `server/src/main/kotlin/com/legitimateapps/dulcet/server/discover/DiscoverSettings.kt`
- Modify: `server/src/main/kotlin/com/legitimateapps/dulcet/server/Main.kt` (Config)
- Modify: `server/src/test/kotlin/com/legitimateapps/dulcet/server/Fixtures.kt`
- Create: `server/src/test/kotlin/com/legitimateapps/dulcet/server/FixtureDiscoverUpstream.kt`
- Test: `server/src/test/kotlin/com/legitimateapps/dulcet/server/DiscoverConfigTest.kt`
- Create: `server/src/test/resources/discover/README.md`

**Interfaces:** Produce `DiscoverSettings(enabled:Boolean=true,wishlistEnabled:Boolean=true,musicBrainzUrl:String,coverArtArchiveUrl:String,listenBrainzUrl:String,listenBrainzLabsUrl:String,listenBrainzToken:String?,musicBrainzMinGapMillis:Long=1000)` with defaults above and `Config.discover:DiscoverSettings`. Models: `DiscoverKind`, `ExternalIds`, `DiscoverItem`, `Availability`, `LibraryRef`, `ExternalLink` following shared fields; `CacheAge(fetchedAt:Long,ageSeconds:Long,stale:Boolean)`, `DiscoverPage<T>(items:List<T>,nextCursor:String?,age:CacheAge)`, `PageRequest(limit:Int=20,cursor:String?=null)`, `UpstreamPage<T>(items:List<T>,total:Int,offset:Int)`. `ExternalIds` is a string-to-string map using existing API registry keys; `LibraryRef(id:String,type:String)`, `ExternalLink(relation:String,url:String)`. Fixture `FixtureDiscoverUpstream:AutoCloseable` exposes `baseUrl:String`, `requests:List<RecordedRequest>`, `respond(path:String,query:Map<String,String>,status:Int,body:ByteArray,headers:Map<String,String>=emptyMap()):Unit` and latch holds. RecordedRequest retains headers, query, path, start nanos and active count.

- [x] **Step 1: Write failing tests.** `DiscoverConfigTest.defaultsOverridesAndIndependentSwitches`: `assertEquals(1000L,settings.musicBrainzMinGapMillis)`, both switches true, four exact defaults, all four overridden bases and each switch off alone. `invalidBaseAndPublicRateBypassFailStartup`: invalid URI parts and public gap `999` throw. `ordinaryFixtureStartsAndScansWithoutOutsideCalls`: `assertFalse(ctx.config.discover.enabled)` and zero requests.
Assertion anchors (Kotlin; arrange the named scenarios above):

```kotlin
assertEquals(1000L, settings.musicBrainzMinGapMillis)
assertTrue(settings.enabled && settings.wishlistEnabled)
assertFalse(ctx.config.discover.enabled)
```

- [x] **Step 2: Verify red.** Run `server_test DiscoverConfigTest`; expect missing setting/model behavior.
- [x] **Step 3: Implement.** Add Config parsing, domain shapes and loopback HttpServer fixture supporting base prefixes, scripted headers/statuses and generated `Fixtures.jpeg` images. Shared Fixtures disables Discover; domain construction never eagerly fetches, so existing direct Config test constructors remain network-safe. Record CC0 JSON manually only when Tasks 4–5 need it, using `USER_AGENT`, at most one request/second. README names date, endpoint/query, CC0 provenance and transformations. No live-capture automated test or recorded cover.
- [x] **Step 4: Verify green.** Run `server_test DiscoverConfigTest`; verify assertions, loopback bind and cleanup.
- [x] **Step 5: Commit.** Message `feat(server): define discovery settings and fixture upstream`.

### Task 2: Polite per-origin transport

**Files:**
- Create: `server/src/main/kotlin/com/legitimateapps/dulcet/server/discover/PoliteHttp.kt`
- Test: `server/src/test/kotlin/com/legitimateapps/dulcet/server/PoliteHttpTest.kt`

**Interfaces:** Consume settings/fixture. Produce `PoliteHttp(clock:DiscoverClock,userAgent:String=USER_AGENT):AutoCloseable`, `suspend get(baseUrl:String,path:String,query:Map<String,String>,maxBytes:Int=8388608):UpstreamResponse`; `UpstreamResponse(status:Int,headers:Map<String,List<String>>,body:ByteArray)`, `UpstreamUnavailable(retryAfterMillis:Long?)`. Clock: `wallMillis():Long`, `monotonicNanos():Long`, `suspend sleepMillis(millis:Long):Unit` with production/virtual implementations.

- [x] **Step 1: Write failing tests.** `serializesAllClientsOnSameOrigin`: `assertEquals(1,maxActive)` and start gaps `>=1_000_000_000L`. `retryAfterDeltaDateAnd503BackOffWholeHost`: 120 s delta/date, 60 s fallback, 429/503. `remainingZeroHonorsResetInAfter200`: header `X-RateLimit-Reset-In=120` blocks 120 s. `wallClockJumpCannotBypassGap`; `cancelledQueueEntryNeverSendsAndOtherOriginProgresses` pin monotonic time/cancellation. Each is in `PoliteHttpTest`.
Assertion anchors (Kotlin; arrange the named scenarios above):

```kotlin
assertEquals(1, maxActive)
assertTrue(startGapsNanos.all { it >= 1_000_000_000L })
assertEquals(120_000L, unavailable.retryAfterMillis)
```

- [x] **Step 2: Verify red.** Run `server_test PoliteHttpTest`.
- [x] **Step 3: Implement.** Shared origin gate, bounded 64 waiting requests (excess unavailable), no automatic retry loop, 5/10 s timeouts and byte bounds. Backoff returns unavailable immediately rather than sleeping through 120 s; queued callers recheck before sending. Use longest retry/reset deadline, parse delta/date Retry-After and LB headers. No SQLite lock while waiting. Fixed client-generated paths only; redirects disabled. Only User-Agent/Accept are sent, never caller headers, cookies or reserved token.
- [x] **Step 4: Verify green.** Run `server_test PoliteHttpTest`; additionally assert oversized/truncated body failure, cancelled capacity release and absent auth/user/device canaries.
- [x] **Step 5: Commit.** Message `feat(server): serialize and back off discovery upstream requests`.

### Task 3: Persistent cache and stale fallback

**Files:**
- Create: `server/src/main/kotlin/com/legitimateapps/dulcet/server/discover/DiscoverCache.kt`
- Test: `server/src/test/kotlin/com/legitimateapps/dulcet/server/DiscoverCacheTest.kt`

**Interfaces:** Produce `DiscoverCache(db:Db,clock:DiscoverClock)` with `suspend fun <T> read(key:CacheKey,ttlMillis:Long,decode:(ByteArray)->T,fetch:suspend ()->UpstreamResponse):Cached<T>` and `prune():Unit`; `Cached<T>(value:T,age:CacheAge,generation:String)`, `CacheKey(baseUrl:String,path:String,query:Map<String,String>,parserVersion:Int=1)`. Definitive not-found is a typed cache outcome, distinct from unavailable.

- [x] **Step 1: Write failing tests.** `freshHitAndTwentyConcurrentMissesSendOnce`: `assertEquals(1,requests.size)`. `restartRetainsAgeAndBadRefreshNeverOverwritesGood`: real DB reopen, malformed 200/503, original fetch/generation, `stale=true`. `noCacheFailureIsNotEmptyAnd404ExpiresAfter24Hours`: `86_400_000L` negative TTL. `baseAndParserVersionSeparateEntries`; `prunesByBytesAndNinetyDayAge`: 256 MiB and 90-day policy, smaller injected budget for eviction mechanics.
Assertion anchors (Kotlin; arrange the named scenarios above):

```kotlin
assertEquals(1, fixture.requests.size)
assertEquals(original.age.fetchedAt, fallback.age.fetchedAt)
assertTrue(fallback.age.stale)
assertEquals(original.generation, fallback.generation)
```

- [x] **Step 2: Verify red.** Run `server_test DiscoverCacheTest`.
- [x] **Step 3: Implement.** SQLite public cache includes status/body/fetched/accessed/generation/bytes; validate decoding before replacement. Single-flight identical keys, preserve stale last-good on temporary failure, enforce shared TTL/retention rules. No fetch in transaction; corrupt cached body becomes a miss. LRU pruning after writes; no standing polling.
- [x] **Step 4: Verify green.** Run `server_test DiscoverCacheTest`; assert valid empty array caches, failure does not, success changes generation and no user identifier is stored.
- [x] **Step 5: Commit.** Message `feat(server): persist discovery answers with stale fallback`.

### Task 4: MusicBrainz metadata and full edition lists

**Files:**
- Create: `server/src/main/kotlin/com/legitimateapps/dulcet/server/discover/MusicBrainzClient.kt`
- Modify: `server/src/main/kotlin/com/legitimateapps/dulcet/server/discover/Models.kt`
- Test: `server/src/test/kotlin/com/legitimateapps/dulcet/server/MusicBrainzClientTest.kt`
- Create: `server/src/test/resources/discover/musicbrainz/artist-search.json`
- Create: `server/src/test/resources/discover/musicbrainz/release-group-search.json`
- Create: `server/src/test/resources/discover/musicbrainz/recording-search.json`
- Create: `server/src/test/resources/discover/musicbrainz/artist.json`
- Create: `server/src/test/resources/discover/musicbrainz/discography-page-1.json`
- Create: `server/src/test/resources/discover/musicbrainz/discography-page-2.json`
- Create: `server/src/test/resources/discover/musicbrainz/release-group.json`
- Create: `server/src/test/resources/discover/musicbrainz/editions-page-1.json`
- Create: `server/src/test/resources/discover/musicbrainz/editions-page-2.json`
- Create: `server/src/test/resources/discover/musicbrainz/release.json`

**Interfaces:** `MusicBrainzClient(http:PoliteHttp,cache:DiscoverCache,settings:DiscoverSettings)` produces suspending `search(kind:DiscoverKind,query:String,offset:Int,limit:Int):Cached<UpstreamPage<DiscoverItem>>`, `artist(mbid:String):Cached<DiscoverItem>`, `discography(artistMbid:String):Cached<List<DiscoverItem>>`, `album(groupMbid:String):Cached<DiscoverItem>`, `editions(groupMbid:String):Cached<List<Edition>>`, `release(releaseMbid:String):Cached<Edition>`. `Edition(releaseMbid:String,releaseGroupMbid:String,tracks:List<EditionTrack>,links:List<ExternalLink>)`; `EditionTrack(disc:Int,position:Int,recordingMbid:String?,title:String,artist:String,durationMillis:Long?)`.

- [x] **Step 1: Write failing tests.** `oneKindSearchUsesCorrectEntityAndEscapesQuery`: decoded `Björk & AC/DC`, artist/release-group/recording paths, `fmt=json`, one call each. `discographyPagesPastTwentyFiveNewestFirst`: all 26, unknown dates last, stable ties. `editionPagingUsesActualRowsAndEveryDisc`: short 2-row page at limit 100 advances offset 2; >25 editions and duplicate slots preserved. `prematureEmptyBrowseAndMalformedSuccessAreUnavailable`: never complete a truncated list.
Assertion anchors (Kotlin; arrange the named scenarios above):

```kotlin
assertEquals(26, discography.value.size)
assertEquals("2", nextEditionRequest.query["offset"])
assertEquals(listOf(1, 2), edition.tracks.map { it.disc }.distinct())
```

- [x] **Step 2: Verify red.** Run `server_test MusicBrainzClientTest`.
- [x] **Step 3: Implement.** Use search paths from brief; artist lookup `inc=url-rels`; discography `/ws/2/release-group?artist=...&inc=artist-credits&limit=100&offset=...`; group lookup `inc=artist-credits+url-rels`; edition browse `/ws/2/release?release-group=...&inc=recordings+artist-credits+release-groups&limit=100&offset=...`; release lookup `inc=recordings+artist-credits+release-groups+url-rels`; all `fmt=json`. Browse until count reached, offsets advance actual row count, empty premature page fails. Cache each page; no N+1 per search result. Only HTTP(S) relation links survive. Keep recording slots, discs, titles and artist credits, not count alone.
- [x] **Step 4: Verify green.** Run `server_test MusicBrainzClientTest`; assert stale fallback and exact 1 h search/24 h metadata TTLs, valid empty versus malformed collections.
- [x] **Step 5: Commit.** Message `feat(server): read MusicBrainz discovery metadata and editions`.

### Task 5: ListenBrainz and personalized shelves

**Files:**
- Create: `server/src/main/kotlin/com/legitimateapps/dulcet/server/discover/ListenBrainzClient.kt`
- Create: `server/src/main/kotlin/com/legitimateapps/dulcet/server/discover/DiscoverShelves.kt`
- Modify: `server/src/main/kotlin/com/legitimateapps/dulcet/server/discover/Models.kt`
- Test: `server/src/test/kotlin/com/legitimateapps/dulcet/server/ListenBrainzClientTest.kt`
- Test: `server/src/test/kotlin/com/legitimateapps/dulcet/server/DiscoverShelvesTest.kt`
- Create: `server/src/test/resources/discover/listenbrainz/trending-artists.json`
- Create: `server/src/test/resources/discover/listenbrainz/trending-recordings.json`
- Create: `server/src/test/resources/discover/listenbrainz/trending-release-groups.json`
- Create: `server/src/test/resources/discover/listenbrainz/fresh-releases.json`
- Create: `server/src/test/resources/discover/listenbrainz/playlist-search.json`
- Create: `server/src/test/resources/discover/listenbrainz/playlist.json`
- Create: `server/src/test/resources/discover/labs/similar-artists.json`

**Interfaces:** Client suspending methods: `trending(kind:DiscoverKind):Cached<List<DiscoverItem>>`, `freshReleases(days:Int=7):Cached<List<DiscoverItem>>`, `similarArtists(seedMbid:String):Cached<List<DiscoverItem>>`, `searchPlaylists(query:String,count:Int):Cached<List<PlaylistSummary>>`, `playlist(mbid:String):Cached<DiscoverPlaylist>`. Define `PlaylistSummary(id:String,title:String,creator:String?,trackCount:Int?)`, `PlaylistEntry(position:Int,item:DiscoverItem)`, `DiscoverPlaylist(summary:PlaylistSummary,entries:List<PlaylistEntry>)`. `DiscoverShelves(db:Db,access:AccountAccess,client:ListenBrainzClient)` produces `suspend shelf(userId:String,shelfId:String):Cached<List<DiscoverItem>>` and `suspend playlistShelf():Cached<List<PlaylistSummary>>`.

- [x] **Step 1: Write failing tests.** `ListenBrainzClientTest.parsesObservedPayloadShapes` asserts `payload.artists/recordings/release_groups`, listen_count, fresh release group/release/CAA/artist/date/type fields, `playlists[].playlist`. `playlistPreservesDuplicatesAndUnknownRecording`: JSPF URI extraction/order and `wishable=false`. `similarUsesOneSeedAndNoIdentity`: one artist_mbids and no user/auth canaries. In `DiscoverShelvesTest`, write `usersShareSeedAnswersButNotHistoryOrOrder` (one shared seed request, different personal result), `newReleasesPreferMyLibraryOverBrowse`, `noSeedsMakesNoRequest` and `labelsAndTtlsAreExact` (exact credit/trending copy, 6 h/7 days/1 h).
Assertion anchors (Kotlin; arrange the named scenarios above):

```kotlin
assertEquals("7", freshRequest.query["days"])
assertEquals(1, similarRequest.query.getValue("artist_mbids").split(",").size)
assertFalse(unidentifiedEntry.item.wishable)
assertEquals(0, noSeedRequests.size)
```

- [x] **Step 2: Verify red.** Run `server_test ListenBrainzClientTest` and `server_test DiscoverShelvesTest`.
- [x] **Step 3: Implement.** Fixed brief endpoints; no LB Radio. Similar algorithm `session_based_days_7500_session_300_contribution_5_threshold_10_limit_100_filter_True_skip_30`, included in cache key. Fresh `days=7`, dedupe release groups; caller's My Library artists first (including member albums/songs), then date descending/id. Similar seeds: at most 5 visible MBID artists from user's plays descending, starred tie break then id; include starred zero-play seeds; no signal means empty without HTTP. Interleave seed neighbors, dedupe MBID, omit visibly held artists. Public playlist shelf uses empty query; search snapshots a bounded top-100 playlist result window, then pages locally with default20/max100. Arbitrary queries use separate snapshots; document this upstream result-window limit, never invent an offset unsupported by the brief. Individual playlist pages preserve the full returned track list. Sources receive seed ids, never user identity.
- [x] **Step 4: Verify green.** Run `server_test DiscoverShelvesTest` and `server_test ListenBrainzClientTest` green here.
- [x] **Step 5: Commit.** Message `feat(server): assemble ListenBrainz discovery shelves and playlists`.

### Task 6: Server-proxied artwork

Implementation note: `PinnedImageHttp.kt` and the shared scheduler are extended here so DNS-validated addresses are used directly at connect time, including each redirect. `DiscoverIdentity` is defined alongside the artwork client and consumed by Task 12.

**Files:**
- Create: `server/src/main/kotlin/com/legitimateapps/dulcet/server/discover/CoverArtClient.kt`
- Test: `server/src/test/kotlin/com/legitimateapps/dulcet/server/DiscoverArtworkTest.kt`

**Interfaces:** `CoverArtClient(http:PoliteHttp,cache:DiscoverCache,settings:DiscoverSettings)` produces `suspend thumbnail(groupMbid:String):DiscoverImage`, `DiscoverImage(bytes:ByteArray,contentType:String,age:CacheAge,placeholder:Boolean)`. Persist `discover_identity` mapping opaque item/artwork ids to kind/MBID. The service resolves artwork id; callers cannot supply URLs.

- [x] **Step 1: Write failing tests.** `redirectImageStaysOnServerAndCachesThirtyDays`: CAA `/release-group/{mbid}/front-250`, 307 fixture redirect, server-returned bytes, no device redirect and one repeat fetch. `redirectDoesNotForwardAuthAndRejectsDowngradeLoopOrOversize`: maximum 5 redirect hops, HTTPS downgrade refusal, 5 MiB cap, auth/header canaries absent. `missingArtGetsPlaceholderButOutageRetainsCachedBytes`: 404 differs from temporary error, malformed JSON never served as image. Fixture artwork generated, never recorded. `privateRedirectTargetCannotReachMetadataOrLoopback` refuses private/link-local/metadata redirect targets including the configured origin; only an explicit test-mode loopback override admits the fixture, and address validation holds at connect time.
Assertion anchors (Kotlin; arrange the named scenarios above):

```kotlin
assertContentEquals(first.bytes, cached.bytes)
assertEquals(first.contentType, cached.contentType)
assertEquals(1, thumbnailFetches)
assertTrue(missingImage.placeholder)
```

- [x] **Step 2: Verify red.** Run `server_test DiscoverArtworkTest`.
- [x] **Step 3: Implement.** Explicit bounded redirects through scheduler, no userinfo/credentials, fixed first path, HTTPS preserved. Pass through thumbnail bytes without decoding or resizing. Require a positive JPEG, PNG, GIF or WebP magic-byte signature and the 5 MiB cap; otherwise fail without serving the bytes. Cache the validated bytes and signature-derived content type. The front-250 endpoint already sizes thumbnails; no ImageIO parser or dimension rule applies. Generated placeholder only on definitive absence. Cached bytes survive outage; uncached temporary failure is unavailable. Discover off returns existing bytes/placeholder without network. No full URLs/query strings in errors/logs. Review amendment: apply the public-address policy to every artwork connection, including the configured origin and every DNS answer on every redirect. Pin the checked address. Private-network artwork mirrors are unsupported; only a startup-test-mode, admin-only `discoverArtworkAllowLoopback` override admits loopback fixtures. Compare origins by lowercase scheme/host and effective default port, so explicit `:443` and host casing cannot create exemptions or separate queues.
- [x] **Step 4: Verify green.** Run `server_test DiscoverArtworkTest`; assert TTL `2_592_000_000L`, missing TTL `86_400_000L`, bytes/content type, close and cancellation. Every redirect in tests stays on the fixture server. Assert address validation holds at connect time using the webhook address-policy precedent, with no new private-target setting.
- [x] **Step 5: Commit.** Message `feat(server): proxy and cache discovery cover thumbnails`.

### Task 7: Release-group tags without identity changes

**Files:**
- Modify: `server/src/main/kotlin/com/legitimateapps/dulcet/server/db/Schema.kt`
- Modify: `server/src/main/kotlin/com/legitimateapps/dulcet/server/scan/TrackInfo.kt`
- Modify: `server/src/main/kotlin/com/legitimateapps/dulcet/server/scan/Scanner.kt`
- Test: `server/src/test/kotlin/com/legitimateapps/dulcet/server/DiscoverScanTagsTest.kt`

**Interfaces:** Add `TrackInfo.mbzReleaseGroupId:String?=null` and `track.mbz_release_group_id TEXT NULL`. Read normalized `musicbrainzreleasegroupid` through existing probe normalization. Query album association from its visible tracks; do not alter album identity derivation.

- [x] **Step 1: Write failing tests.** `tagSurvivesRealProbeScanAndRestart`: actual file tag `MUSICBRAINZ_RELEASEGROUPID`, stored UUID, unchanged ids on retag. `migrationPreservesCatalogAndUserState`: previous-schema DB opens, null new column, existing stars/membership intact. `untaggedScanDoesNotSweepUpstream`: 50 untagged albums, no wishes, zero requests.
Assertion anchors (Kotlin; arrange the named scenarios above):

```kotlin
assertEquals(groupMbid, storedGroupMbid)
assertEquals(originalTrackId, retaggedTrackId)
assertEquals(0, fixture.requests.size)
```

- [x] **Step 2: Verify red.** Run `server_test DiscoverScanTagsTest`.
- [x] **Step 3: Implement.** Append additive migration and extend scanner explicit insert/bindings. Changed/new files gain tag; unchanged files do not force reprobe. Null legacy tags remain eligible for names and candidate-only release lookup in Task 11.
- [x] **Step 4: Verify green.** Run `server_test DiscoverScanTagsTest` and `server_test ScannerIdentityRegressionTest`; preserve id, user data and scan-clock behavior.
- [x] **Step 5: Commit.** Message `feat(server): retain release-group tags for discovery matching`.

### Task 8: Visible-library matching and complete editions

**Files:**
- Create: `server/src/main/kotlin/com/legitimateapps/dulcet/server/discover/DiscoverMatcher.kt`
- Test: `server/src/test/kotlin/com/legitimateapps/dulcet/server/DiscoverMatcherTest.kt`

**Interfaces:** `DiscoverMatcher(db:Db,access:AccountAccess)` produces `match(userId:String,item:DiscoverItem,editions:List<Edition>?=null):Availability` and `normalizeName(value:String):String`. No HTTP in matcher. Consume tags and later rejected pairs. Until Task 10 provides rejection storage, a default empty rejection lookup preserves this task's independence.

- [x] **Step 1: Write failing tests.** `recordingMbidWinsWithoutVersionConflictButConflictingMbidCannotFallBack`; `namesFoldUnicodeWhitespaceAndPunctuationPreservingVersionWords` covers composed/decomposed `Björk`, `AC/DC`, and live/remix/demo/acoustic/instrumental. Assert studio never matches marked variants, same marked version can match. `equalCountsWrongSongsAndDuplicateSlotsAreNotComplete`: 12 wrong/repeated tracks do not satisfy 12 slots. `eightOfTwelveIsPartialAndAnyEditionCanBeComplete`: assert status partlyHeld, counts 8/12, then alternative edition 10/10 gives held. `hiddenTracksAndOtherUsersNeverContribute`: no hidden ids/counts or shared-object mutation.
Assertion anchors (Kotlin; arrange the named scenarios above):

```kotlin
assertEquals("partlyHeld", partial.status)
assertEquals(8, partial.matchedTrackCount)
assertEquals(12, partial.totalTrackCount)
assertEquals("held", complete.status)
```

- [x] **Step 2: Verify red.** Run `server_test DiscoverMatcherTest`.
- [x] **Step 3: Implement.** NFKC, Locale.ROOT lowercase, punctuation to spaces, collapse whitespace; preserve letters/diacritics and version words. No fuzzy or substring matching. Review amendment: the recording-MBID branches for song matching and album slot assignment also require identical sets of normalized live/remix/demo/acoustic/instrumental words. A stale recording tag cannot override an asymmetric version marker. `recordingMbidNeverOverridesAnExplicitVersionMismatch` tests all five words in both directions; `staleRecordingMbidCannotFillAnAlbumEditionSlotWithAnotherVersion` prevents album fulfilment through the same bypass. Matching markers remain eligible. Artist/title compared separately; fallback only if relevant local MBID absent, not disagreeing. Album slot assignment is one-to-one within a single local album: recording id first, untagged artist/title second, disc/position disambiguates duplicates. A file fills at most one slot; do not pool different local editions. Extra tracks allow a complete shorter edition. Name album matching also requires normalized album artist/title plus slot proof. Ambiguous matches remain partial/unknown, no arbitrary held link.
- [x] **Step 4: Verify green.** Run `server_test DiscoverMatcherTest`; assert artist with songs may be held without fulfilment album, missing total omitted. Choose complete edition first, otherwise maximum matched fraction then release MBID. No count-only completeness.
- [x] **Step 5: Commit.** Message `feat(server): match discover items with visible library editions`.

### Task 9: Durable wish mutations and journal

**Files:**
- Create: `server/src/main/kotlin/com/legitimateapps/dulcet/server/wishlist/WishlistModels.kt`
- Create: `server/src/main/kotlin/com/legitimateapps/dulcet/server/wishlist/WishlistStore.kt`
- Create: `server/src/main/kotlin/com/legitimateapps/dulcet/server/wishlist/WishlistService.kt`
- Test: `server/src/test/kotlin/com/legitimateapps/dulcet/server/WishlistStoreTest.kt`

**Interfaces:** Produce `Wish` with shared representation fields, `WishSnapshot(title:String,artist:String?,year:Int?,artworkId:String?)`, `WishSource(type:String,id:String?)`, `AddWish(kind:DiscoverKind,externalIds:ExternalIds,snapshot:WishSnapshot,source:WishSource,operationId:String,actionTime:Long?)`, `RemoveWish(wishId:String?,addOperationId:String?,operationId:String,actionTime:Long?)`, `WishMutation(applied:Boolean,wish:Wish?,removed:Boolean)`. `WishlistService.add(userId:String,input:AddWish):WishMutation`, `remove(userId:String,input:RemoveWish):WishMutation`, `get(userId:String,wishId:String):Wish?`, `list(userId:String,filter:WishFilter):List<Wish>`; `WishFilter(kind:DiscoverKind?=null,state:String?=null,includeFulfilledHistory:Boolean=false)`. Service always enforces ownership; admin uses explicit adapter methods later.

- [x] **Step 1: Write failing tests.** `concurrentCanonicalAddsKeepOneIdAndOriginalTime`: 20 operations, normalized UUID cases, separate users/kinds. `removedAddRetryCannotResurrectAfterRestartAndRetryCacheExpiry`: advance >24 h, reopen real DB, old add stays removed; newer intentional add gets different id. `removeBeforeAddCancelsUnacknowledgedOperation`; `oldRemoveCannotDeleteNewGeneration`; `journalWishAndReceiptsRollbackTogether`: zero rows after injected transaction failure. `offlineClockRejectsOlderAddAndRemovalWinsTie`: exact shared ordering. `fulfilledDefaultListBoundaryIsThirtyDays`: one millisecond before boundary included, boundary excluded, explicit history/get still returns it.
Assertion anchors (Kotlin; arrange the named scenarios above):

```kotlin
assertEquals("wanted", added.wish!!.state)
assertEquals(first.wish!!.id, duplicate.wish!!.id)
assertFalse(oldAddRetry.applied)
assertTrue(oldAddRetry.removed)
assertNull(oldAddRetry.wish)
```

- [x] **Step 2: Verify red.** Run `server_test WishlistStoreTest`.
- [x] **Step 3: Implement.** Table `wishlist_wish`, unique `(user_id,kind,canonical_mbid)`, random server id and all snapshot/state/timestamp/link fields. Tables `wishlist_operation` (user/op UUID unique, fingerprint/result/cancellation target) and `wishlist_target_clock`. Retain receipts/clocks until user deletion. Row insert/update/delete and `wish` journal upsert/tombstone share `db.write`. Duplicate add changes neither original added time nor journal. Removal deletes live row, reason `deleted`; no catalog FK cascade. User deletion cascades. API snapshot add performs zero HTTP, independent of Discover. Validate bounded fields/UUIDs/unknown extra inputs as shared contract specifies.
- [x] **Step 4: Verify green.** Run `server_test WishlistStoreTest`; assert state wanted, offline addedAt vs server updatedAt, unchanged snapshot on duplicate, conflict on operation UUID reuse, `applied=false` on stale input.
- [x] **Step 5: Commit.** Message `feat(server): persist idempotent per-user wishlist mutations`.

### Task 10: State transitions and remembered wrong matches

**Files:**
- Modify: `server/src/main/kotlin/com/legitimateapps/dulcet/server/wishlist/WishlistService.kt`
- Modify: `server/src/main/kotlin/com/legitimateapps/dulcet/server/wishlist/WishlistStore.kt`
- Modify: `server/src/main/kotlin/com/legitimateapps/dulcet/server/wishlist/WishlistModels.kt`
- Modify: `server/src/main/kotlin/com/legitimateapps/dulcet/server/discover/DiscoverMatcher.kt`
- Test: `server/src/test/kotlin/com/legitimateapps/dulcet/server/WishlistStateTest.kt`

**Interfaces:** Produce `setState(actor:Principal,wishId:String,state:String,note:String?,expectedRevision:String?):Wish`, `rejectMatch(userId:String,target:ExternalTarget,libraryItem:LibraryRef,wishId:String?,operationId:String):WishMutation`; `ExternalTarget(kind:DiscoverKind,externalIds:ExternalIds)`. Live admin scope/account required for setState. Four open states allowed; setting fulfilled/unknown is 422 invalid-state; any admin update of fulfilled row is 409 wish-fulfilled. Optional revision mismatch is 412.

- [x] **Step 1: Write failing tests.** `allSixteenOpenTransitionsPermitNotes`: Cartesian product wanted/requested/searching/unavailable, assert state/note/noteAt/journal. `adminCannotSetOrLeaveFulfilled`; `rejectionReopensOnlyLinkedPairForOneUserAfterRestart`: state wanted, cleared fulfilment/note, My Library unchanged, other user eligible, pair still blocked after remove/re-add. `discoverOnlyCanRejectPairWithoutCreatingWish` asserts correction works with Wishlist off and no wish row. `rejectionRacingRemovalNeverRecreatesWish`; `hiddenPairOrOtherUsersWishIsNotFound`; `noteBoundsAndNullClear`: 1,000 code points accepted, 1,001 refused, null clears noteAt too.
Assertion anchors (Kotlin; arrange the named scenarios above):

```kotlin
assertEquals("wanted", reopened.wish!!.state)
assertNull(reopened.wish!!.fulfilledAt)
assertNull(reopened.wish!!.note)
assertEquals(1, existingMembershipCount)
```

- [x] **Step 2: Verify red.** Run `server_test WishlistStateTest`.
- [x] **Step 3: Implement.** Independent `wishlist_rejected_match` rows with unique pair key, operation receipts and matcher filter. Validate supplied pair visibility and wish ownership; allow formerly linked pair rejection after its catalog item disappears, without revealing unrelated hidden ids. Clear old note/fulfilment only on matching linked fulfilled wish. Journal actual changes only. Do not remove My Library automatically. Internal server fulfil method comes in Task 11; no state-setter route can call it.
- [x] **Step 4: Verify green.** Run `server_test WishlistStateTest` and `server_test DiscoverMatcherTest`; assert catalog deletion never reopens fulfilled, removal allowed in every state and artist/album/song rejected pairs all persist.
- [x] **Step 5: Commit.** Message `feat(server): enforce wish transitions and remember rejected matches`.

### Task 11: Post-scan fulfilment into My Library

Implementation note: pending candidate ids coalesce into one SQLite row instead of accumulating per-scan rows. The scan UUID guards both worker cleanup and fulfilment. MusicBrainz and ListenBrainz fetch lambdas also enforce the Discover switch, allowing cache-only work while disabled. CLI drains the same persisted queue synchronously after the scanner returns.

**Files:**
- Create: `server/src/main/kotlin/com/legitimateapps/dulcet/server/wishlist/WishFulfilment.kt`
- Modify: `server/src/main/kotlin/com/legitimateapps/dulcet/server/wishlist/WishlistService.kt`
- Modify: `server/src/main/kotlin/com/legitimateapps/dulcet/server/scan/Scanner.kt`
- Modify: `server/src/main/kotlin/com/legitimateapps/dulcet/server/Main.kt`
- Test: `server/src/test/kotlin/com/legitimateapps/dulcet/server/WishFulfilmentTest.kt`

**Interfaces:** Add compatible default-no-op scanner callback `afterCatalogCommit:(ScanDelta)->Unit`; `ScanDelta(scanId:String,trackIds:Set<String>,albumIds:Set<String>,artistIds:Set<String>)` represents added/changed candidates. Persist pending delta in scan transaction, callback only after successful commit. `WishFulfilment(ctx:ServerContext)` exposes `enqueue(delta:ScanDelta):Unit`, `suspend drain():Unit` with application-owned bounded worker. Construct once in ServerContext: `discoverHttp:PoliteHttp`, `discoverCache:DiscoverCache`, `musicBrainz:MusicBrainzClient`, `listenBrainz:ListenBrainzClient`, `discoverArtwork:CoverArtClient`, `discoverMatcher:DiscoverMatcher`, `wishlist:WishlistService`, `wishFulfilment:WishFulfilment`. Application shutdown closes transport/worker; CLI closes them after drain. Internal `WishlistService.fulfil(userId:String,wishId:String,libraryItem:LibraryRef,expectedWishRevision:String,expectedScanStamp:String,availability:Availability):Boolean` atomically rechecks then applies.

- [x] **Step 1: Write failing tests.** `scanArrivesSongAlbumArtistAndTheirOwnMembership`: real tags/files/scanner, independent wish/library/journal and REST library changed reads; artist adds artist, album adds album, song adds song only. `eightOfTwelveStaysOpenUntilLastTracksScan`; `onlyOpenWishCandidateUsesCachedReleaseLookup`: zero unrelated requests. `upstreamFailureDoesNotFailScanOrSetUnavailable`: successful scan, open state retained, durable pending job. `removalRejectionAndSecondScanWinAgainstLatchedMetadata`; `rollbackChangesNeitherWishNorMembership`; `restartReplaysPendingWithoutDuplicateMembership`: assert exact once transition. `cliAndApiAndRestScanEntryPointsReachSameFulfilmentHook` uses real entry points and local fixture bases.
Assertion anchors (Kotlin; arrange the named scenarios above):

```kotlin
assertEquals("fulfilled", arrived.state)
assertEquals(expectedLibraryRef, arrived.libraryItem)
assertEquals(1, membershipCount)
assertEquals(0, unrelatedReleaseLookups)
```

- [x] **Step 2: Verify red.** Run `server_test WishFulfilmentTest`.
- [x] **Step 3: Implement.** All entry points (CLI/startup/periodic/REST/API startOrJoin) use same scanner seam. Refused scans enqueue nothing. Candidate jobs persist inside catalog commit; worker does HTTP outside DB/scan locks. Match open wishes against new/updated items plus existing tracks of candidate albums. Missing group tag uses release lookup only for an album candidate for an open wish, cached per release. Tagged candidates bypass that association lookup. Edition hydration lazy; outage/unknown completeness stays open and pending. Retry on next scan, explicit wishlist/discover read-triggered work or restart, no polling timer. Discover off permits local/cache-only evaluation; Wishlist off suspends jobs. Worker errors never make committed scan look refused. CLI `scan` drains immediately ready work outside the scan transaction before returning; rate-backed-off work remains persisted rather than holding the CLI open. Startup drains pending work before waiting for new deltas.
- [x] **Step 4: Implement atomic effect and verify green.** In one `db.write`, recheck open wish revision, live visible catalog pair, no rejection and unchanged scan stamp; insert membership if absent, advance `markLibraryChanged` and existing `api_library_receipt` only for actual new membership, set fulfilled/link/time, journal wish and item state, clear pending job. Existing member keeps original addedAt. Run `server_test WishFulfilmentTest`; assert 30-day clock is fulfilledAt, scan deletion remains final, no duplicate journal effect on job replay. Job shutdown/cancellation leaves durable work, resources close with application.
- [x] **Step 5: Commit.** Message `feat(server): fulfil wishes after scans into My Library`.

### Task 12: Dulcet API discovery and capability plumbing

Implementation note: `DiscoverJson.kt` owns shared JSON projections for API, REST and MCP. `Cached.dependencies` records raw cache-key generations for strict cursor eviction/refresh checks. List snapshots freeze order before caller matching and retain at most 32 per user for one hour, with a global 16 MiB snapshot bound included in the 256 MiB cache budget. MusicBrainz search fills 100-row chunks lazily, pins each participating answer and the first-page generation, and never treats its index as an upstream transactional snapshot. A refresh or eviction of a participating answer expires the continuation. Release dates are retained on Edition for deterministic default-edition selection.

**Files:**
- Create: `server/src/main/kotlin/com/legitimateapps/dulcet/server/discover/DiscoverService.kt`
- Create: `server/src/main/kotlin/com/legitimateapps/dulcet/server/v1/Discover.kt`
- Modify: `server/src/main/kotlin/com/legitimateapps/dulcet/server/discover/Models.kt`
- Modify: `server/src/main/kotlin/com/legitimateapps/dulcet/server/v1/Api.kt`
- Modify: `server/src/main/kotlin/com/legitimateapps/dulcet/server/Main.kt`
- Modify: `docs/dulcet-api/openapi.yaml`
- Test: `server/src/test/kotlin/com/legitimateapps/dulcet/server/ApiDiscoverTest.kt`

**Interfaces:** `DiscoverService` produces suspending `search(userId:String,kind:DiscoverKind,query:String,page:PageRequest):DiscoverPage<DiscoverItem>`, `shelf(userId:String,id:String,page:PageRequest):DiscoverPage<DiscoverItem>`, `artist(userId:String,mbid:String):DiscoverArtistPage`, `albums(userId:String,artistMbid:String,page:PageRequest):DiscoverPage<DiscoverItem>`, `album(userId:String,mbid:String,releaseId:String?=null):DiscoverAlbumPage`, `albumTracks(userId:String,groupMbid:String,releaseMbid:String,page:PageRequest):DiscoverPage<DiscoverItem>`, `playlists(userId:String,query:String,page:PageRequest):DiscoverPage<PlaylistSummary>`, `playlist(userId:String,mbid:String,page:PageRequest):DiscoverPlaylistPage`, `artwork(userId:String,id:String):DiscoverImage`. Models: `DiscoverArtistPage(item:DiscoverItem,albums:DiscoverPage<DiscoverItem>)`, `DiscoverAlbumPage(item:DiscoverItem,editions:List<Edition>,selectedReleaseId:String?,tracks:DiscoverPage<DiscoverItem>)`, `DiscoverPlaylistPage(summary:PlaylistSummary,entries:DiscoverPage<PlaylistEntry>)`. `ApiDiscover.install(route:Route):Unit` installs adapters. Public-playlist shelf uses `playlists(userId,"",page)`, not a music-item type cast.

All GET routes below require `catalog:read`, use bearer headers, private ETag/Vary and sync stamp:

| Path under `/dulcet/v1` | operationId | Inputs |
|---|---|---|
| `/discover/search` | `searchDiscover` | kind, q length 1..500, limit/cursor |
| `/discover/shelves/{shelfId}` | `getDiscoverShelf` | music ids or publicPlaylists (playlist-summary page), limit/cursor |
| `/discover/artists/{mbid}` | `getDiscoverArtist` | summary, relations and first album page |
| `/discover/artists/{mbid}/albums` | `listDiscoverArtistAlbums` | limit/cursor |
| `/discover/albums/{mbid}` | `getDiscoverAlbum` | group MBID, optional releaseId |
| `/discover/albums/{mbid}/tracks` | `listDiscoverAlbumTracks` | group MBID, required releaseId, limit/cursor |
| `/discover/playlists` | `searchDiscoverPlaylists` | q may be empty, limit/cursor |
| `/discover/playlists/{mbid}` | `getDiscoverPlaylist` | entry page, limit/cursor |
| `/discover/artwork/{artworkId}` | `getDiscoverArtwork` | image bytes, no external redirect |

- [x] **Step 1: Write failing tests.** `capabilitySwitchesAndEveryScope`: `[1]`, independent switches, unauthenticated401, wrong scope403, disabled404. `allPathsProjectContractAndServerArtwork`: exact fields, externalIds, opaque ids, dates, slots and server art refs. `sharedCacheDoesNotLeakPrivateMatchesOrEtags`: two users, hidden source and rejection; private/Vary header, correct 304. `cursorGenerationAndQueryMismatchNeverMixPages`: generation410, caller/query/kind mismatch400. `emptySuccessDiffersFromUncachedOutage`: empty valid200 versus503, retry header; invalid limits0/101 give400, default20/max100.
Assertion anchors (Kotlin; arrange the named scenarios above):

```kotlin
assertEquals(503, outageResponse.status.value)
assertEquals(403, wrongScopeResponse.status.value)
assertEquals(410, expiredGenerationResponse.status.value)
assertEquals("private", successfulResponse.headers["Cache-Control"])
```

- [x] **Step 2: Verify red.** Run `server_test ApiDiscoverTest`.
- [x] **Step 3: Implement.** Service hydrates source metadata and applies matcher per user after cache. Persist identity mappings; paginate immutable cache generation before projection. Personal shelf ordering is a user-bound cached generation of ids, never public upstream cache content. Artist discography newest first; album default edition is earliest dated edition, stable release-id tie, with selectable alternative editions. A no-edition group has empty track page, unknown completeness; never invent a complete album. Use existing send/stamp/auth helpers. Extend OpenAPI capability examples and schemas `DiscoverItem`, `DiscoverAvailability`, `DiscoverCacheAge`, `DiscoverItemPage`, `DiscoverArtist`, `DiscoverAlbum`, `DiscoverPlaylist`, `DiscoverPlaylistPage`, `DiscoverPlaylistSummaryPage`, `DiscoverLink` and documented problems/headers. Keep required/nullable fields explicit, output enums open. Construction order preserves event/outbox observers.
- [x] **Step 4: Verify green.** Run `server_test ApiDiscoverTest`; assert no per-result network fanout, safe links and server-only images; explicit album page fills cached editions, repeat search can then upgrade availability without another lookup. API schema/examples match actual results.
- [x] **Step 5: Commit.** Message `feat(api): expose optional Discover resources and capabilities`.

### Task 13: Wishlist API, scope enforcement and change feed

**Files:**
- Create: `server/src/main/kotlin/com/legitimateapps/dulcet/server/v1/Wishlist.kt`
- Modify: `server/src/main/kotlin/com/legitimateapps/dulcet/server/v1/Api.kt`
- Modify: `server/src/main/kotlin/com/legitimateapps/dulcet/server/v1/Sync.kt`
- Modify: `server/src/main/kotlin/com/legitimateapps/dulcet/server/v1/Journal.kt`
- Modify: `server/src/main/kotlin/com/legitimateapps/dulcet/server/v1/ApiEvents.kt`
- Modify: `server/src/main/kotlin/com/legitimateapps/dulcet/server/Main.kt`
- Modify: `docs/dulcet-api/openapi.yaml`
- Test: `server/src/test/kotlin/com/legitimateapps/dulcet/server/ApiWishlistTest.kt`
- Test: `server/src/test/kotlin/com/legitimateapps/dulcet/server/WishlistSyncTest.kt`

**Interfaces:** `ApiWishlist.install(route:Route):Unit`, `resource(userId:String,id:String):JsonObject?`, `page(actor:Principal,allUsers:Boolean,params:Map<String,String>):JsonObject`. Lists `addedAt DESC,id`, limit20/max100, cursor-bound kind/state/includeFulfilledHistory; explicit `state=fulfilled` includes history. Direct get and feed snapshots include all live history; default-list aging is not deletion. Feed type `wish`, revision user-scoped. Admin page explicitly checks admin and may filter userId.

| Method/path under `/dulcet/v1` | operationId | Scope and result |
|---|---|---|
| GET `/me/wishes` | `listWishes` | user:read, own page |
| POST `/me/wishes` | `addWish` | user:write, AddWish, 201 new /200 duplicate/cancelled, WishMutation |
| GET `/me/wishes/{wishId}` | `getWish` | user:read, own;404 other/unknown,410 own removed |
| DELETE `/me/wishes/{wishId}` | `removeWish` | user:delete, operationId query, action-time header,204 |
| POST `/me/wishes/cancellations` | `cancelWishAdd` | user:delete, addOperationId/operationId,200 WishMutation |
| POST `/me/wishes/{wishId}/reject-match` | `rejectWishMatch` | user:write, target/pair/operationId,200 WishMutation |
| POST `/discover/rejected-matches` | `rejectDiscoverMatch` | user:write, target/pair/operationId,200 WishMutation; gated by Discover OR Wishlist, works with either independently enabled |
| GET `/admin/wishes` | `listAllWishes` | admin, optional userId and list filters |
| PATCH `/admin/wishes/{wishId}` | `setWishState` | admin, state/note, optional If-Match412 |

- [x] **Step 1: Write failing tests.** `ApiWishlistTest.everyOperationChecksLiveScopeAndOwnership`: two ordinary users, admin scoped down, every path, explicit admin access only, no setting fulfilled. `offlineSnapshotAddAndRemovalSurviveRetryCacheExpiry`: HTTP replay/cancellation and conflicting idempotency keys, zero upstream calls. `thirtyDayHistoryDoesNotDeleteResource`; `cachedPostResponseCannotReintroduceRemovedWish` asserts durable removal overrides an old cached add reply. `WishlistSyncTest.snapshotChangesDeletionAndIsolation`: own-only wish type, removal tombstone reason deleted/no resource, all fulfilled history retained in snapshot; expired/type-mismatched cursors retain existing errors. `disabledWishlistStillDeliversPreviouslyRecordedTombstones` prevents capability withdrawal stranding old rows; `preWishlistCursorWithoutTypesStillContinuesOriginalFilter` pins compatibility for old cursors.
Assertion anchors (Kotlin; arrange the named scenarios above):

```kotlin
assertEquals("wish", deletedChange["type"]!!.jsonPrimitive.content)
assertEquals("delete", deletedChange["op"]!!.jsonPrimitive.content)
assertEquals("deleted", deletedChange["reason"]!!.jsonPrimitive.content)
assertFalse(deletedChange.containsKey("resource"))
```

- [x] **Step 2: Verify red.** Run `server_test ApiWishlistTest` and `server_test WishlistSyncTest`.
- [x] **Step 3: Implement.** Extend syncTypes, authorized types, snapshot ids/resource/revision and events userTypes (`changes` hints only). Add `Journal.boundTypes(userId:String,cursor:String):String`, verifying MAC, timeline and ownership before returning its filter. When types is omitted on continuation, use the verified cursor filter (including existing snapshot codec filter), not the new default; still check current scopes. Explicitly choosing added `wish` type requires a new cursor flow. OpenAPI adds `Wish`, `WishSnapshot`, `WishSource`, `WishPage`, `WishAdd`, `WishRemove`, `WishMutation`, `WishStateUpdate`, `WishRejectMatch`, examples/headers/problems/scopes. Add pre-replay idempotency middleware scope mapping for add/cancel/reject, including delete-scope cancellation. Before replaying a cached successful wish add, consult its durable receipt and current generation so a removed add returns the terminal removed result instead of a stale live representation. Retain the global Idempotency-Key fingerprint/conflict namespace. No upstream work inside POST transaction. Conditional reads and If-Match use existing helpers. Repeated own-id DELETE204, another user's id404; durable own tombstone lookup does not reveal someone else's deletion.
- [x] **Step 4: Verify green.** Run both test classes plus `server_test ApiSyncVisibilitySecurityTest`; assert journal atomicity, revision, Dulcet-Sync-Cursor, bearer-only auth, bounded snapshots/notes, latest-action semantics and no source ids leaked via fulfilled refs after policy restriction (redact links/counts, retain historical fulfilled state).
- [x] **Step 5: Commit.** Message `feat(api): expose wishes in scoped resources and change feeds`.

### Task 14: Signed wishlist webhooks on the durable outbox

**Files:**
- Modify: `server/src/main/kotlin/com/legitimateapps/dulcet/server/v1/ApiWebhooks.kt`
- Modify: `server/src/main/kotlin/com/legitimateapps/dulcet/server/wishlist/WishlistService.kt`
- Modify: `docs/dulcet-api/openapi.yaml`
- Test: `server/src/test/kotlin/com/legitimateapps/dulcet/server/WishlistWebhooksTest.kt`

**Interfaces:** Retain `ApiWebhooks.enqueue(type:String,userId:String?,data:JsonObject,eventKey:String?=null)`. Map `wishlist.changed` to user:read, `wishlist.requested` to admin. Changed data `{op,id,revision,wish?}`; delete omits wish. Requested data `{wish}` with true owner's userId inside Wish. Publish ApiEvents entries in wish transaction. Requested audience has event.userId=null (all current admin subscribers); changed audience is wish owner. Existing outer webhook userId remains subscription owner; no change to prior semantics. No new SSE event type needed.

- [x] **Step 1: Write failing tests.** `requestedCrossesOwnersOnlyToAdminAndChangedStaysOwn`; `dedupeStateReopenAndFulfilNeverRequestAgain`: creation once, re-add new id/event. `rollbackAndRestartNeitherLoseNorInventDelivery`: rollback0, commit outbox durable. `standardWebhooksSignExactWishBytes`: independently compute HMAC(id.timestamp.body), compare signature. `scopeLossAndRevocationBeforeAttemptDropRequested`: no delivery after authority loss.
Assertion anchors (Kotlin; arrange the named scenarios above):

```kotlin
assertEquals("wishlist.requested", requestedBody["type"]!!.jsonPrimitive.content)
assertEquals(expectedHmac, request.headers["webhook-signature"])
assertContentEquals(firstAttempt.body, retriedAttempt.body)
assertEquals(0, unauthorizedDeliveries.size)
```

- [x] **Step 2: Verify red.** Run `server_test WishlistWebhooksTest`.
- [x] **Step 3: Implement.** Service emits one changed for actual mutation, requested only actual insertion; stable event keys and same-transaction staging through existing beforeCommit observer. Keep 24 h retries, encrypted secrets, SSRF rules, cancellation and grant revocation. Require current admin scope/account at subscription and delivery, including `*`; other user's requested never reaches ordinary wildcard subscription. Define `WishlistChangedEvent`/`WishlistRequestedEvent` payload schemas and update allowed-event docs/examples. Fulfil membership/state/event is one commit, not after-response publication.
- [x] **Step 4: Verify green.** Run `server_test WishlistWebhooksTest` and `server_test ApiWebhooksTest`; 500 retry preserves id/body, changed deletion tombstone delivered, scope loss/revocation drops pending data, failure injection leaks no uncommitted event.
- [x] **Step 5: Commit.** Message `feat(server): deliver scoped wishlist webhooks with durable signing`.

### Task 15: REST extensions and complete client contract documents

Implementation note: the shared wish page cursor in `v1/Wishlist.kt` also binds the list revision,
so a concurrent mutation expires both API and REST continuations. REST app adds require Discover
but accept the displayed item snapshot without an upstream call in the mutation transaction,
as required by design §6.2. The actual settings use `DULCET_DISCOVER=on|off` and
`DULCET_WISHLIST=on|off`, following LRCLIB's existing switch syntax.

**Files:**
- Create: `server/src/main/kotlin/com/legitimateapps/dulcet/server/api/DiscoverApi.kt`
- Create: `server/src/main/kotlin/com/legitimateapps/dulcet/server/api/WishlistApi.kt`
- Modify: `server/src/main/kotlin/com/legitimateapps/dulcet/server/api/Rest.kt`
- Modify: `server/src/main/kotlin/com/legitimateapps/dulcet/server/wishlist/WishlistService.kt`
- Modify: `server/src/main/kotlin/com/legitimateapps/dulcet/server/wishlist/WishlistStore.kt`
- Modify: `server/src/main/kotlin/com/legitimateapps/dulcet/server/Main.kt`
- Create: `docs/dulcet-server/dulcet-discover-extension.md`
- Create: `docs/dulcet-server/dulcet-wishlist-extension.md`
- Modify: `docs/dulcet-server/README.md`
- Test: `server/src/test/kotlin/com/legitimateapps/dulcet/server/RestDiscoverWishlistTest.kt`

**Interfaces:** `discoverHandlers(ctx:ServerContext):Map<String,Handler>`, `wishlistHandlers(ctx:ServerContext):Map<String,Handler>`, included in authorized handlers map. Shared Node projection from domain, no second state machine/matcher. Extend `SubsonicError(code:Int,message:String,reason:String?=null,retryAfterSeconds:Long?=null)` and `Envelope.error(code:Int,message:String,reason:String?=null,retryAfterSeconds:Long?=null):Node`; the error child carries those optional attributes. Existing call sites/default envelopes remain unchanged. GET/POST form under formPost, JSON/XML/JSONP, normal auth/envelopes. Declared empty lists preserve JSON arrays. REST omits optional null fields; API follows explicit nullable schema.

| REST method | Inputs beyond auth/format | Container |
|---|---|---|
| `searchDulcetDiscover` | kind,query,size=20,cursor | dulcetDiscover |
| `getDulcetDiscoverShelf` | id,size,cursor | dulcetDiscover, or dulcetDiscoverPlaylists for publicPlaylists |
| `getDulcetDiscoverArtist` | mbid | dulcetDiscoverArtist |
| `getDulcetDiscoverArtistAlbums` | mbid,size,cursor | dulcetDiscover |
| `getDulcetDiscoverAlbum` | mbid,releaseId? | dulcetDiscoverAlbum |
| `getDulcetDiscoverAlbumTracks` | mbid,releaseId,size,cursor | dulcetDiscover |
| `searchDulcetDiscoverPlaylists` | query (empty allowed),size,cursor | dulcetDiscoverPlaylists |
| `getDulcetDiscoverPlaylist` | mbid,size,cursor | dulcetDiscoverPlaylist |
| `getDulcetDiscoverCoverArt` | id | binary thumbnail; errors envelopes |
| `getDulcetWishlist` | kind?,state?,includeFulfilledHistory=false,size=20,cursor | dulcetWishlist |
| `getDulcetWish` | id | dulcetWish |
| `addToDulcetWishlist` | kind,mbid,title,artist?,year?,artworkId?,sourceType,sourceId?,operationId,actionTime? | dulcetWishMutation |
| `removeFromDulcetWishlist` | id OR addOperationId,operationId,actionTime? | dulcetWishMutation |
| `rejectDulcetDiscoverMatch` | kind,mbid,libraryType,libraryId,wishId?,operationId | dulcetWishMutation |

Discover page arrays `item`; playlist summary `playlist`; entry `entry` with position/item; discography `album`; album track `song` carries disc/position. `externalIds`, `availability`, `libraryItems.item`, `snapshot`, `source`, `links.link` are explicit nodes. Artist/album summary uses `item` child, page arrays separate. REST libraryType song maps to API track. `dulcetWishlist` has `wish[]`, nextCursor and `changed` equality-only wall timestamp, strictly advancing per actual mutation across API/admin/scan/REST. Probe size1, full cursor read when changed, no since shortcut; cursors bind list generation and refuse mutation-shifted pages. Wish clock is independent of lastScan and My Library changed.

- [x] **Step 1: Write failing tests.** `advertisementIndependenceAndDisabledMethodRefusal`; `jsonXmlAndJsonpProjectSameFieldsAndEmptyLists`; `wishClockCoversApiAdminAndScanButNotDuplicateAdd`; `retryRemoveBeforeAddAndIntentionalReaddSurviveRestart`; `discoverOffKeepsWishlistReadRemoveAndApiAdd`. Assert missing10, invalid/cursor/upstream0 with stable reason, disabled-app-add50, unknown/other-user70, all envelopes HTTP200. Assert binary failure is envelope, cached outage is stale data and uncached outage is error.
Assertion anchors (Kotlin; arrange the named scenarios above):

```kotlin
assertEquals("1.16.1", envelope["version"]!!.jsonPrimitive.content)
assertEquals(50, discoverOffAddErrorCode)
assertEquals(0, upstreamUnavailableErrorCode)
assertTrue(xml.contains("<dulcetWishlist"))
```

- [x] **Step 2: Verify red.** Run `server_test RestDiscoverWishlistTest`.
- [x] **Step 3: Implement.** Add independently advertised extension version1 and adapters. Add shared wishlist clock advancement to service/store: `fun Db.Session.markWishlistChanged(userId:String,receivedAt:Long):Long` stores `max(receivedAt, previous+1)` under `wishlist_changed:<userId>` in meta and returns it. Call only on actual wish changes in the same transaction; rejection without a wish does not advance this clock. Documents model `dulcet-library-extension.md`, giving all fields/methods/parameters/defaults/ranges/errors, full JSON/XML examples, escaping, ids, stale age, paging/clock rules, matching/partial edition semantics, states/notes, artwork proxy, wrong-match policy, 30-day history and switches. Include lost-response add/remove/cancel/re-add sequences with durable operation UUIDs, and explicit “Wish all missing” as one add per unique canonical missing item. No acquisition or playlist-level wish. Client can implement transport/reader/outbox without server code. Settings table includes all four bases and inactive optional token.
- [x] **Step 4: Verify green.** Run `server_test RestDiscoverWishlistTest` and `server_test RestAuthorizationSecurityTest`; auth-query canaries never reach upstream/errors, escaped snapshot/note round trips, examples agree with API. A consumer facing absent extension must issue zero new methods; actual core/UI implementation is later lane.
- [x] **Step 5: Commit.** Message `feat(rest): publish Discover and Wishlist extensions version one`.

### Task 16: MCP tools and public guides

Implementation note: the existing runnable DA-MCP-3 check derives its expected tools from the
shared registry. It must also filter optional capability/version metadata, so Task 16 additionally
updates `dulcet-api-conformance/src/main/kotlin/com/legitimateapps/dulcet/apiconformance/Realtime.kt`,
`ChecksMcp.kt`, its `src/test/kotlin/com/legitimateapps/dulcet/apiconformance/MockServer.kt`, and adds
`McpCapabilityTest.kt` in that test package. This is existing-profile compatibility, not a new
Discover/Wishlist conformance profile. `ApiMcpTest.kt` becomes capability aware while retaining its
scope, origin, revocation and message assertions.

**Files:**
- Modify: `server/src/main/kotlin/com/legitimateapps/dulcet/server/v1/ApiMcp.kt`
- Modify: `docs/dulcet-api/mcp-tools.json`
- Modify: `docs/dulcet-api/README.md`
- Modify: `docs/dulcet-api/llms.txt`
- Modify: `docs/dulcet-api/CONFORMANCE.md`
- Test: `server/src/test/kotlin/com/legitimateapps/dulcet/server/WishlistMcpTest.kt`

**Interfaces:** Tools `search_discover(query:String,kind:String,limit:Int=20,cursor:String?)` catalog:read; `list_wishes(kind:String?,state:String?,includeFulfilledHistory:Boolean=false,limit:Int=20,cursor:String?)` user:read; `add_wish(kind:String,externalIds:object,snapshot:object,source:object,operationId:String,actionTime:String?)` user:write. Search/list readOnlyHint=true; add readOnlyHint=false/destructiveHint=false/idempotentHint=true. Max100, output schemas API page/mutation. Capability plus scope gates listing and calls. No admin tool.

- [x] **Step 1: Write failing tests.** `toolsExposeOnlyEnabledCapabilitiesAndCorrectScopes`: exact three names/hints, no all-users/state tool. `sameResourcesAndDurableAddAcrossProtocols`: API/REST/MCP one wish, repeated UUID no duplicate, write-only API snapshot add with Discover off. `inputAndOutputSchemasValidateWithOpenOutputState`: MBID/snapshot/UUID required, max100, unknown output state readable; wrong scope403 and disabled tool unavailable.
Assertion anchors (Kotlin; arrange the named scenarios above):

```kotlin
assertTrue("search_discover" in catalogToolNames)
assertTrue("list_wishes" in readerToolNames)
assertTrue("add_wish" in writerToolNames)
assertEquals(firstWishId, repeatedAddWishId)
```

- [x] **Step 2: Verify red.** Run `server_test WishlistMcpTest`.
- [x] **Step 3: Implement.** Dispatch to same services and registered definitions; add capability metadata to new tool definitions and preserve existing tools. Guides include paths/scopes/events/feed/tool matrices, credit/privacy/cache/switches and offline protocol. Describe optional `DA-DISCOVER-1` and `DA-WISHLIST-1` profiles, clearly planned unless a runnable suite is added later; do not claim new conformance execution from prose. Update registry guidance for optional tools.
- [x] **Step 4: Verify green.** Run `server_test WishlistMcpTest`, `server_test ApiMcpTest`, `python3 -m json.tool docs/dulcet-api/mcp-tools.json > /dev/null`. Exact existing tool-list tests become capability-aware while retaining all earlier coverage.
- [x] **Step 5: Commit.** Message `feat(mcp): expose discovery search and personal wishes`.

### Task 17: Server end-to-end evidence and final contract audit

**Files:**
- Create: `server/src/test/kotlin/com/legitimateapps/dulcet/server/DiscoverWishlistIntegrationTest.kt`
- Create: `docs/evidence/discover-wishlist-server.md`
- Modify: `docs/dulcet-server/README.md`
- Modify: `docs/superpowers/specs/2026-08-18-dulcet-design.md` (§28 dated addition, §§29–30 actual contracts)
- Modify: `docs/superpowers/specs/2026-10-22-discover-and-wishlist-design.md` (status/evidence only)
- Modify: `FEATURES.yml` (seven §8 rows only)

**Interfaces:** Integration starts real disposable Ktor Dulcet Server, four upstream base settings bound to FixtureDiscoverUpstream and explicit server base for HTTP caller. Production scanner/worker/routes/webhook maintenance drive effects, no service mock replaces arrival. Evidence records source commit, exact commands, positive JUnit counts, observed chain and unobserved limits.

- [x] **Step 1: Write failing tests.** `discoverWishWebhookScanArrivedLibraryAndFeed`: fixture search -> add via API/REST -> signed admin requested -> actual tagged files -> scan/drain -> arrived wish -> own My Library read -> feed upsert -> owner webhook. Assert exact ids/times, one membership and zero other-user changes. `outagePartialRejectedRemovedStayCorrectAfterRestart`: 8/12, cached outage/age, wrong-match restart, later alternative correct candidate, removal during held metadata. `discoverDisabledSnapshotWishFulfilsFromCacheWithoutNetwork`; `wishlistDisabledRetainsRowsAndResumesJobs`. Scope of plain-server control is server contract only; later core/app lane owns real Navidrome zero-new-request/UI evidence.
Assertion anchors (Kotlin; arrange the named scenarios above):

```kotlin
assertEquals("fulfilled", arrivedOverHttp.state)
assertEquals(wishedLibraryId, membershipOverHttp.id)
assertEquals(1, requestedDeliveries.size)
assertEquals(0, otherUserMemberships.size)
```

- [x] **Step 2: Verify red.** Run `server_test DiscoverWishlistIntegrationTest`; require integration behavior, not a mocked assignment to fulfilled.
- [x] **Step 3: Implement and run checks.** Correct lane-only integration gaps. Run `./gradlew :server:test :server:licensee :server:installDist :dulcet-api-conformance:test --rerun-tasks`; inspect server/conformance XML counts, no failures/errors/skips. Run `npx --yes @redocly/cli@2.54.3 lint docs/dulcet-api/openapi.yaml --config docs/dulcet-api/redocly.yaml`, `python3 tools/parity_gate.py`, `python3 tools/verify_ci_policy.py`, `git diff --check`. Package/dependency resolution is tooling; behavioral tests never reach public music services. Existing conformance self-tests provide regression coverage, not an unimplemented new profile claim.
- [x] **Step 4: Audit and record evidence.** Compare every route/field/example/response/scoping rule with tests; document rate, Retry-After, cache/stale, MBID/name/version/edition matching, idempotency, all transitions, scan arrival, scopes and signing/delivery named in design §8. FEATURES rows: `discover.search`, `discover.shelves`, `discover.pages`, `wishlist.add`, `wishlist.state`, `wishlist.fulfil`, `wishlist.api`. Six platform cells reflect later unimplemented/partial app evidence, never supported from server tests. Terms/CAA caching review remains explicit PROD follow-up. Document stale maximum90 days, unknown completeness delays fulfilment and receipts retained until user deletion. Preserve #349 lineage/identity/timeline invariants when integrating main. Risk warrants owning-session independent review of auth/writes/sync before eventual PR; this planning turn authorizes no implementation push or external review request.
- [x] **Step 5: Commit.** Message `test(server): evidence discovery and wishlist end to end`. Include only listed files and any specifically identified earlier lane integration fixes. Prepare one implementation PR description superseding #352 when the owning session requests delivery.

## Expected overlap with #349

OBSERVED 2026-10-22 from the current PR diff: #349 changes `docs/superpowers/specs/2026-08-18-dulcet-design.md`, `FEATURES.yml` and `.github/workflows/core-ci.yml`; no current `server/` paths. This lane touches the first two in Task 17 and plans no CI change. The brief warns its main merge may touch server files. Potential seams here are `Main.kt`, `db/Schema.kt`, `scan/Scanner.kt`, `v1/Api.kt`, `v1/Journal.kt`, `v1/Sync.kt` and identity regression tests under the existing server source roots. Re-read its actual merged diff on resume. Preserve stable installation identity, catalog ids, access filtering and timeline continuity; no core/account/media identity edits or cross-lane workarounds. Tag migration and scan callback are additive with constructor compatibility.

## Self-review

- **Spec coverage:** Source/search/page goals map to Tasks 1–6/12/15; matching to7–8/10–11; wishes/states/fulfilment to9–11; API/scopes/feed/events/MCP to12–16; server evidence to17. Design §6.2 has explicit coverage for both switches, release-group capture and candidate-only lookup, edition completeness, name/version fallback, rejection, idempotent removal/re-add, membership, 30-day history, signed admin requests and owner changes, art proxy and private seed assembly. §11 is accepted. Core/native/outbox implementation, apps and real Navidrome client control belong to later lanes. §10 stays a recorded PROD condition. No implementation-blocking design question identified.
- **Step scan:** Every task has exact Files/Interfaces, named tests with assertions/values, red command, implementation choices, green command and commit. Helpers define exact commands once. Bodies stay with implementer. Fixtures/provenance belong to their consuming tasks.
- **Type consistency:** One canonical target per kind; domain song maps to API library track only at boundary. CacheAge, PageRequest, EditionTrack, WishMutation, ExternalTarget and operation ids have single definitions. All adapters use one matcher/state machine; source clients take no user or Principal. Playlist shelf has its own summary page, never cast to music items.
- **Review Focus:** All five focus lines map to named tests, including lost-response cancellations, one-to-one edition slots, permission-filtered cache projection, malformed/truncated/stale data and scan/transaction/restart races. Every behavioral upstream request is fixture-bound.
- **Proportion and limits:** Seventeen independently reviewable deliverables; signatures/policies/assertions rather than implementation bodies. This commit contains the plan only. Main merge integration waits for actual #349 merged source rather than guessing changes here. Server evidence cannot promote six app feature cells.


## Execution review notes, 2026-10-22

Tasks 1 through 16 were executed directly in this thread with failing tests before implementation
and individual commits. Task 17's real HTTP test found a future action-time retry conflict: the
boundary clamp changed the durable fingerprint on retry. The shared state machine now fingerprints
the original timestamp and clamps only its ordering time. Its service and HTTP regressions pass.
The real fixture receiver records HTTP method/body as well as headers for independent HMAC checks.
Wishlist WebhookEvent now conditionally references its two payload schemas, checked by strict lint.
Existing conformance MCP registry filtering also needed optional capability awareness; its focused
negative/version test passes. No new optional profile execution is claimed. The owning session's
execution amendment authorizes push/PR/CI and arranges independent review after the PR; it supersedes
this plan's original planning-only delivery and different-agent-app execution handoff language.

The full Task17 run also requires `server/src/test/kotlin/com/legitimateapps/dulcet/server/ScannerTest.kt` to remove the additive release-group column when it deliberately reconstructs a version-1 database. Resetting user_version alone leaves an invalid migration fixture. The existing fingerprint/move assertions remain unchanged.

Task 17 completed: full leased verification reports 244 server tests and 61 API-conformance self-tests, zero failures/errors/skips in each XML set. Licence and distribution tasks, strict OpenAPI lint, parity (40 rows), CI policy (13 workflows), evidence boundaries, spec paths and diff checks pass. Main integration and delivery follow under the approved execution amendment.

Delivery CI follow-up: core-ci run 39831584121 exhausted its 25-minute build job budget inside apt-get update before server tests. The completed log shows repeated ignored Azure Ubuntu mirror requests. The two server-test media provisioning steps in `.github/workflows/core-ci.yml` now bound HTTP/HTTPS requests to 30 seconds with one retry, keeping the existing mirror configuration, exact FFmpeg pin and job caps. This is an observed CI infrastructure correction beyond Task 17's initial file list; it does not anticipate #349 changes.

The completed core-build log from run 39836200785 shows why request bounds alone did not settle provisioning: metadata refresh completed but Azure package downloads progressed near 40 kB/s until the job cap. The two provisioning steps now rewrite the Azure HTTP mirror entry to the already configured official HTTPS Ubuntu archive. Exact package pin, APT signature validation, request bounds and job caps remain unchanged. This second diagnosis is based on the completed job log, before any further change.
