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
- No custom rendering pipeline — works through vanilla `Chunk.getLightFor()`, so renderers like [Celeritas](https://github.com/AE2-UEL/Celeritas) read correct values automatically

## Requirements

- CleanroomLoader

## Credits

- [Starlight](https://github.com/PaperMC/Starlight) by Spottedleaf — the Architecture and core algorithms (BFS propagation, SWMR nibble arrays, deferred lighting) are derived from Starlight's design.
- [SuperNova](https://github.com/AE2-UEL/SuperNova) by mitchej123 — the RGB colored lighting engine for 1.7.10. Pulsar is a scalar-only port of SuperNova's Starlight-derived BFS engine, with all RGB/color features removed
- [CleanroomMC](https://github.com/CleanroomMC) — [CleanroomModTemplate](https://github.com/CleanroomMC/CleanroomModTemplate) used as the project

## License
[LGPL3.0](LICENSE.md)