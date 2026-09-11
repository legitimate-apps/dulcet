# Can Dulcet avoid re-reading a library that has not changed?

**Measured 2026-09-11 against a disposable Navidrome 0.63.2.** Every claim below is marked
**OBSERVED** (with the request that produced it and the response that came back) or **ASSUMED**.

Re-run the measurements with:

```sh
tools/measure-change-detection \
  --base-url http://127.0.0.1:4561 \
  --music-folder "$ROOT/music" \
  --ffmpeg /opt/homebrew/bin/ffmpeg \
  --json-out change-detection.json
```

Its control is `tools/test-measure-change-detection`.

## The hypothesis under test

> `getScanStatus.lastScan` is a sufficient gate for the expensive catalog walk. If `lastScan` has
> not moved since the generation we committed, the catalog cannot have changed, so the open costs
> one request instead of dozens.

**The verdict is: usable, with two named exceptions and one hard limit.** `lastScan` moved for
every ordinary content change put to it, and the two counter-examples found are both narrow and
one of them self-heals. The hard limit is not a counter-example at all — it is that `lastScan`
says nothing whatever about starred items, playlists or play counts, so "unchanged library ⇒ zero
requests" is false no matter how good the gate is.

## Environment

**OBSERVED.** Navidrome `0.63.2 (be10f89c)`, darwin/arm64, loopback on port 4561, own music root
and data folder, seeded by `tools/seed-corpus` (314 media files, 10 folders, 8 albums, 4 artists),
bootstrapped by `tools/conformance-env/health-check` — which reported
`CONFORMANCE PRECONDITIONS PASS evidence_lines=10`. ffmpeg 9.0.1, matching
`tools/conformance-env/read-pin ffmpeg.darwin.version`.

Two facts about the configuration shape everything below, both read from the server's own startup
log:

```
level=info msg="Periodic scan is DISABLED"
level=info msg="Watcher started for library" libraryID=1 name="Music Library"
```

**OBSERVED:** the filesystem watcher is on and the periodic scan is off, which is the default
posture. So content changes are picked up *without any client action*, and nothing else moves the
scan stamp on its own.

## M1 — does it move on a real content change?

**OBSERVED. Yes, in all three cases, and within about 5.5 s of the change, with no `startScan`
call from the client.** The corpus was mutated on disk and `getScanStatus` polled until it
settled.

| mutation | `lastScan` moved | detected by the watcher alone | `count` | `folderCount` | visible in `search3` / `getAlbumList2` |
|---|---|---|---|---|---|
| add one FLAC in a new album folder | **yes** | yes, 5.56 s | 314 → 315 | 10 → 11 | yes, both |
| delete that file and its folder | **yes** | yes, 5.55 s | 315 → 314 | 11 → 10 | gone from both |
| rewrite tags in place (ffmpeg `-c copy`, new `title`) | **yes** | yes, 5.55 s | **314 → 314** | **10 → 10** | new title yes, old title gone |

Raw, for the add:

```
BEFORE  {"count": 314, "folderCount": 10, "lastScan": "2026-09-11T10:58:30.002638-04:00",
         "scanType": "quick", "scanning": false}
AFTER   {"count": 315, "folderCount": 11, "lastScan": "2026-09-11T10:59:26.56092-04:00",
         "scanType": "quick-selective", "scanning": false}
song row {"album": "Change Detection Probe A", "artist": "Probe Ensemble", "duration": 3,
          "id": "2qomacFQNh81ZhZwitbRwX", "suffix": "flac", "title": "M1A New Track"}
```

🚨 **The retag row is the load-bearing one.** `count` and `folderCount` are *identical* either side
of a change that is plainly visible in `search3`. **OBSERVED: neither can substitute for the
timestamp**, and a gate built on "the album count is the same" would have missed this outright.
They can corroborate; they cannot decide.

## M2 — does it move on a no-op scan?

**OBSERVED. Yes.** `startScan` against an untouched corpus advanced the stamp anyway:

```
BEFORE     lastScan 2026-09-11T10:57:53.425425-04:00  count 314  scanType full
startScan  -> {"scanning": true, "count": 0, "folderCount": 0, ...}
AFTER      lastScan 2026-09-11T10:58:30.002638-04:00  count 314  scanType quick
```

**So `lastScan` means "when I last finished a scan", not "when the library last changed."** The
design must say so, because it sets how often the client walks for nothing:

- **OBSERVED:** a server restart with `ScanOnStartup = true` — the packaged default and the
  conformance posture — moves the stamp with **zero** content change. Measured: a clean stop and
  start took `2026-09-11T11:03:00.384376-04:00` to `2026-09-11T11:04:17.47971-04:00` over an
  untouched corpus.
- **ASSUMED:** on a server with the periodic scan *enabled* (this fixture has it off), every
  scheduled scan costs the client one needless walk. The cadence is then the user's
  `Scanner.Schedule`, not anything Dulcet controls.

This is the conservative direction — a needless walk, never a missed change — so it is a cost
question, not a correctness one. But it means the honest claim is "we skip the walk when the
server has not scanned", not "when the library has not changed".

## M3 — the user-state boundary

**OBSERVED. None of the three move `lastScan`, and all three had a real measured effect.** A
12-second quiet window was used after each write — more than twice the measured 5.5 s watcher
latency — so "did not move" is not "has not moved yet".

| action | `lastScan` moved | effect proved by |
|---|---|---|
| `star` (`albumId=2I7ZgUnMXaSmjutz6yf3Ln`) | **no** | `getStarred2` → 1 starred album |
| `createPlaylist` (`name=M3 Probe Playlist`) | **no** | `getPlaylists` → `['M3 Probe Playlist']` |
| `scrobble` (`submission=true`) | **no** | `getSong` → `playCount` 0 → 1 |

All three left `lastScan` at `2026-09-11T11:00:28.599878-04:00` and `count` at 314.

🚨 **This is the hard limit, and it is not a defect — it is the correct behaviour.** User state is
not catalog state, and the scanner does not touch it. The consequence is binding on the design:

> **An unchanged library does not cost zero requests.** Starred items, playlists and play counts
> are invisible to every scan-based gate and must be re-read on every open regardless. Any design
> note claiming "unchanged library ⇒ one request" is wrong; the correct claim is "unchanged library
> ⇒ the gate plus the user-state reads, and no catalog walk".

## M4 — shape and stability

**Wire format — OBSERVED.**

| property | observed |
|---|---|
| type | JSON **string** |
| example | `"2026-09-11T11:04:17.47971-04:00"` |
| format | ISO-8601 with the **server's local UTC offset** — *not* `Z`, and not UTC |
| fractional precision | microseconds |
| fractional width | **variable — trailing zeros are trimmed** |
| present while `scanning: true` | yes, holding the previous completed value |
| survives a restart on the same data folder | **yes, byte for byte** |

🚨 **The fraction is not fixed width.** Across one 8-scan series the tool observed
`fractional_digit_widths: [4, 5, 6]` — e.g. `2026-09-11T11:02:59.128-04:00` (three digits) beside
`2026-09-11T11:02:59.038878-04:00` (six). **Two spellings can therefore denote one instant.**
Compare parsed instants, or compare the raw strings for *equality only*; never order raw strings,
and never assume a fixed-length field.

⚠️ **A single clean run does not refute this.** Trimming only shows when the microsecond value
happens to end in a zero, so a short series often reports one width and looks fixed: a later 8-scan
run of the same tool reported `fractional_digit_widths: [6]` and `fixed_width_fraction: true`. The
variable width is a property of the *formatter*, evidenced by the run that caught three widths —
not something a run that happened to miss it can un-observe.

**Restart survival — OBSERVED.** Stopping the server and restarting it with `ScanOnStartup=false`
returned the identical record:

```
PRE-RESTART   {"count": 314, "elapsedTime": 384376000, "folderCount": 10,
               "lastScan": "2026-09-11T11:03:00.384376-04:00", "scanType": "quick"}
POST-RESTART  {"count": 314, "elapsedTime": 384376000, "folderCount": 10,
               "lastScan": "2026-09-11T11:03:00.384376-04:00", "scanType": "quick"}
```

It is persisted as `library.last_scan_at` in the data folder. (With the *default*
`ScanOnStartup=true` the value survives the restart and is then immediately replaced by the startup
scan's own stamp — see M2.)

**Monotonicity — OBSERVED for 45 + 12 consecutive scans, but do not depend on ordering.** 45
back-to-back scans at ~30 ms spacing produced 45 distinct, strictly increasing microsecond values;
a further 12-scan series was also non-decreasing. The value is the scan's wall-clock **end** time,
so its monotonicity is exactly the server's wall clock's. **ASSUMED:** an NTP step backwards, a
timezone change, or a manual clock change moves it backwards. A gate that asks *"is it different
from what I committed?"* is immune to all of that; a gate that asks *"is it greater?"* is not.
**Use inequality.**

One false lead, recorded so it is not re-derived: `lastScan − elapsedTime` lands on an exact whole
second in 45 of 45 samples, which looks like `lastScan` being *computed* from a truncated start
time and therefore non-monotonic by construction. It is not. `elapsedTime` is measured from a
second-truncated origin — 32 consecutive scans shared the single origin second `11:02:59` while
their `elapsedTime` climbed from 0.039 s to 0.999 s — so the relation is definitional and predicts
nothing. **`elapsedTime` is not the scan's duration**; it overstates it by however far into the
second the scan began, and is unusable as a duration.

🚨 **What `getScanStatus` returns during a scan — OBSERVED, and this one is a trap.**

```
{"count": 0, "elapsedTime": 66096061000, "folderCount": 0,
 "lastScan": "<the PREVIOUS completed value>", "scanType": "<the PREVIOUS type>", "scanning": true}
```

- `count` and `folderCount` are a **live running total**: sampled through one full scan they walked
  `0 → 2 → 8 → 310 → 314` (9 distinct pairs across 4,726 in-flight samples). A client reading
  `count` mid-scan can see **0** and conclude the library is empty.
- `scanType` still reads the *previous* scan's type for the first tens of milliseconds.
- **The stamp and the `scanning` flag are not published atomically.** A dedicated trial — breaking
  on the first sample carrying a new stamp, so the loop cannot start a second scan and misread it —
  found the new stamp *while `scanning` was still `true`* in **12 of 12 trials**, and again in
  **8 of 8** through the committed tool. This is the normal ordering, not a rare race.

  The aggregate counters already read their final values at that moment (`count=314`,
  `folderCount=10` in all 12), so this is the flag lagging the stamp rather than evidence that rows
  are still being written. The client rule is cheap and mandatory anyway:

  > **Never accept a `lastScan` reading unless `scanning == false` in the same response.**

**`count` / `folderCount` as a cheap corroborating fingerprint — OBSERVED, qualified.** Usable only
when `scanning == false`, and only as corroboration: M1's retag changed the catalog with both
values identical, and M6's interrupted scan left both stale while the catalog had changed. They add
value in one direction only — *a changed count proves a change* — and none in the other.

## M5 — CONF-34: `getIndexes?ifModifiedSince`

**The registry ids disagree, and `CONF-34` is the correct one.** `docs/CONFORMANCE.md:59` registers
`CONF-34 | getIndexes?ifModifiedSince behavior and granularity`, and the design's own representative
-test table agrees at spec line 2947. Only the §16.1 *prose* (spec line 2014) still says
**CONF-31** — and that id is now taken: `docs/CONFORMANCE.md:56` and spec line 2954 both assign
`CONF-31` to *generation-pinned reads*, which is what the six shipped `FEATURES.yml` citations
actually evidence. Spec §28 revision 86 records the renumbering explicitly: *"the design's five
descriptions moved to ids in the registry's own scheme: `getIndexes?ifModifiedSince` → CONF-34"*.
So §16.1's prose is a stale survivor of that revision. **Reported, not fixed here.**

**Answering CONF-34 — all OBSERVED.**

**Does the reference server honour it? Yes.**

| `ifModifiedSince` | `index` key | artists |
|---|---|---|
| *(omitted)* | present | 4 |
| `0` | present | 4 |
| watermark − 1000 ms | present | 4 |
| watermark − 1 ms | present | 4 |
| **watermark (exactly equal)** | **absent** | — |
| watermark + 1 ms | absent | — |
| watermark + 60000 ms | absent | — |
| `abc` (not a number) | present | 4 |
| `-5` | present | 4 |
| seconds instead of milliseconds | present | 4 |

- **Unit: milliseconds since epoch.** **Predicate: strictly greater** — an exactly-equal watermark
  returns nothing, so a client may safely echo back the last value it saw.
- 🚨 **The "nothing changed" response OMITS the `index` key entirely.** It does not return an empty
  array. A parser that maps a missing key to `[]` will read "unchanged" as "this library has no
  artists" and wipe the list. The `lastModified` field is *still present and current* in that
  response, so one request returns both the verdict and the next watermark.
- **Malformed values fail open** — `abc`, `-5` and a seconds-valued timestamp all return the full
  index with no error envelope. Conservative, but a client bug costs a full response silently
  rather than erroring.

**At what granularity? Two separate answers, and the spec's assumption is wrong on one of them.**

Spec §16.1 says: *"even honored it is artist-level and cannot detect a changed track under an
unchanged artist."*

- **True of the payload.** The response carries artist rows only — no `child` or `song` key at the
  root — so it never tells you *which* track changed.
- **False of the detection.** **OBSERVED:** a track added inside an existing album by an existing
  artist, leaving the artist set at 4 → 4, still moved `lastModified` from `1789139057000` to
  `1789139143000`, and `getIndexes?ifModifiedSince=1789139057000` returned the full index again.
  `lastModified` is a **whole-library watermark**, not an artist-level one. The endpoint is a
  perfectly good change *detector*; it is simply not a change *description*.

  ➡️ **Recommend correcting §16.1.** As written it would talk a reader out of an endpoint that
  works, for a reason that is not true.

- 🚨 **The real granularity limit is time, not scope: `lastModified` is second-granular.**
  **OBSERVED:** `1789139057000` — every value ends in `000`, and it is `lastScan` truncated to the
  second (`lastScan` was `…11:04:17.47971`). Combined with the strictly-greater predicate, that is
  a hole, and M6 walked through it.

## M6 — the adversarial case

Five attacks were constructed. **Two produced counter-examples; three did not.** The failures are
recorded because "I tried X, Y and Z and could not break it" is the part that makes the design
defensible.

### ✅ Counter-example 1 — a real change `getIndexes.lastModified` cannot represent

**Pre-registered before running:** `lastModified` is second-granular, the predicate is strictly
greater, so a change whose scan lands in the **same wall-clock second** as the baseline leaves the
watermark untouched and `ifModifiedSince` reports "nothing changed".

**OBSERVED, on the first attempt** (and reproduced by the committed tool on its second):

```
{"attempt": 1, "catalog_really_changed": true, "count": "315->316",
 "lastScan": "2026-09-11T11:06:35.433134-04:00 -> 2026-09-11T11:06:35.477406-04:00",
 "lastScan_moved": true, "same_second": true,
 "lastModified": "1789139195000 -> 1789139195000", "lastModified_moved": false,
 "ifModifiedSince_says_changed": false}
```

`count` went 315 → 316 and the new track was retrievable by title through `search3` — the catalog
demonstrably changed — while `getIndexes?ifModifiedSince=1789139195000` answered *nothing changed*.
`lastScan`, at microsecond resolution, caught it.

➡️ **This decides the choice of field.** `getScanStatus.lastScan` is the gate;
`getIndexes.lastModified` is not. They are not interchangeable, and the cheaper-looking one is the
broken one.

### ✅ Counter-example 2 — a scan interrupted partway

Navidrome commits `media_file` rows incrementally and writes `library.last_scan_at` only at the
end. **OBSERVED:** 220 new tracks were moved into the music folder, a full scan started, and the
server was `SIGKILL`ed 350 ms in. On restart with `ScanOnStartup=false`:

```
BEFORE  {"count": 316, "folderCount": 11, "lastScan": "2026-09-11T11:06:40.481899-04:00"}
AFTER   {"count": 316, "folderCount": 11, "lastScan": "2026-09-11T11:06:40.481899-04:00"}
lastScan MOVED : False        count delta: 0
staged tracks retrievable via search3 (sampled 11 of 220): 6
```

**Both cheap signals reported "nothing changed" while 100+ new tracks were live and queryable.**
This needs nothing exotic — a container restart, an OOM kill, or a power cut during a scan.

**But it is bounded, and the bound matters.** **OBSERVED** in the server's log 2 s after the next
startup, and *despite* `ScanOnStartup=false`:

```
level=warning msg="Resuming interrupted scan"
level=info msg="Scanner: Interrupted full scan detected" lib="Music Library"
level=info msg="Scan completed"
```

Navidrome detects the interrupted scan and finishes it. The exposure is therefore:

1. **Bounded in time** — from server-ready to resume-complete, **~3 s** in this measurement
   (11:09:15 ready → 11:09:18 scan completed), scaling with the resume scan's length.
2. **Self-correcting** — after the resume, `lastScan` advanced to
   `2026-09-11T11:09:18.008169-04:00`, which differs from the pre-interruption watermark a client
   would be holding, so the client re-walks on its **next** open.
3. **Residual risk:** a client that opens *inside* that window reads a torn catalog and believes it
   fresh, **for that session only**.

### ✅ (also) — a direct database edit is undetectable, permanently

**OBSERVED.** `UPDATE media_file SET title=... WHERE id='EZEog6mCrJMIKpaijKA2QG'` executed against
the running server's SQLite showed up immediately in `getSong` *and* in `search3` (FTS triggers
propagate it), and `lastScan` did not move across 42 s of polling. Unlike the interrupted scan,
**nothing ever repairs this** — no scan is pending, so the stamp never advances.

- **How realistic?** Out-of-band DB writes are not a normal user action. But the same *class* is:
  **ASSUMED — not measured here**, because `EnableExternalServices = false` in this fixture — a
  server with Last.fm/Spotify/Deezer agents enabled writes artist biographies and images into the
  catalog asynchronously, outside any scan. If those writes do not touch `last_scan_at`, artist and
  album *metadata* can change permanently without moving the gate. **This is worth a follow-up
  measurement before the design promises anything about artist/album images being fresh.**

### ❌ Not a counter-example — two rapid changes inside one granularity tick of `lastScan`

Attempted and **could not be constructed**. `lastScan` is microsecond-resolution; 45 consecutive
scans driven as fast as the transport allows landed ~30 ms apart and produced **45 distinct**
values. A collision needs two scans completing in the same microsecond. **ASSUMED** unreachable at
any achievable scan rate. (The *same* attack against `getIndexes.lastModified`, whose tick is a
full second, succeeded on the first try — see Counter-example 1. Same experiment, different field,
opposite result: the granularity is the whole story.)

### ❌ Not a counter-example — user state written through the server's own paths

`star`, `createPlaylist` and `scrobble` do not move `lastScan` (M3), but they do not change the
catalog either, so the gate is not lying. This is a **scope** limit, not a soundness one, and it is
handled by re-reading user state unconditionally.

### ❌ Not a counter-example — a tag edit that preserves both size and mtime

Constructed by rewriting the tags with ffmpeg, padding the file back to its exact original byte
length, and restoring the original mtime with `utime`. **OBSERVED:**

| scan | new title visible |
|---|---|
| `startScan` (quick), fired before the watcher could react | **no** |
| the watcher's own `quick-selective` scan ~5 s later | **no** |
| `startScan?fullScan=true` | **yes** |

`lastScan` moved for all of them. So the quick path is mtime/size-based and cannot see this edit —
but that means **the server's catalog genuinely did not change**, which is exactly what the gate
reported. The client is mirroring the server's catalog, not the disk. **The honest framing: the
gate inherits the scanner's blind spots, and cannot be better than the scanner.** It is a limit to
state, not a counter-example.

## Recommendation

### The correct open-the-library sequence

```
1. GET /rest/getScanStatus
      ├─ scanning == true?  -> do not read the stamp at all. Serve the committed generation and
      │                        re-check later. (12/12: the new stamp is first observable while
      │                        this flag is still true.)
      └─ scanning == false -> take `lastScan` as the watermark for this open.
2. Compare the watermark to the one stored with the committed generation, for INEQUALITY,
   on the parsed instant (not the raw string — the fraction is variable width).
      ├─ equal    -> skip the catalog walk entirely. Serve the committed generation.
      └─ different -> run the existing three-walk import, and store the watermark READ IN STEP 1
                      alongside the generation it produced.
3. Re-read user state unconditionally, every open, gate or no gate:
   getStarred2, getPlaylists, and play counts. lastScan cannot see any of it.
```

🚨 **Two ordering constraints, both load-bearing:**

- **Read the watermark BEFORE the walk, never after.** If a scan completes during the walk, a
  watermark captured afterwards certifies a generation that was assembled across the change, and
  the client then never re-walks. Captured before, the same event merely causes one extra walk on
  the next open — the conservative direction.
- **`scanning == false` is part of the read, not a nicety.** The stamp is published before the flag
  clears in every trial, so a client that skips the check will routinely bank a stamp for a scan it
  has not seen the end of.

### What it costs

Let *W* be the existing three-walk import, unchanged by this proposal. `LibrarySync` walks artists
and albums at page size 500 and tracks at 200 (`MAX_LIBRARY_ENUMERATION_PAGE_SIZE`,
`TRACK_PAGE_SIZE`), plus `getMusicFolders` and a two-request enumeration probe. **DERIVED, not
measured** — at the spec §16 target scale of ~31,500 tracks / ~2,950 albums and an assumed ~500
artists, *W* ≈ 170 requests.

Let *U* be the user-state reads: `getStarred2` + `getPlaylists`, plus whatever play-count refresh
the design settles on. *U* ≥ 2.

| case | catalog requests | total | vs today |
|---|---|---|---|
| **(a) unchanged library** | **1** (`getScanStatus`) | **1 + *U*** ≈ 3 | *W* + *U* ≈ 172 → **~3** |
| **(b) one new album** | 1 + *W* | 1 + *W* + *U* ≈ 173 | unchanged (one extra request) |
| **(c) first-ever open** | 1 + *W* | 1 + *W* + *U* ≈ 173 | unchanged (one extra request) |

The user's complaint — open the library, leave, come back, and it re-reads everything — is case (a),
and it goes from a full walk to a single request. Cases (b) and (c) pay exactly one extra request
for the gate.

**The needless-walk rate is the cost to accept**, and it is a function of the *server's* scan
behaviour, not the library's change rate (M2): every server restart with the default
`ScanOnStartup=true`, and every scheduled scan on a server with `Scanner.Schedule` set, triggers one
walk that finds nothing. That is the price of a gate that is conservative in the safe direction.

### What it cannot detect

State these in the design rather than discovering them later:

1. **All user state.** Starred items, playlists and play counts never move `lastScan`
   (**OBSERVED**, M3). They must be re-read every open. *"Unchanged library ⇒ zero requests" is
   false.*
2. **A catalog written outside the scanner.** A direct database write is permanently invisible
   (**OBSERVED**). The realistic member of this class — external metadata agents writing artist
   bios and images — is **ASSUMED, unmeasured**, and should be measured before the design promises
   fresh artist/album artwork.
3. **A catalog torn by an interrupted scan**, for the ~3 s between the server becoming ready and
   its "Resuming interrupted scan" finishing (**OBSERVED**). Self-correcting on the next open.
4. **Anything the scanner's quick path cannot see** — notably a tag edit preserving both size and
   mtime (**OBSERVED**). The client cannot be fresher than the server it mirrors.
5. **Nothing about *what* changed.** The gate is one bit. When it fires, the full walk runs;
   there is no delta. `getIndexes` does not help here either — its payload is artist-level even
   though its watermark is whole-library (**OBSERVED**, M5).

### Do not use `getIndexes?ifModifiedSince` as the gate

It looks like the purpose-built answer and it is measurably worse: a second-granular watermark with
a strictly-greater predicate **silently missed a real, verified catalog change** on the first
attempt (M6, Counter-example 1). It remains useful for what it is honest about — one request
returning both a verdict and the next watermark — but the field to gate on is
`getScanStatus.lastScan`.
