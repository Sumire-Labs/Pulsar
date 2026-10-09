# Suggested performance copy

In this validated glowstone fixture, Pulsar completed placement/removal in 62.2/63.8 µs: 20.8×/29.4× shorter completion times than Vanilla and 2.9×/4.5× shorter than Alfheim. These ratios describe this fixture, not general FPS.

Four independent JVM runs per supported condition, same hardware and settings, light-completion checks. Pulsar is labeled 0.5.0 (source75b051d). Flat generation was slower than Vanilla/Alfheim; normal generation had output differences and is not used for an equal-output speed claim. Vanilla did not validate the tall-roof Sky workload.

![Validated block updates](https://raw.githubusercontent.com/Sumire-Labs/Pulsar/main/docs/benchmarks/2026-10-10-pulsar-0.5.0/block-latency.png)

![Continuous server-thread load](https://raw.githubusercontent.com/Sumire-Labs/Pulsar/main/docs/benchmarks/2026-10-10-pulsar-0.5.0/continuous-server-ticks.png)

The continuous graph measures main-thread MSPT, excluding async worker CPU; final drain is recorded separately. It is not a total-CPU or visual-latency speedup claim. [Methods, all results and caveats](https://github.com/Sumire-Labs/Pulsar/blob/main/docs/benchmarks/2026-10-10-pulsar-0.5.0/README.md).

<!-- Local draft: GitHub URLs become usable only after these documentation assets are pushed. No upload, push, release or external post has been performed. CurseForge rich-text editor may require uploading PNGs or inserting these image URLs manually. -->
