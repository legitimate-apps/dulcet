# Discover shelves integration

Step 3 combines the [core](discover-shelves-core.md), [Apple shells](discover-shelves-apple.md)
and [Android phone shell](discover-shelves-android.md). The
[plan](../superpowers/plans/2026-10-27-discover-shelves.md) retains its locked core interface.
No additive interface change was needed. Clients use the approved advertised OpenSubsonic
extensions and their existing authentication. TV remains planned.

## Merge and behavior

The Apple branch `d53dfc7b`, based on `040cd181`, merged into the Android integration branch
without conflicts. The fetched main was `a7047e4c` and already an ancestor. The committed generated
Xcode project passed pinned XcodeGen regeneration; no project file was hand-merged.

Discover appears between Listen Now and Library in the five music tabs on iPhone and Android
phone. iPhone Connection moves to a toolbar sheet when needed. iPad uses its sidebar at regular
width and tabs at compact width; Mac uses its sidebar. Navigation, submitted queries and explicitly
loaded pages are account-scoped. TV constructs no Discover client.

Home retains server order across Trending on ListenBrainz (artists, albums and songs), New releases,
Artists like yours, Public playlists and six Recent wishes. Previews read twelve rows; full lists
read twenty through explicit Load more. Playlist positions preserve duplicate recordings and leave
missing recording MBIDs visible without a wish action. Wishes retain shelf or playlist provenance
through the existing outbox. Existing artist/album and scoped library routes remain the targets.
Cached content retains its age beside refresh failure. Empty success and unavailable content have
separate states. Credits read Data from MusicBrainz and ListenBrainz.

## Hosted proof wiring

A full apple-ci dispatch runs both the legacy step-2 and shelf inventories in these jobs:

| Job | Legacy cases | Shelf cases |
| --- | ---: | ---: |
| `discover-wishlist-macos` | 6 | 3 |
| `discover-wishlist-iphone` | 5 | 2 |
| `discover-wishlist-ipad` | 5 | 2 |

The iPhone and iPad compile step prepares the suite once, then each fixture proof uses
`DULCET_DISCOVER_PREBUILT=true`. iPad destination resolution retains its separate step. Each
inventory selects exact method names and validates finalized results, platform identity and
revision-bound fixture effects. Zero cases, missing cases or unexpected cases fail.

The PR's core-ci `discover-wishlist-android` job runs the original ten phases and the six shelf
phases against separate disposable fixtures on the same emulator. Its result guard requires the
exact inventory, with XML, fixture receipts and foreground captures uploaded. Shelf outputs use
separate directories beneath each job's existing artifact root.

Job envelopes allow both inventories and their independent setup: Mac 95 minutes, iPhone 135,
iPad 171 and Android 70. Shelf step caps are Mac 30, iPhone 35 and iPad 45 minutes. iPhone/iPad
compilation retains its separate 20-minute cap; Android shares a 40-minute emulator step.
The iPad envelope includes main's separate six-minute destination-resolution step; both proof
inventories use that resolved destination. These
are finite upper bounds, not observed durations or retries.

## Evidence and feature status

Android's accepted local emulator proof executed six phases with zero failures, errors or skips
on API 34, source `54570627653840c097ce4f155e2f6ac7d401e4cb`. Its 32 native JPEG captures have
32 foreground receipts. All acknowledged mixed, empty, stale and unavailable variants, retained
cache/offline/reconnect, source-aware wishes, paging, duplicate positions, missing metadata and
positive Navidrome absence are described in the Android handoff. Integration changes do not alter
Android application or host-test sources.

Mac shelf proofs reran on merged source `3ffa46b3599946ed13720d76c81cc2148f429bad`: three
passed, zero failures, errors or skips. Finalized bundle and XML agree. The retained
`integration-mac-shelves` artifacts contain `counts.json`, `validated-report.json`, `proof.xcresult`,
`junit.xml`, invocation and fixture receipts, and eleven native JPEG renders. The fixture cleanup
receipt confirms five owned children reaped and its temporary root deleted. Later integration
commits change documentation and feature declarations only.

The iPhone and iPad shelf proofs compile but have never executed. Their first native runtime
results belong to the hosted merge dispatch. FEATURES promotes `discover.shelves` to shipped only
for Mac and Android, and to partial with named hosted proof targets for iPhone and iPad. Each of the
four `discover.pages` cells gains public-playlist evidence while remaining partial for the remaining
native album-completeness matrix. Existing step-2 evidence is retained. TV cells are unchanged.
No device execution is inferred from type checking or a build.

## Android host soaks

- Core [40449779652](https://github.com/legitimate-apps/dulcet/actions/runs/40449779652):
  completed successfully, `SOAK RESULT passed=20 failed=0`, source `040cd181`.
- Phone DEV [40454579204](https://github.com/legitimate-apps/dulcet/actions/runs/40454579204):
  still in progress at the integration status read, source `80f68fde`, twenty repetitions requested.
- Phone PROD [40454592593](https://github.com/legitimate-apps/dulcet/actions/runs/40454592593):
  still in progress at the integration status read, source `80f68fde`, twenty repetitions requested.

These are the three runs attached to this branch. No fourth run was supplied or found on the
Apple branch. No new host-test source change requires another soak. Pending runs were not watched,
and no apple-ci dispatch was made during integration.

## Merged-tree checks

| Executed suite | Tests | Failures | Errors | Skipped |
| --- | ---: | ---: | ---: | ---: |
| Core JVM | 1,319 | 0 | 0 | 0 |
| Core Android host | 1,635 | 0 | 0 | 0 |
| Core macOS Arm64 | 1,433 | 0 | 0 | 0 |
| Android phone DEV | 306 | 0 | 0 | 0 |
| Android phone PROD | 306 | 0 | 0 | 0 |
| Android TV | 105 | 0 | 0 | 0 |
| DulcetKit, serial macOS | 434 | 0 | 0 | 0 |
| Native Mac shelf proof | 3 | 0 | 0 | 0 |

The Gradle test tasks were forced to execute, and counts were summed from their result XML.
DulcetKit ran serially from fresh internal DerivedData; its finalized xcresult summary and JUnit
agree on 434 passing tests. Its temporary DerivedData was removed after completion.
The local JVM compiler-tier workaround described in the Android handoff was retained; the Linux
soaks use their normal JVM settings. Metadata, AAR, license and iOS native test compilation gates
also passed. XCUITest source type checking and pinned XcodeGen regeneration with negative controls
passed. Parity validates 44 feature rows, migration validates fourteen fixtures through schema v14,
CI policy validates thirteen workflows, and release policy passes.

DulcetMac, DulcetiOS and DulcetTV each passed build-for-testing on the merged tree.
The proof-runner controls pass 22 tests. Additional Android controls reject short or unexpected
phase inventories and invalid foreground captures. The full CI-policy control suite passes; targeted associations pass 18 legacy and 12 shelf
controls. Legacy Apple selection is now explicit: adding
shelf methods to the same suite can neither expand the legacy run nor satisfy the shelf run with
legacy results.

The merged Mac proof emitted:

```text
DISCOVER_SHELVES_MAC mixed=true shelfPaging=24 shelfSource=trendingArtists
DISCOVER_SHELVES_MAC playlistPositions=24 duplicates=true missingMbidUnwishable=true keyboard=true resize=true playlistSource=true recentWishes=true
DISCOVER_SHELVES_MAC empty=true cachedFailure=true uncachedFailure=true variants=light,dark,largest navidromeHidden=true featureCalls=0
```

The Apple handoff's visual-review and capture limits still apply: native renders do not establish
vibrant toolbar contrast or effective Mac text scaling. iPhone and iPad native shelf runtime,
including the actual iPad compact-window transition, remains unproven until hosted execution.
Hosted startup contention can still fail a finite step; no retry-to-green is introduced.
