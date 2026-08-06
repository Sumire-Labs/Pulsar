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

The largest gains appear under heavy lighting load. At lighter loads, Pulsar's
main benefit is lower light-update latency rather than higher TPS.

### Issues fixed in Pulsar

- [MC-92](https://bugs.mojang.com/browse/MC-92)
- [MC-3329](https://bugs.mojang.com/browse/MC-3329)
- [MC-80966](https://bugs.mojang.com/browse/MC-80966)
- [MC-104532](https://bugs.mojang.com/browse/MC-104532)
- [MC-116690](https://bugs.mojang.com/browse/MC-116690)
- [MC-117067](https://bugs.mojang.com/browse/MC-117067)
- [MC-117094](https://bugs.mojang.com/browse/MC-117094)
- [MC-249343](https://bugs.mojang.com/browse/MC-249343)

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

Pulsar mainly targets server-thread stalls when many blocks change at once or a
skylight edit spans a tall column. Vanilla is often quick enough for an ordinary
isolated block-light edit, but that does not make the three engines equivalent:
their light-convergence latency diverges sharply as the affected area grows.

### Light updates

In a controlled Lightbench test, each block edit ran by itself and was followed
by the engine-specific lighting-completion barrier. The timer started
immediately before `setBlockState` and stopped only after server-side lighting
finished. Lightbench then verified the fixed stored-light probes after every
sample, outside the timed interval.

![Light-update completion benchmark](docs/benchmarks/2026-08-06-light-updates.svg)

Each cell below is the median of three independent run p50s; parentheses show
the range of those run p50s. Times are milliseconds and lower is better.

| Edit and resulting light change | Vanilla | Alfheim | **Pulsar** | Vanilla / Pulsar |
|---|---:|---:|---:|---:|
| Open roof column (skylight increases) | 45.541 (38.084–46.356) | 6.635 (6.368–6.716) | **0.808 (0.760–0.816)** | **56.39x** |
| Close roof column (skylight decreases) | 847.284 (762.866–848.995) | 14.768 (13.749–14.978) | **1.541 (1.468–1.972)** | **550.01x** |
| Place glowstone (block light increases) | 2.028 (1.767–2.092) | 0.200 (0.193–0.215) | **0.083 (0.082–0.098)** | **24.43x** |
| Remove glowstone (block light decreases) | 2.820 (2.510–2.880) | 0.303 (0.291–0.329) | **0.097 (0.097–0.126)** | **28.98x** |

The largest ratio came from closing the roof column: vanilla took 847.284 ms,
Alfheim 14.768 ms, and Pulsar 1.541 ms at the median p50. This is deliberately
a demanding skylight workload, with a roof at Y=254 above a floor at Y=3. The
550.01x ratio describes that one light-completion workload; it is not a claim of
550x more FPS, TPS, or overall game speed.

<details>
<summary>Light-update benchmark setup and individual runs</summary>

The benchmark was recorded on 2026-08-06 using
[Lightbench 1.0.0](https://github.com/Sumire-Labs/lightbench) in update mode.
The same controlled Superflat world was used for nine separate Minecraft
launches, in the interleaved order `Vanilla, Pulsar, Alfheim`, repeated three
times. All nine runs in the final series were retained.

- Minecraft 1.12.2 with Cleanroom 0.6.8-alpha, on an integrated server
- Azul Java 25.0.3 on Windows 11, with an 8 GB heap
- AMD Ryzen AI Max+ 395, 32 logical processors
- Fixed seed `20260805`, Overworld, grass floor at Y=3
- Controlled 64x64 stone roof at Y=254, with a 16-block minimum sample margin
- Skylight workload: remove and replace one roof block, opening and closing the
  column to the sky
- Block-light workload: place and remove one glowstone block at Y=4
- Warm-up: 20 edit pairs per workload; measured work: 200 samples per phase
- The same block was reused within a run, with a completion barrier and
  correctness check after every edit

Lightbench's strict comparison accepted all nine result files. It checked the
fixed protocol, every raw sample, per-edit light-probe correctness, benchmark
plan, seed, dimension, runtime, world settings, controlled preflight, config
fingerprint, and non-engine mods. Red Core 0.7.1 was installed only for Alfheim
1.6, which requires it, and was the only explicitly excluded dependency when
comparing mod lists.

| Run | Engine | Open roof | Close roof | Place glowstone | Remove glowstone |
|---|---|---:|---:|---:|---:|
| V1 | Vanilla | 45.541 | 847.284 | 2.028 | 2.820 |
| V2 | Vanilla | 46.356 | 848.995 | 2.092 | 2.880 |
| V3 | Vanilla | 38.084 | 762.866 | 1.767 | 2.510 |
| A1 | Alfheim | 6.635 | 14.978 | 0.193 | 0.291 |
| A2 | Alfheim | 6.716 | 14.768 | 0.200 | 0.303 |
| A3 | Alfheim | 6.368 | 13.749 | 0.215 | 0.329 |
| P1 | Pulsar | 0.808 | 1.541 | 0.082 | 0.097 |
| P2 | Pulsar | 0.816 | 1.972 | 0.098 | 0.126 |
| P3 | Pulsar | 0.760 | 1.468 | 0.083 | 0.097 |

The 200 samples within each phase are repeated hot measurements at one
position; the Minecraft restart is the independent comparison unit. Aggregate
medians use Lightbench's nearest-rank definition. The engine order was
interleaved but not rotated, so run-order and thermal effects remain a
limitation; the full per-run range is shown rather than a confidence interval.
Exact per-run p50, p95, p99, maximum, submission, barrier, GC, and Pulsar
worker-CPU values are in the [comparison CSV](docs/benchmarks/2026-08-06-light-updates.csv).

</details>

### Chunk generation

World-generation gains are smaller because terrain generation usually dominates
the overall chunk-generation time.

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
  architecture and core algorithms on which Pulsar is based

- [SuperNova](https://github.com/GTNewHorizons/SuperNova) by GTNewHorizons, a
  Minecraft 1.7.10 port of Starlight that served as the basis for early
  versions of Pulsar

- [Alfheim](https://github.com/Red-Studio-Ragnarok/Alfheim) by Red Studio, as a
  reference for directional neighbour brightness, liquid and emitter rendering,
  and light-only chunk packet handling

- [CleanroomModTemplate](https://github.com/CleanroomMC/CleanroomModTemplate) by
  CleanroomMC

## AI disclosure

Some code in Pulsar was developed with assistance from generative AI and
reviewed before inclusion. Please report any problems you find.

## License

Pulsar is licensed under the [LGPL-3.0](LICENSE.md).
