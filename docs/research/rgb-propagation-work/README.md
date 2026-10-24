# Evidence index

See [the report](../rgb-propagation-work.md).

The SCALAR follow-up uses eight fresh JVMs per previous artifact with 10,000
warm-up and 5,000 measured pairs per workload. RGB follow-up timings use four
JVMs per artifact/fixture with 2,000 warm-up and 1,000 measured pairs. An
unfiltered RGB follow-up uses four JVMs per artifact with 10,000 warm-up and
5,000 measured pairs to investigate the short-cohort Sky increase. Each edit
and both light volumes retain the original correctness checks. Old runs remain
in the preceding report and are not pooled across different warm-up protocols.

- `scalar-timing.json` and `scalar-analysis.json`: all JVM summaries and paired
  inference, including submission/barrier distributions and worker CPU totals.
- `rgb-long-timing.json` and `rgb-long-analysis.json`: the longer-warmup RGB
  cohort, kept separate from the short protocol.
- `static-proof.json`: class-file identity and version/refmap comparison.
- `transformed-proof.json`: native SCALAR Mixin export comparison, allowing only
  generated handler identifiers and per-launch session UUID strings.
- `production-isolation.json`: unchanged SCALAR class files in the new candidate.
- `validation.json`: unit and native correctness results for the new RGB work.
- `timing.json`, `protocol.json`, `artifacts.json`: RGB run means, protocol,
  artifact hashes and source freeze.
- `raw-data.zip`: raw samples, diagnostic sources and replay/analysis scripts.
- `SHA256SUMS.json`: checksums of these companion files.

Replay scripts name an owned disposable Prism instance and must be adapted to
local paths. Diagnostic jars contain only their own namespaces; jars, worlds,
third-party assets and account data are not redistributed. The runtime is
MC 1.12.2, Cleanroom 0.6.12-alpha, Java 25, fixed 8 GiB heap and the automatic
10-worker shared pool on the same Ryzen AI MAX+395 machine as previous studies.
