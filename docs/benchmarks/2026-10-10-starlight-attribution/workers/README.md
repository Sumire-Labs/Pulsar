# Worker-count control: unchanged Pulsar versus Starlight-1.12

32 formal JVMs, full stored-light validation and actual live worker-count checks. The primary latency comparison uses unmodified original engine binaries with profiling disabled, four fresh JVMs per condition. The only Pulsar setting difference is automatic10 shared workers versus1 shared worker. Supplemental sampled stages use two JVMs per setting and are exploratory.

## Native completion, four independent runs

| Workload | Pulsar auto10 µs | Pulsar1 µs | Starlight µs | P1 vs P10 |
|---|---:|---:|---:|---:|
| blocks/block_place | 70.57 | 74.84 | 58.45 | +6.06% |
| blocks/block_remove | 69.74 | 67.33 | 70.19 | -3.46% |
| sky/sky_remove | 640.92 | 613.00 | 584.33 | -4.36% |
| sky/sky_place | 630.43 | 613.39 | 648.32 | -2.70% |

Values are medians of per-run means; all run means/ranges and per-run percentiles are retained in CSV/JSON. This tuning experiment does not change the default -1 setting and does not measure multi-player or spatially independent bulk throughput. Starlight ignores the supplied Pulsar configuration file. Do not generalize one worker as best for every world/workload.

## Supplemental stages: n2, sampled diagnostic edits

Both settings use the identical instrumented Pulsar binary, recorder and selected edit indices. These values locate a potential setting effect; they are not a replacement for the uninstrumented n4 latency comparison. Wall time includes preemption and probe effects.

### blocks/block_place

| Metric | Auto10 | One worker |
|---|---:|---:|
| setup_nanos (µs) | 1.62 | 1.52 |
| propagation_nanos (µs) | 64.22 | 61.22 |
| publish_nanos (µs) | 1.07 | 1.04 |
| flood_nanos (µs) | 63.96 | 60.94 |
| positions/edit | 1.05 | 1.09 |
| increase_entries/edit | 1925.56 | 1997.99 |
| decrease_entries/edit | 113.58 | 202.64 |
| tasks/edit | 1.05 | 1.09 |
| engines/edit | 1.05 | 1.09 |

### sky/sky_remove

| Metric | Auto10 | One worker |
|---|---:|---:|
| setup_nanos (µs) | 2.64 | 1.99 |
| propagation_nanos (µs) | 649.87 | 592.76 |
| publish_nanos (µs) | 2.93 | 2.59 |
| flood_nanos (µs) | 645.18 | 589.59 |
| positions/edit | 1.29 | 1.08 |
| increase_entries/edit | 21191.71 | 21178.87 |
| decrease_entries/edit | 1.29 | 1.08 |
| tasks/edit | 1.29 | 1.08 |
| engines/edit | 1.29 | 1.08 |

## Scope and reproduction

Same seed1 copied worlds, Minecraft1.12.2/Cleanroom0.6.12, Java25.0.4.1/G1/fixed8GiB/compact headers and fixed graphics/common mods as the matched-stage study. Blocks:5000 warmup/10000 measured pairs; Sky:2000/1000. A fresh JVM/world copy per condition. Default Version2 config retained; only `features.experimentalServerLightThreads` changes from -1 (observed10 workers) to1. No algorithm or functional code changes.

The raw observer counts live threads named Pulsar-Parallel-; every Pulsar run must report1 or10 as intended. All initial/final stored-light volumes, per-edit probes, selected/native timing matches and exact discarded warmup capture counts must pass. Main-thread MSPT, presented FPS and engine-only memory are not measured by this experiment.

[Raw JSON and SHA256 checksums](raw-data.zip), [protocol](protocol.json), [manifest](manifest-final.json), [analysis/reproduction scripts](reproduction/).
