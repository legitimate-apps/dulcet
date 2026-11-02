# Disposable HTTPS connections fixture

Build the current server, then run a finite proof:

```sh
tools/run-gradle-exclusive :server:installDist
tools/conformance-env/multipath-fixture --root /tmp/new-multipath-run -- \
  tools/conformance-env/multipath-fixture-check
```

The wrapper accepts no upstream address. It creates two fresh Dulcet Server databases with four
synthetic 90-second MP3 files and artwork, generates a fresh CA and hostname certificates, and
owns all backend/proxy processes. Before readiness it witnesses each backend listener's process
identity and verifies matching/different lineage through OS-validated TLS. It preserves configured
external Host and `/music` prefix through its proxies. The backend has an unrelated `publicUrl`
so route-relative metadata and grants cannot pass merely by matching that default.

The child receives `DULCET_MULTIPATH_MANIFEST`. The JSON contains `standard`, `disposable`, a fresh
`nonce`, `instanceId`, encoded `serverIdentity`, directory `revision`, synthetic `username` and
`password`, `caPem`, `routeA`, `routeB`, `legacyHttp`, `routes`, `controlBase`, and four `tracks` with
opaque id, container, source byte count/hash, duration, album and artwork ids. A sandboxed Apple
host can receive the same document as `DULCET_MULTIPATH_MANIFEST_JSON`. These are disposable test
credentials, never an existing account. A proof validates the marker/nonce, exact fixture kind,
local-only destinations and expected corpus before dialing anything.

Routes `home` and `remote` share the same actual server/database. `different` uses a separately
created server with the same fixture username but different lineage. `bad-name` has a trusted
fixture certificate for an unrelated SAN. `unlisted` is TLS-valid but absent from the directory.
`legacy` is an explicitly attested HTTP ownership anchor, never a selectable alias. The initial
selected HTTPS address stays fixed in normal use; alternate injection belongs only to test code.

The control listener is a separate loopback HTTP listener and keeps answering while media routes
are unavailable. Every control request requires `X-Dulcet-Fixture: <nonce>`:

| Request | Effect |
| --- | --- |
| `GET <controlBase>/state` | Counts by logical route and endpoint, observations, current outages. |
| `POST <controlBase>/scan` | Runs a real scan on PID-owned backend0 using a separate disposable admin identity. Household credentials remain non-admin. Returns the actual changed `getScanStatus.lastScan` stamp; no admin credentials enter the manifest, receipts or logs. Requires the nonce and rejects query/body target overrides. |
| `POST <controlBase>/reset` | Starts a new observation epoch and clears counters and all injected faults. The final sanitized receipt artifact retains every epoch, including earlier positive controls. |
| `POST <controlBase>/outage?routeId=home&state=on` | Closes future requests without a response. |
| `POST <controlBase>/outage?routeId=allHttps&state=on` | Disables every HTTPS media route; HTTP/control remain observed. |
| Same outage request with `state=off` | Restores the route(s). |

Observations record endpoint names, methods, byte counts and presence of authentication in a
channel. They never record query/body/header values. `requests.json` and process logs remain as
evidence after the finite command ends. Fixture signing keys are removed; all owned processes and
listeners stop even on command failure or a signal. An existing root directory is refused and is
never cleaned up or modified.

Native engines retain their production hostname checks. Apple proofs trust only this run's CA
in the disposable runner/device's isolated trust store. A leased Apple simulator uses
`xcrun simctl keychain <UDID> add-root-cert <ca.pem>`; dispose of the owned simulator afterward.
The macOS helper uses a temporary keychain on a hosted CI runner and removes its root/keychain
in a finalizer.

For Android phone and TV, use the finite proof runner on an already-owned disposable emulator:

```sh
tools/ci/run-connection-android-proofs --serial emulator-<owned-port> \
  --root /tmp/new-native-multipath-run --evidence /tmp/new-native-multipath-evidence
```

The Android helper temporarily copies the fresh CA into a generated test-APK raw resource and
replaces the test-only network security configuration's user-root entry with that exact resource.
It restores the original configuration and removes the generated certificate in a finalizer,
including when instrumentation fails. It requires no `adb root` and changes neither device trust
stores nor production network security configuration. Wrong-hostname certificates remain rejected.
The helper reverses each manifest port with `adb reverse tcp:<port> tcp:<port>` so native requests
retain the authenticated `localhost` name, then removes its forwards and disposable device files.
No shared host trust store, VPN, DNS, proxy settings or real device is changed.


Phase-2 proofs pass `--phase2` to add an admitted `tunnel` listener between `home` and `remote`
in administrator priority. All three terminate ordinary fixture TLS and share the owned backend.
The original phase-1 manifest remains the default for its existing proofs.

Additional nonce-protected control POSTs accept `routeId` and `endpoint`:

| Control | Effect and receipt |
| --- | --- |
| `/cut?...&bytes=65536` | Arm a live response cut at that positive byte count, then make the route unavailable. Receipt records actual `responseBytes`, `cut=true`, `completed=false`. |
| `/throttle?...&bytesPerSecond=48000` | Rate-limit delivery so a native player can demonstrate progress before a later cut. Zero removes the throttle. |
| `/lose-reply?...&state=on` | Once the backend replies, drop the reply and make that route unavailable. Receipt records `lostReply=true` and backend status, with zero client response bytes. |
| `/blackhole?...&state=on` | Hold the request without a response for up to ten seconds, beyond the selector's candidate deadline. |
| `/hold?...&state=on` | Hold a completed backend response for at most twenty seconds. `off`, reset and shutdown release it; receipts record `held` and `holdReleased`. |

`off` disables lost-reply or blackhole arming. `/reset` clears the current observation's
receipts, outages and faults and releases held responses. The final receipt file retains history
with an `observationEpoch` so each proof can be audited independently. Every normal response records delivered bytes and completion; no
query, form-body or authorization values are recorded. `tools/test-multipath-fixture-faults`
checks exact cut receipts, a healthy alternate, a lost reply after commit, and a cut armed after
positive live delivery using only its own disposable loopback listeners. These harness checks
are not native playback or download acceptance.

Phase-2 setup requires authenticated `transcodeOffset:1` and records that capability in the
manifest. Stream receipts expose only a bounded numeric `timeOffsetSeconds`, never arbitrary
query values. Discover upstreams use a separately owned loopback stub; fixture startup checks
listener ownership before configuring the disposable server.
