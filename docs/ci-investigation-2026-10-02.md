# Intermittent CI failures investigated on 2026-10-02

The metadata PR's [first core-ci attempt](https://github.com/legitimate-apps/dulcet/actions/runs/37030728491/attempts/1)
failed two jobs. [Attempt 2](https://github.com/legitimate-apps/dulcet/actions/runs/37030728491/attempts/2)
passed on unchanged source. This establishes intermittency, not a root cause or a harmless failure.

## TV album Play dispatch

The first attempt initialized the player and media session, passed the Play-focus wait, and bound
the playback observer. No Now Playing activity or queue transition followed before the timeout.
No app exception, ANR or activity recreation was recorded during that interval. The second attempt
executed the same album proof in 12.818 seconds, observing three queued tracks, index zero, and
2,792 ms of progressing media time with Now Playing in front.

The bound `playAlbum` action invokes the Now Playing callback immediately after `playQueue`; it
does not wait for asynchronous metadata or audio resolution. The failure is therefore localized
before successful execution of that action. The logs cannot distinguish a missed button callback,
an early return for the current publication, or an action held by the composition's playback binding.
No speculative product fix, click retry or increased assertion timeout is justified.

A future failing run needs click receipt, the clicked publication's header/item state and playable
track count, `playAlbum`'s return value, and whether the binding held or executed that activation.
The timeout should also retain focused tags, resumed activity, playback phase/error/queue, and the
album observation frame.

## Core Gradle invocation

The first attempt logged JVM and Android host-test tasks, then bundle/licence tasks through
`licensee UP-TO-DATE` at 16:01:38 UTC. No further output appeared before the existing 20-minute job
timeout at 16:18:50. No failing testcase was named; the app tests never started. Task log lines alone
do not prove the test workers finished. Attempt 2 completed that core invocation in 2m57s.

Previously the workflow uploaded only app test reports, which did not exist when this invocation
stalled. The workflow now retains core JUnit/worker output on success or failure. `observe-gradle`
also takes one thread snapshot after eight minutes, restricted to JVMs descended from the observed
command. Each diagnostic tool has a deadline. It preserves the command's exit status and cancellation;
it neither retries the build nor changes the job deadline. Fast invocations cancel the observer.

The controls cover an owned slow process, exclusion of a foreign JVM, fast success/failure, a stuck
or failed diagnostic tool, and cancellation. A real JVM probe additionally verified that `jcmd`
produces the waiting method's stack. This fixes loss of diagnostic evidence; it does not claim to
have fixed the unexplained Gradle stall.
