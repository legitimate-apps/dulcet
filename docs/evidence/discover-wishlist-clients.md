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
proof-step timeouts and unconditional artifact uploads. Apple proof jobs follow the existing
conformance jobs in series, preserving the four-slot maximum. Device creation, explicit UDID use,
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
