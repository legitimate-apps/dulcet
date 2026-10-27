# Combined TV Discover and Wishlist integration

The combined branch retains the [Android TV implementation and device evidence](discover-tv-android.md)
and the [Apple TV implementation and hosted proof contract](discover-tv-apple.md). It merges the two
TV implementations from their common shelf-feature base without changing the core interface or
promoting feature-support cells.

## Merge resolution

The only textual conflict was in `tools/ci/run-discover-wishlist-proofs`. The combined runner accepts
both `android-tv` and `tvos`. Android TV still delegates to `run-discover-wishlist-android --tv`;
tvOS retains compile-only preparation and its GitHub-hosted runtime admission guard. The merged
report tests retain both the Android TV dispatch control and the tvOS exact-inventory, device,
fixture-effect and complete-flow controls.

The Xcode project merged without conflict. Its generated files exactly match pinned XcodeGen
2.46.0, including the regeneration self-test. No generated project file was hand-edited.

## Observed combined-tree checks

The Android host tasks executed again on the combined tree. Counts come from JUnit XML; every task
has zero failures, errors and skips.

| Host task | Executed |
| --- | ---: |
| `:android:tv:testDebugUnitTest` | 115 |
| `:android:app:testDevDebugUnitTest` | 306 |
| `:android:app:testProdDebugUnitTest` | 306 |

DulcetTV, DulcetiOS and DulcetMac each completed `build-for-testing` for arm64, with explicit
DerivedData paths and serial test configuration. All five XCUITest source directories type-check,
covering 17 source files. The serial DulcetKit package run finalized 434 passing tests in 24 suites,
with zero failures, errors or skips in exported JUnit. The shared proof runner and report self-tests
pass 26 cases. The Android
runner self-test also retains exact phone, shelf and TV inventories of 10, 6 and 2 executions and
rejects zero, incomplete and wrong-class results.

Parity validates 44 feature rows. Migration validates 14 fixture databases and its 32 explicit
destructive negative controls. CI policy validates 13 workflows, including both TV jobs and their
required aggregate wiring.

## Runtime and hosted boundaries

The merge changes no Android source or Android proof-helper code. Its Android TV dispatch remains
unchanged and passes its forwarding control. The existing API 34 Android TV proof therefore remains
the device evidence: two standard-text cases and one font-scale-2.0 primary case, all passing.

The Android TV fixture job passed on the hosted x86 image in
[core-ci run 40464038529](https://github.com/legitimate-apps/dulcet/actions/runs/40464038529).
The host soak is a separate Linux execution sample.

Apple TV runtime remains unproven. The first hosted `discover-wishlist-tvos` run must establish real
remote focus, system keyboard entry, Back restoration and the complete fixture-backed wish flow.
Its two-case inventory, observed TV identity, authoritative fixture effects, captures and finalized
result bundle are required; compilation alone cannot satisfy that contract.

Both TV proof modes and CI jobs are present on this branch. Integration with main, feature-support
promotion and the contract-history entry are deferred until the shelf-feature base has landed.

## Hosted Android follow-up

The first Linux host run exposed a stale pre-TV assertion. `TvLibraryEntry` accesses the existing
`LibrarySession.discoverWishlist` property to obtain feature availability. That shared property
refreshes Wishlist, which negotiates extensions and reads `getDulcetWishlist` when supported. The
old test asserted zero Wishlist reads immediately after an unrelated catalog response; its local
pass depended on asynchronous ordering. Waiting for the bootstrap reproduces the same failure.

The supported-account test now positively waits for the Wishlist request and advertised Discover
navigation, verifies that Listen Now remains visible and Discover content is unopened, and permits
only `getDulcetWishlist` among feature requests. Discover content reads and wish mutations remain
forbidden before interaction. This preserves the existing phone-shared bootstrap behavior;
unsupported accounts still have no feature traffic.

The generic REST emulator runner also excludes `AndroidTvDiscoverWishlistProofTest`, matching the
phone proof class exclusion. Each Discover proof class runs only through its dedicated fixture job,
with the required runner arguments. A dispatch regression covers exclusions for both surfaces.
The shared runner/report self-test inventory is now 27 cases, all passing locally.
