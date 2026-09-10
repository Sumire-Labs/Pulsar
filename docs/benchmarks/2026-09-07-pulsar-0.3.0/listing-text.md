## Performance

In controlled tests, Pulsar 0.3.0 achieved lower median lighting-update times
than Alfheim 1.6 in all four tested workloads, with the largest gains in skylight
updates. Actual gains depend on your hardware and workload; these results do
not translate directly into higher FPS or TPS.

<details>
<summary>Light-update benchmark — Pulsar 0.3.0</summary>

[![Lighting-update times for Pulsar 0.3.0 and Alfheim 1.6, with historical Vanilla reference values. Lower is better.](https://raw.githubusercontent.com/Sumire-Labs/Pulsar/main/docs/benchmarks/2026-09-07-pulsar-0.3.0/light-updates.png)](https://github.com/Sumire-Labs/Pulsar/blob/main/docs/benchmarks/2026-09-07-pulsar-0.3.0/light-updates.png)

Each update was timed until server-side lighting completed. Markers show the
median of three run medians; horizontal lines show their range.

**Vanilla* uses older measurements under different conditions and is provided
for reference only.** Current results were recorded on September 7, 2026.
Pulsar's median times were lower, though Alfheim had a lower p95 for light-source
placement; full percentile data is linked below.

</details>

<details>
<summary>Chunk-generation benchmark — historical results, Pulsar 0.1.0</summary>

**This is a previous-version benchmark, not a measurement of Pulsar 0.3.0.**

[![Historical chunk-generation benchmark using Pulsar 0.1.0, Alfheim, and Vanilla.](https://raw.githubusercontent.com/Sumire-Labs/Pulsar/main/docs/benchmarks/2026-08-05-chunk-generation.svg)](https://github.com/Sumire-Labs/Pulsar/blob/main/docs/benchmarks/2026-08-05-chunk-generation.svg)

The older 10,404-chunk test recorded median totals of **48.831 s for Pulsar**,
**49.615 s for Alfheim**, and **56.461 s for Vanilla**. This includes terrain
generation and lighting.

No new generation-speed comparison is published for 0.3.0 because the latest
runs produced differing terrain and lighting results.

</details>

[Measurement details and raw data](https://github.com/Sumire-Labs/Pulsar/tree/main/docs/benchmarks/2026-09-07-pulsar-0.3.0)
