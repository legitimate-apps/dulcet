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

2026-10-22 ListenBrainz captures (public CC0 metadata, same repository User-Agent and
at least 1.1 seconds between requests):
- `listenbrainz/trending-artists.json`: GET `/1/stats/sitewide/artists?range=week`.
- `listenbrainz/fresh-releases.json`: GET `/1/explore/fresh-releases/?days=7`.
These retain the first two rows of their source arrays; other fields are unchanged.
- `labs/similar-artists.json`: GET `/similar-artists/json?artist_mbids=0d79fe8e-ba27-4859-bb8c-2f255f346853&algorithm=session_based_days_7500_session_300_contribution_5_threshold_10_limit_100_filter_True_skip_30`
  on `labs.api.listenbrainz.org`; pretty-printed without semantic changes.
Playlist search was unavailable at capture time. Playlist fixtures, trending recordings and
trending release groups are synthetic variants of the shapes checked in the lane brief.
