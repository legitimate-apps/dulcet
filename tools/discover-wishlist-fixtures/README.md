# Discover and Wishlist disposable fixture

This is the finite test environment for design §7 step 2. It owns a fresh Dulcet Server,
Navidrome, generated music, scripted upstream and fault proxy. It accepts binary paths, never
an existing server URL or data directory. Every upstream base is set to the local scripted
server; an unscripted path fails. No live MusicBrainz, ListenBrainz or CAA request is made.

## Start and stop

Prerequisites: Python 3.12+, Java 17+, ffmpeg/ffprobe, `lsof`, and a native Navidrome executable
at the version in `tools/conformance-env/pins.json`. Darwin can provision that binary with
`tools/conformance-env/prepare-darwin --output <new-directory>`; Linux runners provide a native
binary at the pinned version. All servers bind loopback. The wrapper witnesses both native
server listening PIDs with `lsof` before any bootstrap or test write.

Build once, **outside** the wrapper:

```sh
./gradlew :server:installDist
export DULCET_DISCOVER_NAVIDROME_BIN=/path/to/pinned/navidrome
# Optional: DULCET_DISCOVER_FFMPEG and DULCET_DISCOVER_TMPDIR.
tools/ci/with-discover-wishlist-fixture --evidence /path/to/new-report -- <command> <arguments>
```

The wrapper uses dynamic ports and a new temporary root. `--root <new-path>` selects its location;
an existing path is refused. It starts only after checking the installed server distribution.
It never invokes Gradle. `--evidence` also requires a new path. The report retains redacted server
logs, `ready.json` (URLs, PIDs, healthy baseline and monotonic cold-start duration), and
`cleanup.json` (reaped child PIDs and deleted data root). Credentials/admin tokens stay out of
reports. The private proxy configuration and CLI-created personal admin token are destroyed
with the data. The admin token is only for the documented Testing settings/checkpoint hooks;
there is no OAuth flow or auto-approval. The loopback artwork override is confined to this
Testing-profile process.

Success, command failure, SIGINT and SIGTERM use the same cleanup. Each started process is tracked
before readiness is published; signal handling is deferred across spawn bookkeeping without
masking signals in the child. Cleanup terminates/waits, then kills a PID only if its grace period
expires. It never searches for a process name. The command is tracked as well as the four fixture
processes. The self-test keeps an unrelated live process as a positive cleanup control.

The child command receives:

| Environment variable | Meaning |
|---|---|
| `DULCET_DISCOVER_SERVER_URL` | Real Dulcet Server through the app fault proxy |
| `DULCET_DISCOVER_NAVIDROME_URL` | Real Navidrome through the same observed proxy, `/navidrome` prefix |
| `DULCET_DISCOVER_CONTROL_URL` | Loopback control origin |
| `DULCET_DISCOVER_USERNAME`, `DULCET_DISCOVER_PASSWORD` | Fresh owner credentials |
| `DULCET_DISCOVER_OTHER_USERNAME`, `DULCET_DISCOVER_OTHER_PASSWORD` | Independent owner on Dulcet Server |
| `DULCET_DISCOVER_DISPOSABLE` | `true`; conformance refuses an absent marker |

No tool falls back to a preconfigured server. Android runners reverse the proxy's dynamic TCP port
before launching the emulator app. Apple/Android shell builders add their native runner modes to
`tools/ci/run-discover-wishlist-proofs`; those modes currently fail explicitly rather than synthesize
UI evidence. `components` runs the existing Tasks 2–6 transport/outbox probes; `sessions` runs the
intentionally unfinished Task 7 session probes. `DULCET_DISCOVER_GRADLE_RUNNER` can select the caller's
build-capacity wrapper; otherwise the runner uses `tools/run-gradle-exclusive`.

## Corpus and upstream

Music and the 64×64 PNG are generated on every fresh start. The music consists of short sine waves
with synthetic names and MusicBrainz tags. It includes one 8-of-12 album, one held 12-of-12 album,
and a separately staged arrival file. No audio, image download or copyrighted catalog is stored in
the repository. Initial metadata reads warm the first three album pages through public REST before
the checkpoint, so partial/held/unknown availability is present after reset.

`fixture.py` generates 24 artists, 24 release groups, 24 search recordings, two editions per known
group and 12 slots per edition. One group has no editions. `upstream.json` preserves the checked
ListenBrainz response shapes using generated names/UUIDs, not captured personal data. It includes
public charts, fresh releases and empty public-playlist/similar-artist results for later fixtures;
step 2 does not build shelves or playlist pages. The upstream implements MusicBrainz search,
artist relations, discography, release groups and editions, plus a CAA redirect to generated PNG
bytes. The app's artwork bytes travel through Dulcet Server's actual signature/address policy.

The owner has 23 initial wishes, including requested/searching/unavailable states and a plain-text
note, so complete reads must page. The other owner has a separate sentinel wish. `future-metadata`
adds a future kind/status and missing MBIDs through the fault proxy, for open-output/neutral UI
proofs. It never changes the real catalog matching rules.

## Acknowledged controls

Controls are loopback test endpoints, independent of app credentials. They remain available while
the app connection is offline. JSON requests/responses only:

- `POST /control/scenario {"name":"rate-limit-120"}` returns `scenario`, monotonically increasing
  `revision`, `armed=true`, and initial `hits=0`. Unknown names fail 400 without changing revision.
- `GET /control/state` returns the current revision, health and injection count. Every effect names
  its endpoint/scenario/revision. An arm acknowledgement is not proof that a fault fired: require
  an effect at that revision before asserting the app handled it.
- `GET /control/requests?since=0` returns `requests[]` and `next`. Rows contain sequence, channel,
  endpoint and revision only. There are no URLs, auth parameters, headers or request bodies.
  Save `next` before a trigger to examine its suffix; a scenario switch does not erase evidence.
- `GET /control/baseline` performs successful authenticated Navidrome `ping`, a nonempty catalog
  read, and extension detection through the observed proxy. It refuses an empty catalog or a
  server advertising Discover/Wishlist. Require that positive sentinel before concluding that a
  subsequent feature-request list is empty.
- `POST /control/reset {}` restores the server's initial Testing checkpoint and acknowledges
  `reset=true` plus the real new `identity`. It preserves the initial 23 wishes and reapplies the
  in-memory loopback artwork override. **Restore changes instanceId.** Start a fresh account/client
  per test after reset. Scenario switches alone do not reset user data or identity.

`scenarios.json` is the scenario inventory. Effects:

| Scenario | Trigger and observed effect |
|---|---|
| `normal` | Forward the real server response |
| `delayed-old-query` | A query containing `old` waits 750 ms; a cancelled request can still answer |
| `rate-limit-120` | Search returns HTTP 429 with Retry-After 120 |
| `stale-answer` | Real search data is labelled stale with original age 7200 seconds |
| `offline` | Close app sockets; independent server/control operations continue |
| `lost-add-receipt` | Forward one successful add, record `delivered=true`, then close its response |
| `scan-arrival` | Install the staged tagged file, call real `startScan`, await scanning/fulfilment |
| `remove-before-add` | Emit a revision-bound `held=true` effect, then hold the in-flight add for the observed cancellation, at most two seconds |
| `identity-replacement` | Restore through the real Testing hook and return changed instanceId |
| `future-metadata` | Return neutral future kind/status and unwishable missing-MBID rows |

Stale/429/future-metadata are acknowledged wire faults, not evidence of server TTL behavior. Scan,
matching, wishes, owner isolation, My Library membership, artwork validation and restore run in the
real disposable server. The cancellation self-test proves remove-before-add leaves the generation
removed; the lost-response test also scans, observes Arrived/My Library, removes, retries the old add
and independently reads membership again.

## Tests and pending session integration

```sh
python3 tools/test-discover-wishlist-fixture --junit /path/to/fixture.xml
tools/ci/with-discover-wishlist-fixture -- tools/ci/run-discover-wishlist-proofs components
tools/ci/with-discover-wishlist-fixture -- tools/ci/run-discover-wishlist-proofs sessions
```

The Python suite exercises acknowledged faults, paging/artwork/availability, positive Navidrome
readiness, real scan/lost-response cancellation, restore/reset and PID-only cleanup on success,
exit 7 and SIGTERM. It writes JUnit XML with named cases. Read the XML counts, not just the process
exit code. Fixture entry points disable bytecode before loading local modules, including in child
processes, so extensionless script caches cannot enter the recursive CI control inventory. A focused
source-loading check needs no servers:

```sh
python3 tools/test-discover-wishlist-fixture --test ModuleLoadingTests --junit /path/to/module-loading.xml
```

Run `python3 tools/verify_ci_policy.py` after the complete self-test. Task 20 must wire the real
`tools/test-discover-wishlist-fixture` into a direct blocking workflow command after provisioning
its pinned Navidrome/ffmpeg dependencies and building `:server:installDist`; until that integration,
policy reports this legitimate unwired control. Adding a workflow invocation is separate from
preventing generated bytecode from masquerading as a control.

`DiscoverWishlistTransportComponentsTest` reaches the actual Tasks 2–6 REST adapters and durable
outbox. Its direct component probe does not claim production account/fence integration. Three
`DiscoverWishlistConformanceTest` cases use Task 7's `LibraryReaderSession.openDiscoverClient`:
production reader/outbox round trip, no additional feature traffic on Navidrome, and production-client
scan arrival/lost-response removal. The Navidrome case observes the healthy baseline first. None is
skipped, and no parallel `DiscoverClient` or identity fence substitutes for the production session.


## Android phone proofs

Build `:server:installDist` once outside this wrapper. Boot and lease a disposable phone emulator
separately, source its SDK environment, set `ANDROID_SERIAL` to its explicit `emulator-*` serial,
and set `DULCET_DISCOVER_GRADLE_RUNNER` to the caller's build-leased Gradle wrapper. Then run:

```sh
tools/ci/with-discover-wishlist-fixture -- tools/ci/run-discover-wishlist-proofs android
```

The Android helper builds/installs the real DEV app and instrumentation APK, executes five named
methods in ten independent phases, and writes terminal-record JUnit XML, redacted fixture traces,
process-PID evidence and 800-pixel JPEG captures. Light/dark use font scale 1.0; largest uses dark
appearance and system font scale 2.0. It restores font/night settings, removes its own adb reverse,
and stops only the DEV app's positively identified PID. The caller owns emulator cleanup.
`DULCET_DISCOVER_ANDROID_OUTPUT` selects a new evidence directory; the default is unique under
`build/discover-wishlist-android/`. `run-discover-wishlist-android --self-test` checks positive and
negative terminal records, phase inventory and output ownership without starting a device/server.

For the real-reader host proof, use the same fixture with the leased Gradle runner:

```sh
tools/ci/with-discover-wishlist-fixture -- tools/run-gradle-exclusive \
  :android:app:testDevDebugUnitTest --rerun :android:app:testProdDebugUnitTest --rerun \
  -Pdulcet.discoverWishlistConformance
```

This opt-in selects `PhoneWishlistIntegrationTest` in each variant. Ordinary unit runs exclude it;
missing disposable configuration fails setup rather than skipping. CI/soak integration must select
this Discover fixture/property, separately from the older production-search conformance property.
The host proof recreates the actual reader/SQL store; only the device proof claims process death.
Accessibility assertions cover labeled, reachable Compose actions, not a running TalkBack service.
