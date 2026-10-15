# Issues 20 and 21: reproduction, repair and contextual-light performance

This study reproduces the symptoms reported in [issue 20](https://github.com/Sumire-Labs/Pulsar/issues/20)
and [issue 21](https://github.com/Sumire-Labs/Pulsar/issues/21) using real
Thaumcraft 6 and Chocolate Quest Repoured in disposable integrated-server games.
The reporter's exact world and complete mod list were unavailable. The
reproductions below establish concrete failures and their fixes in these
controlled scenarios, rather than asserting coverage of every mod pack.

The baseline is released Pulsar 0.5.0; the candidate starts from `7b523cb`.
Pulsar remains LGPL-3.0. No CQR implementation or external lighting-engine code
was copied into production, and no CQR classes are bundled with Pulsar.

## Thaumcraft crystals: correct emission, premature packets

All 112 registered crystal states (seven types, sixteen states each) already
return and store the configured minimum of 10 after ordinary placement, direct
Chunk placement and a completed relight. The minimum-emission policy was not
changed to conceal the problem.

The failing case is the chunk's first transmission after a later contextual
change. Thaumcraft generation uses `World.setBlockState` with flags 0. A chunk
can still have `lightReady=true` while the crystal's new sample or propagation
is pending. The old initial-send gate checked initial readiness and cardinal
neighbors, allowing a crystal to arrive with block light 0. The server later
reaches 10, but that initial client data can remain at 0.

There is a second path: adding a player to an entry already sent to other
players calls `sendToPlayer`, bypassing the old initial-send gate. The
diagnostic harness reproduces both paths by holding the existing lighting gate
while introducing the crystal. This synchronization exists only in the test
helper; production adds no artificial delay.

The repair waits for outstanding contextual and lighting work in the 3x3
neighborhood. For already-sent entries it defers the new viewer's initial
packet while retaining vanilla membership, suppresses entity tracking and
partial broadcasts until that packet is delivered, and preserves Forge's
watch-event order. Departure, disconnect, chunk replacement and world shutdown
discard obsolete pending sends.

| Check | Released 0.5.0 | Fixed candidate |
|---|---|---|
| Initial send while crystal work is held pending | Sent | Waited |
| Client crystal light after server reaches 10 | 0 | 10 |
| New viewer of an already-sent entry | Receives stale light 0 | Receives light 10 after waiting |
| Watch event during pending work / after delivery | 1 / 0 | 0 / 1 |

The waiting viewer receives neither premature entity tracking nor the early
Forge watch event. The final assertions also require exactly one event after
the wait, and no pending client lighting when comparing the ship.

## CQR ships: external writes bypassed engine bookkeeping

The installed CQR 2.8.0B generator creates its real Galleon structure, measuring
32 × 43 × 50 = 68,800 cells. CQR's bulk path directly changes section palettes
and vanilla light arrays. Its vanilla light-repair pass cannot reliably repair
Pulsar's SWMR arrays, section initialization and contextual snapshots.

After native generation, the old engine's stored light differs from a fresh
relight of the same geometry. Opaque ship blocks can retain sky light 15 where
the relit value is 0. A generic sealed oak-log room did not exhibit this error:
server light, client light and its sampled interior-face lightmap vertices were
all zero. The CQR reproduction identifies a bulk-write integration problem.

An optional CQR hook schedules Pulsar's existing relight operation for CQR's
changed chunk set and its lighting halo. It works on loaded chunks, captures
contextual values on the world thread, and refreshes clients after nearby work
has completed. Reflection is cached and used once per bulk operation, outside
the propagation loops; unsupported CQR internals produce a warning.

| Native generation vs completed full relight | Released 0.5.0 | Fixed candidate |
|---|---:|---:|
| Different Sky cells | 10,928 | 0 |
| Different Block cells | 6,569 | 0 |
| Client/server Sky or Block differences after the complete repair | Nonzero | 0 / 0 |

The final candidate also passes the same comparison with Celeritas 2.4.0-dev,
Fluidlogged API 3.3.3, ThaumcraftFix 1.1.11, StellarCore 1.6.0 and LoliASM 5.33.
The baseline and candidate use the default CQR bulk-generation path. The generic
log-room control and all 112 crystal-state checks pass in both.

## Partial packets must also erase old light

The first CQR repair exposed a separate synchronization defect: 1,152 client
sky-light cells remained at 0 although the server held 15. The affected
sections were physically absent. Omitting a section from a partial packet
preserves its previous client state, so a default-light section still needs a
payload when a partial refresh must reset it.

Partial packets now supply transient light-only storage for requested absent
sections, including default-light resets. These sections are not installed into
the live server chunk. Existing full-packet omission behavior and the split
partial refresh that preserves tracked entities are retained.

Cache version 13 rejects old persisted light, allowing already-generated
affected structures to repair on their next load. The legacy test world was
saved by the baseline before its diagnostic full relight; the candidate is
measured immediately after loading that old world, before a manual repair.

The saved version-12 control still differs from a full relight by 10,928 Sky
and 6,569 Block cells when loaded by 0.5.0. Loading it with the candidate repairs
those differences automatically: both channels match the subsequent explicit
relight, and the client matches the server across all 68,800 scored cells.

## Contextual pending-position collection

The previous path boxed positions into a concurrent hash set, copied the set
into a new hash set at every flush, then removed each original entry. The new
collector deduplicates primitive ints under a short monitor and atomically
detaches a batch. Requests arriving during processing belong to the next
batch. The monitor is released before any world/block callback. The manager
uses a primitive iterator; the existing public `Set<Integer>` accessor remains
available. Signed coordinates and contextual sample identity checks retain
their existing semantics.

Four fresh JVMs per variant, counterbalanced order, 1,000 warmup batches and
1,000 measured batches. Each batch makes 4,096 requests for 2,048 unique packed
positions and iterates every detached position. All checksums match.

| Queue-only metric | Baseline | Candidate | Change |
|---|---:|---:|---:|
| Median elapsed time | 107.918 ms | 51.500 ms | -52.3% |
| Median allocating-thread bytes | 172.066 MiB | 31.441 MiB | -81.7% |

These microbenchmarks isolate request/detach/iteration cost and the allocating
thread's bytes. They do not measure whole-game heap consumption, worker CPU,
FPS or overall engine speed. Concurrent-producer correctness is tested
separately. Adoption also requires the native game regression checks below.

## Native timing cohort

Values are medians of independent JVM run means; parentheses retain the
minimum–maximum run means. Lower is better.

| Metric | Released 0.5.0 | Candidate | Change | JVMs/variant | Unit |
|---|---:|---:|---:|---:|---|
| Main-thread MSPT, 2,048 edits/tick | 192.620 (187.619–198.638) | 67.638 (66.869–70.210) | -64.9% | 4 | ms |
| Final light drain | 0.113 (0.104–0.221) | 0.413 (0.179–0.581) | +267.4% | 4 | ms |
| Block placement | 85.786 (85.386–86.186) | 80.635 (80.061–81.209) | -6.0% | 2 | µs |
| Block removal | 81.755 (80.518–82.992) | 76.924 (75.642–78.207) | -5.9% | 2 | µs |
| Sky opening | 703.222 (696.000–710.443) | 692.045 (684.052–700.037) | -1.6% | 2 | µs |
| Sky closing | 744.394 (736.850–751.938) | 738.131 (733.785–742.477) | -0.8% | 2 | µs |

The dense-edit MSPT median decreases by 64.9%; both variants complete the same
204,800 edits and verify final light. Final drain increases by about 0.301 ms,
so the reduction is not described as an improvement in every metric. Update
timings have two JVMs per variant and serve as descriptive regression checks.

Minecraft 1.12.2, Cleanroom 0.6.12-alpha, Adoptium Java 25, fixed 8 GiB G1 heap,
compact headers, Ryzen AI MAX+ 395, render distance 4 and ten automatically
selected shared workers. Both timed variants include Thaumcraft 6, Bubbles and
Fluidlogged API 3.3.3, with the same Red Core, Fugue and Scalar support mods.
Neither CQR, renderer optimizers nor the diagnostic helper are installed in the
timing games. Native Lightbench bench9 and the same completion adapter are used.
The adapter's only change is its owned-instance path literal; timing code is
unchanged.

Each JVM generates fresh copies of the same seed-1 flat terrain with structures
disabled. There are no saved lighting caches or earlier benchmark structures.
This keeps cache-version migration and dirty fixture geometry out of the
comparison. An earlier timing attempt failed preflight due to inherited test
geometry and is excluded, rather than treated as a performance result.

Continuous editing uses 2,048 edits/tick for 100 ticks, four independent JVMs
per variant. Update checks use 2,000 warmup pairs and 1,000 measured pairs per
phase, two JVMs per variant. Engine order is counterbalanced. Every measured
update must verify light; dense tests must finish all 204,800 edits without a
wall cap and verify final roof geometry and light. All run means are retained.

Completion latency includes submission and the installed adapter's waiting.
Main-thread MSPT excludes asynchronous worker CPU; final drain is reported
separately. These measurements do not establish a universal speedup, FPS gain,
engine-only memory reduction or Cleanroom 0.7.0 performance.

## Verification and reproduction

All 191 unit tests passed (11 added), including concurrent producers/drains,
signed keys, detached-batch reentrant requests, duplicate viewer requests,
departure/disconnect, chunk replacement, reentrant cancellation and default-light
partial resets. The native correctness matrix checks ordinary/direct placement,
initial-send races, existing-entry watch/event ordering, native CQR generation,
completed client synchronization and old-cache repair.

[Validation](validation.json), [native measurements](native-results.json),
[queue microbenchmarks](micro-results.json), [protocol](protocol.json),
[artifact hashes](artifacts.json), [raw reports and replay source](raw-data.zip),
[delivered checksums](SHA256SUMS.json).

The helper source invokes the installed CQR generator and reads its ship from
that mod's own jar. CQR structure assets, Minecraft saves, engine binaries,
account data and full game logs are not distributed in the evidence bundle.
Local paths in the orchestration scripts require adaptation. The test helper
is absent from the production artifact and every timed game.

To replay the correctness checks, use a disposable checkout and copy the
bundled `helper-source/com/sumirelabs/issueaudit` directory into its main Java
source set. Build `remapJar` with JDK 25, then extract only those diagnostic
classes and a matching `issueaudit` mod description into a separate helper jar;
install it alongside the desired baseline or candidate engine. Never install
the unfiltered combined jar as a second Pulsar engine. Use fresh JVMs and a
disposable flat save folder named `Bench Updates` for each case.

Extract CQR's `assets/cqrepoured/defaultConfigs/structures/ships/ships_large/Galleon.nbt`
from the installed 2.8.0B jar into the study path expected by `ShipAudit`.
`/issueaudit ship` runs both crystal packet races and native ship generation.
`/issueaudit ship legacy` under the baseline saves the corrupt cache before the
reference relight; `/issueaudit loadedship` under each engine replays that saved
fixture. Adapt the helper paths to a dedicated local study directory first.

For native timing, create a fresh seed-1 default flat template with structures
disabled and an empty region directory, supply the recorded mod versions and
matching default Pulsar config, then run the saved protocol. Do not reuse the
ship fixture or a world containing old benchmark roofs. The raw bundle also
contains both independent queue implementations used by the microbenchmark.
