# Discover and Wishlist client integration

This is step 2 of the approved Discover and Wishlist design: Search Everywhere, artist and album
metadata, durable personal wishes and Library Wishlist on iPhone, iPad, Mac and Android phone.
Shelves and playlist pages remain step 3. Television feature UI remains step 4; both TV apps still
compile. No acquisition automation, OAuth or new server protocol is introduced.

## Integration and conflict decisions

The integration branch starts at main `ec1cca6d`. It incorporates core `3f9c1e41`, Apple `629fd6aa`
and Android `6d8f25cf`, including the accepted Android visual refinements. The core commit is already
an ancestor of both clients. The merge commits are `5b2e9b45` and `00814c5f`.

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

## Native navigation and execution contract

The iPad fix separates a requested store path from SwiftUI's initially empty rendered path. An
unpresented empty write is refused, then the requested route is replayed after registration. Account,
reader generation and destination fence that replay. An appeared route can still be popped normally.
The native iPad proof must open a held album, verify its metadata, navigate Back and retain the same
pending wish after a real compact resize. Mac evidence alone does not promote the iPad cell.

`run-discover-wishlist-proofs --self-test` exercises a valid report and rejects missing identities,
zero tests, failure/error/skip results, the wrong device idiom, unfinished result bundles, an armed
but unserved fault, missing scan/lost-response effects, missing positive Navidrome browsing and a
missing replay receipt. Disabling validation makes all nine rejection controls fail. Extraction
controls also reject an unrelated XCTest suite and a summary without finalized results. A completed
console transcript is diagnostic only; accepted Apple counts come from the finalized xcresult tree
and its matching summary.

The runner's TERM controls exercise local lease release and hosted device deletion, with a blocked
boot child positively observed before TERM. Separate controls cover invoking-parent loss and a child
in a separate process group. Unrelated sentinel processes survive. The fixture owns and reaps only
its finite server, upstream, Navidrome and proxy processes.

## Hosted evidence

Native promotion is pending final-head runs. No native FEATURES cell is promoted by this document's
initial integration entry. Earlier shell results describe implementation provenance, not the result
of integrating with current main.

The finite jobs run on standard hosted runners, with workflow concurrency cancellation, job and
proof-step timeouts and unconditional artifact uploads. Each Apple proof job follows its
corresponding baseline leg, preserving the four-slot maximum.
A baseline failure does not suppress a separately planned native proof. Cancellation stops it; the
required aggregate still rejects any failed leg. Device creation, explicit UDID use,
`-parallel-testing-enabled NO`, temporary DerivedData and cleanup belong to each isolated job.

| Surface | Workflow job | Expected native identities | Artifact |
| --- | --- | --- | --- |
| iPhone | apple-ci / discover-wishlist-iphone | DulcetDiscoverWishlistUITests, 5 cases | discover-wishlist-iphone-RUN-ATTEMPT |
| iPad | apple-ci / discover-wishlist-ipad | DulcetDiscoverWishlistIPadUITests, 5 cases | discover-wishlist-ipad-RUN-ATTEMPT |
| Mac | apple-ci / discover-wishlist-macos | DulcetMacDiscoverWishlistAppTest, 6 hosted XCTest cases | discover-wishlist-macos-RUN-ATTEMPT |
| Android phone | core-ci / discover-wishlist-android | AndroidEmulatorDiscoverWishlistProofTest, 5 methods in 10 phases | discover-wishlist-android-RUN-ATTEMPT |

Artifacts contain finalized xcresults or instrumentation JUnit, device/runtime and source revision,
redacted endpoint traces, revision-bound fault and scan acknowledgements, cleanup receipts and
compressed JPEG captures. A 429 must be served, a scan must complete, and a lost mutation receipt must
have been delivered before its replay/removal claim. Navidrome first supplies a healthy catalog and
an actual app browse; only then is zero Discover/Wishlist traffic meaningful.

## Soaks and remaining gates

The changed Android host classes receive twenty Linux repetitions. The real-reader
PhoneWishlistIntegrationTest uses the Discover fixture separately from ordinary presentation tests;
its property deliberately selects only that integration class. Every repetition's XML is uploaded.
The required core-ci and apple-ci aggregators retain full conformance, and compilation covers both
phone channels and both television shells. Exact run IDs, accepted counts, final source head and
promotion decisions will be recorded after those jobs complete.

The approved design's production terms, upstream rate limits and artwork rights remain release
conditions. Shared logic and Robolectric results do not promote native client cells.

## Integration findings before the final run

These are diagnostic runs on earlier integration revisions, not promotion evidence.

- OBSERVED: [core-ci 40014070842](https://github.com/legitimate-apps/dulcet/actions/runs/40014070842)
  on `43eccd03` compiled the integrated Android sources. Its core XML records 1,288 JVM tests
  passing and 1,567 Android host tests with one failure, zero errors and zero skips.
  `AndroidDiscoverTest.identityRejectionPublishesTypedDisabledSnapshotOnMain` timed out because its
  harness sent a rejection notification without first rejecting the authoritative identity gate.
  `f8d00297` drives the real decision before notification and retains main's epoch guard. The focused
  check executed nine Discover and six identity-race cases, all passing, from XML.
- OBSERVED: the same run's dedicated Android native artifact contains ten instrumentation phases,
  nine passing and one failing, with zero errors or skips. `everywherePagesAndHeldRoute` could not
  find the held-song Open in Library action. The surface mounts before the asynchronous track page;
  the previous test searched the lazy list only once. `f8d00297` repeats scrolling until that actual
  action is displayed. This is a test timing diagnosis pending the hosted rerun, not a product fix.
- OBSERVED: the baseline phone invocation selected the new fixture-only suite without its required
  arguments. The ordinary REST runner now excludes the suite, following its existing fixture-only
  exclusion, while the dedicated job executes all its phases. The baseline JVM conformance run also
  selected five Discover cases against its Navidrome fixture. Those cases now require the explicit
  Discover property and run in fresh Discover fixtures in core-build, without skipped duplicates.
- OBSERVED: initial workflow validation rejected a job-level runner context and a dependency cycle.
  Controls now reject both. The static native citation controls reject comments, echo output,
  unrelated runner modes, unknown modes and mismatched JUnit suite emissions.
- OBSERVED: the initial Apple catalog check rejected four interpolated UI strings. Explicitly
  verbatim dynamic text fixes them. The initial Linux fixture could not execute a binary extracted
  from the server container. The fixture now verifies the official static Navidrome Linux release
  against its pinned SHA-256 before running it.

Earlier Linux soaks on `51b1186e` observed `SOAK RESULT passed=20 failed=0` for the presentation set
([40013573345](https://github.com/legitimate-apps/dulcet/actions/runs/40013573345), 29 cases per
iteration, 580 executions) and TV My Library
([40013581033](https://github.com/legitimate-apps/dulcet/actions/runs/40013581033), six per iteration,
120 executions). Each repetition's XML has zero failures, errors and skips. The core soak
[40013577210](https://github.com/legitimate-apps/dulcet/actions/runs/40013577210) observed
`SOAK RESULT passed=0 failed=20`; every iteration failed the same unbacked rejection notification
above. Later final-head results replace these diagnostics for readiness.

- OBSERVED: [core-ci 40017932240](https://github.com/legitimate-apps/dulcet/actions/runs/40017932240)
  on `3506230a`, job `discover-wishlist-android` (`120634994257`), records ten native phases
  passing with zero failures, errors or skips. The device metadata identifies API 34,
  `sdk_gphone64_x86_64`, `emulator-5554`. Query acknowledgements record the new payload before
  the old payload; the 429 was served at revision 13, the lost receipt delivered at revision 7,
  the scan completed at revision 9 and both sides of remove-before-add delivered at revision 10.
  This is accepted native evidence on that revision, awaiting final-head repetition.
- OBSERVED: the same run's Linux phone conformance failed two CONF-77 exact request-budget
  assertions. Reconnect issued one `getOpenSubsonicExtensions` in addition to the former reads.
  Shared detection is invalidated by reconnect, and an active phone feature client reads the
  current capabilities after identity and epoch validation. `1200a5c7` requires that exact single
  read after those checks; the existing visible Library read budget and TV expectations remain
  exact. Its affected methods receive a separate twenty-repetition hosted soak.

- OBSERVED: the integration policy first rejected a missing native-fixture validation rule. Its
  implemented controls accept each of the four full wrapped modes and reject six mutations per
  surface: removed wrapper, echoed command, wrong mode, self-test-only execution, a disabled step
  and allowed failure. The rule inspects Android emulator action scripts as well as shell steps.

## Integration validation after the held-result correction

OBSERVED: [core-ci 40029360299](https://github.com/legitimate-apps/dulcet/actions/runs/40029360299)
passes every job. Its actual PR checkout is `e80500565f2bbc47d51158de2a2a1d14b24b17fd`;
its Git tree equals branch revision `e8ebd33b`. This is current-source evidence before the later
merge of main's Compose proof rule, rather than a claim about a later commit.

| Job | XML suite | Executed | Failures / errors / skips |
| --- | --- | ---: | --- |
| core-build | core JVM | 1,288 | 0 / 0 / 0 |
| core-build | core Android host | 1,567 | 0 / 0 / 0 |
| core-build | Discover fixture / components / sessions | 12 / 2 / 3 | 0 / 0 / 0 |
| core-build | phone DEV / PROD | 271 / 271 | 0 / 0 / 0 |
| core-build | TV host | 97 | 0 / 0 / 0 |
| core-build | server | 253 | 0 / 0 / 0 |
| conformance-env-linux | JVM / Android host | 71 / 65 | 0 / 0 / 0 |
| discover-wishlist-android, job 120674008272 | native instrumentation | 10 phases, 5 methods | 0 / 0 / 0 |

The Android device is API 34 `sdk_gphone64_x86_64`, `emulator-5554`. Its artifact is
`discover-wishlist-android-40029360299-1`; phase XML, source/device metadata, fixture receipts,
process-restart witnesses and compressed captures are under `native/`.

The native chain witnesses distinct new and old query payloads at revision 2, a delivered lost add
receipt at revision 6, a completed scan at revision 8, and replay A returning `removed=true` and
`applied=false`. A new wish B survives a completed revision-9 scan and a second old-A replay, and
its Arrived action opens the existing local item. Removing B retains My Library membership. The
separate revision-10 in-flight add is positively held before UI removal, both mutations are
acknowledged, and the final wish is absent. Revision 13 serves the 429. The Navidrome phase first
browses a real catalog through the app and then records zero feature RPCs.

OBSERVED: the earlier native continuation could not add B after reconnect because refresh had
correctly marked its canonical search result held. Android alone hid Wish for held results. The
new host regression first fails with `discover.wish.song.0` absent
([40028537829](https://github.com/legitimate-apps/dulcet/actions/runs/40028537829), one test,
one failure). `a07a3c98` retains canonical, wishable and capability checks while allowing a new
wish after prior removal, matching the other clients. The ten native phases above pass after that
change. All fourteen PhoneDiscoverTest cases also pass twenty Linux repetitions
([40029054110](https://github.com/legitimate-apps/dulcet/actions/runs/40029054110), 280 XML cases,
zero failures/errors/skips, `SOAK RESULT passed=20 failed=0`).

OBSERVED: the iPhone baseline writer receipts on the earlier Apple run show an album pushed,
requested and appeared, followed by a shorter NavigationStack binding write and an accepted pop.
No source snapshot/reset intervenes. The binding getter exposed an empty path while an ordinary
same-context push awaited its presentation acknowledgement. `ccefbe64` keeps that requested path
visible immediately; changed account/destination contexts still wait for registration. The new
store/presentation regression distinguishes those cases. Native integrated-head confirmation is
still required before promoting an Apple cell.

Main's Compose proof execution rule, merged through `e6d6e99f`, is retained unchanged. The existing
Ubuntu archive fallback and pinned FFmpeg installation now precede the new fixture tests, avoiding
a second unversioned package installation against the slow mirror. The first hosted Mac proof did
not run tests because development provisioning was unavailable; `f0fd093c` adopts the established
ad hoc app-hosted proof signing command and tests the actual command in the runner cleanup control.
