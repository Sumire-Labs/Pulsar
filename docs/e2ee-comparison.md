# E2EE live comparison

## Status

Actual comparison requested by the user. Use fresh fixed-seed worlds; no existing
E2EE installation or saved world is to be modified. Server-side measurement is
the first phase; client FPS and Nothirium RGB rendering are not covered by it.

The user explicitly accepted the Minecraft EULA on 2026-10-30 and authorized
`eula=true` and autonomous measurements. Consent is recorded locally; the
controller sets `eula=true` only in disposable run copies. Three pilots and nine
measured runs completed successfully; all comparison servers were stopped.

## Results

Full tables, ranges and limitations: [measured report](../benchmarks/e2ee-20261029/results.md).
Machine-readable observations: [summary](../benchmarks/e2ee-20261029/measured-summary.json).

The matched saved-world sample contained 625 identical terrain chunks, 22 entities
and 3,157 TileEntities in every run. Non-engine mod hashes also matched.
Median post-GC live server heap was 1,184.45 MiB (Alfheim), 1,151.93 MiB
(Pulsar SCALAR), and 1,193.08 MiB (Pulsar RGB). Thus SCALAR used about 32.52 MiB
less and RGB about 8.62 MiB more than Alfheim in this state. This is whole-server
live heap, not lighting-only retained memory or total process memory.

Median idle tick p95 was 1.41 ms / 2.50 ms / 2.43 ms respectively. Pulsar's roughly
1 ms higher tick work is reproducible here; performance parity is not established.
Its default 1 ms contextual TileEntity polling budget is a candidate for follow-up
profiling, not a proven causal attribution from this comparison.

New-generation times were 9.00 s / 9.97 s / 10.77 s for 507 submissions, but generated
terrain differed even between repeated runs of the same engine. These are pack
observations, not controlled lighting-only speedup ratios. Post-generation live heap
was 1,202.49 / 1,207.51 / 1,266.68 MiB; differing workload counts limit attribution.
The goal of RGB using no more memory than Alfheim while preserving performance is
therefore **not yet demonstrated**. Client FPS, visual checks and mature factory
workloads remain outside this server-only measurement.

## Prepared resources

- Pack: E2EE v1.87.3, downloaded from its published GitHub release.
- Official server ignore-project list and extra-file list applied: 380 downloads,
  archive CRC checks and SHA-256 provenance recorded. There are 377 top-level JARs
  before adding the benchmark harness (other downloaded entries include ZIPs).
- Cleanroom 0.6.13-alpha installed with its official installer. This is a controlled
  deviation from the server pack's 0.6.11-alpha default, shared by every variant.
- Runtime: Temurin 25.0.4.1, Ryzen AI MAX+ 395, 32 logical processors, about 95 GiB RAM.
- Engines: supplied Alfheim 1.6, Pulsar `pulsar-0.5.4-dev.50.local.jar` in SCALAR,
  and the same Pulsar JAR in RGB/RGB_MAX. Pulsar SHA-256:
  `2b972c9c8eb2173e95d9c1f2b5e61f898e9cd0cd6e3f018b2935110468b2effa`.

All prepared data is under `benchmarks/e2ee-20261029/` (large artifacts remain local).
`download-provenance.json` records exact files; `manifest.json` preserves the release
manifest. The template is never used as a measured run directory.

## Harness and controlled workload

The local Lightbench source at `f6ccb91b658a7cf3fc2878d222e6d8b5a0d7ba7d`
was copied into `tools/lightbench`, not edited in its original checkout. The copy
has version `1.1.0-bench9-e2ee1` and a documented completion patch:

- A synchronous benchmark must publish Pulsar's pending world-thread samples
  before waiting for worker completion, then check again for late samples.
- Publication is inside measured wall time. It is not performed in a tight loop
  while workers are busy. Alfheim retains its explicit deferred-light flush.
- The shared observer records loaded chunks/entities/tiles, heap and GC data,
  exact tick-work durations, and process CPU time. Observer output occurs after
  its tick measurement window.
- The copied harness passed 61 tests, including a new late-owner-sample barrier test.
  Spotless formatting checks were disabled for this temporary copy; compilation
  and tests were not skipped.

Settings: seed `20261029`, OTG/BBOP-Extended generation from the pack, view distance
12, loopback-only port 25590, identical 8 GiB heap and G1 settings. Mob spawning,
random ticks, day/weather cycling and loot/entity drops are disabled; generated
entities are removed before stable memory/tick windows. Thaumcraft's extra Pulsar
crystal emission is disabled for comparison. These controls must be disclosed.

The controller implements an unranked pilot (`lightbench gen 2 1 2`) and a measured
generation plan (`lightbench gen 4 3 4`), with 20-second idle and loaded tick windows,
post-GC class histograms/heap summaries and 0.5-second process-memory samples.
GC diagnostics occur outside performance windows. Generation wall time includes
terrain generation/population and lighting completion; it is not lighting-only CPU.

## Execution protocol

1. Record the user's explicit consent in `eula-consent.json` (`accepted: true`).
   Only then may run copies contain `eula=true`.
2. Run an Alfheim pilot, then SCALAR/RGB pilots. Inspect startup compatibility,
   active engine detection, lighting completion and generation audits.
3. Normalize generated default configs into the common template, retaining every
   engine's config in every variant. Record mode-specific config differences.
4. Run at least three fresh JVM/world repetitions per variant in alternating order.
   Retain every result and failure. Do not silently discard outliers.
5. Compare only runs with matching loaded-world/workload conditions and validated
   terrain. RGB_MAX intentionally can change gameplay light, so do not present
   differing RGB light hashes as scalar-equivalent results.
6. Publish raw observations, medians/ranges and limitations. Scope conclusions to
   this controlled server workload rather than clients or mature factories.

The driver uses graceful shutdown with a bounded fallback and records process exit
status. It operates only on newly created directories beneath its benchmark root.
The default pack's Nothirium renderer and client FPS require a later client phase.

## Live protocol update (2026-10-30)

All three fresh-world pilots completed successfully. However, their generated
terrain did not match despite the fixed seed (24/25 scored chunk hashes differed
between Alfheim and SCALAR). Therefore fresh-world generation throughput is an
observational pack measurement, not an isolated equal-work lighting comparison.

The measured series copies the exact saved `alfheim-pilot/BBOP-Extended` world
into each fresh run. `world-source.json` records every source file hash. The
source is never launched or mutated again. Common generated configs come from
the Alfheim pilot, supplemented with Pulsar's generated defaults, with
`lightingMode` changed per variant. Their baseline hashes are recorded in
`normalized-configs.json`.

Harness e2ee2 adds an idle loaded-chunk terrain audit and moves generation
centers to +/-20000 chunks so the pilot's +/-10000 areas cannot contaminate
fresh-generation timings. Updated coordinate expectations and all 61 tests pass.
Every measured run uses this same harness. The series order is A/S/R, R/A/S,
S/R/A, with a new JVM and identical source-world copy every time.

The saved-world idle sample is the primary common-terrain memory comparison.
Post-generation samples and throughput remain subject to the pack's observed
terrain variation; entity and tile counts are retained. Entity removal does not
prevent later falling-block creation, and remaining entities are reported rather
than assumed absent. Class histogram totals represent post-full-GC live heap;
they do not equal process working set or lighting-only retained memory.
