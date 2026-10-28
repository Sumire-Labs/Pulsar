# Memory and publication audit, round 2 — 2026-10-29

Baseline: `cc542bf`. RGB remains the priority; the lighting results and public
packet/storage formats are unchanged. Full-game FPS/TPS improvements are not
inferred from the helper benchmarks below.

## Changes

- `RgbStorage.fromSky` freezes engine-owned canonical visible data, then gives
  the three independent channel wrappers copy-on-write views. It no longer creates
  a set of null wrappers only to discard them immediately.
- Externally owned vanilla arrays retain the independent-copy path; freezing them
  is explicitly rejected. A detached private working buffer can become visible
  directly instead of being cloned again. Published bytes never return to the pool.
- Packed RGB reads now use an explicitly read-only updating-data accessor. Reading
  an immutable plane no longer triggers a writable copy or a spurious dirty flag.
- Client fallback skylight resolves an absent-section run once, shares its owned
  extrusion, and shares constant sky planes. Changed live arrays are copied;
  unchanged sections can retain their previous immutable view.
- When RGB sky is present, or a dimension has no sky, the client retains a shared
  empty fallback array instead of an unused per-chunk reference array.
- Packed RGB dirty bounds use two short arrays. Values are limited to 0..4095;
  the existing 4096/-1 sentinels also fit. The propagation algorithm is unchanged.

## Measurements

The instrumentation harness and JVM settings are described in [round 1](memory-audit.md).
Numbers below cover the named object graphs, not total heap/RSS. Each time is the
median of three JVM medians (seven batches of 256 operations after 300 warm-ups).

| Operation / layout | Before graph bytes | After graph bytes | Before time | After time |
| --- | ---: | ---: | ---: | ---: |
| RGB sky initialization, 256 gradient chunks | 29,201,424 | 10,179,600 | 3.109 ms | 0.285 ms |
| Client fallback, 256 open-sky views | 9,534,480 | 25,632 | 3.465 ms | 0.025 ms |
| Client fallback, shadow gradient above absent sections | 9,534,480 | 1,082,400 | 3.525 ms | 0.189 ms |
| Client fallback, all sections nonuniform | 9,534,480 | 9,534,480 | 0.528 ms | 0.522 ms |

RGB sky initialization allocates 667,648 bytes per batch instead of 29,753,344
when the canonical input is engine-owned. The graph measurement uses independent
canonical data for each simulated chunk; the timing measures imports of existing
canonical input, excluding input construction. The shared input bytes remain
reachable and are counted in the storage graph, even though the import does not
allocate them. Do not add their size again as exclusive canonical memory.

A simple copy-on-write prototype still allocated more when all channels were
immediately overwritten. It was replaced by ownership-aware freezing and direct
publication of detached private buffers. With one channel rewritten, the paired
reference/current medians are 2.709/1.985 ms and allocations 29,753,344/10,178,560
bytes. With all three rewritten, they are 2.476/2.237 ms and
29,753,344/29,200,384 bytes. The test-only reference reproduces the previous import
loop and uses the same nibble API/pool, so these are scoped import/write comparisons.

The client fallback comparison runs the prior `RgbClient.refresh` loop as a test-only
reference against the new helper in the same JVM. Dense fallback data is a control:
there is no compression opportunity and timing remains approximately unchanged
(subsequent runs ranged 0.489–0.529 ms for the reference and 0.519–0.538 ms for the
new initial-capture path; these small differences overlap run-to-run variation).
Large speedups for absent sky apply to this helper, not to the entire lighting engine
or the game. The measurements above are initial captures, without previous-view reuse.

The newly constructed RGB engine pair is 37,400 bytes instead of 39,400 for 16
terrain sections, and 99,032 instead of 105,832 for 64 sections. This additional
reduction is the narrower dirty-range arrays, not a reduced queue limit.

Regression checks cover independent writes to shared RGB channels, source mutation,
full/zero/null/hidden states, last-cell packing, every fallback cell across negative
height and missing sections, immutable reuse, and the actual transformed-chunk client
refresh path. The normal and optional renderer/GPU suites remain part of validation.

## Selected whole-pack comparison: E2EE

The user proposed [Enigmatica 2: Expert — Extended](https://www.curseforge.com/minecraft/modpacks/enigmatica-2-expert-extended).
The release selected for a future reproducible comparison is **v1.87.3**, rather
than a moving development branch. Its [pinned manifest](https://github.com/Krutoy242/Enigmatica2Expert-Extended/blob/v1.87.3/manifest.json)
contains 432 file entries and includes:

- Alfheim 1.6
- Cleanroom Relauncher 1.1.4 and Fugue 0.24.4
- Nothirium 0.4.9-beta, Naughthirium 2.3.0 and the Nothirium LittleTiles patch
- VintageFix 0.7.1

Phosphor appears in the repository's broader MODS document, but is not in this
release manifest. The manifest is the starting point for the comparison inventory;
the actual installed instance must still be checked.

The pack's manifest declares Forge as its bootstrap loader. Record the actual
Cleanroom and Java versions after relaunch, and use identical versions/settings
for both sides. Use separate copies of the same world and replace only the lighting
engine: never run Alfheim and Pulsar together. Compare Alfheim, Pulsar SCALAR and
Pulsar RGB separately with matching view distance and workload.

Start with server-side retained heap/allocation/tick measurements to avoid renderer
differences. The default Nothirium client path has not been validated for Pulsar's
RGB rendering. If a controlled Celeritas comparison is needed, use it on both sides
and record that deviation from the stock pack; it is not a stock-E2EE result.

**Pending:** install/launch validation, representative world and movement/edit script,
whole-pack memory/performance capture, and the user's later in-game visual inspection.
The pack has been inspected, not installed or benchmarked in this turn.
