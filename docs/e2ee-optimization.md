# E2EE tick and RGB memory optimization

## Investigation

Baseline: [initial comparison](../benchmarks/e2ee-20261029/results.md).
The original RGB build is `pulsar-0.5.4-dev.50.local.jar`.

JFR sampled the same saved-world idle workload for 20 seconds with a 1 ms
execution-sample interval. Before changes, 285 of 511 Server thread samples
contained contextual-light tracking; after changes, 2 of 209 did. These are
sampling observations, not exact CPU accounting. Profiled trials are excluded
from final performance medians.

Bedrock Ores 1.2.7.42 has a client-only `ParticleManager` parameter on an unrelated
method. On a dedicated server, `Class.getMethod` attempts to resolve that signature
and throws `NoClassDefFoundError`. Pulsar safely fell back to treating both light
properties as contextual, including the inherited, fixed opacity. A synthetic
stripped-client-signature test reproduces this failure and verifies targeted
method-handle lookup. Unknown/inaccessible light methods still use the conservative
fallback.

## Changes

- Resolve only the two light method descriptors when classifying overrides.
- Notify Bedrock Ores light changes from its setter. Actual bytecode confirms disk
  NBT and client NBT both delegate to that setter. Bright-to-dark, null/exhausted
  ore and tile replacement are covered. Only the recognized final block with
  non-contextual opacity and an active notification hook can skip polling.
- A notification source without a stored fluid may skip polling even when
  Fluidlogged API is installed. Fluid-bearing cells and unknown sources retain
  polling; adding fluid still requests the ordinary light update.
- Publish contextual samples through a primitive, single-writer table with
  release/acquire slots. Keys never change while readers can see the table.
  Deletions preserve probe chains; rebuilding publishes a fresh table.
- Share immutable sample values through a bounded, identity-based owner-thread
  cache. No global interning map or unbounded retention is introduced.
- For RGB_MAX, freeze engine-owned visible planes and share equal planes with
  immutable snapshots. Later writes detach; external vanilla storage keeps its
  identity and is copied. Pending unpublished writes are preserved.
- Retain 64 hot 2 KiB scratch arrays per worker; weak overflow remains reusable
  during bursts and can be reclaimed at GC. The burst cap remains 2,048 arrays.
  Visible/borrowed arrays are never returned to this scratch pool.

No sampling budget, feature setting, RGB mode or comparison workload was reduced.

## Verification checkpoint

- Full build with GPU tests: 377 tests, 373 passed, four opt-in benchmark tests
  skipped; seven GPU rendering cases passed.
- Real E2EE Bedrock Ores checks pass in SCALAR, RGB_MAX and RGB/VANILLA: emission increase,
  decrease, neighbouring light, NBT restoration, exhaustion, tile replacement,
  removal and notification-only classification (11 assertions per mode).
- The initial notification probe deliberately failed its no-polling assertion;
  diagnostics exposed the missing client signature. Failed probes and logs were
  retained, not counted as successful benchmark runs.
- The profiled RGB candidate reduced tracked sources from 3,072 to 25 and live
  idle heap to about 1,165 MiB. Final unprofiled comparisons are still pending.

## Recomparison protocol

`benchmarks/e2ee-20261030/run_optimized_series.py` pins the artifact hash and runs
Alfheim/SCALAR/RGB three times each, in the same balanced order as before.
Every run starts a fresh JVM with the exact same saved-world copy, original
8 GiB/G1 settings, configuration template and e2ee2 measurement harness.
The e2ee3 harness is used only for the separate, mutating compatibility checks.
All controls, geometry checks and limitations from the initial report still apply.
No JFR, builds or compatibility mutations run inside the final timed series.
