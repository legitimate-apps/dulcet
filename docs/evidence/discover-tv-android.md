# Discover and Wishlist on Android TV

Android TV uses the same account-owned `DiscoverWishlistSession`, `CoreDiscoverWishlistSource` and
`AndroidDiscoverClient` as the phone. The phone's paging reader and browse memory now live in
`android/shared`, under their existing package; their behavior and the phone's tests are unchanged.
There are no core changes or additional network or login paths.

Discover appears beside Listen Now when the account advertises Discover. It has horizontal rows of
large cards for all six home shelves, independent loading, source age, empty, error and retry notices,
full shelf pages and explicit Load more actions. Public playlists have explicit search and paging;
entries retain their server positions, including duplicate recordings and recordings without MBIDs.
Artist and album metadata includes edition selection and paged albums or tracks. A held item with a
single correctly scoped match opens Library; partial albums retain their metadata and explicit
library-match controls. Availability labels follow the phone's precedence, including partial track
counts and an explicit unknown library match. MusicBrainz and ListenBrainz credit and Trending on
ListenBrainz remain visible.

TV Search retains My server and adds Everywhere for supported accounts. Wishes have separate named
Wish for and Remove wish controls. They use the existing durable outbox and exact search, artist,
album, shelf or playlist source. Library exposes Wishlist independently of Discover availability.
Wishlist includes kind and state filters, older arrivals, sync/error status, dates and notes, explicit
remove, Open in Library and Wrong match actions for arrivals. Recent wishes route by their stable key.

The presentation uses TV Material in a dark theme. Native TV buttons supply visible remote focus;
the existing route focus memory and saveable list positions restore Back. Navigation and library
actions can scroll horizontally at larger font sizes. An idle query is a native TV button; Centre opens and focuses its editor and the TV keyboard.
Submission is explicit, and D-pad Up returns to navigation. Search owns route entry once across
My server and Everywhere, so switching scope cannot restore its previous focus over the query. Artwork is decorative. Source retry
availability uses a finite delay until the server deadline, without automatic source refreshes.
Navidrome retains its existing navigation and sends no Discover or Wishlist requests.

The fixture-backed instrumentation class is `AndroidTvDiscoverWishlistProofTest`. Run it on an
explicitly owned Android TV emulator with:

```sh
ANDROID_SERIAL=emulator-5554 tools/ci/with-discover-wishlist-fixture -- \
  tools/ci/run-discover-wishlist-proofs android-tv
```

The TV runner verifies the system image advertises television and the foreground package is
`com.legitimateapps.dulcet.tv`, requires its exact two-method inventory, and fails zero, missing,
failed or skipped cases. It keeps JUnit XML, request and fixture receipts, and compressed native JPEGs.
The `discover-wishlist-android-tv` job runs beside the phone proof in `core-ci`, builds both TV APKs,
uses an Android TV system image, and supplies its result and attempt-specific artifact to the required
aggregate job. No feature-support cells are promoted by this change.

## Observed host checks

Executed counts come from JUnit XML. No phone host test source changed. The final TV task ran after
the TV-only focus and layout fixes; both phone tasks ran after the shared reader moved. Local JVM
runs used the Android handoff's compiler-tier workaround; Linux CI and the host soak use normal JVM settings.

| Task | Tests | Failures | Errors | Skips |
| --- | ---: | ---: | ---: | ---: |
| `:android:tv:testDebugUnitTest` | 115 | 0 | 0 | 0 |
| `:android:app:testDevDebugUnitTest` | 306 | 0 | 0 | 0 |
| `:android:app:testProdDebugUnitTest` | 306 | 0 | 0 | 0 |

The ten `TvDiscoverTest` cases include a causal focus regression: the first query-focus request after
switching to Everywhere fails before the fix and passes afterward. The scopes had both entered the
same navigation route, allowing restoration to put focus back on the scope control. A single route
owner fixes this without retrying focus or changing the phone.

## Observed native checks

The final application runs on an API 34 Android TV ARM64 system image. The standard proof requires
the complete two-method inventory; the primary flow also runs at system font scale 2.0. It verifies
artist navigation, shelf row 24, a partial album, Back focus restoration, shelf and playlist wish
sources persisted on the server, duplicate playlist positions, an unwishable missing-MBID recording,
playlist position 24, Everywhere, Wishlist, fixture scan fulfilment and real library membership,
and removal. Arrival is asserted on the wished row's state rather than the filter label. Removal
requires both server absence and UI row disappearance while the other two wishes remain.

| Device selection | Executed | Failures | Errors | Skips |
| --- | ---: | ---: | ---: | ---: |
| Standard text, full TV inventory | 2 | 0 | 0 | 0 |
| Largest text, primary flow | 1 | 0 | 0 | 0 |

The Navidrome control observes healthy application catalog reads before asserting absent Discover,
Everywhere and Wishlist controls and zero unsupported feature requests. Query receipts record an
accepted focus request followed by settled focus on the query. Native input-service receipts observe
the keyboard shown before editing and dismissed before remote submission. The runner restores the
original font scale. Each fixture wrapper reaps its five children and deletes its temporary root.

Independent visual review confirms the compact home layout, clear action focus ring, navigation gaps,
readable metadata and complete wish controls at both text sizes. Large text uses stacked card actions
and horizontal scrolling for navigation and filter rows. The final populated home and focused Arrived
filter captures close the earlier loading-state and filter-reachability evidence gaps. At largest
text, the home card actions require vertical scrolling and a partly visible next card indicates
horizontal overflow. Playlist entries prioritize large readable text and separate wish actions,
showing fewer rows at the largest font size. The reviewer recorded the reduced initial action
visibility as a medium usability note, with no blocking finding.

## CI and integration

Parity validates all 44 feature rows. CI policy validates 13 workflows; the policy mutation controls
include the TV fixture invocation. Runner self-tests cover exact phone, shelf and TV inventories and
reject incomplete or wrong-class results. The generic proof-runner tests pass all 23 cases.

The generic runner adds mode `android-tv`; combine that choice and dispatch branch with the Apple TV
mode when integrating the two TV lanes. No core interface request is needed. No `FEATURES.yml` cell
is promoted by this branch.

