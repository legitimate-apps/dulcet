# OpenSubsonic extension `dulcetLibrary`, version 1

Dulcet Server advertises this extension in `getOpenSubsonicExtensions`:

```json
{ "name": "dulcetLibrary", "versions": [1] }
```

It gives each user a personal **My Library**: the artists, albums and songs that user has added,
separate from everything the server holds (which the apps show as **Browse**). Membership belongs
to the user, like stars and ratings. Adding an album to one user's library changes nothing for
anyone else.

A client that does not see the extension advertised must not call these endpoints. On a server
without the extension, My Library has no server-side home. A client can then fall back to its own
convention, such as starred items, or hide the split. Everything below uses the ordinary
`/rest/<method>` transport, authentication, `f=` formats and error envelopes. The methods accept
GET and, under `formPost`, POST forms.

## Membership flag on ordinary responses

Every `child` (song), `album` and `artist` element the server returns carries
`"dulcetInLibrary": true` when that item is in the requesting user's library. Otherwise the
attribute is absent, never `false`. This covers getAlbum, getArtist, getSong, album lists, search3,
playlists, starred lists and the rest. Clients that do not know the extension ignore the attribute,
as the OpenSubsonic spec requires of unknown fields.

## `addToDulcetLibrary`

| Parameter | Repeatable | Meaning |
|---|---|---|
| `id` | yes | A song, album or artist id. The server works out which. |
| `albumId` | yes | An album id. |
| `artistId` | yes | An artist id. |

Answers an empty `ok`. Adding an item that is already a member is not an error and keeps its
original added time.

Errors:
- `10` when no id is given.
- `70` when an id is not in the catalog. Then nothing is added: the call is all or nothing.

Membership is per item. Adding an album does not also add its songs one by one, and adding a song
does not add its album. The client decides how to present that, for example by showing an album
when any of its songs are members.

## `removeFromDulcetLibrary`

Takes the same parameters as `addToDulcetLibrary` and answers an empty `ok`. Removing an item that
is not a member is not an error. Removing an id the catalog no longer has is also allowed, so a
member whose files were deleted can still be removed. An `id` that the catalog cannot resolve is
removed whatever its type.

## `getDulcetLibrary`

| Parameter | Default | Meaning |
|---|---|---|
| `type` | all three | `artist`, `album` or `song`. Any other value returns error `0`. |
| `size` | 500 | Page size per type, at most 5000. |
| `offset` | 0 | Offset per type. |
| `since` | none | Epoch milliseconds. Returns current members added after this time, including offline Dulcet API additions received after it. |

```json
{
  "subsonic-response": {
    "status": "ok", "version": "1.16.1", "type": "dulcet", "serverVersion": "0.1.0", "openSubsonic": true,
    "dulcetLibrary": {
      "artistCount": 1, "albumCount": 2, "songCount": 14,
      "changed": "2026-10-12T18:04:11.532Z",
      "artist": [ { "id": "…", "name": "…", "dulcetInLibrary": true } ],
      "album":  [ { "id": "…", "name": "…", "dulcetInLibrary": true } ],
      "song":   [ { "id": "…", "title": "…", "dulcetInLibrary": true } ]
    }
  }
}
```

- The counts are whole-library totals for the user, whatever `type`, `size` and `offset` say.
- `artist`, `album` and `song` use the same element shapes as `getArtists`/`getArtist`,
  `getAlbumList2` and `getSong`. Each list is ordered newest addition first, with ties broken by id.
- `changed` is the user's library clock. It moves forward on every add or remove call, and two
  writes in the same millisecond still give two different values. It is absent until the user's
  first write. Clients compare it for equality only. When it differs from the value they hold,
  they refresh the library. `since` only helps with additions: removals have no tombstones, so a
  client must establish completeness from the whole-library totals or perform a full re-read
  (§29.9). Dulcet API writes advance the same clock. An offline API addition keeps its original
  action time, but also has a persistent receipt time used by this endpoint's `since` filter;
  an equal-count replacement cannot disappear from the incremental read, even after journal pruning.
- Library writes never move the server's scan clock (`getScanStatus.lastScan`), and scans never
  move `changed`.

## Why an extension, not stars or a playlist

Stars already mean "favourite" in every Subsonic client, and a user can favourite something without
collecting it, or collect something without favouriting it. A special playlist would hold only
songs, would be visible to other clients as clutter, and would carry the playlist edit semantics,
which exist for ordered lists. A named, advertised extension keeps the base API unchanged and lets
any OpenSubsonic client adopt My Library if it wants to.
