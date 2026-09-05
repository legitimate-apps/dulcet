"""Bound a readiness JSON request, including a stalled or trickling response body."""

import http.client
import json
import signal
import urllib.error
import urllib.request


TRANSPORT_ERRORS = (
    TimeoutError, ConnectionError, urllib.error.URLError, http.client.IncompleteRead,
)


def read_json(url: str, timeout: float) -> dict:
    if timeout <= 0:
        raise TimeoutError("timed out")
    # These Unix command-line probes run on the main thread. Like the stream probe,
    # use a wall-clock timer: urllib's timeout alone only bounds each socket operation.
    def expired(_signum, _frame):
        raise TimeoutError("timed out")

    previous_handler = signal.getsignal(signal.SIGALRM)
    signal.signal(signal.SIGALRM, expired)
    signal.siginterrupt(signal.SIGALRM, True)
    signal.setitimer(signal.ITIMER_REAL, timeout)
    try:
        with urllib.request.urlopen(url, timeout=timeout) as response:
            return json.load(response)
    finally:
        signal.setitimer(signal.ITIMER_REAL, 0)
        signal.signal(signal.SIGALRM, previous_handler)
