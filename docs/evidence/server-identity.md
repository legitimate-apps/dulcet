# Server identity and saved accounts — 2026-10-21

The implementation distinguishes a saved provider namespace from server lineage: the former is a
local random account ID, the latter is authenticated product type and, for an advertised Dulcet
server, its origin's discovery instance ID. Software versions do not enter lineage. See spec §28
for trust, migration and plain OpenSubsonic limits.

## Shared core

- OBSERVED on parent revision `271dd44e`: four JVM `ServerIdentityNegotiationTest` cases fail.
  Successful negotiation has no lineage to distinguish replacements or preserve over upgrades.
- OBSERVED in the new implementation: the negotiation tests connect to a disposable loopback
  server, change Navidrome → Dulcet → Navidrome at the same address, change only software version,
  reconnect without changing lineage, rotate a Dulcet instance ID, and reject redirected discovery
  without sending credentials or following the redirect.
- `SavedServerIdentityTest` checks canonical-principal selection, both replacement directions,
  cache-independent accepted identity, the read-before-write reconnect barrier, and the independent
  scrobble retry barrier. Detection preserves old identity and pending writes until account consent.
- OBSERVED: `PlaybackQueueAccountSwitchTest` (4 cases), plus existing queue/store regression
  controls, execute 57 JVM tests with zero failures/errors/skips. A → B → A restores order, current
  entry, repeat/shuffle state and separate resume positions even for identical opaque song IDs;
  stale outgoing player/preload/AutoPlay events cannot change B.
- `SavedServerMediaIsolationTest` checks that selecting B preserves A's partial and completed
  media while neither account can resolve the other's identical raw ID, and that retiring an old
  account deletes its independent lineage and preference rows while retaining the other account.

- OBSERVED: the focused core acceptance set executes 78 JVM cases with zero failures/errors/skips
  (identity negotiation, reader/outbox barriers, media binding, download policy and isolation).
  Thirteen Android-host cases also pass: queue selection, shared identity/media isolation and
  the independent contact-probe race controls.

## Android

OBSERVED fail-before at the production connect-and-save boundary: the baseline implementation with
an API-only optional-parameter shim fails three new acceptance cases. Both product-swap directions
skip the seven-download consent, and saved `alice` / typed `Alice` / confirmed `alice` rotates its
provider ID. The encrypted store, connect operation and new tests are otherwise the production path.

The phone and TV tests exercise Compose on the Android host runtime, with Keystore cryptography
replaced at its existing test seam. These are host app observations, not emulator or physical-device
execution. The local focused app run passes 17 phone cases and eight TV cases with no failures:
account conformance, canonical persistence, sign-out and the new switcher/consent flows. CI and
soak results are recorded in the PR, tied to their run IDs and revisions.

## Apple

OBSERVED: the exact #347 canonical case already passes on the parent Apple implementation; it is
not claimed as fail-before. New shared Kit acceptance exercises both product directions with a
seven-download offer, cancellation before mutation, retirement before removal, canonical retention,
unchanged lineage across upgrades/restarts, and A → B → A provider selection.

OBSERVED on a Mac host: the shipping `DulcetRootView` renders the Account entry; native accessibility
activation opens its saved-server list and activates server B. Native facades and persistent data
have separate integration tests; a fixture datasource alone does not establish live-server I/O.

OBSERVED native adapter tests also pass locally: the real AVPlayer current/preload rebind proof
keeps installed playback and files through Cancel, then observes retirement before the purge
callback after confirmation. The storage roundtrip prints
`DULCET SAVED SERVER STORAGE PASS selection=A->B->A media=distinct-bytes queue=restored library=restored deleted=0`.
It checks provider-scoped SQLite seen-library rows, persistent queue state and different local
media bytes. Both proofs are invoked by the existing download aggregate selected by Apple CI on
Mac, iPhone and iPad. tvOS has the shared account/chooser proof and shared-core store tests; this
native AVPlayer adapter aggregate is not claimed there.

OBSERVED on the iPhone simulator: the hostless shared root/action fixture passes with
`DULCET SAVED SERVER ACTIONS PASS selected=server-b kept=2 native-ui=unproven`.
The app and shared test target compile for iPhone. This is action wiring with injected accounts;
the hostless target has no application scene, so rendered UIKit tab/remote activation remains an
explicit evidence gap on iPhone, iPad and tvOS. The Mac accessibility proof is separate.

## CI

The PR records the final-head Linux soak lines, required checks and dispatched Apple run IDs.
Platform cells remain partial where observations stop at the host UI, injected account transition
or durable core boundary. These tests do not claim a signed-device or multiple-live-server UI tour.

## Background work and learned legacy identity

OBSERVED fail-before: two Android download regressions allowed a scheduled transfer or an in-flight
payload to publish after a known replacement. The corrected worker validates before old-ID requests
and rejects late promotion while preserving partial/local media. The focused download and probe
suite passes; transient HTTP 503 identity failure remains retryable and completes after recovery.
A further real authenticated concurrent-probe regression failed before the fix: a newer same-server
reader probe stranded its superseded download task. The corrected task has a finite wake boundary
and its successor completes the exact bytes; 25 download tests and three probe races pass.
Two additional Android account cases failed to offer consent when the accepted database lineage
was stronger than legacy credentials; the fixed policy reads the database first.

OBSERVED fail-before: two Android playback cases dropped a Play selection during identity probing.
The fix waits for the already chosen connection's probe, preserves latest-selection ordering, and
leaves a rejected probe closed. Separate probe tests cover overlapping probes, cancellation and
exceptions so no waiter remains held by a completed validation.

OBSERVED Apple shared Kit fail-before: detected replacement invoked no download quarantine. The
corrected presentation quarantines before negotiation and Cancel keeps media. New native Kotlin
facade tests reject late promotion, retain existing bytes, reject stale probe success and prevent
saved metadata from downgrading learned lineage. Native adapter execution and exact-head CI results
are recorded in the PR; shared presentation tests do not establish a rendered live-server tour.
