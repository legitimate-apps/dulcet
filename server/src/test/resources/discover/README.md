# Discover upstream fixtures

Automated tests use FixtureDiscoverUpstream on loopback. Unscripted requests fail locally.
MusicBrainz and ListenBrainz JSON is recorded as CC0 source data with capture date and endpoint
in this directory as source fixtures are introduced. Synthetic paging, duplicate and malformed
variants are labelled in tests. Artwork is generated locally; no recorded cover image is bundled.

2026-10-22 MusicBrainz captures (public CC0 metadata, User-Agent DulcetServer/0.1.0
with the repository URL, requests separated by at least 1.1 seconds):
- `musicbrainz/artist-search.json`: GET `/ws/2/artist?query=Björk&fmt=json&limit=1`.
- `musicbrainz/recording-search.json`: GET `/ws/2/recording?query=Björk&fmt=json&limit=1`.
Responses are pretty-printed without semantic changes. Release-group search was unavailable
at capture time; its fixture and all other MusicBrainz fixtures are explicitly synthetic,
using invented UUIDs, credits and editions to exercise paging, repeated slots and malformed links.
