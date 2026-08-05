# Pulsar Lighting Engine

Pulsar is an asynchronous lighting engine for Minecraft 1.12.2, built for
[Cleanroom](https://github.com/CleanroomMC/Cleanroom). It replaces
vanilla block and sky lighting with a Starlight-inspired implementation that
moves most light propagation off the server thread.

> [!WARNING]
> Pulsar is experimental. Back up your world before adding it to an existing
> modpack.

## What Pulsar does

Pulsar is designed to reduce server-thread stalls when lighting has a lot of
work to do, such as during chunk generation, explosions, large building-tool
operations, or machines that place and remove many blocks.

- Runs server-side block and sky light propagation on dedicated worker threads.
- Batches large groups of block changes instead of lighting each block
  separately.
- Saves completed light data with each chunk, avoiding a full relight every
  time the chunk loads.
- Writes normal Minecraft light data, so worlds are not locked to Pulsar.
- Fixes several vanilla rendering bugs around slabs, stairs, and light-emitting
  blocks, including [MC-92](https://bugs.mojang.com/browse/MC-92), on
  Minecraft's standard terrain-rendering path.

The largest gains appear under heavy lighting load. At lighter loads, Pulsar's
main benefit is lower light-update latency rather than higher TPS.

## Installation

Pulsar requires:

- [Cleanroom](https://github.com/CleanroomMC/Cleanroom) 0.5.15 or newer

Existing worlds are supported. Their chunks will be relit once so Pulsar can
create its own light cache; make a backup before the first launch.

## Compatibility

Pulsar should work with most biome, cave, world-generation, and dimension mods
that use Minecraft's normal chunk and world APIs. Mods that replace the light
engine are not compatible.

### Supported integrations

- [Fluidlogged API](https://modrinth.com/mod/fluidlogged-api)
- [Depths Update](https://modrinth.com/mod/depths-update), including dimensions
  that extend below Y=0 or above Y=255

### Incompatible or unsupported

- [Alfheim](https://www.curseforge.com/minecraft/mc-mods/alfheim-lighting-engine)
- Phosphor for Forge
- Hesperus
- Any other mod that replaces or rewrites the lighting engine
- The standard edition of The Aether II, which bundles Phosphor; use
  [The Aether II: Phosphor Not Included](https://www.curseforge.com/minecraft/mc-mods/the-aether-ii-phosphor-not-included)
  instead
- CubicChunks, which uses a different world-storage model

OptiFine is untested and not recommended with Pulsar.

## Performance

Pulsar mainly targets server-thread stalls when many blocks change at once. Its
world-generation gains are smaller because terrain generation usually dominates
the overall chunk-generation time. At low edit rates, vanilla, Alfheim, and
Pulsar may all remain within the 50 ms tick budget, but their light-convergence
latency differs: vanilla can take noticeably longer after a demanding
single-block change and falls behind Pulsar much sooner as the edit rate rises.

In a fresh-chunk generation benchmark on the test system, Pulsar completed the
10,404-chunk workload in a median of 48.831 seconds, compared with 56.461
seconds for vanilla. That is 1.16x the throughput and 13.5% less elapsed time.
Alfheim completed it in 49.615 seconds; its measured range overlaps Pulsar's,
so the two should be considered broadly similar in this workload.

![Fresh chunk generation benchmark](docs/benchmarks/2026-08-05-chunk-generation.svg)

| Engine | Median total | Range | Median chunks/s | Throughput vs vanilla |
|---|---:|---:|---:|---:|
| Vanilla | 56.461 s | 55.639–58.628 s | 184.3 | 1.00x |
| Alfheim | 49.615 s | 48.600–51.478 s | 209.7 | 1.14x |
| **Pulsar** | **48.831 s** | **48.102–49.035 s** | **213.1** | **1.16x** |

These figures do not mean 16% more FPS or TPS. This test measures the total time
to generate fresh chunks and wait for all lighting to finish, so terrain
generation is included. It is not a pure light-propagation benchmark or a
simulation of ordinary gameplay.

<details>
<summary>Benchmark setup and individual runs</summary>

The benchmark was recorded on 2026-08-05 using
[Lightbench 1.0.0](https://github.com/Sumire-Labs/lightbench) in generation
mode. Each engine was tested three times in a fresh world, in the interleaved
order `Vanilla, Pulsar, Alfheim`, repeated three times.

- Minecraft 1.12.2 with Cleanroom 0.6.8-alpha
- Azul Java 25.0.3 on Windows 11, with an 8 GB heap
- AMD Ryzen AI Max+ 395, 32 logical processors
- Fixed seed `20260805`, Overworld, default terrain, structures enabled
- Warm-up: a 101x101-chunk region centred at chunk `-10000, -10000`
- Measured work: 36 separate 17x17-chunk regions, totalling 10,404 chunks
- At most five chunks generated per batch, followed by a lighting-completion
  barrier after every batch
- Preflight checked each target plus a one-chunk border: all 23,605 checked
  chunks were ungenerated before every run

Lightbench's strict comparison accepted all nine result files and verified that
their benchmark plan, seed, dimension, runtime, world settings, configuration,
and non-engine mods matched. Red Core 0.7.1 was present only for Alfheim 1.6,
which requires it; the Pulsar runs used Pulsar 0.1.0.

| Run | Engine | Total | Chunks/s | Batch p99 | Region p95 |
|---|---|---:|---:|---:|---:|
| V1 | Vanilla | 58.628 s | 177.5 | 66.436 ms | 2.084 s |
| V2 | Vanilla | 55.639 s | 187.0 | 61.383 ms | 1.976 s |
| V3 | Vanilla | 56.461 s | 184.3 | 60.503 ms | 2.007 s |
| A1 | Alfheim | 51.478 s | 202.1 | 47.187 ms | 1.767 s |
| A2 | Alfheim | 49.615 s | 209.7 | 45.836 ms | 1.701 s |
| A3 | Alfheim | 48.600 s | 214.1 | 41.701 ms | 1.566 s |
| P1 | Pulsar | 48.831 s | 213.1 | 42.263 ms | 1.642 s |
| P2 | Pulsar | 48.102 s | 216.3 | 44.101 ms | 1.567 s |
| P3 | Pulsar | 49.035 s | 212.2 | 45.082 ms | 1.555 s |

The median batch p99 was 44.101 ms for Pulsar and 61.383 ms for vanilla. Batch
and region percentiles describe this benchmark's work units, not Minecraft tick
times. Exact per-run nanosecond values are available in the
[comparison CSV](docs/benchmarks/2026-08-05-lightbench.csv).

</details>

## Trade-offs

Pulsar moves work away from the server thread, but that comes with costs:

- Server-side light updates are asynchronous. Code that changes a block and
  immediately reads its light in the same call stack may briefly see the
  previous value.
- Pulsar keeps additional light data for loaded chunks and stores a light cache
  in the world save, increasing memory and disk usage.
- Heavy lighting workloads can use additional CPU cores. Performance on
  CPU-constrained systems has not yet been benchmarked.
- Pulsar is newer and has seen less modpack testing than
  [Alfheim](https://www.curseforge.com/minecraft/mc-mods/alfheim-lighting-engine).

## Troubleshooting

If an area has incorrect light, an operator can queue a relight with:

- `/pulsar relight` for the current chunk
- `/pulsar relight <radius>` for nearby loaded chunks, up to a radius of 16
- `/pulsar relight <chunkX> <chunkZ>` for a specific loaded chunk

`/pulsar stats` reports whether light work is pending. `/pulsarc` prints a
client-side light diagnostic that is useful when filing a bug report.

Please attach `latest.log`, the relevant configuration files, and the output of
`/pulsarc` when reporting a lighting problem.

## Credits

- [Starlight](https://github.com/PaperMC/Starlight) by Spottedleaf, for the
  architecture and core algorithms
- [ScalableLux](https://github.com/RelativityMC/ScalableLux) by RelativityMC,
  for later Starlight fixes and implementation references
- [SuperNova](https://github.com/GTNewHorizons/SuperNova) by GTNewHorizons, the
  1.7.10 Starlight port on which early Pulsar versions were based
- [Alfheim](https://github.com/Red-Studio-Ragnarok/Alfheim) by Red Studio, for
  the MC-92 family of rendering fixes
- [CleanroomModTemplate](https://github.com/CleanroomMC/CleanroomModTemplate) by
  CleanroomMC

## AI disclosure

Some code in Pulsar was developed with assistance from generative AI and
reviewed before inclusion. Please report any problems you find.

## License

Pulsar is licensed under the [LGPL-3.0](LICENSE.md).
