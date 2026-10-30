# Measured completion latency

Milliseconds per edit: median of four JVM run means (minimum–maximum).

| Variant | Glowstone placement | Glowstone removal | Roof removal | Roof replacement |
|---|---:|---:|---:|---:|
| Vanilla lighting | 1.345 (1.271–1.410) | 1.935 (1.804–2.105) | Validation failed | Validation failed |
| Alfheim 1.6 | 0.164 (0.145–0.272) | 0.255 (0.230–0.452) | 10.993 (10.775–14.693) | 18.435 (17.511–26.016) |
| Pulsar SCALAR | 0.073 (0.070–0.074) | 0.074 (0.072–0.076) | 0.669 (0.666–0.694) | 0.682 (0.673–0.696) |
| Pulsar RGB | 0.160 (0.153–0.164) | 0.145 (0.138–0.150) | 0.728 (0.704–0.989) | 0.723 (0.677–0.921) |

Only the open-sky workload is used for the glowstone columns. Roof-test glowstone samples remain in the raw reports.

See [measurement conditions and limitations](../../../README.md#benchmarks) before interpreting these values.
