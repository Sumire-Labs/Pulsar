# Pulsar update pipeline diagnostics — October 10, 2026

Four independent JVM runs per condition; 24 formal launches, all workload checks passed. Separate diagnostic copies of Pulsar and Lightbench; original release benchmark inputs/results remain frozen. Values below are medians of four per-run means. These diagnostic runs do not replace the published 0.5.0 comparison.

## Probe overhead controls

| Fixture / action | Original (µs) | Probe build, disabled (µs) | Probe enabled (µs) | Enabled vs disabled | Disabled vs original |
|---|---:|---:|---:|---:|---:|
| blocks/block_place | 74.68 | 72.52 | 82.04 | +13.1% | -2.9% |
| blocks/block_remove | 71.79 | 70.59 | 73.54 | +4.2% | -1.7% |
| sky/sky_remove | 650.04 | 637.96 | 641.73 | +0.6% | -1.9% |
| sky/sky_place | 636.36 | 630.09 | 633.48 | +0.5% | -1.0% |

Both controls use the same diagnostic Lightbench JAR with its bridge disabled. The original Pulsar binary is SHA256 b985bc77f630da1a1f5f403d7bf0f218455e7f3a6a67f0bc88652838de9cffd2. The instrumented binary differs by optional timers only; its pre-tag version metadata differs. The enable flag is the intentional JVM/config difference. Probe overhead includes timestamp reads, atomic aggregation, changed JIT/code layout, per-sample allocation and possible GC/scheduling effects; this experiment cannot isolate those causes. Do not subtract a constant overhead from individual stages.

**Placement probe overhead is about13% in this study, larger than the earlier approximately7µs cross-engine placement gap. The stage breakdown is diagnostic, not a microsecond-accurate reconstruction of uninstrumented Pulsar.** The original binary also ran at a different absolute placement time here than in the earlier published study, despite the same artifact and protocol. Execution-time environmental/JIT variation is not isolated; do not subtract these new stage times from old Starlight totals.

## Per-lane stages, probe enabled

All stage values are wall time in µs per edit, summed over matching lane tasks. Timers can include OS preemption. Queue-to-task-start is a sum across tasks, not an exclusive critical-path slice. The prepare/flood rows are nested inside propagation. Sky and Block can overlap each other and server submission. **Do not add the entire table to obtain completion latency.**

### blocks/block_place

Submission: **1.39 µs**; completion barrier: **80.62 µs**; total completion: **82.04 µs**.

| Worker stage | Sky lane (µs/edit) | Block lane (µs/edit) |
|---|---:|---:|
| Queue creation → task start (sum) | 44.70 | 13.50 |
| Task body, excluding future completion | 6.19 | 69.79 |
| Engine cache setup | 1.89 | 1.62 |
| Section reconciliation | 0.07 | 0.06 |
| Propagation (inclusive) | 2.41 | 66.05 |
| Publish SWMR + vanilla storage | 0.60 | 1.13 |
| Cache cleanup | 0.67 | 0.41 |
| ↳ Column/check preparation | 1.56 | 0.19 |
| ↳ Decrease + increase flood | 0.74 | 65.77 |

| Work / timeline | Sky lane | Block lane |
|---|---:|---:|
| Mean tasks/edit | 1.19 | 1.09 |
| Mean positions/edit | 1.19 | 1.09 |
| Mean increase_entries/edit | 9.95 | 2007.95 |
| Mean decrease_entries/edit | 2.19 | 214.89 |
| first_task_start_offset_nanos (µs from edit start) | 24.83 | 8.30 |
| last_task_end_offset_nanos (µs from edit start) | 50.31 | 78.38 |

Offsets show first task entry and last body end; completion bookkeeping/wakeup follows. Not every lane necessarily receives exactly one task per edit because changes can be queued/claimed separately.

Non-overlapping wall-clock envelope (µs/edit): edit start → first task **4.34**, first task → last body end **76.70**, last body end → caller completion **0.61**. These intervals sum to completion per sample; medians of separate run means need not sum exactly. The middle interval includes any gaps and overlapping main-thread work, so it is not pure propagation or total CPU.

### sky/sky_remove

Submission: **4.43 µs**; completion barrier: **637.24 µs**; total completion: **641.73 µs**.

| Worker stage | Sky lane (µs/edit) | Block lane (µs/edit) |
|---|---:|---:|
| Queue creation → task start (sum) | 53.42 | 386.54 |
| Task body, excluding future completion | 620.51 | 5.85 |
| Engine cache setup | 2.34 | 2.20 |
| Section reconciliation | 0.14 | 0.16 |
| Propagation (inclusive) | 613.24 | 1.11 |
| Publish SWMR + vanilla storage | 2.84 | 0.83 |
| Cache cleanup | 0.71 | 0.47 |
| ↳ Column/check preparation | 4.42 | 0.44 |
| ↳ Decrease + increase flood | 608.17 | 0.17 |

| Work / timeline | Sky lane | Block lane |
|---|---:|---:|
| Mean tasks/edit | 1.06 | 1.03 |
| Mean positions/edit | 1.06 | 1.03 |
| Mean increase_entries/edit | 21177.91 | 0.00 |
| Mean decrease_entries/edit | 1.06 | 1.03 |
| first_task_start_offset_nanos (µs from edit start) | 15.48 | 370.62 |
| last_task_end_offset_nanos (µs from edit start) | 636.07 | 379.34 |

Offsets show first task entry and last body end; completion bookkeeping/wakeup follows. Not every lane necessarily receives exactly one task per edit because changes can be queued/claimed separately.

Non-overlapping wall-clock envelope (µs/edit): edit start → first task **11.69**, first task → last body end **628.22**, last body end → caller completion **1.91**. These intervals sum to completion per sample; medians of separate run means need not sum exactly. The middle interval includes any gaps and overlapping main-thread work, so it is not pure propagation or total CPU.

## Interpretation

In these diagnostic fixtures, propagation is the dominant worker stage: about66µs in the Block lane for placement and613µs in the Sky lane for opening. Publication costs are about1.1µs and2.8µs respectively, so SWMR/vanilla publication is not the largest cost here. Sky column/check preparation is about4.4µs; the subsequent flood is about608µs. This identifies the main work inside the instrumented Pulsar, rather than the cause of the Starlight speed difference.

Single-edit latency also includes asynchronous task start and completion coordination. Multiple tasks can be created for one edit, as the work-count table shows. Those are candidates for a separately controlled investigation; this study does not prove that they are redundant or safely removable.

## Methods and limits

- Blocks: `lightbench blockupdates 5000 10000`, open-sky glowstone; Sky: `lightbench updates 2000 1000`, tall-roof fixture. The roof fixture’s block phases are retained separately. Update flags16. Warmup is not instrumented.
- Same copied worlds, seed1, common mods, Java25.0.4.1/Cleanroom0.6.12, fixed8GiB/G1/compact headers, 1280×720/renderDistance4, default ConfigVersion2 and automatic10 shared workers as the prior study. Only explicit JVM opt-in differs between enabled and disabled diagnostic builds.
- Orders: original→off→on; off→on→original; on→original→off; on→off→original. Four repetitions, not a fully pair-balanced six-order design. Fresh JVM/world per condition.
- Independent complete stored Sky/Block volumes before/after measurement, per-edit checks, native sample count and every diagnostic/native latency matched exactly. Nested timings and task-before-completion ordering were checked. Concurrent lane accumulation, sample reset, overlap guard and disabled behavior passed a separate probe self-test. Existing Pulsar175 and Lightbench60 tests passed in the diagnostic copies.
- The profiler covers the default shared scheduler. It is not a dedicated-lane or client profiler. Per-cell loops have no added timestamp/atomic operation; timers are at task/engine boundaries. Queue timestamp begins at task construction, before insertion finishes; it includes wakeup/claim/partner-lane serialization, and is not pure OS scheduler delay.
- Final engine publication includes SWMR and vanilla nibble synchronization. Client mesh rebuild, network latency and presented FPS are outside this server-light completion metric.
- These measurements locate time inside Pulsar. They do **not** attribute the original roughly7µs placement gap to Starlight without equivalent per-stage Starlight instrumentation. The small Sky-opening gap must also be interpreted against run ranges and probe overhead. No engine algorithm was replaced or optimized during this diagnostic task.

[Run ranges and full summary](summary.csv). Original benchmark: ../2026-10-10-pulsar-0.5.0/README.md. Instrumentation patches, probe source, validator, analysis and raw JSON are included locally. No GPL reference Java is copied into Pulsar or distributed with these results.
