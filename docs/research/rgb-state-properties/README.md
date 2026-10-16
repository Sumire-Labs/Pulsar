# Evidence index

See [the report](../rgb-state-properties.md).

- `validation.json`: 214 JUnit tests and eight native reference/isolation audits.
- `timing.json`: all 16 short-protocol runs, including slower candidate runs.
- `long-timing.json`, `long-analysis.json`: eight longer-protocol runs and paired analysis.
- `production-isolation.json`: 96 unchanged scalar/core/Mixin class files.
- `protocol.json`, `artifacts.json`: runtime/input settings, order and hashes.
- `raw-data.zip`: raw per-update samples and own diagnostic/replay sources.
- `SHA256SUMS.json`: companion-file checksums.

Baseline: `9672c0d`, artifact `trim.jar`. Candidate: `state.jar`. The runtime is
MC 1.12.2, Cleanroom 0.6.12-alpha, Java 25, fixed 8 GiB heap and the automatic
10-worker pool. Renderer correctness runs additionally cover Celeritas's fast
path. Formal timed runs contain no diagnostic helper and overlap no build/test/
archive work. The filtered fixture is unchanged from the preceding study.

Replay scripts refer to an owned disposable Prism instance and require path and
installed-runtime adaptation. Jars, worlds, account files and third-party assets
are omitted. Diagnostic jars contain only their own namespaces.
