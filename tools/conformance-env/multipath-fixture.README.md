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

Native engines use their production hostname checks and OS trust. Install only this run's CA in
the disposable runner/device's isolated trust store before launching the proof. For a newly leased
Apple simulator, `xcrun simctl keychain <UDID> add-root-cert <ca.pem>` scopes the root to that device;
dispose of the owned simulator afterward. A macOS host proof uses a temporary keychain on an
isolated CI runner and removes that root/keychain in a finalizer. On a disposable rooted Android
emulator, install the exact CA in its system trust store using the image's supported mechanism;
never add a release network-security exception or trust-all branch. Reverse each manifest port
with `adb reverse tcp:<port> tcp:<port>` so the app retains the authenticated `localhost` name.
The wrapper does not change a shared host trust store, VPN, DNS, proxy settings or a real device.
