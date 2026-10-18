# SuperNova parity contract

Reference: GTNewHorizons/SuperNova at
`22d8559c3064d77d115e58523c58cde824f4e214` (LGPL-3.0), inspected from source.
The requested outcome is a SuperNova-style RGB engine adapted to Pulsar's
Minecraft 1.12.2/Cleanroom architecture, with an independently selectable
SCALAR engine. A rendering milestone is not completion of that outcome.

## Current differences

| Area | SuperNova reference | Pulsar at `8c5454c` |
|---|---|---|
| Propagation | Packed RGB levels and a shared traversal | Three per-channel traversals, plus canonical scalar gameplay light |
| Storage | Per-channel 4-bit nibbles | Per-channel 4-bit nibbles and immutable snapshots |
| Gameplay brightness | RGB maximum synchronized to vanilla | Independent canonical scalar field |
| Sources | Static and position-dependent colored emission | Static chromaticity scaled by contextual scalar emission |
| Filters | Static and position-dependent transmittance | Static minimum absorption combined with contextual scalar/face/fluid opacity |
| User colors | Color and translucency configuration files | Initialization registration API only |
| Blending | Additive, squared weight, linear weight, OkLab, vivid | One prototype blend formula |
| Rendering | Vanilla tinting and Angelica integration | Vanilla/Forge/Celeritas tinting, entity/TESR/particle/hand scopes |
| Default | RGB | SCALAR while the prototype is validated |

Both rendering designs retain the scalar lightmap and apply a color tint.
SuperNova is not universally using a replacement shader RGB lightmap. Its
Angelica bridge cannot be copied as a Minecraft 1.12.2 renderer integration.
Pulsar's canonical gameplay publication and world-thread sampling rules must be
retained when adapting upstream ideas. The completed design may derive canonical
brightness from RGB maximum or retain an independent scalar field; this is an
explicit server policy, rather than an assumption fixed by the prototype.

## Implementation in the completion candidate

| Area | Pulsar completion candidate |
|---|---|
| Propagation | Packed `0x0R0G0B` worker values and one RGB queue per lane; three-pass implementation retained behind a diagnostic JVM switch |
| Block and sky storage | Three nibble planes, immutable client/cache snapshots and generation-checked publication |
| Gameplay brightness | `RGB_MAX` by default; optional independent `VANILLA` field; SCALAR remains independently selectable |
| Source API | Static and world-thread positional emission, actual RGB strengths, plus the original normalized chromaticity registration |
| Filter API | Static and world-thread positional transmittance; existing registration and user files use minimum absorption |
| User overrides | State/property and legacy metadata selectors, deterministic precedence, validation and profile invalidation |
| Blending | All five upstream modes, default squared weight, plus the earlier legacy formula; runtime terrain refresh |
| Rendering | Vanilla/Forge and Celeritas terrain, fluids, entities, normal/Fast TESRs, particles and held items |
| Default selection | RGB for new/regenerated configurations; valid explicit SCALAR configurations retained |

This is an adaptation, not binary compatibility with SuperNova's 1.7.10 API.
Public colors use compact `0xRGB`; only worker values use the spaced packed
layout. Pulsar retains 1.12.2 face/context/fluid handling, owner-thread sampling,
its chunk reservations and immutable publication. Direct sky remains a
per-component level-15 source; partial components propagate through the queue.
The upstream sky implementation is not copied indiscriminately.

RGB_MAX additionally requires a compatible client for authoritative canonical
light. A SCALAR client can negotiate this without RGB buffer/chunk fields.
SCALAR servers preserve their ordinary client compatibility. The documented
legacy Forge LOGIN/PLAY transport race is separate from RGB negotiation; raw
attempts and any ordinary reconnects must remain visible in the evidence.

## Completion and evidence

The scoped implementation and validation are complete. See the
[validation and performance report](supernova-parity/README.md) for 260 unit
tests, 43 native runs, four two-JVM mode combinations, retained initial
regression evidence, and the final counterbalanced performance comparison.

Packed RGB improves block updates in the tested workload; Sky and SCALAR have
no established slowdown in the retest, with uncertainty explicitly reported.
Custom GLSL and arbitrary third-party renderers require separate integration.
The user guide documents defaults, policy/client requirements, APIs and files.
