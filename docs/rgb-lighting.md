# RGB lighting

Pulsar's RGB backend provides server-authoritative colored block and sky light.
It colors terrain, fluids, entity models, regular/Fast TESRs, particles and held
items in the tested Vanilla/Forge and Celeritas paths. The server selects whether
gameplay brightness follows the RGB maximum or an independent vanilla scalar
field. LGPL-3.0 is unchanged.

## Select the engine

New configurations select RGB. The entry in the `general` category of
`config/pulsar.cfg` on both server and client is:

```properties
S:lightingMode=RGB
```

Use `SCALAR` for the existing monochrome backend. Switching engines requires
restarting Minecraft and the server, including through the GUI selector.
Matching-version configurations retain an explicit SCALAR choice. A missing
or mismatched managed config version regenerates the main configuration with
the current defaults; keep the version unchanged. The new RGB defaults are
`rgbBlendMode=SQUARED_WEIGHT` and `rgbGameplayLight=RGB_MAX`.

Changing between RGB_MAX and ordinary gameplay brightness (SCALAR or
RGB/VANILLA) invalidates saved lighting and relights loaded chunks. Cache format
14 also relights older caches once, since they did not identify which gameplay
brightness policy produced their canonical light.

RGB clients connected to SCALAR servers retain monochrome lighting. In RGB mode,
the restart-only `rgbGameplayLight` policy selects:

- `RGB_MAX`: the brightest RGB component becomes ordinary block/sky light, as
  in SuperNova. This avoids a separate scalar propagation pass. Color absorption
  and emission can change spawning and every other gameplay light consumer.
  Both RGB and SCALAR clients need a compatible Pulsar version for authoritative
  light; unsupported clients are disconnected after a handshake grace period.
  The older RGB color handshake alone does not establish this capability.
- `VANILLA`: retain the independent scalar propagation pass and existing gameplay
  brightness. RGB filters affect appearance without changing scalar brightness.

SCALAR servers retain their ordinary monochrome behavior. The earlier native
loopback evidence used a private transport workaround; see
[initial-send validation](research/initial-chunk-send-retry.md). The completion
matrix separately checks unassisted connections; historical assisted evidence
does not establish that result.

## Client blending and user palettes

`rgbBlendMode` supports `ADDITIVE`, `SQUARED_WEIGHT`, `LINEAR_WEIGHT`, `OKLAB`
and `VIVID`, adapted from SuperNova. `LEGACY` retains the earlier prototype
formula. This client setting applies without restarting and refreshes loaded
terrain. It does not change server gameplay brightness.

RGB startup creates `config/pulsar-colors.cfg` and `config/pulsar-filters.cfg`.
SCALAR does not load or create these files. For example:

```properties
# pulsar-colors.cfg: source chromaticity
minecraft:torch = 15, 8, 3
# pulsar-filters.cfg: minimum absorption; transmit red
minecraft:stained_glass[color=red] = 0, 15, 15
```

Components are integers from 0 to 15. Omitted properties match every state;
`namespace:block:meta` and `namespace:block:*` support legacy selectors. Later
lines override earlier lines. Invalid lines warn and are skipped without
partially applying a selector. Source `0,0,0` disables RGB emission. Nonzero
source colors are scaled to contextual scalar emission, like the registration
below. To create an emitter with independent RGB strength, use the emission API.
Overrides take precedence over mod registrations and interface defaults.
Restart the server after editing; the main config version does not reset these
palette files. Changed profiles invalidate cached RGB lighting.

## Register sources and filters

Mods register static chromaticity during initialization, before
`FMLLoadCompleteEvent`:

```java
BlockLightColors.register(state, 15, 8, 3);
```

Import `com.sumirelabs.pulsar.api.BlockLightColors`. Each component is 0–15;
the peak is normalized to 15 and the existing contextual scalar emission sets
the actual strength. A color registration does not make a non-emitting block
emit light. Black is not a source chromaticity. Unregistered sources use white.
Defaults include warm torches/glowstone/lava, red redstone torches and cool sea
lanterns.

Filters use **minimum absorption**, not transmission:

```java
BlockLightFilters.register(state, 15, 0, 0); // absorb red: cyan transmission
```

Import `com.sumirelabs.pulsar.api.BlockLightFilters`. Zero adds no absorption;
15 blocks that component. Filters combine with existing contextual, fluid and
face opacity and do not open an opaque face. Stained glass and panes have
built-in defaults. Registries freeze after initialization; changing the profile
invalidates the RGB cache and relights it when chunks load.

For actual component strengths, use
`BlockLightColors.registerEmission(state, 8, 4, 0)`. Under `RGB_MAX`, this emits
`0x840` even if vanilla emission is zero. Under `VANILLA`, its peak is capped to
the existing scalar emission without increasing a weak source to full strength.

Blocks can implement `ColoredLightSource` or `ColoredTranslucency` for static
state callbacks, or `PositionalColoredLightSource` and
`PositionalColoredTranslucency` for world/position callbacks. Public values use
compact `0xRGB`, with four bits per component. Emission is actual strength;
interface transmittance is **15 = transmit, 0 = absorb**, unlike the absorption
registration API. For example:

```java
public int getColoredLightEmission(IBlockState state) { return 0x840; }
public int getColoredTransmittance(IBlockState state) { return 0xF00; }
```

Static callbacks run during initialization. Positional callbacks run on the
server world thread; workers read immutable samples. Loaded dynamic positions
are sampled each tick, including color-only changes without a block update or
TileEntity tick. Explicit registry/user overrides replace the corresponding
interface behavior. SCALAR does not invoke these RGB callbacks. Existing
contextual scalar brightness/opacity retains its world-thread sampling path.

## Rendering and validation limits

Without a shaderpack, rendering modulates the existing scalar lightmap/AO.
With a supported Iris-family loader and pack, a GPU light field supplies RGB
lighting to the pack's lighting terms. Material alpha, full-bright effects and nested
renderer state are preserved in the accepted native tests. Object rendering
requires four fixed-function texture units; unsupported hardware uses scalar
client lighting and logs the fallback.

Native rendering coverage includes Cleanroom 0.6.12-alpha and 0.7.7-alpha,
Java 25 and Celeritas 2.4.0-dev. Renderers that take over the reserved texture
unit and cached lists embedding mixed lightmap behavior require separate integration.
GLSM loaders use their emulated texture-combiner state rather than removed
fixed-function OpenGL queries.

SCALAR excludes the RGB chunk/state/buffer fields and renderer hooks at startup.
RGB uses packed propagation with a shared traversal and transfers color
snapshots. Its per-channel storage, snapshots and packed worker scratch have
different costs from SCALAR. `VANILLA` additionally propagates an independent
scalar field. Server lighting latency, client render callback time,
Java allocation and retained memory are different measurements; results should
be interpreted using their recorded protocols.

See [current measurements and native evidence](research/supernova-parity/README.md),
[RGB sky implementation](research/rgb-sky-prototype.md) and the
[completion plan](research/rgb-engine-roadmap.md).


## Demonica and Actinium shaderpacks

RGB has an optional, independently written bridge to the Iris-family APIs used
by Demonica and Actinium. It uploads immutable server light publications to two
integer textures and changes lighting terms after the loader transforms GLSL.
Terrain and object material colors are left untouched while the shader pipeline
owns lighting. Disabling or enabling a pack rebuilds terrain in the appropriate
representation. This is separate from SuperNova/Angelica's integration.

Validated loader versions are Demonica 0.8.2 with its bundled Celeritas on
Cleanroom 0.7.7-alpha, and Actinium alpha-0.0.12 on Cleanroom 0.6.12-alpha.
The tested Actinium release fails on Cleanroom 0.7.7 before the RGB shader bridge
runs; this integration does not repair the loader's platform incompatibility.

The pack adapters target Complementary Reimagined/Unbound r5.9.3, BSL 10.1.8,
Photon v1.3b, Solas 3.8, Pastel Universal 1.200 and Tea V2.0.0 Dev10. Later pack
or loader versions can change internal contracts and need validation. Photon is
not officially listed for Minecraft 1.12.2. This is not universal OptiFine or
arbitrary GLSL support. Unknown lighting layouts keep the pack's original terms;
simple lightmap programs have a fallback adapter, except with intermediate
geometry/tessellation stages that need their own position-varying contract.

Pack-specific tone mapping, shadows, emission, handheld light and PBR remain
under the pack's control. Named-term adapters preserve the pack's brightness
curve and apply the authoritative RGB hue/attenuation; the no-shader blend-mode
options do not replace each pack's mixing model. Full-bright bypasses already
implemented by a pack stay under that pack's control.

Only visible-area chunks are tracked, with owner coordinates preventing ring
aliasing. Uniform sections need no voxel texture rows. Nonuniform section data
is capped at 4,096 sections (64 MiB GPU data storage); the index texture and CPU
metadata are additional. Upload traversal has a 2 ms soft budget per client tick,
which is not a guarantee on GPU allocation/driver latency. A missing or
budget-exceeded section uses the pack's original lighting until uploaded.
Storage grows lazily and texture names are reused across world/pack switches.
Two texture samplers are allocated through the loader, without commandeering
existing pack samplers. SCALAR excludes all RGB shader mixins at startup and
creates no RGB shader atlas. No GPL/AGPL loader implementation or shaderpack
assets are distributed with Pulsar; the Pulsar license remains LGPL-3.0.

See the [shader compatibility protocol and native evidence](research/rgb-shaders/README.md).


## Forge login ordering

A common fix, enabled in both RGB and SCALAR, orders Forge's initial server
PLAY transition on the connection's Netty event loop. Previously, Forge could
switch the protocol while `SPacketLoginSuccess` was still queued for encoding,
causing an intermittent "Can't serialize unregistered packet" disconnect.

Only that channel-state operation is ordered. Player/world initialization stays
on its original thread, and packet IDs, encoding, authentication and compression
are not rewritten. No waiting or retry loop is added to the production path.
The old failed connections in the original RGB completion evidence predate this
fix. See the [controlled reproduction and validation](research/forge-login-order/README.md).
