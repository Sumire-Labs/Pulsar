# RGB rendering and session validation

This evidence extends the object-rendering milestone with sampler reuse,
initial chunk-send retries and independent client/server measurements.

`results.json` records ten accepted native correctness runs, twenty independent
client timing JVMs, eight server timing JVMs and four two-JVM connection tests.
Exact production and diagnostic artifact hashes are retained for every run.
`native-evidence.zip` contains reports, logs, representative screenshots, 232
JUnit results, diagnostic sources and the measurement scripts. All timing
samples are retained, including GC. Failed preliminary diagnostic setups are
not counted as accepted correctness runs; their investigation is recorded in
the accompanying research reports and private study.

Client timing uses 600 warm-up frames followed by 1,200 measured frames with
64 entities and 1,024 particles. Each variant has four independent JVMs with
counterbalanced order. The interval is FML RenderTick START to END, not FPS.
Render-thread Java allocation is not retained heap or GPU memory. RGB's older
terrain-only variant does less rendering work; the complete-object variant is
the appropriate reference for the sampler optimization.

Server timing uses Lightbench with 2,000 warm-up pairs and 1,000 measured pairs
per phase. Every sample must pass the lighting verification. Comparisons use
paired log ratios of independent JVM means, with a two-sided Student t interval
(four pairs, three degrees of freedom). These small-sample intervals describe
this experiment, not every machine or workload.

The native platform is Minecraft 1.12.2, Cleanroom 0.6.12-alpha, Java 25,
Ryzen AI MAX+ 395 and Radeon 8060S; optional Celeritas 2.4.0-dev is used in
correctness tests. Measurements run without concurrent builds or test games.

The connection matrix uses real, separate Minecraft JVMs over loopback TCP.
The private diagnostic mod corrects a legacy LOGIN/PLAY encoder-state defect
around SPacketLoginSuccess only. This workaround is not included in Pulsar.
Final cases disable diagnostic initial-send assistance and exercise production
retries through Overworld, Nether, Overworld and disconnect/rejoin. Both RGB
and both mixed SCALAR/RGB combinations are covered. See
[the transport boundary](../initial-chunk-send-retry.md).

Diagnostic sources require a normal Pulsar development workspace and native
Minecraft/Forge libraries. Adjust the private paths in the replay scripts.
Accounts, worlds and third-party mod jars are not distributed. Cleanroom 0.7
and custom GLSL rendering are outside this evidence's validated platform.
