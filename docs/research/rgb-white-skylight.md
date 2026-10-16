# Filter-free RGB skylight

This continues `0a291d9` on `feat/rgb-engine`. RGB sky now avoids the three
component propagations when a filter-free neighborhood proves that all components
equal canonical sky. SCALAR remains separate, and LGPL-3.0 is unchanged.

## Eligibility and publication boundary

Horizontal propagation absorbs at least one level per block. With maximum level
15, a filter's effect can reach at most 14 blocks horizontally, although direct
sky can extend vertically. To publish white sky for the center 3×3 chunks, the
engine checks all vertical sections in the surrounding 5×5 chunks for filters.
Missing chunks and unknown containers/palettes take the original colored path.
Canonical changes outside the center 3×3 never authorize whitening the outer
ring: a filter just beyond the checked halo can still affect that ring.

The filter predicate is conservative. Any registered nonzero component absorption
counts as a possible filter, even when scalar opacity would mask its effect.
Thus eligibility can reject unnecessary cases, but cannot open an opaque face or
omit a known filter. The scalar sky algorithm and its fluid/contextual behavior
still compute the canonical field.

## Classification and invalidation

An RGB-only section-container mixin classifies Vanilla local palettes. A palette
without any filter state avoids scanning 4,096 cells. When filter entries exist,
occupancy is checked because an unused palette entry can remain after the last
filter is removed. Unknown subclasses and registry palettes are conservative.

The cache tracks palette/storage identity and a mutation revision. Nested writes
keep the revision odd until the outermost write finishes; a concurrent or changing
read falls back to color work. Setter and packet-read hooks invalidate the cache.
Load, initial-light, section-change and block-change tasks also invalidate the
center's classification, covering notified bulk writes that bypass setters.
As with scalar lighting, silent raw edits without lighting notification are not
a supported way to update a world.

Every new field and setter/read hook belongs to `MixinRgbFilterPalette`, which
the existing bootstrap plugin excludes entirely in SCALAR. The scalar palette
sampler, propagators and light-cache implementation are unchanged.

## White/color transitions and ownership

An eligible task runs only the canonical sky propagation. A white snapshot uses
that canonical field for all three RGB components. One owned immutable payload
can be shared by its four planes; mutable SWMR/Vanilla arrays never become shared
snapshot storage. The color engines themselves are created lazily.

Removing a filter can change RGB while leaving canonical sky identical. Eligible
jobs therefore explicitly reset formerly materialized color storage in the
center 3×3 and publish changed colors even when canonical propagation did not
touch those chunks. Mutable RGB sky storage is released there.

When a filter appears, component storage is materialized from the previous
canonical sky state, including null-section extrusion, zero/full states and
emptiness maps. Each component receives independent writable data. Color writes
cannot alter canonical sky or another component. Existing range relighting keeps
the complete component path and generation-safe publication.

Cache format 2 and protocol 2 remain unchanged: the represented light values are
the same. Existing colored caches remain usable, and white cached fields can
transition back to independent color work.

## Validation

The unit additions cover the complete halo, the smaller publication boundary,
unknown-class fallback, immutable white payload sharing and independent mutable
materialization. Existing randomized extrusion equivalence and scalar tests remain.

The native sky reference audit retains the fourteen sky/filter cases, including
split red/blue roofs, filter removal/reintroduction, missing sections, range
relighting and superseded generations. It additionally checks palette insertion,
removal, resize, same-bit-width packet reading, NBT reading, explicit invalidation
after a raw write, and conservative handling of custom/registry containers.

A separate boundary scene places a red sky opening three chunks from an eligible
white job. The opening illuminates an opaque roof's adjacent shadow in the outer
ring. That point must remain RGB `E00` with canonical/stored sky 14 before and
after the white relight, on both server and client with real geometry present.
The eligible job's mutable color storage must be released. This guards against
using a 5×5 proof to whiten its entire 5×5 publication area.

Native cache, block-light, SCALAR-isolation, Thaumcraft and CQR checks are repeated
against the same candidate. Formal timing compares the preceding RGB sky version
with this implementation using fresh JVMs and unchanged non-engine inputs. Exact
results and replay sources are stored in the companion `rgb-white-skylight`
directory. All 212 JUnit tests and the final native audits passed, including
16 sky cases in both Vanilla and Celeritas rendering configurations.

## Measured performance

Times below are microseconds from a server edit to lighting completion, using
four fresh JVMs per version and mode. Each value is the median of JVM means;
parentheses show the complete range of those means. Each JVM warms 2,000 edit
pairs and measures 1,000 pairs per workload. All measured edits and the initial
and final light volumes passed the unchanged Lightbench checks.

| Mode / workload | Before | After | Change in median |
|---|---:|---:|---:|
| RGB / Sky opening | 3157.4 (3119.6–3182.4) | 774.0 (765.6–801.1) | -75.5% |
| RGB / Sky closing | 3405.0 (3327.1–3449.2) | 818.4 (786.4–835.5) | -76.0% |
| RGB / Block placement | 308.7 (304.8–317.4) | 312.5 (310.7–327.5) | +1.2% |
| RGB / Block removal | 285.5 (280.2–289.9) | 268.5 (264.4–279.9) | -5.9% |
| SCALAR / Sky opening | 700.0 (691.7–706.3) | 720.3 (698.1–733.4) | +2.9% |
| SCALAR / Sky closing | 742.3 (732.5–749.6) | 742.6 (729.1–748.2) | +0.0% |
| SCALAR / Block placement | 84.5 (82.0–86.6) | 90.2 (82.3–99.4) | +6.7% |
| SCALAR / Block removal | 81.4 (79.5–82.2) | 85.4 (78.3–92.9) | +4.9% |

Filter-free RGB sky opening and closing take approximately 75–76% less time
(about 4.1 times faster) in this fixture. RGB block propagation was not changed;
its small changes should not be attributed to a new block-light algorithm.

SCALAR executable propagation and palette sampling were not changed, and the
native isolation audit confirms that neither RGB chunk fields nor the new RGB
palette fields exist in SCALAR mode. The measured SCALAR medians nevertheless
rose by 0.0–6.7%, depending on workload, with overlapping run ranges. These runs
do not establish exact performance equivalence or a statistically significant
regression. The raw observations are retained rather than reported as zero cost.

The separate filtered cohort uses two JVMs per version and places one red glass
block inside the eligibility halo, outside the checked light volume and measured
hole frontier. It forces the existing full-color path with unchanged verification.

| Filtered RGB workload | Before | After |
|---|---:|---:|
| Sky opening | 3543.1 (3139.2–3947.0) | 3154.1 (3064.1–3244.1) |
| Sky closing | 3752.2 (3331.1–4173.3) | 3321.2 (3212.2–3430.2) |

Filtered sky still requires roughly 3.1–3.4 ms in the candidate. The last baseline
filtered run was slower across all four workloads; with only two runs and this
variation, the cohort supports fallback correctness and gives descriptive timing,
not a precise filtered-path speedup claim. No run or GC observation was discarded:
three baseline unfiltered RGB runs collected once (3–4 ms), both baseline filtered
runs collected once (4 and 7 ms); the remaining measured runs recorded no GC.
No compilation, tests or archive work ran concurrently with formal measurements.

## Scope

This shortcut benefits verified filter-free regions. Filtered neighborhoods,
unknown storage implementations and range jobs keep three color passes plus
canonical propagation. It does not implement packed RGB queues or extend terrain
color rendering to entities, TESRs, particles or shaders. Snapshot sharing and
lazy storage are allocation improvements; total game heap and FPS require separate
measurements.
