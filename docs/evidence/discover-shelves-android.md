# Android phone Discover shelves

The Android phone uses the locked [step-3 contract](../superpowers/plans/2026-10-27-discover-shelves.md) through its existing account-owned Discover session. The Kotlin core and locked shell interface are unchanged. Android TV has no new destination or requests.

## Presentation and navigation

Discover sits between Listen Now and Library when the server advertises Discover v1. Home has Trending on ListenBrainz (Artists, Albums, Songs), New releases, Artists like yours, Public playlists and Recent wishes. The core supplies 12 entries per home shelf and 20 per full page. Home uses horizontally scrolling 280 dp cards; full shelves, public playlists and playlist entries use vertical lists. Each shelf keeps independent loading, age, empty, error and retry controls. Source failure keeps cached rows and labels their age. Public playlist search submits explicitly; continuation uses Load more, with no automatic prefetch or refresh timer.

Artist and album rows open existing Discover pages. A held artist or song with one scoped reference can use the existing library route; an album additionally needs known completeness. Partial or unknown albums keep their metadata page and explicit library action. Playlist positions preserve duplicate recordings; a missing recording MBID leaves a visible row without a wish button. Shelf and playlist wishes use the existing outbox with their exact source ID. Entering home restores the default Wishlist filter, and recent wishes open Wishlist using the stable row key.

Page readers survive Back within the account. Saved page counts replay the pages already loaded after activity recreation or rotation, and the public playlist query is saved. Separate title and wish controls allow large text to wrap; artwork is decorative. Headings, native Back/query controls and named Wish for actions supply accessibility semantics. Navigation labels retain the existing bounded scale, fit the complete single-line name without wrapping and center it in the control; body text follows system font size.

## Disposable emulator proof

Run only against the finite disposable fixture on an explicitly leased phone emulator:

```sh
tools/ci/with-discover-wishlist-fixture -- \
  tools/ci/run-discover-wishlist-android --shelves --no-build --output build/discover-shelves-proof
```

Build the DEV application, DEV instrumentation APK and `:server:installDist` before `--no-build`. Supply the wrapper's pinned Navidrome binary as documented in the [fixture guide](../../tools/discover-wishlist-fixtures/README.md). The runner rejects a missing disposable marker, a physical device, an unspecified emulator or non-loopback fixture URLs. Existing step-2 proof selection remains available without `--shelves`.

The four instrumentation identities execute six invocations: artist/partial-album/scoped-library routing, source wishes, stable recent-wish activation and held-song playback with the player bar, mixed shelf/cache/offline states, healthy Navidrome control, and accessibility/restoration in light, dark and largest dark text (system font 2.0). Their evidence includes per-invocation JUnit XML, request traces, revision-bound fault acknowledgements, reader receipts and compressed native JPEGs with foreground-package receipts. Accessibility evidence covers semantic labels, keyboard activation and reachable actions; it does not claim a running TalkBack service.

Initial device regression evidence caught a held artist with `completeness=unknown` being routed to metadata despite a single scoped artist reference. The shell now applies track-completeness requirements only to albums. The initial failing invocation is retained alongside final evidence.

## Observed device results

The final DEV APK and instrumentation were built from production/proof source `54570627`. On the reused API 34 ARM64 phone emulator, the runner emitted these executed JUnit results:

| Identity | Invocations | Failures | Errors | Skips |
|---|---:|---:|---:|---:|
| `shelvesRoutesAndSourceWishes` | 1 | 0 | 0 | 0 |
| `shelvesMixedAndCachedOffline` | 1 | 0 | 0 | 0 |
| `shelvesNavidromeControl` | 1 | 0 | 0 | 0 |
| `shelvesAccessibilityAndRestoration` | 3 | 0 | 0 | 0 |
| Total | 6 | 0 | 0 | 0 |

Run `android-shelves-full4` retains 32 native JPEGs, each at most 800 pixels on its long edge, plus 32 foreground receipts naming the DEV application. Its mixed-state proof records all six shelf effects on the armed revision: non-empty artists/releases/playlists, stale albums with original source age 7200 seconds, unavailable songs and empty similar artists. A later acknowledged unavailable refresh keeps cached rows, and an acknowledged offline transport failure keeps those rows through reconnect. The healthy Navidrome control observes actual application catalog requests before asserting the missing Discover destination and zero feature requests.

Routing assertions use independent wire witnesses for opaque IDs. The held-song assertion waits for the production playback observer's exact scoped raw ID, then closes the full player and observes Discover with the mini player. Other assertions cover the 24th shelf row after activity recreation, the 24th playlist and entry, duplicate positions, the unwishable entry, persisted shelf/playlist wish sources, a pending shelf wish, recent-row activation, keyboard Enter, and landscape/portrait restoration. All requests use only the wrapper-owned disposable servers and recorded upstream responses.

The independent Opus 5.5 medium visual review found that API 34 drew only “Listen” at the left edge, while the API 35 native-graphics host test accepted the full label. An actual-device text-layout assertion reproduced the failure: visible character end 6 instead of 10, and glyph center 36.087 pixels left of the control center. Disabling soft wrapping lets autosizing fit the whole name; explicit centering places it correctly. Final light, dark and largest-text device receipts all report visible end 10, glyph/control center 81 pixels, and the complete glyph span 4 through 158 inside the control's 0 through 162 pixel bounds. The native host tests and device proof retain these regressions.

## Observed task checks

These are executed test counts from JUnit XML, with no skipped tests. The selected conformance class resets the disposable fixture before opening its real reader; running it after the UI phases cannot reuse their wishes. Both final fixture wrappers report all five owned children reaped and their temporary roots deleted. The owned emulator was stopped after the final UI proof and its lease released.

| Task | Tests | Failures | Errors | Skips |
|---|---:|---:|---:|---:|
| `:core:jvmTest` | 1319 | 0 | 0 | 0 |
| `:core:testAndroidHostTest` | 1635 | 0 | 0 | 0 |
| `:core:macosArm64Test` | 1433 | 0 | 0 | 0 |
| `:android:app:testDevDebugUnitTest` | 306 | 0 | 0 | 0 |
| `:android:app:testProdDebugUnitTest` | 306 | 0 | 0 | 0 |
| `:android:tv:testDebugUnitTest` | 105 | 0 | 0 | 0 |
| `:core-conformance:jvmTest` | 1 | 0 | 0 | 0 |

The final phone variants used local JVM options `-XX:TieredStopAtLevel=1 -XX:ReservedCodeCacheSize=256m` after a JDK C2 compiler crash in Robolectric's SQLite path interrupted an earlier DEV worker. This workaround changes the local compiler tier, not test selection. The Android Linux soaks use their normal JVM settings; no soak outcome is claimed here.

The required core metadata, Android AAR, license and iOS simulator compile tasks pass. These are compile/package checks and have no test count. Android TV host tests compile and execute without adding TV UI. Parity and CI policy checks pass; no feature-support cell is promoted in this part.

The same Opus 5.5 medium review thread then inspected five new final JPEGs: light and dark home, largest-text home and playlist, and a full shelf tail. All five passed. The review confirms the complete centered Listen Now label, distinct Discover/Browse icons, card and action fit, readable contrast, largest-text wrapping and separate duplicate playlist rows. This visual verdict complements the executed native layout and semantic checks; it does not establish networking behavior.

## Dispatched host soaks

Both 20-repetition runs were dispatched on `feat/discover-shelves` at `80f68fde`, whose implementation and proof sources match `54570627`. They use normal Linux JVM settings and filters `*DiscoverPagedReaderTest,*PhoneDiscoverShelvesTest,*PhoneNavigationTest,*PhoneDiscoverTest,*PhoneWishlistTest`. The last two cover the shared presentation double and existing wish actions. Dispatch is observed; outcomes are not claimed or watched here.

- DEV `:android:app:testDevDebugUnitTest`: [40454579204](https://github.com/legitimate-apps/dulcet/actions/runs/40454579204).
- PROD `:android:app:testProdDebugUnitTest`: [40454592593](https://github.com/legitimate-apps/dulcet/actions/runs/40454592593).

## Integration

The Apple and Android shells are combined in the [step-3 integration evidence](discover-shelves-integration.md),
which records merged-tree checks, feature cells, CI proof wiring and the one-time soak status read.
