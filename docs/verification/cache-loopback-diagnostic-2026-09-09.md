# Cache-probe diagnostic regression evidence

Observed 2026-09-09 on `queue/batch-name-the-cause`, based on `de69354`.
All network fixtures bind only `127.0.0.1:4533`; no production server is exercised.

## Budget contract

The search still has five seconds, with one-second maximum attempts. On exhaustion it prints and
flushes `cache search never completed: attempts=… last_error=…` before optional diagnostic I/O.
Diagnosis has an explicit **additional 1.25-second allowance shared by all three layers**, enforced
with an absolute deadline and `ITIMER_REAL` around each operation, including response-body reads.
The configured search-plus-diagnosis budget is **6.25 seconds**, plus process/scheduling overhead.
Any description claiming this entire change has “no budget extension” must be corrected: only the
search window is unchanged. The stream budget is unchanged.

A deadline raised by this timer is distinguished from an immediately raised endpoint error: prompt
HTTP/authentication failures remain anomalies under `server-answering`. Consuming the remainder of
the shared allowance cannot be mistaken for a prompt successful response.

## Reproduction and mutation results

Commands:

```sh
python3 tools/test-cache-search-readiness
python3 tools/test-cache-loopback-mutations
python3 tools/test-transcode-probe-timeout-diagnostic
```

The complete cache-search suite invokes the new diagnostic controls. The mutation runner makes
compiled temporary probe copies; it requires an assertion failure, not merely a nonzero exit.
`DULCET_TEST_CACHE_PROBE` selects a temporary probe for both the diagnostic and cache-search controls.
No mutation edits the working probe.

Before the deadline fix, the new real dripping-body control against the original probe exited 1:

```text
AssertionError: ping-drip: failure path exceeded 7.2s; primary_seen=False
```

The exact constant-verdict mutation was also rerun against the complete original control suite from
`3d93431`. It reproduced the review's false green:

```text
ORIGINAL SUITE constant-verdict exit=0
```

After strengthening the controls, the constant fails on the connection-refused case, which requires
a distinct verdict. The following is actual mutation output (all 14 rejected):

```text
KILLED constant-verdict exit=1 AssertionError: connect delay=0: loopback diagnosis=server-answering
KILLED delete-raw-probe exit=1 AssertionError: connect delay=0: loopback diagnosis=server-answering connect_seconds=0.000 ping_seconds=0.000 rest_ping_seconds=0.000 detail=every layer answered promptly, so the stalled request is not explained by server availability
KILLED delete-http-probe exit=1 AssertionError: healthy: probes ['connect', 'rest'], expected ['connect', 'ping', 'rest']
KILLED delete-authenticated-probe exit=1 AssertionError: healthy: probes ['connect', 'ping'], expected ['connect', 'ping', 'rest']
KILLED delete-connect-elapsed-classification exit=1 AssertionError: connect delay=1.01: loopback diagnosis=server-answering connect_seconds=1.010 ping_seconds=0.000 rest_ping_seconds=0.000 detail=every layer answered promptly, so the stalled request is not explained by server availability
KILLED delete-http-elapsed-classification exit=1 AssertionError: ping delay=1.01: loopback diagnosis=server-answering connect_seconds=0.000 ping_seconds=1.010 rest_ping_seconds=0.000 detail=every layer answered promptly, so the stalled request is not explained by server availability
KILLED delete-rest-elapsed-classification exit=1 AssertionError: rest delay=1.01: loopback diagnosis=server-answering connect_seconds=0.000 ping_seconds=0.000 rest_ping_seconds=1.010 detail=every layer answered promptly, so the stalled request is not explained by server availability
KILLED delete-anomaly-reporting exit=1 AssertionError: loopback diagnosis=server-answering connect_seconds=0.000 ping_seconds=0.000 rest_ping_seconds=0.000 detail=every layer answered promptly, so the stalled request is not explained by server availability
KILLED delete-envelope-validation exit=1 AssertionError: loopback diagnosis=server-answering connect_seconds=0.000 ping_seconds=0.000 rest_ping_seconds=0.000 detail=every layer answered promptly, so the stalled request is not explained by server availability
KILLED delete-redactor exit=1 AssertionError: redaction connect '-leading-canary': loopback diagnosis=connection-refused connect_seconds=0.000 connect_error=RuntimeError: request 'http://127.0.0.1:4533/rest/ping.view?u=-leading-canary&t=-token&s=%26salt' failed detail=refused immediately, so the port is not listening
KILLED word-only-redactor exit=1 AssertionError: redaction connect '-leading-canary': loopback diagnosis=connection-refused connect_seconds=0.000 connect_error=RuntimeError: request 'http://127.0.0.1:4533/rest/ping.view?u=-leading-canary&t=-token&s=%26salt' failed detail=refused immediately, so the port is not listening
KILLED delete-wall-timer exit=1 AssertionError: ping-drip: failure path exceeded 7.2s; primary_seen=True
KILLED fresh-budget-per-layer exit=1 AssertionError: (1, 7.11320816699299, [(1.051308958005393, 'cache search transport error: timed out\n'), (2.10925874998793, 'cache search transport error: timed out\n'), (3.1702772919961717, 'cache search transport error: timed out\n'), (4.225280124985147, 'cache search transport error: timed out\n'), (5.051746082986938, 'cache search transport error: timed out\n'), (5.0517597919970285, 'cache search resolved attempts=5 completed=0\n'), (5.051760667003691, 'cache search never completed: attempts=5 last_error=timed out\n'), (7.06574950000504, 'loopback diagnosis=database-path-blocked connect_seconds=0.000 ping_seconds=0.754 rest_ping_seconds=1.259 rest_error=DiagnosisDeadlineExceeded: diagnosis wall-clock deadline exhausted\n')])
KILLED diagnose-before-primary exit=1 AssertionError: ping-stall: primary failure delayed: [(6.302691082993988, 'cache search never completed: attempts=5 last_error=timed out\n')]
```

The branch tests independently inject raw-connect refusal/timeout, slow success at each layer,
prompt HTTP/authentication failures, malformed JSON, and a failed authenticated envelope. They
check which probes actually ran as well as the verdict. Network tests cover stalled and dripping
HTTP responses, a dripping authenticated response, and a delayed HTTP response followed by either
a dripping authenticated body or stalled authenticated headers.

Credential-bearing exceptions are injected at each of the three layers with `-leading-canary`,
`a&b=c;d/e?f`, `%2Dencoded%26secret%3Dvalue`, and `ping`. Assertions require the exact sanitized URL:
the route `/rest/ping.view` survives while the whole query becomes `?<redacted>`. Neither a hex scan
nor global replacement of credential words is used. The existing query redactor needed no change;
the missing regression protection did.

Final unmutated verification (all commands exited 0):

```text
PASS layer=connect delay=0 verdict=connection-refused
PASS layer=healthy delay=0 verdict=server-answering
PASS layer=connect delay=0 verdict=nothing-accepting
PASS layer=connect delay=1.01 verdict=nothing-accepting
PASS layer=ping delay=1.01 verdict=http-blocked
PASS layer=rest delay=1.01 verdict=database-path-blocked
PASS layer=ping delay=0 verdict=server-answering
PASS layer=rest delay=0 verdict=server-answering
PASS layer=ping delay=0 verdict=server-answering
PASS layer=rest delay=0 verdict=server-answering
PASS layer=failed-envelope delay=0 verdict=server-answering
PASS layer=malformed-json delay=0 verdict=server-answering
PASS credential exception canary='-leading-canary' layers=connect,ping,rest
PASS credential exception canary='a&b=c;d/e?f' layers=connect,ping,rest
PASS credential exception canary='%2Dencoded%26secret%3Dvalue' layers=connect,ping,rest
PASS credential exception canary='ping' layers=connect,ping,rest
PASS ping-stall primary_seconds=5.052 exit_seconds=6.373 verdict=http-blocked
PASS ping-drip primary_seconds=5.078 exit_seconds=6.355 verdict=http-blocked
PASS rest-drip primary_seconds=5.066 exit_seconds=6.394 verdict=database-path-blocked
PASS shared-rest-drip primary_seconds=5.083 exit_seconds=6.395 verdict=database-path-blocked
PASS shared-rest-stall primary_seconds=5.072 exit_seconds=6.349 verdict=database-path-blocked
POSITIVE CONTROL PASS cache_search_transport_retried=true attempts=2 completed=1
cache search transport error: Remote end closed connection without response
cache search resolved attempts=2 completed=1
TRANSCODE CACHE OBSERVATION cached=false transcoding=true bytes=7 first_response_seconds=0.001 total_request_seconds=0.001 incomplete_read=false song=Dulcet-Health-Probe
NEGATIVE CONTROL PASS cache_search_deadline=true elapsed_seconds=5.095 attempts=5 completed=0
NEGATIVE CONTROL PASS search-http-error=rejected message=HTTP Error 503
NEGATIVE CONTROL PASS search-missing-id=rejected message=known transcode cache probe is absent from the disposable corpus
NEGATIVE CONTROL PASS search-error-envelope=rejected message=known transcode cache probe is absent from the disposable corpus
NEGATIVE CONTROL PASS header_timeout_phase=true causation=plausible-not-established
NEGATIVE CONTROL PASS body_timeout_phase=true wall_clock_deadline=true
POSITIVE CONTROL PASS TRANSCODE CACHE OBSERVATION cached=false transcoding=true bytes=7 first_response_seconds=0.107 total_request_seconds=0.442 incomplete_read=false song=Dulcet-Health-Probe
CONTROL SPEED PASS elapsed_seconds=1.26
```

## What can still be deleted while the cache-search suite passes?

A temporary copy with all three of these deletions still passed the **complete cache-search suite**:

- The `if not payload` empty-audio rejection.
- The final `--expect-cached` mismatch rejection.
- The diagnostic's reported `connect_seconds`, `ping_seconds`, and `rest_ping_seconds` fields.

Actual audit result:

```text
CURRENT SUITE delete-empty-audio-guard + delete-cache-expectation-guard + delete-layer-duration-fields exit=0
```

The first two are downstream stream/cache assertions: these fixtures always return nonempty audio
and the expected cache state. The third is a reporting gap: classification is tested, but the
numeric diagnostic fields are not asserted. These deletions were **only in temporary copies**;
all three remain in the working tool. They are explicit remaining coverage gaps, not certified
behavior. The separate stream-timeout control pins header/body deadlines and stream latency metrics;
it does not make the cache-search suite exhaustive. This finite mutation audit is not a claim that
no other deletion could survive.

No Kotlin source was modified during this revision. No push or PR update was performed.
