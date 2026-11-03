# Verified connections — `dulcetRoutes:1` / `dulcet.routes:1`

Contract addition, 2026-10-28. This is a compatibility bridge for OpenSubsonic clients;
Dulcet API authentication remains header-only. Native Dulcet API adoption has a separate gate.
A connection is transport, never a new account or a new library namespace. Phase 1 enrolls
connections with one selected route. The phase-2 automatic selector is specified below;
native acceptance and platform promotion are recorded separately.

## Authenticated directory

Discover `dulcetRoutes` version `1` alongside `formPost:1` through
`getOpenSubsonicExtensions`. Authenticate the explicitly chosen HTTPS base, confirm its user
and accepted nonempty Dulcet `instanceId`, then POST `rest/getDulcetConnections.view` relative
to that base. Send standard `u`, fresh salted `t`/`s`, `v`, `c`, `f=json` exclusively in an
`application/x-www-form-urlencoded` body. GET, query authentication and redirects are refused;
there is no query fallback. Clients bound declared and streamed responses to 65,536 bytes.
Directory and error responses carry `Cache-Control: private, no-store`; cookies are not used.

The standard JSON `subsonic-response` envelope contains `dulcetConnections`:

```json
{
  "version": 1,
  "revision": 7,
  "instanceId": "opaque-lineage",
  "principal": {"id": "opaque-user", "username": "confirmed-user"},
  "routes": [
    {"id": "home", "baseUrl": "https://home.example.test/music", "role": "private", "priority": 10},
    {"id": "remote", "baseUrl": "https://remote.example.test/music", "role": "public", "priority": 20}
  ],
  "legacyOwnershipAnchors": ["http://legacy.example.test:4533/music"],
  "authAliases": [
    {"routeId": "home", "issuer": "https://home.example.test/music", "tokenEndpoint": "https://home.example.test/music/oauth/token"},
    {"routeId": "remote", "issuer": "https://remote.example.test/music", "tokenEndpoint": "https://remote.example.test/music/oauth/token"}
  ]
}
```

At most eight routes are allowed. IDs/bases are unique. Bases are exact normalized HTTPS
scheme/hostname/effective-port/path values without userinfo, query, fragment, traversal or
ambiguous separators. Labels are optional display text; roles describe topology, not proof
that a VPN is in use. Priority is an administrator preference for later automatic selection.
Unknown optional fields are ignored. A durable, increasing revision is scoped to this lineage
and authenticated principal; rollback is refused. A directory refresh cannot erase a local
removal tombstone. Authenticated ordinary users may learn private addresses; public discovery
never publishes the address list. Administrators may reduce publication by role.

A listed candidate receives no credentials until its own OS-trusted TLS connection and
same-origin, redirect-free discovery confirm the expected lineage and exact API base mapping.
Resolve discovery relative to the exact route base: a base ending in `/music` serves
`/music/.well-known/dulcet` and advertises `/music/dulcet/v1`. The origin-root document cannot
authorize a different base prefix. Discovery has the same 65,536-byte application limit.
Discovery alone never authorizes a new address. A TLS failure means unavailable, including on
an unrelated LAN reusing a private IP; a TLS-valid different lineage is quarantined while old
offline content remains. HTTP aliases are ignored even with consent and receive zero bytes.
`legacyOwnershipAnchors` is adoption attestation only, never a probe/dial target.

## Resource and authentication boundaries

The receiving listener, configured external authority and base select a route before
authentication. Neither arbitrary Host/forwarded headers nor the backend's HTTP scheme can
choose the externally advertised scheme or issuer. A legacy `publicUrl` is only a single-route
default; it cannot override a configured receiving route. A handler mount must match that
validated authority and exact receiving base prefix. A prefix belonging to another authority,
or a different nested mount on the same authority, cannot expose handlers through that route.
Every reachable API handler traverses credential-query rejection, rate limiting and idempotency
processing exactly once; a mismatched mount is rejected before authentication or writes.

Media grants are relative to the route base as a directory: `dulcet/v1/media/<opaque>` under
`https://home.example.test/music/`. An absolute resource is allowed only at that exact origin
and inside the segment-bounded base. `/music2` is outside `/music`. Plans, artwork, grants and
each redirect are checked before dispatch. Userinfo, authority-relative references, another
enrolled alias, HTTPS-to-HTTP and path escapes fail with redacted `ResourceOutsideRoute`.
Never rewrite a signed URL to another host; request a fresh plan instead. Existing inline
binary-envelope and media-signature validation still applies to actual engine bytes.

OAuth metadata and issuers describe the configured receiving route. Shared opaque grants,
revocations and rotation/retry records allow refresh at explicitly listed `authAliases` after
TLS/lineage admission and exact endpoint containment. A resource alias alone does not authorize
refresh-token disclosure. Authorization-code/device transactions stay on their initiating
issuer; validate `iss` and never change issuer mid-transaction. Removing the bootstrap route
retains provenance; another admitted route may refresh the directory and authorized token family.
Removing the last connection leaves the account offline, with no implicit anchor fallback.
Native OAuth refresh integration is deferred to native Dulcet API adoption.

## Ownership and preserving HTTP adoption

The normalized originally saved address is an immutable ownership anchor, even if HTTP or no
longer reachable. Account ID, namespace, principal and media epoch stay fixed. Only request
factories consume route leases. Strict existing endpoint comparisons stay strict; enrollment
and route changes call neither cache binding/purge nor media binding/account removal.

An HTTP account may explicitly authenticate to a user-chosen HTTPS base. Preserving adoption
requires previously known matching lineage, exact confirmed principal continuity, and that
HTTPS directory's exact attestation of the old HTTP anchor. Commit HTTPS enrollment and disabled
HTTP dialing atomically without rewriting the saved address or moving rows/files. Failure,
Cancel or an incomplete transaction retains the old mode. Once committed, all native players,
download workers and artwork paths use HTTPS only, including when HTTPS is unreachable.
An older binary that ignores enrollment can resume legacy HTTP use; this compatibility limit
is disclosed rather than claimed controllable by the new binary.

Existing HTTP/Navidrome single-origin accounts retain their explicit local-network opt-in,
per-request local numeric dial pin and strict replacement behavior. Independently saved alias
accounts are not automatically merged.

## Explicit recovery and removal

Route rows and their transport generation are restored from one database snapshot. Publication
is monotonic per account, including offline selections: a delayed restore cannot replace a
newer removal or certify old route rows with a later generation. A configuration change also
cancels an earlier suspended Add/Verify intent before commit. These transport generations do
not change the ownership anchor, media epoch or owned rows/files.

Verify reads through the selected admitted HTTPS route, or another enabled nonremoved HTTPS
route when the account is offline. It never uses a frozen HTTP anchor after adoption. A
refresh retains local tombstones. In phase 1, removing the selected route leaves the account
offline until an explicit Add/re-add action. In phase 2, new request demand may admit another
nonremoved route; removing the last route still leaves the account offline.

Add/re-add is an explicit HTTPS action available after enrollment as well as before it. Through
an available admitted route, fetch the authenticated directory and require the chosen address
to be listed and to pass credential-free OS TLS, lineage and API-base checks before sending
credentials there. On success, only the chosen route's tombstone may be cleared, and the chosen
route becomes selected. Other removals remain intact. When no admitted source is available,
including when a recorded source is unreachable, explicit recovery may authenticate a previously
listed chosen HTTPS bootstrap after those same credential-free checks and then validate its
authenticated directory/principal continuity. Unreachable route records do not have to be removed
first. This exception belongs only to the person's explicit known-address action; Verify cannot
fall back to a removed route, and cancellation or failed identity/principal continuity cannot
be converted into recovery.
An unknown unlisted origin receives zero credentials. Failure leaves the existing account,
its offline files and every tombstone intact. Account replacement is a separate operation.

A fresh Android account establishes its anchored media nonce non-destructively when its new
account ID and accepted identity are saved, before automatic enrollment. This initialization
refuses an existing namespace; enrollment never replaces a media binding to acquire readiness.

## Deployment and continuity

TLS terminators are trusted credential boundaries. During the REST compatibility stage,
existing media/metadata calls may contain replayable salted credentials in URLs; form POST on
the directory does not remove that exposure. Redact full queries, auth headers and request
bodies; disable credentialed-response caching and encrypt proxy-to-origin transport. No client
can verify a commercial proxy's internal retention.

Moving/recreating a database or its data directory, changing volume objects, restoring data or
losing the continuity marker can rotate `instanceId`. This appears as a different timeline to
enrolled clients even if music/usernames look unchanged. Keep paths/objects stable where
continuity matters; copying the marker alone does not preserve it. New lineage is quarantined,
never silently adopted by matching URLs or usernames. Administrator-attested lineage continuation,
application signing keys, secure HTTP pairing and account consolidation are separate work.

The [constructor audit](../connection-ownership-audit.md) records all equality and construction
boundaries. The [conformance plan](CONFORMANCE.md#verified-connections-phase-1) distinguishes
server, common-core and real native executor evidence.


## Automatic HTTPS selection (phase 2)

The phase-2 selector keeps the saved URL as ownership provenance and applies a transport
lease only inside the shared authenticated request factory. Preference selection and route
revocation are distinct: a higher-priority admission applies to future requests while healthy
in-flight reads, streams and downloads keep their captured authority. Removal, a changed base,
or a changed accepted identity revokes that authority; re-adding a removed route cannot revive
an earlier prepared request. Directory refreshes retain local tombstones.

The fixed order is HTTPS admission, the consumer's existing network-cost permission, then
administrator priority and route ID. A shared account flight starts on demand, with 250 ms
probe staggering, at most two concurrent probes, a 3 s candidate deadline and an 8 s round
bound. Candidates share the remaining round budget, up to the 3 s ceiling, so hung
higher-priority routes cannot starve a last-priority candidate. First success serves waiting work immediately; a better success in the same round
changes future selection. Successful admission expires after 60 s idle. Meaningful topology
hints coalesce for 250 ms, including changes while the OS still reports online. No idle timer
polls routes. Transport cooldowns use monotonic 1, 2, 4…60 s backoff with bounded jitter;
identity mismatch quarantines the candidate until a configuration replacement. Topology
changes permit an immediate coalesced retry without clearing identity quarantine.

Prepared resources retain both authority generation and admission-attempt generation.
Preference changes preserve healthy authority; failure feedback belongs to its captured
attempt and cannot cool down a subsequently recovered route. Authentication refusals,
permission/missing-item responses, malformed envelopes and 429 responses keep their existing
account/endpoint handling and never trigger alias cycling. A validated 401, 403 or 429 response
header remains authoritative when its body is cut or malformed; preserve `Retry-After` and
reject the body without feeding that refusal into transport-route recovery. This applies to GET,
repeated GET, form POST, bounded reads and JSON POST: metadata body saving must not hide a known
refusal status. Cancellation and explicit security or revocation failures still propagate.
Writes are not retried by the request factory: their existing outboxes retain operation IDs, action times and reconciliation.

Implementation status: the common selector and factory integration have focused JVM coverage.
Native handoff, topology adapters and per-platform cut-listener acceptance remain pending;
this section records the phase-2 contract and does not assert a household release.


For legacy REST stream handoff, `transcodeOffset:1` explicitly admits a freshly resolved
`stream` request with integer-second `timeOffset`. The decoder reports time relative to that
remainder, so clients add the sent offset when recording song position and preserve the listen's
existing accrued time. A decoder timeline reset before its error callback cannot erase the
last confirmed song position; an intentional backward seek replaces that position, and a new
attempt begins with its own observations. An offset is never copied to `getTranscodeStream` or an opaque grant.
Original streams use native seeking. An unsupported offset leaves the selected listen stopped;
it cannot silently restart at zero or advance to the next song. This phase adds no native API
playback adoption. Native cut-listener execution is still required before platform promotion.

Connection diagnostics are session-only observations. The current route follows actual admission;
a completed probe records Ready, Unavailable or Different server plus a wall-clock check time.
A removed configuration cannot publish an older diagnostic into a replacement configuration.
Historic verification alone is not a recent availability check. These fields do not confer request
authority, replace the saved account address, or create idle probes. Native Local Network permission
state remains an adapter observation, separate from a generic unreachable result.

On Apple, the visible Connections surface observes the existing OS Local Network permission
adapter only for an actually checked, unavailable route. Its answer is scoped to the account,
route address and check time, and cancelled when that observation ends. Only an adapter denial
changes the unavailable label to Local Network access blocked; a transport timeout alone cannot
do so. Permission state grants no route authority and cannot turn a different-server result into
an ordinary outage. Deterministic adapter tests are not actual OS permission evidence.


### Platform network permissions (phase 2, 2026-11-02 decision)

Automatic route selection preserves existing download permissions: Android WorkManager uses
`NetworkType.CONNECTED`; Apple URLSession preserves cellular, expensive and constrained access
flags on replacement tasks. A reachable alternative does not override a denied platform path.
There is no current Wi-Fi-only download preference; adding that advanced preference is outside
phase 2. Runtime recovery permission proofs remain pending until recorded per platform.


### Playback preparation failures (2026-11-02)

A route-recovery checkpoint belongs to the listening session, not to a fresh decoder attempt
that has not started. Failed metadata or source preparation retains the last confirmed position
through subsequent admitted-route attempts. Actual decoder progress or an explicit seek replaces
that checkpoint. An original-file recovery supplies its starting seek before native extraction.
Legacy transcodes request `timeOffset` only after authenticated `transcodeOffset:1` negotiation;
without that support a fresh legacy stream restarts with a zero origin and its true decoder clock.
Authentication, permission and rate refusals remain failures, not permission to restart or switch.

OBSERVED on Android phone and TV before the checkpoint fix: distinct engine-attempt markers
issued the alternate requests; recovery was invoked with a missing checkpoint (logged as zero),
so it skipped offset negotiation. Both supported-transcode and original Range assertions failed.
The common regression independently fails after the first intermediate preparation failure
(expected 40 seconds, observed null). Native after-fix results are pending.


### Native recovery resources (2026-11-03 UTC)

Original-file recovery sets the native starting position before extraction. Android bounds its
metadata read and continuations to 256 KiB Range responses; a short or revoked response surfaces
to the engine instead of opening another segment. Explicit Play/Try Again after a paused route
failure retains capability-aware recovery. An ordinary retry fallback clears that provenance.

Apple can deliver validated prefixes from an exact-framed 200 while its one captured transfer
is open. Envelope, audio signature and content-type validation precede delivery; terminal framing
remains required. Cancelling a speculative AVAsset read retires only that subscriber. Resource
retirement cancels the transfer. Failure or generation revocation rejects subsequent prefix reads
and authorizations. Unknown-length responses retain buffered validation without an invented total.
Strict redirect source retirement and scoped route evaluation still apply.
