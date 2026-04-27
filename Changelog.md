# CHANGELOG

All notable changes to Pulsar are documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [0.1.0-dev.8] - 2026-04-27

### Fixed
- `OutOfMemoryError: Java heap space` thrown from
  `ScalarSkyEngine.<init>` during JEI plugin registration on modpacks with
  many multiblock recipes (reproduced on a Cleanroom 0.5.8-alpha pack #1 with
  GregTech CEu  + Had Enough Items, ). Root
  cause: GregTech's `WorldSceneRenderer` instantiates a `TrackedDummyWorld`
  per multiblock preview, and `MixinWorld.pulsar$getLightManager()` had no
  filter for fake worlds, so each preview eagerly built a full
  `WorldLightManager` (~17 MB heap + 2 daemon threads). With 100+
  multiblocks the cumulative allocation overran the heap before
  `JeiStarter.registerPlugins` finished, even with `-Xmx10240m`.
  `MixinWorld.pulsar$getLightManager()` now consults
  `Pulsar.proxy.isRealMainWorld(World)` and returns `null` for any world
  that is not a `WorldServer` (server side) or the active
  `Minecraft.getMinecraft().world` (client side); every existing caller
  already tolerates a `null` manager. Vanilla light propagation still runs
  on fake worlds via the unconditional `MixinChunk.pulsar$generateSkylightMap`
  replacement, which is what the preview renderer expects.

### Changed
- `PulsarEngine.INITIAL_QUEUE_SIZE` lowered from `1 << 15` (32768) to
  `1 << 12` (4096), matching Starlight's `StarLightEngine.java:1023`
  (`new long[16 * 16 * 16]`). The existing
  `resize{Increase,Decrease}Queue` doublers already grow on demand up to
  `MAX_QUEUE_SIZE = 1 << 20`, so bursts that previously fit in the
  preallocated 32K still complete with at most three doublings (amortised
  O(1)). Saves 480 KB per engine before any BFS work starts.
- `ScalarSkyEngine` no longer overrides the base queue size with
  `1 << 18`. The override was a SuperNova-era empirical bump for sky
  bursts; with Starlight's 4096-entry initial size the resize path covers
  the same workload and saves 4 MB per sky engine instance.
- `WorldLightManager` constructor no longer eagerly pre-populates the
  engine pool with two sky + two block engines. `getEngine(cache, factory)`
  has always allocated on cache miss, so the eager calls were redundant.
  First BFS task on a fresh world pays one allocation; subsequent tasks
  reuse pooled engines via `releaseEngine`. Saves ~16 MB heap at world
  construction; defence-in-depth in case the new fake-world filter ever
  misses an edge case.
- `PulsarEngine.increaseDedupSet` / `decreaseDedupSet` are now allocated
  only when `PulsarConfig.performance.enableBfsDedup` is `true`. Previously
  the `LongOpenHashSet`s were created unconditionally even though the
  flag has defaulted to `false` since `0.1.0-dev.7`. The
  append/rollback/clear paths already gated on the same flag, so the sets
  are never read while null. Saves ~1.5 MB per engine when dedup is off
  (the default).

### Notes
- Combined heap impact for a real overworld: `WorldLightManager`
  construction drops from ~17 MB to a few hundred KB; steady-state with
  one sky + one block engine drops from ~17 MB to ~0.2 MB. For fake
  worlds the cost is now zero — they never get a manager.
- Runtime-flipping `enableBfsDedup` from `false` to `true` only enables
  dedup for engines allocated *after* the flip; engines already in the
  pool stay non-dedup until they roll over (max 4 in pool, replaced as
  pool releases run). World reload guarantees full rollover. Matches the
  existing semantics of the flag as a memory/CPU trade-off knob.

## [0.1.0-dev.7] - 2026-04-14

### Changed
- Default `enableBfsDedup` to `false`. The Alfheim-style `LongOpenHashSet`
  dedup layer costs ~1.5 MB of extra memory and neither the original
  Starlight nor SuperNova engines use dedup. The option remains available
  for users who observe BFS queue overflow warnings.

### Removed
- Removed unused dirty byte range tracking fields (`dirtyByteMin`,
  `dirtyByteMax`) and their accessor methods (`getDirtyByteMin`,
  `getDirtyByteMax`, `resetDirtyRange`) from `SWMRNibbleArray`.
  `ChunkLightHelper.sync*` always performs a full `System.arraycopy`,
  so the per-nibble range tracking was dead code. Saves 8 bytes per
  `SWMRNibbleArray` instance (36 instances/chunk).

## [0.1.0-dev.5] - 2026-04-10

### Changed
- Migrated `PulsarConfig` from the legacy Forge `Configuration` API to the
  annotation-based `@Config` system. The config file (`config/pulsar.cfg`)
  is now managed automatically by Forge's `ConfigManager`, which provides:
  - Runtime config editing through the Mod Options GUI (no restart required).
  - Automatic `ConfigChangedEvent` listener that re-syncs field values
    when the GUI is closed.
  - `@Config.RangeInt` validation on numeric fields.
- Config fields are now organised into nested category classes:
  `PulsarConfig.performance.*`, `PulsarConfig.features.*`,
  `PulsarConfig.debug.*`. The top-level `PulsarConfig.enabled` master
  switch remains unchanged.
- Removed the manual `PulsarConfig.load()` / `sync()` / `save()` methods;
  `Pulsar.preInit` now calls `ConfigManager.register(PulsarConfig.class)`.

## [0.1.0-dev.4] - 2026-04-10

### Fixed
- `ArrayIndexOutOfBoundsException: Index -1` crash on the `Pulsar-Sky`
  worker thread during heavy chunk-gen bursts (reported on a mid-size
  Cleanroom modpack with Quark / Battle Towers style worldgen). This
  was a regression introduced by the BFS dedup in
  `0.1.0-dev.2`: `ScalarSkyEngine.tryPropagateSkylight` uses a
  speculative-append-with-rollback pattern, and the dedup short-circuit
  caused the unconditional rollback to drive
  `increaseQueueInitialLength` negative, which crashed the next BFS
  drain on `increaseQueue[-1]`. `appendToIncreaseQueue` /
  `appendToDecreaseQueue` now return a `boolean` indicating whether
  the entry was actually written, and `tryPropagateSkylight` gates
  both the counter decrement and the new `rollbackIncreaseDedup`
  call on that return value so the counter and the dedup set stay
  in sync.
- Rolled-back speculative appends no longer leak into the dedup set.
  Without the explicit `rollbackIncreaseDedup`, a rolled-back key
  would stay in the `LongOpenHashSet` and silently reject any future
  legitimate enqueue of the same `(coord, level, flags)` triple,
  which could leave isolated dark spots near unloaded chunk
  boundaries.

## [0.1.0-dev.3] - 2026-04-10

### Added
- Right-click placement tracking in `MixinPlayerControllerMP`.
  `processRightClickBlock` now raises and lowers the
  `pulsar$playerAction` flag symmetrically with `onPlayerDestroyBlock`,
  so client-side `checkLightFor` calls triggered by torch / lantern
  placement take the synchronous fast-path instead of being queued
  behind the BFS worker. Noticeable during heavy chunk-gen bursts —
  without this, a player-placed torch could sit behind the
  initial-light backlog for several hundred ms. Under steady-state
  load the effect is sub-frame.
- Gated by the existing `PulsarConfig.trackPlayerAction` flag.

### Documentation
- `CHANGELOG.md` is now shipped in the repo (added in 0.1.0-dev.2's
  post-release window) and is the canonical per-version changelog.

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
- README credits entry for [Alfheim](https://github.com/Red-Studio-Ragnarok/Alfheim),
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
