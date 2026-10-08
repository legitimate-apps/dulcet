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
| CONF-10b | no request after a relaunch into persisted credentials until an explicit Connect or Reconnect (downloads queued earlier excepted); prefill where a form is shown |
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
| CONF-42 | `songLyrics` v2 structured response shape, language selection, offline reuse, and legacy `getLyrics` only without the extension |
| CONF-43 | `search3` local-versus-server matching divergence |
| CONF-44 | `getCoverArt` size behavior, content types, error envelopes, and image signatures |
| CONF-51 | live exact and cold-estimated bodies validate before atomic promotion; exact mismatch never reaches destination and duplicate delivery is idempotent |
| CONF-52 | a live item promoted locally yields a `LocalPlaybackPlan` and identical bytes after all conformance network clients close |
| CONF-61 | unknown response fields are preserved and ignored |
| CONF-70 | a scanned deletion between two window pages changes the post-page `getScanStatus`; the race finds zero violations under the bracketed check |
| CONF-71 | `getScanStatus` readable by a non-admin user and unmoved by effective user-state writes, with a no-op scan as the positive control |
| CONF-72 | per-user state in catalog payloads reflects the reading user only |
| CONF-73 | `/rest` JSON reads carry no HTTP validators and a conditional request returns a full `200`, with a header positive control |
| CONF-74 | album removal behaviour under both `PurgeMissing` settings |
| CONF-75 | `X-Total-Count` presence per `getAlbumList2` type, as a total rather than a page length, and its absence on `search3` |
| CONF-76 | a cached open publishes before any request or loading state; a never-opened album offline is `unavailable` |
| CONF-77 | reconnect performs only its declared reads, by counted requests |
| CONF-78 | seen-cache eviction order, with pinned metadata surviving every ceiling |
| CONF-79 | search scope is published correctly in each of its four cases |
| CONF-80 | a seen-cache namespace bound to a different account is purged before any row is served |
| CONF-81 | reader migrations preserve protected data, pin downloads and queue entries, and seed the verified mirror |
| CONF-82 | production reader window tearing, rebasing, scanning mode and anchor keeping; no-epoch and unknown-total handling |
| CONF-83 | production reader gone-ness under both server settings |
| CONF-84 | optimistic favourite and rating overlay |
| CONF-85 | download enqueue pins metadata atomically; downloaded albums rechecked after an epoch change |
| CONF-86 | multi-list screen rows publish independently |
| CONF-87 | bounded detail look-ahead |
| CONF-88 | reference-server playlist writes: positional removal, replace by `playlistId`, a 2,400-entry form-body replace kept in order, the stale-index hazard reproduced |
| CONF-89 | every playlist operation through the production editor is read back as meant, with and without `formPost`; a lost create is identified by what the server listed before its send, never by a clock: adopted beside an older namesake that is never a candidate, never sent again when a second namesake appears (both named, the chosen one adopted), and never deleted by inference — its candidate is named, and removed only by a confirmed delete by id; each run names its playlists afresh and deletes every one it made, so a server reused after a failed run measures the same |
| CONF-90 | a playlist edit whose base changed elsewhere is refused with nothing written; unchanged-list control |
| CONF-91 | another user's playlist is not editable to the reader, the editor or the server; own-playlist control |
| CONF-92 | a streaming-quality cap is transcoded on both delivery paths (legacy `maxBitRate` with a named format; `ClientInfo` bitrate limits), no larger than the cap allows, a source already within the cap streamed as the original, with *Original* as the control; transcoding capability asserted first |
| CONF-93 | `scrobble` for an id the server does not hold is answered `ok` for `submission=true` and `false`, and moves no other track's play count |

## Account-connect evidence boundary (CONF-09b)

CONF-09b asks that every account-connect render state the account-connect path can originate is
reached through the app's own transitions; the capability error, which the path never originates
(below), is named rather than reached. It is evidenced on every `account.connect` cell: through the
production apps on the simulators and emulators, and on `macos` through the hosted Mac app together
with the signed, entitled Mac host's main run.

**Apple app proofs.** One proof per platform drives the app against the local disposable server and
reads each state back from what the app shows: `DulcetAccountConnectStatesUITests` on an iPhone and
an iPad simulator (each its own method, which fails on the other device class),
`DulcetTVAccountConnectStatesUITests` on an Apple TV simulator by the remote alone, and
`DulcetMacAccountConnectStatesAppTest` in the hosted Mac app. Each state's trigger is real:

| state | trigger |
|---|---|
| idle | a launch with no saved account |
| input error | an `ftp://` address, refused by the connector before any request |
| security error | the server's plain-HTTP address with the local-HTTP consent off |
| transport error | a loopback port that refuses the connection |
| authentication error | the disposable server's own answer to a wrong password |
| protocol error | the server's web-player address, `/app`, which answers with its own 404 page |
| server error | the fault proxy in front of the server answers the connect sequence's `ping` with OpenSubsonic error 0, and counts its answer |
| in progress, then Cancel | a loopback port that accepts the connection and never answers; the proof counts the connection |
| connected | the right account (on the simulators submitted through Connect's own path by the DEBUG launch hook) |
| saved, disconnected | a relaunch with the account saved: the library offers Reconnect, Connection names the server |
| credential persistence | simulators: a launch whose active-account pointer names no Keychain item, so the real Keychain read fails; Mac: that, and the unentitled host's Keychain refusing to save an account the server accepted |

Three inputs are not the server's or the person's own. The server error's answer is the fault
proxy's, because the disposable server never sends a server-family error to a valid request; the
connector, the Apple adapter and the view still classify a real HTTP answer. The persistence error's
pointer is planted, in the argument domain of the defaults the store reads; the read and its answer
are the Keychain's. And the simulators' connection is submitted by the launch hook, which sets the
form's fields and calls the same submission Connect calls, because a typed password that is then
accepted raises the system's save-password prompt, which no UI test can dismiss (`docs/TRAPS.md`
33). A failing Keychain save has no simulator trigger.

**How macOS is evidenced.** The hosted Mac host is signed ad hoc without entitlements, so its
Keychain refuses every save: `DulcetMacAccountConnectStatesAppTest` reaches both persistence errors
for real, but connected and saved/disconnected over an in-memory credential store, with the
production connector, reader, data source and view. The signed, entitled host
(`DulcetSignedMacAccountConnectTests`, §21.3.1) supplies the Keychain half on main, in separate app
processes: a real save and the connected UI, a relaunch that reads the item back into the saved
library, Reconnect through the rendered action, and the load-time persistence error once the item
is deleted. That run is cited through its committed receipt; the hosted proof carries the CONF-09b
row and the signed proof an `observes` row.

**The earlier presentation test.** `accountPresentationTransitionsGivenConnectorOutcomes` submits
through the production `DulcetPresentationStore` into `DulcetAccountDataSource` but injects the
connector's completed outcomes and controls the credential store. It checks each step against the
one state that step must produce, so two outcomes exchanging their states fail it, but it does not
prove that production can originate each outcome. It runs in the macOS package run
(`DulcetKitTests`), on the iPhone and the iPad (`DulcetKitIOSTests`) and on Apple TV
(`DulcetKitTVOSTests`), and each cell cites it as a bounded `observes` row. So are the tests the
cells cited for CONF-09b before revision 109, each for the one thing it shows:
`fixtureRendersEveryDeclaredDistinctState` (macOS), `accountConnectRootLoadsForIOS`,
`accountConnectRootLoadsForTVOS` and `testAccountConnectUsesRegularWidthSplitLayout` (iPadOS).

**Android.** `AndroidEmulatorAccountConnectProofTest` (phone) and
`AndroidTvEmulatorAccountConnectProofTest` (TV) run in core-ci on the API 34 emulators against the
disposable server, from an install with no saved account. The phone is driven by touches injected at
each control's place on the screen and typed key events; the TV by the remote's keys and its own
on-screen keyboard. Every state comes from a production transition: a wrong password the server
itself refuses (its error code 40 is read from the wire), the server made unreachable, a request held
in flight and cancelled, the app's preferences directory made read-only so the real save fails,
connected, a relaunch into the saved account, and a relaunch after the saved account's Keystore key
is deleted, as a Keystore reset deletes it. Neither app has separate saved or connected cards:
connected is the library, and saved-and-disconnected is the saved account's library opened at
relaunch, saying the account is saved and not connected and offering Reconnect. A relaunch is a new
activity in the same process, after the proof closes the process's library reader, which a new
process does not have.

`conf09bEveryDeclaredDistinctRenderStateIsReachable`, which reaches the view model's six render
states through injected gateway results and an injected failing credential store, stays cited as a
bounded `observes` row. The capability point below does not arise on Android, which declares one
failure render state for every error.

Each emulator also has its own CONF-10b proof (§13.1), which connects through the form and then
relaunches, and the CONF-09b proofs take the same relaunch: the relay in front of the server counts
no connection for five seconds while the library paints the albums this device has seen, and the
first connection comes only after the person chooses Reconnect — a touch on the phone, the remote on
TV, the first place UP out of the library and one DOWN from the bar — which reconnects in place. Neither app shows a prefilled form for
a saved account; the library's Reconnect is the explicit reconnect. The view-model test that prefills
the form stays cited as a bounded `observes` row.

`unevidenced_conformance` maps a declared CONF id to a nonblank reason, and the parity gate refuses a
`shipped` cell that carries one. When a cell cites at least one conformance row, or is `shipped`, the
gate also requires the evidence and the named gaps to partition the cell's declared ids with no
overlap; a cell citing only `observes` rows may name any subset of its declared ids as gaps. This
keeps the universal CONF-09b requirement visible instead of satisfying it with a narrower test.

### `Capability.Unsupported` has no account-connect origin

OBSERVED from source on 2026-09-25. `DomainError.CapabilityUnsupported` and `CapabilityFeature` are
declared in `core/.../AccountConnection.kt`. Production constructs it only in `LibrarySync.kt`, for a
server that cannot enumerate the whole library and for a walk that will not terminate; every other
production occurrence is a type match in a diagnostic or facade mapping. On the account-connect path,
`AccountConnector.connectNormalized` treats an extension-list 404 or non-envelope as
`legacySubsonic = true` and proceeds after an authenticated `ping`; malformed metadata is
`Protocol.MalformedEnvelope`, an incompatible version is `Protocol.Incompatible`, and parsed server
errors go through `AccountConnectionContract.mapSubsonicError`. None of them yields a capability
error, which agrees with spec §10.3: absent discovery must not fail a baseline login. §10.4's
three-failure circuit breaker has no production implementation in `commonMain`, so it supplies no
origin either. The Apple and Android capability error presentations are therefore reserved
vocabulary for account setup: the Swift conditional test injects it, and the Android CONF-09c
presentation control constructs it directly.

## CONF-51 citations on the Apple download cells are incomplete

CONF-51 requires live exact **and** cold-estimated bodies to validate before atomic promotion, an
exact mismatch never to reach the destination, and duplicate delivery to be idempotent. The macOS,
iOS and iPadOS `downloads.offline` cells cite
`DulcetAppleDownloadIntegrationTest/downloadTriggerPromotesValidatedResponseAtomically[OnIOS|OnIPadOS]`,
which observes one successful promotion through the platform executor: the downloaded state, exactly
one promoted file, no remaining `.partial`, and a stored size equal to the asset's exact length. It
exercises no cold estimate, no exact mismatch and no duplicate delivery. The core conformance control
`conf51LiveDownloadsValidateBeforeAtomicPromotion` covers those three against the reference server,
but through `DownloadPolicyContract`, not the Apple executor. This finding is open: nothing here
changes a citation or a status.

A naming rule — the CONF id must appear in the cited test's name — was considered as a gate for this
class of defect and rejected. In the citation audit that found these two defects it flagged 26
legitimate descriptive citations against 7 weak ones (four CONF-09b, three CONF-51), and a renamed
test that still injects its outcome would pass it; naming does not establish semantic coverage.
