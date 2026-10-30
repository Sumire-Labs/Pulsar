# Controlled implementation candidates

40 independent JVM launches, four per engine/variant/workload. Same native Lightbench commands, default automatic10 shared workers, copied worlds, artifact assertions and full light-volume qualification as the preceding studies. Profiling is disabled. Primary values are medians of four per-run means; ranges and percentiles are retained in CSV/JSON.

## Native completion

| Workload | Stock Pulsar µs | Direction mask µs | Late partner µs | Both µs | Starlight µs |
|---|---:|---:|---:|---:|---:|
| blocks/block_place | 69.56 | 70.90 | 64.86 | 68.45 | 59.35 |
| blocks/block_remove | 68.15 | 70.12 | 66.60 | 70.17 | 69.69 |
| sky/sky_remove | 652.47 | 623.25 | 617.82 | 624.44 | 591.45 |
| sky/sky_place | 648.54 | 622.26 | 613.41 | 629.16 | 649.32 |

## Factor contrasts

Negative is shorter completion. Per repetition: mask effect averages mask−stock and both−late; late effect averages late−stock and both−mask. Interaction=both−mask−late+stock. This accounts for measured implementation changes including JIT/layout effects, not just an instruction count.

| Workload | Mean mask effect µs | Mean late effect µs | Mean interaction µs | Mean combined change µs |
|---|---:|---:|---:|---:|
| blocks/block_place | +2.74 | -2.50 | +2.72 | +0.24 |
| blocks/block_remove | +2.80 | -0.04 | +2.28 | +2.76 |
| sky/sky_remove | -17.24 | -8.35 | +52.18 | -25.59 |
| sky/sky_place | +0.47 | -7.25 | +54.62 | -6.78 |

Four rotated condition orders are in protocol.json; no confidence interval or complete five-condition order balance is implied. Timings from other epochs are not subtracted. Native before/after light volumes and every edit probe passed; unit tests179 include concurrency, stop/arrival/future cases and exhaustive direction equivalence. These two fixtures alone are not a complete modpack/regression qualification; dense edits and generation should be checked before adopting a winner.

[Raw JSON and SHA256 checksums](raw-data.zip), [protocol](protocol.json), [manifest](manifest-final.json), [analysis/reproduction scripts](reproduction/).
