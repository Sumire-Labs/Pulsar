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

## Remaining supported-source audit

The remaining built-in presets were compared against the locally pinned
SuperNova reference `22d8559c3064d77d115e58523c58cde824f4e214`.

- Botania blaze block: restored `FF0` (yellow), replacing the custom `FC6` amber.
- Botania buried petals and mushrooms: use SuperNova's dim dye palette, halving
  each component with `(component + 1) >> 1` before hue normalization. This
  preserves SuperNova's rounding and color ratios, while retaining the actual
  block's contextual light strength.
- Botania shiny flowers, floating flowers, pylons, glimmering livingwood and
  white sources already use the reference hues.
- Twilight Forest presets, Thaumcraft candles/crystals, Natura glowshrooms,
  Biomes O' Plenty crystal/glowshroom, Tinkers' stone torch and AE2 fixtures
  already match the corresponding reference hues.
- ProjectRed lamps and multipart lights already use the reference bright dye
  palette, with contextual power/inversion behavior preserved.
- Thaumcraft 6 colored nitor, Natura's additional amber variant and Ars Magica
  Rekindled presets have no direct entry in that pinned SuperNova reference;
  retain their current dye/material identities rather than invent a match.

No glass transmittance, source intensity, runtime tint curve or user/API override
precedence is changed by this audit. The existing 15-source screenshot gallery
predates these additional Botania adjustments; these blocks need in-game review.

## Additional 1.12.2 source ports

The next group adds hue presets only; contextual emission remains authoritative.
No optional-mod classes are linked and missing blocks are ignored.

| Mod | 1.12.2 mapping / hue |
|---|---|
| Vanilla | End rod `FAF`, magma `510` (Et Futurum counterparts) |
| Thermal Foundation 2.6.7.1 | `fluid_glowstone` `FC7`; `storage_alloy[type=lumium]` `FF0` |
| Thermal Expansion 5.5.7.1 | `cell` `800` |
| BuildCraft | `buildcraftcore:marker_volume` `037`, `marker_path` `070` |
| Railcraft | `lantern` material variants `DA8` |
| Forestry 5.8.2.427 | `beehives` `431`, respecting configured light level |
| BiblioCraft 2.4.6 | `lanterngold/lanterniron` `FED`, `lampgold/lampiron` white, `desk` `775` |
| OpenComputers 1.8.10 | assembler `055`, capacitors `052`, geolyzer `320`, power distributor `550`, screens/holograms white |
| Galacticraft | arclamp/bright air white; lit torches `DA8`, unlit torches `321` if emissive, glowstone torch `FD3` |

Thermal/BiblioCraft/OpenComputers IDs were cross-checked against the released
JARs already present in the E2EE server template. Thermal lumium is selected by
serialized property name rather than the old Storage metadata 11. The normal
SuperNova hues remain normalized to each source's 1.12 contextual intensity.

Source references: BuildCraft `8.0.x-1.12.2`, Railcraft `mc-1.12.2`,
ForestryMC `mc-1.12`, Nuchaz/BiblioCraft-Source, micdoodle8/Galacticraft `MC1.12`.
Exact checked-out commits are recorded below. Property selection uses Forge's
IProperty serializer, supporting enums that do not implement IStringSerializable.

Old BuildCraft construction markers and Railcraft firestone recharge blocks
have no direct verified match in these source trees. They are not registered
under speculative names. Thermal's old Glass metadata is likewise not copied
onto an unrelated 1.12 block. These entries remain version-specific gaps.
- buildcraft: 7c86626d09c4569fb2e0f458a16aaf8c3148ebaf
- railcraft: 34416755a312fd0ba6813d9a80169fe56dab369f
- forestry: 72e15b0f08daa25d8293cf28b5d5e1da200f9161
- bibliocraft: ec4b1dc1d009b0640be225fae5e226035f205506
- galacticraft: 33af6a3803b6ccc7c5030da13bcfda6c1cec4750
