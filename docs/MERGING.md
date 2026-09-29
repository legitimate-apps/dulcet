# Merging, branch protection and commit-email history

Moved out of `CLAUDE.md` on 2026-09-29. The short rule lives there; this is the measured detail.

## Merging

**What `main` actually enforces — OBSERVED 2026-08-26, read from
`GET /repos/{owner}/{repo}/branches/main/protection` with an admin token:**

| setting | live value |
|---|---|
| required status checks | `core-ci`, `parity-gate`, `apple-ci` |
| `strict` (branch must be up to date with `main`) | **true** |
| `required_pull_request_reviews` | **null — no review is required** |
| `enforce_admins` | **true** — admin bypass is off |
| force pushes to `main` | disabled |

🚨 **Correction, 2026-08-26.** This section previously stated that `main` "requires a code-owner
review" and that "an approval must post-date the last push by a different actor." **Neither is
enforced.** `required_pull_request_reviews` is null, so `CODEOWNERS` advertises ownership and
requests reviewers — it gates nothing. A pull request showing an empty `reviewDecision` and
`mergeStateStatus: BLOCKED` is blocked on **status checks alone**; reading that as a review deadlock
sends you looking for a second approving account that the branch never asked for. Re-measure before
re-asserting either claim.

**`strict: true` is the setting that shapes day-to-day work.** Every pull request must be rebased or
updated onto the current `main` before it can merge, and `apple-ci` is the slow leg. With more than
one pull request in flight this **serialises**: merging one invalidates the others' up-to-date
status and each must re-run. Land them deliberately in dependency order, and tell the other branches
when `main` moves so they rebase once instead of twice.

**History, so nobody re-derives the two-account dance.** There really was a wall here: on 2026-08-20
an approving review from a pull request's own author was refused with
`422 Unprocessable Entity — "Review Can not approve your own pull request"`, and the workaround was
to have one maintainer account open the pull request and the other approve it. That cost real time
and is worth remembering — but it was a workaround for a **required review that is no longer
configured**, so the dance is obsolete, not merely optional. If you meet the 422 again you have gone
looking for an approval nothing asked you for. **Do not "fix" a blocked pull request by enabling
required reviews.**

**Which account opens matters, and not only for the review.** GitHub attributes a **squash-merge**
commit to the *pull request's* author, not to the commit author, and it uses that account's **profile
email** — the `noreply` address only when the account has email privacy enabled. **Open pull requests
as `legitimate-apps`.**

🚨 **Corrected 2026-09-06, and the previous correction was itself understated twice over.** It said
"31 of 103" and that *zero* of our own commits were affected. Re-measured across all of `origin/main`
by classifying **both** the author and committer email of every commit:

```
199 commits total
 31  the account's profile address as AUTHOR       (GitHub-synthesized squash merges)
 97  the account's profile address as COMMITTER    (committer name: legitimate-apps)
---
128 exposed on at least one side  =  64% of main, one single address throughout
```

**Both earlier claims were wrong in the same direction.** The exposure is 64%, not 30%; and it is not
confined to GitHub-synthesized commits, because 97 commits carry the address as *committer* while
naming `legitimate-apps` as the committer. Reading only `%ae` finds 31 and looks like a contained
problem. **Classify both sides, or the measurement flatters the answer.**

❌ **Withdrawn: "Rebase-merge is CONFIRMED to fix it."** That claim, carrying a ✅ and an OBSERVED
date, is false and was load-bearing — it is the reason rebase was chosen deliberately, and rebase is
what produced the 97. GitHub's rebase-merge replays each commit with its **original author** and sets
the **committer** to the account performing the merge, using that account's commit email. So rebase
did exactly what the note said — the `noreply` address is on the author line — while moving the
private address onto the committer line, where nobody was looking. The earlier evidence was not
faked; it inspected `%ae` and stopped there.

➡️ **A ✅CONFIRMED that only ever checked one field is worse than no note at all**, because it ends
the investigation. Neither merge method avoids this: squash exposes the author, rebase exposes the
committer.

✅ **The durable fix is done — OBSERVED 2026-09-06.** "Keep my email addresses private" is now
enabled on the account that opens pull requests. Verified two independent ways: the setting's toggle
reads `aria-pressed="true"` on a fresh page load, and the public API returns `email: null` for the
account, where it previously returned the profile address. It is an account-owner setting, not
anything a repository can configure, which is why no commit-time convention ever substituted for it.

**What that does and does not settle.**
- ✅ **Going forward is now OBSERVED, not assumed.** The first squash merge after the setting change
  is commit `31a7962`, and it is clean on both fields — author `legitimate-apps` with the `noreply`
  address, committer `GitHub` with `noreply@github.com`. The exposed count stayed at 128 while the
  total went 199 → 200. Squash merge is safe again, and the merge method no longer has to be chosen
  around this.
- The 128 commits already on `main` are unchanged. Rewriting them is a destructive history operation
  and is the repository owner's decision, not a cleanup task to be picked up.
- `git commit` identity is still bound by *Identity* above. It was never the cause here, and it is
  still what keeps every commit we author ourselves correct on the author line — a majority of
  `main`, and a figure that moves with every merge, so run the command below rather than quoting one.

**Re-measure before restating any figure here.** Every number above grows with each merge, and each
previous version of this paragraph was accurate when written and wrong within days. The command is

```sh
git log --format='%ae%x09%ce' origin/main | awk -F'\t' \
  '{a=($1 ~ /users\.noreply\.github\.com$/); c=($2 ~ /users\.noreply\.github\.com$/ || $2=="noreply@github.com"); n++; if(!a||!c) x++} END{print x" of "n" exposed"}'
```

Pull-request authorship and commit authorship are separate fields. Commits are authored
`legitimate-apps` by the convention in *Identity* above; that is an instruction here, not something
GitHub enforces.

`@legitimate-apps` is a GitHub **User** account, not an Organization, so there are no teams —
`CODEOWNERS` entries must resolve to individual collaborators while ownership stays as it is.

⚠️ **Nothing in this repository's configuration provides independent review.** Branch protection
requires no approval at all, and ownership sits with a single maintainer. The adversarial review
demanded above is therefore an obligation the maintainer owes the code, not something the merge
button verifies — a green pull request means the checks passed and nothing more.


## Building locally — full notes

CI is entirely hosted, so nothing here depends on a particular workstation. A standard Xcode and
JDK/Gradle setup builds every target.

**Type-check the XCUITest sources before pushing — it takes seconds, and it is not a gate.**

```sh
tools/typecheck-xcuitest-sources --self-test
```

`DulcetiOSUITests` and `DulcetTVUITests` import only system frameworks, so they type-check with no
Gradle build and no Xcode project. A one-character Swift mistake there otherwise costs a full
`apple-ci` job to learn about, on a host that may be too loaded to run Xcode at all. It is
**deliberately not an apple-ci step**: the real build already catches this class there, and the tool
resolves XCTest's global assertion functions through a shim, so a shim that has fallen behind would
block merges on a change the compiler accepts. That staleness is reported as `SHIM GAP` — a fault in
the tool, never as an error in the sources — and `--self-test` proves both that gate and the
type-check itself can fire. The shim does not retire itself: if a toolchain starts resolving those
functions standalone, a same-signature declaration in the checked files **shadows** the imported
one with no ambiguity error (measured), so nothing fails and the stand-ins silently keep answering
for XCTest. Delete the shim when that happens; nothing here will say so. It scans only the `*UITests` directories under `apple/`; every other
test directory is outside its scope (the app-hosted ones need a Gradle-built `DulcetCore` framework),
and a `*UITests` directory that imports one of our modules is listed as NOT COVERED rather than
silently skipped.

Two Apple-toolchain failure modes are worth knowing because they present as something else:

- **A wedged CoreSimulator hangs every Xcode build with no error output** — including device and
  archive builds — freezing at `CompileAssetCatalogVariant`. It looks like a corrupt asset catalog and
  is not. `xcrun simctl list devicetypes | head` returning nothing is the tell; run it before any
  archive.
- **`xcodebuild test` clones the destination simulator by default.** Where cloning is unavailable the
  clone fails *after* a successful build, so the run reads as a test failure when no test executed.
  `-parallel-testing-enabled NO` is the fix.

Concurrent simulator and Xcode builds are memory-hungry enough to trigger OOM kills on a machine doing
anything else; serialise them rather than fanning out locally.

**If you are working on a shared or managed build machine, follow that machine's own operational rules.
They are deliberately not reproduced in this repository.**

