# Release workflow boundaries

**Dispatch is currently blocked before setup or secret use.** The temporary-keychain wrapper
imports only Apple Distribution; it has no Mac Installer Distribution certificate/private-key
import. The workflow exits 78 with that prerequisite named, including for dry runs. Removing the
blocker requires a tested installer identity import and clean-keychain package-export evidence;
selecting `installerSigningCertificate` alone does not supply the identity.


`.github/workflows/release.yml` is deliberately manual-only. Design specification §22 describes
automatic DEV uploads from `main` and tag-selected PROD uploads, while the repository working
agreement requires `workflow_dispatch` as the only release trigger. The manual trigger wins until
that open policy decision is resolved; the workflow must not add a `push` or tag trigger on its own.

The first build uploaded for an App Store Connect app record permanently binds that record to the
build's bundle identifier. Every run therefore defaults to a dry run that archives, exports, and
validates without uploading. A maintainer must select the channel explicitly and turn dry-run mode
off and set `upload=true` after reviewing the resolved bundle identifier. Both choices must be
explicit; absent, empty, null and numeric values authorize no upload. The decision helper reads
the dispatch event JSON without GitHub loose-equality coercion.

DEV (`com.legitimateapps.dulcet.dev`) is a TestFlight-only record for the life of the project. The
release export options set `testFlightInternalTestingOnly=true` for DEV. The bundle-bound uploader
permits only its build-upload routes and rejects review/submission paths before token creation or
HTTP transport. A future PROD submission client must preserve the DEV prohibition; this client
permits no submissions for either channel. This cannot prevent a separate account holder or
unrelated tool from acting outside this code. PROD
(`com.legitimateapps.dulcet`) uses the production target, whose application composition has no
preconfigured-server input; the workflow accepts no server URL and does not add one as a build
setting.

## Deferred: structural exclusion of DEV server configuration

**Finding 4 remains open.** Both `DulcetMac` and `DulcetMacRelease` include the complete
`DulcetMac` and `DulcetAppleShared` directories and the same DulcetKit package. The current
production composition has no preconfigured-server input. That is an observation about today's
code, not a build boundary preventing a future shared source/resource from containing a URL.
The script-body gate does not inspect resource membership and offers no protection here.

Implementing the boundary requires separating the composition roots and auditing transitive
Swift package/Kotlin resources, then rebuilding and checking both channel artifacts. Adding a
DEV-only file or a denylist of known addresses alone would leave the shared-resource route open.
This repair deliberately defers that change instead of claiming partial protection.

Proposed design (not implemented):

1. Split Mac entry points into explicit DEV and PROD target directories. Keep common UI and core
   code channel-neutral. Only the DEV composition root may depend on a dedicated development
   configuration module/resource. PROD's composition must have no dependency on that module.
2. Replace broad app/resource directory inclusion with explicit reviewed manifests for both
   targets. Include the Swift package and Kotlin resource dependency closure in the audit.
   Generated pbxproj source/resource membership and build settings must agree with those manifests;
   verify target attachment, not just script text. Unknown resource additions fail the gate.
3. Keep private development values out of source control and the PROD build environment. Generate
   DEV configuration only into a DEV-owned build input, outside all shared/PROD roots. Build PROD
   from a staging tree containing only its declared inputs; it cannot read the DEV input.
4. Prove the boundary with a synthetic URL canary: DEV's intended configuration path receives it,
   PROD's compiled app/resources do not. Mutating PROD to include the DEV resource or adding it to
   a shared/transitive resource manifest must fail before export. Check hashes before each mutation.
   Also compile/launch both channels and verify their initial account forms and saved-account
   behavior. Artifact canary checks supplement the dependency boundary, not replace it.

Acceptance remains the binding §22.3 requirement. Until this design is implemented and verified,
PROD's structural exclusion must not be described as enforced. Deliberate changes to policy/gates
can still defeat a repository boundary; no build setup makes arbitrary future code edits safe.
