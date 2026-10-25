# Discover and Wishlist client integration

Step 2 adds Search Everywhere, artist and album metadata, durable personal wishes and Library
Wishlist on iPhone, iPad, Mac and Android phone. Shelves and playlist pages remain step 3;
television presentation remains step 4. Both TV apps still compile. No acquisition automation,
OAuth or new server protocol is introduced.

This record distinguishes accepted finalized results from diagnostic failures and cancellations.
The corrected candidate is `375b991d`, incorporating main through `a452a7a8` and the pressure
admission dependency below. All four candidate-native surfaces have finalized passing results: iPhone 5/5,
iPad 5/5, Mac 6/6 and Android ten phases across five methods. Earlier mobile reds and their observed causes are retained
below. Diagnostic dispatches cannot replace the final plain full Apple release gate.



## Integrated native confirmation and artifact handoff

The subsequent full [Apple run 40202061025](https://github.com/legitimate-apps/dulcet/actions/runs/40202061025)
on `a7e2d6b8` concludes failure. Its dedicated Mac suite passes 6/6 and iPhone passes 5/5,
each in a finalized xcresult with zero failures, errors or skips. The dedicated
[iPad job 121217562182](https://github.com/legitimate-apps/dulcet/actions/runs/40202061025/job/121217562182)
finalizes five executions, four passes and one failure, with zero errors or skips. The failed
`testAccountSwitchAndIdentityReplacementFence` reports `Missing Search Everywhere scope`
at 120.823 seconds. The other four cases pass, including
`testIPadSidebarSelectionSurvivesWishlistDetailAndResize` at 351.921 seconds. Device: iPad (A16),
iOS 26.5 (23F77), `3EDBE328-2B49-460D-AD93-9380F8C7EDC5`. Artifact:
`discover-wishlist-ipad-40202061025-1`, `native/proof.xcresult`.

OBSERVED: XCTest's Search activation point `(126, 364.25)` is inside the button's
`(26, 338, 200, 52)` frame. UIKit dispatches touch events to the app and XCTest receives its
touch-completion event at 16:10:46 UTC. The subsequent hierarchy still selects Listen Now.
The first catalog snapshot takes roughly 44 seconds and the SpringBoard interruption check
roughly ten seconds. The artifact contains no host-pressure record or app stack sample, and
this first case did not enable navigation receipts. Whether SwiftUI dropped the action or a
later source snapshot replaced the destination is unresolved; pressure is not established by
elapsed time alone. No product fix is claimed for this failure.

The Discover harness now uses the shared `host-pressure` sampler and `isolate-simulator`
post-boot admission and first-launch warm-up, retaining their raw diagnostics in its own native
artifact. Every Discover test launch enables the existing opt-in navigation receipts. Assertions,
selection input and test deadlines are unchanged. The runner wiring control fails against
`a7e2d6b8` with `native launch bypassed pressure diagnostics`; five admission controls separately
fail before the helper exists. The corrected controls require exact-device warm-up, reject
missing builds or failed admission, and exercise TERM cleanup. This closes an observed harness
and diagnostic gap; a new-head native result is still required.

The same run's baseline iPad My Library case times out at its existing 300-second bound under
recorded host pressure; the baseline iPhone proof step succeeds but artifact upload fails DNS
resolution. Neither is accepted as a passing final gate. These failures remain recorded while
the shared startup-admission dependency lands on main. No old-head diagnostic rerun substitutes
for the final updated branch's required checks and conformance dispatch.

On `4eb79877`, the plain full [Apple run 40191182235](https://github.com/legitimate-apps/dulcet/actions/runs/40191182235)
finalizes every platform, conformance and native job successfully. The aggregate then fails its
executed-evidence check. This is an artifact-directory handoff failure, not a native test failure.
All Apple native counts below are read directly from finalized, noninterrupted xcresult summaries
and test trees, matched to their XML, with xcodebuild exit zero. Android uses instrumentation XML.
Every dedicated native result has zero failures, errors or skips.

| Surface | Workflow run / native job | Passing executions | Device and runtime | Captures |
| --- | --- | ---: | --- | ---: |
| iPhone | [40191182235 / 121182044024](https://github.com/legitimate-apps/dulcet/actions/runs/40191182235/job/121182044024) | 5 | iPhone 17, iOS 26.5 (23F77), `79164188-6D2E-441E-A806-056A14711F9B` | 23 |
| iPad | [40191182235 / 121181073849](https://github.com/legitimate-apps/dulcet/actions/runs/40191182235/job/121181073849) | 5 | iPad (A16), iOS 26.5 (23F77), `31A03367-4FE9-4172-888A-633C6F94AD12` | 26 |
| Mac | [40191182235 / 121178487656](https://github.com/legitimate-apps/dulcet/actions/runs/40191182235/job/121178487656) | 6 | Apple Virtual Machine 1 arm64, macOS 26.6.2 (25G83), `9560c9774de9d226f8cf112ceeeaa4b26af27a83` | 25 |
| Android | [40191174154 / 121162538516](https://github.com/legitimate-apps/dulcet/actions/runs/40191174154/job/121162538516) | 10 phases across 5 methods | API 34, `sdk_gphone64_x86_64`, `emulator-5554` | 32 |

Apple artifacts are `discover-wishlist-<mode>-40191182235-1`, with `native/proof.xcresult`,
`native/junit.xml`, `native/acknowledgements.jsonl` and `native/captures/`. Android's artifact is
`discover-wishlist-android-40191174154-1`, with instrumentation XML and 32 positive foreground
capture receipts. Its PR checkout `12abee25c306dda7c5a955e39064063b7cd5bec3` has tree
`542ef0e512edba5939ec984c969ba805e4593564`, identical to source head `4eb79877`.

The iPhone and iPad suites serve the 120-second 429 at revision 7 and return distinct new/old
query payloads at 10. iPhone delivers lost operation `8d0cb4b9-cc09-4280-807f-f7b1f936d0f7`
at 25 and completes actual scans 27/28; iPad delivers `a5804097-0bf9-4af2-adc7-05c70bc7ce72`
at 27 and completes scans 29/30. Both old-A replies are removed/unapplied. Generations
`wi_2225b1db-1fa4-4532-870c-943e54adefe9` and `wi_5b34e88b-59b1-4662-80b6-f93038142782`
respectively survive the old reply. Removing the Arrived wish retains exact library items
`77b61c68-2c53-47be-988a-275a15025472` and `1e1b5443-a9c8-4f0a-8bb8-a0e1eaf61032`.
The iPad sidebar/held-album/resize/Back/durable-pending case and native iPhone consent Cancel
with its VoiceOver label pass. Each Apple positive Navidrome control reads an album and observes
zero feature RPCs.

Mac acknowledges the held window-close detail at 26 before closure and its release afterward;
lost operation `4f402285-8ed6-4b0d-a283-972a533683d1` is delivered at 29 and scans 31/32 complete.
The old receipt is removed/unapplied and B survives; intentional Arrived removal retains
`39a6bcca-6c76-42cc-a28d-f466465af87e`. The hosted durability scope is the production window,
reader, session and SQL reopen inside the XCTest hosting process.

Android delivers lost operation `293ba0ba-86b8-4de6-bd3a-2ce25a5ccee9` at 6 and completes
scans 8/9. Both held remove/add mutations at 10 are delivered. Retry/stale controls use 13/15;
both old-A replies are removed/unapplied and B `wi_662d38fd-e4c5-478f-8461-3a1f565d0040`
survives. Navidrome records 14 positive album reads in 40 requests and zero feature RPCs. The old
app PID was already absent before the explicit stop; the native pending/relaunch proof passes,
and no prior death is attributed to that later stop.

The same full Apple run verifies packages Mac 415, iPhone 397, iPad 397 and TV 377, one Keychain
case per simulator platform and one iPad layout case. Darwin core records Mac 1,415 / iOS 2 /
TV 2; server conformance Mac 74 / iOS 64 / TV 64. Its app proofs record Mac 30, iPhone 6, iPad 6
and TV 23. Device conformance adds iPhone 28 XML plus two retained native interaction passes,
iPad 20 XML plus three interaction passes. All these groups have zero failures, errors or skips.
[PR Apple 40191174121](https://github.com/legitimate-apps/dulcet/actions/runs/40191174121) is green
with the same package counts; conformance and Discover are correctly unplanned on that PR event.

OBSERVED: [aggregate job 121192921250](https://github.com/legitimate-apps/dulcet/actions/runs/40191182235/job/121192921250)
fails with `evidence test did not execute` for the Mac Everywhere identity, `nearby=[]`. Each
single-directory parity upload contains a bare `TEST-...xml`; the aggregate downloads it at the
temporary root while reading `dulcet-discover-<mode>-junit`. The new actual-workflow regression
fails first with `macos Discover JUnit artifact directory missing`. The correction restores each
platform directory at download. CI policy also distinguishes a literal directory upload from a
wildcard or multiple-directory upload, and rejects missing, root, sibling, duplicate or renamed
downloads and mismatched producer paths. Restoring the actual hosted files resolves all 192
cited Apple identities across 251 reports without changing `verify-parity-evidence`. The fresh
full workflow on the artifact-wiring correction remains required before readiness.

The six exact-`4eb79877` Linux soak scopes each verify every requested class in all twenty
repetitions, with zero XML failures, errors or skips. Executions include repetition.

| Scope | Run / job | Executions / XML reports | Exact result |
| --- | --- | ---: | --- |
| Core Android, 17 classes | [40191188754 / 121162365639](https://github.com/legitimate-apps/dulcet/actions/runs/40191188754/job/121162365639) | 3,740 / 340 | SOAK RESULT passed=20 failed=0 |
| JVM, four classes including DiscoverPagesTest | [40191190474 / 121162370213](https://github.com/legitimate-apps/dulcet/actions/runs/40191190474/job/121162370213) | 600 / 80 | SOAK RESULT passed=20 failed=0 |
| PROD, seven classes | [40191192157 / 121162375818](https://github.com/legitimate-apps/dulcet/actions/runs/40191192157/job/121162375818) | 780 / 140 | SOAK RESULT passed=20 failed=0 |
| DEV production reader | [40191193903 / 121162380250](https://github.com/legitimate-apps/dulcet/actions/runs/40191193903/job/121162380250) | 560 / 20 | SOAK RESULT passed=20 failed=0 |
| TV My Library | [40191195846 / 121162392790](https://github.com/legitimate-apps/dulcet/actions/runs/40191195846/job/121162392790) | 120 / 20 | SOAK RESULT passed=20 failed=0 |
| Discover real reader, fresh fixture per repetition | [40191198094 / 121162391945](https://github.com/legitimate-apps/dulcet/actions/runs/40191198094/job/121162391945) | 60 / 20 | SOAK RESULT passed=20 failed=0 |

The artifact handoff changes CI policy, workflow destinations and this record; native/client/core
sources are unchanged. Fresh final-head confirmations are recorded in the PR once they finish.

## Detail lifecycle regression

OBSERVED: the focused [iPad job 121139750680 in 40183080857](https://github.com/legitimate-apps/dulcet/actions/runs/40183080857/job/121139750680)
finalizes one executed case, one failure, zero errors or skips on `7ea0ba2b`. Device: iPad (A16),
iOS 26.5 (23F77), `89BAE237-119D-4CC4-BB75-37DC2EBD4CE7`. The artifact is
`discover-wishlist-ipad-40183080857-1`, `native/proof.xcresult`. The complete retained route
survives the compact transition. Detail receipts 35 and 37 show two different models requesting
the same album and nil edition through the same reader. The second closes at 39; at 41 the
surviving model accepts a cancelled completion. It has no album to render. Core's previous
`DiscoverPages.album` unconditionally advanced the album epoch and cancelled all older operations,
even for the identical request.

`closingDuplicateDefaultAlbumReadDoesNotCancelSurvivingPage` drives that overlap through the real
core: duplicate nil-edition reads, closing the duplicate, then releasing both responses. Before
`4f820b88`, the nine-case JVM class records one failure because the surviving caller receives
`Failed` rather than `Value`. Afterward all nine pass. The companion A/A/B/A regression retains
revocation of every older edition, including late responses when the selection returns to A.
Requested identity is tracked separately from the response's resolved default edition. Existing
signatures stay unchanged. The complete affected JVM, macOS Arm64 and Android host suites then
record 1,304, 1,415 and 1,598 passing cases respectively, zero failures, errors or skips. The
corrected hosted core gate below independently confirms JVM and Android. The corrected native
iPad suite below confirms the same duplicate-reader sequence now succeeds.

OBSERVED: the same iPad run records `pageBar present=false` at 18 for its concrete Search album.
The [iPhone job 121138088163](https://github.com/legitimate-apps/dulcet/actions/runs/40183080857/job/121138088163)
finalizes five passes with zero failures, errors or skips, iPhone 17 / iOS 26.5 (23F77),
`683D98DA-1D5A-4F3D-8CCD-4E26A1CA4B51`, 23 captures. Its same album path receives the existing
floating configuration, and receipt 54 records `present=true required=true` after Play. The
candidate correction supplies that same configuration above the iOS split/compact presentation
boundary. The corrected iPad suite below observes `present=true`, the native bar and the exact
scoped playback track after the unchanged held-song trigger.

## Platform pressure dependency

OBSERVED: [platform job 121124432852 in 40178357088](https://github.com/legitimate-apps/dulcet/actions/runs/40178357088/job/121124432852)
fails before the iPad tests at post-boot destination confirmation. The acknowledged device is
`687AB7EE-DF9A-4A96-878E-7509AB01A80C`, but every bounded Xcode query lists only placeholder
iOS destinations. The three-core, 7-GiB hosted VM records load1 449.9 during this window,
maximum resident swap 1,695.38 MiB and post-boot free memory as low as 43.67 MiB. Precise
CoreSimulator causation remains inferred; no iPad test result is accepted for this prerequisite
failure. The artifact is `dulcet-apple-failure-diagnostics-apple-platform-40178357088-1`.

Merge `90bd99fd` incorporates the existing pressure-admission implementation from
[PR #391](https://github.com/legitimate-apps/dulcet/pull/391), source `58943d75`, as an integration
dependency. Its bounded gate requires a full quiet minute before the same-device destination
query; its established device warm-up and admission retain the original production proof launch.
The GitHub PR itself remains separate. The two textual conflicts preserve its admission/phase
markers and this branch's exact same-device confirmation and negative controls. All pressure,
isolation, readiness, executed-shell controls and CI policy pass. A generic app/test build compiles
the merge without booting a device. The final full Apple run still must demonstrate admission,
destination confirmation and native execution together.

## Integration decisions

OBSERVED: the branch contains core `3f9c1e41`, Android `6d8f25cf` and Apple `629fd6aa`,
with main through `a452a7a8`. The core commit is an ancestor of both client inputs. Main's
changes remain authoritative for their owned contracts; the client behavior is composed with them.

| Overlap | Resolution and reason |
| --- | --- |
| Saved-account lineage and reconnect | Keep main's authorization epochs, rejected-epoch delivery lock and post-await checks. Discover participates in the existing reader authorization and retirement instead of adding an independent account fence. |
| Apple consent and TV/test helpers | Keep the native consent alert with an accessible Cancel, main's settled TV focus helper, coherent reader observations and exactly-once playlist assertions. Main #356 merges without a textual conflict; its actual-input and pending-read preconditions remain intact. |
| Schema and fixture versions | Keep main #386's queue migration, schema 13, fixture v13 and SQLite writer pin. Move the additive Discover migration and fixture to schema 14; verify pending My Library intents, scoped media ownership and queue context survive 13-to-14. |
| Compose and SDK setup | Keep main #368's Compose test rule and #367's bounded SDK installer. The new Android job invokes the same installer and checks its booted image. Both merge cleanly. |
| Android connection/playback recovery | Keep main #372's controller, remote proof and regression sources unchanged. The revision-record insertion conflict retains both complete entries. The accepted Android client visual structure is preserved. |
| Fixtures and proof runners | Retain both client modes, distinct old/new payloads, acknowledged held detail, and committed remove-before-add ordering. Keep main's existing conformance and baseline checks in addition to the new finite native jobs. |
| Hosted pressure admission | Incorporate #391's committed quiet-window and first-launch implementation as a dependency after the platform boot inventory red. Retain exact selected-device confirmation and both sets of rejection controls; final full Apple execution remains required. |

The detailed integration and red/green records below distinguish textual conflicts from clean
merges and harness corrections. No client signature is removed; the two core exports are additive.

## Current hosted iPhone native result

[Job 121147327659 in 40186168478](https://github.com/legitimate-apps/dulcet/actions/runs/40186168478/job/121147327659)
finalizes all five `DulcetDiscoverWishlistUITests` identities passed on `375b991d`, zero failures,
errors or skips. Direct xcresult summary/test-tree reads match XML; the bundle is not interrupted
and xcodebuild exits zero. The device is iPhone 17, iOS 26.5 (23F77),
`2E8E1915-A549-404A-AA2F-3EC66C8763D2`, with 23 captures in
`discover-wishlist-iphone-40186168478-1`, `native/proof.xcresult` and `native/captures/`.
The Everywhere case observes the native player bar and exact scoped playback track after the
held-song trigger. Account-switch consent Cancel and its accessible label, largest-text labels
and the full offline/relaunch/removal/Arrived chain pass.

The fixture serves the 120-second 429 at 7, returns distinct new-before-old responses at 10,
delivers lost operation `829364e5-e537-42fb-9159-65e54a58c579` at 25, and completes scans
27 and 28. The native replay checks retain removed A and surviving B
`wi_349fc97f-27ab-472f-91a2-b9408544145d`; the later intentional Arrived removal retains
library item `60bf0eb9-55c0-440e-b514-00065628ef58`. Positive Navidrome browsing reads one
album and observes zero feature RPCs. These are candidate-source native results; the full
final-head release gate remains required.

## Current hosted iPad native result

[Job 121149529146 in 40186168478](https://github.com/legitimate-apps/dulcet/actions/runs/40186168478/job/121149529146)
finalizes all five `DulcetDiscoverWishlistIPadUITests` identities passed on `375b991d`, zero
failures, errors or skips. Direct xcresult summary/test-tree reads agree with XML; finalization
is not interrupted and xcodebuild exits zero. Device: iPad (A16), iOS 26.5 (23F77),
`8842DB57-F9BA-4495-ADB1-901E53AA26FF`. Artifact:
`discover-wishlist-ipad-40186168478-1`, `native/proof.xcresult`, with 26 compressed captures.

OBSERVED: the resize case repeats the failing sequence exactly. Receipt 34 requests the retained
`[wishlist, album]` path in the compact stack; 35 and 37 start identical nil-edition album reads
through the same reader. The duplicate closes at 39 and its cancelled callback is rejected at 40.
At 41 the surviving model now accepts success, where the red accepted cancelled. Native Back at
42 through 46 returns to Wishlist, and the surviving page closes with its album present at 47.
The case requires the exact album title, real compact tab shell and durable pending intent.
Compressed `iPad-Compact-Album.jpg` and `iPad-Compact-Wishlist.jpg` visibly retain that title,
Back control and pending wish. The Everywhere case separately records page-bar configuration
present at 18/21 and required at 42, then passes the native mini-player and exact scoped track
assertions. The opt-in receipts remain disabled in ordinary app use. The earlier account startup
failure does not recur; no separate causal fix is claimed for that initial-launch failure.

The fixture serves the 120-second 429 at 7, acknowledges held old query 10 and returns distinct
new before old, delivers lost operation `bc665dd2-8508-4cef-86db-eccb440a551a` at 27, and
completes scans 29 and 30. Old A remains unapplied/removed while
`wi_14ea56b5-0253-4705-9e94-1a541cb8ae60` survives. Intentional Arrived removal then retains
library item `e53c7915-1c68-47fa-927a-ad9884998c3f`. Positive Navidrome browsing reads one
album with zero feature RPCs. The candidate dispatch succeeds but intentionally skips baseline
legs; it does not replace the final plain full Apple release run.

## Current hosted Mac and Android native results

On corrected candidate `375b991d`, [Mac job 121147327611 in 40186168478](https://github.com/legitimate-apps/dulcet/actions/runs/40186168478/job/121147327611)
finalizes all six identities passed, zero failures, errors or skips. Direct xcresult reads agree
with XML; the bundle is not interrupted and xcodebuild exits zero. The device is Apple Virtual
Machine 1 arm64, macOS 26.6.2 (25G83), `900edd0528d83d285a06d644c8207f899ec1cc24`, with
25 captures in `discover-wishlist-macos-40186168478-1`, `native/proof.xcresult` and
`native/captures/`. The fixture serves the 429 at 7, delivers distinct new-before-old responses
at 10, acknowledges held and released window detail at 26, delivers lost operation
`5c1d0ace-cc21-4de4-85fd-c6a79b25499a` at 29, and completes scans 31 and 32. Native
assertions retain B through both old-A replays. The later intentional Arrived removal leaves
library item `761be2af-bf05-4b33-818d-615e06cc9061` present. Positive Navidrome browsing reads
one album and records zero feature RPCs. App-hosted relaunch recreates the persistent session and
window within the hosting process; it is not an operating-system process-death proof.

The [Android job 121147304534 in core-ci 40186171196](https://github.com/legitimate-apps/dulcet/actions/runs/40186171196/job/121147304534)
executes ten passing phases across five methods, zero failures, errors or skips, API 34,
`sdk_gphone64_x86_64`, `emulator-5554`, with 32 compressed captures and 32 positive foreground
receipts. Checkout `ec3bd74f1451608ea4cea6f76b9ec866aff7eb34` has the candidate's identical tree
`d9fd572879220c0c2d72dd8158dcd20779849ea1`. Query revision 2 returns new before old;
lost operation `cfc53390-2eab-4cb9-b0d3-7710786cb5c5` is delivered at 6 and scans 8 and 9
complete. Both old-A replays are unapplied/removed while
`wi_46d7992b-3850-4486-a6ab-3bdbfb28a13f` survives. The racing remove/add at 10 is held
and both mutations are delivered. Native 120-second retry controls and stale response use
revisions 13 and 15. Navidrome records nine positive album reads across 29 requests and zero
feature RPCs. PID 5988 is already absent before the explicit stop, so the prior death is not
assigned to that later command. Artifact: `discover-wishlist-android-40186171196-1`, under
`native/`. Full final integrated-head confirmation remains required.

## Current complete core gate

[Core-ci 40186171196](https://github.com/legitimate-apps/dulcet/actions/runs/40186171196)
and [parity 40186170989](https://github.com/legitimate-apps/dulcet/actions/runs/40186170989)
pass on `375b991d`. Uploaded XML records core JVM 1,304; core Android host 1,598; DEV and
PROD app host 277 each; TV host 100; server 317; API 61; Linux conformance 136; Android
conformance 65; production reader phone/TV 32/34; fixture/session/component 21 (16/3/2);
and Discover instrumentation 10. Every listed group has zero failures, errors or skips.
The 218 duplicate core XML reports are verified byte-identical and counted once.
Baseline phone separately has 37 cases, 35 passing and two existing fixture-only skips;
baseline TV has 38 cases, 35 passing and three existing skips. Neither has a failure or error.
The migration verifier stays enabled and passes in the ordinary core build.

## Candidate source checks

On `375b991d`, the local full DulcetKit package finalizes 415 passing test identities, zero
failures, errors or skips. Direct xcresult summary and XML agree; the parameterized execution
statistic is not added to the identity count. The host is Mac Studio arm64, macOS 27.0.1
(26A434), without an iOS simulator. The generic iOS app and test build also compiles the fresh
core framework and existing player configuration above the split presentation boundary without
booting a simulator. The dedicated native iPad proof above confirms its device effect.

The corrected `375b991d` source passes 16 fixture XML cases, 14 native report controls and six
process-owner cleanup controls. All four simulator-health controls, the pressure/isolation/startup/
readiness controls and full CI-policy self-tests also pass. The latter includes 32 finite fixture
invocation controls, eight shared-test compilation controls and seven guarded focused-selector
controls. The Android runner's foreground/parser controls pass. XcodeGen
2.46.0 output matches the committed project, and the self-test refuses a scheme pre-action, extra
scheme and edited generated plist. XCUITest source checking, CI policy, parity, OS-floor and
release-policy checks pass. The pressure-admission controls retain positive execution and reject timeout,
wrong device and failed warm-up before the proof command. Final-head gate results remain required and supply no counts until
completion.

## Measured mobile failures and candidate corrections

OBSERVED: the finalized mobile jobs in [40175100268](https://github.com/legitimate-apps/dulcet/actions/runs/40175100268)
record iPhone 5 executed, 4 passed, 1 failed and iPad 5 executed, 3 passed, 2 failed, with zero
errors or skips. The phone is iPhone 17, iOS 26.5 (23F77),
`AA6E36A1-F09A-4C33-9D94-55AC6299E089`; the tablet is iPad (A16), the same runtime,
`F9814116-5571-4D7C-9B93-EFC214A1BB81`. Artifacts
`discover-wishlist-iphone-40175100268-1` and `discover-wishlist-ipad-40175100268-1`
retain `native/proof.xcresult` and 23 and nine compressed captures respectively.
Direct finalized summary/test-tree reads match the XML.

Both `testEverywhereQueryPagesAndHeldRouting` cases fail the unchanged 30-second mini-player
assertion after selecting Fixture Song 10 through its scoped Library reference. Their final
accessibility hierarchies contain the scoped Play receipt and `discover-player-bar-required:true`,
but no mini-player. This distinguishes a missing page control from absent playback-store publication.
`ab48f54b` passes the existing shell bar configuration through the navigation environment and
applies it to concrete iOS Discover destinations. It retains the same store, placement and
open-player action. The app/test generic build compiles it without a device boot. The iPhone native green above confirms the unchanged player-bar assertion; the ancestor configuration correction above subsequently confirms iPad. Extending fixture media to 120 seconds previously failed to close this red,
so no EOF cause is claimed.

The iPad resize case fails its immediate title existence assertion after a real width-375
transition and compact tabs. OBSERVED: opt-in navigation receipts 24 and 25 push/request
`[wishlist, album]`; 26 records album appearance. After resize, 27 requests the same complete
path in the compact stack, then 28 and 29 record album appearance again. There is no later
store or binding write, source destination change, pop, reset or missing-navigation-destination
warning. The final video frame shows the compact detail's loading spinner and Back control.
Those observations do not support a path-clearing writer as this run's cause.

`0f29b7cd` replaces immediate `album.exists` with the same bounded 30-second title readiness
used before resize. OBSERVED: [iPad job 121125768574 in 40178354192](https://github.com/legitimate-apps/dulcet/actions/runs/40178354192/job/121125768574)
still fails that assertion. This disproves the readiness-only hypothesis. Its final accessibility
hierarchy retains the compact tabs, Wishlist Back control and metadata credit, but has no album
title, spinner or error notice. The recorded path remains `[wishlist, album]`. Lifecycle or read
cancellation was left ASSUMED by that run alone. The later focused detail receipts above
identify the cancellation cause, and the corrected full suite confirms success through the same
model/reader sequence. No store mutation is added to address an unobserved path reset.

That finalized bundle executes five cases: Busy/Stale/Navidrome and Offline/Relaunch/Arrival pass;
Everywhere still fails the unchanged player-bar assertion; resize fails as above; and Account
Switch fails its initial positive album-tile control before the Discover flow. The latter cause
is unestablished. There are zero errors or skips. Device: iPad (A16), iOS 26.5 (23F77),
`1A62D97D-4A52-484D-98A2-C0D406BB0415`. The artifact is
`discover-wishlist-ipad-40178354192-1`, `native/proof.xcresult`, with nine captures. The bundle is
finalized and not interrupted. No iPad feature promotion is taken from it.

## Current diagnostic and migration controls

On `b27fdf1f`, [Discover-only dispatch 40175100268](https://github.com/legitimate-apps/dulcet/actions/runs/40175100268)
finalizes its [Mac job 121113979225](https://github.com/legitimate-apps/dulcet/actions/runs/40175100268/job/121113979225)
with all six hosted native identities passed, zero failures, errors or skips, and 25 captures.
Direct xcresult summary and test-tree reads match the XML. The device is Apple Virtual Machine 1,
arm64, macOS 26.6.2 (25G83), with artifact `discover-wishlist-macos-40175100268-1`,
`native/proof.xcresult`. Its fixture serves the 429 at revision 7, returns distinct new-before-old
query payloads at 10, acknowledges the pending window-close detail at 26, delivers lost receipt
`74d3991b-d7eb-475b-84b0-125d3f07d913` at 29 and completes scans 31 and 32. The positive
Navidrome control reads one album and observes zero feature RPCs. All five fixture children are
reaped and the fixture root is removed. The mobile jobs finalize with the failures above. This diagnostic skips
the baseline legs by design and cannot satisfy the full release gate.

The Android [native job 121113911520 in core-ci 40175088669](https://github.com/legitimate-apps/dulcet/actions/runs/40175088669/job/121113911520)
also passes ten phases across five methods on `b27fdf1f`, API 34, `sdk_gphone64_x86_64`,
`emulator-5554`, with zero failures, errors or skips, 32 captures and 32 positive foreground
receipts. Its checkout tree equals the source tree. The fixture returns distinct new-before-old
query payloads at revision 2, delivers lost receipt `b9fe9e21-a47f-40e9-828f-4ee03700b8e7`
at 6 and completes both scans at 8 and 9. Both old-A replays remain unapplied and removed while
B `wi_10a95332-4f90-4151-9dbd-0e7281fcdb5a` survives. The positive Navidrome control
reads nine albums across 29 requests and observes zero feature RPCs. The old application PID
is already absent before the explicit stop, so its earlier death is not attributed to that stop.
The separate core-build migration failure keeps this overall workflow red.

The local Mac package on the same source finalizes 415 passing test identities, zero failures,
errors or skips. Direct xcresult reads agree with its XML; the parameterized execution statistic
is not added to the identity count. The host is Mac Studio arm64, macOS 27.0.1 (26A434).

OBSERVED: [core-build 121113911314 in run 40175088669](https://github.com/legitimate-apps/dulcet/actions/runs/40175088669/job/121113911314)
fails before tests in SQLDelight's migration verifier. SchemaCrawler's natural table sort throws
`Comparison method violates its general contract`. Bundled bytecode identifies SchemaCrawler
16.19.2 inside SQLDelight 2.3.2 and shows mutable table sort indexes. Its crawler schedules the
index writer alongside the table-constraint matcher without a dependency between them.
Concurrent mutation is the inferred cause of this particular failure; the failed run has no
instrumented comparison trace. Upstream's [table comparator](https://github.com/schemacrawler/SchemaCrawler/blob/v16.19.2/schemacrawler-api/src/main/java/schemacrawler/crawl/MutableTable.java)
uses that mutable index in its ordering.

`70adc40a` selects SchemaCrawler's supported serial scheduler through the POSIX Gradle launcher's
environment, which reaches isolated workers. The launcher control first fails for both absent
and inherited-false settings, then passes. A control using the actual bundled scheduler records
parallel task overlap and serial non-overlap. The real migration verifier passes; adding a temporary
table to migration 13 makes the same verifier reject the concrete schema mismatch. The migration
is then restored byte-for-byte. Verification stays enabled. The protected-data migration gate
also passes all 14 fixtures and its 32 destructive negative controls. These are build-tool controls,
not native client counts. The corrected-source complete core gate above confirms the ordinary migration verifier passes.

## Hosted job contract

Standard hosted `macos-26` and Ubuntu jobs have workflow cancellation, job and proof-step timeouts,
explicit expected identities and unconditional artifact upload. The iPhone proof follows the baseline device matrix, Mac follows the platform job, and iPad follows Mac, retaining the four-slot maximum. A baseline failure does not suppress a
planned native proof; cancellation stops it, and the required aggregate still rejects every failed
leg. iOS build-for-testing precedes the timed fixture proof, using the same source receipt and internal
DerivedData. The finite iOS proof cap is 45 minutes, with a 95-minute job envelope: the first iPhone
run spent about nine minutes in startup before its multi-launch cases and exhausted the former
30-minute proof cap. All cases and assertions remain required.

| Surface | Workflow / job | Expected identities | Native artifact |
| --- | --- | --- | --- |
| iPhone | apple-ci / discover-wishlist-iphone | DulcetDiscoverWishlistUITests, 5 cases | discover-wishlist-iphone-RUN-ATTEMPT |
| iPad | apple-ci / discover-wishlist-ipad | DulcetDiscoverWishlistIPadUITests, 5 cases | discover-wishlist-ipad-RUN-ATTEMPT |
| Mac | apple-ci / discover-wishlist-macos | DulcetMacDiscoverWishlistAppTest, 6 hosted XCTest cases | discover-wishlist-macos-RUN-ATTEMPT |
| Android | core-ci / discover-wishlist-android | AndroidEmulatorDiscoverWishlistProofTest, 5 methods in 10 phases | discover-wishlist-android-RUN-ATTEMPT |

Native artifacts include finalized xcresult or instrumentation XML, source revision, device/runtime,
redacted request traces, revision-bound acknowledgements and JPEG captures. Their paths within the
artifact are `native/proof.xcresult`, `native/summary.json`, `native/tests.json`, `native/junit.xml`,
`native/acknowledgements.jsonl` and `native/captures/*.jpg` for Apple. Android separates each method
and phase under `native/<method>-<phase>/`, with `junit.xml`, requests, acknowledgements and captures.
Navidrome's Android phase has request JSON and positive native assertions rather than acknowledgement
JSONL. A completed console transcript never substitutes for finalized native results.

The four common Apple identities are `testEverywhereQueryPagesAndHeldRouting`,
`testOfflineWishRelaunchRemoveAndScanArrival`, `testBusyStaleAndNavidromeControl` and
`testAccountSwitchAndIdentityReplacementFence`. iPhone adds
`testIPhoneLargestTextAndVoiceOverLabels`; iPad adds
`testIPadSidebarSelectionSurvivesWishlistDetailAndResize`; Mac adds
`testMacKeyboardSearchSidebarAndContextRemove` and `testMacWindowClosureDropsPendingNavigation`.
Each expected identity executes once in its native suite. Android's five method identities are
`everywherePagesAndHeldRoute`, `offlineWishRelaunchRemoveRaceAndArrival`,
`busyStaleAndNavidromeControl`, `accountSwitchIdentityFence` and `largestFontAndTalkBackLabels`;
they execute in 1/2/3/1/3 phases respectively. These are ten phases, not ten distinct methods.




## Integration and conflict decisions

The integration branch starts at main `ec1cca6d`. It incorporates core `3f9c1e41`, Apple `629fd6aa`
and Android `6d8f25cf`, including the accepted Android visual refinements. The core commit is already
an ancestor of both clients. The client merge commits are `5b2e9b45` and `00814c5f`. Main #368 is merged in
`e6d6e99f`, retaining its Compose proof rule unchanged. Main #367 (`ca188022`) is merged
cleanly in `80bd8db3`; its bounded SDK installer and baseline steps remain authoritative.
`f8d62a5f` invokes that same installer for the new Discover Android job before app compilation,
verifies the booted image and uploads installer diagnostics. Its 21 helper controls pass.
No nontrivial conflict occurs in either main update. Main #370 (`8c0690a6`) is then merged
cleanly in `8538a56b`, retaining its server thumbnail structure validation and API documentation.
Fresh core compilation and fixture-backed soaks check that updated integration. Main #356
(`832aa2e0`) is merged cleanly in `848ac82e`. Main owns the retained playback resync
assertions, playlist accessibility identifiers, actual-play drag preconditions, geometry-checked
menu dismissal and fixture pending-read acknowledgements. There is no conflict to resolve in
this update. Android main/instrumentation/TV and Kotlin Mac/iOS platform compilation pass after
the merge; the updated full Apple gate remains required. Main #372 (`134b9300`) then lands
Android chosen-connection playback recovery and merges as `415b3599`. Its production/controller,
remote proof and regression sources are kept unchanged. The only conflict is two new §28 revision
entries at the same insertion point: both entries are retained, including main’s recovery semantics.
The updated Android held-route proof and affected host classes require fresh verification.

Main #375 (`1d2cf959`) merges cleanly as `584f103b`. Its bounded host-pressure stack worker,
cleanup and loaded quality-menu proof changes are retained intact. The host-pressure executable
controls, CI policy and parity checks pass after the merge; the new integration still requires
hosted compilation and final native gates.

- OBSERVED: main's saved-account reconnect and rejected-epoch notification delivery conflict with
  the clients' older identity code. Main's authorization epochs, rejection delivery lock and race
  tests are retained. Discover registers with the existing reader and captures its authorization;
  it does not introduce a separate account fence. Successful reconnect retires the old token.
- OBSERVED: the clients carried earlier TV focus and playback fixture fixes. Main's settled TV
  focus helper, coherent reader observation, background account setup and exactly-once playlist
  submission assertions are retained.
- OBSERVED: the Apple account consent conflict is resolved with the native alert and explicit
  accessible Cancel action. The saved-server Form retains borderless buttons so one row does not
  activate several actions.
- OBSERVED: Discover extends the reader's existing identity state with atomic visibility for queued
  publications, shared extension detection and account retirement. Main's lasting rejection and
  post-await request checks remain authoritative. The schema adds Discover reads and wish intents;
  account cleanup includes those tables.
- OBSERVED: both runners and both fixture improvements are retained. Old/new query answers are
  distinguishable; held detail has a release acknowledgement; remove-before-add waits for the
  committed removal and records both delivered mutations.

## Navigation cause and correction

OBSERVED: the original held-album failure has an ordinary same-context push followed by a
synthetic empty binding write while its presentation acknowledgement catches up. Opt-in sequence
receipts show `pushReaderPage`, requested path, appearance, shorter binding write, acceptance and
pop, with no source snapshot or reset between them. `ccefbe64` preserves the requested path through
`displayedPath(for:)`. Account, reader-generation and destination changes still wait for registration
and fence replay; an appeared route can still be popped normally.

The package regression `anAlbumPushNeverExposesAnEmptyPathWhileItsPresentationCatchesUp` first fails
against the old store, then passes in the finalized 413-case package bundle. Later native tests reach the held album and scoped Play action; the newer player-bar red is recorded above. This earlier correction alone supplies no compact-window retention claim.

## Runner rejection and cleanup controls

`run-discover-wishlist-proofs --self-test` exercises a valid report and rejects missing identities,
zero tests, failure/error/skip results, the wrong device idiom, unfinished result bundles, an armed
but unserved fault, missing scan/lost-response effects, missing positive Navidrome browsing and a
missing replay receipt. Disabling validation makes all nine rejection controls fail. Extraction
controls also reject an unrelated XCTest suite and a summary without finalized results. A completed
console transcript is diagnostic only; accepted Apple counts come from the finalized xcresult tree
and its matching summary.

The rejection controls assert the first cause, rather than merely expecting a nonzero exit:

| Control | Required first cause |
| --- | --- |
| `test_missing_expected_identity`, `test_zero_tests` | `expected-test-not-executed` |
| `test_skip_failure_and_error` | `test-not-passed` |
| `test_wrong_idiom` | `wrong-device-idiom` |
| `test_unfinalized` | `result-not-finalized` |
| `test_armed_is_not_served`, `test_no_scan_or_lost_receipt` | `fixture-effect-not-observed` |
| `test_no_positive_navidrome` | `navidrome-positive-control-missing` |
| `test_receipt_replayed` | `lost-receipt-replay-not-observed` |

`test_positive` accepts the complete report. Later controls distinguish a finalized zero-test
bundle from an unfinished bundle, require the exact native suite and retain ordinary execution
failures. A runner-issued post-suite interrupt is normalized only after finalized expected cases
and fixture validation pass. A console completion alone does not qualify.

The runner's TERM controls exercise local lease release and hosted device deletion, with a blocked
boot child positively observed before TERM. Separate controls cover invoking-parent loss and a child
in a separate process group. Unrelated sentinel processes survive. The fixture owns and reaps only
its finite server, upstream, Navidrome and proxy processes.

On `f1374df7`, the report self-test records 14 passing controls, the process-owner suite records
six and the simulator-health suite records four. The six file-backed fixture-module controls
also record zero failures, errors or skips in JUnit. CI policy, parity, OS-floor configuration
and release-policy verification pass. The CI policy self-test includes 32 finite native fixture
invocation controls across all four surfaces and eight shared iOS-test compilation controls.
These controls validate the harness and job wiring; they supply no native feature promotion. On `0f29b7cd`, disabling `validate_report` in an isolated copy makes 13 of the 14 controls fail while the positive control still passes. The tracked implementation passes all 14; no production validator is changed for this mutation test.

## Parity execution-fixture correction

OBSERVED: [parity job 121160089576 in 40190435090](https://github.com/legitimate-apps/dulcet/actions/runs/40190435090/job/121160089576)
rejects its synthetic positive execution report on metadata head `8389b6f6`: that fixed report
omits the newly cited Discover identities. The first reported missing identity is
`DulcetMacDiscoverWishlistAppTest/testEverywhereQueryPagesAndHeldRouting`. The production parity
validator correctly refuses the omission. The correction explicitly registers the twelve cited
Discover case identities across four surfaces in the fixed fixture, independently of FEATURES,
and adds missing/failure/error/skip rejection controls for each. The complete local self-test
passes two workflow-specific positive controls and all 48 Discover rejection controls. Each runs
in its actual Apple or core context; the original Apple-only helper would ignore Android claims.
The production validator also accepts the 278 cited core identities against the actual archived
core and native XML. The production validator and native cases are unchanged. These synthetic cases are harness controls and supply no native
feature evidence. The replacement final-head run must pass the complete self-test before release.

## Current fixture and rejection controls

On `e2fb654f`, the full finite-fixture control suite records 16 passing cases in
`final-fixture-controls-e2fb6/fixture.xml`, zero failures, errors or skips. It includes the actual
120-second generated-media check and owned-process cleanup after success, failure and TERM.
The native proof runner's 14 controls pass, including missing identity, zero tests, wrong idiom,
unfinished results, skips/failures/errors, an armed-but-unserved fault and missing positive
Navidrome browse. Android runner foreground/parser/phase/output controls also pass.
`verify_ci_policy.py` reports valid across 13 workflows, including direct fixture invocation,
and parity reports 44 valid rows. Cleanup releases the owned build lease.

An earlier local policy invocation rejects a generated fixture-module Python cache as an
unregistered control. That generated artifact is removed; standalone policy then passes. No
CI-policy rule is relaxed. The combined shell status from that earlier invocation is not used
as policy evidence.

On `e2fb654f`, pinned XcodeGen regeneration and its three deliberate corruption controls,
XCUITest source typechecking and its self-test, and release-policy verification pass.
The generated project matches XcodeGen 2.46.0 exactly. These are configuration/source checks,
not native UI execution.

## Earlier full-gate iPad baseline failure

The iPad baseline [job 121082982680 in full dispatch 40164599677](https://github.com/legitimate-apps/dulcet/actions/runs/40164599677/job/121082982680)
finalizes 19 raw bundles with 23 unique test identities: 22 pass, one fails, zero skip, read from
direct xcresult summaries and test trees. The device is iPad Pro 13-inch (M5), iOS 26.5
(23F77), `99DEE653-946C-406D-8E9E-238D1FCE93C9`. The three retained native-input bundles
are included once in these unique identities. This baseline failure belongs to the existing conformance suite; it is distinct from the dedicated Discover proof.

`testAStreamingQualityChosenOnConnectionCapsTheStreamOnIPadOS` executes one case and fails
at the existing quality helper's `item.tap()`. OBSERVED: its existence, finite positive frame and
hittability checks pass, but XCTest reports an invalid accessibility activation point before
sending the event. The row frame is `(693, 802.5, 224, 38)` inside a `(0, 0, 1032, 1376)`
window. The saved failure frame shows Original in the open menu. Its center is `(805, 821.5)`.
The underlying reason XCTest cannot derive that activation point is UNKNOWN.

`cc7d421c` is a test-only candidate that additionally requires the entire row inside the native window and sends
an XCUITest coordinate tap at its checked center. The existing two-presentation readiness
control, selected-value check and later actual stream-cap assertions are preserved. Native green
is pending; the candidate is not recorded as a product fix or a passing native proof. A full
generic iOS app/test build compiles this exact test source without booting a simulator.

## Earlier full-gate iPhone baseline result

The iPhone baseline [job 121082982721 in dispatch 40164599677](https://github.com/legitimate-apps/dulcet/actions/runs/40164599677/job/121082982721)
completes successfully on `cc0bbd0a`. Its 22 XML reports contain 28 passing cases, zero failures,
errors or skips. The separate native-input artifact retains two finalized raw bundles, album-tile
drag and playlist-row drag, each with one passing case. Their direct xcresult identities are
disjoint from the XML identities, giving 30 unique passing cases. The phone artifact has two
input bundles; it is not assigned the iPad artifact's three-bundle count.

The destination is iPhone 17 Pro, iOS 26.5 (23F77),
`77AA4492-C787-4EEE-A772-05D4B3E9ECCD`. The other successful baseline raw bundles are not
retained. The dedicated Discover iPhone suite is a separate pending job, `121101918552`.

## Earlier full-gate Apple platform result

On `cc0bbd0a`, the platform [job 121082982688 in full dispatch 40164599677](https://github.com/legitimate-apps/dulcet/actions/runs/40164599677/job/121082982688)
completes successfully. Its eight uploaded XML reports record Mac package 415, iPhone package
397, iPad package 397, TV package 377, one stored-Keychain-attributes case on each simulator
idiom, and one regular-width iPad split-layout case. Every report has zero failures, errors and
skips. Successful raw platform bundles are not retained, so these counts are from XML rather
than a new direct xcresult read. Other fullscreen, title and compact-navigation assertions in
the job are not added to these XML counts.

The runner is arm64 macOS 26.6.2 (25G83), Xcode 26.4.1. The phone is iPhone 17 Pro,
iOS 26.5 (23F77), `77AA4492-C787-4EEE-A772-05D4B3E9ECCD`; the tablet is iPad Pro 13-inch
(M5), the same runtime, `687AB7EE-DF9A-4A96-878E-7509AB01A80C`. TV tests use Apple TV 4K
(3rd generation), tvOS 26.5 (23L470). These platform package and layout tests alone do not promote Discover cells.

## Earlier full-gate Darwin core and app conformance

The [Darwin core job 121082982707 in dispatch 40164599677](https://github.com/legitimate-apps/dulcet/actions/runs/40164599677/job/121082982707)
completes successfully on `cc0bbd0a`. Uploaded XML records server conformance Mac 74,
iOS 64 and TV 64; core reader Mac 1,413, iOS two and TV two; and native hosted/input
cases Mac 30, iPhone six, iPad six and TV 23. Every group has zero failures, errors and
skips. The direct reader artifact and separate conformance artifacts are counted by their
actual test cases; console totals are not substituted for XML.

The mobile app destinations are iPhone 17 Pro and iPad Pro 13-inch (M5), iOS 26.5
(23F77), and Apple TV 4K (3rd generation), tvOS 26.5 (23L470). This job also compiles
all three Apple app schemes. Its successful raw app bundles are not retained, so these
are XML counts, separate from the dedicated Discover native proofs.

## Earlier complete core gate

[Full core-ci 40165443555](https://github.com/legitimate-apps/dulcet/actions/runs/40165443555)
and [parity 40165443544](https://github.com/legitimate-apps/dulcet/actions/runs/40165443544)
complete successfully on `e2fb654f`. All nine result artifacts are retained. Actual XML records
JVM 1,302; core Android 1,596; DEV and PROD host 277 each; TV host 100; server 317; API controls
61; Linux conformance 136; Android conformance 65; production reader phone/TV 32/34; finite
fixture/session/component controls 21 (16/3/2); and Discover native 10. All those groups have
zero failures, errors or skips. The 218 repeated core XML reports are verified byte-identical
between artifacts and counted once.

Baseline phone separately records 37 cases, 35 passing and two existing fixture-only skips;
baseline TV records 38 cases, 35 passing and three existing skips. Neither has a failure or
error. Those skips are retained explicitly and are not described as an all-zero aggregate.
The native Android job is `121085478628`, API 34 / `sdk_gphone64_x86_64`, `emulator-5554`,
with ten passing phases and 32 foreground-acknowledged captures. Its PR checkout
`fbcf4b77409e0f6fe184009589400038a1455cb8` has source-identical tree
`9c9bbb634a203aa779dab644e32018249d463348`. Lost operation
`5b18fa15-484b-4d0c-ae6a-ea5f28a33787` is delivered at 6, scans 8 and 9 complete, both old-A
replays are unapplied/removed and B `wi_00b6ba4e-a455-4967-9ea2-5d71e2199cde` survives.
Query hold/order 2, native 429/retry checks 13, stale response 15 and racing add/remove 10 are
acknowledged. Navidrome has nine positive album reads, 29 requests and zero feature RPCs. The
old PID 5784 is already absent before explicit stop; its earlier death is not assigned to that
later command. Fixture cleanup reaps all five children and removes the root. The full Apple
gate remains pending.

## Earlier candidate hosted soaks

Six finite 20-repeat Linux jobs run against pushed candidate `0f29b7cd`. Accepted results below
require the exact `SOAK RESULT passed=20 failed=0` line, all twenty sets of XML, zero failures,
errors or skips, and positive execution of every requested class in every repetition. Pending
jobs supply no counts. Any later source change affecting a scope requires its refreshed soak.

| Scope | Run / job | XML reports | Executions | Exact result |
| --- | --- | ---: | ---: | --- |
| JVM LibraryStatusTransportTest, WishOutboxRelaunchTest, DiscoverAccountFenceTest | [40180834980 / 121131365439](https://github.com/legitimate-apps/dulcet/actions/runs/40180834980/job/121131365439) | 60 | 420 | SOAK RESULT passed=20 failed=0 |
| Core Android, all 17 requested account/Discover/outbox/database/queue/Media3/download classes | [40180833514 / 121131361005](https://github.com/legitimate-apps/dulcet/actions/runs/40180833514/job/121131361005) | 340 | 3,700 | SOAK RESULT passed=20 failed=0 |
| TV My Library | [40180839495 / 121131378708](https://github.com/legitimate-apps/dulcet/actions/runs/40180839495/job/121131378708) | 20 | 120 | SOAK RESULT passed=20 failed=0 |
| Real-reader DiscoverWishlistConformanceTest, fresh fixture each repetition | [40180841084 / 121131382879](https://github.com/legitimate-apps/dulcet/actions/runs/40180841084/job/121131382879) | 20 | 60 | SOAK RESULT passed=20 failed=0 |
| PROD phone, all seven requested Discover/Wishlist/navigation/queue classes | [40180836491 / 121131369880](https://github.com/legitimate-apps/dulcet/actions/runs/40180836491/job/121131369880) | 140 | 780 | SOAK RESULT passed=20 failed=0 |
| DEV production reader, fresh disposable fixture each repetition | [40180838017 / 121131374482](https://github.com/legitimate-apps/dulcet/actions/runs/40180838017/job/121131374482) | 20 | 560 | SOAK RESULT passed=20 failed=0 |

All six scopes pass with zero failures, errors or skips. These host results do not promote native
feature cells. Their source is explicitly pinned. The later core duplicate-reader correction
changes these inputs, so all six scopes require fresh twenty-repeat runs on the final pushed head.
The new JVM scope also includes `DiscoverPagesTest`; no refreshed count is inferred here.

## Earlier accepted hosted soaks

The replacement held-read test completes [Android host soak 40165440651, job 121085417347](https://github.com/legitimate-apps/dulcet/actions/runs/40165440651/job/121085417347)
on `e2fb654f` with the exact line `SOAK RESULT passed=20 failed=0`. Its 180 XML reports contain
2,340 executions, zero failures, errors or skips. Every repetition includes all nine requested
Discover, account, database, queue, Media3 and download-controller classes, including the corrected
held-read test. The preceding unchanged-source `cc0bbd0a` soak
[40164660083, job 121083133580](https://github.com/legitimate-apps/dulcet/actions/runs/40164660083/job/121083133580)
also has 20/0 and 2,340 passing executions, demonstrating non-reproduction of the old timing
failure rather than a fix for its missing read-entry precondition.

The fresh-fixture real-reader [soak 40164660094, job 121083133804](https://github.com/legitimate-apps/dulcet/actions/runs/40164660094/job/121083133804)
on `cc0bbd0a` has `SOAK RESULT passed=20 failed=0`: 60 executions in 20 XML reports, zero
failures, errors or skips. Every repetition executes `DiscoverWishlistConformanceTest` against a
fresh Discover fixture, including the updated generated audio. `e2fb654f` changes only an Android
host test; these real-reader and fixture sources remain identical.

The refreshed PROD [soak 40164660722, job 121083135892](https://github.com/legitimate-apps/dulcet/actions/runs/40164660722/job/121083135892)
on `cc0bbd0a` also records `SOAK RESULT passed=20 failed=0`. Its 100 XML reports contain
700 executions, zero failures, errors or skips. Each repetition positively matches all five
requested classes: `DiscoverWishlistSessionTest` (4), `PhoneDiscoverTest` (18),
`PhoneWishlistTest` (8), `PhoneWishlistIntegrationTest` (3) and `QueueTrackAdaptersTest` (2).
Both fixture flags are enabled, and the Discover fixture is recreated for every repetition.
These PROD host sources are unchanged by the later held-read test correction.

## Other meaningful diagnostic reds

- After merging #370, [core-ci 40057782486](https://github.com/legitimate-apps/dulcet/actions/runs/40057782486)
  has passing native Android (ten cases), baseline phone/TV and Linux conformance jobs. Its DEV
  host suite executes 271 cases with one failure in
  `PhoneMyLibraryIntegrationTest.aRemoveOnTheWireTakesTheAlbumOutAndARelaunchKeepsTheLibrary`:
  the restored album is observed, then its node is absent when touch input resolves it at line 128.
  The same class's five PROD cases pass. Client and core sources are unchanged from the preceding
  all-green run; the node-disappearance cause is UNKNOWN. Focused twenty-repetition DEV class
  soak [40058896524](https://github.com/legitimate-apps/dulcet/actions/runs/40058896524) executes
  100 cases across 20 XML reports with zero failures, errors or skips and
  `SOAK RESULT passed=20 failed=0`. It does not reproduce the failure in isolation. A twenty-repeat
  whole DEV suite [40059550826](https://github.com/legitimate-apps/dulcet/actions/runs/40059550826)
  checks the original class context. One unchanged-source failed-build-only diagnostic rerun
  passes on attempt 2. Its XML records JVM 1,288, core Android host 1,567, DEV 271, PROD 271,
  TV host 97, server 278 and API 61 cases, all with zero failures, errors or skips. The original
  node-disappearance cause remains UNKNOWN; this unchanged-source green is a non-reproduction.
- Core build `120750885089` on [40053405511](https://github.com/legitimate-apps/dulcet/actions/runs/40053405511)
  fails before tests in SchemaCrawler's NamedObjectList comparator with “Comparison method violates
  its general contract”. Core, schema, Gradle and build inputs are unchanged from the preceding
  all-green source. One unchanged-source diagnostic rerun (`120754488726`) passes. The underlying
  cause remains UNKNOWN; this is non-reproduction, not a causal fix. The same verifier failure
  recurs before tests in `core-build` job `120783376475` of run `40064029949` on `03a33b48`.
  The plugin bundles SchemaCrawler 16.19.2 in `migration-env-2.3.2.jar`; migration verification
  remains enabled. No test counts are accepted for either pre-test failure. The subsequent complete
  `042a4cad` core run above passes every job on its first attempt.
- Android `40037316301` executes ten phases with one failure: the delayed-query acknowledgement
  did not prove an old request was actually held. `c9194966` requires `held=true` before replacing
  the query on all native surfaces. Its handler regression
  `test_unrelatedSearchCannotReleaseTheOldQueryHold` fails against the original source, then all
  five module controls pass. Old, new and unrelated query payloads are distinct. Corrected native
  Android runs repeatedly pass the stricter handshake.
- The original Android held-page wishability control fails before `a07a3c98`; canonical metadata is
  re-read after the held response while keeping main's authorization fence. Host, native and soak
  results pass after that correction.
- An earlier baseline phone autoplay case on `40038830167` times out before media advances with
  Unreachable. Its transport cause remains UNKNOWN. One unchanged-source diagnostic rerun records
  37 cases, zero failures or errors and two existing skips; later first-attempt greens are
  non-reproductions, not a claimed product fix. The original red is retained.



## Nonblocking lifecycle follow-ups

A completed independent code inspection of `de940790` reports no blockers and two should-fix
findings. They are recorded as follow-ups under the repository's review policy. The relevant
Discover lifetime, WishOutbox and reader implementations are unchanged through `441798be`;
later diagnostic facade receipts do not change these paths. These are code-inspection findings,
not new native reproductions, and do not replace the owner's final review after green CI.

- Same-reader identity rejection followed by accepted saved-lineage recovery can leave the old
  Discover account's captured authorization/generation stale. On Android, a reader/process
  replacement reconstructs that lifetime, while an in-place Reconnect can leave its wishes
  pending. Follow-up proof: queue offline, reject, accept the same reader and require a pending
  wish to flush while retaining old-reply fences.
- A Wishlist write's 429 records its monotonic backoff deadline, but no Discover-owned wakeup
  schedules an idle online retry at that deadline. Another write or reconnect can trigger it.
  Follow-up proof: no retry before Retry-After, one automatic retry at the effective deadline,
  cancellation on close or identity retirement.

## Promotion limits and release conditions

Android Search, add and fulfil cite the corrected native trigger-to-effect chain and loaded-capture
proof in run 40186171196. Mac cites its six hosted/native identities in 40186168478; pages and Wishlist state remain partial on both. iPhone and iPad each cite their five passing native cases in that run. Final-head gates remain
required for all surfaces. Pages
remain partial for step-3 playlist pages and the native partial/unknown completeness-link matrix.
Wishlist state remains partial until the complete five-state, note/time, unknown/error/history and
Discover-disabled read/remove matrix has native evidence. Both TV feature cells and shelves remain
planned. `wishlist.api` retains the existing server-contract evidence.

Design §10 upstream terms, rate values and thumbnail caching rights remain PROD release conditions.
No new protocol amendment or outstanding core interface request is required. Shared logic and
Robolectric results do not promote native cells. The core's two additive exports preserve existing
signatures for both clients.

The Mac suite is app-hosted XCTest. Its restart recreates production SQL/session composition and
the real window within the hosting process. Mobile proofs separately stop and relaunch the app.
The label checks do not claim an active VoiceOver escape gesture. Mac environment captures do not
claim enlarged desktop text or a faithful vibrant-material render. Compressed native captures,
fixture acknowledgements and raw results remain in each run's named artifact.
