# RGB filter validation evidence

See [the implementation report](../rgb-filters.md) for scope and interpretation.

- `validation.json`: accepted native RGB/SCALAR, persistence, rendering and Mod
  regression results, plus the JUnit totals.
- `timing.json`: all sixteen independent timing runs, including GC counters and
  each run's mean completion time. Group summaries use the median of four
  independent JVM run means per variant.
- `artifacts.json`: baseline commit, artifact SHA-256 hashes and the exact
  production source hashes tested before the commit.
- `protocol.json`: runtime settings, input hashes and the actual run order.
- `raw-data.zip`: full measured samples, diagnostics and local replay scripts.
- `red-glass.png`: native Vanilla terrain screenshot with blue light blocked by
  a red stained-glass wall at the chunk boundary.
- `SHA256SUMS.json`: hashes of the companion evidence files.

Native setup reuses the dedicated prototype study described in
`../rgb-block-prototype/README.md`: MC 1.12.2, Cleanroom 0.6.12-alpha, Java 25,
8 GiB heap and automatic shared-pool sizing. It does not use the Cleanroom
version reported as an available update. Before/after timing uses the same
non-engine inputs and fresh flat worlds, without the correctness-only RGB Audit
mod. Each JVM warms up 2,000 edit pairs and measures 1,000 pairs for each of
block placement/removal and skylight opening/closing. Stored light is verified
after every measured edit outside the timed interval. Completion includes the
server lighting barrier; these are not FPS or network-latency measurements.

The replay scripts contain study-local paths that must be adapted. They rename
the **owned disposable instance** game directory on the same volume before
staging each run, so leftover Mod jars cannot contaminate the next run. Earlier
exploratory runs with failed staging or the canonical-only synchronization bug
are excluded from the accepted cohort.

Build `RgbAudit.java` against the tested production source and the same mapped
MC/Forge environment; remap it to Searge names and package only its diagnostic
namespace, not a second copy of Pulsar. It deliberately registers pure RGB
sources during initialization. Its separate canonical reference does not derive
gameplay light from the filtered RGB result. The source includes the allocation
microprobe; that is executed only in correctness runs, never in formal timing.

Jars, Minecraft worlds, CQR ship assets, account files and launch credentials
are not redistributed. Existing native fixtures, Mod versions and input hashes
are required for exact replay. The old-cache migration fixture comes from the
preceding block-light prototype; the new-cache fixture is saved after the first
accepted filter run. Saved migration results must show the old RGB cache rejected
and the new RGB cache accepted.
