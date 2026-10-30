# Strict Apple media redirect handoff

## Original failure

OBSERVED in `apple-ci` run [40976913927, attempt 1](https://github.com/legitimate-apps/dulcet/actions/runs/40976913927/attempts/1),
`apple-platform`, `strictTransportRoutesRedirectsThroughTheExistingCredentialPolicy(crossOrigin: true)`.
The failure-diagnostics artifact contains `dulcet-swift-testing.xcresult`. Its exported unified log
records the source connection, task completion and destination connection in this order (UTC):

```text
00:40:34.454  task 3BA21ED7… resumes, request timeout 60 seconds
00:40:34.455  C51 connects to the source listener, 127.0.0.1:62426
00:40:34.456  task 3BA21ED7… summary for redirection: response_status=-1
00:40:34.456  task 3BA21ED7… summary for task success: response_status=-1
00:40:34.456  task 3BA21ED7… finished successfully
00:40:34.457  C53 starts connecting to the destination listener, 127.0.0.1:62427
00:40:34.458  C53 is cancelled while connecting
00:40:34.459  C53 ignores CONNECTED because disconnect has been requested
```

The destination listener was ready at `00:40:34.454`. There was no destination connection failure
or request timeout. The task completed before the target could supply an HTTP response. The resulting outcome is
`.failed(error: .transport, refreshReason: nil)`, inferred from the recorded successful completion
with `response_status=-1` and the loader's nil-HTTP-response guard; the old test did not print its outcome.

The strict `URLProtocol` reported a redirect, then also reported the source response and successful
completion. Those original-response callbacks could complete the task during the redirect handoff.
All Apple shells share this playback path; strict foreground download verification also uses it.
This is a playback correctness defect, not a test timeout.

## Fix and regression boundary

After reporting a redirect, the source protocol cancels its connection and timer without further
client callbacks. URLSession owns the redirected request and its completion. Refused media redirects
explicitly cancel their tasks: passing nil alone asks URLSession to receive the original redirect
body, which this media path does not accept.

`strictRedirectRetiresTheSourceWithoutCompletingTheTask` drives a real source socket through the
strict protocol and records its client callbacks directly. A serial-queue barrier observes the
entire callback batch without relying on URLSession's scheduling. The old code emits
`redirect, response, finish`; correct code emits only `redirect`, including after `stopLoading`.

`strictRedirectWaitsForTheDestinationResponse` additionally drives the real URLSession playback
resource with a delayed destination response for same-origin and cross-origin redirects. It requires
all media bytes, one credential-policy decision, the retained object signature, preserved same-origin
credentials and stripped cross-origin credentials. Refusal still completes as failure and sends
nothing to the target. The existing chunked framing, truncated body, cache, download delegate and
active-idle-timeout controls remain in the socket suite.

`testStrictDownloadRedirectWaitsForItsDestinationAndRejectsLoops` drives the actual shared
controller and its foreground verification delegate. The initial chunked download triggers strict
replacement; only that replacement redirects. A held cross-origin destination cannot promote an
offline file before its bytes arrive. A redirect loop reaches the policy limit and must finish as
interrupted/failed, with no offline asset or stored download bytes, within five seconds.

## Local validation

All local Xcode runs used `-parallel-testing-enabled NO`.

| Run | Result |
| --- | --- |
| New socket suite, original transport (only internal test access added) | 9 test declarations: 8 passed, 1 failed, 0 skipped. The callback ledger reports `redirect, response, finish`, failing its two callback assertions. |
| Same socket suite, corrected transport | 9 passed, 0 failed, 0 skipped. |
| Entire native DulcetKit package, corrected transport | 427 tests across 23 suites passed, 0 failed, 0 skipped. |
| Shared download controller, corrected transport but original download delegate | 1 test failed: refused redirect loop did not finish within five seconds. |
| Shared download controller, corrected delegate plus adjacent chunked, restored identity and cancellation controls | 4 passed, 0 failed, 0 skipped. The redirect test includes held-destination success and loop refusal. |

The callback ledger uses the protocol's serial queue as its observation boundary, so this failure
is deterministic and does not depend on repeating hosted-runner timing. The real URLSession tests
with delayed destinations are additional integration controls; they are not claimed to fail on
every execution of the old transport.

The redirect handoff follows Foundation's
[`wasRedirectedTo` client callback](https://developer.apple.com/documentation/foundation/urlprotocolclient/urlprotocol(_:wasredirectedto:redirectresponse:)).
A nil redirect decision requests the source body, per the
[URLSession redirect delegate contract](https://developer.apple.com/documentation/foundation/urlsessiontaskdelegate/urlsession(_:task:willperformhttpredirection:newrequest:completionhandler:));
the media delegates explicitly cancel policy refusals because they cannot accept that body.
