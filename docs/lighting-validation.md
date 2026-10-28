# Lighting validation

## Scope

The fork-inspired changes retain Pulsar's world-owned snapshots. No dimension-global
tile-light map or off-thread TileEntity access was added.

Automated probes use released mod classes with minimal world fixtures. They exercise
actual light getters, production Mixins and engine propagation, but do not constitute
a complete modpack startup or an in-game visual inspection.

| Component | Pinned test version | Coverage |
| --- | --- | --- |
| CBMultipart | 1.12.2-2.6.2.83 | Material colors, mixed parts, polling policy, scalar/RGB propagation, removal and reload |
| ProjectRed | 1.12.2-4.9.3.118 | Fixture/button/material dye colors, power/inversion, NBT state restoration, real scalar brightness |
| IC2 Experimental | 2.8.222-ex112 | Red Wheat growth-dependent emission through the real block/tile getters |
| IC2 Classic | 1.5.15 for 1.12.2 | Red Wheat emission, isolated from Experimental's overlapping classes |
| Ender IO | 1.12.2-5.3.72 | Electric light and invisible light-node state changes |
| Celeritas | User-supplied 1.12.2 jar | Optional renderer probes and diagnostic Mixin application |

Industrial probes check off/on/off transitions, sample reload and worker-thread safety.
Lighting persistence probes publish into an untouched neighbouring chunk, serialize and
restore its NBT, and verify that a later publication survives vanilla's dirty reset.
Failure probes cover initial failure, retention of a good sample, retry backoff, recovery,
unload, and visibility while failed samples are excluded from valid disk caches.

## Diagnostics

Set `debug.traceLightUpdates=true` in `config/pulsar.cfg`. Every 200 client ticks,
the normal game log receives exact queue/render/schedule/completed-build counts,
aggregate successful Celeritas build time, and top sampled origins/positions.
Origins and positions are sampled at 1/64, not an exhaustive event history.
Position keys include the event kind and dimension; Celeritas section positions use
`Integer.MIN_VALUE` for unknown dimension. Their coordinates are section coordinates,
while queue/render coordinates are block coordinates. A render request records its
minimum corner, not every block in its bounds.

Maps retain at most 64 origins and 1024 positions plus overflow buckets per window.
Disabled recording does not collect stacks or positions. The feature writes through
the normal logger rather than creating an unbounded dedicated trace file.

## Empty-section experiment

The shortcut is **off by default**. Enable it with the JVM argument
`-Dpulsar.light.emptySectionFastPath=true` to bypass state decoding for empty,
ordinary `ExtendedBlockStorage` instances. Custom storage implementations retain
their getter. Fluidlogged sampling remains separate and is not skipped.

Run the accessor comparison with:

```text
gradlew test --tests "*EmptySectionBenchmarkTest" -PpulsarEmptySectionBenchmark=true
```

Each variant runs in three fresh JVMs, with five warm-up batches and the median of
nine batches of 4,096,000 reads. Checksums and a subsequent block insertion are checked.
Observed median ranges on this development machine:

| Storage | Existing accessor | Shortcut |
| --- | ---: | ---: |
| Empty | 6.63–6.69 ms | 0.060–0.096 ms |
| Half populated | 6.76–8.13 ms | 6.80–6.98 ms |

These are accessor microbenchmarks. The empty case is particularly easy for the JIT
to optimize; these numbers are **not** whole-engine speedups or FPS improvements.
Keep the default unchanged until representative world-generation and edit workloads
show a repeatable benefit. No comparative total-heap measurement has been made.

## Commands

```text
gradlew build
gradlew test -PpulsarGpuTests=true
gradlew test -PceleritasTestClasspath=/absolute/path/to/celeritas.jar
```

The renderer jar is remapped to development names for native probes. A disposable
test copy omits unused GL access-widener metadata to work around Unimined's namespace
assumption; the user's original jar is not changed. No optional
test dependency is bundled into the Pulsar jar. On Windows installations affected by
Unix-domain socket failures in DOS-short temporary paths, set `JAVA_TOOL_OPTIONS` to
`-Djdk.net.unixdomain.tmpdir=` followed by an existing, short ASCII directory.

Before release, visually compare MAXIMUM with the existing default in daylight,
at night, indoors, and behind colored glass, and exercise actual world save/reload
with the intended modpack. The default blend mode has not changed.
