# Pulsar

A Starlight-style scalar lighting engine for Minecraft 1.12.2, built for [CleanroomLoader](https://github.com/CleanroomMC/CleanroomLoader).

> [!WARNING]
> This mod is a personal hobby project and still in an early stage of development.
> Benchmarks and playtests have been conducted, but verification with large-scale modpacks has not been performed.
> If you are trying to use this project without understanding its purpose, please use this instead: [Alfheim](https://www.curseforge.com/minecraft/mc-mods/alfheim-lighting-engine)

## Overview

Pulsar fully replaces the vanilla lighting engine with an implementation of [Starlight](https://github.com/PaperMC/Starlight)'s architecture: SWMR (single-writer multi-reader) nibble arrays, per-chunk initial lighting with deferred edge checks, and BFS propagation that runs on dedicated worker threads instead of the server thread.

### What it does

- Replaces vanilla block **and** sky light propagation with Starlight-equivalent BFS.
- Runs light computation on worker threads — the server thread never pays for lighting.
- Processes bulk edits (explosions, `/fill`, worldedit-style paste) as one batch per chunk instead of per block.
- Caches light in chunk NBT (`PulsarLight` tag): worlds do not relight on every load. The cache is version-gated, so light from older/incompatible builds is relit automatically.
- Keeps the vanilla `blockLight`/`skyLight` nibbles in sync, so removing the mod leaves a working world behind.
- Thin client: the server's light is authoritative and the client never recomputes it. Chunks are sent only once their own and their four neighbours' initial light is done (no light-update packet exists in 1.12.2, so light sent wrong would stay wrong).
- Fixes vanilla rendering bugs around directional light ([MC-92](https://bugs.mojang.com/browse/MC-92) family — slabs and stairs, ported from Alfheim) and light-emitting blocks darkened by ambient occlusion (MC-50734, MC-249343).

### What it doesn't do

- No RGB / colored light — Pulsar is scalar only.

## Benchmarks

Measured with a 1.12.2 port of Spottedleaf's [lightbench](https://github.com/Sumire-Labs/lightbench) methodology on CleanroomLoader 0.5.15-alpha, fixed seed, fresh world per run, identical mod stack with only the lighting engine swapped. Values are the mean of 2 runs; the sustained-load panel is a single sweep per engine.

Test system: **Ryzen AI MAX+ 395 · Radeon 8060S · 96 GB DDR5-8000**, BareBones Template (Cleanroom) instance.

![Benchmark: Pulsar vs Alfheim vs vanilla](docs/benchmarks/bench-three-engines.svg)

| | vanilla 1.12.2 | Alfheim 1.6 | Pulsar 0.1.0-Dev.14 |
|---|---:|---:|---:|
| Worldgen, 10,201 chunks until fully lit | 61.3 s | 54.0 s | **49.8 s** |
| — p99 per chunk | 16.7 ms | 12.3 ms | **11.7 ms** |
| Block **remove** until light converged (p50) | 23.5 ms | 4.28 ms | **0.18 ms** |
| Block **place** until light converged (p50) | 308 ms | 10.3 ms | **0.18 ms** |
| Bulk: 4,096-block platform fully lit | 26.0 s | 0.6 s | **0.2 s** |
| Sustained load, MSPT @ 256 edits/tick | 32.5 ms | 38.7 ms | **7.3 ms** |
| Sustained load, MSPT @ 2048 edits/tick | 233 ms | 196 ms | **33.6 ms** |

Worldgen wall time is dominated by terrain generation itself, so the headline there is modest (−19% vs vanilla, −8% vs Alfheim, plus ~9 s of light CPU moved off the main thread). The axis where the architecture actually shows is **edit latency**: single-block light convergence lands in the sub-millisecond range and the worst-case spikes disappear — which is what you feel as stutter in game.

About the **sustained-load rows**: that test is a stress test of Pulsar's own ceiling, not a realistic scenario. Ordinary play — building, mining, farms, light automation — stays below ~10 light-affecting edits per tick, where every engine is effectively free and TPS is identical across all three; even sustained TNT clearing or large machine fleets only reach roughly 64–256. We were simply curious how far the async design could be pushed before 20 TPS breaks, so the harness force-feeds edit rates that real worlds essentially never produce (every tick it toggles N random blocks of a y=254 platform, each flipping a ~190-block sky column, with no per-edit drain — each engine pays exactly where it would on a live server). Pulsar held the 50 ms tick budget through 2048 edits/tick, with its workers genuinely keeping pace (post-run backlog ≤ 0.03 s). The other engines' curves are in the chart for context, not as a scoreboard — at the edit rates actual gameplay produces, all three engines do their job.

## Trade-offs — where Alfheim is still the better pick

The benchmarks above are all wins for Pulsar, so here is the other side of the ledger:

- **Strict light consistency.** Alfheim flushes its update queue synchronously on every light read, so code that changes a block and reads light in the same tick always sees the final value. Pulsar converges asynchronously — typically well under a millisecond, but a read in the same call stack can still see the pre-edit value, so gameplay logic that reads light immediately after editing blocks (mob-spawn checks, light-sensing contraptions) can run a tick behind.
- **Memory.** Pulsar keeps double-buffered SWMR nibble arrays on top of the vanilla ones — roughly 2–3× the light memory per loaded chunk — and the persisted light cache makes saves somewhat larger. Alfheim runs on vanilla storage plus a queue.
- **Total CPU / small machines.** Pulsar's speed comes from moving light to worker threads (~1.5 cores busy at the heaviest benchmark rung). On the 32-thread test system that parallelism is free; on a 2–4 core budget server the workers compete with the main thread, and Alfheim's lower total CPU use could come out ahead. Untested.
- **Client FPS while streaming chunks — under investigation.** There are early signs that Pulsar's render-side integrations (Celeritas clone-cache invalidation, directional-light lookups) may cost some FPS while flying through freshly loaded terrain. A client-side benchmark is planned; until then, treat this as a possible Alfheim win.
- **Maturity.** Alfheim is battle-tested across large modpacks. Pulsar is `0.1.0-dev.x` with a young compatibility surface — hence the warning at the top of this page.

## Requirements

- [CleanroomLoader](https://github.com/CleanroomMC/CleanroomLoader) >= 0.5.x (benchmarked on 0.5.15-alpha)

Install by dropping the jar into `mods/`. No other mod is required.

## Compatibility

Pulsar fully replaces the vanilla lighting engine, so it conflicts with anything that does the same.

### Incompatible

- **[Alfheim](https://www.curseforge.com/minecraft/mc-mods/alfheim-lighting-engine)** — lighting engine replacement.
- **Phosphor (Forge)** and **Hesperus** — lighting engine replacements.
- **Any other mod that replaces or rewrites the lighting engine.**
- **The Aether II** — bundles Phosphor. Use [The Aether II: Phosphor Not Included](https://www.curseforge.com/minecraft/mc-mods/the-aether-ii-phosphor-not-included) instead.
- **CubicChunks** — cubic world format, untested and likely incompatible.

### Not recommended

- **OptiFine** — untested, most likely incompatible. Use [Celeritas](https://github.com/kappa-maintainer/Celeritas-auto-build) instead (see below).

### Works with

- **[Celeritas](https://github.com/kappa-maintainer/Celeritas-auto-build)** — Pulsar ships two optional integrations, both **off by default** while their FPS impact during chunk streaming is being measured (see Trade-offs): `compat.celeritasDirectionalMeshLight` routes Celeritas's chunk-meshing light lookups through the MC-92 directional fix (restart required), and `compat.celeritasCloneInvalidation` invalidates Celeritas's cloned-section cache on light changes so meshes never rebuild from stale light — enable it if you see lighting stick in sealed rooms. Both are soft hooks that disable themselves cleanly when Celeritas is absent.
- So far, it works without conflicting with the mods I regularly use, such as Chibi, Universal Tweaks, StellarCore, and VintageFix.

## Known issues

- Building a platform in open air **above** the previously highest block of a chunk can leave the space under it rendered bright until the area is relit — tracked in the [Changelog](Changelog.md) under `0.1.0-dev.14` Known issues.

## Credits

- [Starlight](https://github.com/PaperMC/Starlight) by Spottedleaf — the architecture and core algorithms Pulsar implements.
- [SuperNova](https://github.com/GTNewHorizons/SuperNova) by mitchej123 / GTNewHorizons — the 1.7.10 Starlight port Pulsar originally grew out of.
- [Alfheim](https://github.com/Red-Studio-Ragnarok/Alfheim) by Red Studio — the directional render-light fixes (MC-92 family) are ported from Alfheim.
- [CleanroomModTemplate](https://github.com/CleanroomMC/CleanroomModTemplate) by CleanroomMC — the modding template Pulsar is built on.

## License

[LGPL-3.0](LICENSE.md). The render-light fixes ported from Alfheim remain under their original MIT license.

## ⚠️ Notice

Part of this mod's code is written with the help of generative AI. I review the generated code beforehand, but on rare occasions an imperfection may still remain — if you spot one, I'd appreciate it if you let me know via an Issue.

I'm also well aware that some people feel uneasy about, or dislike, software that uses generative AI. If you're okay with that, I'd be glad to have you use this mod.
