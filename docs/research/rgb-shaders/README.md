# RGB shader integration validation

Date: 2026-10-18. Implementation follows `322d55c` on `feat/rgb-engine`.
This is a versioned compatibility result, not universal GLSL support or an FPS
benchmark. Pulsar's LGPL license is unchanged. Loader/pack implementations and
assets are not included in the evidence.

## Matrix

| Loader | Runtime | Packs |
|---|---|---|
| Demonica 0.8.2 + bundled Celeritas | Cleanroom 0.7.7-alpha, Java 25 | all seven below |
| Actinium alpha-0.0.12 | Cleanroom 0.6.12-alpha, Java 25 | all seven below |

Complementary Reimagined/Unbound r5.9.3, BSL 10.1.8, Photon v1.3b,
Solas 3.8, Pastel Universal v1.200 and Tea V2.0.0 Dev10. Exact downloaded
files, hashes and URLs are in `shader-packs.json`. Tea used an existing local
copy whose filename matched the official listing; its hash identifies that copy.
Photon is not officially listed for 1.12.2.

The published Actinium release failed in the Cleanroom 0.7.7 baseline before
RGB shader lighting ran (OpenGL context/GLFW/GLSM API incompatibilities).
Partial loader bootstrap shims were investigated and removed, not shipped.

## Protocol

Use disposable Prism profiles with a closed quartz room, an authoritative
TileEntity source that changes only its RGB value, a white sheep, normal/Fast
TESR quads, quartz particles and a held quartz block. Source canonical light
stays 15 while emission changes red to blue without a block/checkLight call.
Check CPU snapshots, the actual deferred pipeline, installed sampler/program
counts, GPU-field readiness and captured framebuffer colors after settling.
A configured shader selection without a compiled pipeline is never accepted.

The final real-pack runs use X=-1,000,000, Z=1,000,032, including negative chunk
indices and the loader's large-coordinate camera wrapping. Earlier runs also
cover X=20,000. Enable, disable and re-enable each pack; confirm shader ownership
switches and visible color returns. The normal renderer intentionally desaturates
weak light through its blend curve, so its appearance differs from pack lighting.

An independently authored diagnostic pack also exercises filtered red/blue sky,
normal/Fast TESR and particle submissions, a held item, and Overworld blue →
Nether green → Overworld blue. The dimension test requires a new green GPU image
in the no-sky dimension and blue again on return, not just a changed dimension ID.
Read the diagnostic normal/specular/alpha MRT sentinels in a fixed 32×32 ROI
and the diagnostic shadow-color target before/after a color-only change. This
checks those buffers for this scene; it is not exhaustive PBR/resource-pack
coverage. Generic-lightmap full-bright/alpha guards and unchanged shadow-only
source transformations also have synthetic unit coverage. Full-bright/emissive
behavior in named pack lighting functions follows the pack's own bypasses.

## Scope and costs

Named lighting adapters use one eight-voxel field stencil per lighting invocation;
camera reflection is cached once per rendered frame. Immutable section views are
reused by plane identity. Constant sections need only metadata; nonuniform data
uses a lazily growing 4,096-row cap (64 MiB GPU data), plus the separate index,
CPU metadata and retained immutable planes. Upload traversal has a 2 ms soft
client-tick budget, excluding a guarantee on allocation/driver stall duration.
Missing sections fall back to the pack's own lighting. Integer owner coordinates
prevent ring aliases; world/chunk replacement invalidates old entries.

The 80 lighting-package class bytecodes match the preceding engine artifact.
SCALAR excludes all RGB mixins at startup. Native SCALAR runs additionally check
no RGB chunk fields, no positional RGB callbacks and no shader atlas/program
patching. This supports engine isolation; no new FPS, total VRAM or cross-pack
performance claim is made from the color tests.

Solas/Pastel produced a one-shot GL_INVALID_OPERATION (1282) after some reloads
in the initial SCALAR loader baselines as well as RGB runs. Steady blue frames
and pixel captures were clear. Photon/Solas also produced 1282 on disable in the final SCALAR lifecycle
controls, with zero RGB programs/callbacks/atlas. Disable/re-enable observations
are recorded separately; no error-free reload claim
is made. These are compatibility limits, not hidden passing checks.

## Evidence

`summary.json` selects the seven real-pack cases from each final RGB run and
keeps links to the original raw reports. The first Actinium final run then tried
an early diagnostic pack, which failed due to a diagnostic GLSL definition; its
seven preceding real-pack results are retained, and the failure is not counted
as a passing diagnostic. Corrected diagnostic runs are stored separately.
Earlier harness/loader failures are listed in `attempts.json`.

Raw reports and stage hashes are under `raw/`. `reproduce.zip` contains the
original diagnostic Java/GLSL and local orchestration scripts, not third-party
shaderpacks or loader jars. The scripts document the exact disposable profiles
and require local Prism/test dependencies; they are evidence, not a portable
one-command installer. Screenshots are native captures, not generated mockups.
