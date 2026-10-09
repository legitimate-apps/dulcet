# Offline download background continuity

This registry tracks the two background-session requirements behind the Apple
`downloads.offline` cells (spec §14.5). As of 2026-10-07 both are settled on
macOS, iOS and iPadOS, and the three cells are shipped. The regression backstop
`tools/test-downloads-macos-review-regressions` keeps each cell, its evidence
and these headings in step. `tools/verify_promotion_conditions.py` checks the
structure of any blocked promotion condition.

## killed-process-background-session-handoff

Status: closed on macOS, iOS and iPadOS (2026-10-07).

The claim: a background URLSession download is still outstanding when the app's
process dies, and a replacement process reconciles the secured artifact into the
durable downloaded row, with the same download identity and the server's bytes.
Each proof runs against the local disposable server, through the app's own
download path, and observes each step on its own:

1. **Outstanding.** The fault proxy holds the download's single `stream` read
   unanswered, and the app's session reports that download running in this
   process.
2. **Death.** The process ends, and the read is still unanswered afterwards.
3. **The transfer continues without the app.** The proxy releases the read and it
   is answered once, with no app process alive: the system finishes it on the
   app's behalf.
4. **Replacement.** A new process, with a new pid, reconciles the session and
   promotes the download into the durable downloaded row: the same download id,
   the server's byte count and SHA-256 read back with the row, and no second
   `stream` read.

The proofs, each run in apple-ci:

| Platform | Test | How the process dies | Who launches the replacement |
|---|---|---|---|
| macOS | `DulcetMacDownloadHandoffAppTest/aKilledAppsDownloadIsReconciledByTheReplacementProcessThroughTheHostedMacApp` | `SIGKILL` from the hosted test | the test, after a 60 s watch in which the system launched nothing |
| iOS | `DulcetiOSUITests/testAKilledAppsDownloadIsReconciledByTheReplacementProcessOnIPhone` | the app calls `exit()` | the system, in the background, for the session's events; the test then foregrounds it |
| iPadOS | `DulcetiOSUITests/testAKilledAppsDownloadIsReconciledByTheReplacementProcessOnIPadOS` | the app calls `exit()` | as on iOS |

The Mac proof launches a copy of the app re-identified with a bundle identifier
of its own, so its background session, preferences and Launch Services record
never touch an installed Dulcet. Its markers come from a DEBUG-only probe
(`DulcetDownloadHandoffProbe`) whose namespace keeps the proof's session,
database and files apart from the app's and is removed when the proof ends.

A mutation check, run locally once: with the launch-time reconciliation skipped,
the Mac proof fails at step 4.

The proofs found a product defect on the way: a launch into a saved account
configured no downloads until the person touched Reconnect, so a download the
system finished while the app was gone was never reconciled. DulcetKit's
`aRelaunchIntoASavedAccountReconcilesItsDownloadsBeforeAnyReconnect` fails without
the fix.

ASSUMED, unmeasured: a task can finish and be absent from `getAllTasks` while its
delegate event has not yet arrived. If that event arrives after `finishReconciliation`,
the row is marked interrupted and the scheduler starts a re-fetch: a second server
`stream` read, despite the first transfer having finished. Promotion accepts any row
state, so the secured artifact still promotes once; idempotent promotion does not
prevent the extra server read. The handoff proofs' three-second settle checks observe
one read in their tested ordering, rather than excluding this race.

### Hosted proof failure diagnostics (2026-10-09)

**Not diagnosed:** two hosted failures stopped at different steps. In
[run 37857703010, attempt 2](https://github.com/legitimate-apps/dulcet/actions/runs/37857703010/attempts/2),
the Mac child's markers reported `download-request-failed` with zero search rows.
Its xcresult system log reported a reader setup `SQLiteExceptionErrorCode`, followed
by search failures with no session (**OBSERVED**). No download was started and no
replacement was tested. The old diagnostic omitted the SQLite error code; a
database lock/schema race is **ASSUMED**, not established.

In [run 37875773587](https://github.com/legitimate-apps/dulcet/actions/runs/37875773587),
the iPad process died with one stream read held, and the system launched its
replacement (**OBSERVED**). The replacement's CFNetwork log showed the ordinary
background-session identifier, while the first process used the probe's namespaced
identifier. The missing overlay therefore accompanies a lost probe namespace,
rather than showing a failed promotion in that namespace. The resolver's rejection
reason was not recorded; missing defaults, bundle-path mismatch and expiration
remain unproven alternatives. A longer promotion wait cannot restore the session
identifier selected once at launch.

The DEBUG resolver now logs `DULCET HANDOFF resolution` even when it rejects the
probe, with a closed result (`missing`, `invalid-record`, `bundle-mismatch`,
`expired`, `active`) and boolean launch/bundle-match fields. No path or defaults
value is printed. Cleanup explicitly names the proof namespace and copies its
marker trail to the app/system log before clearing defaults, including when the
replacement lost them. The Mac test always attaches its final marker trail as
`download-handoff-markers`. Reader failures also name the driver's SQLite error
type and primary code, without its message, SQL or database path.

On the next failure, export the handoff xcresult's attachments and diagnostics
with `xcresulttool export attachments` and `xcresulttool export diagnostics`.
Inspect the replacement's resolution line alongside its CFNetwork session
identifier; inspect `DULCET HANDOFF marker` / the Mac marker attachment and the
reader's SQLite code. These diagnostics change no timeout or proof assertion.
`tools/test-download-handoff-diagnostics` executes the real resolver, including
marker recovery without defaults and canary checks for diagnostic privacy.

## os-initiated-background-session-delivery

Status: settled per platform (2026-10-07).

**iOS and iPadOS: the system relaunches the app.** OBSERVED on the iOS 26.5
simulator, iPhone 17 Pro and iPad Pro 13-inch (M5). The app ended itself with
`exit()` while the download was outstanding, and once the read was released the
system launched the app in the background about 2 s later. That launch was not
made by any harness, ran the scene's `.backgroundTask(.urlSession(...))`, and
reconciled and promoted the same download. A `SIGKILL` from the host was also
followed by a system launch, about 10 s after the release. The iOS proofs assert
the unattended launch and the background-events run as separate steps.

The method is Apple's own advice for testing this path ("Testing Background
Session Code", Apple Developer Forums thread 14855, archived at
https://web.archive.org/web/20250728174416/https://developer.apple.com/forums/thread/14855):
an app that ends itself with `exit()` is not treated as force-quit, so the system
relaunches it in the background. A force quit is different: the app switcher, and
`simctl terminate` (which reaches the app through SpringBoard with
`RBSTerminateContext domain:10 code:0xFBFBFBFB`), cancel the session's tasks, and
the system does not relaunch the app. The same post warns that the simulator "may
not accurately simulate" suspension and resumption.

ASSUMED: a real device's memory-pressure termination relaunches the app as
`exit()` does on the simulator. The simulator has no jetsam command, and these
proofs run on no physical device.

**macOS: no unattended relaunch was observed in the proof's configuration.**
OBSERVED: the Mac proof used an unsandboxed, ad-hoc-signed app copy under a separate
bundle identifier, launched by `NSWorkspace` with `activates=false`. After `SIGKILL`
with the download outstanding, nothing relaunched it within the proof's 60 s watch;
a dedicated measurement watched for 120 s. The released read was answered with no
app process alive, and the next harness launch reconciled the download. This does
not establish relaunch behavior for the sandboxed distribution app. Apple documents
the relaunch for iOS only.
`URLSessionConfiguration.background(withIdentifier:)` describes the case where "an
iOS app is terminated by the system and relaunched". The
`sessionSendsLaunchEvents` wording speaks of the app being launched in the
background, and `application(_:handleEventsForBackgroundURLSession:completionHandler:)`
exists only on `UIApplicationDelegate`; `NSApplicationDelegate` has no
counterpart. Those API differences do not establish a general macOS relaunch rule.
The Mac proof meets the requirement by observing the system finish the transfer
while the app is not running and the app reconcile it at the next harness launch
(spec §28, 2026-10-07). Unattended relaunch in the sandboxed distribution app remains
ASSUMED.
