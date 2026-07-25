# CHANGELOG

All notable changes to Pulsar are documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

Performance rework based on a structural comparison against Starlight
upstream (`forge` branch) and Alfheim.

### Added

- Light data persistence (`LightDataSerializer`, port of Starlight's
  `SaveUtil`): SWMR nibbles are saved to chunk NBT and restored on load.
  Restored chunks skip the full relight and only run a cheap cache init
  (`PulsarEngine.loadInChunk`). Old worlds relight each chunk once, then
  never again.
- Per-state light cache (`LightInfo` + `MixinBlockStateImplementation`,
  analogue of Starlight's `BlockStateBaseMixin`): opacity, emission and
  face-occlusion bits are memoised per block state, so the BFS hot loops
  read one field per neighbour visit instead of making virtual calls and
  hash lookups.

### Changed

- The client no longer relights received chunks; it trusts the server's
  nibbles.
- Thin client: the client no longer runs worker threads or keeps a separate
  SWMR light store. Received chunks' nibbles are wrapped in place (shared
  storage with vanilla, no import clone), the light queues are drained on
  the main thread at tick end, and the engines mark render bounds directly.
  Eliminates the chunk-receive import (`scheduledExecutables` measured ~3×
  Alfheim across all benchmarks, with a 95.7ms burst frame in E2E), the
  SWMR→vanilla copy-back, the render-update drain, and two client threads.
  The server keeps the async worker architecture — initial lighting stays
  off-thread.
- `sendChunksWithoutLight` now defaults to `false`: chunks are sent only
  after BFS completes (1.12.2 has no light-update packet to correct them
  later). With persistence, only freshly generated chunks pay the delay.
- Edge checks now run inline during initial lighting (upstream Starlight's
  `light()` path) instead of as a separately queued all-sections task —
  the worker no longer pays a second 5×5 cache setup per chunk per
  engine. Seams to not-yet-lit neighbours are covered by the neighbour's
  own inline check when it lights up; the deferred edge-check machinery
  (`queueEdgeCheck*`, `ChunkTasks.queuedEdgeChecks*`) is removed.
- One `Pulsar-Light` worker thread replaces the sky/block pair: same
  total work, half the threads competing with render and chunk-build
  threads in singleplayer (upstream is also single-lane). Initial-light
  tasks interleave 8 per queue so sky/block completions — and therefore
  `lightReady` chunk sends — keep pace during worldgen bursts.
- `LightQueue` priority lookups are now O(1). The SuperNova-inherited
  implementation scanned the whole task map once per task processed —
  O(N²) per drain, holding the same lock main-thread enqueues need,
  precisely during worldgen bursts. Auxiliary FIFO key queues (validated
  lazily on pop) plus an exact counter replace the scans; priority
  semantics are unchanged.
  
### Fixed

- `ScalarSkyEngine.initSkyNibble` now only initialises NULL-state nibbles
  (upstream Starlight's guard); previously it could overwrite valid light
  data during section changes.
- The `checkLight()` replacement now also sets `isTerrainPopulated` (vanilla
  sets both flags there); previously chunks were saved as unpopulated.
- `setLightLevel` now skips no-op writes. The sky column walk rewrites whole
  columns with unchanged values; each write marked the section dirty (2KB
  vanilla sync + render rebuild) even though nothing changed visually.
- `debug.enableDebugStats` was never checked: stats were collected and
  `logs/pulsar-stats.log` written regardless of the config. Collection and
  the log file are now fully gated (no file is created while off; the
  toggle takes effect at runtime).
- The increase-BFS loops were missing Starlight upstream's
  `currentLevel >= propagatedLevel - 1` early-out, paying a palette read +
  light-info lookup for the ~half of frontier neighbours that can never be
  brightened. Restored in both engines; propagation writes also go through
  the already-resolved nibble instead of `setLightLevel`'s index recompute
  and no-op guard.
- Chunks were sent to clients before terrain population: the `isPopulated`
  gate returned `lightReady` alone, ignoring vanilla's populated flag, so
  chunks shipped pre-decoration and every tree/ore/plant block then
  streamed as an individual block-change packet (~5× the client
  setBlockState rate vs Alfheim), re-marking already-built render chunks
  and re-queueing server BFS per block. The gate now ANDs `lightReady`
  onto vanilla's own result (and `sendChunksWithoutLight=true` now means
  plain vanilla behaviour).

### Removed

- The unwired `performance` config category (`workerThreadPriority` and the
  two budget knobs were never connected to anything; the engine uses fixed
  5ms/10ms worker budgets).
- Unused `ChunkLightHelper.hasSavedBlockData`.
- Dead `LightStats` client sync counters (`renderQueue`/`syncBlock`/
  `syncSky`/`syncMs` — the code feeding them was removed earlier).
- The playerAction sync fast path (`trackPlayerAction` config,
  `MixinPlayerControllerMP`, `WorldLightManager.blockChange`): with
  main-thread queue processing at tick end, player edits are lit within
  the same frame anyway, and the extra synchronous BFS plus re-queue ran
  every place/break three times over.
- `RenderUpdateQueue`, stripped to a `RenderBounds` packing utility — with
  engines marking render updates directly on the main thread, the
  offer/drain machinery had no users left.
- Dead `SafeBlockAccess` (never referenced anywhere; it also carried a
  chunk-key packing mismatch inherited from SuperNova that would have made
  every lookup miss).

## [0.1.0-dev.10]

### Changed

- Synced the template with upstream CleanroomModTemplate.

### Fixed

- Fixed a broken action id in `release-to-cf-mr.yml` and aligned the Gradle
  version across CI workflows.

### Removed

- The Alfheim-style BFS queue deduplication layer.

## [0.1.0-dev.9] - 2026-04-27

### Fixed

- Broken underwater sky-light rendering: `FaceOcclusion.registerDefaults()`
  was never invoked, so the sky-light column walk stopped at the first
  partially-transparent block (water, ice, slabs, leaves). It is now called
  from `Pulsar.postInit`.

## [0.1.0-dev.8] - 2026-04-27

### Fixed

- `OutOfMemoryError` during JEI plugin registration: fake worlds (GregTech
  `WorldSceneRenderer` previews) each allocated a full `WorldLightManager`.
  `pulsar$getLightManager()` now returns `null` for any world that is not
  the real server/client world.

### Changed

- Initial BFS queue size lowered from 32768 to 4096 (matching Starlight);
  the queues already grow on demand. The sky engine's 256K override was
  removed. Saves several MB per engine.
- The engine pool is no longer eagerly pre-populated at world construction.
- BFS dedup sets are only allocated when `enableBfsDedup` is on.

## [0.1.0-dev.7] - 2026-04-14

### Changed

- `enableBfsDedup` now defaults to `false` — neither Starlight nor SuperNova
  use dedup, and the sets cost ~1.5 MB per engine.

### Removed

- Dead dirty byte range tracking in `SWMRNibbleArray` (syncs always copy the
  full array).

## [0.1.0-dev.5] - 2026-04-10

### Changed

- Migrated `PulsarConfig` to Forge's annotation-based `@Config` system:
  runtime editing via the Mod Options GUI, automatic re-sync, and nested
  `performance` / `features` / `debug` categories.

## [0.1.0-dev.4] - 2026-04-10

### Fixed

- `ArrayIndexOutOfBoundsException` on the sky worker during chunk-gen
  bursts: the BFS dedup could roll back a speculative append that was never
  written, driving the queue length negative. Appends now report whether
  they were written, and rollbacks are gated on that.
- Rolled-back speculative appends no longer leak into the dedup set (which
  could leave dark spots near unloaded chunk boundaries).

## [0.1.0-dev.3] - 2026-04-10

### Added

- Right-click placement now raises the `pulsar$playerAction` flag (like
  block breaking already did), so torch/lantern placement takes the
  synchronous fast-path instead of queueing behind the BFS worker.

## [0.1.0-dev.2] - 2026-04-10

### Added

- Alfheim-style BFS queue deduplication behind a new `enableBfsDedup` flag
  (default `true`).
- `CHANGELOG.md` shipped in the repo.

### Changed

- Expanded README credits

## [0.1.0-dev.1] - 2026-04-10

First working build