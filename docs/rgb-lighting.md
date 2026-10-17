# Experimental RGB lighting

Pulsar's RGB backend provides server-authoritative colored block and sky light.
It colors terrain, fluids, entity models, regular/Fast TESRs, particles and held
items in the tested Vanilla/Forge and Celeritas paths. Gameplay light remains
an independent scalar field, so spawning and other vanilla light consumers do
not read an individual color channel. LGPL-3.0 is unchanged.

## Enable it

Set this entry in the `general` category of `config/pulsar.cfg` on both server
and client, then restart both:

```properties
S:lightingMode=RGB
```

Use `SCALAR` for the existing monochrome backend. SCALAR remains the default
while this experimental branch is being validated. The GUI selector also
requires a restart. Keep the managed config version unchanged.

RGB servers support SCALAR clients through canonical light packets. RGB clients
connected to SCALAR servers retain monochrome lighting. The native loopback
matrix checks all four combinations, dimension travel and rejoining. Its
transport test uses a private workaround for a legacy Forge LOGIN/PLAY race;
see [initial-send validation](research/initial-chunk-send-retry.md).

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

Position-dependent dynamic hue callbacks and user-editable color files are
outside this prototype. Existing contextual scalar brightness/opacity remains
sampled through the established world-thread path.

## Rendering and validation limits

Rendering modulates the existing scalar lightmap/AO rather than replacing it
with shader-level RGB lightmaps. Material alpha, full-bright effects and nested
renderer state are preserved in the accepted native tests. Object rendering
requires four fixed-function texture units; unsupported hardware uses scalar
client lighting and logs the fallback.

Native coverage uses Cleanroom 0.6.12-alpha, Java 25 and Celeritas 2.4.0-dev.
Cleanroom 0.7 rendering has not yet been verified. Custom GLSL programs,
renderers that take over the reserved texture unit, and cached lists embedding
mixed lightmap behavior require separate integration.

SCALAR excludes the RGB chunk/state/buffer fields and renderer hooks at startup.
RGB runs additional color propagation and transfers color snapshots, so its
cost differs from SCALAR. Server lighting latency, client render callback time,
Java allocation and retained memory are different measurements; results should
be interpreted using their recorded protocols.

See [current measurements and native evidence](research/rgb-render-performance.md),
[RGB sky implementation](research/rgb-sky-prototype.md) and the
[completion plan](research/rgb-engine-roadmap.md).
