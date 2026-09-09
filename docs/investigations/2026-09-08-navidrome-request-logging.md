# Navidrome 0.63.2 request-logging evaluation

Scope: evaluate server visibility for the Apple download integration's library-browse timeout.
The committed fixture configuration remains unchanged while the candidate is measured. No request
timeout, retry, assertion, budget, routing or CI server-lifecycle change is part of this evaluation.

OBSERVED FROM PINNED SOURCE — there is a narrower option than global debug:

```toml
LogLevel = "info"
EnableLogRedacting = true

[DevLogLevels]
"server/middlewares.go" = "debug"
```

The v0.63.2 [configuration](https://github.com/navidrome/navidrome/blob/v0.63.2/conf/configuration.go)
defines `DevLogLevels` and installs it with `log.SetLogLevels`. The
[logger](https://github.com/navidrome/navidrome/blob/v0.63.2/log/log.go) matches caller source-file
prefixes. This enables the debug calls in the
[HTTP middleware](https://github.com/navidrome/navidrome/blob/v0.63.2/server/middlewares.go), including
successful HTTP completion records, without enabling database or other subsystem debug logs.
`LogFile` selects a destination, not request coverage; no separate access-log enable switch was found
in this version's configuration. The candidate includes user agent, while full headers require trace.
The middleware does not log request bodies. Its request record is emitted after `ServeHTTP` returns:
no completion record still cannot rule out an unfinished handler that received the request.

OBSERVED FROM SOURCE — the redaction hook applies at every level and processes message text and
string fields. Its token rule is `([^\w]t=)[\w]+`, unlike the `s` and `p` rules that consume through
the next ampersand. This suggests a malformed token beginning with punctuation can remain visible.
An independent exact-Go-regexp control confirmed that an all-word token is replaced, while a leading
hyphen token remains and an internal hyphen leaves a suffix. Real-server controls must decide whether
this affects the proposed logging setting. A normal token canary alone would miss this case.

OBSERVED FROM WORKFLOW — the conformance root is a direct child of runner temp. Navidrome stdout and
stderr go to `ROOT/logs/navidrome.log`; existing restarts append to the same file. Download integration
commands execute inside the same `darwin_conformance` step and its cleanup does not remove that log.
Both failure inventory and upload use `!success()` and include `runner.temp/*/logs/*.log`.
Independent local glob control matched `navidrome.log` in that exact layout. This verifies selection,
not an actual hosted upload. Full-run volume and real-server credential controls follow below.
