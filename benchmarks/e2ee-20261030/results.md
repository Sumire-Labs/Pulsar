# Optimized Pulsar: E2EE comparison

Measured 2026-10-30. Three new JVMs per variant, order A/S/R, R/A/S, S/R/A. Values are medians (minimum–maximum).
Source commit: `c62f162`. Artifact and harness hashes are in `series-input.json`.

## Validated conditions

- All nine runs have identical terrain hashes for 625 loaded chunks, matching the earlier comparison.
- Identical loaded world counts: [(0, 625, 22, 3157), (-343800852, 0, 0, 0), (2, 0, 0, 0)] (dimension, chunks, entities, tiles).
- Identical source-world file hashes and all non-engine mod hashes; no JFR or compatibility mutations in these runs.
- E2EE 1.87.3, Cleanroom 0.6.13-alpha, Alfheim 1.6, Pulsar SCALAR or RGB/RGB_MAX.
- Same Temurin 25.0.4.1, Ryzen AI MAX+ 395, 8 GiB fixed heap, G1, OTG/BBOP-Extended seed 20261029, view distance 12.
- Same normalized configs, observer e2ee2, 400-tick windows, post-full-GC histograms, disabled mob spawning/random ticks/day/weather/loot and extra Thaumcraft crystal emission.
- This remains a server-only, fresh-world comparison on a workstation. It does not measure FPS or mature factories.

## Matched saved-world results

Heap means whole-server live Java objects after full GC, not lighting-only retained size. Tick work is bracketed by Forge ServerTickEvent START/END; sleep between ticks is excluded.

| Variant | Live heap MiB | Working set MiB | Tick p50 ms | Tick p95 ms | CPU s / 400 ticks |
|---|---:|---:|---:|---:|---:|
| alfheim | 1183.97 (1183.61–1185.26) | 8033.53 (8030.08–8051.53) | 1.07 (1.06–1.24) | 1.43 (1.41–1.98) | 5.75 (5.52–6.11) |
| scalar | 1142.57 (1142.11–1142.94) | 7981.50 (7961.07–7983.59) | 1.15 (1.06–1.20) | 1.55 (1.44–1.56) | 5.33 (5.17–5.95) |
| rgb | 1163.55 (1163.36–1163.91) | 7983.57 (7946.17–7989.51) | 1.18 (1.17–1.20) | 1.65 (1.58–1.70) | 5.53 (5.30–5.59) |

## Change from the previous Pulsar build

| Mode | Previous heap MiB | Current heap MiB | Heap change MiB | Previous p95 ms | Current p95 ms |
|---|---:|---:|---:|---:|---:|
| scalar | 1151.93 | 1142.57 | -9.36 | 2.50 | 1.55 |
| rgb | 1193.08 | 1163.55 | -29.52 | 2.43 | 1.65 |

## Generation: observational only

After 169 warmup submissions, each run measures 507 fresh submissions near chunk +20000 (243 scored core chunks plus support halo). World generation/population, lighting barriers and finalization are timed together. Generated terrain differs even between runs of the same engine; these are not equal-work lighting speedup ratios. Post-generation heap is also affected by differing terrain/entity/tile counts.

| Variant | Generation s | Post-generation live heap MiB | Loaded tick p95 ms | Peak working set MiB |
|---|---:|---:|---:|---:|
| alfheim | 10.06 (8.80–10.09) | 1201.47 (1201.33–1206.15) | 1.86 (1.71–1.95) | 8125.39 (8115.32–8143.66) |
| scalar | 9.45 (9.25–10.21) | 1186.39 (1183.81–1190.79) | 1.95 (1.88–2.01) | 8065.07 (8039.31–8089.05) |
| rgb | 10.59 (10.09–11.00) | 1220.36 (1212.80–1221.73) | 2.01 (1.92–2.02) | 8081.71 (8070.69–8084.86) |

## Per-run evidence

| Run | Idle heap MiB | Idle p95 ms | Generation s | Post-generation chunks / entities / tiles |
|---|---:|---:|---:|---|
| alfheim-optimized-1 | 1183.61 | 1.983 | 10.085 | 1317 / 1403 / 18445 |
| alfheim-optimized-2 | 1183.97 | 1.406 | 10.065 | 1323 / 1395 / 18493 |
| alfheim-optimized-3 | 1185.26 | 1.434 | 8.797 | 1315 / 1399 / 18415 |
| rgb-optimized-1 | 1163.55 | 1.583 | 10.088 | 1315 / 1399 / 18417 |
| rgb-optimized-2 | 1163.91 | 1.652 | 10.586 | 1323 / 1387 / 18493 |
| rgb-optimized-3 | 1163.36 | 1.698 | 11.003 | 1325 / 1401 / 18520 |
| scalar-optimized-1 | 1142.11 | 1.561 | 9.453 | 1315 / 1557 / 18417 |
| scalar-optimized-2 | 1142.94 | 1.442 | 10.214 | 1326 / 1407 / 18544 |
| scalar-optimized-3 | 1142.57 | 1.548 | 9.246 | 1311 / 1400 / 18414 |

## Verification and limits

- 377 tests: 373 passed, four opt-in benchmark tests skipped. Seven GPU render cases passed.
- Bedrock Ores live mutation checks passed in SCALAR, RGB_MAX and RGB/VANILLA (11 assertions each). Failed exploratory probes remain retained separately and are not included above.
- JFR contextual-tracking samples fell from 285/511 to 2/209 Server thread samples. This is sampling evidence, not exact time attribution.
- Known notification sources still refresh on changes; unknown sources and cells containing stored fluid keep fallback polling. Budgets and features were not reduced.
- Small tick differences within the observed ranges should not be interpreted as a universal engine ranking. Three runs are not a statistical guarantee across modpacks.
- Real-world visual checks, client FPS, extended-height stress and mature E2EE factories remain outside these measurements.
- Raw trials remain in `../e2ee-20261029/runs/*-optimized-*`; compact observations and input hashes accompany this report.
