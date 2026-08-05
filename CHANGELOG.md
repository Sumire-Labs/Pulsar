# CHANGELOG

All notable changes to Pulsar are documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [0.1.1]

### Changed

- Removed the optional Celeritas-specific directional mesh-light and
  clone-cache hooks. Current Celeritas builds invalidate their own cloned
  sections when scheduling rebuilds, and normal Pulsar lighting remains
  compatible without the extra integrations.
- Added a reproducible Lightbench comparison to the README, including three
  interleaved runs each for vanilla, Alfheim, and Pulsar, the test conditions,
  and exact per-run aggregate data.

### Fixed

- Added compatibility with Depths Update's per-dimension height bounds and
  chunk-section layout, allowing block and sky light to propagate, sync, and
  persist below Y=0 and above Y=255 ([#4](https://github.com/Sumire-Labs/Pulsar/issues/4)).
  Existing Pulsar light caches are invalidated once so affected chunks relight
  across the corrected height range.
- Fixed the Depths Update a12 bridge falling back to vanilla height because the
  released jar exposes storage-index mapping on `HeightContext`, rather than
  the newer `HeightManager` forwarding method. Light caches written while the
  bridge was disabled are invalidated once so affected chunks relight.
- Fixed `/pulsar relight` resending only the vanilla sixteen chunk sections in
  Depths Update worlds, leaving corrected light below Y=0 or above Y=255
  invisible to connected players until the chunk was reloaded.
- Fixed chunks briefly appearing to have no pending light work after a worker
  started processing them, which could race chunk saves/unloads and preserve
  stale or partially updated lighting.
- Fixed the global pending-update status reporting idle while a worker was
  still processing a dequeued task, which could let diagnostics and completion
  waiters return before light propagation finished.
- Fixed overlapping relights and BFS-overflow retries marking chunks as lit
  from stale or duplicate worker completions. Relight generations are now
  isolated, and readiness waits for the final retry of every active light
  engine.
- Fixed newly lit chunks becoming ready before their deferred sky- and
  block-light border checks finished, which could send or save visible seams
  between neighbouring chunks. Border checks now finish for the same relight
  generation before the chunk is published as ready.
- Fixed Mod blocks with world- or position-dependent Forge light values being
  treated as if their opacity and emission were constant per block state.
  Ordinary blocks retain the cached fast path; only overriding block classes
  use context-aware lookups, with no per-lookup `BlockPos` allocation. Existing
  Pulsar light caches are invalidated once so affected chunks relight.


## [0.1.0]

### Changed

- Made Celeritas chunk mesh light lookups and clone section cache invalidation for MC-92 fixes optional (toggable), and temporarily disabled them by default due to performance degradation.

### Added

- Added compatibility with Fluidlogged API

## [0.1.0-dev.15]

### Fixed
- The icon is not displaying correctly because mcmod.info is being modified.

## [0.1.0-dev.14]

### Changed

- Sky-light block changes are processed as ONE batch per chunk (upstream
  Starlight's set-based `propagateBlockChanges`): caches prepared once, one
  skylight column walk per changed COLUMN (highest changed Y), every
  `checkBlock` seeded into the same queues and a single BFS drain at the
  end — instead of the full pipeline per position. Bulk edits converge
  dramatically faster (64×64 platform at y=254: light settle 1.64s → 0.07s;
  single-edit convergence p50 1.4ms → 0.14ms, p99 outliers gone), and
  worldgen light CPU drops ~13%

### Known issues

- Sections created above the previous highest non-empty section (skybase
  platforms in open air) can keep their moment-of-creation vanilla light —
  the area under such a platform may render fully bright. Pre-existing
  (not from the batching change); the engine-side sky nibble for that
  section stays NULL instead of being initialised and re-darkened

### Added

- `/pulsarc` client-side diagnosis command: prints client vanilla, client
  engine (SWMR + state) and integrated-server light values side by side for
  the player's surroundings — one glance shows which layer diverges
- Facing-aware neighbour brightness for slabs and stairs (MC-92 family),
  ported from Alfheim (MIT, Red Studio): the render-side lookups in
  `World`/`ChunkCache`/Celeritas's `WorldSlice` now take light only through
  the faces a block declares open instead of vanilla's max over all five
  neighbours, and light-emitting blocks are no longer darkened by ambient
  occlusion (MC-50734, MC-249343)

### Fixed

- Lighting inside tight enclosures going stale — bright floors in sealed
  boxes, rooms staying dark after breaking open a window (even through
  F3+A): Celeritas meshes chunks from cached section clones and its rebuild
  path never invalidates them, so whether a rebuild saw fresh light was a
  race against the end-of-tick BFS. Pulsar now invalidates the affected
  cloned sections (reflection bridge, soft dependency) whenever the client
  engine publishes light changes

## [0.1.0-dev.12] - 2026-07-26

### Fixed

- Phantom light sources on the ocean floor (and anywhere water converts lava
  to stone): when a chunk unloaded with light updates still queued, the
  pending removal was dropped and the stale "light of the removed source" was
  persisted as valid — fossilising glowing patches into the save. Chunks are
  no longer persisted as lit while value-changing light work is pending; they
  relight on next load instead. Existing fossils can be cleaned with
  `/pulsar relight <radius>`

## [0.1.0-dev.11] - 2026-07-26

### Added

- Light data is now cached in chunk NBT and restored on load (no more relight
  on every load; existing worlds relight once)
- Per-block-state light cache (faster light propagation)

### Changed

- The client no longer relights received chunks; it uses the server's results
  directly
- Slimmed down the client light pipeline (no worker threads, no duplicate
  light storage — everything runs on the main thread)
- Chunks are sent only after their own and their 4 neighbours' initial light
  is done
- `sendChunksWithoutLight` now defaults to `false`
- Light queue priority lookups are now O(1)

### Fixed

- Whole chunks (surface included) rendering pitch black around where the
  player stops during fast worldgen: sections created by decoration AFTER a
  chunk's initial light completed were sent to clients with all-zero light.
  New sections are now filled from the engine's data the moment they are
  created (server and client)
- `/pulsar relight` now resends the relit chunks to watching clients (1.12.2
  has no light packet, so relights were invisible without a rejoin)
- Deferred edge checks could be silently dropped when one engine finished a
  chunk before the other; they are now queued only once both engines are done
- Caves, mineshafts and ocean/lake floors rendering fully bright
- Opening an enclosed space leaving it pitch black
- Stale brightness lingering under newly placed blocks or water
- Black holes and dark bands at chunk borders
- Ghost light remaining behind a dimmer light source after breaking a
  brighter one
- Light not updating correctly when placing or breaking slabs and stairs
- Sky light of a whole chunk breaking when a block is placed in empty air
- Heightmap being computed one block too low (top blocks were ignored)
- Chunks being sent before decoration (trees/ores), flooding clients with
  block-update packets
- Block updates not being delivered for chunks still waiting on light
- A race between chunk saving and in-flight light work
- `debug.enableDebugStats` writing a log file even when disabled
- Removed wasteful recomputation and writes in the BFS (restored upstream
  Starlight optimizations)
- Light data from older builds is now relit automatically on load

### Removed

- The non-functional `performance` config category
- The `trackPlayerAction` config and the synchronous light path (no longer
  needed)
- Assorted dead code (`SafeBlockAccess`, the render update queue, unused
  counters)

## [0.1.0-dev.10]

### Changed

- Synced with upstream CleanroomModTemplate

### Fixed

- Broken action ID and mismatched Gradle version in CI workflows

### Removed

- The Alfheim-style BFS queue deduplication layer

## [0.1.0-dev.9] - 2026-04-27

### Fixed

- Underwater sky light not rendering correctly

## [0.1.0-dev.8] - 2026-04-27

### Fixed

- `OutOfMemoryError` during JEI plugin registration

### Changed

- Initial BFS queue size lowered from 32768 to 4096 (less memory)
- Engine pool is no longer pre-allocated at world creation
- BFS dedup sets are only allocated when `enableBfsDedup` is on

## [0.1.0-dev.7] - 2026-04-14

### Changed

- `enableBfsDedup` now defaults to `false` (saves ~1.5 MB per engine)

### Removed

- Unused dirty-range tracking

## [0.1.0-dev.5] - 2026-04-10

### Changed

- Config migrated to Forge's `@Config` system (editable in-game via Mod
  Options)

## [0.1.0-dev.4] - 2026-04-10

### Fixed

- Crash (`ArrayIndexOutOfBoundsException`) during chunk generation bursts
- Dark spots lingering near unloaded chunk borders

## [0.1.0-dev.3] - 2026-04-10

### Added

- Right-click placement (torches etc.) now also gets instant light updates

## [0.1.0-dev.2] - 2026-04-10

### Added

- Alfheim-style BFS queue deduplication (`enableBfsDedup`)
- CHANGELOG.md

### Changed

- Expanded README credits

## [0.1.0-dev.1] - 2026-04-10

First working build
