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

**6. Shared wall-clock anchors and a measured observer threshold (2026-09-08 follow-up).**

OBSERVED — this follow-up supersedes section 5's missing-clock-anchor and 40-second-observer limits.
Client phase markers and snapshots now append `wallTimeMillis`, using
`Clock.System.now().toEpochMilliseconds()`. Fixture JSONL records use the same Unix-epoch-millisecond
representation from `time.time_ns() // 1_000_000`. Neither changes durations: client elapsed time and
server handler duration remain monotonic. The server captures both timestamps before waiting for its
file lock. No header, query parameter or request ID was added to any request. The host capture tool
also writes `dulcet-stall-phase-receipts.log`, containing host monotonic and wall time when each phase
marker is received, plus `COMMAND_EXIT` on completion. It matches the existing failure artifact glob.
A missing exit marker is incomplete evidence, not a healthy calibration run.

OBSERVED — healthy local measurements, selected before changing the observer threshold:

| Calibration population | Gaps | Minimum | p50 | p95 | p99 | Maximum |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| Three retained JVM/native control logs from section 5, source monotonic | 121 | 0 ms | 0 ms | 26 ms | 156 ms | 10519 ms |
| Five fresh Darwin runs, source monotonic | 520 | 0 ms | 0 ms | 4 ms | 158 ms | 10515 ms |
| Three fresh Darwin runs through the actual capture wrapper, host receipt monotonic | 312 | 0 ms | 0 ms | 0 ms | 1 ms | 9707 ms |
| All eleven healthy logs, source monotonic (including the three wrapped runs) | 953 | 0 ms | 0 ms | 6 ms | 158 ms | 10519 ms |

Percentiles are nearest-rank. Source and host rows describe different clocks and are not pooled.
Consecutive markers are measured only within a test body and label; snapshots, inter-test/run time,
and late callbacks after body exit are excluded. Each fresh Darwin run selected
`*StallDiagnosticsTest*`, `*slowSelfHostedServerCanCompleteAccountNegotiation*`,
`*conf06DistinguishesAuthenticationAndTransportFailures*` and
`*DarwinProxyAuthenticationConformanceTest*`. These run the unchanged 10.5-second endpoint sleep,
port-1 failure, proxy challenge/observation and diagnostic controls. The source maximum came from
the slow-account endpoint; low percentiles include the many adjacent zero/one-millisecond markers.
The nine slow-account extension-wait gaps themselves span 10507–10519 ms, with median 10512 ms.
Host receipts show batching through Gradle, so they were measured independently rather than assumed
to equal source timing. This is a local healthy distribution, not a hosted-CI percentile guarantee.

The committed [calibration dataset](2026-09-08-conformance-phase-gaps.json) retains per-run/per-label
gap arrays, body counts and summaries without raw URLs or home paths. Reproduce summary extraction
from successful Gradle logs with `python3 tools/measure-conformance-phase-gaps <logs...>`; use
`--clock host` for the corresponding receipt logs. All five fresh unwrapped runs and three wrapped
runs passed. The capture wrapper preserved exit status; none sampled with the then-current threshold.
The measurement controls reject empty, failed, incomplete or out-of-order input and prove that a
snapshot cannot break a 10.5-second phase gap into smaller apparent gaps.

DECISION — set only the observer's `SAMPLE_AFTER_SECONDS` to **16**:
`ceil(1.5 * max(10.519, 9.707)) = 16`. The margin above the larger observed healthy maximum is
**5.481 seconds (52.1%)**; the trigger is **14 seconds below** the unchanged 30-second request budget.
The existing two-second polling interval, 15-second resampling spacing, sampling commands, request
timeouts, runTest budgets and assertions are unchanged. This initiates sampling before a 30-second
failure on a responsive host; host starvation, output delivery delays or slow sampling tools can
still delay the observation. A healthy run above the threshold would produce diagnostic evidence,
not fail a test, retry a request, kill a process or change a deadline.

OBSERVED — eight sequential slow-account requests were independently joined by wall time and
endpoint/order. Client `engine-send request=1` to server arrival was **1–3 ms**; server completion to
client `end getOpenSubsonicExtensions.send` was **1–2 ms**. One actual anchored sequence was:

| Observation | wallTimeMillis |
| --- | ---: |
| Client engine-send | 1788915946553 |
| Server parsed-request arrival | 1788915946556 |
| Server handler completion | 1788915957067 |
| Client response observation | 1788915957068 |

Thus the observed pre-arrival interval was 3 ms, handler wall interval 10511 ms, and post-completion
interval 1 ms. Monotonic handler durations remain the authoritative duration measurement. For this
sequential, uniquely identifiable endpoint occurrence, **the before-arrival/after-completion split
is now decidable**, including when output is forwarded later: use event wall time, not log-print
order. A large wall-clock step should be checked against the paired monotonic durations. The anchor
is not a transport identifier: genuinely overlapping identical requests can remain ambiguous. The
arrival boundary is still after parsing; a prompt completion is still not proof of client delivery.

OBSERVED — a real host control compiled a C executable that prints and flushes a test-body marker,
sleeps **20 seconds**, and exits zero. At the new threshold it produced:

```text
HOST STALL CONTROL exit=0 samples=1 sampleAtSeconds=16.671 commandDurationSeconds=20.274 stack=nanosleep
```

The sample contained `Call graph:` and `nanosleep`. This control exits before the old 40-second
threshold, so it discriminates the newly covered failure window. Capture-tool controls passed four
tests, including the wall-anchored marker format and the observed healthy maximum staying below the
trigger. Measurement-parser controls passed three tests. Access-log controls passed six tests in
11.656 seconds, preserving the in-flight hit-count and credential negative controls while checking
wall anchors. Core commands plus the updated JVM diagnostic controls returned
`BUILD SUCCESSFUL in 49s`; JVM core 184, Android host 180, JVM diagnostics 4 tests, zero failures/errors.
CI policy, parity, OS-floor configuration and migration gates passed. Independent review reproduced
the raw-source and host distributions and found no remaining blocking issues.

OBSERVED FROM SOURCE — **library browse in the Apple download integration is not covered by these
paired access records**. `DulcetAppleDownloadIntegrationTest.loadLiveTrack` passes the environment's
`baseURL` to `AppleLibraryBrowseRequest`; `AppleLibraryBrowseClient` invokes `LibraryBrowser` against
that URL. The workflow supplies `http://127.0.0.1:4533`, served directly by the native Navidrome
process. Browse requests include `getMusicFolders`, `getArtists`, `getAlbumList2` and `getAlbum`.
The separately launched redirect/proxy fixtures on 4540/4541/4543 do not see them. The workflow's
failure/cancellation artifact collection does include Navidrome's own `ROOT/logs/navidrome.log`, the
download test log, xcresult and JUnit output. Collecting the fixture access file on that failure does
not make it an access log for Navidrome. The reported `library browse failed: kind=timeout` in job
102278787665 was not reproduced or diagnosed here; its path remains a stated instrumentation blind
spot. No routing or server lifecycle was changed to broaden scope.


OBSERVED — final healthy verification through the capture wrapper at the **new 16-second threshold**
returned `FINAL HEALTHY CAPTURE exit=0 samples=0`. All seven selected Darwin tests passed. This run
is a post-selection control, not an extra sample used to choose the threshold. The shared-clock and
sampler changes are committed locally; no push or PR action was taken.

**7. Review repairs and enforced mutation controls (2026-09-09).**

OBSERVED — independent review found that an encoded reflected-route prefix bypassed the literal
mask and exposed credential values that happened to equal route vocabulary. The logger now preserves
only **complete recognized structures**: fixed health/observation paths, known scenario/rest/endpoint
positions, and a bounded numeric redirect-loop hop. Unknown structures return `[redacted]` for the
entire path and endpoint. No percent-decoding is used to rescue unknown input. The literal reflected
route continues to mask its entire suffix. Routing and responses are unchanged: a 404 is not a reason
to log unrecognized input.

OBSERVED — the committed socket control now checks encoded prefixes (`cross%2Dreflected-target`,
`re%73t`, `rest%2Fu`, a double-encoded prefix), encoded values, an unknown scenario and misplaced safe
words. It drives both actual handler classes with `health`, `ping`, and `rest` as canaries, verifies
both arrival and completion records, and first requires a known `/health` response and log entry on
each live logger. The new control makes **16 adversarial requests plus two known health hits**.
Restoring the old logger in a disposable copy while retaining the new control produces 14 failures.

OBSERVED — the sampler suite previously never required the wrapper to reach its sampling branch.
The new cross-platform positive control invokes the **actual main loop**, real child process, output
queue, clock and unchanged **16-second threshold**. Its child emits a phase and waits 20 seconds.
Only host utilities are replaced, not `sample_binary`; the test requires ordered lsof/ps/sample calls,
the expected PID and sample arguments, and a written artifact. The separate supported-macOS control
compiles a probe, samples that exact live executable with the real host tools, and requires its PID's
report to contain `Call graph:`, `stall_control_wait`, and `nanosleep`. It cleans up its owned process.
The wrapper also closes the exhausted child stdout stream; no test/process deadline was changed.

Actual mutation results, using disposable copies and the committed test suites:

| Variant | Exit | Actual unittest result |
| --- | ---: | --- |
| New structural logger, focused socket control | 0 | Ran 1 test in 1.028s; OK |
| Restore old literal-prefix/vocabulary logger | 1 | Ran 1 test in 1.033s; FAILED (failures=14) |
| Unmodified capture tool, complete suite | 0 | Ran 6 tests in 22.038s; OK |
| Replace `sample_binary()` body with `pass` | 1 | Ran 6 tests in 20.443s; FAILED (failures=2) |
| Replace overdue sampling loop with `for label in []` | 1 | Ran 6 tests in 22.235s; FAILED (failures=1) |

The two no-op-sampler failures are the actual-loop invocation assertion and the exact-PID usable
stack assertion. The empty-loop mutation fails the wrapper assertion while the standalone real
sampler still passes, distinguishing missing dispatch from missing stack collection. Passing output:

```text
WRAPPER LOOP CONTROL thresholdSeconds=16 samples=1 hostCommands=lsof,ps,sample
SUPPORTED HOST CONTROL exactPid=true usableStack=stall_control_wait,nanosleep
```

OBSERVED — all three standalone suites are now unconditional steps in the required macOS apple-ci
PR job: `test-conformance-access-log`, `test-capture-conformance-stall`, and
`test-measure-conformance-phase-gaps`. macOS placement matters: Linux alone would skip the supported
host stack test. Existing request/test budgets and the workflow's job timeout remain unchanged.

**Orphan-gate verdict:** `verify_ci_policy.py` previously had **no standalone-control orphan gate**.
Its `written - read` check only detects Apple JUnit directories written but not consumed by parity
verification. Therefore it could not catch these three uninvoked scripts. This was a coverage gap,
not a malfunction of the directory check. The added explicit contract now requires these three files
and real unconditional Python invocations in the required macOS PR job. It is deliberately described
as an explicit diagnostic-suite contract, **not universal discovery of every tools/test-* script**.

The already-CI-invoked `test-verify-ci-policy` now includes **22 diagnostic-wiring cases**: a positive
fixture; removal, comment-only, echo-only, disabled, allowed-failure and missing-file variants for each
suite; disabled-job, Linux-only and manual-only variants. All passed. Replacing the verifier with its
old version makes that control suite exit 1 with
`tools/test-conformance-access-log missing was accepted`, so removing the new contract also bites.
The existing policy controls still pass: six core and four Apple cases.

OBSERVED — rebased onto `origin/main` **0e7e3ea** after the repairs. All 15 replayed patches compare
identically in range-diff; no conflicts occurred. Both `tools/resolve-ipad-destination` and
`tools/test-ipad-destination-resolver` have zero diff against that main, preserving #112 unchanged.
Post-rebase checks: full access-log suite **7 tests / OK** (12.716s), measurement parser **3 / OK**,
policy controls **6 core + 4 Apple + 22 diagnostic / pass**, CI policy valid across six workflows,
parity valid across six feature rows, and `git diff --check` clean. Independent review found no
blocking issues in the repair, sampling controls or wiring contract. The calibrated gaps, caps and
wall/monotonic timing were not changed or re-calibrated. No Navidrome configuration change, push or
PR action was performed.

The [mutation result dataset](2026-09-09-stall-control-mutations.json) preserves the actual summaries
without raw machine paths. The mutation copies did not edit the working branch.
