# Lightbench light-update comparison

All 6 result files passed schema, fixed-protocol, raw-sample and per-edit correctness validation. Strict comparison found matching plans, seed, dimension, server/runtime environment, world settings, controlled preflight, config fingerprint and non-engine mods.

- Mode: `updates`
- Seed: `1`
- Dimension: `0`
- Controlled platform: 64x64 stone blocks at y=254
- Warmup: 20 pairs per workload
- Measured samples: 200 per phase
- Primary metric: completion time from immediately before the block edit until the engine-specific server-light completion barrier returns
- Engine mod IDs excluded from mod-list equality: `pulsar`, `alfheim`
- Aggregate medians use Lightbench's nearest-rank definition across runs.

## Engine summary

### `sky_remove`

| Engine | Runs | Median completion p50 (ms) | p50 range (ms) | Median p95 (ms) | Median p99 (ms) | vs vanilla |
|---|---:|---:|---:|---:|---:|---:|
| alfheim/alfheim | 3 | 9.674 | 9.508–9.712 | 10.082 | 10.176 | n/a |
| pulsar/pulsar | 3 | 0.621 | 0.597–0.638 | 1.127 | 1.452 | n/a |

### `sky_place`

| Engine | Runs | Median completion p50 (ms) | p50 range (ms) | Median p95 (ms) | Median p99 (ms) | vs vanilla |
|---|---:|---:|---:|---:|---:|---:|
| alfheim/alfheim | 3 | 15.822 | 15.766–15.883 | 16.478 | 16.779 | n/a |
| pulsar/pulsar | 3 | 0.617 | 0.606–0.624 | 1.322 | 1.709 | n/a |

### `block_place`

| Engine | Runs | Median completion p50 (ms) | p50 range (ms) | Median p95 (ms) | Median p99 (ms) | vs vanilla |
|---|---:|---:|---:|---:|---:|---:|
| alfheim/alfheim | 3 | 0.167 | 0.157–0.175 | 0.177 | 0.200 | n/a |
| pulsar/pulsar | 3 | 0.072 | 0.072–0.077 | 0.220 | 0.587 | n/a |

### `block_remove`

| Engine | Runs | Median completion p50 (ms) | p50 range (ms) | Median p95 (ms) | Median p99 (ms) | vs vanilla |
|---|---:|---:|---:|---:|---:|---:|
| alfheim/alfheim | 3 | 0.272 | 0.259–0.278 | 0.289 | 0.308 | n/a |
| pulsar/pulsar | 3 | 0.077 | 0.076–0.080 | 0.101 | 0.187 | n/a |

## Individual phase results

| Run | Source file | Engine | Phase | Completion p50 (ms) | Completion p95 (ms) | Completion p99 (ms) | Completion max (ms) | Submission p50 (ms) | Barrier p50 (ms) |
|---|---|---|---|---:|---:|---:|---:|---:|---:|
| alfheim/alfheim-1 | 20260906-192512-644Z-updates-alfheim-dim0.json | alfheim/alfheim | sky_remove | 9.674 | 10.082 | 10.176 | 10.351 | 0.020 | 9.653 |
| alfheim/alfheim-1 | 20260906-192512-644Z-updates-alfheim-dim0.json | alfheim/alfheim | sky_place | 15.822 | 16.218 | 16.332 | 16.440 | 0.014 | 15.804 |
| alfheim/alfheim-1 | 20260906-192512-644Z-updates-alfheim-dim0.json | alfheim/alfheim | block_place | 0.175 | 0.222 | 0.265 | 0.318 | 0.001 | 0.174 |
| alfheim/alfheim-1 | 20260906-192512-644Z-updates-alfheim-dim0.json | alfheim/alfheim | block_remove | 0.278 | 0.329 | 0.363 | 0.403 | 0.001 | 0.278 |
| alfheim/alfheim-2 | 20260906-193439-970Z-updates-alfheim-dim0.json | alfheim/alfheim | sky_remove | 9.712 | 10.236 | 10.499 | 11.423 | 0.018 | 9.692 |
| alfheim/alfheim-2 | 20260906-193439-970Z-updates-alfheim-dim0.json | alfheim/alfheim | sky_place | 15.766 | 16.529 | 16.779 | 19.435 | 0.013 | 15.745 |
| alfheim/alfheim-2 | 20260906-193439-970Z-updates-alfheim-dim0.json | alfheim/alfheim | block_place | 0.167 | 0.177 | 0.194 | 0.228 | 0.000 | 0.166 |
| alfheim/alfheim-2 | 20260906-193439-970Z-updates-alfheim-dim0.json | alfheim/alfheim | block_remove | 0.272 | 0.289 | 0.307 | 0.331 | 0.000 | 0.271 |
| alfheim/alfheim-3 | 20260906-193634-728Z-updates-alfheim-dim0.json | alfheim/alfheim | sky_remove | 9.508 | 9.718 | 10.130 | 10.307 | 0.017 | 9.488 |
| alfheim/alfheim-3 | 20260906-193634-728Z-updates-alfheim-dim0.json | alfheim/alfheim | sky_place | 15.883 | 16.478 | 17.276 | 17.604 | 0.015 | 15.866 |
| alfheim/alfheim-3 | 20260906-193634-728Z-updates-alfheim-dim0.json | alfheim/alfheim | block_place | 0.157 | 0.170 | 0.200 | 0.209 | 0.000 | 0.156 |
| alfheim/alfheim-3 | 20260906-193634-728Z-updates-alfheim-dim0.json | alfheim/alfheim | block_remove | 0.259 | 0.278 | 0.308 | 0.322 | 0.000 | 0.258 |
| pulsar/pulsar-1 | 20260906-192736-870Z-updates-pulsar-dim0.json | pulsar/pulsar | sky_remove | 0.621 | 1.217 | 1.495 | 1.564 | 0.014 | 0.605 |
| pulsar/pulsar-1 | 20260906-192736-870Z-updates-pulsar-dim0.json | pulsar/pulsar | sky_place | 0.606 | 1.346 | 1.614 | 1.721 | 0.014 | 0.590 |
| pulsar/pulsar-1 | 20260906-192736-870Z-updates-pulsar-dim0.json | pulsar/pulsar | block_place | 0.072 | 0.220 | 0.582 | 1.069 | 0.004 | 0.067 |
| pulsar/pulsar-1 | 20260906-192736-870Z-updates-pulsar-dim0.json | pulsar/pulsar | block_remove | 0.076 | 0.101 | 0.187 | 0.222 | 0.004 | 0.070 |
| pulsar/pulsar-2 | 20260906-192942-472Z-updates-pulsar-dim0.json | pulsar/pulsar | sky_remove | 0.597 | 1.127 | 1.452 | 1.703 | 0.014 | 0.581 |
| pulsar/pulsar-2 | 20260906-192942-472Z-updates-pulsar-dim0.json | pulsar/pulsar | sky_place | 0.624 | 1.276 | 1.845 | 2.562 | 0.013 | 0.610 |
| pulsar/pulsar-2 | 20260906-192942-472Z-updates-pulsar-dim0.json | pulsar/pulsar | block_place | 0.072 | 0.231 | 0.732 | 1.326 | 0.004 | 0.067 |
| pulsar/pulsar-2 | 20260906-192942-472Z-updates-pulsar-dim0.json | pulsar/pulsar | block_remove | 0.077 | 0.107 | 0.216 | 0.245 | 0.004 | 0.071 |
| pulsar/pulsar-3 | 20260906-193829-376Z-updates-pulsar-dim0.json | pulsar/pulsar | sky_remove | 0.638 | 0.960 | 1.226 | 1.557 | 0.014 | 0.620 |
| pulsar/pulsar-3 | 20260906-193829-376Z-updates-pulsar-dim0.json | pulsar/pulsar | sky_place | 0.617 | 1.322 | 1.709 | 1.838 | 0.014 | 0.600 |
| pulsar/pulsar-3 | 20260906-193829-376Z-updates-pulsar-dim0.json | pulsar/pulsar | block_place | 0.077 | 0.202 | 0.587 | 0.944 | 0.005 | 0.071 |
| pulsar/pulsar-3 | 20260906-193829-376Z-updates-pulsar-dim0.json | pulsar/pulsar | block_remove | 0.080 | 0.092 | 0.123 | 0.222 | 0.006 | 0.074 |

## Run-level observations

| Run | Validation-inclusive GC collections | Validation-inclusive GC time (ms) | Validation-inclusive Pulsar worker CPU (ms) |
|---|---:|---:|---:|
| alfheim/alfheim-1 | 1 | 4 | n/a |
| alfheim/alfheim-2 | 1 | 3 | n/a |
| alfheim/alfheim-3 | 1 | 3 | n/a |
| pulsar/pulsar-1 | 0 | 0 | 281.250 |
| pulsar/pulsar-2 | 0 | 0 | 437.500 |
| pulsar/pulsar-3 | 0 | 0 | 343.750 |

Completion is the primary cross-engine metric. Submission and barrier columns are diagnostics: engines divide the same end-to-end interval differently, and these six runs contain only Alfheim and Pulsar. The v1.0.6 update adapter also drains Vanilla deferred sky-gap ticks; no Vanilla update run passed validation. GC and worker CPU are observations, not compatibility conditions. The CSV beside this file contains raw-derived nanosecond metrics for every phase. Aggregates are descriptive summaries, not confidence intervals; keep the validated JSON files and individual runs when publishing results.

CPU replay windows are separate from latency sampling: 200 sky pairs and 5000 block pairs, including every completion barrier. Full-volume verification occurs only before and after the window. Combined CPU is server plus lighting-worker CPU, not whole-process CPU. Windows are repeated in the CSV's two matching phase rows and must not be summed twice.

| Run | Replay | Wall ms | Server CPU ms | Worker CPU ms | Combined CPU ms |
|---|---|---:|---:|---:|---:|
| alfheim/alfheim-1 | sky_pairs | 4702.657 | 4640.625 | 0.000 | 4640.625 |
| alfheim/alfheim-1 | block_pairs | 2217.319 | 2218.750 | 0.000 | 2218.750 |
| alfheim/alfheim-2 | sky_pairs | 4860.951 | 4828.125 | 0.000 | 4828.125 |
| alfheim/alfheim-2 | block_pairs | 2185.056 | 2187.500 | 0.000 | 2187.500 |
| alfheim/alfheim-3 | sky_pairs | 4704.946 | 4640.625 | 0.000 | 4640.625 |
| alfheim/alfheim-3 | block_pairs | 2027.005 | 2031.250 | 0.000 | 2031.250 |
| pulsar/pulsar-1 | sky_pairs | 256.820 | 0.000 | 265.625 | 265.625 |
| pulsar/pulsar-1 | block_pairs | 682.017 | 93.750 | 765.625 | 859.375 |
| pulsar/pulsar-2 | sky_pairs | 271.413 | 0.000 | 265.625 | 265.625 |
| pulsar/pulsar-2 | block_pairs | 680.228 | 78.125 | 656.250 | 734.375 |
| pulsar/pulsar-3 | sky_pairs | 317.674 | 15.625 | 296.875 | 312.500 |
| pulsar/pulsar-3 | block_pairs | 685.787 | 31.250 | 687.500 | 718.750 |
