# E2EE live comparison — preparation checkpoint

## Status

Actual comparison requested by the user. Use fresh fixed-seed worlds; no existing
E2EE installation or saved world is to be modified. Server-side measurement is
the first phase; client FPS and Nothirium RGB rendering are not covered by it.

**No live measurements have been taken yet.** Explicit Minecraft EULA consent is
pending. `benchmarks/e2ee-20261029/server-template/eula.txt` remains `eula=false`.
The controller refuses to launch without an explicit local consent record.

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

## Next steps after consent

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
6. Publish raw observations, medians/ranges and limitations. Whole-game memory
   equivalence to Alfheim remains unproven until these steps complete.

The driver uses graceful shutdown with a bounded fallback and records process exit
status. It operates only on newly created directories beneath its benchmark root.
The default pack's Nothirium renderer and client FPS require a later client phase.
