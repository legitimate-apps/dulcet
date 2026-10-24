#!/usr/bin/env python3
"""Allowlisted Discover gate evidence. Never serialize response items or raw log lines.

A verifier calls summarize(response, request_window_logs) in its finally/save path.
Read docker logs only for the request's time window; matching concurrent requests
can contribute aggregate outcomes, so this is origin attribution, not a request ID.
"""
import json
import re
import subprocess
import sys

LABEL = re.compile(r"(?:musicbrainz|cover-art-archive|listenbrainz|listenbrainz-labs)(?:-[0-9]+)?|origin-[0-9]+|other-origins")
EVENTS = {"http", "decode", "refresh_failure", "stale_serve"}
OUTCOMES = {"cache_only", "response", "http_status", "timeout", "transport", "dns_timeout", "dns_transport", "cache_write", "backoff", "waiting_cap", "invalid_body", "parse_failure", "closed", "unavailable", "cancelled"}
COUNTERS = {"duration_ms", "attempts", "requests", "status_1xx", "status_2xx", "status_3xx", "status_4xx", "status_5xx", "duration_total_ms", "timeouts", "backoff_applied", "backoff_refused", "waiting_cap", "parse_failures", "stale_serves", "refresh_failures", "refresh_timeouts"}


def summarize(response, lines):
    page = response
    if isinstance(page, dict) and "subsonic-response" in page:
        page = page["subsonic-response"]
    if isinstance(page, dict) and "dulcetDiscover" in page:
        page = page["dulcetDiscover"]
    stale = page.get("stale") if isinstance(page, dict) else None
    if type(stale) is not bool:
        stale = None
    outcomes = []
    for line in lines:
        if "discover_upstream " not in line:
            continue
        fields = dict(re.findall(r"([a-z_0-9]+)=([^\s]+)", line.split("discover_upstream ", 1)[1]))
        if not LABEL.fullmatch(fields.get("origin", "")) or fields.get("event") not in EVENTS or fields.get("outcome") not in OUTCOMES or fields.get("status") not in {"none", "1xx", "2xx", "3xx", "4xx", "5xx"}:
            continue
        safe = {key: fields[key] for key in ("origin", "event", "outcome", "status")}
        safe.update({key: int(fields[key]) for key in COUNTERS if fields.get(key, "").isascii() and fields.get(key, "").isdigit()})
        outcomes.append(safe)
    return {"stale": stale, "upstream_outcomes": outcomes, "outcome_scope": "overlapping log-window origin aggregates; not exact request correlation"}


def capture(response, read_logs):
    """A diagnostic collection fault must not mask primary HTTP evidence."""
    try:
        lines = read_logs()
    except (OSError, subprocess.SubprocessError) as error:
        return {**summarize(response, []), "diagnostics_available": False,
                "diagnostics_error_type": type(error).__name__}
    return {**summarize(response, lines), "diagnostics_available": True}


if __name__ == "__main__":
    data = json.load(sys.stdin)
    print(json.dumps(summarize(data.get("response"), data.get("logs", []))))
