# Playlist play context

The submitted-play contract is specified in
[playlist-play-context.md](../dulcet-api/playlist-play-context.md). Context belongs to the
playing queue entry and its playback session; it does not come from whichever screen is visible
when the durable outbox drains. A playlist read and a now-playing report create no playlist history.

## Shared behavioral checks

`PlaylistPlayContextTest` drives progressing media observations through the real queue and
scrobble accumulator. It checks playlist provenance after queue edits, non-playlist insertion,
advancement, restored queues and repeat sessions. Its durable-outbox case observes the persisted
context, recreates the store, fails discovery, retains the row, retries and checks the exact
submitted parameters after timestamp clamping. Other cases cover released-schema migration to
null, provider isolation, valid absence, unsupported discovery versions/endpoints, malformed and
authentication failure retry, serialized discovery and reconnect invalidation. Existing scrobble
outbox and delivery suites continue to check the surrounding delivery policy.

These shared checks establish core behavior. They do not alone establish any platform's UI,
engine playback, process-death behavior or rendered Home result. Platform observations and test
counts are recorded below only when the corresponding runtime has executed.

## Platform coverage

Pending runtime evidence. Each `FEATURES.yml` cell retains its existing platform observations and
states any remaining gap; a successful build is not a runtime observation.
