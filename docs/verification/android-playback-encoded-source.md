# Encoded envelopes and production source consumption — 2026-09-09

This closes the two findings remaining after independent re-verification of the earlier
Android repairs. Those queue, UTF-8 prolog, navigation and scrobble mutation audits were not
repeated. The new implementation is in `6b36d31`, the production-source controls in `d43f247`,
and explicit fixture BOM assertions in `0972c44`.

## Encoding boundary

Envelope inspection now identifies the byte encoding before classifying XML or JSON. It decodes
an inspection view only; the data source retains and delivers the original bytes. Detection follows
[XML 1.0 appendix F](https://www.w3.org/TR/xml/#sec-guessing), with BOMless ASCII opening code-unit
recognition also covering a direct root and encoded leading whitespace.

| Decoded encoding | BOM bytes | BOMless opening supported |
| --- | --- | --- |
| UTF-8 | EF BB BF | Yes |
| UTF-16BE | FE FF | Yes |
| UTF-16LE | FF FE | Yes |
| UTF-32BE | 00 00 FE FF | Yes |
| UTF-32LE | FF FE 00 00 | Yes |
| UCS-4, order 2143 | 00 00 FF FE | Yes |
| UCS-4, order 3412 | FE FF 00 00 | Yes |

Four-byte BOMs are considered before their two-byte prefixes. Partial markers, code units,
UTF-8 sequences and surrogate pairs preserve uncertainty. The existing 16 MiB inspection cap
remains. A recognizable encoding marker is not an unconditional rejection: impossible-document
binary must still load at a witnessed range offset.

Deliberately outside the decoder: EBCDIC conversion, UTF-7 and arbitrary legacy charset conversion.
The EBCDIC XML opening signature is recognized and held Unknown, so it fails closed rather than
being accepted as media. Other unsupported legacy encodings are not promised rejection coverage.
This is neither a universal text detector nor a declaration-driven transcoder. It also does not
turn the existing envelope inspector into a complete XML parser/entity processor.

`AndroidEncodedPlaybackDataSourceTest` has seven encodings, each with and without a BOM, and four
tests per combination: **56 test cases**. Actual emitted BOM bytes are asserted independently of
host charset encoding and the fixture's byte permutation helper.

- `encodedXmlErrorsCannotUseAnEarlierAudioWitness` first establishes a WAV witness, then opens a
  consistent octet-stream 206 at offset 100. Each combination checks a declaration plus 9000 spaces,
  a declaration-free root after 9000 spaces, and encoded leading whitespace. All produce
  `InvalidCredentials`; none can open as media.
- `encodedJsonErrorsCannotUseAnEarlierAudioWitness` rejects an oversized JSON error document in
  every encoding/BOM combination through the same production factory.
- `splitEncodingMarkersAndCodePointsRemainUnknown` inspects every byte prefix of a processing
  instruction containing BMP and supplementary Unicode characters. No prefix becomes binary proof.
- `encodingMarkersDoNotRejectImpossibleDocumentBinaryRanges` supplies encoded `{` plus impossible
  document control characters, followed by binary data. All 14 combinations open after reading
  exactly **8192 bytes**, deliver byte-identical original data, and account for the delivered bytes.

Before the decoder fix, the initial 42-case matrix reported **42 tests completed, 24 failed**.
The later all-prefix control and explicit BOM assertions were added as further discrimination.

## Real production source, not just installed metadata

`productionPlayerConsumesAuthenticatedValidatedBytes` retains real ExoPlayer construction, the
production ProgressiveMediaSource, AndroidPlaybackDataSourceFactory, AndroidHttpPlaybackResource,
authenticated authorizer and read-side accounting. A loopback receiving socket serves a 16,044-byte
PCM WAV. The test checks the selected song and signed stream query received at `/rest/stream.view`,
and waits for real player loading to produce the production read-side byte count. Final output:

```text
PRODUCTION SOURCE OBSERVED authenticated-http=true validated-bytes=16044 fixture-bytes=16044 decoder-progression-not-claimed=true
```

`productionPlayerRejectsAnHttpEnvelopeBeforeConsumingMediaBytes` sends an XML code-40 error from
the receiving socket while retaining the same production source path. It requires an authenticated
stream request, the validator's `InvalidCredentials` error at the controller, and zero counted
media bytes. This is paired with the positive test so rejecting all input cannot counterfeit success.

Metadata loading and wire-plan resolution are still substituted. No sender, HTTP resource, data
source, validator, ExoPlayer or read-side accounting callback is replaced in these two controls.
The fixture intercepts the controller's delivery handoff, so these controls do not add a new claim
about decoder-driven scrobbling; the previously verified live-consumer test remains separate.

## Executed mutations

Each product mutation was applied alone, executed against selected tests, then restored. Fresh
JUnit reports were required; a failing build with zero executed tests was not accepted.

| Mutation | Tests executed | Failures | Actual discriminator |
| --- | ---: | ---: | --- |
| Remove the decoded inspection view | 56 | 37 | Encoded envelopes/partial text escape classification; baseline UTF-8 and positive controls retain their distinct outcomes |
| Reject encoding markers wholesale | 14 | 14 | Every new positive binary-range case fails to open |
| Replace the controller factory with `DataSource.Factory { ByteArrayDataSource(byteArrayOf(1, 2, 3)) }` | 2 | 2 | Positive cannot observe validated HTTP consumption; negative observes no receiving HTTP request |
| Delete `attemptConsumed.addAndGet(bytes)` | 1 | 1 | Authenticated loading alone cannot satisfy required read-side accounting |
| Bypass validation while retaining real HTTP and accounting | 1 | 1 | Expected `InvalidCredentials`, got `UnexpectedBinary` from downstream media handling |

All five mutations were caught: **74 executed test invocations, 55 expected failures, zero errors
or skips**. The first attempt at the final mutation inferred an overly narrow Kotlin result type
and did not compile; it was excluded, corrected to the declared result interface, and then produced
the executed assertion failure shown above. Both logs are retained in local evidence.

The exact factory substitution kept `setMediaSource` and item metadata intact. No changes to those
lines were needed to make the new tests fail. Mutation diffs, Gradle logs and JUnit XML were retained.

## Final rebased validation

Main advanced from `2fbc37b` to `50c98de` during the work. The pre-rebase tip was preserved and
all 26 branch patches replayed without conflicts or patch changes. All 22 pre/post changed paths
were main-only changes and matched main byte-for-byte; there was no unexplained delta.
`apple-ci.yml` matches the new main, and `FEATURES.yml` is unchanged from the start of this repair.

The final run used an internal-disk build root resolved with `realpath`, a local Gradle init script
for output routing, configuration cache disabled, two workers, and `--rerun-tasks`. It executed:

- `:core:allMetadataJar`, `:core:bundleAndroidMainAar`, `:core:licensee`.
- `:core:jvmTest` filtered to PlaybackStreamValidationTest, ArtworkFetchTest, PlaybackWireTest and
  PlaybackWireLoadingTest, the JVM callers affected by the shared inspector.
- `:core:testAndroidHostTest` filtered to the 56 encoded-source cases, two production-player source
  cases, and the existing impossible-JSON positive binary-range control.

Output: **`BUILD SUCCESSFUL in 19s`; `44 actionable tasks: 44 executed`.**

| Selected test task | Suites | Tests | Failures | Errors | Skipped |
| --- | ---: | ---: | ---: | ---: | ---: |
| JVM | 4 | 28 | 0 | 0 | 0 |
| Android host | 3 | 59 | 0 | 0 | 0 |
| Total | 7 | 87 | 0 | 0 | 0 |

CI policy passed for 6 workflows; parity for 6 feature rows; OS-floor configuration agrees on
macOS 14.0 and iOS/tvOS 17.0. Existing compiler/fixture and unused-license-allowance warnings remain.
No warning suppression, feature promotion, emulator run, push or PR operation was performed.
Independent read-only review found no blocker in the encoding implementation or source controls.

## What could still be absent without these controls detecting it?

- The controller's production song/catalog-loading and wire-resolution calls are substituted in
  the source fixtures. Direct resolver unit tests do not establish those controller connections.
- Audio rendering, actual media-time progression, audio focus/noisy handling, background-service
  survival and TV remote/lifecycle behavior are not established by source loading. These controls
  could still pass with those behaviors absent.
- Real player seek/range sequencing is not driven. The encoded range tests directly open the
  production factory at an offset, while the new real-player controls load an initial stream.
- The real-player consumption observation is a count, not a byte-for-byte observation at decoder
  input. The direct data-source positive controls prove original-byte delivery at that lower
  boundary; corruption introduced downstream of it is not excluded by these controls.
- Negative envelope tests alone could survive removal of successful delivery; the positives guard
  that separately. Positive binary tests alone could survive removal of envelope rejection; the
  negatives guard that separately.
- Unsupported legacy encodings remain the explicit implementation boundary above.

These limits do not reopen or repeat the independently verified queue, navigation and live-scrobble
controls. They state what this repair's additional evidence does and does not establish.
