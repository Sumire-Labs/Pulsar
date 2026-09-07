# Pulsar 0.3.0 benchmark record — 7 September 2026 (JST)

The six formal light-update runs passed Lightbench's strict comparison. Pulsar
0.3.0 had lower median completion latency than Alfheim 1.6 in all four tested
workloads. Glowstone placement had a higher p95 and p99 with Pulsar, as retained
in the detailed data. The compact chart shows p50 and the range of run p50s.

![Light-update completion](light-updates.svg)

| Workload | Alfheim p50, ms (run range) | Pulsar p50, ms (run range) | Alfheim / Pulsar | Alfheim p95, ms | Pulsar p95, ms |
|---|---:|---:|---:|---:|---:|
| Open roof column | 9.674 (9.508–9.712) | 0.621 (0.597–0.638) | 15.58x | 10.082 | 1.127 |
| Close roof column | 15.822 (15.766–15.883) | 0.617 (0.606–0.624) | 25.66x | 16.478 | 1.322 |
| Place glowstone | 0.167 (0.157–0.175) | 0.072 (0.072–0.077) | 2.31x | 0.177 | 0.220 |
| Remove glowstone | 0.272 (0.259–0.278) | 0.077 (0.076–0.080) | 3.54x | 0.289 | 0.101 |

Each percentile column is the median of the three independent JVM-run
percentiles. Ratios use the unrounded median p50s. Ranges are the full range of
run p50s, not confidence intervals. These are completion times for specific
repeated edits, not FPS, TPS, general gameplay speed, or isolated algorithm cost.
The experiment does not attribute the difference to Starlight's algorithm,
threading, queue design, or any single Pulsar optimization.

## Historical Vanilla reference

The previously published Vanilla results are retained at the user's request,
separately from the new six-run series. They used Lightbench 1.0.0 with sparse
probes, Cleanroom 0.6.8-alpha, Azul Java 25.0.3, and seed `20260805`. They were
not remeasured for 0.3.0 and did not pass through the current validation
protocol. No historical Vanilla/current Pulsar or Alfheim ratio is reported.

| Update workload | Median of run p50s, ms | Full run-p50 range, ms |
|---|---:|---:|
| Open roof column | 45.541 | 38.084–46.356 |
| Close roof column | 847.284 | 762.866–848.995 |
| Place glowstone | 2.028 | 1.768–2.092 |
| Remove glowstone | 2.820 | 2.510–2.880 |

Updates were recorded August 6, 2026 (JST), with three JVM runs per engine.
Values above are recomputed from the Vanilla rows of the
[original update CSV](../2026-08-06-light-updates.csv). The historical Vanilla
generation total was 56.461 s for 10,404 chunks, with a three-run range of
55.639–58.628 s, from the [original generation CSV](../2026-08-05-lightbench.csv).
The generation protocol also differs from the current schema-3 pilots.
The historical update values appear on the same chart as `Vanilla*`, with an
explicit note about their different protocol and environment. Sharing an axis
does not establish comparability. They remain excluded from the current
summary CSV, strict comparison report, and raw-update dataset.

## Method and environment

- Minecraft 1.12.2, Cleanroom 0.6.12-alpha, integrated server on Windows 11.
- AMD Ryzen AI MAX+ 395, 32 logical processors; Eclipse Adoptium Java
  25.0.4.1+1-LTS, G1, `-Xms8192m -Xmx8192m -XX:+UseCompactObjectHeaders`.
- Lightbench **1.0.6-completion**, update schema 2, `bounded-volume-v2`.
- Alfheim **1.6 release JAR**, Pulsar **0.3.0** built from `b5dece9` with the
  release version and changelog changes. JAR hashes are in
  [artifact-manifest.json](artifact-manifest.json).
- Common mods: Cleanroom Relauncher 1.1.2, Fugue 0.24.3, Scalar 3.8.4-1,
  Scala 3.8.4 and parser combinators 2.4.0, Red Core 0.7.1, and the same
  Lightbench JAR. Red Core was installed in **both** engines; no extra
  dependency was excluded from comparison. Engine IDs `pulsar` and `alfheim`
  alone are excluded from non-engine mod equality.
- Each launch used a fresh copy of the same pristine Superflat template,
  seed `1`, peaceful, no structures, floor Y=3. Render distance 2, 60 FPS
  limit, VSync off. Identical frozen configuration was staged before each run.
- Controlled 64×64 stone roof at Y=254, edit position (20008,254,20008).
  Sky tests remove/replace one roof block. Block tests place/remove glowstone
  at Y=4. Warmup: 20 edit pairs per workload; measurement: 200 edits per phase.
- Prespecified final launch order: **A, P, P, A, A, P**. All six launches
  are retained, with one full Minecraft/JVM restart per run. Three launches
  per engine are a small descriptive sample, not a significance test. Order
  was not randomized and thermal/background-process effects remain possible.

Timing starts immediately before `World.setBlockState` and ends after the
engine-specific server-light completion barrier. Alfheim's deferred queue is
drained explicitly. Pulsar waits for the relevant future and verifies global
queued/in-flight work while holding both queue monitors. Cached-light reads do
not themselves flush the engine. Timing only submission would omit deferred
work, so submission and barrier values are diagnostic columns only.

After every measured sky edit, outside timing, the harness checks all 242,172
cells in a 31×31×252 volume. Block edits check BLOCK and unchanged SKY in
16,337 cells (31×31×17). The analytical expectation is cross-checked against
an independent flood-fill oracle in harness tests. This establishes correctness
for these fixtures; it does not establish correctness for every mod or world.
The scans warm affected data, so the result describes hot repeated updates.

The comparator validates the protocol, raw sample intervals and percentiles,
per-edit validation markers, preflight, seed, runtime, world settings,
configuration fingerprint, and non-engine JARs. It cannot independently rerun
the light scans from a JSON file; those scans happen in-game. Separate CPU
replays are retained as diagnostics and are not added to the latency samples.
The two CSV phase rows for each replay repeat the same CPU window and must not
be summed twice.

Pulsar's 41 tests and the final harness's 50 tests passed before the formal
series; the harness build also passed formatting and build checks. The original
Prism comparison instance's mods, configuration, options, and worlds were
restored from backups after measurement.

## Why there is no Vanilla update score

Vanilla was attempted and failed the stronger volume checks. On the pristine
fixture, preflight found SKY=1 where the closed-roof oracle expected 0 at
(19993,16,19993). A separate diagnostic using an already prepared roof passed
initial checks and warmup, then failed the first measured sky increase:
(19994,7,20008) expected SKY=1, observed 0.

An audit found that the earlier harness treated Vanilla completion as entirely
inline and omitted its normal deferred sky-gap ticks. Version 1.0.6 therefore
times and drains pending `Chunk.onTick(false)` gap maintenance on already loaded
chunks, with a bounded quiescence check. It does not force light values or
readiness flags. Both Vanilla failures persisted with that correction.

There is no completed, validated Vanilla update report and no defensible new
Vanilla/Pulsar ratio from this protocol. This result alone does not identify
whether the remaining discrepancy is an engine limitation or an unresolved
fixture/completion assumption. The historical August schema-1 benchmark used
sparse light probes; its numbers and ratios are not reused as 0.3.0 results.

The six formal A/P runs all use the same frozen v1.0.6 JAR and the pristine
template. Earlier v1.0.3 successful pilots and one completed A run were excluded
because the harness changed, before the new final series was specified. A
v1.0.3 P launch was stopped at the main menu before world load. All attempts
remain in the local research archive. The prepared roof was used only to
diagnose Vanilla, never in the six formal runs.

## Why there is no new chunk-generation ranking

Fresh generation pilots completed once per engine, using generation schema 3.
All 30,493 target-and-border chunks were ungenerated at preflight. The plan
generates an 11,025-chunk warmup, then 36 regions of 441 generated chunks each
(15,876 including halos), with 10,404 measured core chunks. Generation of the
halo, batch barriers, and bounded ordinary chunk maintenance are timed. This
includes terrain generation and is not a pure lighting benchmark.

Core terrain and normalized raw-light hashes were collected after timing.
Strict comparison rejected the pilots because the outputs differ:

| Pair | Terrain-mismatched chunks | Light-mismatched chunks | Light mismatches with identical terrain |
|---|---:|---:|---:|
| Vanilla / Alfheim | 598 | 3,155 | 2,745 |
| Vanilla / Pulsar | 1,019 | 3,680 | 2,978 |
| Alfheim / Pulsar | 904 | 1,456 | 1,109 |

Each pair covers 10,404 matching chunk coordinates. These are chunk counts,
not differing cell counts. No engine is declared correct or incorrect from
hash differences alone. Because even the terrain differs, a lighting-only
interpretation would be unsafe. The same-terrain/light-different subset also
needs investigation. No timing ranking is published and repeated speed runs
were not continued after the parity gate failed.

The generation-only disposable-world hook suppressed subsequent chunk saving
**after** each completed raw report, outside timing. It did not skip generation,
maintenance, or validation. The full in-memory generated terrain was not saved;
per-chunk hashes and raw measurements were retained. Reproducing individual
cell differences requires a diagnostic run that saves those chunks.

## Data and reproduction

- [Run-level CSV](runs.csv), [chart summary CSV](summary.csv),
  [validated comparison with all phases and CPU observations](validated-comparison.md).
- [Six untouched update JSON files](raw-updates/), [final run plan](plan-final.json),
  [frozen measurement configuration and mod manifests](measurement-config.zip).
- [Generation raw JSON archive](generation-diagnostics.zip),
  [parity counts](generation-parity.json),
  [strict comparison rejection](generation-comparison-rejected.txt).
- [Vanilla update failure logs](vanilla-update-failures.zip).
- [Lightbench source snapshot](lightbench-source.zip),
  [patch against local base e5084fe](lightbench-v106.patch).
  This is a local validation build, not a claim that upstream Lightbench has
  released that version. Build with Java 25 and the included Gradle wrapper;
  the initial build needs its dependencies. The snapshot includes the
  repository's license. README and one Javadoc correction were made after
  recording; executable measurement code was unchanged. The manifest records
  the exact JAR used, rather than promising a byte-identical rebuild.
  Refer to the protocol above for this run; older
  instructions in the harness README describe historical protocols.
- [Chart reproduction script](plot.py): run with Python and Matplotlib
  (`python plot.py`); it reads `summary.csv` and the historical Vanilla rows
  from `../2026-08-06-light-updates.csv`. The rendering used Matplotlib 3.11.1.
- [Modrinth / CurseForge replacement text](listing-text.md).

Re-run updates in fresh, equivalent Superflat worlds with the exact recorded
mods/configuration using `/lightbench updates alfheim` or
`/lightbench updates pulsar`, restart Minecraft between runs, and run the
snapshot's `BenchmarkCompare` CLI against the resulting JSON files. Preserve
failures, and do not combine schema/harness versions or substitute Vanilla
timings that did not pass validation.

The generated comparison document has one editorial correction to its generic
Vanilla barrier sentence, which was stale in the report formatter. The six
update JSONs, CSV values, and comparator acceptance are unchanged. No public
listing, Git commit, or upstream push was made while preparing these files.
