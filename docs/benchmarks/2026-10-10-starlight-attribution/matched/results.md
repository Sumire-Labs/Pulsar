# Pulsar / Starlight-1.12 matched pipeline study

Four independent JVMs per engine/condition/workload; 48 formal launches. Original, probe-disabled and sampled-probe conditions share one harness/common-mod set. Both engines use identical deterministic sample indices. Per-cell loops have no timestamps; data buffers are allocated before warmup and exported after measurement. Values are medians of independent per-run means, not pooled edits or confidence intervals.

## Native completion and probe controls

| Workload | Engine | Original µs | Disabled µs | Sampled on µs | On vs disabled |
|---|---|---:|---:|---:|---:|
| blocks/block_place | pulsar | 73.74 | 73.67 | 73.96 | +0.40% |
| blocks/block_place | starlight | 61.89 | 60.80 | 59.82 | -1.61% |
| blocks/block_remove | pulsar | 71.22 | 71.37 | 71.87 | +0.70% |
| blocks/block_remove | starlight | 73.71 | 71.77 | 73.01 | +1.74% |
| sky/sky_remove | pulsar | 670.41 | 661.87 | 693.16 | +4.73% |
| sky/sky_remove | starlight | 598.49 | 641.65 | 593.19 | -7.55% |
| sky/sky_place | pulsar | 661.93 | 650.18 | 681.59 | +4.83% |
| sky/sky_place | starlight | 665.46 | 714.24 | 664.29 | -6.99% |

## Captured-edit probe perturbation

Overall overhead is diluted because only about1/16 edits are captured. Therefore, we separately estimate capture perturbation as (on selected − on others) − (disabled selected − disabled others), using the same edit-index selection. This is an observational contrast, not a guaranteed correction factor for individual stages.

| Workload | Engine | Median capture excess µs | Run min–max µs |
|---|---|---:|---:|
| blocks/block_place | pulsar | 1.45 | 1.39–3.31 |
| blocks/block_place | starlight | 0.69 | 0.48–0.98 |
| sky/sky_remove | pulsar | -10.50 | -26.49–18.27 |
| sky/sky_remove | starlight | 20.13 | 9.84–25.40 |

## Matched engine stages

Each lane is shown separately; lane times and main-thread submission can overlap. prepare/flood are nested inside propagation. Do not sum every row into native completion time. Stage clocks measure wall time and may include OS preemption.

### blocks/block_place: block lane

| Stage | Pulsar µs/edit | Starlight µs/edit | Pulsar − Starlight µs |
|---|---:|---:|---:|
| setup_nanos | 1.53 | 1.21 | +0.31 |
| sections_nanos | 0.05 | 0.02 | +0.03 |
| propagation_nanos | 62.57 | 53.77 | +8.80 |
| publish_nanos | 1.04 | 0.91 | +0.14 |
| cleanup_nanos | 0.48 | 0.32 | +0.16 |
| prepare_nanos | 0.14 | 0.09 | +0.05 |
| flood_nanos | 62.33 | 53.58 | +8.74 |

| Captured edit / work | Pulsar | Starlight |
|---|---:|---:|
| block_positions per edit | 1.04 | 2.00 |
| block_increase_entries per edit | 1911.90 | 1835.00 |
| block_decrease_entries per edit | 96.78 | 2.00 |
| block_engines per edit | 1.04 | 1.00 |
| before_first_engine_nanos (µs) | 2.99 | 0.45 |
| engine_span_nanos (µs) | 71.49 | 60.15 |
| after_last_engine_nanos (µs) | 0.81 | 0.13 |

The three engine-envelope intervals are non-overlapping per edit. The middle span includes both lanes and any gaps/coordination; it is not pure propagation CPU. Separate medians need not add exactly.

### sky/sky_remove: sky lane

| Stage | Pulsar µs/edit | Starlight µs/edit | Pulsar − Starlight µs |
|---|---:|---:|---:|
| setup_nanos | 2.58 | 1.31 | +1.27 |
| sections_nanos | 0.13 | 0.03 | +0.10 |
| propagation_nanos | 661.34 | 581.74 | +79.60 |
| publish_nanos | 3.24 | 2.23 | +1.01 |
| cleanup_nanos | 0.81 | 0.38 | +0.43 |
| prepare_nanos | 4.12 | 3.05 | +1.07 |
| flood_nanos | 656.68 | 578.35 | +78.33 |

| Captured edit / work | Pulsar | Starlight |
|---|---:|---:|
| sky_positions per edit | 1.23 | 252.00 |
| sky_increase_entries per edit | 21188.17 | 21355.00 |
| sky_decrease_entries per edit | 1.23 | 252.00 |
| sky_engines per edit | 1.23 | 1.00 |
| before_first_engine_nanos (µs) | 8.96 | 10.54 |
| engine_span_nanos (µs) | 675.56 | 599.16 |
| after_last_engine_nanos (µs) | 1.47 | 0.20 |

The three engine-envelope intervals are non-overlapping per edit. The middle span includes both lanes and any gaps/coordination; it is not pure propagation CPU. Separate medians need not add exactly.

## Additive decomposition of the captured completion gap

Because runs reject overlapping engine writers, the two lanes’ five engine stages can be summed without double-counting engine intervals. Native completion minus this sum is the non-engine residual: queue/coordination, caller work, gaps and probe bookkeeping. This table uses the **arithmetic mean of four per-run captured means**, so component differences add exactly to the captured completion difference; other tables use medians. It is diagnostic wall-time accounting, not a correction for probe overhead or proof of a specific instruction-level cause.

### blocks/block_place

| Component | Pulsar µs | Starlight µs | P − S µs |
|---|---:|---:|---:|
| setup | 3.22 | 2.48 | +0.74 |
| sections | 0.11 | 0.04 | +0.07 |
| propagation | 65.80 | 55.52 | +10.28 |
| publish | 1.72 | 1.34 | +0.39 |
| cleanup | 1.16 | 0.67 | +0.49 |
| non_engine | 5.70 | 0.71 | +4.99 |
| selected_completion | 77.72 | 60.77 | +16.95 |
### sky/sky_remove

| Component | Pulsar µs | Starlight µs | P − S µs |
|---|---:|---:|---:|
| setup | 4.59 | 2.95 | +1.64 |
| sections | 0.23 | 0.05 | +0.18 |
| propagation | 654.00 | 591.29 | +62.72 |
| publish | 4.00 | 4.23 | -0.23 |
| cleanup | 1.30 | 0.74 | +0.57 |
| non_engine | 15.73 | 11.35 | +4.38 |
| selected_completion | 679.85 | 610.60 | +69.25 |

## Methods, provenance and scope

A fixed last75%-of-measured-indices sensitivity is retained in summary metrics (`late_*`) for all conditions and sampled stages. The cutoff was selected before inspecting numeric results, not tuned to a winning outcome. Primary tables retain all measured pairs. Early/late differences can reflect JIT, frequency, cache or other effects and do not identify a cause by themselves.

The sampled recording path also executes during warmup. Every run must report the exact expected positive warmup capture count, fully drain it, clear its preallocated records and discard it before measured pairs. The preceding cold-sampler epoch was retained separately and is not pooled here. Active warmup reduces first-entry effects but does not prove perfect steady state; captured perturbation and run ranges remain necessary.

- Same seed1 disposable worlds, fixed floorY3, flags16, 5000/10000 block warmup/measured pairs and2000/1000 roof-profile pairs. Sky roofY254,64×64; independent initial/final full Sky/Block volume checks and per-edit probes. Each sampled/native latency is exactly matched by phase/index.
- Minecraft1.12.2/Cleanroom0.6.12, AdoptiumJava25.0.4.1/G1/fixed8GiB/compact headers, RyzenAI MAX+395/32 logical cores, Radeon8060S,1280×720/renderDistance4. Pulsar defaultConfigVersion2/-1 means10 shared workers. Settings/common mods are frozen. No external profiler agent.
- Pulsar original source75b051d; current documentation-only source33d81bb has the same runtime source. Starlight0a7d94e uses the same manifest-cleaned original binary from the prior study. Only three engine source files and the resulting nested direction class differ in the Star probe binary;29 other original Java classes are byte-identical. No GPL reference source is integrated into Pulsar.
- Starlight stages run synchronously on the server caller; Pulsar uses its existing shared scheduler. The probe records server engines only and rejects overlapping recording writers rather than silently accepting unsafe plain-buffer aggregation. It does not change lighting policy, scheduling or queue coalescing.
- The four engine-condition orders are recorded in the protocol; they are the first four rows of a six-condition Williams design, not a complete six-repetition balance. Fresh JVM/world per run. Main-thread sleep/worker CPU/client mesh/network/presentation are separate scopes.
- New measurements must be interpreted within this epoch; do not subtract these stage times from older62µs/55µs totals. Differences in stage time identify where time is spent, but a causal claim that a particular feature causes the gap needs an ablation or controlled implementation change.

[Raw JSON and SHA256 checksums](raw-data.zip), [protocol](protocol.json), [manifest](manifest-final.json), [analysis/reproduction scripts](reproduction/).
