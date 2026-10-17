# Android listening sessions

The phone and Android TV share the playback service, core queue decisions and Media3 adapter.
The phone puts listening options in Up Next. TV puts a focused Listening options button below
artwork, with scrollable remote choice dialogs; queue actions use Move Up/Down and Remove.

## Host coverage

- `AndroidMedia3ListeningSessionTest`: timed sleep on elapsed time, ten-second gain fade, cancel,
  end-of-track and end-of-queue stop points; AutoPlay answer identity, queue order and switch-off;
  confirmations after application, empty-player behavior and retiring superseded notices; saved crossfade and album boundary;
  controller preload promotion, incoming failure cleanup, restored overlap after Off/On or a
  cancelled stop point, a stable media-session bridge, and a three-track chain that keeps at most
  two players alive while allowing the next preload after the outgoing tail finishes.
- `AndroidMedia3EngineTest`: genuine simultaneous player intent and fade gains, one natural end at
  handoff, no reporting listener on the outgoing tail, one counted play per progressing session;
  a zero boundary creates no second player and ends through the existing path.
- `PhoneListeningSessionTest`: AutoPlay switch, every sleep choice, cancel, crossfade range,
  AutoPlay section and one-shot queue notices.
- `TvListeningSessionTest`: D-pad choices and scrolling, the integrated long-title/rating/queue
  layout and queue notice delivery.

These tests observe adapter/controller behavior. They do not measure OS mixer output, perceived
loudness or sample continuity; acoustic gaplessness remains unclaimed.

## Host execution

Every changed host class passed twenty Linux repetitions:

| Task and class | Executions per repetition | Result |
| --- | ---: | --- |
| Core `AndroidMedia3EngineTest` + `AndroidMedia3ListeningSessionTest` | 22 | `SOAK RESULT passed=20 failed=0` ([run](https://github.com/legitimate-apps/dulcet/actions/runs/38995469153)) |
| Phone PROD `PhoneListeningSessionTest` | 2 | `SOAK RESULT passed=20 failed=0` ([run](https://github.com/legitimate-apps/dulcet/actions/runs/38993294179)) |
| TV `TvListeningSessionTest` | 3 | `SOAK RESULT passed=20 failed=0` ([run](https://github.com/legitimate-apps/dulcet/actions/runs/38993291861)) |
| Phone DEV `PlayBeforeBindTest` | 4 | `SOAK RESULT passed=20 failed=0` ([run](https://github.com/legitimate-apps/dulcet/actions/runs/38993633276)) |
| Phone PROD `*Playback*Test`, including the adapted release/account-change classes | 13 | `SOAK RESULT passed=20 failed=0` ([run](https://github.com/legitimate-apps/dulcet/actions/runs/38993781846)) |

Each repetition deletes old results and counts the newly written JUnit XML. The full host tasks
at `7a78a26d` completed with zero failures or skips in [core-ci](https://github.com/legitimate-apps/dulcet/actions/runs/38994894149),
counted from the uploaded JUnit XML. The later three-track regression is included in the core
soak above; the PR's checks run the full suite again at its final head.

| Task | Classes | Tests |
| --- | ---: | ---: |
| `:core:testAndroidHostTest` | 83 | 1,283 |
| `:android:app:testDevDebugUnitTest` | 41 | 206 |
| `:android:app:testProdDebugUnitTest` | 41 | 206 |
| `:android:tv:testDebugUnitTest` | 27 | 89 |

Phone DEV app and instrumentation APKs also assembled successfully.

## Phone runtime proof

`AndroidEmulatorListeningSessionProofTest` is opt-in instrumentation against a disposable
Navidrome. It drives Up Next and its AutoPlay switch, observes server candidates after the user
entry and their removal on switch-off, selects 15 minutes, checks the service countdown after Home
with the observer unbound, cancels it, selects End of track and seeks near the end to observe the
next entry selected paused. Its output includes compact accessibility labels and service state.
The PR links the completed runtime job and its observed labels/countdown values. The full timed
deadline and fade are exercised with a monotonic host clock; the device proof selects the real
15-minute choice, checks background survival and cancellation, then shortens only the wait for
the end-of-track boundary by seeking. TV's new listening controls have host D-pad coverage;
the existing TV device suite covers playback, library queue ordering and service lifecycle.
