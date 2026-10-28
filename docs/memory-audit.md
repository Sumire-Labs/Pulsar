# Lighting memory audit — 2026-10-29

## Goal and pending user validation

The [second round](memory-round2.md) records additional sky-memory reductions and
the user's selected E2EE comparison pack.

Prioritize RGB at its existing default settings, and improve SCALAR as well.
Preserve light correctness and avoid trading memory for slower propagation.
The user will perform the in-game visual checks later. Whole-world comparison
against Alfheim remains pending; this audit does **not** claim that Pulsar's total
heap is already at or below Alfheim's.

## Alfheim comparison

Inspected Alfheim commit `8035b0cb3eff9a1d37591a132b369de0b8c6c8c7`:

- [LightingEngine](https://github.com/Red-Studio-Ragnarok/Alfheim/blob/8035b0cb3eff9a1d37591a132b369de0b8c6c8c7/src/main/java/dev/redstudio/alfheim/lighting/LightingEngine.java)
  initializes 36 queues at capacity 16,384: two update queues, 16 darkening,
  16 brightening, and two initial queues.
- [DeduplicatedLongQueue](https://github.com/Red-Studio-Ragnarok/Alfheim/blob/8035b0cb3eff9a1d37591a132b369de0b8c6c8c7/src/main/java/dev/redstudio/alfheim/utils/DeduplicatedLongQueue.java)
  pairs a primitive FIFO with a primitive hash set. An equivalent allocation of
  these **buffers only**, using this test runtime's fastutil, measures 14,160,112
  bytes. This is an initial allocation model, not measured Alfheim gameplay memory.
  Queues/sets may shrink or be replaced during processing.
- [ChunkMixin](https://github.com/Red-Studio-Ragnarok/Alfheim/blob/8035b0cb3eff9a1d37591a132b369de0b8c6c8c7/src/main/java/dev/redstudio/alfheim/mixin/ChunkMixin.java)
  uses vanilla light storage plus a small amount of extra state, including lazy
  boundary flags. It does not store full RGB lighting.

Pulsar's comparison must therefore separate per-world engine/worker costs from
per-chunk light planes, snapshots and metadata. A smaller cold engine does not
prove a smaller total heap. Three independent color components contain more
information than a scalar channel; sharing helps only when data is equal or uniform.

## Implemented changes

1. Share immutable equal RGB/canonical planes and bounded constant planes across
   publications. Reuse repeated input planes across adjacent sky sections. There
   is no global content-interning map that can grow with the world.
2. Reuse immutable canonical planes through copy-on-write SWMR views. State-only
   changes preserve sharing; writes detach without mutating or pooling snapshot
   bytes. Existing mutable vanilla wrappers retain their original behavior.
3. Allocate BFS queues on first use at the same initial capacity as before. Keep
   their existing growth/reuse policy. Omit client-only bounds arrays on servers.
4. Allocate contextual sample/miss maps and tracking sets only when used. Static
   chunks no longer eagerly allocate empty hash tables and RGB position arrays.

## Method

Baseline: Pulsar `6bfe474`, before these changes. Java 25, 512 MiB maximum heap,
compressed references, three fresh JVMs per memory run. A test-only Java agent
uses `Instrumentation.getObjectSize`; graph walking counts referenced objects
once, excluding external World, Chunk, IBlockState, Thread, Class and enum roots.
This is **not** an exclusive retained-size/dominator analysis or process RSS.
Shared planes reached from the measured roots are counted once per graph.
Static cache scaffolding is excluded; the new constant cache is bounded to 16
planes (32 KiB of byte payload plus small headers), with lazy population.

Snapshot cases hold 256 chunks with 18 light sections each. Gray channels contain
equal gradients in different input arrays; colored gradients have distinct RGB
components with red dominating the canonical maximum. Flat means uniform level 7.
These are controlled data layouts, not a claim about their frequency in a modpack.

| Owned structure | Before, bytes | After, bytes | Scope |
| --- | ---: | ---: | --- |
| Scalar engine pair, 16 sections | 158,144 | 19,008 | Before first propagation |
| RGB engine pair, 16 sections | 248,120 | 39,400 | Before first propagation; optional sky channels not yet instantiated |
| Scalar engine pair, 64 sections | 204,176 | 45,840 | Before first propagation |
| RGB engine pair, 64 sections | 343,352 | 105,832 | Before first propagation |
| Gray RGB snapshots, 256 chunks | 38,153,232 | 9,620,496 | Snapshot planes/headers only |
| Colored RGB snapshots, 256 chunks | 38,153,232 | 28,642,320 | Snapshot planes/headers only |
| Uniform level-7 snapshots, 256 chunks | 38,153,232 | 111,648 | Shared constant payload |
| Full-white sky snapshots, 256 chunks | 111,648 | 111,648 | Already shared before this change |
| Scalar sample metadata, 4,096 static chunks | 1,507,904 | 623,184 | External chunk objects excluded |
| RGB sample metadata, 4,096 static chunks | 2,032,176 | 623,168 | Where a positional-RGB manager exists |

Active engines still allocate their queues. Do not apply the cold-engine reduction
percentage to steady-state active memory. Working-byte pools and large queue
retention after a burst were deliberately left unchanged to avoid unmeasured
allocation/throughput tradeoffs.

After plane deduplication, comparing canonical views of the same 256 snapshots:

| Layout | Defensive-copy canonical views + snapshots | Copy-on-write views + snapshots |
| --- | ---: | ---: |
| Gray gradients | 19,339,320 bytes | 9,828,408 bytes |
| Colored gradients | 38,361,144 bytes | 28,850,232 bytes |

These rows isolate the additional canonical-view improvement; they must not be
added to the snapshot reductions as if they were separate whole-world measurements.

## Publication performance and allocation

Each JVM warms up 300 publications, then takes seven batches of 256 publications.
Below is the median of the three JVM batch medians. Allocations use the current
thread's allocated-byte counter. Publication inputs have no previous snapshot,
representing initial capture; ordinary unchanged publications retain previous planes.

| Layout | Before time | After time | Before allocated bytes | After allocated bytes |
| --- | ---: | ---: | ---: | ---: |
| Empty | 0.211 ms | 0.151 ms | 206,848 | 108,544 |
| Gray | 15.943 ms | 1.331 ms | 47,761,408 | 9,619,456 |
| Colored | 15.187 ms | 13.563 ms | 47,761,408 | 38,152,192 |
| Flat level 7 | 14.796 ms | 1.511 ms | 47,761,408 | 108,544 |
| Full-white sky | 4.293 ms | 1.553 ms | 206,848 | 108,544 |

An indicative existing contextual-source benchmark (68,352 samples/tick, polling
budget relaxed for the benchmark) measured median 13.825 ms before metadata
laziness and 10.811 ms after, with the same samples/tick. This is not an FPS/TPS
claim, and no cross-machine timing threshold is enforced in CI.

## Reproduction and next comparison

```text
gradlew test --tests "*MemoryFootprintTest" -PpulsarMemoryBenchmark=true
gradlew build -PpulsarGpuTests=true
```

Regression tests cover immutable input/output isolation, full/zero/hidden/null
transitions, canonical writes, save during an unpublished write, RGB projection,
palette/network persistence, worker publication, and first-use queue growth.

For the later game comparison, use separate copies of the same world with the same
Cleanroom/JDK, JVM flags, view distance, height range, dimension count and other mods.
Run Alfheim, Pulsar SCALAR and Pulsar RGB separately. Record heap after warm-up and
after bursts, allocation rate, tick/frame percentiles and lighting backlog during:
idle exploration, colored-source edits, large edits, chunk unload/reload and extended
height. Include worker pools, vanilla light arrays, renderer snapshots and shader
buffers. Only that comparison can establish the requested whole-game memory target.
