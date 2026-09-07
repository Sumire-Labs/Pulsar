# Modrinth / CurseForge replacement text

Replace the previous **Performance** section and both old benchmark images.
Upload `light-updates.png` from this directory with the platform's image
uploader, then insert that image below the first paragraph. The text below is
ready to copy. The GitHub data link becomes available once this directory is
published on the repository's default branch; these files have not been posted.

---

## Performance — Pulsar 0.3.0

Recorded on September 7, 2026. Lightbench measured from each block edit until
server-side lighting completed, then checked the stored light values across
the affected volume outside timing. All six formal runs passed validation.

Each value is the median of three independent Minecraft/JVM-run p50s.
**Lower is better.**

| Workload | Alfheim 1.6 | Pulsar 0.3.0 | Alfheim / Pulsar |
|---|---:|---:|---:|
| Open roof column (SKY increases) | 9.674 ms | 0.621 ms | 15.58x |
| Close roof column (SKY decreases) | 15.822 ms | 0.617 ms | 25.66x |
| Place glowstone (BLOCK increases) | 0.167 ms | 0.072 ms | 2.31x |
| Remove glowstone (BLOCK decreases) | 0.272 ms | 0.077 ms | 3.54x |

Pulsar had lower median latency in these four workloads. For glowstone
placement, however, the median run p95 was **0.220 ms for Pulsar versus
0.177 ms for Alfheim**. The chart shows p50 and p95; whiskers show the full
range of run p50s. These timings describe repeated edits at one position,
not FPS, TPS, overall game speed, or the contribution of any single algorithm.

### Historical Vanilla reference — previous measurement

These are the previous Vanilla measurements, **not new 0.3.0 measurements**.
The chart shows them in a separate historical panel with its own axis range.
They used Lightbench 1.0.0 with sparse light probes, Cleanroom 0.6.8-alpha,
Azul Java 25.0.3, and seed `20260805`. They did not undergo the current
full-volume validation. The changed protocol and environment mean they
**cannot be used to calculate speedup against the current results above**.

| Workload | Historical Vanilla p50 | Range of three run p50s |
|---|---:|---:|
| Open roof column | 45.541 ms | 38.084–46.356 ms |
| Close roof column | 847.284 ms | 762.866–848.995 ms |
| Place glowstone | 2.028 ms | 1.768–2.092 ms |
| Remove glowstone | 2.820 ms | 2.510–2.880 ms |

Recorded August 6, 2026 (JST); each value is the median of three run p50s.
The historical Vanilla generation result was **56.461 s** for 10,404 chunks
(three-run range **55.639–58.628 s**), also using the old Lightbench 1.0.0
protocol. It is reference data only, not a baseline for the new generation pilots.

### Current measurement setup and limitations

Test setup: Minecraft 1.12.2, Cleanroom 0.6.12-alpha, integrated server,
Windows 11, AMD Ryzen AI MAX+ 395, Eclipse Adoptium Java 25.0.4.1, 8 GiB heap,
and Lightbench 1.0.6-completion (local validation build). Both engines used
the same common mods and configuration. Each launch used a fresh copy of a
seed-1 Superflat template with a 64×64 roof at Y=254 above a floor at Y=3.
Each phase had 200 measured samples after 20 warmup pairs. Launch order was
Alfheim, Pulsar, Pulsar, Alfheim, Alfheim, Pulsar.

**Vanilla and generation results:** Vanilla failed the stronger update-volume
checks, even after the harness was corrected to drain deferred sky-gap
maintenance, so no new Vanilla/Pulsar ratio is reported. Generation pilots
also produced differing terrain and light hashes; a generation-speed ranking
is withheld until output equivalence is established. Historical benchmark
numbers are not presented as 0.3.0 results, and the changed protocol does not
measure improvement over older Pulsar releases.

[Full methodology, raw measurements, reproducible chart, and validation records](https://github.com/Sumire-Labs/Pulsar/tree/HEAD/docs/benchmarks/2026-09-07-pulsar-0.3.0)
