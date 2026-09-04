# Release workflow boundaries

`.github/workflows/release.yml` is deliberately manual-only. Design specification §22 describes
automatic DEV uploads from `main` and tag-selected PROD uploads, while the repository working
agreement requires `workflow_dispatch` as the only release trigger. The manual trigger wins until
that open policy decision is resolved; the workflow must not add a `push` or tag trigger on its own.

The first build uploaded for an App Store Connect app record permanently binds that record to the
build's bundle identifier. Every run therefore defaults to a dry run that archives, exports, and
validates without uploading. A maintainer must select the channel explicitly and turn dry-run mode
off after reviewing the resolved bundle identifier.

DEV (`com.legitimateapps.dulcet.dev`) is a TestFlight-only record for the life of the project. The
release workflow uploads builds but contains no App Store Review submission operation. PROD
(`com.legitimateapps.dulcet`) uses the production target, whose application composition has no
preconfigured-server input; the workflow accepts no server URL and does not add one as a build
setting.
