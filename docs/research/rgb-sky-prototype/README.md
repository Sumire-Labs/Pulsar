# RGB skylight validation evidence

See [the implementation report](../rgb-sky-prototype.md).

- `validation.json`: accepted sky, block, cache, renderer, SCALAR and Mod
  regression checks, including canonical light and real client geometry.
- `timing.json`: all sixteen fresh-JVM timing runs, with GC deltas, individual
  means and group medians/ranges. No valid timing run is discarded.
- `optimization-timing.json`: eight additional counterbalanced RGB runs comparing
  the first sky prototype with the final sky-publication optimization.
- `protocol.json`: runtime settings, input hashes, counterbalanced run order
  and construction of the old-format migration fixture.
- `artifacts.json`: artifact hashes and tested/final source hashes.
- `raw-data.zip`: full samples, native diagnostic sources and local replay tools.
- `red-skylight.png`: the registered diagnostic red-only skylight filter in the
  native Vanilla renderer. That clear-glass override belongs to the helper.
- `SHA256SUMS.json`: hashes of companion evidence files with stable LF endings.

The setup uses MC 1.12.2, Cleanroom 0.6.12-alpha, Java 25, a fixed 8 GiB heap,
automatic shared-pool sizing (10 workers on this host), render distance 4 and
FPS cap 260. Each timing JVM uses a fresh seed-1 flat fixture with 2,000 warmup
and 1,000 measured pairs per workload, without either correctness helper.
The metric is server block-state change through lighting completion, with
stored-light checks outside the timed interval. It does not measure FPS,
network delivery or total memory usage.

`SkyAudit.java` contains a bounded four-field reference for the room and an
analytic reference for the center of a 48×48 canopy. The central chunk lies
more than 14 blocks from any outside white-sky route, so blocked components
must be zero throughout the sampled 96-layer column. The native result also
records fourteen absent physical sections. The helper checks sky RGB and
canonical fields independently and confirms no block-light contamination.

The range test holds the existing lighting gate while queuing two range targets
and superseding one with an ordinary relight. It then releases the gate and
verifies the completed fields, avoiding a timing-dependent assumption that
supersession happened before the range worker ran.

The cache migration tool operates only on the owned copied fixture. It preserves
the profile, block RGB and canonical fields, changes RGB metadata to version 1
and removes sky cache tags. Its region/NBT codec preserves unrelated binary
payloads. A player's initial saved position can load and regenerate the remote
canopy before the later query; the main room's rejected-cache flag is the
explicit version-rejection assertion. New-cache reload checks both regions.

Replay paths in the scripts must be adapted. Build the helpers against the
mapped production API, remap to Searge and package only their diagnostic
namespaces. Never package the helper project's copied Pulsar classes. The
scripts rename the owned disposable instance game directory before staging
each run, preventing leftover Mod jars from contaminating later comparisons.
Jars, worlds, CQR assets, credentials and account files are not redistributed.

The final build optimizes missing-section resolution and updates the English
configuration description to mention RGB sky. Artifact comparison verifies that
all jar entries except `RgbStorage.class` and `PulsarConfig.class` are identical
to the first sky prototype. All final native correctness checks use this build,
and the separate eight-run optimization cohort measures it. SCALAR propagation,
cache and renderer classes remain unchanged.

`SkyAudit-tested.java` is the helper used in the original correctness cohort;
`SkyAudit.java` adds the split red/blue roof in the final-jar run. Both helpers
retain the later full-blue roof and the same persistence profile. The split
case verifies color propagation across a boundary while scalar sky stays 15.
