# Android Emulator reliability — 2026-10-30

## Cause and scope

OBSERVED: [run41008830633](https://github.com/legitimate-apps/dulcet/actions/runs/41008830633)
launched Android Emulator37.2.13 with the original SDK launcher and I/O. A read-only process
observer captured QEMU as a zombie with Linux wait status139 (SIGSEGV), before the action's
cleanup. The host had more than9GiB available and cgroup OOM counters stayed zero. Guest ADB
and console endpoints disappeared; subsequent `pm clear`/night-mode failures were consequences.
The internal crashing instruction is unknown. The37.2.13 release notes describe graphics-resource
cleanup changes; they do not prove which internal change caused this crash.

OBSERVED: changing only the action's `emulator-build` to16428233 in
[run41009859485](https://github.com/legitimate-apps/dulcet/actions/runs/41009859485)
launched37.2.12.0/build16428233 and completed10 native proofs with zero failures, errors or skips.
That single comparison motivates a repeated hosted measurement; it is not by itself a pass-rate claim.

The ordinary `android-test-soak` workflow runs host JVM/Robolectric tests, without an emulator.
Some historical dispatches replaced it with diagnostic emulator jobs. Those must be classified by
their actual logged steps rather than the workflow name. Full attempt census and repeated final
branch measurements are being collected for this change.

## Fix and pin resolution

Both core-ci host-engine paths (Discover and phone/TV playback) consume build16428233 from
`gradle/libs.versions.toml` through `tools/export_toolchain.py`. The commit-pinned
[runner installer](https://github.com/ReactiveCircus/android-emulator-runner/blob/a421e43855164a8197daf9d8d40fe71c6996bb0d/src/sdk-installer.ts#L54)
uses Google's exact archive `https://dl.google.com/android/repository/emulator-linux_x64-16428233.zip`.
After boot, `tools/ci/android-system-image` verifies the actual SDK executable reports
37.2.12.0 and build16428233 before allowing any device proof. A different version or build is an
infrastructure failure, not a fallback to a new engine. Guest API34, image target/ABI, renderer,
app, instrumentation and proof assertions remain the same. No app/proof retry is added.

The optional Discover repetition matrix supplies independent runners/emulators/fixtures, unique
artifacts, fail-fast=false and a required result covering every member. Rep1 preserves the existing
artifact and producing-attempt output. Ordinary pushes/PRs still run one member.

## Local validation

- `tools/test-android-system-image`:23 tests passed,0 failed. Two new controls inject37.2.13
  or the correct version with the wrong build; both are rejected.
- Exact same two controls against the original image verifier:2 tests failed because it accepted
  either engine. With the version guard:2 passed. All temporary old source restored.
- CI policy, parity gate, actionlint and diff whitespace checks passed.

Primary context: [official emulator release notes](https://developer.android.com/studio/releases/emulator).
