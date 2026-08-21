# Dulcet macOS design evaluation

This document defines the reproducible evidence and scoring contract for Dulcet's native macOS
fixture UI. It evaluates design quality; it does not claim that the network, cache, playback engine,
or account flow is implemented. The views are driven by an in-repository deterministic fixture so a
later data source can replace it without changing the visual layer.

## 1. Evidence contract

The `apple-ci` job produces the artifact named `dulcet-macos-design-captures-<run>-<attempt>`. Its
`run-a` directory is the evidence set. The job renders a second complete `run-b` directory and
requires a recursive byte comparison before uploading `run-a`.

Every image is a compressed JPEG at exactly 1180 × 760 pixels. The render environment is fixed:

- macOS runner image and Swift toolchain selected by `apple-ci`;
- light or dark appearance set explicitly rather than inherited from the runner;
- `en_US_POSIX` locale, Gregorian calendar, UTC time zone;
- fixture clock `2026-08-21T14:32:00Z`;
- system semantic fonts at either standard or accessibility-5 text size;
- no network-backed data source, downloaded artwork, audio decode, or animation;
- procedural artwork generated from stable fixture identifiers;
- JPEG compression factor 0.72.

Each capture directory includes `manifest.json` with the complete filename set, environment values,
byte lengths, and SHA-256 digests. `tools/verify_design_captures.py` rejects a missing, extra,
renamed, oversized, wrong-dimension, non-JPEG, or hash-mismatched image. The verifier also requires
the deliberately bad control. `tools/test-design-capture-gates` proves those checks reject a missing
control and a mutated JPEG.

### 1.1 Standard set: 16 JPEGs

Each reference state appears in light and dark:

1. `empty-library-no-account`
2. `library-browse`
3. `album-detail-multi-disc`
4. `now-playing`
5. `search-mixed-sources`
6. `error-tls-untrusted`
7. `offline-metadata-only`

The filenames are `macos-<state>-<appearance>.jpg`.

The two negative-control files are:

- `macos-CONTROL-DELIBERATELY-BAD-library-browse-light.jpg`
- `macos-CONTROL-DELIBERATELY-BAD-library-browse-dark.jpg`

They intentionally contain broken spacing, a clashing accent, inconsistent typography, poor
contrast, arbitrary card styling, and mismatched hierarchy. They are calibration evidence, never a
product design.

### 1.2 Accessibility-size set: seven JPEGs

Every reference state also appears once in light appearance at accessibility-5 text size. Filenames
end in `-light-accessibility5.jpg`. These images exist to expose clipping, fixed-height rows, lost
actions, and layouts that preserve attractive proportions by hiding content.

Scrolling is allowed. Essential content being below the fixed viewport is not truncation when the
surface remains scrollable; clipped text, ellipsized essential actions, overlapping content, and
unreachable regions are failures.

## 2. What can and cannot be concluded

Every evaluation claim must be marked `OBSERVED` or `ASSUMED`.

`OBSERVED` from the artifact:

- pixels, dimensions, appearance, visible copy, hierarchy, spacing, density, truncation, and state
  differentiation;
- that the named JPEGs and bad controls exist;
- manifest fields, checksums, and byte identity between the two CI render runs;
- visible accessibility-size layout and visible focus/contrast cues.

`OBSERVED` from CI or source evidence, but not from pixels alone:

- Swift package tests, app build success, capture-verifier negative controls;
- explicit accessibility labels attached to controls in the SwiftUI source;
- semantic fonts, content-sized scalable-text rows, and native focusable controls.

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

This score uses both standard appearances and all seven accessibility-5 captures. A rater must cite
the evidence type for each finding.

| Category | Points | Contract |
|---|---:|---|
| Contrast | 30 | WCAG AA: 4.5:1 normal text, 3:1 large text, and 3:1 meaningful non-text boundaries or focus cues. Inspect both appearances. |
| Text scaling and reflow | 25 | Accessibility-5 preserves every essential title, reason, action, source, playback state, and offline limitation without overlap or clipping. |
| Non-color communication | 15 | Selection, source, error, offline state, and unavailable playback use labels or symbols in addition to color. |
| Labels and roles | 15 | Source evidence assigns a concise VoiceOver label to every control and uses native control roles; pixels alone cannot earn full credit. |
| Keyboard and focus | 15 | Native controls are traversable, primary actions have expected shortcuts, and focus has a visible cue; behavior stays `ASSUMED` without an interaction trace. |

Report `accessibility_score` independently. The design does not pass accessibility when this score is
below 90, any essential text or action is clipped, any control lacks a label, or WCAG AA contrast has
a confirmed failure.

## 6. Negative-control gate — failure voids the run

Each rater scores each bad-control appearance using the same taste rubric as the corresponding good
`library-browse` image. The bad controls are excluded from the product average.

For **each rater** and **each appearance**, all three conditions must hold:

1. bad-control score is at most 40/100;
2. bad-control score is at least 25 points below the good `library-browse` score;
3. bad control is the rater's lowest-scoring image for that appearance.

If any condition fails, the entire two-rater run is void. Do not average it, selectively discard a
rater, relax the threshold, or prompt the same rater to lower the control. Start a new run with two
fresh independent raters. A rating harness that cannot produce a decisively low score is not measuring
the intended range.

## 7. Required rater output

Each rater returns JSON plus a concise evidence narrative. The JSON shape is:

```json
{
  "rater": {"model_family": "...", "model": "..."},
  "control_gate": {
    "light": {"score": 0, "browse_delta": 0, "lowest": true, "pass": true},
    "dark": {"score": 0, "browse_delta": 0, "lowest": true, "pass": true},
    "run_valid": true
  },
  "states": {
    "empty-library-no-account": {
      "light": {"taste": 0, "findings": []},
      "dark": {"taste": 0, "findings": []}
    }
  },
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

All seven state keys are required. Scores are integers. Findings name the file and the rubric category;
generic praise or criticism without evidence is invalid.

## 8. Reproduction

The hosted job is authoritative. The same native-only steps can be run on a compatible Mac without
invoking the Kotlin build:

```sh
swift test --package-path apple/DulcetKit
swift run --package-path apple/DulcetKit DulcetCapture \
  --output "$RUNNER_TEMP/dulcet-design/run-a/standard" \
  --state all --appearance all --include-control
swift run --package-path apple/DulcetKit DulcetCapture \
  --output "$RUNNER_TEMP/dulcet-design/run-a/accessibility5" \
  --state all --appearance light --dynamic-type accessibility5
python3 tools/verify_design_captures.py "$RUNNER_TEMP/dulcet-design/run-a"
```

The output directories must not already exist. The harness refuses to merge old and new evidence.
