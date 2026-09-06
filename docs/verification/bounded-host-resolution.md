# Bounded host resolution

OBSERVED: `platformResolveHost` has Apple, JVM, and Android actuals. All now call
`boundedHostResolution`. Apple keeps `getaddrinfo` / `getnameinfo` / `freeaddrinfo`;
JVM and Android keep `InetAddress.getAllByName`. A null Java address fails the entire
lookup; it is never filtered out of a potentially mixed answer.

OBSERVED: each platform owns one lazy, process-lifetime two-thread pool created by
`newFixedThreadPoolContext`. On Kotlin/Native this API owns native worker threads;
`dispatch` submits the blocking operation there. It does not use `Dispatchers.IO`
or depend on a main dispatcher implementation. The waiter uses
`suspendCancellableCoroutine`, with no structured child that must be joined when
the deadline expires. Late results cannot resume a cancelled waiter successfully.
The Apple allocation scope and `freeaddrinfo` remain on the worker until completion.

Design choice: the production deadline is **3,000 ms**. This is an interactive
connection security check for local DNS/mDNS: three seconds gives a transient
lookup time to retry while limiting the pre-request wait to a few seconds. It is
a product latency budget, not a measured guarantee that every LAN resolves in that
time. A slower network is denied; no unresolved-host allowance is introduced.

OBSERVED: a shared two-permit semaphore bounds outstanding operations. Permits stay
held until the blocking operation actually returns, even after timeout. Saturation
returns empty immediately, rather than queueing more work. The OS operation itself
is not interrupted. Two permanently stuck operations will deny subsequent hostname
lookups for the process lifetime; literals remain usable. This is deliberate fail-closed
resource containment. The dedicated threads are retained for the process lifetime.

OBSERVED: `targetFor` still rejects empty or any nonlocal answer;
`leavesLocalNetwork` still returns true for those answers. The policy boundary maps
resolver timeout, cancellation (including parent cancellation), and exceptions to
empty. Existing address classification, URL pinning, redirect comparisons, and
credential policy are unchanged. Cancellation is translated only at the DNS security
boundary; it is not cleared from the parent job.

## Regression proof

OBSERVED: tests use a 500 ms non-cooperative busy wait as the blocking syscall stand-in,
with an 80 ms waiter deadline. A heartbeat scheduled on the calling dispatcher must
run before resolution returns, and rejection must arrive within 400 ms. A separate
test exercises the redirect call site. No real slow DNS or external service is used.

OBSERVED: temporarily replacing the body of `boundedHostResolution` with `lookup()`
(disabling the off-thread/deadline mechanism, reproducing the old synchronous behavior)
and running:

```sh
./gradlew :core:jvmTest --tests '*HostResolutionTest.blockingLookup*' --max-workers=1 -Dorg.gradle.jvmargs=-Xmx1536m
```

produced:

```text
HostResolutionTest[jvm] > blockingLookupDeadlineRejectsRedirect[jvm] FAILED
HostResolutionTest[jvm] > blockingLookupDoesNotBlockCallerAndDeadlineRejectsTarget[jvm] FAILED
2 tests completed, 2 failed
BUILD FAILED in 11s
```

This is a mechanism-disable regression experiment, not a claim that the new test
helpers compile against an untouched historical revision. The implementation was
restored before the passing runs and commit.

## Final validation

OBSERVED: final fixed-code runs, sequential, one Gradle target at a time:

```sh
./gradlew :core:jvmTest --tests '*HostResolutionTest' --tests '*RedirectHostPolicyTest' --max-workers=1 -Dorg.gradle.jvmargs=-Xmx1536m
# BUILD SUCCESSFUL in 8s
./gradlew :core:macosArm64Test --tests '*HostResolutionTest' --tests '*RedirectHostPolicyTest' --max-workers=1 -Dorg.gradle.jvmargs=-Xmx1536m
# BUILD SUCCESSFUL in 16s
```

OBSERVED: each target's Gradle XML reports `HostResolutionTest tests=10 failures=0
errors=0` and `RedirectHostPolicyTest tests=9 failures=0 errors=0` (19 passing per
target). Coverage includes both empty-result decisions, suspended timeout, thrown
cancellation, parent cancellation, worker errors/cancellation, late blocking results,
caller responsiveness, saturation/recovery, mixed-address rejection, literal bypass,
and the platform's numeric loopback resolver.

OBSERVED: Android compilation also passed after the null-address handling change:

```sh
./gradlew :core:compileAndroidMain --max-workers=1 -Dorg.gradle.jvmargs=-Xmx1536m
# BUILD SUCCESSFUL in 4s
```

OBSERVED: `git diff --check` returned exit 0 with no output.

Scope: no push, PR, simulator, xcodebuild, or service lifecycle operations. Tests
use injected resolvers and numeric loopback resolution, with no HTTP requests.
ASSUMED / untested: actual main-thread Apple facade interaction, real DNS failure
latency, Android runtime execution, and iOS/tvOS binaries. macOS executes the shared
Apple actual and the shared regression tests directly.

API references:
- https://kotlinlang.org/api/kotlinx.coroutines/kotlinx-coroutines-core/kotlinx.coroutines/new-fixed-thread-pool-context.html
- https://kotlinlang.org/api/kotlinx.coroutines/kotlinx-coroutines-core/kotlinx.coroutines/suspend-cancellable-coroutine.html
