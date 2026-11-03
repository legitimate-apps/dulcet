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
| `POST <controlBase>/reset` | Clears observations/counters. |
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
