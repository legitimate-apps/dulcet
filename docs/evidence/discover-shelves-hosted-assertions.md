# Discover hosted assertion corrections

The first shelf conformance run, `41164065661` on `e36f7bf7`, failed the same iPad
playlist-entry assertion and iPhone saved-account assertion on attempts 1 and 2.

## iPad playlist activation

`DulcetDiscoverWishlistProof.presented(_:in:)` accepted a lazy row whose midpoint
was inside the application window but above the scroll viewport. After paging to
playlist 23 and scrolling back, `reveal()` returned while playlist 00 remained
behind the navigation and status chrome.

The attempt-2 recording `3E8BB94A-A116-40DF-8F51-99E886918510.mp4` shows Mix 02 as
the first visible row at 307 seconds. Event
`0F203A56-E3BC-430D-AA5F-F393CC3EE2F6` then taps landscape coordinates
`(359.20, 9.15)`, the status bar, scrolling the list to the top. At 309 seconds
Mix 00 is visible, but Public playlists is still the destination. Attempt 1 has
the same status-bar event at `(359.20, 10.15)`. Both fixture request logs contain
the playlist-list reads at sequences 261 and 262 and no
`getDulcetDiscoverPlaylist` read. The product page was never opened.

The visibility helper now intersects the ancestor scroll/list viewports and
excludes navigation chrome, using one immutable snapshot per poll. The playlist
activation requires live hittability and taps the validated row center. The entry,
duplicate-position, paging and source-aware wish assertions remain in place.

## iPhone Connection access

The five-tab shell removed Connection from the tab bar, but its toolbar was
attached outside `DulcetDestinationStack`'s navigation content. The attempt-2
recording `70FE4426-4CC9-41D8-BFDA-8E173434F575.mp4` shows no Connection button on
either Library or Browse. At `10:12:45`, the test taps a stale
`dulcet.tab.settings` identifier; navigation receipt `NAV|33` records
`library->browse`. The final hierarchy
`A1A5AEE8-7908-4C0E-A22B-B8942D8F9084.txt` shows Browse and five tabs, with no
Connection sheet. The saved-account list was never presented.

Connection and the sheet's Done action now belong to the in-stack root and
pushed content. Toolbar availability is read in the view body so capability
changes invalidate that content. This shared shell covers iPhone and compact
iPad; regular-width iPad and Mac keep their sidebar entry point. The test helper
checks the current native tab label as well as its identifier and dismisses the
sheet through its dedicated Done control. The account test explicitly requires
Connection at the root and on the pushed Wishlist page, then retains the saved
account, owner isolation and replacement-consent assertions.

The two failed hosted attempts are the fail-before evidence. Build-only checks do
not establish that either device assertion passes; the follow-up conformance run
must execute both named tests with no skips.

Local integration checks passed: DulcetiOS, DulcetMac and DulcetTV
`build-for-testing` (3/3); UI source typechecking (5 directories, 21 source
entries); parity (44 feature rows); CI policy (13 workflows). These local checks
compiled the tests and did not execute runtime tests.
