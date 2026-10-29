# Several HTTPS connections to one server

Dulcet Server can publish an authenticated directory of exact HTTPS addresses for one database.
The OpenSubsonic `dulcetRoutes:1` bridge lets current REST clients enroll those addresses without
changing the account's saved ownership address. Enrollment does not enable automatic switching.
The wire contract is [Dulcet routes](../dulcet-api/connections.md).

Configure `--routes='<JSON array>'` / `DULCET_ROUTES`, for example:

```json
[
  {"id":"home","baseUrl":"https://home.example.test/music","role":"private","priority":10},
  {"id":"remote","baseUrl":"https://remote.example.test/music","role":"public","priority":20}
]
```

Every URL is a client-facing, certificate-valid HTTPS base. At most eight routes are accepted.
IDs are unique strings of 1–64 ASCII letters, digits, `_` or `-`. Roles are `local`, `private` or
`public`; priorities are integers from 0 through 65535, lower first. Optional `label` is at most
128 characters, `audience` is `all` (default) or `admin`, and `authAlias` is `true` by default.
An audience restricts publication to authenticated users; it does not change catalogue permissions.
An `authAlias:false` route is excluded from refresh authorization and refuses `/oauth/token`.
Unknown optional configuration fields are ignored. Invalid URLs, duplicate routes, HTTP routes,
userinfo, queries, fragments, escaped or traversing paths, wildcard addresses and oversized
configuration fail startup. Keep configured paths and case exact.

`--legacy-ownership-anchors='<JSON array>'` / `DULCET_LEGACY_OWNERSHIP_ANCHORS` optionally attests
exact previous addresses, for example `["http://legacy.example.test:4533/music"]`, for a preserving
client upgrade. These are historical ownership metadata, never enabled client routes or permission for a
client to disclose credentials to HTTP. The server continues serving ordinary REST on these exact
configured HTTP authorities/base prefixes for existing legacy clients, while refusing directory,
discovery, OAuth and v1 requests there. Configure the precise legacy address before enabling routes
if old single-address HTTP clients must remain usable. Only an explicitly chosen authenticated HTTPS connection,
matching known lineage and confirmed principal can adopt the old namespace. Never list another
installation or infer continuity from equal usernames. A client binary predating enrollment can
still use its old HTTP address; upgrading a client cannot control an older binary.

The directory revision is stored in the server database and increases transactionally when the
configured directory changes. Restarting with identical settings keeps the revision. All routes
share the same database, users, lineage, grants, refresh rotation, receipts and revocations. Do not
point separate server databases at the addresses and expect them to become one server.

## TLS proxy and base mapping

Each proxy preserves the original external `Host` (including a nondefault port) and complete base
prefix. A server configured with `/music` installs REST, discovery, OAuth and API routes below that
prefix; do not strip it at the proxy. The external HTTPS scheme comes from configuration, never
from the backend HTTP connection or arbitrary forwarded headers. Unknown authorities and prefixes
are rejected before authentication. An HTTP backend connection is accepted only from a numeric
socket peer covered by `trusted-proxies`; list the actual proxy IP/CIDR explicitly. Configure the
proxy to reject arbitrary public Host values too. A preserved Host must exactly match one configured
route. `X-Forwarded-Host`, when supplied, must agree; `X-Forwarded-Proto` must be `https`;
`X-Forwarded-Prefix` must match the configured prefix. Ambiguous multi-value assertions and generic
`Forwarded` headers are rejected. These headers do not select a route.

Use encrypted origin transport for remote proxy hops; a local loopback proxy can use the local
listener. The route configuration does not install certificates or open a TLS listener: terminate
certificate-valid TLS at the configured proxy/listener, keeping TLS verification enabled.
`public-url` remains the legacy single-origin setting only when no route directory is configured.
It cannot override a recognized receiving route.

Never log full queries, authentication bodies, Authorization headers or media grants in server,
proxy or CDN access logs. Disable caching of credentialed REST/API replies and honor directory
`Cache-Control: private, no-store`. The directory uses form-body salted authentication, but existing
native REST media requests can still carry salted credentials in URLs. A configured TLS terminator
can read those credentials and content; directory enrollment authorizes that trust boundary. Header
tokens in a future native API transport reduce URL-log exposure, not the terminator's visibility.

Public discovery exposes only same-route pointers and lineage, never the private route list.
Authenticated ordinary family accounts see `audience:all` routes. Media grants and playback URLs
are relative to the receiving route base (for example `dulcet/v1/media/<grant>`); resolve with a
trailing slash on the base, preserving `/music`. OAuth metadata uses that configured absolute base
as issuer. Authorization-code and device transactions stay pinned to their initial issuer;
refresh tokens may rotate at a directory-listed authorized alias, with the existing shared retry
window and revocation rules. Removing a configured route removes its network/refresh access without
changing historical ownership or grant issuance provenance.

## Data lineage and moves

`instanceId` identifies the data timeline, not an installation key. Moving/recreating a database
file, moving a data/container volume, disk migration, restore or continuity-marker mismatch can
rotate it even if music and usernames look unchanged. Keep paths and volume objects stable when
continuity matters. Copying only the marker does not preserve lineage. Enrolled clients quarantine
a conflicting lineage and retain offline state; equal URLs or usernames do not authorize adoption.
Administrator-attested continuation needs a separately designed future trust contract.

Server evidence is `ConnectionsConformanceTest` (MP-01/11): two configured bases, durable revision,
body-only auth, HTTP backend socket trust, relative media bytes, route issuers, transaction pinning
and cross-alias refresh/retry. This is server evidence, not proof of native selection or UI parity.
