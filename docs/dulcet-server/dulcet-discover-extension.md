# OpenSubsonic extension `dulcetDiscover`, version 1

Dulcet Server advertises `{"name":"dulcetDiscover","versions":[1]}` in
`getOpenSubsonicExtensions` only while outside lookups are enabled. Clients check the extension
and version before issuing any new method. An absent extension means zero Discover requests and
no Discover UI. Wishlist is an independent extension. Devices reach only Dulcet Server; MusicBrainz,
ListenBrainz and Cover Art Archive are contacted by the server.

All methods use ordinary `/rest/<method>` or `/rest/<method>.view` authentication (`u`, `p` or
`t`/`s`, `v`, `c`). GET and POST form bodies under `formPost` are equivalent. Responses use the
usual Subsonic envelope, version `1.16.1`, `type="dulcet"`, `openSubsonic=true`. `f=json`, `f=xml`
(default) and `f=jsonp&callback=...` are supported. Callback names follow the ordinary dispatcher
rules. Encode query/form values with URL encoding; do not concatenate user text into URLs.

## Methods

Every `size` defaults to 20, accepts 1 through 100, and rejects values outside that range. `cursor`
is optional on the first page and opaque thereafter. `mbid` is a canonical MusicBrainz UUID;
`releaseId` is a MusicBrainz release UUID, whereas album `mbid` is a release-group UUID.

| Method | Parameters beyond auth and format | Response child |
|---|---|---|
| `searchDulcetDiscover` | required `kind=artist\|album\|song`, `query` (1..500 Unicode code points); `size`, `cursor` | `dulcetDiscover`, `item[]` |
| `getDulcetDiscoverShelf` | required `id`; `size`, `cursor` | `dulcetDiscover`, `item[]`, or `dulcetDiscoverPlaylists`, `playlist[]` |
| `getDulcetDiscoverArtist` | required `mbid` | `dulcetDiscoverArtist` |
| `getDulcetDiscoverArtistAlbums` | required `mbid`; `size`, `cursor` | `dulcetDiscover`, `album[]` |
| `getDulcetDiscoverAlbum` | required `mbid`; optional `releaseId` | `dulcetDiscoverAlbum` |
| `getDulcetDiscoverAlbumTracks` | required `mbid`, `releaseId`; `size`, `cursor` | `dulcetDiscover`, `song[]` |
| `searchDulcetDiscoverPlaylists` | `query` (empty or omitted allowed); `size`, `cursor` | `dulcetDiscoverPlaylists`, `playlist[]` |
| `getDulcetDiscoverPlaylist` | required `mbid`; `size`, `cursor` | `dulcetDiscoverPlaylist` |
| `getDulcetDiscoverCoverArt` | required opaque artwork `id` | binary thumbnail, or ordinary failure envelope |

Shelf ids are `trendingArtists`, `trendingAlbums`, `trendingSongs`, `newReleases`,
`similarArtists` and `publicPlaylists`. Trending is labelled **Trending on ListenBrainz**, never a
general chart. Discover carries **Data from MusicBrainz and ListenBrainz**. The similar shelf
uses at most five visible artists from My Library, ranked by plays then favourites; without a
signal it is empty and makes no similarity request. Public responses are cached per seed, then
assembled for the requesting user; upstreams receive no Dulcet user id or token.

## Data model

Optional null values are omitted in REST. Output strings are open sets: preserve unknown fields
and map unknown values to a neutral UI. Lists below remain JSON arrays when empty or one element;
XML represents one child per row. No playback media or preview is provided by Discover.

| Object | Fields and children |
|---|---|
| Item | `id` opaque server id, `kind`, `title`, optional `artist`, `year`, `releaseDate`, `artworkId`, `disc`, `position`, `listenCount`, `primaryType`; `wishable` boolean; `externalIds`, `availability`, `links.link[]`, `artistMbids[]` |
| `externalIds` | MusicBrainz UUIDs: `musicbrainzArtistId`, `musicbrainzReleaseGroupId`, `musicbrainzRecordingId`, and source-specific release/CAA ids when supplied. Canonical wish identity is kind plus the corresponding first three keys. These are external ids, never server catalog ids. |
| `links.link` | `relation`, `url` (safe HTTP or HTTPS relation); open in the browser, never treat as a media URL |
| `availability` | `status=held\|partlyHeld\|notHeld`, `completeness=complete\|partial\|unknown`; optional `matchedBy=mbid\|name`, `matchedTrackCount`, `totalTrackCount`, `editionReleaseId`; `libraryItems.item[]` |
| Local reference | `id` opaque local catalog id, `type=artist\|album\|song` (Dulcet API uses `track` for song references) |
| Page | the method's row array; optional `nextCursor`; `fetchedAt` UTC RFC3339 timestamp, `ageSeconds` integer, `stale` boolean |
| Artist | summary `item`, `albums` page with `album[]`, newest known release date first, unknown dates last |
| Album | summary `item`, `editions.edition[]`, optional `selectedReleaseId`, `tracks` page with `song[]` |
| Edition | `releaseId`, optional `releaseDate`; `trackCount`; `links.link[]` |
| Playlist summary | `id` playlist MBID, `title`, optional `creator`, `trackCount` |
| Playlist | `summary`; `entries` page with `entry[]` |
| Entry | one-based `position`, `item`; duplicates and source ordering remain intact |

An album defaults to the earliest dated edition, with release UUID breaking ties; undated
editions sort last. A group without a known edition has an empty tracks page and unknown
completeness. The public playlist search is a bounded first 100 source-results window. A recording
without an MBID has a stable server id for that playlist slot and `wishable=false`.

Matching uses visible catalog rows only, after reading the public cache. MBIDs take priority.
Without a local MBID, artist/title comparison normalizes Unicode, case, punctuation and whitespace
while retaining version words and diacritics. A live, remix, demo, acoustic or instrumental title
cannot satisfy another version. Ambiguous name matches stay partial. Albums are held only when
one local album contains every track of a single known edition, with one local file per edition
slot. Counts never pool across albums or substitute for track identity. For an 8-of-12 match,
`matchedTrackCount=8`, `totalTrackCount=12`, `status=partlyHeld`, `completeness=partial`. Missing
edition metadata leaves completeness unknown and omits counts. Artist held status may reflect
songs, but artist-wish fulfilment requires an album. Use returned local ids to route held items
to ordinary library pages. **This isn't it** uses the rejection method in the Wishlist contract.

## Cache and paging

Search caches for 1 hour, entity metadata and playlist contents for 24 hours, trending/new releases
for 6 hours, similarities for 7 days and thumbnails for 30 days. Negative results cache for 24 hours.
Valid stale data can be served for up to 90 days when a source fails; its age remains visible. A
failure never replaces a last good cache entry. An uncached failure is unavailable, not an empty
success. Artwork not found uses the server placeholder. Signature or size failures are never served.

Cursors bind caller, query, kind, selection and cache generation. Keep the same parameters on
continuation, except size. An invalid or foreign cursor fails; a refreshed or evicted generation
expires the cursor, requiring a new first page. Personal shelf order is frozen for the cursor.
Upstream search pages are pinned as consumed; an upstream does not offer a transactional snapshot.
The server refuses a changed cached page generation rather than mixing it silently. No public offset.

`getDulcetDiscoverCoverArt` returns the already sized `front-250` thumbnail bytes without decoding
or resizing. It requires JPEG, PNG, GIF or WebP magic and at most 5 MiB. The proxy follows at most
five safe redirects, refuses HTTPS downgrades, strips credentials and pins checked DNS addresses.
Use this method with the opaque artwork id. Never request a Cover Art Archive URL from a device.

## Examples

```json
{"subsonic-response":{"status":"ok","version":"1.16.1","type":"dulcet","serverVersion":"0.1.0","openSubsonic":true,"dulcetDiscover":{"fetchedAt":"2026-10-22T12:00:00.000Z","ageSeconds":15,"stale":false,"item":[{"id":"di_example","kind":"song","title":"A & B","artist":"Artist","wishable":true,"externalIds":{"musicbrainzRecordingId":"11111111-1111-4111-8111-111111111111"},"availability":{"status":"notHeld","completeness":"unknown","libraryItems":{"item":[]}},"links":{"link":[]},"artistMbids":[]}]}}}
```

```xml
<?xml version="1.0" encoding="UTF-8"?>
<subsonic-response xmlns="http://subsonic.org/restapi" status="ok" version="1.16.1" type="dulcet" serverVersion="0.1.0" openSubsonic="true"><dulcetDiscover fetchedAt="2026-10-22T12:00:00.000Z" ageSeconds="15" stale="false"><item id="di_example" kind="song" title="A &amp; B" artist="Artist" wishable="true"><externalIds musicbrainzRecordingId="11111111-1111-4111-8111-111111111111"></externalIds><availability status="notHeld" completeness="unknown"><libraryItems></libraryItems></availability><links></links></item></dulcetDiscover></subsonic-response>
```

## Errors and settings

All extension failures are HTTP 200 Subsonic `status=failed` envelopes. Missing required parameters
use code `10`; invalid kinds, size, UUIDs or cursors use `0`, `reason=invalid-request` (cursor
expiry uses `reason=cursor-expired`); unknown resources or disabled Discover use `70`. Uncached
source failure uses code `0`, `reason=upstream-unavailable`, and integer `retryAfterSeconds`.
Messages and upstream requests never contain authentication parameters. Example:

```json
{"subsonic-response":{"status":"failed","version":"1.16.1","type":"dulcet","serverVersion":"0.1.0","openSubsonic":true,"error":{"code":0,"message":"Discovery source temporarily unavailable","reason":"upstream-unavailable","retryAfterSeconds":60}}}
```

Server settings (environment variables, or the corresponding lowercase hyphenated `--name=value` CLI option). Set `DULCET_DISCOVER=off` or `DULCET_WISHLIST=off` to disable:

| Setting | Default | Meaning |
|---|---|---|
| `DULCET_DISCOVER` | `on` | Outside lookups and Discover advertisement |
| `DULCET_WISHLIST` | `on` | Wishlist, independent of Discover |
| `DULCET_MUSICBRAINZ_URL` | `https://musicbrainz.org` | MusicBrainz base; mirrors and fixture upstream supported |
| `DULCET_COVER_ART_ARCHIVE_URL` | `https://coverartarchive.org` | Thumbnail source base; every connection requires public addresses, including this origin |
| `DULCET_LISTENBRAINZ_URL` | `https://api.listenbrainz.org` | Public ListenBrainz base |
| `DULCET_LISTENBRAINZ_LABS_URL` | `https://labs.api.listenbrainz.org` | Similar-artists base |
| `DULCET_MUSICBRAINZ_MIN_GAP_MS` | `1000` | Mirrors may lower the gap; public MusicBrainz cannot go below 1000 ms |
| `DULCET_LISTENBRAINZ_TOKEN` | absent | Optional secret reserved for later, currently unused; never sent |

Artwork checks every resolved address before connecting, including the configured source and each
redirect hop, and pins the checked socket address. Private-network artwork mirrors are unsupported.
Only an explicit `discoverArtworkAllowLoopback` Testing-profile override permits loopback fixtures;
it requires startup test mode and an admin token, resets on restart and never admits other private
or metadata addresses. Origin comparisons normalize scheme, host casing and default ports.

Requests serialize per origin with the Dulcet Server/repository User-Agent and respect Retry-After
and ListenBrainz rate headers. LB Radio, acquisition, downloads and previews stay outside v1.
Terms and thumbnail-cache permission remain conditions for a PROD release; see the design §10.
