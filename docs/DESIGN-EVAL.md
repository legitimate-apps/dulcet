# Dulcet macOS design evaluation

This document defines the reproducible evidence and scoring contract for Dulcet's native macOS
fixture UI. It evaluates design quality; it does not claim that the network, cache, playback engine,
or account flow is implemented. The views are driven by an in-repository deterministic fixture so a
later data source can replace it without changing the visual layer. The presentation source owns
content and ordering, including the Recently Added selection; view code contains no deterministic
fixture identifiers. All human-facing view copy and compound formatting route through the localized
`DulcetStrings` resource boundary.

## 1. Evidence contract

The `apple-ci` job produces the artifact named `dulcet-macos-design-captures-<run>-<attempt>`. Its
`run-a` directory is the evidence set. The job renders a second complete `run-b` directory and
requires a recursive byte comparison before uploading `run-a`.

### 1.1 Capture composition and claim boundary

The current artifact does **not** render the shipping root composition. The macOS application
installs `DulcetRootView`, whose standard branch places `DulcetSidebar` and the selected state surface
inside a balanced SwiftUI `NavigationSplitView`. The capture executable instead installs
`DulcetCaptureView`, a fixed-composition sibling that places the same `DulcetSidebar` and state surface
inside a `GeometryReader` and plain `HStack`, with a fixed 232-point sidebar frame and an explicit
divider.

This sibling exists because `NavigationSplitView` places its sidebar in a separate AppKit compositor
subtree that `NSHostingView.cacheDisplay` does not include. Capturing `DulcetRootView` through that
path therefore omits the sidebar. Reusing the sidebar and state-surface components preserves their
contents, but it does not make the two root containers visually or behaviorally equivalent.

The captures are evidence for the pixels produced by those shared content components **as embedded
in `DulcetCaptureView`**, together with the fixed fixture payload, appearance, capture-window chrome,
and geometry recorded by the manifest. They are **not** evidence for:

- the end-to-end pixels produced by the shipping `DulcetRootView` composition;
- `NavigationSplitView` sidebar material, backdrop, selection appearance, divider, column allocation,
  collapse, or resizing behavior;
- window, toolbar, navigation-title, or compositor integration that depends on the shipping
  `NavigationSplitView` hierarchy; or
- pixel parity between the design artifact and the shipping macOS application.

This boundary is a measured limitation, not an untried assumption. Hosted `apple-ci` experiments on
2026-08-22 installed the actual `DulcetRootView` in the titled window and retained both the exact
1180 × 760 geometry guard and the recursive byte comparison:

- `NSWindow.dataWithPDF(inside:)` was byte-deterministic in run `32553554450`, but the resulting
  pixels were not faithful. The PDF retained only fragments such as the window title, an empty
  selection shape, and a scrollbar while omitting sidebar labels and detail content. The existing
  distinct-state gate rejected the artifact because the empty-library and offline dark captures
  decoded to identical pixels.
- `SCScreenshotManager` with a desktop-independent filter for the exact `NSWindow.windowNumber`
  captured the complete shipping composition, including the native sidebar material, selection,
  split-view divider, toolbar integration, and detail content. It was not byte-deterministic in run
  `32554080215`. Pre-sizing the window before display, disabling window animation, and increasing
  post-layout compositor settling from 80 ms to 500 ms eliminated the sidebar-edge differences, but
  run `32554336276` still differed in `offline-metadata-only-light`: 237 decoded pixels (0.0264% of
  the image) inside the GPU-muted artwork region at `x=552…815`, `y=248…383`, with channel deltas of
  one to three levels.
- `CGWindowListCreateImage` was then exercised against the same settled on-screen window in run
  `32554568474`. It reproduced the same sole failing file, the same 237 changed decoded pixels, the
  same bounding box, and the same per-channel delta distribution. This independently confirmed that
  the residual variance comes from the shared WindowServer/compositor output rather than
  ScreenCaptureKit's JPEG path.

The measurements do not support claiming that sidebar material is intrinsically nondeterministic:
the settling configuration made every sidebar pixel byte-stable. They do establish that, on the
hosted evidence platform, no tested faithful window-level path captures the complete shipping
composition while also satisfying exact byte determinism across the declared states. Pixel
tolerances, post-capture normalization, and a weaker geometry rule were deliberately not adopted.
The fixed sibling therefore remains the evidence path. A design rating of this artifact is a rating
of the fixed capture sibling, not of the shipping view, and no stronger shipping-UI claim should be
made from it unless a future capture path passes both unchanged gates.

Every image is a compressed JPEG of the complete titled `NSWindow` at exactly 1180 × 760 pixels.
The standard AppKit title bar, title, and traffic-light controls are inside the evidence boundary;
content-only or borderless renders are not eligible to calibrate the native-macOS lens. The render
environment is fixed:

- macOS runner image and Swift toolchain selected by `apple-ci`;
- light or dark appearance set explicitly rather than inherited from the runner;
- `en_US_POSIX` locale, Gregorian calendar, UTC time zone;
- fixture clock `2026-08-21T14:32:00Z`;
- macOS system semantic fonts at their platform size;
- no network-backed data source, downloaded artwork, audio decode, or animation;
- procedural artwork generated from stable fixture identifiers;
- a titled, closable, miniaturizable, resizable `NSWindow` with standard AppKit chrome;
- a capture-only `NSWindow` subclass that retains standard titled chrome but declines AppKit's
  visible-screen frame constraint, so the 1180 × 760 evidence surface does not shrink to the hosted
  desktop; content is installed and the window ordered before the fixed frame is applied, followed by
  a runtime geometry guard requiring an 1180 × 760 window frame and a zero-origin 1180 × 760 AppKit
  theme-frame capture boundary;
- SwiftUI `controlActiveState` fixed to `key` and recorded per manifest entry, so standard prominent
  button fills and their rendered label contrast are present in pixels even though a hosted command-
  line process cannot become the desktop's active application;
- the standard centered AppKit window title remains visible, while each content surface carries its
  own visible state heading and debug target suffixes are excluded;
- one discarded library-browse preflight render before recording, so first-use AppKit font, symbol,
  and view caches have the same warmed state in both independent capture processes;
- JPEG compression factor 0.72.

Each capture directory includes `manifest.json` with the complete filename set, environment values,
per-record window-frame and capture-bound coordinates, rendered control-active state, a provenance
declaration, byte lengths, and SHA-256 digests. Current-run references carry measured geometry; pinned
controls carry the reviewed baseline geometry. Each JPEG also carries a self-authored consistency
comment with a fixture-state label, appearance, variant, and the SHA-256 of the original compressed
payload. The verifier removes that comment, reconstructs and hashes the payload, and requires the
embedded labels, filename, and manifest record to agree. It separately decodes every JPEG through the
platform image decoder and requires all 16 normalized pixel rasters to be pairwise distinct.

This is **internal consistency plus pixel distinctness**, not proof that the pixels depict the named
fixture state. An artifact author can swap two compressed payloads, regenerate each embedded comment,
and refresh the manifest; the verifier will accept the self-consistent swap because this repository
has no independent visual oracle for what either state must look like. Human or model evaluation of
the visible content remains the external semantic check.

`tools/verify_design_captures.py` rejects a missing, extra,
renamed, oversized, wrong-dimension, non-JPEG, or hash-mismatched image. The verifier also requires
the deliberately bad control. `tools/test-design-capture-gates` proves those checks reject a missing
control, a mutated JPEG, an extra JPEG, extra media with another extension, an artifact whose 16 JPEGs
were replaced by one image while every manifest hash and byte count was updated, two payloads swapped
without updating their embedded labels, one payload rebound to 16 distinct consistent labels, and two
different JPEG encodings that decode to identical pixels. It also demonstrates the semantic boundary
by accepting a two-payload swap after both embedded comments and the manifest are made internally
consistent. Further negative controls cover a byte-identical capture mislabeled as a different Dynamic
Type treatment, translated capture bounds, an inactive rendered control state, and a substituted valid
control JPEG whose ordinary manifest evidence was updated; the pinned digest must still reject the
control substitution.

### 1.2 Standard set: 16 JPEGs

Each reference state appears in light and dark:

1. `empty-library-no-account`
2. `library-browse`
3. `album-detail-multi-disc`
4. `now-playing`
5. `search-mixed-sources`
6. `error-tls-untrusted`
7. `offline-metadata-only`

For the checked-in deterministic fixture, `error-tls-untrusted` constructs a
`DulcetConnectionFailure.tlsUntrusted` value containing the failure detail used by the current views;
the Swift package test checks that fixture output. This is not a type-system invariant:
`DulcetSnapshot` publicly stores `state` and `connectivity` independently, so a manually constructed
snapshot can make them inconsistent. No broader type-safe TLS-state derivation is claimed.

The filenames are `macos-<state>-<appearance>.jpg`.

The search fixture contains ten mixed-source results to exercise real scrolling and stable source
annotation. Search uses SwiftUI's native macOS `Table`, with Result, Type, and Source columns and
native row selection/scrolling. Source provenance is a plain label plus an SF Symbol in the Source
column—not a custom capsule or status tag—and therefore aligns at the top of the same native row as
the 40-point artwork and result identity. The table presents substantially more results in the fixed
evidence frame while retaining title, subtitle, refresh status, kind, and source; the remaining rows
stay available through the table's native scroll surface.

The two negative-control files are:

- `macos-CONTROL-DELIBERATELY-BAD-library-browse-light.jpg`
- `macos-CONTROL-DELIBERATELY-BAD-library-browse-dark.jpg`

They intentionally contain broken spacing, a clashing accent, inconsistent typography, poor
contrast, arbitrary card styling, and mismatched hierarchy. They are calibration evidence, never a
product design.

### 1.3 Pinned negative-control baseline

The two bad-control JPEGs are reviewed, checked-in resources under
`apple/DulcetKit/Sources/DulcetCapture/Resources/PinnedControls`. A normal capture with
`--include-control` does not render `DulcetDeliberatelyBadControlView`; it copies those bundled bytes
verbatim. The capture executable and artifact verifier each hold the reviewed SHA-256 values, the
workflow compares the artifact controls byte-for-byte with the checked-in resources, and manifest
schema 8 records `controlBaselinePolicy` and `pinnedControlSha256`. The verifier rejects a substituted
valid JPEG even when its ordinary manifest hash and byte count have been updated. Decoded-pixel
distinctness also rejects an attempt to place the pinned control pixels in a reference slot while the
required pinned control remains in the artifact, including when the reference uses a different JPEG
encoding.

`captureProvenance` is only a self-declared classification. The verifier requires the expected literal
(`bundled-pinned-resource` for controls and `rendered-current-run` for references) as an internal
consistency check; it does not measure how an arbitrary file was produced. The fixed control digests,
resource comparison, and pixel-distinctness gate are the protections described above. Geometry and
active-control fields on a pinned record describe the reviewed baseline image; they are not a claim
that the control was re-rendered in the current run.

Control regeneration is an explicit candidate-producing act:

```sh
tools/regenerate-design-control-baseline <new-candidate-directory>
```

The command renders both appearances, prints pinned and candidate SHA-256 values, and never modifies
the checked-in resources. Accepting a change requires reviewing both candidate images, replacing both
resources as one reviewable commit, and updating the expected hashes in `DulcetCapture` and
`tools/verify_design_captures.py`. **Any accepted control change creates a new calibration baseline:
every previously recorded score must be re-run with two fresh raters. Scores from before and after the
control change must never be compared, averaged, or presented as a trend.**

### 1.4 No macOS Dynamic Type capture

**OBSERVED 2026-08-21 ([Apple SwiftUI API reference](https://developer.apple.com/documentation/swiftui/environmentvalues/dynamictypesize)):**
`EnvironmentValues.dynamicTypeSize` does not affect text size on macOS. The earlier `accessibility5`
set injected that environment value and then labeled the output as scaled even when the rendered
bytes were unchanged. Those files were not evidence and are no longer emitted. The capture executable
rejects `--dynamic-type` on macOS, and the artifact verifier rejects a byte-identical
standard/treatment pair before accepting an evidence set. CI first builds and locates the capture
binary under normal fail-fast behavior, then invokes that binary and requires exit code 1, the exact
unsupported-Dynamic-Type diagnostic, and no output directory. A setup or compilation failure cannot
satisfy the negative control. A future macOS text-resizing claim requires a platform-applicable
mechanism and visibly changed rendered evidence first.

## 2. What can and cannot be concluded

Every evaluation claim must be marked `OBSERVED` or `ASSUMED`.

`OBSERVED` from the artifact:

- pixels, dimensions, appearance, visible copy, hierarchy, spacing, density, truncation, and state
  differentiation;
- that the named JPEGs and bad controls exist;
- manifest fields, checksums, and byte identity between the two CI render runs;
- actual AppKit window-frame size and zero-origin theme-frame capture bounds for the 14 current-run
  reference JPEGs, plus reviewed baseline geometry and a pinned-resource provenance declaration for
  the two controls;
- the explicitly rendered key control state. This is not a claim that the hosted command-line process
  became the desktop's active application;
- visible focus and contrast cues;
- the standard AppKit window frame and chrome surrounding both reference and control content.

`OBSERVED` from CI or source evidence, but not from pixels alone:

- Swift package tests, app build success, capture-verifier negative controls;
- sampled rendered-pixel [WCAG 2.2 contrast ratios](https://www.w3.org/WAI/WCAG22/Techniques/general/G18)
  for every entry in the explicit `DulcetRegisteredContrastPair` registry in Aqua and Dark Aqua. A
  SwiftUI preference records which registry entries the seven fixture views reach; one test requires
  that observed set to equal the registry's `allCases`, and another tests each registered foreground
  and declared background stack. A source policy rejects direct `.foregroundStyle` and
  `.foregroundColor` calls unless they are the registry applier or carry one of the fixed decorative/
  bad-control waiver reasons; its negative control injects both an uncataloged foreground and an
  unapproved waiver and requires rejection. The probe renders each registered ordered background
  stack through AppKit, samples the composited pixels, and computes luminance after alpha compositing.
  The former `Mac + Server` tag pair is retained as an instrument negative control: native purple over
  its 11% tint measures below 4.5:1 in both appearances and must be rejected;
- **Contrast automation limit:** this is a registry and direct-modifier source policy, not exhaustive
  coverage of every pair rendered by SwiftUI/AppKit. Native `Button`, `Link`, `Table`, `TextField`,
  `GroupBox`, selection, focus, hover, disabled, and tint-driven control styling can introduce or
  override foreground/background pairs outside the registry. Those native-control surfaces remain
  visible in the artifact and subject to the §5 evaluation rubric, but the automated gate makes no
  exhaustive WCAG claim for them;
- explicit accessibility labels attached to controls in the SwiftUI source;
- semantic fonts, content-sized rows, and native focusable controls.

`ASSUMED` unless a separate interaction trace is supplied:

- VoiceOver announcement order and wording on a running built app;
- Tab traversal order and focus restoration after dismissing a surface;
- media-key behavior;
- search debouncing, cancellation, replacement-in-place, and stable scroll ordering;
- real connectivity, TLS evaluation, cached metadata, or playback.

A screenshot description must never promote an interaction or network behavior to `OBSERVED`.

## 3. Two-rater protocol

A valid rating run requires two independent external evaluation models from different model families.
Two samples from the same model are not two raters.

Both raters receive the same immutable inputs:

- the complete `run-a` artifact, including manifests and controls;
- this document;
- no score, prose, or hidden chain of reasoning from the other rater;
- no implementation author's self-rating.

Each rater starts in a fresh context and returns the schema in §7. Results are revealed together only
after both are complete. A malformed or incomplete response is replaced before either valid score is
used. The replacement sees the original inputs, not the failed response.

If the two product taste scores differ by more than 20 points, or the two accessibility scores differ
by more than 15 points, a third independent model adjudicates the disputed categories. The third
rater must also pass the control gate. The final report keeps all raw scores and states which rater
supplied the adjudicated category; it never hides disagreement behind an average.

## 4. Taste rubric — 100 points, scored per standard image

Accessibility is not folded into this score. A visually fashionable screen can score well here and
still fail §5.

| Category | Points | What earns the points |
|---|---:|---|
| Native macOS idiom | 25 | Source-list navigation, platform controls, Mac-appropriate density and information architecture; no ported web-page feel. |
| Hierarchy and typography | 20 | One legible primary hierarchy, coherent semantic type scale, restrained metadata, readable long and Unicode content. |
| Spacing and density | 20 | Deliberate rhythm, aligned regions, useful density without crowding, stable multi-disc grouping and track columns. |
| State clarity and primary action | 20 | The state is identifiable without its filename; limitations and remedies are explicit; the most important available action is obvious. |
| Coherence and finish | 15 | Light/dark parity, consistent artwork treatment, aligned controls, intentional empty space, and no visibly unfinished edge. |

These categories form two independently calibrated lenses:

- `platform_idiom`: Native macOS idiom (25 points);
- `information_design`: Hierarchy and typography, spacing and density, state clarity and primary
  action, and coherence and finish (75 points).

An earlier synthetic good/bad calibration pair produced an `information_design` gap of 1.21 on a
normalized 0–10 scale but only a `platform_idiom` gap of 0.43. Neither synthetic image contained real
window chrome. Therefore that experiment calibrated only information design; platform idiom remains
**unproven until the titled-window artifact defined in §1 passes §6**. The synthetic result must not
be quoted as a Dulcet product score.

A lens is calibrated only when, for every rater and appearance, its normalized good-browse score is
at least 1.0 point above its deliberately bad control score. If either lens is uncalibrated, the run
is `VOID`: that lens score, the 100-point taste score, and `product_taste_score` are all unreported
(`null`), not averaged or presented with a caveat. Category findings may still be retained as
diagnostics, but they are not scores.

Score every reference JPEG separately, then compute:

1. the mean of light and dark for each state;
2. the mean of the seven state means as `product_taste_score`;
3. the light/dark delta for each state.

A light/dark delta above 12 points is a named inconsistency, even if the average is high.

### 4.1 State-specific questions

- **Empty/no account:** Is the restraint intentional, and is connecting the unmistakable next step?
- **Browse:** Does real density survive the 300-track album, Unicode, missing album, several album
  artists, and the very long title without looking like demo data?
- **Multi-disc detail:** Are disc boundaries and track-number restarts immediately legible?
- **Now playing:** Do transport, progress, identity, output, and queue read in the right order?
- **Mixed-source search:** Can local, server, and replacement/refreshed results be distinguished
  without badges overwhelming result relevance?
- **TLS untrusted:** Does the limitation read as a safe, specific explanation and remedy rather than
  a stack trace? There must be no bypass action.
- **Offline metadata-only:** Is it clear that browsing works while playback does not, without making
  cached metadata look broken or unavailable?

## 5. Accessibility rubric — separate 100-point score

This score uses both standard appearances. A rater must cite the evidence type for each finding.
The current artifact provides no macOS text-scaling evidence, so the text-scaling category and the
aggregate accessibility score are `null`; neither may be inferred from standard-size pixels.

| Category | Points | Contract |
|---|---:|---|
| Contrast | 30 | WCAG AA: 4.5:1 normal text, 3:1 large text, and 3:1 meaningful non-text boundaries or focus cues. Inspect both appearances. |
| Text scaling and reflow | 25 | Unscored until Dulcet implements and captures a platform-applicable macOS text-resizing mechanism. |
| Non-color communication | 15 | Selection, source, error, offline state, and unavailable playback use labels or symbols in addition to color. |
| Labels and roles | 15 | Source evidence assigns a concise VoiceOver label to every control and uses native control roles; pixels alone cannot earn full credit. |
| Keyboard and focus | 15 | Native controls are traversable, primary actions have expected shortcuts, and focus has a visible cue; behavior stays `ASSUMED` without an interaction trace. |

Report `accessibility_score` independently only when every category has evidence. The design does not
pass accessibility while the score is unreportable, when it is below 90, when any essential text or
action is clipped, when any control lacks a label, or when WCAG AA contrast has a confirmed failure.

## 6. Lens calibration and negative-control gate — failure voids the run

Each rater scores each pinned bad-control appearance using the same taste rubric as the corresponding
good `library-browse` image. The bad controls are excluded from the product average. A rating run must
record the two pinned control SHA-256 values; only runs with the same two values share a baseline and
may be compared.

For **each rater** and **each appearance**, all five conditions must hold:

1. bad-control score is at most 40/100;
2. bad-control score is at least 25 points below the good `library-browse` score;
3. bad control is the rater's lowest-scoring image for that appearance.
4. normalized `information_design` gap between good browse and bad control is at least 1.0/10;
5. normalized `platform_idiom` gap between good browse and bad control is at least 1.0/10.

If any condition fails, the entire two-rater run is void. Do not average it, selectively discard a
rater, relax the threshold, prompt the same rater to lower the control, or report a score from the
uncalibrated lens. Record the failed lens and gap, leave lens and aggregate scores `null`, and start a
new run with two fresh independent raters. A rating harness that cannot produce a decisively low
score is not measuring the intended range. The real titled-window captures are the first evidence
eligible to settle the currently unproven platform-idiom calibration.

## 7. Required rater output

Each rater returns JSON plus a concise evidence narrative. The JSON shape is:

```json
{
  "rater": {"model_family": "...", "model": "..."},
  "control_gate": {
    "light": {
      "score": 0,
      "browse_delta": 0,
      "lowest": true,
      "information_design_gap_0_to_10": 0,
      "platform_idiom_gap_0_to_10": 0,
      "pass": true
    },
    "dark": {
      "score": 0,
      "browse_delta": 0,
      "lowest": true,
      "information_design_gap_0_to_10": 0,
      "platform_idiom_gap_0_to_10": 0,
      "pass": true
    },
    "run_valid": true
  },
  "states": {
    "empty-library-no-account": {
      "light": {
        "taste": 0,
        "categories": {
          "platform_idiom": 0,
          "hierarchy_and_typography": 0,
          "spacing_and_density": 0,
          "state_clarity_and_primary_action": 0,
          "coherence_and_finish": 0
        },
        "findings": []
      },
      "dark": {
        "taste": 0,
        "categories": {
          "platform_idiom": 0,
          "hierarchy_and_typography": 0,
          "spacing_and_density": 0,
          "state_clarity_and_primary_action": 0,
          "coherence_and_finish": 0
        },
        "findings": []
      }
    }
  },
  "lens_scores": {"platform_idiom": 0, "information_design": 0},
  "product_taste_score": 0,
  "accessibility": {
    "contrast": 0,
    "text_scaling_and_reflow": 0,
    "non_color_communication": 0,
    "labels_and_roles": 0,
    "keyboard_and_focus": 0,
    "score": 0,
    "findings": []
  },
  "weakest_reference_state": "...",
  "strongest_reference_state": "...",
  "claims": [
    {"status": "OBSERVED", "claim": "...", "evidence": "filename or CI check"},
    {"status": "ASSUMED", "claim": "...", "evidence_needed": "interaction trace"}
  ]
}
```

All seven state keys and all five category scores are required for a valid run. Point-weighted
category scores and aggregate scores are integers; normalized control gaps may be decimals. For a
void run, `lens_scores`, every per-image `taste`, and `product_taste_score` are `null`; the calibration
gaps and failure evidence remain populated. Findings name the file and the rubric category; generic
praise or criticism without evidence is invalid.

## 8. Reproduction

The hosted job is authoritative. The same native-only steps can be run on a compatible Mac without
invoking the Kotlin build:

```sh
swift test --package-path apple/DulcetKit
swift run --package-path apple/DulcetKit DulcetCapture \
  --output "$RUNNER_TEMP/dulcet-design/run-a/standard" \
  --state all --appearance all --include-control
python3 tools/verify_design_captures.py "$RUNNER_TEMP/dulcet-design/run-a"
```

The output directories must not already exist. The harness refuses to merge old and new evidence.
