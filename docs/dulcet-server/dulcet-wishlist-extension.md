# OpenSubsonic extension `dulcetWishlist`, version 1

Dulcet Server advertises `{"name":"dulcetWishlist","versions":[1]}` independently of
`dulcetDiscover`. Clients issue these methods only after detecting version 1. Transport, auth,
GET/form POST, JSON/XML/JSONP, escaping and HTTP 200 error envelopes follow
[Discover](dulcet-discover-extension.md) and [My Library](dulcet-library-extension.md).
No new app request is appropriate on a plain server without these extensions.

## Methods

| Method | Parameters | Response child |
|---|---|---|
| `getDulcetWishlist` | optional `kind`, `state`, `includeFulfilledHistory=false`, `size=20`, `cursor` | `dulcetWishlist` with `wish[]`, optional `nextCursor`, `changed` |
| `getDulcetWish` | required opaque `id` | `dulcetWish` |
| `addToDulcetWishlist` | required `kind`, canonical `mbid`, `title`, `sourceType`, UUID `operationId`; optional `artist`, integer `year`, opaque `artworkId`, `sourceId`, `actionTime` | `dulcetWishMutation` |
| `removeFromDulcetWishlist` | exactly one of opaque `id` or UUID `addOperationId`; required UUID `operationId`; optional `actionTime` | `dulcetWishMutation` |
| `rejectDulcetDiscoverMatch` | required `kind`, canonical `mbid`, `libraryType`, `libraryId`, UUID `operationId`; optional `wishId` | `dulcetWishMutation` |

`kind` is artist, album or song. List size accepts 1..100; malformed/out-of-range values fail.
List filters and ordering are server-side: added time descending, then id. `state=fulfilled` implies
all fulfilled history; otherwise fulfilled wishes older than 30 days leave the default list unless
`includeFulfilledHistory=true`. Direct reads and Dulcet API sync snapshots retain all live wishes.
Aging is not deletion. Unknown kinds/states are invalid inputs; unknown output values are open.

`sourceType` accepts `search`, `shelf`, `artist`, `album`, `playlist` or `api`. A shelf's id goes in
`sourceId`. Titles are required and at most 500 Unicode code points; artist and source id are at
most 500. The artwork id must have been issued by this server; URLs are refused. A canonical target
is artist MBID, release-group MBID for an album, or recording MBID for a song. Different editions
of the same release group share one wish. There is no playlist-level wish. **Wish all missing**
sends one add for each unique canonical missing item with `wishable=true`, skipping held items and
unknown recording ids. Preserve playlist duplicates in its reader, but deduplicate wishes.

An app add starts from a Discover item and sends its display snapshot. The server does no upstream
work inside the add transaction. When Discover is off, REST app add fails with `50`, while Wishlist
read/remove stay available. Dulcet API snapshot add under `user:write` remains available without
Discover. When Wishlist is off, wish methods fail `70`, retained rows and pending scan work remain,
and API change feeds can still deliver recorded owner-only tombstones. Pair rejection works with
either Discover or Wishlist enabled; a disabled Wishlist is never created or reopened by it.

## Wish and mutation fields

| Field | Meaning |
|---|---|
| `id`, `userId` | Opaque wish id and owner id; ordinary users see only their own |
| `kind`, `externalIds` | Kind and canonical MusicBrainz UUID registry; same as Discover |
| `snapshot` | `title`, optional `artist`, `year`, `artworkId`; captured display data, not a live external lookup |
| `source` | `type`, optional `id` |
| `addedAt`, `updatedAt` | UTC RFC3339 wall timestamps, milliseconds |
| `state` | wanted, requested, searching, unavailable, fulfilled; open output set |
| `note`, `noteAt` | Optional integration/admin note, at most 1000 Unicode code points, and time set |
| `fulfilledAt` | Optional server arrival time |
| `libraryItem` | Optional local `id`, `type=artist\|album\|song` |
| `availability` | Same `status=held\|partlyHeld\|notHeld`, `completeness=known\|unknown`, edition counts and `libraryItems.item[]` as Discover; `known` describes available edition metadata, while `status` describes holdings |
| `revision` | Opaque version, also used by API conditional state updates |
| Mutation `applied` | True only when this operation changed persistent state |
| Mutation `wish` | Optional resulting live wish; omitted for a removed/cancelled generation |
| Mutation `removed` | True when the operation's generation is removed or cancelled |

REST omits null fields; JSON lists remain arrays. API Wish uses explicit nulls for note, noteAt,
fulfilledAt and libraryItem, and mutation wish. Notes and snapshots are plain escaped text.
Do not interpret HTML. Use the server artwork proxy, never contact an upstream image URL.

The owner removes in any state. Admins/integrations use Dulcet API `admin` scope to move freely
among wanted/requested/searching/unavailable. In that API PATCH, an absent `note` preserves both
the note and `noteAt`; explicit JSON null clears both; a string sets the note and refreshes
`noteAt`, even if the text is unchanged. REST and MCP expose no admin state or note update
operation. They cannot
set fulfilled or leave fulfilled. The scanner alone fulfils a complete local match and atomically
adds that item to the owner's My Library. Song adds the song, album the album, artist the artist.
Membership already present is preserved. Missing files later do not reopen a fulfilled wish.
Partial albums remain open with counts such as 8 of 12; unknown edition data omits counts and waits.

**This isn't it** records a per-user target/local-item pair and reopens only its linked fulfilled
wish as wanted, clearing note/arrival/link. It retains My Library membership. The rejected pair
survives removal/re-add and restart, and never blocks another user's matching. `libraryType` is
artist, album or song. Hidden or another user's references are refused without revealing them.
A historical fulfilled state remains after a source restriction, but hidden local ids/counts are
redacted on reads.

## Changed clock and cursor reads

`changed` is absent before the first actual wish mutation. It advances as
`max(serverReceivedAtMillis, previous+1)` for API, REST, admin, scan and linked rejection changes,
in the same database commit as the wish/journal/event. A duplicate add, duplicate operation or
rejection without a changed wish leaves it alone. It is independent of the last scan and My Library
clocks. Compare it for equality only, never use it as an ordering or since cursor.

Probe with size=1. If changed differs, read the complete list via nextCursor, retaining the same
filters. Cursors bind owner, filter, visibility and list generation. A mutation between pages
expires the cursor with code 70 and reason cursor-expired; start again. A foreign or filter-mismatched
cursor fails code 0. There is no offset or since shortcut. A client can merge its durable unsent
outbox over the last complete reader cache while refreshing.

## Durable offline operations

Persist operation UUIDs and action times before showing optimistic changes. `actionTime` is RFC3339
UTC, clamped to current server time if in the future, or receipt time if omitted. Latest action wins
per user/kind/MBID; removal wins equal timestamps. Keep the same payload, actionTime and operationId
when retrying a lost response. An operation reused with different intent fails code 0,
reason operation-conflict. Do not invent a new UUID for a retry.

1. Add A with operation UUID A. A lost response is retried as A and returns the same wish id;
   duplicate adds for the target keep its original snapshot and added time.
2. Remove that returned wish id with UUID R and the removal's action time. Retrying R is harmless,
   including after restart. A later replay of A returns removed and no wish; it cannot resurrect it.
3. If the add response was lost before its wish id was known, remove with `addOperationId=A` and
   operation UUID R. This cancels A durably even if the removal reaches the server before A.
4. A deliberate later add uses a new UUID B and later action time. It creates a new wish id and a
   new integration request. Old removals refer to the old id/add operation and never delete B.

Receipts and cancellation intent persist until user deletion, beyond the HTTP retry cache's
24-hour life. Expiry of an HTTP Idempotency-Key response does not expire an add/remove receipt.
An API cached add response is checked against the durable current generation before replay.
Wishlist writes do not acquire music. Integrations can subscribe to signed `wishlist.requested`
(admin) and owners to `wishlist.changed` (user:read), or consume the owner-only `wish` change feed.

## Examples and errors

```json
{"subsonic-response":{"status":"ok","version":"1.16.1","type":"dulcet","serverVersion":"0.1.0","openSubsonic":true,"dulcetWishlist":{"changed":"2026-10-22T12:00:00.000Z","wish":[{"id":"wi_example","userId":"u_example","kind":"song","addedAt":"2026-10-22T12:00:00.000Z","updatedAt":"2026-10-22T12:00:00.000Z","state":"wanted","revision":"r_example","externalIds":{"musicbrainzRecordingId":"11111111-1111-4111-8111-111111111111"},"snapshot":{"title":"A & B","artist":"Artist"},"source":{"type":"search"},"availability":{"status":"notHeld","completeness":"unknown","libraryItems":{"item":[]}}}]}}}
```

```xml
<?xml version="1.0" encoding="UTF-8"?>
<subsonic-response xmlns="http://subsonic.org/restapi" status="ok" version="1.16.1" type="dulcet" serverVersion="0.1.0" openSubsonic="true"><dulcetWishlist changed="2026-10-22T12:00:00.000Z"><wish id="wi_example" userId="u_example" kind="song" addedAt="2026-10-22T12:00:00.000Z" updatedAt="2026-10-22T12:00:00.000Z" state="wanted" revision="r_example"><externalIds musicbrainzRecordingId="11111111-1111-4111-8111-111111111111"></externalIds><snapshot title="A &amp; B" artist="Artist"></snapshot><source type="search"></source><availability status="notHeld" completeness="unknown"><libraryItems></libraryItems></availability></wish></dulcetWishlist></subsonic-response>
```

Missing required parameters use `10`; invalid parameters, conflicting operations and rejected
cursor filters use `0` with a stable reason; unknown/other-user/removed wishes or disabled Wishlist
use `70`. Disabled Discover app-add uses `50`. Removal of an already removed own id succeeds;
an unknown or other user's id fails `70`. Uncached upstream errors elsewhere use `0`, reason
upstream-unavailable and retryAfterSeconds. See the Discover document for all settings and cache
rules. Core transport/readers/outboxes and app UI are later lanes; this contract supplies their seam.
