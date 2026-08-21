# Conformance registry

This registry reserves stable identifiers for the tests required by the design. An identifier remains
stable when its test moves from planned to executable; the design's representative-test table carries
the detailed assertion.

| id | assertion |
|---|---|
| CONF-01 | unauthenticated extension discovery |
| CONF-02 | reference-server extension set |
| CONF-03 | baseline ping authentication |
| CONF-04 | version compatibility |
| CONF-05 | OpenSubsonic envelope fields |
| CONF-06 | distinguish authentication and transport failures |
| CONF-07 | redact credentials from diagnostics |
| CONF-08 | redirect credential policy |
| CONF-11 | successful stream signature validation |
| CONF-12 | legacy stream error-envelope detection |
| CONF-13 | ranged stream behavior |
| CONF-14a | server-offset transcode seek |
| CONF-14b | legacy transcode offset |
| CONF-15 | OpenSubsonic transcode decision and stream |
| CONF-22 | progressing-media-time scrobble threshold |
| CONF-23 | at-least-once scrobble outbox |
| CONF-31 | generation-pinned reads |
| CONF-32 | atomic sync-generation commit |
| CONF-33 | bounded stability witness |
| CONF-41 | local and server search merge |
| CONF-51 | validated atomic download promotion |
| CONF-52 | offline playback plan |
