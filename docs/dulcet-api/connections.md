# Verified connections — `dulcetRoutes:1` / `dulcet.routes:1`

Contract addition, 2026-10-28. This is a compatibility bridge for OpenSubsonic clients;
Dulcet API authentication remains header-only. Native Dulcet API adoption has a separate gate.
A connection is transport, never a new account or a new library namespace. Phase 1 enrolls
connections and retains one selected route; it does not automatically switch addresses.

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
refresh retains local tombstones and an offline selection; it does not select an alternate
automatically. Removing the selected route leaves the account offline until an explicit action.

Add/re-add is an explicit HTTPS action available after enrollment as well as before it. Through
an available admitted route, fetch the authenticated directory and require the chosen address
to be listed and to pass credential-free OS TLS, lineage and API-base checks before sending
credentials there. On success, only the chosen route's tombstone may be cleared, and the chosen
route becomes selected. Other removals remain intact. If no admitted route remains, explicit
recovery may authenticate a previously listed chosen HTTPS bootstrap only after those same
credential-free checks and then validate its authenticated directory/principal continuity.
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
