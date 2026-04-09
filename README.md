# Pulsar

A high-performance lighting engine mod for Minecraft 1.12.2, built on [Starlight](https://github.com/PaperMC/Starlight)'s BFS propagation algorithm.

> [!WARNING]
> This mod is in early development. Expect breaking changes and incomplete features.

## Overview

Pulsar replaces Minecraft’s default lighting engine with a modern BFS-based implementation derived from [Starlight](https://github.com/PaperMC/Starlight). It is intended for use with [CleanroomLoader](https://github.com/CleanroomMC/CleanroomLoader).

Bringing the power of Starlight to 1.12.2.

### What It Does

- Replaces vanilla's slow, iterative light propagation with Starlight-equivalent BFS
- Uses SWMR (Single Writer Multi Reader) nibble arrays for thread-safe concurrent lighting
- Runs light computation on dedicated worker threads with time-budgeted drain loops
- Writes results back to vanilla `blockLight`/`skyLight` nibbles — fully transparent to other mods

### What It Doesn't Do

- No RGB / colored lighting (scalar only — same visual output as vanilla)
- No custom rendering pipeline — works through vanilla `Chunk.getLightFor()`, so renderers like Celeritas read correct values automatically

## Requirements

- CleanroomLoader

## Credits

- [Starlight](https://github.com/PaperMC/Starlight) by Spottedleaf — the architecture and core algorithms (BFS propagation, SWMR nibble arrays, deferred lighting) are derived from Starlight's design.
- [SuperNova](https://github.com/AE2-UEL/SuperNova) by mitchej123 — the RGB colored lighting engine for 1.7.10. Pulsar is a scalar-only port of SuperNova's Starlight-derived BFS engine, with all RGB / color features removed. The majority of Pulsar's light engine, mixin targets and chunk-send strategy are 1:1 conversions of SuperNova's sources.
- [Hodgepodge](https://github.com/GTNewHorizons/Hodgepodge) by mitchej123 — Pulsar's `MixinChunk.pulsar$alwaysPopulated` (the chunk-send gate bypass) is the 1.12.2 equivalent of Hodgepodge's `MixinChunk_SendWithoutPopulation`, and `MixinWorldEntitySpawner` mirrors Hodgepodge's `MixinSpawnerAnimals_optimizeSpawning` "skip mob spawning in chunks with pending light" behaviour. Hodgepodge's sources were consulted directly while porting both.

- [GTNHLib](https://github.com/GTNewHorizons/GTNHLib) by the GTNH team — SuperNova's 1.7.10 build depends on GTNHLib for its annotation-driven config, coordinate utilities, blockpos backport and `gtnhmixins` declarative mixin loader. Pulsar does not link against GTNHLib (1.12.2 ships modern equivalents via Forge / mixinbooter), but the design of Pulsar's config and coremod entry point is informed by how SuperNova uses GTNHLib.
- [CleanroomModTemplate](https://github.com/CleanroomMC/CleanroomModTemplate) by the CleanroomMC team

## License
[LGPL3.0](LICENSE.md)