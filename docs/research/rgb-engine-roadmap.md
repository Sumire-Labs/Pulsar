# RGB engine completion plan

The first complete RGB target includes terrain, entities, TileEntity special
renderers (TESRs) and particles with Vanilla and Celeritas rendering. SCALAR
remains available with its existing update paths; LGPL-3.0 is retained. Work is
on `feat/rgb-engine`, using coherent feature/performance commits.

## Established baseline

- Authoritative RGB block and sky fields, independent scalar gameplay light.
- Static source chromaticity and colored transmission, contextual scalar
  emission/opacity, sided blocks and Fluidlogged integration.
- Immutable snapshots, chunk synchronization, cache migration, generation-safe
  range publication and unload protection.
- Vanilla/Forge terrain and fluid rendering plus Celeritas terrain rendering.
- Filter-free sky shortcut and reduced redundant component calculations.
- Native references and recorded performance comparisons. Entity/TESR/particle
  paths now have native fixed-function coverage; the full remote/session,
  current-platform and client-performance matrix is still incomplete. The
  engine is not declared complete. See [object rendering](rgb-object-rendering.md).

## Milestones and acceptance criteria

1. **Static RGB metadata**: one RGB-only packed per-state cache, published only
   after both registries freeze. Pre-freeze calls must not poison it; unknown
   states retain the lookup fallback. Contextual light and sided/fluid opacity
   must never enter this static cache. SCALAR must gain no field or mutation hook.
2. **Dynamic render paths**: apply the current RGB light model to entity models,
   TESRs and particle vertices. Handle nested renders and restore color on normal
   return and exceptions. Preserve vertex alpha, custom base colors, full-bright
   effects and rendering outside a world. Terrain must not be tinted twice.
3. **Native rendering matrix**: living entities, dropped items, block/item entity
   models, signs/chests and representative custom TESRs, ordinary/full-bright
   particles, GUI/hand state isolation and renderer exceptions. Verify real GL
   and vertex-buffer results in Vanilla and Celeritas, including colored sky.
4. **Session and compatibility matrix**: rejoin, dimension/world changes, full
   chunk replacement, dense edits and unloads; RGB and SCALAR client/server
   combinations. Repeat the existing Thaumcraft/CQR/Fluidlogged checks.
5. **Performance and usability**: measure server update latency and the added
   client rendering work separately. Retain all runs, report variance and avoid
   FPS/heap claims from server timings. Document color/filter registration,
   restart-only mode selection, supported render paths and remaining limits.
6. **Release readiness**: complete the matrix and review public documentation
   before selecting RGB as the release default. Registry/protocol/cache changes
   require explicit migration decisions. A milestone passing does not mark the
   whole engine complete while later acceptance criteria remain unchecked.

Packed RGB queues/shared traversal are a possible later performance step. The
current three color propagators remain the reference implementation until an
alternative passes independent decrease/increase, filter, face, fluid, boundary,
initialization and range-relight tests and demonstrates an actual benefit.
