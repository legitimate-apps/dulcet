# Offline My Library, automatic retention and streaming cache

The Kotlin core owns retention, scheduling order, local playability, validation, storage limits and
LRU decisions. Native shells own settings presentation and the operating system's file/network and
playback services. Cache identity uses the same account/server instance, raw item and profile tuple
as downloads; a URL and username alone cannot identify cached media.

## Evidence boundaries

OBSERVED at shared-core commit `f59522bc`: the final gate executed 1,145 JVM tests and 1,224 macOS
native tests, with zero failures, errors or skips. Both runtimes passed all 24
`OfflineLibraryPolicyTest`, 18 `DownloadPolicyTest`, seven `OfflineLibraryRetentionSafetyTest` and
six `PlaybackQueueOfflineTest` cases. SQLDelight migration verification, metadata packaging, license
checks and iOS simulator test compilation also passed. The macOS native count includes native-only
facade contracts; it is separate from running the Mac application.

A passing core test is evidence of its named core contract, not of a native screen or real playback
engine. No iPhone observation stands in for iPad or Mac, and no Android host test stands in for
emulator playback. Platform implementation and runtime verification are tracked below.

| Claim | Acceptance path | Runtime evidence |
| --- | --- | --- |
| Explicit downloads survive removal and disabling automatic downloads | `OfflineLibraryRetentionSafetyTest` drives retention changes, promotes files, reopens the engine and reads back the local plan | OBSERVED on JVM and macOS native; seven-case retention acceptance suite |
| Cache deletion is scoped to account and ownership | `OfflineLibraryRetentionSafetyTest` clears one account with duplicate raw IDs across two accounts and checks retained local files | OBSERVED on JVM and macOS native; seven-case retention acceptance suite |
| Cache cannot publish incomplete ranges or truncation | `OfflineLibraryRetentionSafetyTest` writes incomplete responses and sparse ranges and checks that no file becomes playable | OBSERVED on JVM and macOS native; complete coverage is required even when tail bytes arrive first |
| Playing/preloaded cache files survive eviction | `OfflineLibraryRetentionSafetyTest` shrinks the account budget to zero while two identities are protected, then releases protection and verifies eviction | OBSERVED on JVM and macOS native; seven-case retention acceptance suite |
| Offline queue construction and continuation use playable items | `PlaybackQueueOfflineTest` changes eligibility after saving an online queue, checks shuffle, selected identity, preload, next/previous, natural end and finite repeat-all termination | OBSERVED on JVM and macOS native; six queue cases passed |
| Android phone automatic retention | Real album addition through My Library, WorkManager completion, explicit Keep Download, membership removal and automatic-off | OBSERVED on the API 34 phone emulator: four local album files; automatic-only files deleted; explicit file survived both actions |
| Android phone same-response cache and offline playback | One remote Media3 playback, validated cache, real HTTP relay failure while OS network remains available, then local playback | OBSERVED: one media GET, 24,334 validated cache bytes; offline local playback advanced 1,934 ms with zero server bytes; the final selected class passed two cases in 35.633 s |
| Android phone offline presentation and clear | Publish cached and unavailable My Library songs after relay loss, construct queue, activate Clear Cache | OBSERVED: unavailable song excluded from queue; cached file removed by Clear Cache |
| iPhone/iPad/Mac automatic download and offline UI | Platform-specific `DulcetMyLibrary*UITests/testBrowseAddRemoveRelaunchAndOfflineReplay` adds a real album, waits for downloaded tracks, cuts the HTTP relay and starts playback | Each platform executes its own selector in `apple-ci`; final run results are required before merge |
| iPhone/iPad/Mac playback cache | Platform-specific `DulcetAppleDownloadIntegrationTest/downloadTriggerPromotesValidatedResponseAtomically*` requires actual AVPlayer media time to advance while streaming and again from the promoted cache with network clients closed; separately drives the playback loader with tail/overlapping ranges and identical-byte replay | Each platform executes its own selector in `apple-ci`; final run results are required before merge |
| iPhone/iPad native settings | `DulcetDesignTourUITests/testOfflineMusicSettingsPersistAcrossRelaunch` drives Automatic Downloads off, relaunch persistence, on and Clear Cache | The iPhone and iPad Home proof jobs execute this selector independently |
| Mac native settings | Shared settings presentation, core bridge and artwork executor tests | No Mac settings UI interaction is claimed; this remains partial |
| Android TV native interaction | `AndroidTvMyLibraryProofTest/automaticLibraryRetentionAndSameResponseCachePlayOffline` drives the production TV shell with D-pad activation | OBSERVED on the API 34 leanback-only emulator: one passing case in 10.956 s; four automatic files; explicit retention survived removal/off; real D-pad toggle; one GET/24,334-byte cache; after relay loss local playback advanced 1,848 ms without server bytes; unavailable queue item excluded; Clear Cache removed cached file |

The Android wrapper selectors execute only in the advertised Dulcet fixture stage. The REST-only
stage excludes these selectors entirely, so a skipped invocation cannot masquerade as parity
evidence. Existing REST My Library proofs continue to execute. The strict result verifier requires
both the existing split proof and the new offline proof to pass.

Storage-budget acceptance includes trimming evictable artwork before streamed media, a prospective
budget plan that does not mutate the setting before the executor acknowledges the trim, and Clear
Cache preserving downloaded/current covers and protected media. Apple artwork statistics are
read-only during cold inventory, so inventory cannot delete covers before the core restores pins.

Saved-account local playback has separate source/host evidence: the production controller checks a
local plan before the contact-closed guard. `AndroidPlaybackControllerTest` includes
`aSavedAccountNotYetConnectedPlaysADownloadAndSaysTheNextSongNeedsReconnect`; the shared
phone/TV `LibraryPlay.playTracks` filters core-published `UnavailableOffline` rows before Play or
Shuffle constructs its queue. The device proof above observes server-cut playback in the connected
foreground lifecycle. A fresh saved offline relaunch was not separately driven on the emulators.

## Platform scope

Apple TV retains the existing v1 no-download contract and network playback. This change skips
its streaming cache and offline controls because the tvOS shell has no file-backed offline
executor; it does not claim a durable offline media store there. Android TV supports downloads and shares the Android core
executor and policy; TV interaction evidence remains distinct from phone evidence. Both platform wrappers are also selected in the full CI fixture run.

## Validation required before merge

Run the changed common/core contracts and platform integrations, soak changed Android host tests
for 20 Linux repetitions, and dispatch the complete Apple workflow on the final branch head.
Independent review covers the file deletion and playback boundaries before merging.
