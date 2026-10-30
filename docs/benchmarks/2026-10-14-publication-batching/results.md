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
