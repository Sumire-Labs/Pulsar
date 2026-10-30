# E2EE: Alfheim / Pulsar live server comparison

Measured 2026-10-30. Three fresh JVMs and identical saved-world copies per variant; order A/S/R, R/A/S, S/R/A.
Values below are medians (minimum–maximum), not confidence intervals. Pilots are excluded.

## Conditions

- E2EE 1.87.3 server exclusions; Cleanroom 0.6.13-alpha (pack default is 0.6.11-alpha).
- Alfheim 1.6; Pulsar 0.5.4-dev.50.local, SCALAR or RGB/RGB_MAX. No client connected.
- Temurin 25.0.4.1, Ryzen AI MAX+ 395, 32 logical CPUs, 8 GiB fixed heap, G1.
- Seed 20261029, OTG/BBOP-Extended, view distance 12. Common source: saved fresh Alfheim pilot world.
- Mob spawning, random ticks, day/weather cycle and drops disabled; Thaumcraft extra crystal emission disabled.
- TileEntity light tracking remains enabled, default 1,000 microsecond / 4,096 sample budget.
- Local Windows workstation, not a dedicated isolated benchmark host. No concurrent builds during measured runs.
- Histogram full GC and geometry audits occur outside timed windows. Tick windows contain 400 ticks each, bracketed by Forge ServerTickEvent START/END callbacks (not client frame time).

## Matched saved-world observations

- Loaded terrain hashes equal across all nine runs: **True** (625 chunks).
- Loaded dimension/chunk/entity/tile counts equal: **True**.
- All non-engine mod file hashes equal: **True**.
- Post-GC live heap is the class-histogram total for the entire server, not lighting-only memory.
- Working set is sampled process physical memory; the fixed heap and JVM residency affect it.

| Variant | Live heap MiB | Idle working set MiB | Tick p50 ms | Tick p95 ms | Process CPU s / 400 ticks |
|---|---:|---:|---:|---:|---:|
| alfheim | 1184.45 (1183.16–1185.49) | 8024.33 (7992.13–8048.23) | 1.04 (0.98–1.07) | 1.41 (1.35–1.52) | 3.02 (2.72–3.48) |
| scalar | 1151.93 (1151.86–1152.37) | 7978.63 (7972.63–7991.32) | 2.14 (2.12–2.27) | 2.50 (2.45–2.56) | 3.77 (3.55–4.03) |
| rgb | 1193.08 (1190.81–1193.50) | 7989.32 (7972.03–8008.88) | 2.10 (2.07–2.14) | 2.43 (2.40–2.50) | 3.56 (3.33–3.58) |

## Generation observations: unequal terrain workload

Each run warms up 169 submissions then measures 507 submissions (243 scored core chunks, plus support halo) in three fresh areas near chunk +20000. Timing includes terrain generation/population, lighting, completion barriers and finalization. It is not lighting-only CPU time.
The pack generated differing block-state terrain hashes despite a fixed seed. These values describe observed pack behavior, and **must not be used as a controlled lighting-engine speedup ratio**. Post-generation memory also includes different terrain/entity/tile populations.

| Variant | Generation seconds | Post-generation live heap MiB | Loaded tick p95 ms | Process peak working set MiB |
|---|---:|---:|---:|---:|
| alfheim | 9.00 (8.86–10.07) | 1202.49 (1200.76–1204.48) | 1.64 (1.58–1.69) | 8094.01 (8089.22–8105.59) |
| scalar | 9.97 (9.97–10.91) | 1207.51 (1207.24–1214.79) | 2.73 (2.71–2.76) | 8073.15 (8068.34–8087.53) |
| rgb | 10.77 (10.37–11.97) | 1266.68 (1265.20–1272.77) | 2.64 (2.61–2.72) | 8058.21 (8050.90–8072.70) |

## Per-run observations

| Run | Idle live MiB | Generation s | Post-generation chunks / entities / tiles | Terrain differences vs first measured run |
|---|---:|---:|---|---:|
| alfheim-shared-1 | 1183.16 | 10.075 | 1324 / 1412 / 18519 | 0/243 |
| alfheim-shared-2 | 1184.45 | 8.855 | 1316 / 1394 / 18416 | 208/243 |
| alfheim-shared-3 | 1185.49 | 9.004 | 1316 / 1403 / 18415 | 207/243 |
| rgb-shared-1 | 1190.81 | 10.771 | 1318 / 1407 / 18451 | 206/243 |
| rgb-shared-2 | 1193.08 | 11.969 | 1325 / 1400 / 18522 | 207/243 |
| rgb-shared-3 | 1193.50 | 10.372 | 1318 / 1395 / 18447 | 207/243 |
| scalar-shared-1 | 1151.93 | 9.975 | 1312 / 1400 / 18415 | 206/243 |
| scalar-shared-2 | 1151.86 | 10.909 | 1325 / 1397 / 18519 | 206/243 |
| scalar-shared-3 | 1152.37 | 9.969 | 1312 / 1389 / 18414 | 208/243 |

## Limits and reproducibility

- A newly generated world does not represent a mature E2EE factory or multiplayer load. Client rendering/FPS and visual correctness were not tested.
- RGB provides different lighting features; scalar light equality is not assumed for RGB_MAX.
- A similar total-server heap does not prove equal lighting-engine retained memory; heap histograms lack ownership graphs.
- Tick work excludes the server sleep between ticks; lower work at idle does not establish maximum player capacity.
- TileEntity polling is a candidate for Pulsar tick overhead, based on its configured 1 ms budget; this run does not isolate its causal contribution.
- Raw local artifacts: `runs/<name>/trial-status.json`, `benchmark-inventory.json`, `world-source.json`, `process-memory.csv`, `*-histogram.txt`, `benchmark-results/*.json`, and `BBOP-Extended/lightbench-results/*.json`.
- Download provenance and normalized configs are retained alongside the controller. Servers were stopped after every run; no existing user world or Prism instance was modified.
