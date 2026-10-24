# RGB prototype evidence

`validation.json` records the accepted native checks and 201-test unit suite.
`timing.json` retains every JVM run mean and full per-run ranges; the raw archive
contains all measured samples. Baseline and prototype SCALAR each use four JVMs;
RGB uses two. No long-latency run or sample was removed.

`artifacts.json` identifies the engine, diagnostic and installed mod inputs by
SHA-256 and records the production source freeze. Third-party binaries/assets
and launcher account files are not included.

The raw archive contains the diagnostic Java sources, corrected compatibility
fixture, replay scripts, stage manifests and JSON outputs. Replay requires
Minecraft 1.12.2, Cleanroom 0.6.12-alpha, Java 25, PrismLauncher and the listed mods.
Adapt the study/Prism paths in the scripts before running them. Create fresh
seed-1 flat and normal templates; the normal template must not reuse old test
crystals. CQR's Galleon.nbt must be extracted locally from the installed CQR jar
and its path adjusted in ShipAudit.java.

Build Pulsar with `gradlew build --no-watch-fs`. Build the diagnostic sources in
a disposable copy using the same mappings and remapJar task, then package only
the diagnostic namespaces into a separate jar. Never include the copied Pulsar
classes in that diagnostic jar. The production jar was checked to contain no
such namespaces.

Run `rgbaudit` for the five RGB reference-solver cases, `rgbaudit reload` for a
saved diagnostic world, and `rgbaudit scalar` for the SCALAR mixin exclusion.
The Celeritas fast path uses `-Dceleritas.useVintageFastBlockRenderer=true`.
The compatibility replay uses `issueaudit ship` and explicitly checks pending
light before chunk/watch delivery. Its corner reader waits for actual client
geometry and idle lighting, rather than sampling an unloaded chunk.

Formal timing uses `lightbench updates 2000 1000` without either diagnostic mod.
All input mods and options remain the same between variants. See protocol.json
for scope, order and settings. Timing is not an FPS or network-delivery benchmark.
