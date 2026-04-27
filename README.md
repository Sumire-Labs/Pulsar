# Pulsar

A high-performance lighting engine mod for Minecraft 1.12.2, built on [Starlight](https://github.com/PaperMC/Starlight)'s BFS propagation algorithm.

> [!WARNING]
> This mod is a personal hobby project and is currently in the early stages of development.
> As no precise benchmarks or tests have been carried out, we cannot guarantee its effectiveness.
> It may also corrupt your world, so we do not recommend using it unless you fully understand what it does. We accept no responsibility for any damage caused by its use.

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

## Incompatibilities

Pulsar fully replaces the vanilla lighting engine, so it conflicts with any mod that touches the same area.

### Not compatible

- **Phosphor (Forge)** — mixes into the same methods; will not work alongside Pulsar.
- **Alfheim** — Phosphor-derived, conflicts for the same reason.
- **Other lighting engine replacements** — any mod that overrides `Chunk.checkLight`, `recheckGaps`, or `enqueueRelightChecks` will likely conflict.
- **The Aether II** — bundles Phosphor and therefore conflicts. Use [The Aether II: Phosphor Not Included](https://www.curseforge.com/minecraft/mc-mods/the-aether-ii-phosphor-not-included) (a fork with Phosphor stripped) instead.

### Not recommended

- **OptiFine** — its ASM-level `Chunk` modifications can interfere with Pulsar's mixins. If you want rendering speed, consider [Celeritas](https://github.com/kappa-maintainer/Celeritas-auto-build) instead.
- **CubicChunks** — extended world heights are not supported.

## Requirements

- CleanroomLoader >= 0.5.x

## Credits

- [Claude](https://claude.com/product/claude-code) — During development, Claude's ideas came to our rescue on numerous occasions.
- [Starlight](https://github.com/PaperMC/Starlight) by Spottedleaf — the architecture and core algorithms (BFS propagation, SWMR nibble arrays, deferred lighting) are derived from Starlight's design.
- [SuperNova](https://github.com/GTNewHorizons/SuperNova) by mitchej123 — a Starlight-inspired RGB colored lighting engine for 1.7.10. Pulsar is a scalar-only port of SuperNova's Starlight-derived BFS engine, with all RGB / color features removed. The majority of Pulsar's light engine, mixin targets and chunk-send strategy were rewritten with reference to SuperNova's implementation.
- [Hodgepodge](https://github.com/GTNewHorizons/Hodgepodge) by GTNHdev — several of its optimizations are embedded in Pulsar, rewritten with reference to Hodgepodge's implementation.
- [Alfheim](https://github.com/Red-Studio-Ragnarok/Alfheim) by Desoroxxx — referenced when implementing Pulsar's BFS queue dedup layer (currently deprecated).
- [GTNHLib](https://github.com/GTNewHorizons/GTNHLib) by GTNHdev — code from several of SuperNova's dependencies has been rewritten for 1.12.2 and embedded into Pulsar.
- [CleanroomModTemplate](https://github.com/CleanroomMC/CleanroomModTemplate) by CleanroomMC — Pulsar uses CleanroomMC's developer-friendly modern 1.12.2 modding template.

## License
[LGPL-3.0](LICENSE.md))