# Natural RGB lighting direction

The standing visual target is natural, restrained color across all supported
blocks: identifiable hues with a little more presence than muted defaults,
without uniformly saturated or neon-looking lighting. See [AGENTS.md](../AGENTS.md).
The user's Gleam reference is visual inspiration, not a target for identical rendering.

## Source palette adjustments

These are 4-bit RGB hues, normalized to each source's existing light level.
They do not add emission to blocks that are off or non-emissive.

| Source | Hue | Direction |
|---|---|---|
| Redstone torch / lit ore / powered repeater and comparator | `F11` | Red ember |
| Jack o'lantern | `F95` | Soft pumpkin orange |
| Glowstone and Chisel glowstone variants | `FCA` | Warm white |
| Fire | `FB6` | Warm amber |
| Lit furnace | `FA7` | Soft orange |
| Sea lantern | `BDF` | Cool white |
| Nether portal | `B4F` | Soft violet |
| Twilight Forest portal | `A4F` | Slightly cooler violet |
| Future MC lantern | `FB7` | Warm lantern light |
| Future MC campfire | `FA6` | Warm flame |
| Future MC soul fire lantern | `8DF` | Pale cyan |
| Botania blaze block | `FC6` | Warm amber |

Future MC 0.2.21's installed JAR was inspected: the IDs are `futuremc:lantern`,
`futuremc:campfire`, and `futuremc:soul_fire_lantern`. Lanterns retain level 15,
soul lanterns level 10, and campfires retain their `lit`-dependent 15/0 behavior.
This agrees with the upstream [registry](https://github.com/thedarkcolour/Future-MC/blob/58406daa6d7de87917b66d45b66afd8614fe6679/src/main/java/thedarkcolour/futuremc/registry/FBlocks.kt)
and [campfire implementation](https://github.com/thedarkcolour/Future-MC/blob/58406daa6d7de87917b66d45b66afd8614fe6679/src/main/java/thedarkcolour/futuremc/block/villagepillage/CampfireBlock.kt).
No Future MC classes are linked into Pulsar's preset registration path.

User palette overrides and mod-provided color APIs retain their precedence.
Other dye/aspect-specific presets retain their identifying hues. The common
render-tint adjustment below also applies to those RGB light sources.

## Low-light tint

The normal non-legacy tint path previously used `L / (60 - 3L)` as its hue weight,
pulling partially attenuated colors strongly toward neutral white. It now uses
`L / 15`, retaining a gradual fade near darkness without maximizing saturation.
At levels 7 and 12 the weights change from approximately 0.18/0.50 to 0.47/0.80.
The default blend mode stays `SQUARED_WEIGHT`; the legacy render path is retained.

This tint curve does not modify propagated or gameplay light. Changing source
hues can still change brightness *after colored filters* in RGB_MAX, as intended
by that mode. Maximum source strength and unfiltered reach are preserved.

The OKLAB forward matrix was also corrected to match its existing inverse,
following the [linear sRGB/Oklab definition](https://bottosson.github.io/posts/oklab/).
Isolated primaries now round-trip without introducing another hue.

## Glass observation

A production-engine fixture with four red-glass sides, a red-glass roof and an
opaque floor gives `C00` outside: no green or blue block-light component escapes.
Opening the roof gives `C74` through an unfiltered air route; closing it restores
`C00`. Roof removal also matches a freshly computed open enclosure over all cells.
The existing 35 closed-shell filter cases remain checked for both engine paths.

The supplied screenshot does not establish the exact cause of the visible pale
halo. Possible contributions include an open route, sky light and render tint.
The old low-light whitening is a confirmed render behavior, not a failure of the
red-glass absorption rule. Some intentional soft fading and sky mixing remain.
Actual appearance still needs the user's in-game visual check; no running instance
or save was modified during this work.

## Validation and applying the change

Full build with GPU tests: 390 tests, 386 passed, four opt-in benchmark tests skipped.
Seven GPU rendering cases passed. Preset tests check soul-light strength and
campfire off states; tint tests cover natural low-light color and OKLAB primaries.

Install the rebuilt Pulsar JAR and restart Minecraft. `/pulsar reload` reads palette
files, not new Java defaults or renderer code. Explicit `pulsar-colors.cfg` overrides
continue to win over the new built-ins.
