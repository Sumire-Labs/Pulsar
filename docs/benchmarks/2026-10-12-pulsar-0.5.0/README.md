# Pulsar 0.5.0: controlled Lightbench comparison

Formal measurement window (UTC): 2026-10-12T13:43:46.0124708Z through 2026-10-12T16:27:58.8801855Z.

These are measurements on one machine and one fixed setup, not a promise for every world or modpack. Values are medians of **four independent JVM run means**; figure whiskers show the minimum and maximum run mean, not confidence intervals.

## Validated glowstone updates under open sky

| Engine | Place (µs) | Remove (µs) |
|---|---:|---:|
| Vanilla | 1,294 | 1,869 |
| Alfheim 1.6 | 184 | 271 |
| Pulsar 0.5.0 | 66.6 | 68.3 |

Completion time includes the server block edit and the installed engine's light-completion barrier. The fixture is a uniform low Superflat floor with no roof. Each JVM uses 5,000 warmup pairs and 10,000 measured pairs. Sparse checks run after every edit, and independent complete Sky/Block volume checks run before and after measurement. Update flags are 16; this is not player input, network latency or arbitrary neighbor-event throughput.

![Validated block updates](block-latency.png)

## Sky-column stress test

| Engine | Open (ms) | Close (ms) |
|---|---:|---:|
| Vanilla | Not validated | Not validated |
| Alfheim 1.6 | 9.62 | 16.3 |
| Pulsar 0.5.0 | 0.606 | 0.618 |

The 64×64 roof is at Y254 over a floor at Y3. This is a deliberately tall lighting stress test. Vanilla did not satisfy the full stored-light validation protocol and has no comparable score. Normal public chunk maintenance was included for Vanilla; synthetic per-cell repairs were not used to manufacture a passing result.

![Sky column stress test](sky-latency.png)

## Equal-output flat generation

| Engine | Until lit (s) |
|---|---:|
| Vanilla | 1.31 |
| Alfheim 1.6 | 1.18 |
| Pulsar 0.5.0 | 1.34 |

Each measured run submits 5,292 chunks across 12 disjoint regions, including support halos. The 3,468 scored core chunks must have matching terrain and light hashes across qualifying runs. Provisioning, engine completion and explicit finalization are timed; hashing and report writing are outside the timer. This simple flat workload does not represent cave or forest generation.

![Flat generation](flat-generation.png)

## Continuous roof editing

| Edits/tick | Vanilla | Alfheim 1.6 | Pulsar 0.5.0 |
|---:|---:|---:|---:|
| 64 | Not validated | 12.6 | 0.523 |
| 128 | Not validated | 20.6 | 0.854 |
| 256 | Not validated | 37.1 | 0.741 |
| 512 | Not validated | 66.1 | 1.03 |
| 1024 | Not validated | 112 | 1.53 |
| 2048 | Not validated | 178 | 2.48 |

Values are main-thread MSPT over 100 requested ticks per rate. A separate warmup precedes scored spikes and the sweep; initial and final stored light are validated. Capped or incorrect cases are excluded. Async worker CPU is not part of main-thread MSPT, and post-run light-drain time is recorded separately. Lower MSPT alone does not prove less total CPU work or faster visual light convergence.

![Continuous editing](continuous-server-ticks.png)

## Travel, frame observations and memory

| Engine | Flat waypoint readiness (ms) |
|---|---:|
| Vanilla | 57.5 |
| Alfheim 1.6 | 56.6 |
| Pulsar 0.5.0 | 55.4 |

The route has eight warmup and 32 measured waypoints, one chunk apart, with at least 20 ticks of dwell. Readiness covers the loaded 5×5 server neighborhood and is polled each server tick. It is not client mesh or presentation completion. Complete terrain hashes are recorded outside timing.

![Server waypoint readiness](flat-travel-readiness.png)

Frame observations use render-loop callback intervals, **not presented FPS**. Client loaded-chunk counts differ between default engines; these data are therefore not an equal-rendered-scene FPS ranking. The raw dataset retains them for inspection.

| Engine | Observed heap after updates (MiB) |
|---|---:|
| Vanilla | 201 |
| Alfheim 1.6 | 217 |
| Pulsar 0.5.0 | 206 |

Heap snapshots follow at least 100 post-workload server ticks and an explicit GC. They include the entire game, world and client data, rather than engine-only allocations. Client cache sizes can differ. Sampled process working sets are also retained, with two-second polling; neither metric is a general memory-efficiency claim.

![Observed whole-game heap](post-gc-heap.png)

## Normal generation: descriptive only

| Engine | Observed time (s) |
|---|---:|
| Vanilla | 32.9 |
| Alfheim 1.6 | 23.4 |
| Pulsar 0.5.0 | 22 |

Normal-terrain/light hashes differ, so these timings are not promoted as equal-output speed ratios. Fresh cohort terrain and light parity is checked independently. The cause of any output variation is not isolated by this timing study. Formal cohort parity is listed in the raw summary.

![Normal generation, not an equal-output ranking](normal-generation-descriptive.png)

## Reproduction and scope

- Minecraft 1.12.2, Cleanroom 0.6.12-alpha, Adoptium Java 25.0.4.1; G1, fixed 8 GiB heap and compact object headers.
- GMKtec EVO-X2 / Ryzen AI MAX+ 395, 32 logical processors, approximately 95.2 GiB OS-visible RAM, Radeon 8060S.
- 1280×720, render distance 4, VSync off, frame limit 260 (unlimited in 1.12.2), no shaders, Celeritas or OptiFine.
- Common dependencies and configuration are frozen; every variant receives the same default Version2 Pulsar config file. Non-Pulsar variants ignore that file. Pulsar uses automatic `-1` worker sizing, observed as 10 shared workers, and the default 2 ms client budget.
- Four fresh-JVM repetitions per supported condition. Public engine orders are recorded in protocol.json. Unsupported cases are omitted, not replaced with zero.
- Every JVM receives a fresh world copy. Fresh generation coordinates, completed work amounts and stored-light validation are checked. Within-run timing drift remains possible despite long warmup; run ranges and raw sequences are retained. Drift alone is not proof of JIT compilation.
- The chart label is **Pulsar 0.5.0**, the intended release. The measured source is `94ed8cb`; its pre-tag artifact reports `0.4.1-dev.30.local`. Exact engine/runtime JAR hashes are in the manifest. No release tag or publication was created by this benchmark task.
- Vanilla means the builtin lighting implementation under the same Cleanroom/common-mod setup, not a separate unmodified Java8 Mojang installation.

Alfheim is the official [1.6 release](https://github.com/Red-Studio-Ragnarok/Alfheim/releases/tag/1.6). Pulsar remains LGPL-3.0.

## Completed validation audit

All 76 public formal JVM runs completed and passed their selected workload checks. Each metric cohort has four independent runs. Lightbench’s native strict comparator separately accepted 12 block-update, 8 Sky-update and 12 flat-generation reports; independently recomputed per-run timings match its CSV. Passing a selected workload is not proof that every stored-light cell in an arbitrary world is correct.

| Cohort | Runs | Distinct terrain hashes | Distinct stored-light hashes | Equal-output comparison |
|---|---:|---:|---:|---|
| flatgen | 12 | 1 | 1 | Yes |
| naturalgen | 12 | 6 | 6 | No |
| flattravel | 12 | 1 | Not fully hashed | Yes |
| naturaltravel | 12 | 12 | Not fully hashed | No |

These counts cover the 76 public trials. Travel checks top Sky/Block light at 1,600 positions and hashes complete terrain; it does not hash complete light volumes. Normal output variations remain unisolated, so those times are descriptive.

## Paced low-rate roof edits

Edits are paced at 2 edits per 20 ticks, over 200 ticks (`lightbench spikes 2 10`). These are not large simultaneous edit bursts. The p99 values below are medians of each run’s p99 server tick, including non-edit ticks.

| Engine | 2 edits/20 ticks: mean / p99 (ms) |
|---|---:|
| Vanilla | Not validated |
| Alfheim 1.6 | 0.764 / 7.697 |
| Pulsar 0.5.0 | 0.315 / 0.507 |

![Paced edits](burst-server-ticks.png)

## Continuous-load completion backlog

| Engine | Post-run drain after 2048 edits/tick (ms) |
|---|---:|
| Vanilla | Not validated |
| Alfheim 1.6 | 155.701 |
| Pulsar 0.5.0 | 13.393 |

This final barrier is outside the measured tick durations. It does not measure the maximum age of queued light work during the sweep.

## Normal travel observations

| Engine | Mean waypoint readiness (ms) |
|---|---:|
| Vanilla | 119.812 |
| Alfheim 1.6 | 114.161 |
| Pulsar 0.5.0 | 105.731 |

![Normal travel, descriptive](normal-travel-descriptive.png)

## Data and reproduction files

- [Summary CSV](summary.csv), [summary and validation JSON](summary.json), [protocol](protocol.json), [artifact manifest](manifest.json).
- Raw per-edit/per-tick JSON, validation audits, observer data and two-second process samples are retained in [benchmark-data-20261012.zip](benchmark-data-20261012.zip) (publication bundle).
- Lightbench runtime source: `05596de`; offline comparator: `f6ccb91`. Build with JDK25 using `gradlew build`; no profiling instrumentation is attached to measured JVMs.
- [Local companion and analysis source](reproduction/README.md). No worlds, account files or engine binaries are included in this documentation.

## Publication copy

[GitHub](github-copy.md), [CurseForge](curseforge-copy.md), [Modrinth](modrinth-copy.md). Image URLs in these prepared drafts become available when the documentation commit is pushed.

[Delivered-file checksums](SHA256SUMS.json) and [qualification pilot evidence](qualification-pilots.json).
