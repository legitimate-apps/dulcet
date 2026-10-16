# Dulcet API conformance suite: plan

This is the plan for a public, runnable suite that proves a server or a client implements Dulcet
API v1. Suite 0.1.0 covers the server side of the **Core**, **Sync** and **Device** profiles and lives in
[`dulcet-api-conformance/`](../../dulcet-api-conformance); [Running the suite](#running-the-suite)
says how to use it and where it departs from this plan. The rest of this document is the plan the
remaining build follows. Profiles are defined in [README §20](README.md#20-claiming-compatibility).

## Goals

1. **Anyone can run it** against their own server or client with one command, offline, with no
   account anywhere.
2. **A pass means something.** Every test can fail, and the suite proves it: each check has a
   negative control (a deliberately broken reference behaviour) that must make it fail.
3. **A missing capability fails, never skips,** for any profile being claimed. Skips read as green.
4. **The report is evidence.** It is machine-readable, names the suite version, the profiles, the
   server's `instanceId` and version, and every check's result, so a compatibility claim can link
   to it.

## Shape

- **Language and packaging.** A Kotlin/JVM command-line tool in this repository, published as a
  container image and a runnable JAR under Apache-2.0. Kotlin matches the reference server and lets
  the suite reuse its HTTP stack; the container means nobody needs a JVM.
- **Contract-driven.** The suite loads `openapi.yaml` and validates every response it receives
  against the schema of that operation and status (status code, media type, required fields, types,
  formats). Unknown fields are allowed, as the contract says.
- **Fixtures.** A generated, royalty-free audio corpus (tones and noise, in FLAC, ALAC, AAC, MP3 and
  Opus, with known tags, artwork, embedded and sidecar lyrics, multi-disc albums, compilations,
  duplicate titles and non-ASCII names). The suite ships the generator, not the audio, and the
  corpus is identical everywhere.
- **Server under test.** The operator points the suite at a base URL, a library directory the
  server scans, and admin credentials for a disposable instance. The suite creates its own users
  and never runs against a server holding real people's data: it refuses to start unless
  `/.well-known/dulcet` reports `testMode: true` (README §20, Testing profile), which a server can
  only be started with and never on data served without it.
- **Mutations the suite needs** (retag a file, move a file, delete one, add one, trigger a scan) go
  through a small adapter: by default, file operations on the mounted library directory plus
  `POST /admin/scans`. A **merge** is made the same way: write a second album with the first one's
  MusicBrainz release id and scan; the contract says that is a merge and the first-issued id
  survives.
- **Conditions only a hook can create** use the Testing profile, all with an admin token:
  sign-in and device approval without a person (`autoApprove`), a verified client with no network
  (`trustedClients`), a short journal retention, short signed-URL lifetimes, a low rate limit, a
  webhook receiver beside the suite with a seconds-long retry schedule (`webhooks`), and
  checkpoint-and-restore. Checks that need a hook fail
  (never skip) for a Testing claim and are listed as not run for other claims.
- **The server's log** is read from wherever the operator captured it: `--server-log <file>`, or
  `--server-log-command <cmd>` for a container (`docker logs …`). No API exposes logs.
- **Output.** JUnit XML (one test case per check id) plus a JSON report and a one-line summary:
  `DULCET-CONFORMANCE suite=1.0.0 profiles=Core,Sync passed=N failed=M`.

## Running the suite

Build the runnable JAR once (JDK 17 or newer), then point it at a disposable instance started in
test mode:

```sh
./gradlew :dulcet-api-conformance:fatJar
export DULCET_TOKEN=...          # user A: catalog:read media:read user:read user:write user:delete plays:write account
export DULCET_TOKEN_B=...        # user B, a second ordinary user
export DULCET_ADMIN_TOKEN=...    # an admin, for scans, source visibility and the test hooks
java -jar dulcet-api-conformance/build/dist/dulcet-api-conformance.jar \
  --base-url https://music.test --profile Core,Sync,Device,Testing,Queue,Events,Webhooks,MCP \
  --webhook-listen 0.0.0.0:8099 --webhook-url https://hooks.music.test \
  --library-dir /srv/music/conformance --server-log-command 'docker logs dulcet-test' \
  --server-start-command './start-test-mode-on-served-data.sh' \
  --server-restart-command 'docker restart dulcet-test' --require-complete \
  --junit conformance.xml --report-json conformance.json
```

- **Credentials come only from the environment.** The suite refuses a `--base-url` that carries
  user info or a query, and it never puts a token in a URL. Everything it prints or writes passes
  through redaction.
- **Test mode only.** The suite writes favourites, ratings, plays, playlists, tokens and library
  files, changes a user's sources, and restores checkpoints. It reads `/.well-known/dulcet` first and
  runs no check unless that says `testMode: true`; otherwise it exits `2`.
- **Each check prints one line**, `PASS`, `FAIL`, `SKIP` or `MANUAL`, with its id and the contract
  text it proves; a skip says which input it needs, and `MANUAL` marks a check that needs a step
  outside the API (starting or restarting the server) which the run was given no command for. `--list` prints every check; `--only` runs a subset;
  `--profile Core` limits the run to one profile.
- **Every response is validated** against the bundled `openapi.yaml` (or `--contract FILE`). A
  response that departs from its operation's schema fails the check that received it.
- **Test hooks.** With the admin token the suite sets the Testing overrides it needs for one check
  at a time (short signed-URL lifetimes, a one-second journal retention, a low rate limit) and puts
  the previous overrides back afterwards; the run ends with `{}`. Restores use
  `POST /admin/test/checkpoints` and `/{id}/restore`. Merges come from writing a second album with
  the first one's MusicBrainz release id and scanning.
- **OAuth.** By default (`--oauth auto`) the suite lists its client id in `autoApprove` as user A,
  so sign-in and device approval need no person; every other OAuth rule is still checked.
  `--oauth interactive` prints each authorization URL for a person to approve instead, and
  `--oauth off` skips the flow checks. The refresh-token retry window defaults to 60 seconds;
  `--refresh-window-seconds` matches a server configured shorter.
- **Optional inputs widen coverage:** `--library-dir` (a directory the server scans; the suite adds,
  retags and deletes its own files in a subfolder and calls `POST /admin/scans`), `--server-log FILE`
  or `--server-log-command CMD` (the server's log, scanned for secrets and canaries after the run),
  `DULCET_OTHER_INSTANCE_URL` with `DULCET_OTHER_INSTANCE_TOKEN` (a second instance, for foreign
  cursors), and `DULCET_NON_TEST_URL` with `DULCET_NON_TEST_ADMIN_TOKEN` (the same build started
  without test mode, whose hooks must answer `404`).
- **Process control** is outside the API, so the operator supplies it. `--server-start-command`
  starts this build in test mode on a data directory a normal start already served (DA-TEST-3): it
  must exit non-zero within `--server-command-timeout-seconds` (default 120); one still running
  then has started, which fails, and exit `127` (command not found) proves nothing. A
  `--server-restart-command` restarts the server under test and returns (DA-TEST-5); the suite sets
  an override, restarts, waits for discovery in test mode and requires `GET /admin/test/settings`
  to answer `{}`.
- **Queue, Events, Webhooks and MCP.** The Queue checks register their own devices (ids start
  `conf-<run>-`), hold an `/events` stream for each device that must be online, and forget them at
  the end. `--event-timeout-seconds` (default 15) bounds each wait for an event or a delivery, and
  `--quiet-seconds` (default 5) is how long the suite watches for something that must not arrive;
  the heartbeat check waits up to 32 seconds for each of two heartbeats.
- **Webhook receiver.** The Webhooks profile runs its own HTTP receiver inside the suite, on
  `--webhook-listen HOST:PORT` (default `127.0.0.1:0`, an ephemeral loopback port), and registers
  webhooks pointing at a fresh path per check under `/dulcet-conformance/<run>/`. The suite sets
  the Testing override `webhooks.allowPrivateTargets`, so the server delivers to that plain-HTTP
  loopback receiver; when the server runs elsewhere (a container, another host), pass a base URL
  that reaches the listener as `--webhook-url` and the suite appends its paths to it. A server that
  refuses the receiver with the override set fails the webhook checks. The receiver can answer
  `500` (to see a retry) or `307` (to see that redirects are not followed). Retries and
  auto-disable are driven by `webhooks.retryScheduleSeconds` and `webhooks.disableAfterFailures`,
  so they take seconds.
- **MCP.** The suite talks Streamable HTTP to `/dulcet/v1/mcp` as personal tokens it mints with
  exact scopes, and compares `tools/list` field by field with the bundled `mcp-tools.json`; each
  listed tool must carry `_meta["dulcet/scopes"]` equal to the file's `x-dulcet-scope`, and not the
  `x-dulcet-scope` field itself.
- **Verified client.** One webhook check signs in as a verified OAuth client: the suite lists its
  own client metadata document in the Testing override `trustedClients` (client id
  `https://conformance.dulcet.invalid/oauth/trusted-client.json`, redirect
  `http://127.0.0.1/dulcet-conformance/callback`) and auto-approves it, so it needs `--oauth=auto`.
- **Exit codes:** `0` no failures, `1` a failure (or, with `--require-complete`, any `SKIP` or
  `MANUAL`), `2` a usage error or a server not in test mode.

### Claiming compatibility

A claim names the profiles, the suite version and the contract checksum, and links the JSON report
of a run with `--require-complete` that exited `0`: no check failed, skipped or stayed manual
(Goal 3). The report's summary line reads
`DULCET-CONFORMANCE suite=<version> profiles=Core,Sync passed=N failed=0 skipped=0 manual=0 complete=true`.

A Testing claim therefore needs `--server-start-command` and `--server-restart-command`: without
them DA-TEST-3 and DA-TEST-5 report `MANUAL`, which a complete run never accepts. The device
user-code lockout on the HTML verification page is not a suite check; a Device claim records it
from a person's run of the checklist.

### Where 0.1.0 departs from this plan

- **Fixture corpus.** The plan has the suite refuse a server that does not report exactly the
  fixture corpus; 0.1.0 relies on test mode and on catalog content the server already has (at
  least two albums and four tracks), and generates only small MP3 files for its library checks.
- **Negative controls** run against a contract-valid in-memory mock in the suite's own tests, with
  one switch per deliberate fault, instead of a `--break` switch on the reference server. CI
  (`core-ci`, on `ubuntu-latest`) runs the healthy mock (must pass), every fault (must fail its
  check), and a mock without test mode (must be refused) on each change.
- **Process control** for DA-TEST-3 and DA-TEST-5 goes through operator-supplied commands rather
  than the plan's adapter; the self-tests stand in for them with a mock endpoint.
- **Webhooks.** A public name that resolves to a private address is not checked; the suite does
  not control DNS. That `webhooks.allowPrivateTargets` is ignored outside test mode is not checked
  either, since the suite runs only against a server in test mode.
- **MCP.** Prompt-injection handling of track titles and the absence of administration tools are not
  checked beyond the `tools/list` comparison.
- **Not yet covered:** the device user-code lockout (manual) and the Transcoding, Lyrics and Admin
  profiles.

## Server checks, by profile

Each check has a stable id (`DA-<area>-<n>`) so reports and claims can cite it.

### Core

| Area | What is proven |
|---|---|
| Discovery | `/.well-known/dulcet` shape; base URL is `/dulcet/v1`; capabilities without a token show only public data, with a token add `permissions`; `apiVersion` matches `1.x`. |
| OAuth | Code flow with S256 PKCE succeeds; `plain` PKCE, a wrong verifier, a reused code and a mismatched redirect URI are refused; the response carries `iss` and metadata advertises it; refresh rotates; the old refresh token presented within 60 seconds returns the same new pair, and after the window revokes the grant; revocation stops the access token and its signed media URLs at once, while refreshing the access token does not. |
| Client identity | A client id metadata document is fetched and its name shown; a document behind a redirect, on a private address, over 64 KiB or slower than 5 seconds is not trusted; with the metadata host unreachable the client is shown as unverified, may use only a loopback or reverse-DNS scheme redirect, and is never granted `admin` or `account`. |
| Tokens | Personal tokens carry only requested scopes; a scope above the calling token's scopes or the user's role is refused; a token and a webhook minted under an OAuth grant carry `parentId` and stop working when that grant is revoked; a lifetime above the server maximum is refused; the secret is shown once; prefixes are correct. |
| Delete scope | Every removal (delete playlist, remove entry, replace that drops entries, remove from My Library, delete play) answers `403 insufficient-scope` with `user:write` alone; a batch `libraryRemove` without `user:delete` fails only that op; account deletion needs `account`, `user:delete` and a recent sign-in (`reauthentication-required` otherwise). |
| Header-only | A token in any query parameter is refused (never accepted, never echoed). |
| Scopes | Every operation is called once with a token lacking its scope and must answer `403 insufficient-scope` with `requiredScopes`. |
| Catalog | Lists page to completion with no duplicates and no gaps against the known corpus; `nextCursor` null only at the end; `ids` batch fetch; every `sort` value orders correctly with ties broken stably; `scope=library` follows the My Library rule. |
| Ids | Ids are unique across types; a retag (title change) keeps the track id; a file move keeps it; a deleted id is not reused, also after a restore; a merged album answers `308` to the survivor; a deleted one `410`, a split one `410` with every successor in `replacedBy`; an id never issued `404`. |
| Pagination under change | Adding and deleting albums in the middle of a walk never makes the walk skip an album that existed throughout. |
| ETags | `If-None-Match` gives `304`; a catalog change gives a new ETag; favouriting an album changes its ETag for that user only, and not its `revision`; ETag responses carry `Cache-Control: private` and `Vary: Authorization`; on playlists and the queue the ETag equals the quoted revision; `If-Match` mismatch gives `412` with `current`; replacing playlist items or the queue without `If-Match` gives `428`; after a restore, no earlier ETag answers `304`. |
| Unknown input | Unknown query parameters and unknown body fields are ignored, not rejected; every write returns the resulting representation. |
| Idempotency | A repeated `POST` with the same key returns the first response and creates one resource; the same key with a different body gives `422`. |
| Artwork | Image bytes with a real image media type; the artwork id changes when the art changes; `private` `immutable` caching; a signed artwork URL is identical across two requests within `artworkUrlTtlSeconds`. |
| Playback | A direct plan's URL serves the original bytes (checksum) with `Range` 206 and 416; expired and tampered grants give `403 media-grant-expired`; a revoked token kills its grants; the grant never appears in any response other than the plan; a decision records no play. |
| Item state | Favourite, rating and My Library writes are idempotent and keep first timestamps; removal of a missing or unknown id succeeds; `POST /me/batch` reports per-operation results; a write with a `Dulcet-Action-Time` older than one already applied answers `applied: false` and changes nothing. |
| My Library | All `dulcetLibrary` semantics: per-item membership, no cascade, whole-library counts, newest-first order with id tie-break, isolation between users. |
| Plays | A play is counted once per id however many times it is submitted; a deleted play does not come back when resubmitted; an unknown track gives `unknownTrack`; now playing changes no count, expires after its TTL, and is not shown to other users until they share. |
| Playlists | Entry ids allow duplicates to be moved and removed exactly; every edit changes `revision`; a walk across an edit answers `409`; non-owners see `readOnly`. |
| Account | `PATCH /me` changes the display name and settings; a password change revokes other tokens unless kept; account deletion removes the user's data and tokens. |
| Export | The export parses as JSON Lines and holds exactly the user's favourites, ratings, library, playlists and plays, and no other user's. |
| Errors | Every non-2xx body is `application/problem+json` with a known `type` for its status. |
| CORS | Preflight allows the documented request headers and exposes the documented response headers; no API response sets a cookie. |
| Logging hygiene | Canary tokens, passwords and grants are sent through every endpoint; the server's own log (collected through the adapter) must not contain any canary. |
| Rate limits | When the server's limiter is lowered through the adapter, a burst gets `429` with `Retry-After`. |

### Sync

- `start=now` returns no changes and a usable cursor.
- `start=snapshot` delivers every visible entity of the corpus exactly as the catalog endpoints do,
  then the `changes` phase; changes made **during** the snapshot (adds, deletes, favourites) all
  arrive; nothing is missing at the end, compared against a fresh catalog walk.
- Every kind of change appears: catalog add, retag, delete (`reason: deleted`), merge and split
  (`reason: merged`, `replacedBy` list), each item-state field, playlist edit, play, queue change.
- **Merges carry state.** A user who favourited, rated, played and collected both albums of a
  merge, and holds them in a playlist and the queue, sees the survivor's `itemState` with the
  documented merge rules, the playlist and queue upserted with entry ids kept, and plays pointing at
  the survivor, all before the tombstone in feed order.
- **Visibility.** Taking a source away from a user produces `reason: hidden` tombstones for its
  entities (or `410`); giving it back produces upserts.
- **Replay.** Sending the same cursor twice returns the same changes or their later state; a
  client applying both reaches the same state.
- **Types.** A cursor sent with a different `types` value gives `400 cursor-types-mismatch`; a type
  the token cannot read gives `403`; empty pages with `hasMore: true` are followed to the end.
- A `Dulcet-Sync-Cursor` from any read, followed by the feed, reproduces the current state.
- User isolation: user A never sees user B's state, nor a source A cannot access.
- A cursor older than retention (shortened through the adapter) gives `410 cursor-expired`.
- **A real restore.** The adapter backs up the server's data, the suite makes changes and new
  items, and the adapter restores the backup. Then: `instanceId` changed; every earlier cursor
  gives `410`; no earlier ETag answers `304`; no earlier revision passes `If-Match`; no id issued
  after the backup names a different item.
- Atomicity: a scan's changes become visible in the feed and the catalog together, never one
  without the other.

### Testing

- Discovery says `testMode: true`; the hook paths answer `404` on the same build started without
  test mode, and `403` to a non-admin token in test mode.
- A server refuses to start in test mode on data first served without it (checked through the
  adapter).
- `autoApprove` still enforces PKCE, exact redirects, `iss` and scope limits.
- Overrides end when the server restarts.

### Other profiles

- **Device:** codes, `authorization_pending`, `slow_down`, expiry, approval (through `autoApprove`)
  and denial. The five-wrong-entries lockout is on the HTML verification page and stays a
  **manual** check, run by a person with the report's checklist.
- **Transcoding:** a profile without the source codec gets a transcode in a requested target; a
  bitrate limit is honoured (bits per second); `lengthExact: false` plans really are estimates;
  `offsetMs` starts at the right time (within one frame) and `streamDurationMs` matches the bytes.
- **Lyrics:** sidecar, embedded and absent cases; synced times with offset applied; languages.
- **Queue:** one queue per user seen identically by two devices; a playback report from a
  non-active device gives `409 not-active-device` with the queue; position reports do not change
  `revision`; a stale `If-Match` gives `412` with `current`; a transfer moves the active device and
  the old device gets a `queue` event; two simultaneous transfers leave exactly one active device
  and both devices agree which; an automatic transfer with a stale `If-Match` gets `412`; a command
  reaches the active device's stream once and answers `202`, and `409 device-unavailable` with no
  active device; an offline device reconnecting with a stale base revision gets `412` and the
  server state is unchanged.
- **Events:** each event type fires for its trigger, and its `data` validates against its schema; `Last-Event-ID` resumes; a revoked token gets
  `revoked` and a closed stream; heartbeats arrive in time.
- **Webhooks:** signature verifies with the shown secret; retries on 500 follow
  `webhooks.retryScheduleSeconds`; the hook is disabled with `delivery-failures` after
  `webhooks.disableAfterFailures`; private-network and `http://` targets answer
  `422 webhook-target-refused` without `allowPrivateTargets`, including a public name that resolves
  to a private address and a target that redirects; an event type outside the token's scopes
  answers `403 insufficient-scope`; a webhook made under a verified client's OAuth grant is deleted
  when that grant is revoked; the body validates against the contract's `webhooks` schema.
- **MCP:** initialization and tool listing per scope (no write tools without `user:write`, no
  removal tools without `user:delete`); `tools/list` equals `mcp-tools.json` filtered by scope,
  with `x-dulcet-scope` replaced by `_meta["dulcet/scopes"]`;
  every tool's input and output validate against those schemas; every `user:delete` tool carries
  `destructiveHint: true`; every tool's result matches the equivalent API call; no tool can
  administer or read another user's data; requests with a foreign `Origin` are refused; a session
  cannot be used with another token; injected instructions in a track title come back as plain
  data.
- **Admin:** user create, update, delete; role removal revokes dependent tokens; scans report
  progress and counts.

## Client checks

A client cannot be tested by calling it, so the suite also ships a **reference mock server**: a
scriptable Dulcet API server, built from the contract, that records every request. Client authors
drive their own app (UI tests or a headless harness) through named scenarios, and the mock judges
what the client did.

| Scenario | The client must |
|---|---|
| Discovery fallback | Use Dulcet API when discovery succeeds, and not when it 404s. |
| Header-only | Never send a token in a URL; never send `Authorization` across origins after a redirect; refuse an HTTPS-to-HTTP redirect. |
| Forward compatibility | Keep working when every response carries unknown fields, unknown enum values, unknown change and event types, and unknown problem types. |
| Paging | Follow cursors to the end; not stop on a short or empty page with a cursor. |
| Sync | Store and resume the cursor; react to a `changes` event; restart cleanly on `410`, keeping unsent writes; follow `replacedBy` lists; drop `hidden` entities. |
| Auth | Reject an authorization response whose `iss` differs; survive a lost refresh response by retrying within the window. |
| Queue | Stop audio on `not-active-device` or a `queue` event naming another device; never retry an automatic write after `412`; send `If-Match` with the base revision when reconnecting from offline. |
| Offline writes | Send `Dulcet-Action-Time` on writes queued while offline. |
| Retries | Reuse the `Idempotency-Key` and the play id on retry; honour `Retry-After`. |
| Media | Request a new plan when a grant expires mid-session; not treat an estimated length as exact. |
| Plays | Submit only plays past its threshold; never submit for a URL it only resolved. |
| Redaction | Never write a canary token or grant to its logs (checked through the client's own log export). |

## Negative controls

The suite's own tests hold the controls in use today: each deliberate fault of the in-memory mock
must fail the check that covers it. The Queue, Webhooks and MCP controls are: a stale queue write
accepted (DA-Q-2, DA-Q-6, DA-Q-10), a device that is not active moving the position (DA-Q-3,
DA-Q-4), an unsigned delivery (DA-WH-3, DA-WH-4), an MCP delete without `user:delete` (DA-MCP-4)
and a webhook that outlives its token's revocation (DA-WH-8). For the rules settled on 2026-10-13:
a private target accepted without `allowPrivateTargets` and a refused target answered `400`
(DA-WH-2), a retry that ignores `retryScheduleSeconds` (DA-WH-5), a webhook for unreadable events
accepted and filtered (DA-WH-7), a webhook disabled rather than deleted on revocation (DA-WH-8,
DA-WH-10), `trustedClients` ignored (DA-WH-10), a webhook never auto-disabled (DA-WH-9), `tools/list`
without `_meta["dulcet/scopes"]` (DA-MCP-3), presence that needs an event stream (DA-Q-12), and a
default `/events` stream refused instead of filtered (DA-EV-9).

The reference server gets a `--break=<check-id>` switch that disables exactly one behaviour (for
example, accept a token in the query, reuse a deleted id, drop tombstones). The suite's own CI runs
every check against the healthy reference server (must pass) and against the matching broken one
(must fail). A check without a working negative control does not ship.

## Versioning of the suite

The suite is versioned separately (`1.0.0`, …), and each release names the API minor version it
covers. A new suite version may add checks for new minor-version features, and a claim always names
the suite version it passed. Checks for v1 behaviour are never loosened.

## Build order

1. Contract validation harness, discovery, OAuth and tokens.
2. Catalog, ids, pagination under change, ETags, errors.
3. Media and plays.
4. Sync.
5. Item state, My Library, playlists, export.
6. Remaining profiles, client mock, negative controls in CI.

Dulcet Server's own CI runs the suite on every change to `:server`, on `ubuntu-latest`, against a
disposable instance.
