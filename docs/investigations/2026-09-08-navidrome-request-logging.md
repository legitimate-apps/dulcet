# Navidrome 0.63.2 request-logging evaluation

**Decision: reject the candidate; keep the committed configuration at `info`.** Real-server
controls found a token canary exposed by the added HTTP debug records. The log volume is manageable
and browse visibility works, but the privacy acceptance condition fails.

Scope: evaluate server visibility for the Apple download integration's library-browse timeout.
The committed fixture configuration remains unchanged while the candidate is measured. No request
timeout, retry, assertion, budget, routing or CI server-lifecycle change is part of this evaluation.

OBSERVED FROM PINNED SOURCE — there is a narrower option than global debug:

```toml
LogLevel = "info"
EnableLogRedacting = true

[DevLogLevels]
"server/middlewares" = "debug"
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


OBSERVED — the real binary rejects a quoted TOML override key ending in `.go`: Viper treats the
period as a nested key, yielding `expected type string, got ... map[string]interface {}`. The
candidate therefore uses the prefix `server/middlewares`, which selects the same file without the
configuration parser ambiguity. The failed startup was excluded from volume comparison; its waiting
precondition process was stopped once the fatal configuration error was identified.


## Full Darwin comparison

OBSERVED — each level ran the **unfiltered** `:core-conformance:macosArm64Test` suite against a fresh
disposable native Navidrome 0.63.2 at `http://127.0.0.1:4533`, with the same 314-file deterministic
corpus and pinned ffmpeg 9.0.1. Each run passed **54 tests in eight classes**, zero failures/errors/
skips. This is the full macosArm64 Darwin conformance suite, not the complete Apple UI CI job.

| Measurement window | Current info bytes | Current info lines | Narrow request debug bytes | Narrow request debug lines |
| --- | ---: | ---: | ---: | ---: |
| Full Gradle conformance invocation | 8706 | 31 | 103558 | 301 |
| Startup, preconditions, existing cold-restart preparation, and full invocation | 19992 | 131 | 126154 | 433 |

The full-run increase is **94852 bytes and 270 lines**: 11.90 times the bytes and 9.71 times the lines,
but only about 101 KiB total. The startup-through-run candidate total is about 123 KiB. This is not
disproportionate for the fixture; volume alone would not reject it. This is one matched pair, not a
performance distribution. The measured Gradle invocations took 19.982 and 15.907 seconds; cache and
build differences prevent treating that as a logging-performance result. Canary probes were run
**after** each measurement endpoint, so they do not inflate either volume window.

Reproduction uses the repository's `new-class-root`, `render-config`, and `health-check` commands,
`TZ=UTC`, and the pinned synthetic corpus. For the candidate, append the shown DevLogLevels table
to the **temporary rendered** configuration only. Keep the redirect, untrusted-TLS and proxy fixture
helpers on their existing ports. After health-check, snapshot lastScan, stop Navidrome, clear the
transcode cache, restart and require lastScan to advance using the existing readiness tools—the
same cold-run preparation used by apple-ci. Record the server log byte offset, then run:

```sh
TZ=UTC \
DULCET_CONFORMANCE_BASE_URL=http://127.0.0.1:4533 \
DULCET_CONFORMANCE_DISPOSABLE=true \
DULCET_REDIRECT_CONFORMANCE_ROOT=http://127.0.0.1:4540 \
DULCET_UNTRUSTED_TLS_URL=https://127.0.0.1:4542 \
./gradlew --no-daemon --max-workers=1 -Dorg.gradle.jvmargs=-Xmx1536m \
  :core-conformance:macosArm64Test --rerun
```

Count bytes and newline characters from the saved offset to command exit; count the complete log
through that same endpoint separately. No existing CI lifecycle script was changed. Initial setup
attempts rejected missing `TZ=UTC`, an omitted readiness mode, and the dotted configuration key;
none entered the measured conformance window. The successful comparison required all preconditions.

## Canary result: reject

OBSERVED — after each suite, successful authenticated GET and form-POST requests exercised all four
browse endpoints, using salt `ND_LOG_SALT_CANARY_20260908` and the correct token derived from the
fixed fixture password. A successful one-album list supplied the ID for getAlbum. All eight replies
had Subsonic status `ok`. Additional GET and form-POST probes supplied invalid password and token
canaries; each was required to return a failed Subsonic envelope. This exercises both success and
failure request-log paths without using a hex-pattern scan.

| Canary | Info log | Candidate log |
| --- | --- | --- |
| Valid token derived from fixed fixture password and canary salt | Absent | Absent |
| Salt `ND_LOG_SALT_CANARY_20260908` | Absent | Absent |
| Password `ND_PASSWORD_CANARY_!Plus+Amp&20260908`, raw and query-encoded | Absent | Absent |
| Token `ND_TOKEN_CANARY_WORD_20260908` | Absent | Absent |
| Token `-ND_TOKEN_CANARY_LEADING_20260908` | Absent | **Present verbatim once in new HTTP debug record** |
| Token `ND_TOKEN_CANARY_PREFIX-SUFFIX_20260908` | Absent | **Suffix `-SUFFIX_20260908` present once** |

The last two exposures occurred on GET requests; the form bodies were not logged. Safe excerpts
from the query fields were `t=-ND_TOKEN_CANARY_LEADING_20260908` and
`t=[REDACTED]-SUFFIX_20260908`. These are artificial canaries, not real credentials. The matching
records had HTTP 200 even though their Subsonic envelopes reported authentication failure. The
candidate therefore exposes malformed credential input that was absent at info; rejecting invalid
authentication does not justify logging its value. The ordinary successful-token redaction control
would have missed this defect.

`u=dulcet-admin` remains visible. It is the existing published disposable account, already present
in current logs, so that adds no new secret identity for this fixture and is **not** the rejection
reason. It does mean the candidate cannot be described as whole-query redaction.

## Browse visibility and artifact selection

OBSERVED — the full candidate run produced these HTTP completion records, all carrying HTTP 200:
getMusicFolders **10**, getArtists **10**, getAlbumList2 **45**, getAlbum **78**. The info run produced
no ordinary HTTP completion records for them. The separate successful GET/POST controls establish
that visibility is not confined to failed API requests.

OBSERVED — local collection control copied the actual 103558-byte candidate run log into the same
runner-temp/root/logs layout, executed the inventory shell block extracted from the current workflow,
and expanded the **actual upload path list**. Both steps' `!success()` conditions were checked; the
download commands live in the step whose failure activates them, before the later conformance tests.
The selected log was packed into a local ZIP and read back byte-for-byte, then checked for all four
endpoint counts above. Moving it outside the collected paths made selection empty:

```text
inventoryMatched=1 archiveBytes=103558 byteIdentical=true
getMusicFolders=10 getArtists=10 getAlbumList2=45 getAlbum=78
negativeOutsidePatternMatches=0 hostedUploadExecuted=false
```

This verifies file selection and preservation with the real measured log; it does not claim the
GitHub upload action ran locally. No push, workflow dispatch or PR action was performed.

**Are browse endpoints now visible in a collected CI artifact? No.** The rejected temporary candidate
would make them visible in the selected Navidrome log, but the committed configuration remains info.
The known blind spot remains. A safe follow-up would first fix/replace the upstream token redactor
or provide a dedicated query-free request logger, then repeat these controls; neither is silently
introduced in this diagnostic configuration evaluation. A completion-only logger also cannot prove
non-arrival from a missing line when a handler is still pending.

The [result dataset](2026-09-08-navidrome-request-logging-results.json) preserves the volume, test,
canary and artifact-control results without raw logs or machine paths. Independent review recomputed
both test totals, byte/line counts, malformed-token exposures and ZIP equality from the raw local
evidence, supporting rejection. CI policy and parity checks passed; no runtime code was changed.
