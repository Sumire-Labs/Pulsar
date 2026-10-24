# Filter-free skylight evidence

See [the implementation report](../rgb-white-skylight.md).

- `validation.json`: native sky, boundary, palette, cache, block, SCALAR and Mod
  regression checks, plus the JUnit result.
- `timing.json`: all independent run means, GC deltas and complete ranges.
- `protocol.json`: runtime settings and counterbalanced run order.
- `artifacts.json`: baseline/candidate hashes and the tested source hashes.
- `raw-data.zip`: full per-edit samples, diagnostic sources and replay scripts.
- `SHA256SUMS.json`: companion-file hashes with stable LF endings.

The baseline is `0a291d9`, including its optimized missing-section resolver.
Both variants use the same MC 1.12.2/Cleanroom 0.6.12-alpha/Java 25 setup,
8 GiB heap, automatic 10-worker pool, seed-1 flat worlds and frozen options.
The main cohort runs four fresh JVMs per version/mode. A separate filtered
cohort runs two per version. Each JVM warms 2,000 edit pairs and measures 1,000
pairs per workload. Correctness helpers are absent from timed runs. No build,
test or archive work runs concurrently with formal timing.

The filtered timing fixture places one red stained-glass block at
`(20032,255,20032)`: within the eligibility halo but outside the checked air
volume and measured hole's light frontier. It forces the full colored path
without changing the canonical verification workload. Every measured edit and
initial/final stored-light volume must still pass the unchanged Lightbench
checks. Timing measures server edit-to-lighting completion; it does not measure
FPS, network delivery or total game heap.

`SkyAudit.java` retains the independent four-field sky reference and adds native
palette mutation checks and the outer-ring scene. The scene verifies a point
under an opaque roof, lit by a red opening three chunks from a white relight.
Before and after that relight, server/client RGB must be `E00` while canonical
sky remains 14. Actual client roof geometry must match, and the eligible job
must release its mutable color storage. Range supersession remains tested.

Scripts use owned disposable-instance paths that must be adapted for replay.
Compile and remap diagnostic sources against the matching production API and
package only their diagnostic namespaces. The runner renames the owned game
directory before staging a fresh instance. Cache fixtures and CQR assets are
reused from the preceding prototype studies; jars, worlds, account files and
third-party assets are not redistributed in this archive.
