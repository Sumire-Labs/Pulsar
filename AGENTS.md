# RGB visual direction

The user's standing preference is SuperNova-like source colors and brightness
with natural, restrained rendering across existing and future RGB blocks.
Suppress the white appearance of colored-glass light: softening dim hues must
not restore absorbed RGB components. Avoid Gleam-like neon intensity.

- Match each source's material and identity with soft, believable hues.
- Preserve source light levels and lit/unlit behavior unless explicitly requested.
- Keep dye/aspect identity, mod-provided color APIs and user palette overrides.
- Tune low-light color fading gently; do not maximize saturation globally.
- Distinguish propagation/filter correctness from sky mixing and render tint.
- Do not weaken colored-glass filtering to make the result look softer.
