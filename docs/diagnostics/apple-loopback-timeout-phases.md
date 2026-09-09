# Apple loopback timeout phase evidence

## Scope and current conclusion

**OBSERVED (source diff):** this change instruments the Darwin proxy authentication conformance
case and the Apple download integration test's library browse, the largest measured apple-ci failure
mechanism (3 of 15 unique failure logs, 20%). Existing request timeouts and retry policy are unchanged.
This is not a no-behavior-change claim: failed browse awaits sequential probes that can add roughly
ten seconds before publishing the failure and trace; proxy failure adds a bounded three-second
observation fetch. Successful browse now requires header/body diagnostic evidence, and proxy success
requires a positive challenge count and an empty authorization list beyond the previous status check.
`tools/conformance-env/await-library-ready` and the relative order of existing workflow steps are unchanged.
The failure-publication and proxy controls are now additional CI steps.
The three reported failures remain separate cases. **Instrumented, mechanism still unknown.**

**ASSUMED / unproven:** the original CI stalls will recur with these diagnostics enabled. No local
control below reproduces their cause, establishes a shared cause, or measures their CI incidence.

## Read the next occurrence

**OBSERVED (implementation):** `PROXY AUTH TEST` emits a monotonic timeline while the test runs,
covering ambient credential setup, connector entry/return, the observation request, and cleanup.
The existing failure wrapper retains the timeline and its separately bounded observation fetch.
The fixture records challenge count and received authorization values in memory before sending
407; it emits no `PROXY AUTH challenge received` or `PROXY AUTH 407 sent` lines. The test retrieves
that state from `/observations/proxy-auth`; recording a challenge does not establish that the response
was sent or observed. A passing test requires its observation response to contain a positive challenge count and
an empty authorization list. An observation count of zero means the handler recorded no challenge;
it does not prove whether a socket connected.

**OBSERVED (implementation):** `LIBRARY BROWSE` is enabled only by the integration test's explicit
`AppleLibraryBrowseClient(diagnosticObserver:)` constructor. The default client has no observer or
response-phase plugin. The stream covers:

- Swift entry, Kotlin facade entry, coroutine entry, and transport creation.
- Per-request authentication material preparation, target-policy resolution, HTTP entry, receipt of
  headers, buffered body completion, and HTTP failure. Each request has its own atomic numeric ID;
  concurrent `getAlbum` requests remain distinguishable. Redirect hops retain that request ID.
- Music-folder, artist, album-page and album parsing; transport close; DTO creation; Kotlin completion
  entry/return; Swift completion and continuation resumption.

**OBSERVED (implementation):** endpoint names are allowlisted. The stream contains no URL, query
string, parameters, raw headers, response body, opaque library IDs, or exception text. Swift captures
timestamps and queues events in memory, retains the last 128, and exposes them through `summary`.
The failed track-unwrapping assertion publishes that summary after browse completion and the probes.
There is no live Swift stderr writer. A killed runner loses this in-memory tail. If completion never
returns, or the runner dies before the assertion evaluates, there is no live browse trace to recover.
Earlier events can also fall out of the 128-event window. A passing browse asserts header and body
completion evidence but does not publish a trace or claim a timeout occurred.

**ASSUMED (interpretation of a future trace):** a request with `http-started` but no
`headers-received` has not reached the instrumented receive phase. This alone cannot distinguish
connection establishment, server handling, or engine scheduling. `headers-received` without
`body-completed` narrows the pending work to body acquisition/buffering or subsequent processing
before that marker. Neither pattern names a root cause. Use request IDs, terminal events and the
handler/server observations; do not substitute nearby successful requests for the failed request.
**OBSERVED (induced JVM timeouts):** a listening socket that never accepts and a handler that
receives the request but withholds headers both produce `http-started` → `http-failed` at about
30 seconds. Sending headers and withholding the body produces `http-started` → `headers-received`
→ `http-failed`. This is a **two-way split: before versus after headers**, not a three-way separation
of never arrived, slow handler and response not observed. There is no request-correlated library
handler arrival/send observation. Adding one would require forwarding a safe diagnostic request ID
to the disposable library server (or an instrumented intermediary), recording arrival and header-send
against that ID, and retrieving those records independently. Proxy counters do not supply this for
Navidrome library requests. That additional instrumentation is outside this change.

Timestamps are process-local monotonic elapsed times; do not subtract timestamps from different
processes as though they had a common origin.

## Failure-publication regression controls

`tools/test-download-browse-failure` builds the actual macOS Kotlin framework and compiles the
integration test's `loadLiveTrack` path through the unwrapping and completion assertions, including
the actual observer forwarding, continuation and assertion message expressions. A loopback socket
receives the request and withholds the response until the real 30-second browse timeout returns.
The control captures the message passed to the failed `XCTUnwrap` call and requires timeout,
`http-started`, `http-failed`, and Swift/facade/completion evidence in that message. It substitutes
the assertion sink (to inspect the message without failing the enclosing run) and post-failure
reachability probes (already tested separately); it does not substitute the browse client or events.
It does not exercise XCTest's reporter or xcresult persistence, the later downloadable-track
conversion, or the probe implementation. The standalone executable uses macOS Darwin, not iOS.

**OBSERVED (local Darwin control):** sending headers and withholding the body did not deliver
`headers-received` to this hook before timeout. The two-way socket result above is JVM evidence;
it does not establish Darwin header-phase visibility during a stalled body. The Apple publication
control withholds the entire response and makes no claim to distinguish those two Darwin stalls.

`LibraryBrowseDiagnosticsTest.failedBrowseRetainsTerminalHttpEvidence` uses the real JVM browser,
receives and withholds the response, and asserts a timeout result plus terminal HTTP evidence before
releasing the socket. This adds failed-browse coverage to the existing successful socket controls.

`tools/test-proxy-challenge-output` loads the real fixture module, starts its actual HTTP handler and
state recorder on a loopback socket, and requests a challenge with an authorization canary. Broken
and blocked stdout must still yield 407, the exact `Proxy-Authenticate` header and an empty response
body, while the real state reports one challenge and that canary. The control does not replace the
recorder or discard response headers.

## Local controls

**OBSERVED:**

```text
./gradlew :core:jvmTest --tests '*LibraryBrowseDiagnosticsTest' --tests '*LibraryBrowseTest'
BUILD SUCCESSFUL in 6s
LibraryBrowseDiagnosticsTest: tests=2 failures=0 errors=0; time=0.162s
LIBRARY DIAGNOSTIC CONTROL handler-held=headers observed=true
LIBRARY DIAGNOSTIC CONTROL handler-held=body observed=true
```

The socket handler itself signals that it received the authenticated request and withheld either
headers or body. Assertions run while the response remains pending; the test releases the handler
only after checking the appropriate markers. The response then completes, and diagnostics are checked
against credential, query and response-header canaries. A separate control checks that two album
requests have distinct IDs and that an unexpected endpoint containing a query is rendered as `other`.

**OBSERVED (negative control):** initially placing the marker in Ktor's `onResponse` hook failed the
withheld-body control with `TimeoutCancellationException: Timed out waiting for 5000 ms`. Inspection
of the pinned Ktor 3.5.2 bytecode showed `SaveBody` at receive phase `Before` and `onResponse` at
`State`. Installing the diagnostic phase before `Before` passed both controls without changing the
request's buffering path. This names an instrumentation placement error, **not** a mechanism for
any of the reported CI failures.

**OBSERVED:** against a freshly started `redirect-server --port 4543 --forward-proxy-auth`:

```text
./gradlew :core-conformance:macosArm64Test --tests '*DarwinProxyAuthenticationConformanceTest*'
BUILD SUCCESSFUL in 11s
PROXY AUTH TEST 42.744833ms: handler challenge asserted count=1; Proxy-Authorization absent
PROXY AUTH TEST 42.799250ms: cleanup completed
```

**OBSERVED:** repository checks:

```text
python3 tools/parity_gate.py
parity gate valid: 6 feature rows
python3 tools/verify_ci_policy.py
CI policy valid across 6 workflows
python3 tools/test-redirect-credential-detector
redirect oracle defence-in-depth mutation controls pass
```

**OBSERVED:** the full default-client JVM suite also passed:

```text
./gradlew :core:jvmTest
BUILD SUCCESSFUL in 7s
XML aggregate: tests=183 failures=0 errors=0 skipped=0
```

**OBSERVED:** final source was built for the iPhone simulator and the real CONF-51 test ran against a
fresh disposable database on `127.0.0.1:4533`, after verifying ownership of the listening process:

```text
xcodebuild build-for-testing -project apple/Dulcet.xcodeproj \
  -scheme DulcetiOSDownloadIntegration -configuration Debug \
  -destination "platform=iOS Simulator,id=$SIMULATOR" \
  -derivedDataPath "$DERIVED_DATA" -parallel-testing-enabled NO
** TEST BUILD SUCCEEDED **

tools/conformance-env/await-library-ready --base-url http://127.0.0.1:4533 \
  --after-last-scan 1970-01-01T00:00:00Z
library ready: scanning=false lastScan=2026-09-07T13:42:36.060145Z advanced_from=1970-01-01T00:00:00Z count=314 queryable_albums>=1

TEST_RUNNER_DULCET_CONFORMANCE_BASE_URL=http://127.0.0.1:4533 \
TEST_RUNNER_DULCET_CONFORMANCE_DISPOSABLE=true \
xcodebuild test-without-building -project apple/Dulcet.xcodeproj \
  -scheme DulcetiOSDownloadIntegration -configuration Debug \
  -destination "platform=iOS Simulator,id=$SIMULATOR" \
  -derivedDataPath "$DERIVED_DATA" -resultBundlePath "$RESULT" \
  -parallel-testing-enabled NO \
  -only-testing:DulcetiOSDownloadIntegrationTests/DulcetAppleDownloadIntegrationTest/downloadTriggerPromotesValidatedResponseAtomicallyOnIOS
CONF-51 IOS DOWNLOAD trigger=platform-executor response=validated promotion=atomic exact_bytes=5257
Executed 1 test, with 0 failures (0 unexpected) in 2.352 (2.353) seconds

python3 tools/verify-xcode-test-execution "$RESULT" \
  DulcetiOSDownloadIntegrationTests.DulcetAppleDownloadIntegrationTest \
  downloadTriggerPromotesValidatedResponseAtomicallyOnIOS
xcode test execution valid: test=DulcetiOSDownloadIntegrationTests.DulcetAppleDownloadIntegrationTest/downloadTriggerPromotesValidatedResponseAtomicallyOnIOS terminal=Passed individual-results=1
```

**OBSERVED:** the real trace reaches `headers-received` and `body-completed` for the browse requests,
then `albums-parsed`, `transport-close-returned`, `dto-created`, `completion-returned`, and
`swift-continuation-resumed`. The original timeout did not occur. Setup attempts before this run
encountered an occupied fixture port and then a missing readiness-mode argument; neither executed an
iPhone test. The final run used a new empty database. No CI run was launched, and no mechanism fix is
claimed. macOS/iPadOS download execution and member 3 were not tested in this change.
