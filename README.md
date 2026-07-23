# Pulsar

An experimental lighting engine mod for 1.12.2, built on [Starlight](https://github.com/PaperMC/Starlight)'s BFS propagation algorithm.

> [!WARNING]
> This mod is a personal hobby project, and in its current early stage of development, it is infested with hundreds of dragons.
> No benchmarks or proper tests have been conducted, so its effectiveness cannot be guaranteed.
> There is also a risk of world corruption, so we do not recommend using it unless you fully understand what it does. We accept no responsibility for whatever happens as a result of using this mod.
> If you're wondering "What's the point of this mod?", play it safe and use [Alfheim](https://www.curseforge.com/minecraft/mc-mods/alfheim-lighting-engine) instead, which is safe and guaranteed to work.

## Overview

Pulsar replaces Minecraft's lighting engine with a modern BFS-based implementation derived from [Starlight](https://github.com/PaperMC/Starlight). It is intended for use with [CleanroomLoader](https://github.com/CleanroomMC/CleanroomLoader).

Bringing the power of Starlight to 1.12.2.

### What It Does

- Replaces vanilla light propagation with Starlight-equivalent BFS.
- Uses SWMR nibble arrays for thread-safe concurrent lighting.
- Runs light computation on dedicated worker threads with time-budgeted drain loops.
- Writes results back to vanilla `blockLight` / `skyLight` nibbles.

### What It Doesn't Do

- No RGB support (scalar only — visual output is identical to vanilla).
## Incompatibilities or Not compatible

Pulsar fully replaces the vanilla lighting engine, so it conflicts with any mod that touches the same area.

- **Phosphor (Forge)**
- **Alfheim**
- **Other lighting engine replacements**
- **The Aether II** — Use [The Aether II: Phosphor Not Included](https://www.curseforge.com/minecraft/mc-mods/the-aether-ii-phosphor-not-included) instead.

### Not recommended

- OptiFine — Untested, but most likely incompatible. Use [Celeritas](https://github.com/kappa-maintainer/Celeritas-auto-build) instead.

- CubicChunks — Untested, but likely incompatible.

## Requirements

- CleanroomLoader >= 0.5.x

## Credits

- [Starlight](https://github.com/PaperMC/Starlight) by Spottedleaf — Its architecture and core algorithms are referenced from Starlight's design.

- [SuperNova](https://github.com/GTNewHorizons/SuperNova) by mitchej123 — A Starlight-based RGB colored lighting engine for 1.7.10. Pulsar is a rewritten port of SuperNova's scalar engine component.

- [CleanroomModTemplate](https://github.com/CleanroomMC/CleanroomModTemplate) by CleanroomMC — Pulsar uses CleanroomMC's modern and user-friendly modding template.

## License

[LGPL-3.0](LICENSE.md)

## ⚠️ Notice

Part of this mod's code is written with the help of generative AI. I review the generated code beforehand, but on rare occasions an imperfection may still remain — if you spot one, I'd appreciate it if you let me know via an Issue.

I'm also well aware that some people feel uneasy about, or dislike, software that uses generative AI. If you're okay with that, I'd be glad to have you use this mod.

