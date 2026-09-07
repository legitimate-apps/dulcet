# Apple loopback timeout phase evidence

## Scope and current conclusion

**OBSERVED (source diff):** this change instruments the Darwin proxy authentication conformance
case and the Apple download integration test's library browse. It does not change
`tools/conformance-env/await-library-ready`, existing timeouts, workflow gates, or retry policy.
The three reported failures remain separate cases. **Instrumented, mechanism still unknown.**

**ASSUMED / unproven:** the original CI stalls will recur with these diagnostics enabled. No local
control below reproduces their cause, establishes a shared cause, or measures their CI incidence.

## Read the next occurrence

**OBSERVED (implementation):** `PROXY AUTH TEST` emits a monotonic timeline while the test runs,
covering ambient credential setup, connector entry/return, the observation request, and cleanup.
The existing failure wrapper retains the timeline and its separately bounded observation fetch.
The fixture's `PROXY AUTH challenge received` and `PROXY AUTH 407 sent` lines are emitted by the
handler. A passing test requires its observation response to contain a positive challenge count and
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
string, parameters, raw headers, response body, opaque library IDs, or exception text. Swift writes
live lines to stderr and retains the last 128 for the track-unwrapping failure message. Its passing
browse asserts that header and body completion markers actually ran. The completion assertion does
not claim a timeout occurred.

**ASSUMED (interpretation of a future trace):** a request with `http-started` but no
`headers-received` has not reached the instrumented receive phase. This alone cannot distinguish
connection establishment, server handling, or engine scheduling. `headers-received` without
`body-completed` narrows the pending work to body acquisition/buffering or subsequent processing
before that marker. Neither pattern names a root cause. Use request IDs, terminal events and the
handler/server observations; do not substitute nearby successful requests for the failed request.
Timestamps are process-local monotonic elapsed times; do not subtract timestamps from different
processes as though they had a common origin.

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
