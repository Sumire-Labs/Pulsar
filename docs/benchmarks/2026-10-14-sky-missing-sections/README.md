# Block notification and missing-section skylight: rejected experiments

Two independent Pulsar changes were tested: collecting one reentrant server
`World.setBlockState` operation's lighting notifications before publishing them,
and avoiding block-state lookup during skylight increase propagation through
verified plain air in absent sections. No external engine source was copied;
Pulsar remains LGPL-3.0.

**Decision: retain the existing production implementation.** Notification
collection regressed dense-edit main-thread MSPT. The isolated skylight shortcut
did not demonstrate a repeatable update-latency improvement in the native
four-JVM cohort. Both prototypes were removed; the source patches and reports
are retained for reproduction.

## Notification collection: rejected

The collector eliminated repeated Sky/Block engine invocations in every captured
edit in the diagnostic sample. It preserved nested callbacks, pending-work
barriers, exception unwinding, direct Chunk edits and signed-height coordinates.
However, the native 2,048-edits-per-tick test increased median main-thread MSPT
from 4.195 to 5.286 ms (+26.0%, four independent JVMs per variant). The collector
was removed from production. A single instrumented JFR run per variant did not
identify long queue-monitor waits as the main explanation; it does not establish
the remaining CPU cost's cause. Do not describe the rejected prototype as an
adopted optimization.

Values are medians of independent JVM run means (minimum–maximum). Lower is better.

| Metric | Released 0.5.0 | Candidate | Change | JVMs/variant | Unit |
|---|---:|---:|---:|---:|---|
| Block placement | 103.681 (77.905–162.587) | 75.441 (70.584–87.259) | -27.2% | 4 | µs |
| Block removal | 104.800 (78.714–160.035) | 79.271 (73.914–90.345) | -24.4% | 4 | µs |
| Sky opening | 790.590 (689.176–945.709) | 707.754 (687.547–769.922) | -10.5% | 4 | µs |
| Sky closing | 798.834 (710.403–964.856) | 748.844 (721.687–807.983) | -6.3% | 4 | µs |
| Main-thread MSPT, 2,048 edits/tick | 4.195 (3.317–5.635) | 5.286 (4.593–10.164) | +26.0% | 4 | ms |
| Final light drain | 14.087 (12.217–15.063) | 15.456 (13.255–19.624) | +9.7% | 4 | ms |

These observations have substantial JVM variation, particularly in baseline
block timings. They do not establish a universal or statistically significant
speedup. A separately warmed two-JVM long update check also improved block
placement/removal, but is supplemental evidence rather than an equally
replicated claim.

The prototype passed 187 unit tests, six game callback cases per variant,
45 stored-light snapshots and terrain/light equality for 289 generated core
chunks. Diagnostics are separate from native timing trials. In the captured
samples, extra Sky/Block invocations were 217/43 for placement, 179/25 for
removal, 431/4 for sky closing and 424/20 for sky opening in the baseline;
they were zero for both lanes in every prototype phase. Counts are from a
single warmed diagnostic JVM per variant, not a production latency estimate.
Both variants captured 671 placements, 613 removals, 590 sky closings and
629 sky openings. Pending-work barriers can intentionally publish before the
outer edit returns; zero repeated invocations is not promised for every callback.

## Isolated missing-section shortcut

The second experimental candidate contained only the absent-section skylight shortcut.
After the existing destination-nibble and level guards, it bypasses
`getBlockStateFast` only if the cached air descriptor is plain, the current
default AIR is the cached state, section storage is absent, and no Fluidlogged
capability applies. Direction filtering, contextual/sided blocks, nonempty
sections, queue order, overflow handling and publication retain their existing
behavior. Neither change was adopted. Config Version 2 and lighting-cache
version 12 are unchanged.

Values are medians of independent JVM run means (minimum–maximum). Lower is better.

| Metric | Released 0.5.0 | Candidate | Change | JVMs/variant | Unit |
|---|---:|---:|---:|---:|---|
| Block placement | 78.376 (77.208–79.544) | 75.899 (74.489–77.310) | -3.2% | 2 | µs |
| Block removal | 80.408 (80.136–80.680) | 77.880 (77.023–78.738) | -3.1% | 2 | µs |
| Sky opening | 770.716 (724.163–843.920) | 799.311 (691.011–1150.075) | +3.7% | 4 | µs |
| Sky closing | 787.753 (743.052–853.215) | 830.006 (735.459–1082.275) | +5.4% | 4 | µs |
| Main-thread MSPT, 2,048 edits/tick | 4.121 (3.412–4.201) | 3.833 (3.360–4.169) | -7.0% | 4 | ms |
| Final light drain | 14.128 (13.600–18.277) | 16.334 (14.541–19.950) | +15.6% | 4 | ms |

The four-JVM sky-opening median increased by approximately 3.7% and sky-closing
by 5.4%. Run ranges overlap substantially; the candidate's last JVM was
particularly slow. These data do not prove a universal slowdown or identify its
cause, but they do not justify adoption as a demonstrated speed improvement.
No run was removed from the aggregation. The block check has two JVMs per
variant and is descriptive, not evidence of a new block-engine optimization.

All 180 production unit tests passed. Each variant passed six game callback
cases, 45 stored-light snapshots and generation checks. The 289 scored core
chunks matched in terrain and both-channel light hashes. Every selected native
update and dense-edit verification passed. The native cohort contains 26
independent JVM launches: six correctness games, eight Sky games, eight dense-edit
games and four block regression games. Fluidlogged was not installed in these
games; its fallback guard is retained but compatibility is not newly measured.

[Isolated results](isolated-results.json), [artifact hashes](isolated-artifacts.json),
[isolated raw reports and orchestration](isolated-raw-data.zip),
[combined prototype results](combined-results.json),
[prototype raw reports and source patch](combined-raw-data.zip).

The restored production build also passed all 180 tests; its source matches the
parent commit. See [validation](validation.json).

The bundles omit game saves, engine binaries, account data and full game logs.
Local orchestration paths require adaptation. See [checksums](SHA256SUMS.json).

## Measurement scope

The isolated cohort uses the same frozen Minecraft 1.12.2 / Cleanroom 0.6.12-alpha
runtime as the combined prototype: Adoptium Java 25, fixed 8 GiB G1 heap, compact
headers, Ryzen AI MAX+ 395, render distance 4, default Config Version 2 and ten
automatically selected shared workers. No shaders, OptiFine or rendering
accelerator are installed. Native Lightbench bench9 and the original completion
adapter are used; profiler and sampled ownership helpers are absent from scored
trials. Fresh copies of the same seed-1 flat world are used for every JVM.

Sky opening/closing: 2,000 warmup pairs and 1,000 measured pairs, four JVMs per
variant. Continuous editing: 2,048 edits/tick for 100 requested ticks, four JVMs
per variant. Block update regression check: 5,000 warmup and 10,000 measured
pairs, two JVMs per variant. Engine order is reversed between runs, as recorded
in the protocol. Every measured update verifies light; continuous trials must
finish all ticks without the wall cap and pass final roof/light verification.

Completion latency includes engine submission, the completion adapter and
waiting. Main-thread MSPT excludes asynchronous worker CPU; final drain is
reported separately. These tests do not measure FPS, engine-only memory,
ordinary terrain generation speed or Cleanroom 0.7.0 performance. Do not compare
this cohort numerically with an older publication to assign a causal speedup.
