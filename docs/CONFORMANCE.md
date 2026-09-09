# Conformance registry

This registry reserves stable identifiers for the tests required by the design. An identifier remains
stable when its test moves from planned to executable; the design's representative-test table carries
the detailed assertion.

**This registry includes protocol, sync-consistency, presentation, and platform-security contracts.**
For example, CONF-09b/09c cover render-state inventory and actionable errors, and CONF-10a/10e cover
secure storage and observed Keychain attributes. A `conformance` row cites one of these registered
contracts. An `observes` row carries a supplementary claim for which no matching id is registered;
its distinction is the absence of a matching registry id, not an exclusion of platform or UI behavior
from this registry (design spec §19.2).
Citing a test under either shape proves that one named test executed and passed — it is not a status
promotion, and it does not by itself justify moving a cell to `shipped`. When a cell strengthens from
`planned` through `blocked`, `partial`, and `shipped`, the changed document must add at least one
complete evidence row that was absent from that cell in the base document. Whitespace runs and edges
in `observes` are normalized for novelty; prose words, case, punctuation and identifiers remain exact. Reordering
a row's keys, or editing `reason` or `promotion_condition`, does not meet that requirement. A missing `evidence`
value and `evidence: null` are both the empty set; `n/a` is outside the ordered statuses. A future
promotion that cannot structurally gain CI evidence must be declared in the initially empty
`accepted_promotions` list with the cell id, platform, a non-empty reason, and a `#<number>` PR.
Promotion exceptions and `accepted_regressions` are deliberately independent. The base document is
`origin/$GITHUB_BASE_REF:FEATURES.yml` when requested, otherwise `HEAD^:FEATURES.yml`. A missing
base is an error naming that document; the gate never substitutes a different base or skips comparison.

| id | assertion |
|---|---|
| CONF-01 | unauthenticated extension discovery |
| CONF-02 | reference-server extension set |
| CONF-03 | baseline salted-token authentication and invalid credentials |
| CONF-04 | version compatibility |
| CONF-05 | OpenSubsonic envelope fields |
| CONF-06 | transport reachability and server-error mappings |
| CONF-07 | wire-observed credential channels and credential-free diagnostics |
| CONF-07b | form-POSTed credentials on `stream` |
| CONF-08 | fail-closed request-channel inventory; same-origin preservation and pre-send cross-origin refusal on every account-connect hop |
| CONF-09a | account-connect progress and active-operation cancellation |
| CONF-09b | account-connect render-state inventory |
| CONF-09c | total actionable account-error presentation |
| CONF-10a | unavailable platform-secure credential storage fails closed with a typed reason, no active-account pointer, and no weaker fallback |
| CONF-10b | explicit reconnect after persisted-credential prefill |
| CONF-10c | a 407 proxy-auth challenge rejects ambient credentials |
| CONF-10d | restricted-user permission errors map to `Auth.Forbidden` |
| CONF-10e | a production Apple credential-store save records `AfterFirstUnlockThisDeviceOnly` accessibility and a non-synchronizable item, observed by an unconstrained attribute read-back; this does not claim protection enforcement or device-equivalent simulator semantics |
| CONF-11 | successful stream content type and signature validation |
| CONF-12 | legacy-stream and extension-stream error status and shape |
| CONF-13 | raw and transcoded ranged-stream behavior |
| CONF-14a | legacy `transcodeOffset` seek |
| CONF-14b | `transcoding` extension offset behavior |
| CONF-15 | POST transcode decision and opaque-parameter stream round-trip |
| CONF-16 | transcode concurrency limiter returns 429 with `Retry-After`, mapped to `Server.Busy` |
| CONF-17 | cold legacy-transcode `Content-Length` estimate semantics |
| CONF-21 | whether `playbackReport` increments play count — not a Phase-1 test |
| CONF-22 | `submission=false` versus `submission=true` play-count behavior |
| CONF-23 | repeated same-time scrobble deduplication behavior |
| CONF-31 | generation-pinned reads |
| CONF-32 | atomic sync-generation commit |
| CONF-33 | bounded stability witness |
| CONF-34 | `getIndexes?ifModifiedSince` behavior and granularity |
| CONF-35 | paging past the end returns an empty list, not an error |
| CONF-41 | local and server search merge |
| CONF-42 | `songLyrics` v2 structured response shape |
| CONF-43 | `search3` local-versus-server matching divergence |
| CONF-44 | `getCoverArt` size behavior, content types, error envelopes, and image signatures |
| CONF-51 | live exact and cold-estimated bodies validate before atomic promotion; exact mismatch never reaches destination and duplicate delivery is idempotent |
| CONF-52 | a live item promoted locally yields a `LocalPlaybackPlan` and identical bytes after all conformance network clients close |
| CONF-61 | unknown response fields are preserved and ignored |

## Apple account-connect evidence boundary

CONF-09b remains unevidenced on all four Apple cells. The shared
`accountPresentationTransitionsGivenConnectorOutcomes` test observes real presentation-store
snapshots, but injects completed connector outcomes. It proves conditional presentation behavior,
including credential saving, saved-account reconstruction and save-error handling. It does not prove
that production can originate each outcome or forward it through the Apple adapter. In particular,
`capabilityUnsupported` is injected and must not be called a live account-connect state.

The former production-reachability claim is withdrawn. The earlier persistence-publication and
security-family mutations detect presentation regressions only; they do not validate the production
connector boundary. The four cells retain this useful conditional coverage as explicitly bounded
`observes` entries, with matching declarations for all three target classnames in
`tools/test-parity-gate`. No cell status changes.

`unevidenced_conformance` is an explicit map from a declared CONF id to a nonblank gap reason.
Evidence and named gaps must partition the declared contracts, with no overlap. A cell with a gap
cannot be `shipped`. This preserves the universal CONF-09b requirement instead of moving it into an
Android-only requirement or using a narrower test as apparent full evidence. Conditional transition
coverage and the bounded production-origin input-error control contribute to investigation of
CONF-09b, but neither closes the complete contract.

## CapabilityUnsupported verdict

**OBSERVED from repository source: dead/reserved error vocabulary, not a missing account-connect
mapping.** `core/src/commonMain/kotlin/com/legitimateapps/dulcet/core/AccountConnection.kt` declares
`DomainError.CapabilityUnsupported` and the `CapabilityFeature` enum. Constructor searches across
Kotlin sources find executable constructions only in
`AccountConnectAndroidConformanceTest.kt` and `AccountConnectConformanceTest.kt`; production uses
are type matches in diagnostics and Apple facade mappings. The conditional Swift test similarly
injects `DulcetAccountFailureKind.capabilityUnsupported`.

The relevant production decisions are in `AccountConnector.connectNormalized`:

- Extension HTTP 404 or a non-envelope result records `extensionListUnavailable`; successful
  authenticated ping permits connection with an empty extension map and `legacySubsonic = true`.
- Malformed successful extension metadata is `Protocol.MalformedEnvelope`; an incompatible ping
  version is `Protocol.Incompatible`; permission roles populate `CapabilitySet` with false defaults.
- Parsed server errors go through `mapEnvelopeError`/`AccountConnectionContract.mapSubsonicError`,
  producing authentication, protocol or server errors, never capability-unsupported errors.

These choices agree with spec §10.3: absent discovery must not fail baseline login. §10.4 separates
advertised support, user/device/policy gates and operational health; it does not prescribe an
account-connect failure when an optional capability is absent. No implementation of that section's
three-failure session circuit breaker was found in production commonMain sources. That is a separate
unimplemented capability-health mechanism, not a reason to manufacture an account-connect error.

The general error vocabulary is specified in §18.12, but a declared/mapped type is not a reachable
behavior. The unused account-specific capability error branch is dead code and a candidate for
removal, not a missing server-error mapping. This evidence repair leaves the exported error types
and their exhaustive mappings intact rather than expanding into cross-platform API removal. No
constructor or new login failure is invented. Capability-error production reachability remains an
explicit gap; retaining the type does not count as evidence for it.

## Production failure forwarding control

`DulcetMacAccountConnectAppTest/accountInputFailureCrossesProductionConnectorIntoStore` runs inside
the macOS app host and verifies its bundle identity. It starts with the production
`DulcetCoreAccountConnector`, submits `https://`, observes connecting, and waits for an input-error
snapshot with `invalidServerURL` and no credential save. The production origin is
`AccountConnector.connect` URL normalization, before network setup. The call traverses
`AppleAccountConnectionClient` and `DulcetCoreAccountConnector`'s failure completion. No completed
connector outcome is injected. This control covers idle, connecting and input-error only; it does
not claim every domain-error family or a network failure. CI selects it and checks its individual
execution before exporting the macOS JUnit report.

| State coverage | Production-origin evidence and remaining gap |
|---|---|
| Idle, connecting, input error | New macOS app-host test executes the full production connector/facade/forwarding path |
| Credential-persistence error | Existing `connectSuccessCrossesLiveKotlinFacadeIntoPersistenceFailureState` drives the disposable server and production save failure; its citation remains separately bounded, and it was not rerun in this revision |
| Connected and saved/disconnected | Conditional store transitions and persistence/reconstruction are tested; this revision does not establish a successful production-connector plus OS-credential-store path |
| Transport, security, protocol, server, authentication errors | Conditional presentation tests remain useful; this revision does not execute each family through the real Swift failure-forwarding path |
| Capability error | No production origin exists; dead/reserved vocabulary, not a live-state proof |

The iPhone/iPad/tvOS runs below execute conditional presentation tests. They do not borrow the new
macOS app-host result as production-connector evidence for those platforms.


## Naming-gate audit retained from the initial investigation

Baseline `0e7e3ea` had 165 conformance citations. Matching the CONF id after case/hyphen normalization
against the test identifier flagged 33 citations / 26 unique `(CONF id, test identifier)` pairs:
26 legitimate descriptive-name citations, four false CONF-09b citations, and three incomplete
CONF-51 citations. The legitimate groups were CONF-09a/09c/10a/10b (16), CONF-10c (4), CONF-10e (3),
and CONF-52 (3). CONF-51 controls observe successful promotion and stored size but do not independently
exercise cold estimates, exact mismatch or duplicate delivery. That finding remains open.

The initial proposed renamed CONF-09b tests would have passed the naming rule while still injecting
unoriginated outcomes. This review supplies a concrete counterexample to the rule's usefulness.
The recommendation remains against it: exempting the legitimate flagged citations alone requires
19 exact pairs (11 after removing target-classname distinctions), and naming does not establish
semantic coverage. No naming gate or naming-exemption list is added.

## Verification after the connector-boundary review

The reviewer mutation replaced the production failure-completion call in
`DulcetAppleProduction.swift` with `return`. `xcodebuild test` compiled that file in the `DulcetMac`
app target and executed `accountInputFailureCrossesProductionConnectorIntoStore` in its test host.
The mutation run exited 65; the restored run exited 0. Actual failure output included:

```text
failed - Production failure forwarding did not leave connecting: accountConnecting
XCTAssertEqual failed: ("accountConnecting") is not equal to ("accountErrorInput")
failed - The production connector did not deliver its input failure
Executed 1 test, with 3 failures (0 unexpected) in 5.113 (5.114) seconds
** TEST FAILED **
```

After restoring the production completion and rebuilding the same app target:

```text
Executed 1 test, with 0 failures (0 unexpected) in 0.054 (0.054) seconds
** TEST SUCCEEDED **
```

The individual execution guard reported
`DulcetMacTests.DulcetMacAccountConnectAppTest/accountInputFailureCrossesProductionConnectorIntoStore`
as `terminal=Passed individual-results=1`. JUnit export contains
`classname="DulcetMacAccountConnectAppTest" name="accountInputFailureCrossesProductionConnectorIntoStore"`.
The mutation is not committed; production Swift source is unchanged.

- macOS package: 86 tests passed, including the renamed conditional presentation test.
- iPhone: 77 tests passed; iPad: 77 tests passed; tvOS: 78 tests passed. Their JUnit exports contain
  the renamed conditional test under `DulcetKitIOSTests` or `DulcetKitTVOSTests` as appropriate.
- Core command (`:core:allMetadataJar :core:jvmTest :core:testAndroidHostTest
  :core:bundleAndroidMainAar :core:licensee`) passed: 44 tasks, 1 executed and 43 up-to-date.
  Test execution was reused, not rerun: reports contain 184 JVM and 180 Android host tests,
  zero failures/errors/skips.
- `python3 tools/parity_gate.py`: valid, 6 feature rows.
- `python3 tools/test-parity-gate`: mutation and executed-evidence tests pass, including six new
  gap controls and matching testcase declarations for each new citation's target classname.
- CI policy: valid across 6 workflows. OS-floor configuration: macOS 14.0, iOS/tvOS 17.0 agree.
- All 36 statuses match base `de69354`; all six account-connect cells remain `partial`.
