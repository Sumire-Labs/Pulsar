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

<details>
<summary>Benchmark charts, setup, and results</summary>

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
