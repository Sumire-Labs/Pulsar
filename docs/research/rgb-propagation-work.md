# SCALAR isolation follow-up and RGB propagation work

This follows `2258d6e` on `feat/rgb-engine`. The previous four-run comparison
reported a 6.7% increase in the median SCALAR block-placement mean. The follow-up
separates executable changes from fresh-JVM timing variation before accepting
further RGB optimizations. LGPL-3.0, configuration and cache/protocol versions
remain unchanged.

## SCALAR investigation

The comparison uses the exact previous artifacts: `sky.jar` from `0a291d9` and
`white.jar` from `2258d6e`. ZIP-entry inspection finds 147 byte-identical class
files, including scalar propagation, scheduling, contextual sampling and the
RGB Mixin-selection plugin. `Pulsar.class` and `Reference.class` differ only in
version constants. Existing refmap mappings remain unchanged. The added palette
Mixin is rejected in SCALAR, including its fields and setter hooks; this is also
checked in the native game rather than inferred only from source.

A separate diagnostic launch exports five transformed targets: the chunk-state
container, chunk, block-state implementation, world and server world. Class
structure, fields, constant-pool positions, bytecode instructions and attributes
match. Raw hashes differ only because Mixin writes a per-launch session UUID and
shifts generated handler identifiers when an additional Mixin is listed. The
comparison permits only those UTF-8 string changes, verifies a one-to-one rename,
and otherwise requires exact equality. No filter-cache fields appear in either
SCALAR palette class. Both diagnostic worlds pass the unchanged light checks.
The earlier diagnostic-helper launch could not run against the old artifact
because its `RgbFilterPalette` type did not exist there; it is excluded, and both
final exports instead use ordinary Lightbench without diagnostic helper jars.

The new cohort runs eight fresh JVMs per artifact, with 10,000 warm-up pairs and
5,000 measured pairs per workload. This increases both counts fivefold relative
to the preceding cohort. Order alternates ABBA and BAAB blocks to balance which
artifact runs first. The game, JVM flags, pool sizing, worlds, non-engine mods,
options and Lightbench verification remain fixed. Every edit and both checked
light volumes must pass. No compilation, tests or archive creation overlap timed
runs. GC observations and slow runs are retained.

The independent unit is a JVM, not an edit. Analysis reports all run means,
ranges, adjacent-pair log ratios, paired confidence intervals and exact sign-flip
tests. Intervals describe this small controlled cohort; they do not prove
identical timing for all machines or workloads. Worker CPU totals include the
entire measurement interval, including pool idle spinning, rather than just BFS.

| SCALAR workload | Before (µs) | After (µs) | Median change |
|---|---:|---:|---:|
| Sky opening | 709.74 (696.73–917.34) | 703.06 (677.86–716.35) | -0.94% |
| Sky closing | 747.73 (731.35–931.73) | 741.66 (719.92–751.89) | -0.81% |
| Block placement | 83.54 (80.07–150.48) | 83.75 (76.87–86.10) | +0.26% |
| Block removal | 79.28 (75.49–132.50) | 79.60 (73.72–80.95) | +0.41% |

The previous +6.7% block-placement median increase does not reproduce: the new
median difference is +0.26%, while removal is +0.41% and sky is slightly faster.
The old artifact itself has slow runs (block placement 130.6 and 150.5 µs), far
beyond the candidate's 76.9–86.1 µs range. These runs were retained. Their elevated
p50 and both half-run means show an entire slower execution, not only one isolated
pause. All sixteen measured intervals recorded zero GC. Worker CPU medians are
7,648.4 ms before and 7,578.1 ms after, with no extra CPU-work signal.

The result supports run-to-run JVM/system execution variation rather than added
SCALAR engine work as the explanation for the earlier figure. It does not isolate
CPU placement, clocks, OS scheduling or JIT behavior individually. No SCALAR fix
is warranted by these observations. Exact equality is not established: the paired
95% log-t interval for block placement spans approximately −27.9% to +6.7%, owing
to the slow baseline runs. The sign-flip test gives p=0.164, and bootstrap results
are also supplied rather than selecting whichever interval looks most favorable.
A small regression cannot be excluded by timing alone; bytecode and native Mixin
isolation provide the independent structural check.

## RGB work reduction

- Resolve only the emission component requested by a color lane. Zero-emission
  states return zero without a color-map lookup; emitting states retain the same
  normalized chromaticity and integer rounding.
- Source-face checks read existing light information without computing an
  emission value that is immediately discarded.
- An already opaque face skips the filter-map lookup: a bounded 0–15 filter
  cannot raise absorption above an existing value of at least 15.
- Direct-sky columns reuse the same block-state lookup for contextual light
  information and the color-filter check.

These edits stay in RGB engines and color/filter helpers. Scalar propagators,
scheduler and contextual sampling are unchanged. The existing world-thread Mod
callbacks, fluid handling and sided attenuation remain in place. This does not
merge the three component BFS traversals; filtered sky and RGB block lighting
still have that larger structural cost.

## Validation and timing

An exhaustive unit check compares single-component scaling against packed RGB
scaling for all 4,096 colors, 16 light levels and three components (196,608
comparisons). The full build and all 213 JUnit tests passed. The new candidate
retains 96 byte-identical scalar-light, scheduler, core and Mixin class files
relative to `2258d6e`. Native reference tests cover color/filter changes, boundaries,
missing sections, range supersession, cache reload and migration, Vanilla and
Celeritas rendering, SCALAR isolation and the Thaumcraft/CQR regressions.

The first Celeritas boundary audit failed because its client snapshot was absent
at the probe. The helper re-sent a full chunk on every failed probe; vanilla
`ChunkProviderClient.loadChunk` constructs a replacement Chunk, so each retry
could discard the previous snapshot immediately before the separate RGB message
was installed. The revised helper sends once per boundary scene and passively
polls delivery, retaining exactly the same RGB `E00`, canonical/stored sky 14
and real-roof geometry requirements. It records every probe, client/server
revisions and chunk identities, and requires exactly two forced boundary sends.
Both renderer modes recorded the same pattern: after the second full send,
the snapshot was temporarily absent, then the next passive probe received
server revision 3 and the exact expected values on the same replacement Chunk.
Both old helper source/results and the revised helper are retained in the raw
archive. Production code and the frozen candidate jar were not changed for this
fixture repair. The final audits use the revised helper in both renderer modes.

The short-protocol cohort uses four fresh JVMs per artifact/fixture, with
2,000 warm-up and 1,000 measured pairs per workload. Values are medians of JVM
means in microseconds; parentheses retain the full range of means.

| RGB fixture / workload | Before | After | Median change |
|---|---:|---:|---:|
| Unfiltered / Sky opening | 759.0 (744.2–814.5) | 783.0 (761.8–970.5) | +3.2% |
| Unfiltered / Sky closing | 797.6 (781.5–836.0) | 824.3 (807.0–1054.7) | +3.3% |
| Unfiltered / Block placement | 315.3 (289.5–330.4) | 280.3 (270.0–294.4) | -11.1% |
| Unfiltered / Block removal | 267.9 (252.7–278.4) | 253.6 (246.1–265.0) | -5.3% |
| Full-color fallback / Sky opening | 3240.6 (3176.6–3336.0) | 3114.2 (3042.3–3343.7) | -3.9% |
| Full-color fallback / Sky closing | 3383.5 (3324.7–3519.6) | 3304.7 (3214.9–3466.8) | -2.3% |
| Full-color fallback / Block placement | 311.7 (304.1–321.0) | 277.2 (270.3–294.3) | -11.1% |
| Full-color fallback / Block removal | 287.3 (282.5–289.9) | 265.1 (263.4–277.4) | -7.7% |

Both fixtures show lower block-placement/removal medians. Full-color fallback
sky medians are modestly lower, but unfiltered sky increased by approximately 3%. This
unfiltered path does not run the edited component propagators; its canonical
propagator and white-publication code are unchanged. That observation alone
does not dismiss an indirect JIT/execution effect. A separate eight-JVM cohort
therefore repeats the unfiltered fixture with fivefold warm-up and measurement
counts, rather than claiming all workloads improved from the short cohort.

The longer-protocol follow-up uses four fresh JVMs per artifact, 10,000
warm-up and 5,000 measured pairs per workload. These results remain separate
from the short protocol.

| Unfiltered RGB workload | Before (µs) | After (µs) | Median change |
|---|---:|---:|---:|
| Sky opening | 804.3 (743.4–1361.8) | 771.7 (718.8–1140.1) | -4.1% |
| Sky closing | 836.8 (784.6–1381.0) | 813.9 (772.0–1144.4) | -2.7% |
| Block placement | 343.9 (301.7–413.6) | 272.6 (253.5–398.3) | -20.7% |
| Block removal | 309.0 (268.5–384.5) | 259.2 (243.1–392.7) | -16.1% |

The short-protocol Sky increase did not reproduce: longer-protocol medians
instead decreased by approximately 4.1% for opening and 2.7% for closing. Both
versions contain slow executions, including the last adjacent pair, and ranges
are broad. The paired 95% log-t intervals span zero (opening approximately
−21.8% to +10.3%). No persistent unfiltered-Sky regression is established, and
no general unfiltered-Sky speedup is claimed. All observations are retained.

Block placement improved in all four longer-protocol adjacent pairs and in both
short-protocol fixtures. Short-protocol median reductions were 11.1% without the
filter and 11.1% with full-color fallback. The longer-protocol median reduction
was 20.7%, but its magnitude is influenced by slow baseline executions; the
paired geometric reduction was 15.4% with a wide interval. The supported claim
is reduced redundant work and a repeated placement improvement in these
fixtures, rather than a precise universal percentage. Removal is less consistent:
its longer-protocol last pair was 2.1% slower, despite lower aggregate medians.

All forty formal measured intervals (sixteen SCALAR, sixteen short RGB, eight
long RGB) recorded zero GC. All per-edit and initial/final volume checks passed.
The timing variation therefore cannot be explained as measured GC pauses alone.
The component traversals remain separate; packed RGB queues and sharing block
information across traversals are future structural optimizations.

See the companion [evidence index](rgb-propagation-work/README.md) for exact
artifacts, validation output, all per-edit samples and replay scripts. No FPS,
whole-game heap or general filtered-path speedup is inferred from these timings.
