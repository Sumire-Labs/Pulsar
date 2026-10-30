# SuperNova-like RGB lighting

The visual target is SuperNova-like source colors and brightness, with restrained
rendering and less whitening through colored glass. This supersedes the earlier
Gleam-inspired palette. Source strength and lit/unlit semantics remain contextual:
Pulsar does not raise Future MC soul lanterns from level 10 to 15 merely to match
SuperNova's different backport. User overrides and mod color APIs retain priority.

## Palette

Hues follow SuperNova DefaultColors, LightColors, EtFuturumColors and
CampfireBackportColors (reference commit 22d8559c3064d77d115e58523c58cde824f4e214).
Values are normalized to the existing source level in Pulsar.

| Source | Hue |
|---|---|
| Torch | `DA8` |
| Glowstone / Chisel glowstone | `FD3` |
| Lava / lit redstone lamp | `FCA` |
| Redstone torch / powered repeater and comparator | `611` |
| Lit redstone ore | `511` |
| Fire | `EC0` |
| Jack o'lantern | `FDB` |
| Lit furnace | `DCA` |
| Sea lantern | `AFF` |
| Nether portal | `72B` |
| Twilight Forest portal | `A0F` |
| Future MC lantern | `FC5` |
| Future MC campfire | `FCA` |
| Future MC soul fire lantern | `9EF` |

## Dim light and glass

The non-legacy renderer uses SuperNova's `L / (60 - 3L)` curve as a softening
weight. Instead of interpolating each normalized tint component `t` toward white,
it uses `t + t * (1 - t) * (1 - weight)`. This keeps zero components at zero,
softens intermediate components without exceeding one, and retains neutral light.
It applies to all supported sources without filter-provenance storage or extra
world lookups. Genuine white sky/light can still mix with colored light.

The generic shader lightmap bridge also stops adding its neutral `0.6` term to
RGB hue. That term remains in canonical brightness attenuation. Named shader
adapters already preserve missing color components. LEGACY is retained as the
original comparison mode and does not receive this change.

These rendering changes address an identified whitening mechanism. They do not
prove that every reported pale area has the same cause. An open roof permits
unfiltered light to travel around glass, and vertex sampling can mix nearby light.
The production-engine fixture checks closed, open and reclosed red-glass shells
and compares packed propagation with independent channel propagation.

## Validation and use

Regression tests cover absent primary channels at levels 1–15, material alpha,
neutral light, mixed sky, all tint modes, and the actual GLSL on a GPU. Palette
tests retain campfire off states and soul-lantern level 10.

Install the rebuilt JAR and restart Minecraft; `/pulsar reload` alone does not
replace renderer code. Existing `pulsar-colors.cfg` overrides still take priority.
Visual equivalence across Minecraft 1.12.2 and 1.7.10 is approximate and requires
in-game review; the earlier screenshot gallery shows the build before this change.
Validation: full build passed; 391 tests, 387 passed, four opt-in benchmarks skipped. All eight actual-GPU cases passed.
