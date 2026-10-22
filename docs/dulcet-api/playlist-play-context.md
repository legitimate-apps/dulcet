# Playlist play context: client integration contract

The Home server receiver and shared submitted-play outbox carry playlist provenance. Native
playlist actions supply the raw server playlist ID to each queue entry; progressing playback
captures it in the session and persists it before delivery. Reading Home or a playlist does not
supply this signal. Platform runtime coverage and remaining evidence gaps are recorded in
`FEATURES.yml` and `docs/evidence/playlist-play-context.md`.

## Fields and where they are set

Use the existing per-entry `QueueSourceContext`, with `kind = QueueSourceKind.Playlist`,
`sourceId = ProviderItemId(providerInstanceId, rawPlaylistId)`, and the playlist's display name.
Set it at the actual playlist **Play**, **Shuffle**, track activation, **Play Next**, or
**Play Later** action when constructing `PlaybackQueueRequest` or `PlaybackQueueInsertion`.
This includes opening a playlist from Home. Preserve each entry's source through reorder,
shuffle, advancement, preloading and restored queues; an inserted album/search track keeps
its own source even while the surrounding queue came from a playlist. Viewing a playlist,
editing its membership, or playing a recommendation mix is not playlist provenance.

Apple's `ApplePlaybackQueueRequestDto` / `ApplePlaybackQueueInsertionDto` already accept
`sourceKind = "playlist"`, `sourceRawId`, and `sourceDisplayName`; their facade converts these
to the core context. Android's `AndroidQueueSource.Playlist` passes the raw playlist ID from the playlist action
through the controller's queue mapping.
Do not substitute a title, a queue-entry ID, an album ID, a local cache key, or a fabricated
playlist for the raw server playlist ID.
An offline-created playlist that has no server ID yet uses a library source with no playlist
context. A server playlist with pending edits retains its real server ID. Resolving a local
create later does not retroactively assign context to a session already started.

At the session's actual submitted-play threshold, snapshot the **playing entry's** context,
not the then-visible screen or the queue's current selection after advancement. The additive event/outbox shape is:

```kotlin
// Existing track identity and session timestamp remain unchanged.
RecordedPlaybackEvent.SubmittedPlay(
    itemId: ProviderItemId,
    sessionStartWallClock: PlaybackWallClockTime,
    playlistContextId: ProviderItemId? = null,
)
```

`playlistContextId` is non-null only for a real playlist source and must have the same
`providerInstanceId` as `itemId`. Capture it while that session's queue entry is still known
and carry it through the recorder into `PersistentScrobbleOutbox.persistSynchronously`.
The outbox's existing `server_id` binds both IDs to the account/provider; persist the nullable
raw playlist ID alongside `raw_id` and `session_start_wall_clock`, and restore it in
`ScrobbleOutboxEntry.toSubmittedPlay`. Migrate older rows to null. Keep it immutable on retry,
including the worker's timestamp-clamping copy, and preserve it across process death and
offline delivery. Do not reconstruct it from the current queue when draining. Account
switching must never send an old row under another account's credentials.

## Negotiation and wire request

For each connected account/session, authenticate `getOpenSubsonicExtensions` using that
account's normal transport. Enable these extra fields only if its `openSubsonicExtensions`
array explicitly includes `{"name":"dulcetHome","versions":[1]}`. Version 1 must occur in
the versions array; a different version, hostname, server brand, or a Home UI visit is not
negotiation. Cache the result for that connection, invalidate it on reconnect/account change,
and serialize concurrent discovery. The Home reader's private `ListenNowTransport.offered`
is not a public playback capability; use an account-scoped shared capability result or
perform the same negotiation in the outbox composition. Do not couple delivery to Home loading. The playback availability path also invalidates the
sender's answer on a reachable/revalidated report, including a foreground report without a
preceding unreachable report. This changes no queue entry, playback session or attempt and
sends no discovery request until a submitted play with context needs it.
On Android, a successful library-reader reconnect (`ReaderConnectionOutcome.Read`) also
revalidates that account's playback sender, even when the OS network never changes. Its
availability notification is serialized on the controller's delivery dispatcher; another
account's notification does not invalidate this sender. OS network-setter invalidation remains
independent of the reader signal.

A valid absence, or discovery endpoint unsupported (Subsonic 70 / HTTP 404 / 501), means
ordinary scrobbling. Authentication, transport and malformed discovery failures are not
negative advertisements: retain context in the durable row and use the ordinary delivery
failure/retry path until negotiation succeeds. Do not discard context or silently classify
the account as an ordinary server after those failures.

After positive negotiation, `ScrobbleEndpointSender` sends the following for a submitted play
with a playlist context (usual authentication/version/format parameters omitted):

```text
/rest/scrobble?id=<raw song ID>&time=<session-start Unix milliseconds>&submission=true
              &dulcetContextType=playlist&dulcetContextId=<raw playlist ID>
```

Use the normal parameter encoder. Both context fields are optional **together** and occur
only on `submission=true`. A null context or an ordinary OpenSubsonic server receives the
existing `id`, `time`, `submission=true` request unchanged. `RecordedPlaybackEvent.NowPlaying`
continues to send only its existing `id` and `submission=false`; no playlist fields or
playlist-history writes. No new event UUID or parallel play submission is required.

The Dulcet API equivalent is the existing submitted play's
`context: {"type":"playlist","id":"<raw playlist ID>"}`. Clients choosing the API transport
use its negotiated API capabilities and normal play contract rather than also sending REST.

## Receiver behavior and acceptance checks

The server accepts context only for a readable submitted song belonging to an owned/public
playlist visible to that user. It advances that user's playlist timestamp with
`MAX(previous, submitted time)`. Invalid, private, deleted and non-member contexts are ignored
while the ordinary song scrobble succeeds. Playlist reads and now-playing do not write it.
API history and REST annotations are deduplicated into one recent-playlist shelf. The receiver
does not add a playlist play count. This does not change ordinary scrobbling's existing
at-least-once semantics: a lost response can still duplicate a song play count.

Client acceptance checks should drive a real playlist entry through the playback threshold,
inspect its persisted outbox row, relaunch offline, then deliver after reconnection and observe
that playlist in Home. Include a queue advanced before drain, a non-playlist insertion, repeat,
an old migrated row, account isolation, advertisement absence/unsupported version, failed
discovery followed by successful retry, and now-playing as negative controls. Retrying an older
timestamp must not move the playlist backwards. The server's `HomeRecommendationsTest` already
covers the receiver's valid member and invalid/private/non-member/now-playing cases.
