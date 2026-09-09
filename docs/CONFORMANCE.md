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

## Apple CONF-09b evidence

`conf09bEveryDeclaredDistinctRenderStateIsReachable` runs in the macOS package and the shared
iOS/iPadOS and tvOS test targets. It follows the Android reachability control: submit through
`DulcetPresentationStore` into `DulcetAccountDataSource`, control connector completion and credential
storage, and compare observed snapshots with an explicit expected set. It reaches idle, connecting,
connected, restored saved/disconnected, all seven domain-error families, and credential-persistence
failure (12 distinct live states). The restored account uses credentials saved by the successful
submission; the persistence failure comes from a successful connector result followed by a throwing
credential store. No deterministic fixture produces the observations.

The enum also contains capture-only variants: `accountConnectEmpty`, `tlsUntrusted`, and
`tlsUntrustedPopulatedForm` are not emitted by the production account state machine. Production uses
`accountConnectIdle` with empty/editable form contents and `accountErrorSecurity` for TLS failures.
This test does not claim reachability of those fixture identifiers, account-removal states, rendered
pixels, a live network connection, or a successful OS Keychain save. Those are separate claims.

A mutation replacing the production successful-connection/save-error publication of
`accountErrorPersistence` with `accountConnectIdle` makes the observed set omit persistence failure:
the focused test fails with one issue. A second mutation changes the production security-family
mapping from `accountErrorSecurity` to `accountErrorTransport`, making the security render state
unreachable; the same test fails with one issue. Restoring each production change makes it pass again.

## CONF identifier naming audit

Baseline `0e7e3ea` contains 165 conformance citations (supplementary `observes` rows excluded).
Normalizing case and the hyphen in the CONF id, then requiring that id in the cited test identifier,
flags 33 citations, representing 26 unique `(CONF id, test identifier)` pairs. This normalization
accepts Android's existing `conf09b...` spelling.

| Flagged group | Citations | Assessment from test bodies |
|---|---:|---|
| Apple CONF-09b fixture/root/layout controls | 4 | Wrong evidence for production reachability; replaced in this change |
| Apple CONF-09a, CONF-09c, CONF-10a, CONF-10b | 16 | Relevant controls: production progress/cancellation, total error copy, unavailable Keychain failing closed, and saved credentials awaiting explicit reconnect |
| Darwin CONF-10c proxy control | 4 | Relevant: establishes retrievable ambient credentials, connects through a challenge fixture, asserts typed rejection and no credential delivery |
| Apple CONF-10e attribute controls | 3 | Relevant: production save, unconstrained attribute readback, and wrong-accessibility control |
| Apple CONF-52 offline playback controls | 3 | Relevant: closes network access, obtains local playback asset, and asserts identical loaded bytes |
| Apple CONF-51 download controls | 3 | Relevant but incomplete for the full registry wording: observes successful promotion and exact stored size, but does not independently exercise cold estimates, exact mismatch, or duplicate delivery |

Thus 26/33 flags are legitimate descriptive names, four identify this defect, and three expose
partial coverage deserving a separate CONF-51 evidence review. Naming cannot distinguish these
categories. After the four CONF-09b replacements, the proposed rule would still flag 29 citations
across 22 unique pairs. An exemption list would need 19 unique pairs for the 26 legitimate
citations alone (or 22 pairs/29 citations if it also grandfathered the partial CONF-51 controls).
Normalizing away platform test-module identities reduces those figures to eleven legitimate
`(CONF id, method)` exemptions, or fourteen including CONF-51, but makes exemptions apply more broadly.

**Recommendation: do not add this gate.** Most flags would require renaming valid tests or maintaining
exceptions. A name can be added to a fixture-only test without improving its assertions; the rule
cannot establish semantic coverage. Prefer reviewing the trigger-to-observation path and requiring
a meaningful production mutation for a reachability claim. No naming gate or exemption list is added.

### Reachability mutation output

Focused selector: `-only-testing:DulcetKitTests/conf09bEveryDeclaredDistinctRenderStateIsReachable()`.
The parentheses matter for Xcode's Swift Testing selection; an invocation that selects zero tests
is not evidence. Both mutations were temporary and the production source was restored.

Save-failure publication changed to idle (observed set: 11 states, missing persistence failure):

```text
✘ Test conf09bEveryDeclaredDistinctRenderStateIsReachable() failed after 0.003 seconds with 1 issue.
✘ Test run with 1 test in 0 suites failed after 0.003 seconds with 1 issue.
** TEST FAILED **
```

Security-family mapping changed to transport (observed set: 11 states, missing security error):

```text
✘ Test run with 1 test in 0 suites failed after 0.004 seconds with 1 issue.
** TEST FAILED **
```

Restored production source, after each mutation:

```text
✔ Test run with 1 test in 0 suites passed after 0.003 seconds.
** TEST SUCCEEDED **
```

### Verification for this evidence repair

- Repository core command (`:core:allMetadataJar :core:jvmTest :core:testAndroidHostTest
  :core:bundleAndroidMainAar :core:licensee`): passed, 44 tasks (24 executed, 20 from cache).
  Both test tasks were then rerun with `--rerun-tasks`: 25 tasks executed, 184 JVM tests and
  180 Android host tests, zero failures/errors/skips.
- `DulcetKit-Package` on macOS: 86 tests passed.
- `DulcetKitIOSTests` on iPhone: 77 tests passed; on iPad: 77 tests passed.
- `DulcetKitTVOSTests` on tvOS: 78 tests passed.
- Every Apple run used serial testing and a resolved internal-disk DerivedData path. The iPad
  run used `test-without-building` with the same universal test products as the iPhone run.
- `python3 tools/verify_ci_policy.py`: valid across 6 workflows.
- `python3 tools/parity_gate.py`: valid, 6 feature rows.
- `python3 tools/verify_os_floors.py --configuration-only`: macOS 14.0, iOS/tvOS 17.0 agree.
- All 36 feature-cell statuses match the baseline; all six `account.connect` cells remain `partial`.
