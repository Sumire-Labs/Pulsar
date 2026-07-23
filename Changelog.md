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
- `sendChunksWithoutLight` now defaults to `false`: chunks are sent only
  after BFS completes (1.12.2 has no light-update packet to correct them
  later). With persistence, only freshly generated chunks pay the delay.
- Render notifications now mark only the changed-block bounding box per
  section, deduplicated per client tick — replacing the 3×3×3 section
  spread that rebuilt up to 27 RenderChunks per light change.

### Fixed

- `ScalarSkyEngine.initSkyNibble` now only initialises NULL-state nibbles
  (upstream Starlight's guard); previously it could overwrite valid light
  data during section changes.

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