# Pulsar 0.5.0 performance comparison

October 12, 2026 study: Minecraft 1.12.2 / Cleanroom 0.6.12-alpha, Ryzen AI MAX+ 395, Java 25.0.4.1, fixed 8 GiB heap. The exact measurement window is recorded in the methods and manifest. Four independent fresh-JVM runs per supported condition, matching settings and stored-light validation. Values are medians of run means; lower is better. Pulsar source: `94ed8cb`.

| Workload | Vanilla | Alfheim 1.6 | Pulsar 0.5.0 |
|---|---:|---:|---:|
| Block placement (µs) | 1,294 | 184 | 66.6 |
| Block removal (µs) | 1,869 | 271 | 68.3 |
| Sky opening (ms) | Not validated | 9.62 | 0.606 |
| Sky closing (ms) | Not validated | 16.3 | 0.618 |
| Equal-output flat generation (s) | 1.31 | 1.18 | 1.34 |
| Main-thread MSPT at 2,048 edits/tick | Not validated | 178 | 2.48 |
| Flat travel: server readiness (ms) | 57.5 | 56.6 | 55.4 |

Completion times include the server block edit and the engine-specific light-completion barrier. They are not FPS measurements. MSPT excludes async worker CPU; final drain is recorded separately. Vanilla failed the tall-roof Sky/continuous validation and has no comparable score for those conditions. Flat generation requires matching complete scored terrain and light hashes.

![Block update completion](https://raw.githubusercontent.com/Sumire-Labs/Pulsar/main/docs/benchmarks/2026-10-12-pulsar-0.5.0/block-latency.png)

![Tall-roof Sky stress](https://raw.githubusercontent.com/Sumire-Labs/Pulsar/main/docs/benchmarks/2026-10-12-pulsar-0.5.0/sky-latency.png)

![Equal-output flat generation](https://raw.githubusercontent.com/Sumire-Labs/Pulsar/main/docs/benchmarks/2026-10-12-pulsar-0.5.0/flat-generation.png)

![Continuous editing: main-thread tick time](https://raw.githubusercontent.com/Sumire-Labs/Pulsar/main/docs/benchmarks/2026-10-12-pulsar-0.5.0/continuous-server-ticks.png)

Continuous-edit tick times exclude async worker CPU; completion backlog and worker CPU are reported separately. Normal-terrain generation/travel outputs vary, so their timings remain descriptive. Whole-game heap and render callbacks do not establish engine-only memory savings or equal-scene FPS gains. Results describe these workloads on this machine.

[All workloads, run ranges, methods, validation and raw data](https://github.com/Sumire-Labs/Pulsar/blob/main/docs/benchmarks/2026-10-12-pulsar-0.5.0/README.md).

<!-- Prepared local copy. These URLs become usable after the documentation commit is pushed. No external post or release was created. CurseForge may require inserting or uploading the PNG images in its rich-text editor. -->
