# Server light publication batching: rejected experiment

An independent Pulsar prototype grouped dirty server light sections by chunk, holding the chunk monitor and checking registration once per chunk instead of once per section. No Lucis code was copied. Client publication and propagation algorithms were unchanged.

**Decision: retain the existing production implementation.** The prototype passed correctness checks but did not demonstrate a speed improvement. All four update medians increased by about 4–5%, exceeding the predeclared 3% regression guard. Run ranges overlap; these observations do not establish a universal or statistically significant slowdown.

## Update completion latency

Values are medians of four independent JVM run means, with 10,000 warmup pairs and 10,000 measured pairs per workload. Completion includes the installed engine adapter and waiting behavior. Lower is better.

| Phase | Released 0.5.0 (µs) | Prototype (µs) | Prototype change |
|---|---:|---:|---:|
| Block placement | 83.39 | 87.04 | +4.38% |
| Block removal | 82.38 | 86.43 | +4.91% |
| Sky opening | 706.05 | 733.34 | +3.86% |
| Sky closing | 723.14 | 753.58 | +4.21% |

[Run ranges and summaries](summary.csv) retain the min/max of independent run means. These new measurements form their own timing cohort; do not subtract October 12 publication values to attribute the prototype.

## Dense-edit stress observations

Two JVM runs per variant completed the paced edit test and the 64/128/256/512/1024/2048 edits-per-tick sweep. These are stress checks with descriptive timings, not an equally replicated performance claim. Main-thread MSPT excludes worker CPU; post-run drain is separate.

| Metric | Released 0.5.0 (ms) | Prototype (ms) |
|---|---:|---:|
| 2,048 edits/tick: main-thread MSPT | 3.466 | 3.715 |
| Final light drain | 14.104 | 16.514 |

## Correctness and scope

- 184 unit tests passed, including four new publication/liveness tests: extended-height section publication, unloaded/replaced chunk rejection, exclusion of concurrent unload during a chunk batch, and unchanged client publication order.
- 17 fresh-JVM game launches completed: one candidate pilot and 16 measured/validation runs. All selected checks passed. Update trials validate every measured edit plus independent initial/final light volumes. Dense trials require all requested ticks/edits, no wall cap, and roof/light verification.
- Generation audits matched all 289 scored core chunks in terrain and both-channel light hashes.
- Shape audits matched 15 cases and 45 light-volume snapshots, including sided stairs/slabs and transparent blocks.
- No change was adopted in production; the prototype and its test source are retained solely for reproduction. Pulsar remains LGPL-3.0.

## Inputs and procedure

Measured baseline: release `0.5.0`, source `5ab6bb3`. Candidate: the same source plus [implementation.patch](reproduction/implementation.patch). Minecraft 1.12.2, Cleanroom 0.6.12-alpha, Adoptium Java 25.0.4.1, fixed 8 GiB G1 heap, compact headers, render distance 4, default Config Version 2 and automatic 10 shared workers. All launches use the same offline player and fresh copies of the same seed-1 flat template. No diagnostic ownership helpers or profiler are attached. This study does not measure Cleanroom 0.7.0 performance.

Orders were baseline→candidate, candidate→baseline, candidate→baseline, baseline→candidate for updates; the first two orders for continuous editing. Primary acceptance required repeatable ≥2% update or ≥5% dense-load improvement without >3% regression in another update phase, plus all correctness gates.

The ideas reviewed against Lucis were already largely present: `LightQueue` deduplicates per-chunk positions, `LightInfo.requiresBlockChange` skips equal static light descriptors, contextual sampling selects changed light lanes, SWMR data publishes only dirty values, and `ClientRenderUpdates` coalesces compatible notifications. The additional prototype tested chunk-level grouping of server publication; it was rejected on measured performance.

[Protocol](protocol.json), [artifact provenance](artifact-proof.json), [validation/metrics](results.json), [raw-data bundle](raw-data.zip), [delivered-file checksums](SHA256SUMS.json).

For replay, obtain the release baseline, build the same source with the patch using JDK25, then run the saved protocol on fresh disposable worlds. The raw bundle includes run reports and local orchestration sources, but no Minecraft saves, engine binaries, account data or full game logs. Source hashes identify artifacts; byte-identical builds are not guaranteed across platforms.

The native Lightbench comparator independently accepted each four-run update cohort. All 32 native per-phase rows match the independent aggregator: [validation details](validation.json), [baseline CSV](native/baseline/comparison.csv), [prototype CSV](native/candidate/comparison.csv). Local orchestration paths require adaptation, and frozen Minecraft template worlds are not distributed.
