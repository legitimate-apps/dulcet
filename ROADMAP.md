# Dulcet roadmap

What comes next, in the order we plan to do it. Spec references point into
[`docs/superpowers/specs/`](docs/superpowers/specs/). What already works on each platform is listed in
[`FEATURES.yml`](FEATURES.yml).

## Where Dulcet is today

Dulcet connects to an OpenSubsonic server or to Dulcet Server. It browses and searches the library,
streams and downloads music, keeps playlists, favorites and play counts in sync, and shows lyrics. It runs
on Mac, iPhone, iPad, Apple TV, Android phones and Android TV. All six apps share one Kotlin
Multiplatform core. The Apple apps were restyled for the 2026 system design, and the Android apps follow
Material 3. Internal beta builds of the Apple apps go out through TestFlight.

## 1. Polish and a design convention (in progress)

We reviewed every captured screen on all six platforms, in light, dark and the largest text sizes, and
side by side across platforms. That review produced 441 findings about spacing, element size,
alignment and consistency. We are fixing them in small packages, one pull request each:

- **Shared foundations first.** Spacing and size tokens, then one shared version each of the
  components that keep being redrawn by hand: the media row, the detail header, the main action button,
  the empty, error and offline messages, and dialogs.
  - Android phone: merged in #437.
  - Android TV: merged in #436.
  - Apple: in review in #438.
- **Then every screen adopts them.** On Apple: the library pages, playlists, account and settings,
  the shell and player, search, and the Apple TV page frame. On Android: library and playlists, the
  player, Discover and Wishlist, connect and settings, and the TV chrome, dialogs, player and search.
- **One glossary across platforms.** "Songs", "albums", "Favorites", and the same verbs for the same
  recovery actions ("Try Again" when a server can't be reached, "Sign In Again" when it refuses the
  credentials).
- **A written convention, `docs/DESIGN-CONVENTIONS.md`.** It sets the spacing scale, artwork and row
  sizes, touch and focus targets, type roles, alignment, section rhythm, the layout of empty, error and
  offline states, and the rules for dark mode and the largest text sizes, on each platform.
- **A check that holds the line.** `tools/verify_design_tokens.py` will fail a pull request that adds a
  raw spacing or size value outside the token files. It starts from a baseline of today's literals, and
  that count may only go down. Each UI pull request also re-captures the screens it touches with the
  design tours, and a design review of those captures happens before the change merges.

## 2. Keep `main` green and CI fast

- Fix the Android TV focus regression behind the current red `main`, issue #439.
- Split the Apple platform job, so a pull request gets its Apple result sooner.
- Keep the required gate small: the core, data safety, server writes and sync, a build of every app,
  and a short UI smoke test. Run the full UI conformance suite and the design tours nightly, and before
  every release.
- Close out the review follow-ups that are still open: #342, #343, #347, #362 and #381.

## 3. First public release on the App Store

- Finish what a public release needs: App Review notes, store listing copy and screenshots from the
  design tours, and a release build that has passed the full conformance and live-server runs.
- Ship iPhone and iPad first, then Mac and Apple TV, one app at a time.
- The public build ships with no preconfigured server. The person using it always chooses their
  own.

## 4. Android release track

- Add an automatic `versionCode` and a separate development build for Android TV, matching the Apple
  development and production channels.
- Run an internal test track on Google Play, then a public release once the polish packages for
  Android have landed.

## 5. Discover and Wishlist

Discover lets a person search beyond their own library: trending music, suggestions and public
playlists. Wishlist keeps a list of what they want added. Its design is in
`2026-10-22-discover-and-wishlist-design.md`.

- Finish the listing experience on every platform.
- Then add a documented way for a server owner to act on wishlist entries. The Wishlist API and its
  webhooks already let a server-side tool react to them.

## 6. Feature evidence

- Bring the rows of `FEATURES.yml` up to date with what each platform demonstrably does, citing a
  test or a captured proof for each cell.
- Mark nothing as shipped on a platform until the real trigger has been driven to the real observed
  effect there.

## How to follow along

Open pull requests show the work in flight. Issues hold follow-ups and anything found in review.
Contract changes are recorded, newest first, in §28 of the main design spec.
