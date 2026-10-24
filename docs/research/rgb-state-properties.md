# Frozen RGB state metadata

This continues `9672c0d` on `feat/rgb-engine`. A separate RGB-only state Mixin
caches static source chromaticity and minimum filter absorption in one packed
integer. Scalar gameplay emission, contextual callbacks, directional occlusion
and fluid opacity continue through the existing paths. LGPL-3.0 is unchanged.

## Lifetime and compatibility

The cache captures the immutable color and filter registry references once per
computation. It records readiness for each captured reference and stores a result
only when both were already frozen. A freeze racing a lookup therefore cannot
mark an earlier default as permanent. Initial queries retain the previous API
behavior. Registered zero filters are distinguished from unregistered states.

Frozen values are deterministic. Concurrent first reads can compute the same
value repeatedly and store one atomic int; no lock or per-hit map lookup is
needed. Unknown IBlockState implementations retain a registry-lookup fallback.
This does not cache a block's live emission level or any world-dependent data.
Registry fingerprints, RGB cache format 2 and protocol 2 remain unchanged.

The new field is excluded entirely in SCALAR. Native checks verify the absence
of RGB chunk, palette and state interfaces, and 96 existing scalar-light,
scheduler, core and Mixin class files match the baseline byte for byte. The raw
payload is one int per RGB state; JVM alignment can make the object-size increase
larger than four bytes. No whole-game heap reduction is claimed.

## Validation

The build and all 214 JUnit tests passed, including packed-component/readiness
round trips. Eight native audit runs passed strict server/client/reference
checks: Vanilla and Celeritas skylight (16 cases each), two cache reload/migration
runs, Vanilla and Celeritas block light (10 cases each), SCALAR isolation, and
Thaumcraft/CQR regressions. The latter retain 112 crystal-state checks, initial
send/watch ordering and 68,800-cell generation/relight comparisons.

New native checks query colors/filters before registry freeze, then verify that
subsequent registration wins. Eight threads first-read an unused colored state
and must observe identical complete metadata. An exotic proxy state exercises
the fallback, including white emission, absent filtering and the existing
unregistered negative-absorption API behavior. Contextual source changes and
filtered dense edits remain covered by the independent light reference.

## Measurements

The short cohort uses four fresh JVMs per artifact and fixture, 2,000 warm-up
and 1,000 measured pairs per workload. Values below are medians of independent
JVM means in microseconds; parentheses show their complete range.

| Fixture / workload | Before | After | Median change |
|---|---:|---:|---:|
| Unfiltered / Sky opening | 823.8 (762.6–912.6) | 829.2 (827.5–847.7) | +0.7% |
| Unfiltered / Sky closing | 844.0 (784.1–971.0) | 856.8 (855.3–892.5) | +1.5% |
| Unfiltered / Block placement | 293.9 (277.7–326.0) | 320.0 (261.4–366.0) | +8.9% |
| Unfiltered / Block removal | 265.2 (255.9–299.3) | 287.2 (237.9–350.2) | +8.3% |
| Full-color fallback / Sky opening | 3219.0 (3159.4–3587.2) | 3087.5 (3003.4–3641.0) | -4.1% |
| Full-color fallback / Sky closing | 3375.6 (3346.1–3724.3) | 3311.8 (3197.6–3841.5) | -1.9% |
| Full-color fallback / Block placement | 288.7 (267.0–315.7) | 264.4 (242.4–345.4) | -8.4% |
| Full-color fallback / Block removal | 273.9 (255.6–292.1) | 254.8 (234.9–317.6) | -7.0% |

Short-cohort unfiltered block medians increased while filtered medians decreased.
Those observations are retained. An additional eight-JVM unfiltered cohort uses
10,000 warm-up and 5,000 measured pairs, with reversed adjacent-pair order in the
second half. It is analyzed separately rather than pooled with the short cohort.

| Longer-protocol workload | Before (µs) | After (µs) | Median change |
|---|---:|---:|---:|
| Sky opening | 757.3 (726.5–829.7) | 771.6 (742.3–791.5) | +1.9% |
| Sky closing | 791.6 (773.1–858.5) | 803.4 (770.1–824.2) | +1.5% |
| Block placement | 269.3 (259.2–284.6) | 243.3 (239.6–255.5) | -9.7% |
| Block removal | 254.8 (245.0–267.8) | 232.5 (230.9–237.8) | -8.8% |

All four longer-protocol adjacent pairs improved for block placement and removal.
Paired geometric changes were −9.3% and −8.6%; log-t intervals were −14.7% to
−3.5% and −13.8% to −3.2%. With only four pairs, exact two-sided sign-flip p is
0.125, and this is not a universal speedup claim. The short-protocol increases
were not reproduced under the longer protocol.

Sky medians increased slightly in the longer cohort, but paired geometric
changes were +0.29% for opening and −0.38% for closing, with intervals spanning
zero. No general Sky speedup or exact equivalence is established. Worker CPU
medians were approximately 9.81 s and 9.86 s for the whole measurement interval;
these include waiting/spinning and are not a per-propagation CPU measurement.
All 24 measured intervals recorded zero GC, and every update and both checked
light volumes passed the unchanged Lightbench checks. No run was discarded.

See [the evidence index](rgb-state-properties/README.md) for all samples, exact
source/artifact hashes and replay sources. This milestone establishes static
metadata caching; entity/TESR/particle rendering and the remaining acceptance
matrix in [the completion plan](rgb-engine-roadmap.md) are separate work.
