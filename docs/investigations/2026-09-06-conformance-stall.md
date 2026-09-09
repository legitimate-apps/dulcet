**1. Verdict: production HTTP has deadlines; the historical CI cause remains unresolved.**

OBSERVED — investigation baseline: `git log -1 --oneline` returned `5700afe Name which readiness condition the spike failed on`. Ktor and coroutines are pinned: `rg -n 'ktor =|coroutines =' gradle/libs.versions.toml` returned `8:ktor = "3.5.2"` and `10:coroutines = "1.11.0"`.
OBSERVED — `rg -n 'connectObserved|TimeoutMillis|TIMEOUT_MILLIS' core/src/commonMain/kotlin/com/legitimateapps/dulcet/core/AccountConnection.kt` returned entry at 426/428, three timeout assignments at 447–449, and `813: const val ACCOUNT_REQUEST_TIMEOUT_MILLIS = 30_000L`. `connect` constructs its own client, then negotiates extensions, ping, and user; this is a per-request deadline, not a 30-second overall connect deadline.
OBSERVED — `rg -n 'val extensionResponse|val ping =|val userResponse|candidates =|MAX_REDIRECTS'` on that file returned 501, 521, 565, 1427, and `1158: private const val MAX_REDIRECTS = 5`. Negotiation, redirects and optional HTTPS-to-local-HTTP fallback can add multiple finite request budgets. The explicit HTTP port-1 failure attempts only extension discovery.
OBSERVED — `sed -n '14,55p' core/src/appleMain/kotlin/com/legitimateapps/dulcet/core/AccountHttpClient.apple.kt` shows `HttpClient(Darwin)`, `configure()`, disabled credential storage and explicit completion of both challenge branches. No custom dispatcher or session deadline is installed here.
OBSERVED, upstream source — HTTP timeout [HttpTimeout.kt:138–175](https://github.com/ktorio/ktor/blob/3.5.2/ktor-client/ktor-client-core/common/src/io/ktor/client/plugins/HttpTimeout.kt#L138) launches `request-timeout`, delays the configured duration, then cancels the request execution context. Retrieval: Python `urllib.request.urlopen(raw GitHub URL).read().decode()`; actual source: `delay(requestTimeout.milliseconds)` and `executionContext.cancel(cause.message!!, cause)`.
OBSERVED, upstream source — [CommonHooks.kt:35–52](https://github.com/ktorio/ktor/blob/3.5.2/ktor-client/ktor-client-core/common/src/io/ktor/client/plugins/api/CommonHooks.kt#L35) supplies `Sender(this, client.coroutineContext)`; [HttpClient.kt:1312–1314](https://github.com/ktorio/ktor/blob/3.5.2/ktor-client/ktor-client-core/common/src/io/ktor/client/HttpClient.kt#L1312) supplies `engine.coroutineContext + clientJob`. Same urllib retrieval; these are actual output excerpts. Thus the HTTP timer uses the client/engine context, not runTest's virtual clock.
OBSERVED, upstream source — [DarwinRequestUtils.kt:12–15](https://github.com/ktorio/ktor/blob/3.5.2/ktor-client/ktor-client-darwin/darwin/src/io/ktor/client/engine/darwin/internal/DarwinRequestUtils.kt#L12) calls `setupSocketTimeout`; [TimeoutUtils.kt:15–29](https://github.com/ktorio/ktor/blob/3.5.2/ktor-client/ktor-client-darwin/darwin/src/io/ktor/client/engine/darwin/TimeoutUtils.kt#L15) sets `setTimeoutInterval(it / 1000.0)` and maps `NSURLErrorTimedOut` to `SocketTimeoutException`. Same urllib retrieval, actual excerpts. Effective production inactivity timeout: 30 seconds. Darwin does **not** implement the separate connect timeout ([Ktor support table](https://ktor.io/docs/client-timeout.html)); the whole-request timer covers establishment.
OBSERVED, upstream source — [DarwinSession.kt:32–55](https://github.com/ktorio/ktor/blob/3.5.2/ktor-client/ktor-client-darwin/darwin/src/io/ktor/client/engine/darwin/internal/DarwinSession.kt#L32) creates the URLSession data task, registers cancellation, calls `task.resume()`, and suspends at `response.await()`. Browser open of the raw source returned those statements. URLSession owns the TCP socket; actual socket state and callback scheduling during the failed simulator run are ASSUMED, not observed.
OBSERVED — `rg -n 'parsed.protocol|resolve\(|isIpAddressLiteral' core/src/commonMain/kotlin/com/legitimateapps/dulcet/core/HostResolution.kt` returned HTTPS bypass at 37, resolution at 40/64, literal bypass at 70, resolver call at 73. `rg -n 'actual suspend|getaddrinfo' core/src/appleMain/kotlin/com/legitimateapps/dulcet/core/HostResolution.apple.kt` returned synchronous `memScoped` at 24 and `getaddrinfo(...)` at 36. Policy executes at AccountConnection.kt:762 **before** the client request; neither this resolver nor overall connect has a product deadline. The Apple facade uses `MainScope()` at AppleAccountConnectionFacade.kt:66 and calls connect at 91 (`sed -n '60,100p'`). This is a separate application-level unbounded DNS wait and possible UI blocking defect; an actual forever-blocked OS resolver is ASSUMED. Neither failing test enters this policy DNS path.

**2. Shared machinery does not establish shared load or shared deadlock.**

OBSERVED — `sed -n '280,312p' core-conformance/src/commonTest/kotlin/com/legitimateapps/dulcet/conformance/AccountConnectConformanceTest.kt` shows default `runTest`, `AccountConnector`, and `http://127.0.0.1:1`; this revision has no authentication half in conf06. The supplied invalid-login server event therefore does not locate this revision's suspension point.
OBSERVED — `rg -n 'runTest|HttpClient\(Darwin\)|PROXY_PORT|observation-get' core-conformance/src/appleTest/kotlin/com/legitimateapps/dulcet/conformance/DarwinProxyAuthenticationConformanceTest.kt` returned default runTest at 30, separate observation client at 56, observation GET at 82 and `104: const val PROXY_PORT = 4543`. The forward-proxy wrapper delegates to AccountConnector at AccountHttpClient.apple.kt:74–84. It uses HTTPS and loopback proxy 4543, not the Navidrome fixture or port 1.
OBSERVED, upstream source — [DarwinClientEngine.kt:15–27](https://github.com/ktorio/ktor/blob/3.5.2/ktor-client/ktor-client-darwin/darwin/src/io/ktor/client/engine/darwin/DarwinClientEngine.kt#L15) creates its own session, selects current NSOperationQueue (substituting a fresh queue for main), and executes via the call context. [HttpClientEngineBase.kt:43–46](https://github.com/ktorio/ktor/blob/3.5.2/ktor-client/ktor-client-core/common/src/io/ktor/client/engine/HttpClientEngineBase.kt#L43) uses `config.dispatcher ?: ioDispatcher()`. Raw-source browser opens returned these statements. Shared: engine implementation, default scheduling machinery, native test framework and simulator process/runtime. A shared *instance* or identical queue in the failed runs is ASSUMED.
OBSERVED — `rg 'darwin-session-defaults' /tmp/dulcet-stall-native.log` returned `requestSeconds=60.0 resourceSeconds=604800.0`. Apple documents the same defaults ([request](https://developer.apple.com/documentation/foundation/urlsessionconfiguration/timeoutintervalforrequest), [resource](https://developer.apple.com/documentation/foundation/urlsessionconfiguration/timeoutintervalforresource)); urllib retrieval of their `.md` pages returned `The default value is 60` and `The default value is 7 days`. The proxy observation GET has these OS defaults and **no Ktor HttpTimeout**, so its inactivity timer directly races the 60-second test bound. Actual entry into that GET during the failure is ASSUMED.
ASSUMED predictions — large watchdog gaps and stale test-dispatcher pulses, with sampled runnable workers, support scheduling starvation. Timely pulses plus an overdue request job, cancellation without completion, or sampled locks support timeout/cancellation/engine synchronization trouble. A pending observation GET after product connect returned isolates the harness fixture. A closed product request awaiting test continuation isolates dispatch/teardown. No supplied failed-run observation chooses between these.

**3. Instrument implemented; timeouts and assertions preserved.**

OBSERVED — `rg -n 'TimeSource|observeRequest|snapshot' core/src/commonMain/kotlin/com/legitimateapps/dulcet/core/AccountConnectionDiagnostics.kt` returned monotonic clock at 10, request-job registration at 36 and snapshot at 47. Both failing tests always install it. It emits fixed phase names and elapsed/duration milliseconds for client creation, policy, endpoint send/body, redirect checks, close, proxy challenge callbacks, observation GET/body and credential cleanup. It filters existing URL-bearing request traces; exception messages and credential values are excluded.
OBSERVED — `rg -n 'delay\(|watchdogGap|test-body-exited' core-conformance/src/commonTest/kotlin/com/legitimateapps/dulcet/conformance/StallDiagnostics.kt` returned real dispatcher delay at 25/32, watchdog/pulse ages at 36, and final snapshot at 44. Defaults: heartbeat every 10 seconds, test-dispatcher pulse every 5 seconds. Request snapshots report active/cancelled/completed state. The test clock and original 60-second runTest bounds are unchanged.
OBSERVED — `rg -n 'overdue|sample_binary|40' tools/capture-conformance-stall` returned sample function at 39 and 40-second inactivity check at 97. The host forwards test output/exit status, finds processes opening the exact test executable, and takes one-second `sample` plus `ps` evidence, at most once per 15 seconds while overdue. Heartbeat lines do not reset phase progress. `.github/workflows/apple-ci.yml:866` wraps only the existing iOS conformance invocation; existing failure-artifact rules collect the resulting runner-temp `.log` files. Gradle standard streams are enabled at core-conformance/build.gradle.kts:91.
OBSERVED — JVM command: `DULCET_CONFORMANCE_BASE_URL=http://127.0.0.1:4533 DULCET_CONFORMANCE_DISPOSABLE=true ./gradlew --no-daemon --max-workers=1 -Dorg.gradle.jvmargs=-Xmx1536m :core-conformance:jvmTest --tests '*StallDiagnosticsTest*' > /tmp/dulcet-stall-jvm.log 2>&1`; `tail -5` returned `BUILD SUCCESSFUL in 22s`. XML extraction returned `jvmTest {'tests': '3', 'failures': '0', 'errors': '0'}`.
OBSERVED — same command with `:core-conformance:macosArm64Test` and native log path returned `BUILD SUCCESSFUL in 16s`; XML extraction returned `macosArm64Test {'tests': '3', 'failures': '0', 'errors': '0'}`. Controls cover suspension/cancellation, real watchdog time with unchanged virtual time, and invalid authentication against only disposable 4533. Native log actual output: `elapsedMs=33 end connect durationMs=33`; `watchdogGapMs=50 testDispatcherPulseAgeMs=2`; final request jobs both `completed=true`. These are successful local controls, **not a CI stall reproduction**.
OBSERVED — `python3 tools/test-capture-conformance-stall` returned `Ran 2 tests ... OK` (inactivity detection ignores snapshots; stdout and exit 7 propagate). `python3 tools/verify_ci_policy.py` returned `CI policy valid across 6 workflows`. `git diff --check` returned no output.
OBSERVED — host integration command: compile a temporary C executable that prints `CONFORMANCE_PHASE host-control elapsedMs=0 begin test-body`, flushes, then `sleep(43)`; invoke `python3 tools/capture-conformance-stall --binary "$probe" --output "$probe_dir" -- "$probe"`. Actual output: `CONFORMANCE_HOST_SAMPLE sequence=1 matchingPids=[30982]`, exit 0, 8,351-byte sample containing `Sampling completed, processing symbols...`, `Call graph:`, `86 sleep`, and `86 nanosleep`. Initial copied-system-binary probe was killed (exit 247); the compiled control above is the successful evidence.
ASSUMED — complete iOS CI integration remains unexecuted: no simulator or historical failure was reproduced. Host delivery still depends on Gradle forwarding the first phase marker, lsof finding the executable, and macOS permitting sampling. Missing/failed samples are printed explicitly; whole-host starvation can delay even this observer. Therefore do not label a future run conclusive unless its evidence actually separates these cases.

**4. Recommendation only.**

OBSERVED verdict confidence: high that the relevant product HTTP requests have 30-second deadlines; high that plaintext hostname resolution lacks an application deadline; **insufficient evidence to call the historical stalls harmless CI load or a product synchronization defect**. The first successful local control is milliseconds, which cannot justify enlarging a CI budget.
ASSUMED deciding observation: one failed iOS occurrence with the outstanding phase, request-job state, dispatcher heartbeat ages, and simultaneous host stack sample. It would place the wait before the timer, inside timed transport/cancellation, after product completion, or in the separate fixture GET. Preserve 60 seconds until that observation exists; there is no measured basis for recommending 90 seconds or five minutes today. Investigate the DNS deadline/cancellation gap separately; simply wrapping blocking getaddrinfo in withTimeout would not make it interruptible.
OBSERVED scope: diagnostic code only; no timeout, retry, assertion, branch, server lifecycle or product recovery change. JVM/native targets ran sequentially and used only the already-running disposable 4533 service. Neither failing endpoint was exercised locally. No push or PR action was performed.

**5. 2026-09-08 follow-up: independent server evidence, diagnostic-only.**

OBSERVED — `f0e6f1b` rebased cleanly onto `origin/main` at `4022bcc`, becoming `16f192c`.
`git range-diff f0e6f1b^..f0e6f1b 4022bcc..16f192c` reports `=` for the instrument commit.
Sections 1–4 describe the historical baseline, not all current-main behavior: main now includes
host-resolution changes, which this branch preserves without re-investigating them.

REPORTED by the maintainer — two iOS simulator failures in
`slowSelfHostedServerCanCompleteAccountNegotiation`: job `102206147605` on PR #107 returned
`Failed(error=Timeout)`; job `102220717052` on PR #97 exhausted the one-minute `runTest` bound.
These reports were not independently reproduced here. The fixture sleeps 10.5 seconds only for
extension discovery, against a 30-second individual request budget: the unexplained difference is
approximately 19.5 seconds within one request. The fixture already uses `ThreadingHTTPServer`;
its sleep does not serialize other requests. No timeout, retry, assertion, budget or server lifecycle
was changed. The slow-account test now installs the same client instrument as conf06 and proxy auth,
retaining its fixture salt source, host resolver and result assertion.

OBSERVED — both fixture handler classes now emit independent JSONL access evidence. CLI startup
always enables it, including in CI, at
`$RUNNER_TEMP/dulcet-conformance-access-<port>-<pid>.log` (system temporary directory when
`RUNNER_TEMP` is absent). `--access-log` overrides the file for a local control. The existing Apple
failure/cancellation inventory and artifact upload already include `$RUNNER_TEMP/*.log`.
Each file starts with `log-start`, including PID and its byte cap. A parsed request emits `arrival`
before dispatch and `completion` after the handler and final flush return or raise. Records include
absolute `monotonicNs`, a process-local sequence, method, sanitized path, endpoint and listener port.
Completion links to the arrival sequence through `requestId` and includes selected response status,
`durationMs`, `handlerReturned` and `bodyWriteFailed`. An unfinished handler leaves an arrival behind.
The final flag records the body-write errors the redirect fixture already catches; their handling is
unchanged. Status means the status selected by the handler, not proof of client receipt.

OBSERVED — query strings are removed in full before parsing or recording; authorities, headers,
bodies and exception text are excluded. Only fixed route vocabulary survives in paths; unknown
segments are masked, and the credential-reflecting target route masks its entire suffix and endpoint
regardless of vocabulary. Methods and endpoint names are restricted too. Paths are capped at 512
characters. Serialized, unbuffered writes cap each process file at 4 MiB including one `log-limit`
marker, then stop. The two ordinary listeners share one file; the proxy process owns another.
Logging I/O failures produce a fixed `CONFORMANCE_ACCESS_LOG_UNAVAILABLE` stderr marker and stop
logging without changing response handling. Saturation also prints `CONFORMANCE_ACCESS_LOG_LIMIT`.
Neither marker contains input or filesystem paths. No rotation discards the early arrival history.

OBSERVED — `python3 tools/test-conformance-access-log` ran six controls in 11.669 seconds, all passing.
The actual output included:

```text
BOUND CONTROL bytes=1336 cap=2048 limitMarkers=1
SLOW CONTROL in-flight arrivals=2 completions=0
SLOW CONTROL hits=2 statuses=200,200 durationsMs=10502.182,10508.005
NEGATIVE CONTROL total slow hits=3 rejected-query status=500 credentials absent
Ran 6 tests in 11.669s
OK
```

The two slow requests used real loopback sockets and the unchanged 10.5-second handler sleep. The
third hit included `u`, `t`, `s`, `p` canaries and was rejected by the existing closed request oracle;
its 500 completion was required to appear in the same log. Additional controls cover both CLI modes'
default runner-temp activation, proxy GET/POST/CONNECT, two requests on one persistent connection,
concurrent log saturation, reflected credentials that match allowed route words, and the existing
suppressed body-write failure. This explicitly rejects an empty-log false pass.

OBSERVED — the repository core command, with `--no-daemon --max-workers=1` and a 1536 MiB Gradle heap,
ran `:core:verifySqlDelightMigration :core:allMetadataJar :core:jvmTest :core:testAndroidHostTest
:core:bundleAndroidMainAar :core:licensee`: `BUILD SUCCESSFUL in 1m`, 45 actionable tasks.
JUnit totals were JVM 184 tests and Android host 180 tests, both with zero failures/errors.
`python3 tools/verify_ci_policy.py` returned `CI policy valid across 6 workflows`;
`python3 tools/parity_gate.py` returned `parity gate valid: 6 feature rows`.
Configuration-only OS-floor verification and `tools/migration_gate.py` passed, as did the redirect
credential-detector mutation controls. `python3 tools/test-capture-conformance-stall` returned
`Ran 2 tests ... OK`. `git diff --check` passed.

OBSERVED — the original `StallDiagnosticsTest` controls ran sequentially against a fresh disposable
Navidrome 0.63.2 at `http://127.0.0.1:4533`, with `DULCET_CONFORMANCE_DISPOSABLE=true`, using the same
Gradle options above and `--tests '*StallDiagnosticsTest*'`. JVM: `BUILD SUCCESSFUL in 13s`,
3 tests, zero failures/errors. `macosArm64`: `BUILD SUCCESSFUL in 28s`, 3 tests, zero failures/errors.
Actual native output included `elapsedMs=54 end connect durationMs=53`,
`watchdogGapMs=51 testDispatcherPulseAgeMs=0`, and both request jobs `completed=true` at body exit.
The watchdog control still asserts virtual test time equals zero. These are passing local controls,
not reproductions of the CI stall.

OBSERVED — the original host integration control was repeated using a compiled C executable that
prints the first phase marker, flushes stdout and sleeps 43 seconds. The capture tool returned zero,
printed `CONFORMANCE_HOST_SAMPLE sequence=1 matchingPids=[50377]`, and wrote a sample containing
`Sampling completed, processing symbols...`, `Call graph:`, `85 sleep` and `85 nanosleep`.
Independent adversarial review found the allowed-word reflected-credential case; the structural
redaction fix and failing-writer observation were re-reviewed with no remaining blocking findings.

INTERPRETATION LIMITS — the next failed iOS occurrence must still supply the deciding evidence.
A complete, unsaturated log with other known hits but no slow-endpoint arrival supports “no parsed
request reached this handler.” It cannot establish that no TCP connection or partial headers reached
the host: logging begins after request parsing, not at accept. A slow arrival-to-completion interval,
or arrival without completion, supports time inside the server handler. A roughly 10.5-second
completion alongside a 30-second client timeout places the excess outside the measured handler,
but does not alone prove it occurred after the response rather than before request arrival.
`bodyWriteFailed=true` identifies a caught send failure; false does not prove delivery. Kernel/network
transport and client engine/callback/dispatcher delay remain distinct unmeasured possibilities.

The client uses relative elapsed time; the server uses its own absolute monotonic clock. No shared
request ID or clock anchor crosses the transport. Match endpoint, order, port and count cautiously;
concurrent identical requests can remain ambiguous. Missing/unavailable/saturated artifacts make a
negative result inconclusive. The unchanged host sampler waits 40 seconds without phase progress,
so a promptly propagated 30-second timeout can exit before a sample occurs. It supports the longer
stall shape, not a guarantee of simultaneous stacks for every timeout. No real CI failure or iOS
simulator run was observed in this follow-up; no push or pull-request action was performed.

OBSERVED — an additional Darwin integration run selected both `*StallDiagnosticsTest*` and
`*slowSelfHostedServerCanCompleteAccountNegotiation*` on `:core-conformance:macosArm64Test`, with
`DULCET_REDIRECT_CONFORMANCE_ROOT=http://127.0.0.1:4540` and the disposable 4533 variables above.
It returned `BUILD SUCCESSFUL in 17s`: four tests, zero failures/errors. The separate fixture process
recorded exactly one arrival/completion pair for each negotiation endpoint:

| Method | Endpoint | Status | Handler duration (ms) |
| --- | --- | --- | --- |
| GET | getOpenSubsonicExtensions | 200 | 10503.084625 |
| POST | ping | 200 | 0.329958 |
| POST | getUser | 200 | 0.155375 |

All three recorded `handlerReturned=true` and `bodyWriteFailed=false`. Independently, the client
reported `getOpenSubsonicExtensions.send durationMs=10519`; at 10010 ms its watchdog showed that
phase pending and request 1 active, not cancelled or completed. This proves the slow-test wrapper
and the server log observe the same successful local negotiation, not the unexplained CI delay.
