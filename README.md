# Pulsar

A lighting engine rebuilt for Minecraft 1.12.2, exclusively for [CleanroomLoader](https://github.com/CleanroomMC/CleanroomLoader).

> [!WARNING]
> This mod is a personal hobby project and still in an early stage of development.
> If you are trying to use this project without understanding its purpose,
> please use this instead: [Alfheim](https://www.curseforge.com/minecraft/mc-mods/alfheim-lighting-engine)

## Overview

Pulsar fully replaces the vanilla lighting engine with an implementation of [Starlight](https://github.com/PaperMC/Starlight)'s architecture: SWMR (single-writer multi-reader) nibble arrays, per-chunk initial lighting with deferred edge checks, and BFS propagation that runs on dedicated worker threads instead of the server thread.

### What it does

- Replaces vanilla block **and** sky light propagation with Starlight-equivalent BFS.
- Runs light computation on worker threads — the server thread never pays for lighting.
- Processes bulk edits (explosions, `/fill`, worldedit-style paste) as one batch per chunk instead of per block.
- Caches light in chunk NBT (`PulsarLight` tag): worlds do not relight on every load (inspired by Alfheim).
- Keeps the vanilla `blockLight`/`skyLight` nibbles in sync, so nothing breaks even if you remove the mod and switch to another lighting engine.
- Thin client: the server's light is authoritative and the client never recomputes it. Chunks are sent only once their own and their four neighbours' initial light is done.
- Fixes the vanilla rendering bug around directional light ([MC-92](https://bugs.mojang.com/browse/MC-92)), plus MC-50734 and MC-249343.

## Benchmarks

Measured with a 1.12.2 port of Spottedleaf's [lightbench](https://github.com/Sumire-Labs/lightbench) methodology on CleanroomLoader-0.5.15, fixed seed, fresh world per run, identical mod stack with only the lighting engine swapped. Values are the mean of 2 runs; the sustained-load panel is a single sweep per engine.

Test system: **Ryzen AI MAX+ 395 · Radeon 8060S**, BareBones Template (Cleanroom) instance.

![Benchmark: Pulsar vs Alfheim vs vanilla](docs/benchmarks/bench-three-engines.svg)

<details>
<summary>Full measurement table (collapsed so it doesn't clutter the page)</summary>

| | vanilla 1.12.2 | Alfheim 1.6 | Pulsar 0.1.0-Dev.14 |
|---|---:|---:|---:|
| Worldgen, 10,201 chunks until fully lit | 61.3 s | 54.0 s | **49.8 s** |
| — p99 per chunk | 16.7 ms | 12.3 ms | **11.7 ms** |
| Block **remove** until light converged (p50) | 23.5 ms | 4.28 ms | **0.18 ms** |
| Block **place** until light converged (p50) | 308 ms | 10.3 ms | **0.18 ms** |
| Bulk: 4,096-block platform fully lit | 26.0 s | 0.6 s | **0.2 s** |
| Sustained load, MSPT @ 256 edits/tick | 32.5 ms | 38.7 ms | **7.3 ms** |
| Sustained load, MSPT @ 2048 edits/tick | 233 ms | 196 ms | **33.6 ms** |

</details>

Worldgen wall time is dominated by terrain generation itself, so the headline there is modest (−19% vs vanilla, −8% vs Alfheim, plus ~9 s of light CPU moved off the main thread). The axis where the architecture actually shows is **edit latency**: single-block light convergence lands in the sub-millisecond range and the worst-case spikes disappear — which is what you feel as stutter in game.

About the **sustained-load rows**: that test is a stress test of Pulsar's own ceiling, not a realistic scenario. Ordinary play most likely stays below ~10 light-affecting edits per tick, where every engine is effectively free and TPS is identical across all three; even sustained TNT blasts or large machine fleets only reach roughly 64–256. We were simply curious how far the async design could be pushed before 20 TPS breaks, so the harness force-feeds edit rates that real worlds essentially never produce (every tick it toggles N random blocks of a y=254 platform, each flipping a ~190-block sky column, with no per-edit drain — each engine pays exactly where it would on a live server). Pulsar held the 50 ms tick budget through 2048 edits/tick, with its workers genuinely keeping pace (post-run backlog ≤ 0.03 s). The other engines' curves are in the chart for context, not as a scoreboard — at the edit rates actual gameplay produces, all three engines do their job.

### On a heavy modpack

We re-ran the same sustained-load sweep on a ~290-mod CleanroomLoader 0.6.7 modpack:

![Sustained edit load on a 250+ mod pack](docs/benchmarks/bench-heavy-modpack.svg)

Two things change in a heavy environment. Worldgen becomes even more terrain-dominated — light is under 10% of a ~16 ms chunk, so all three engines generate within noise of each other (169 s / 159 s / 200 s, with 6–7 s single-chunk structure spikes). The edit axis moves the other way: the latency gap **widens** — Alfheim's inline pipeline pays the pack's heavier block-access hooks (6.7 / 14.3 ms p50 for remove / place) while Pulsar's worker wakeup does not care about mod count (0.094 / 0.099 ms, roughly 70–145×).

In the chart, Pulsar's nearly flat line doubles as a control: its small rise is the tick-thread cost of the edits themselves (mod block-update hooks, +46 ms at 2048 edits/tick). Subtracting that, Alfheim and vanilla pay **+136–139 ms of lighting per tick** at 2048 — and on this pack they exceed the 20 TPS budget from just 64 edits/tick, while Pulsar stays in budget to roughly 512. Same honest caveats as above: single sweep per engine, fresh world per engine (modded worldgen varies spawn terrain, so read the slopes rather than the absolute offsets).

## Trade-offs — where Alfheim is still the better pick

The benchmark numbers above measure the scenarios where Pulsar's design has the advantage. There are also scenarios where, by design, Alfheim is the better fit:

- **Strict light consistency.** Alfheim flushes its update queue synchronously on every light read, so code that changes a block and reads light in the same tick always sees the final value. Pulsar converges asynchronously — typically well under a millisecond, but a read in the same call stack can still see the pre-edit value, so gameplay logic that reads light immediately after editing blocks (mob-spawn checks, light-sensing contraptions) can run a tick behind.
- **Memory.** Pulsar keeps double-buffered SWMR nibble arrays on top of the vanilla ones — roughly 2–3× the light memory per loaded chunk — and the persisted light cache makes saves somewhat larger. Alfheim runs on vanilla storage plus a queue.
- **Total CPU / small machines.** Pulsar's speed comes from moving light to worker threads (~1.5 cores busy at the heaviest benchmark rung). On the 32-thread test system that parallelism is free; on a 2–4 core budget server the workers compete with the main thread, and Alfheim's lower total CPU use could come out ahead. Untested.
- **Maturity.** Alfheim has years of service behind it and is proven stable across large modpacks. Pulsar's compatibility surface is still young — hence the warning at the top of this page.

## Requirements

- [CleanroomLoader](https://github.com/CleanroomMC/CleanroomLoader) >= 0.5.x

## Compatibility

Pulsar fully replaces the vanilla lighting engine, so it conflicts with anything that does the same.

### Incompatible

- **[Alfheim Lighting Engine](https://www.curseforge.com/minecraft/mc-mods/alfheim-lighting-engine)**
- **Phosphor (Forge)** and **Hesperus**
- **Any other mod that replaces or rewrites the lighting engine.**
- **The Aether II** — bundles Phosphor. Use [The Aether II: Phosphor Not Included](https://www.curseforge.com/minecraft/mc-mods/the-aether-ii-phosphor-not-included) instead.
- **CubicChunks** — cubic world format, untested and likely incompatible.

### Not recommended

- **OptiFine** — untested, most likely incompatible. Use [Nothirium](https://github.com/Meldexun/Nothirium) or [Celeritas](https://github.com/kappa-maintainer/Celeritas-auto-build) instead (see below).

### Works with

- **[Nothirium](https://github.com/Meldexun/Nothirium)** (+ RenderLib; on Cleanroom add [Naughthirium](https://www.curseforge.com/minecraft/mc-mods/naughthirium)) — verified in-game; works out of the box, just install both. You'll see one mixin-overlap warning at startup, but it's harmless (both mods apply the same fix, so whichever one wins, behaviour is identical).
- **[Celeritas](https://github.com/kappa-maintainer/Celeritas-auto-build)** — works as-is. On top of that, Pulsar ships two optional integrations, both **off by default** while their FPS impact is being verified:
  - `compat.celeritasDirectionalMeshLight` — applies the stairs/slab light-rendering fix (MC-92) to terrain rendering as well (restart required)
  - `compat.celeritasCloneInvalidation` — prevents lighting from sticking stale in sealed rooms; turn it on if you ever see that happen

## Credits

- [Starlight](https://github.com/PaperMC/Starlight) by Spottedleaf — the architecture and core algorithms Pulsar implements.
- [SuperNova](https://github.com/GTNewHorizons/SuperNova) by GTNewHorizons — the 1.7.10 Starlight port early versions of Pulsar were based on.
- [Alfheim](https://github.com/Red-Studio-Ragnarok/Alfheim) by Red Studio — the MC-92 family of fixes is ported from Alfheim.
- [CleanroomModTemplate](https://github.com/CleanroomMC/CleanroomModTemplate) by CleanroomMC — the modding template Pulsar is built on.

## License

[LGPL-3.0](LICENSE.md)

## ⚠️ Notice

Part of this mod's code is written with the help of generative AI. I review the generated code beforehand, but on rare occasions an imperfection may still remain.

I'm also well aware that some people feel uneasy about, or dislike, software that uses generative AI. If you're okay with that, I'd be glad to have you use this mod.
