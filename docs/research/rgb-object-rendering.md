# RGB object rendering

This report records the original rendering milestone. The subsequent
[session matrix and performance report](rgb-render-performance.md) completes
the pending measurements below and records the later initial-send repair.

This work extends the existing RGB block/sky fields to entities, TileEntity
special renderers (TESRs), particles and first-person held items. SCALAR keeps
its existing render classes: all additions use the RGB-only mixin selection.
LGPL-3.0 is unchanged. The 15 accepted native runs and 220 JUnit tests are recorded below. Client
performance and the full remote session matrix were still pending at this
milestone, so it did not declare the whole engine complete.

## Rendering model

Object lighting uses the same `RgbClient.tintAbgr` sampling and modulation as
terrain. Interpolated entity eye positions, tile centers and interpolated
particle positions select the color. Canonical scalar lightmaps and terrain AO
remain in use. This is colored modulation of scalar lighting, rather than a
shader-level replacement of Minecraft's lightmaps.

Models using fixed-function material colors receive a scoped texture-env
multiplier on the last fixed-function texture unit. A private one-pixel white
1D texture makes the unit complete. RGB combines `PREVIOUS * CONSTANT`; alpha
passes through unchanged. The setup follows the
[Khronos ARB_texture_env_combine specification](https://registry.khronos.org/OpenGL/extensions/ARB/ARB_texture_env_combine.txt).
The 1D binding avoids replacing mods' 2D sampler bindings. Target enables,
texture-env state, the 1D binding and both actual/cached active units are restored
at the end of the enclosing scene. Material `glColor` values are not rewritten.

Completed COLOR vertex ranges receive CPU modulation once, after material
colors have been written. The uniform multiplier is suspended while those
vertices are uploaded. Buffer-generation tags prevent duplicate tinting and
invalidate on buffer reuse; Fast TESR and ordinary particle batches carry
per-object color into their later shared draw. Bulk particle vertex writes use
the same range processing. There is no per-vertex `endVertex` injection.

Nested scopes reuse arrays and clear closed owners. Wrappers close scopes in
`finally`, including when a renderer throws. Full-bright lightmap coordinates,
disabled lightmaps and full-bright per-vertex UVs retain their original color.
Display-list compilation does not record transient RGB state; replay uses the
current object multiplier. World rendering, held-item rendering and breaking
TESRs enter their own scopes. Ordinary inventory/GUI rendering does not open a
world scope, and debug bounding boxes use an unlit scope.

## Compatibility boundaries

The native target is Vanilla/Forge and Celeritas's fixed-function object paths.
Custom GLSL programs, renderers which explicitly take over the reserved last
texture unit, and cached COLOR display lists mixing lit and full-bright vertices
need separate integration. Four fixed-function texture units are required;
unsupported hardware retains scalar client lighting and logs the fallback.

RGB propagation, protocol version 2, RGB cache version 2 and the scalar cache
format are unchanged by this rendering change. No renderer hook or buffer tag
is added to a SCALAR startup. Retained fields, buffers and actual frame-time or
allocation costs are assessed separately from server Lightbench timings.

## Native validation

Vanilla and Celeritas runs check actual entity and TESR GPU RGBA, original
material alpha, full-bright output, current-color display-list replay, nested
renderer exceptions, held-item scopes and restoration of the reserved texture
unit. Fast TESRs and ordinary/bulk/full-bright particle batches are checked in
their completed vertex buffers. Final daytime tests use a cyan skylight filter
and additionally check negative signed lightmap coordinates. All accepted
frames end with zero draw depth and no world scope.

The 13-run matrix also repeats the independent block/sky references, cache
reload/migration, SCALAR startup isolation and Thaumcraft/CQR checks. Two
focused final actor runs exercise the signed-coordinate repair with Vanilla
and Celeritas. All 220 JUnit tests pass. The 89 non-RGB light, mixin and core
classes compared with `0248f97` are byte-identical. This is isolation evidence,
not a numerical claim about frame time or retained memory.

A separate native connection test found that Forge can fire logout for a player
whose connection is absent. RGB logout now removes that player and other stale
connections without dereferencing null or removing unrelated live peers. The
native test inserts both missing and stale clients and verifies that the live
host remains registered.

At this milestone, the complete remote matrix remained pending because initial login also failed
in a SCALAR/SCALAR control before RGB negotiation: `SPacketLoginSuccess` is
encoded in PLAY state despite requiring LOGIN. No generic transport workaround
is added to Pulsar by this rendering milestone. RGB remains opt-in and SCALAR remains the default while further compatibility
and performance work continues.

Evidence: [native reports, source hashes and replay diagnostics](rgb-object-rendering/).
