# CHANGELOG

All notable changes to Pulsar are documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [0.1.0-dev.2] - 2026-04-10

### Added
- Alfheim-style BFS queue deduplication. `PulsarEngine.appendToIncreaseQueue` /
  `appendToDecreaseQueue` now consult a `LongOpenHashSet` keyed by
  `(coord, level, write/recheck flags)` and reject duplicate enqueues before
  they reach the drain loop. Direction bits are deliberately excluded from
  the key so that multi-direction re-enqueues of the same `(pos, level)`
  collapse to a single entry.
- New config flag `enableBfsDedup` (default `true`, in the `performance`
  category of `pulsar.cfg`). Kill-switch for the dedup layer in case it is
  ever suspected of dropping legitimate propagation work.
- README credits entry for [Alfheim](https://github.com/embeddedt/Alfheim),
  the Phosphor-derived 1.12.2 lighting engine whose `DeduplicatedLongQueue`
  design the new dedup layer is modelled on.

### Changed
- README credits section expanded with detailed attribution for
  [GTNHLib](https://github.com/GTNewHorizons/GTNHLib) and
  [CleanroomModTemplate](https://github.com/CleanroomMC/CleanroomModTemplate).

## [0.1.0-dev.1] - 2026-04-10

First working build. Delivers a CleanroomLoader-compatible scalar port of
SuperNova's Starlight-derived BFS lighting engine. Visual output matches
vanilla; all RGB / colored-lighting features from 1.7.10 SuperNova are
excluded by design.

### Added

#### Lighting engine core
- `PulsarEngine` abstract BFS base (AxisDirection, 5×5 chunk cache,
  packed-long BFS queue, IBlockState-keyed face occlusion, array pool).
  Ported from SuperNova's `SupernovaEngine` with the RGB layer stripped and
  the 1.7.10 block-id caches replaced by `IBlockState[][]` caches.
- `ScalarBlockEngine` and `ScalarSkyEngine` concrete BFS drivers.
- `SWMRNibbleArray` (single-writer multi-reader 4-bit nibble array with
  updating / visible double buffering and a ThreadLocal byte-pool).
- `WorldLightManager` with dedicated sky and block worker threads, per-chunk
  `LightQueue`, `pendingWork` tracking, initial-light latch, engine pool and
  a time-budgeted drain loop.
- `SafeBlockAccess` — off-thread safe `IBlockAccess` implementation used by
  the BFS workers.
- `ChunkLightHelper` for importing vanilla `blockLight` / `skyLight` nibbles
  into SWMR mirrors and syncing the BFS result back into the vanilla nibbles
  so downstream readers (including Celeritas) see the correct values through
  the stock `Chunk.getLightFor()` path.

#### Mixins (Sponge Mixin 0.8.7, `pulsar.default.mixin.json`)
- `MixinChunk` — SWMR nibble fields, `onLoad` / `onUnload` hooks, vanilla
  skylight column walk + heightmap rebuild replacement for
  `generateSkylightMap`, no-op handlers for `recheckGaps`,
  `enqueueRelightChecks` and `checkLight()`, and
  `pulsar$alwaysPopulated` — the `isPopulated` override that is the 1.12.2
  equivalent of Hodgepodge's `MixinChunk_SendWithoutPopulation` and is what
  lets the server ship chunks to clients before Pulsar's async BFS has
  finished propagating.
- `MixinWorld` / `MixinWorldServer` — route `checkLightFor` / `checkLight`
  into the `WorldLightManager` and drive `scheduleUpdate` from the server
  tick tail.
- `MixinPlayerControllerMP` — tracks client-side player destroy actions so
  the renderer can fast-path the response to player place/break events
  (gated by `trackPlayerAction`).
- `MixinWorldEntitySpawner` — mirrors Hodgepodge's
  `MixinSpawnerAnimals_optimizeSpawning`: removes chunks with pending light
  from `eligibleChunksForSpawning` so mobs do not spawn in dark chunks that
  are still being lit.

#### Public API (`com.sumirelabs.pulsar.api`)
- `ExtendedChunk` — `pulsar$isLightReady()`.
- `ExtendedWorld` — `pulsar$getAnyChunkImmediately`,
  `pulsar$hasChunkPendingLight`, `pulsar$flushLightUpdates`.
- `FaceLightOcclusion` — `EnumFacing` + `IBlockState` per-face opacity hook.

#### Coremod, config and command
- `PulsarLoadingPlugin` implements both `IFMLLoadingPlugin` and mixinbooter's
  `IEarlyMixinLoader` so the mixin config is early-loaded in both dev and
  production loadouts.
- `PulsarConfig` (Forge `Configuration`, written to `config/pulsar.cfg`)
  with `enabled`, `workerThreadPriority`, `lightUpdateBudgetMillisPerTick`,
  `edgeCheckBudgetMillisPerTick`, `trackPlayerAction`,
  `sendChunksWithoutLight` and `enableDebugStats`. Each flag is wired to
  the corresponding mixin gate so the user can actually disable the feature
  it names.
- `/pulsar stats` — print live engine stats.
- `/pulsar relight [<cx> <cz> | <radius>]` — manually requeue BFS work for a
  chunk or for the current chunk radius.

#### Performance fix (merged late in the dev.1 cycle)
- Replaced the SuperNova-ported copy-on-write `SnapshotChunkMap` with a
  plain `ConcurrentHashMap`. The 1.7.10 implementation rebuilt the full
  snapshot on every mutation, which degraded to O(N²) under the chunk-load
  bursts produced by long-distance teleports. The new map removes that
  behaviour; chunk-load bursts that previously stalled the worker for
  several seconds now track the baseline vanilla chunk-gen cost.

### Verified

- Compiles and launches under CleanroomLoader with Java 21.
- New-world generation: sky-light propagates correctly, torches light and
  extinguish immediately, mobs only spawn where vanilla would.
- Existing worlds load with no visible light gaps.
- Multiplayer: chunks are delivered to the client before BFS finishes, and
  the BFS result arrives without a visible "dark chunk" pop.
- Celeritas reads the correct light values through the stock
  `Chunk.getLightFor()` path, with no Pulsar-side mixin required.
