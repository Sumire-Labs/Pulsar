# Pulsar Lighting Engine


This is a mod exclusively for Cleanroom. It requires Java 25 and Cleanroom 0.6.10 or later.

Pulsar is an asynchronous lighting engine built for [Cleanroom](https://github.com/CleanroomMC/Cleanroom).
It reimplements Starlight-style algorithms for Cleanroom and runs lighting calculations on separate threads.

## Requirements

- [Cleanroom 0.6.10-alpha or newer](https://github.com/CleanroomMC/Cleanroom/releases/tag/0.6.10-alpha)

## Features

- Runs server-side light propagation on separate threads.
- Allows parallel tasks to share read-only chunk halos while keeping light writes isolated.
- Batches large groups of block changes instead of processing lighting separately for every edit.
- Caches light data in each chunk's NBT to avoid unnecessary full relights.
- Keeps Vanilla's block-light and sky-light data synchronized, so removing Pulsar does not corrupt your world.
- Includes various lighting bug fixes and quality-of-life improvements.
- Adds natural lighting to Thaumcraft crystals.
- SCALAR integrates with Celeritas, Actinium, and Nothirium; RGB rendering is validated with Vanilla/Forge and Celeritas.

### RGB lighting

Pulsar includes SuperNova-style packed colored block/sky lighting for
terrain, fluids, entities, normal/Fast TESRs, particles and held items. New
configurations select RGB, squared-weight blending and RGB_MAX gameplay light;
valid explicit SCALAR settings are retained. RGB_MAX requires compatible Pulsar
clients. The optional VANILLA policy keeps independent gameplay brightness.

Vanilla/Forge and Celeritas rendering are validated on Cleanroom 0.6.12-alpha
and 0.7.7-alpha; other renderer integrations require separate validation.
RGB shader lighting is also validated with Demonica 0.8.2 on Cleanroom 0.7.7
and Actinium alpha-0.0.12 on Cleanroom 0.6.12.

The current release-candidate shader target is **Demonica 0.9.0 + its pinned
Celeritas d94ec1e5 + Complementary Reimagined r5.9.3**, on Cleanroom 0.7.4-alpha
and Java 25. The 2026-10-26 isolated development and production-client checks on AMD Radeon 8060S
covered day/night, stained glass, a chest, first/third person, shader OFF/ON and
Overworld/Nether/return. Runtime assertions confirmed shader injection, sampler
registration and a ready GPU light field after switching and dimension changes.
The production check used unmodified Demonica/Celeritas release binaries and the
remapped Pulsar candidate jar, with Fugue 0.25.5 and Scalar 3.9.0 as Cleanroom's
required patch mods. The development check used MCP-mapped jars. Both completed
the scene/switching/dimension script; the test diagnostics mods are not distributed
with Pulsar. This is a tested combination, not a guarantee for every shaderpack,
GPU or modpack. Demonica 0.9.0 requires
Cleanroom 0.7.4 or later and its exact Celeritas pin; do not substitute an arbitrary
Celeritas build. See the [Demonica requirements](https://github.com/ndellagrotte/Demonica/releases/tag/v0.9.0).

`./gradlew build -PpulsarGpuTests=true` also runs a hidden-window GPU test of the
shipped light-field GLSL: mixed colors, alpha, fullbright, missing chunks,
independent scalar attenuation, neutral white and filtered night sky. Without that
flag the GPU test is explicitly skipped, while the normal tests still run.

### Source presets and remaining SuperNova differences

Common vanilla light sources now have SuperNova-derived hues, including fire,
portals, furnaces, ender chests and redstone sources. Presets retain the original
contextual emission strength. Optional presets are ported by verified 1.12.2
registry IDs and named state properties, not by blindly reusing 1.7.10 metadata:

| Mod | Source-color coverage |
| --- | --- |
| Botania (`1.12-final`) | Shiny/floating flowers, buried petals, mushrooms, glimmering livingwood, pylons, blaze blocks, mana/elf glass and panes, bifrost and other neutral sources |
| Twilight Forest (`1.12.x`) | Fireflies/jars, portal, lit cinder furnace, trollberries, mushgloom and five force-field colors across their axis states |
| Thaumcraft 6.1.BETA26 | Six primal crystal types, 16 candles and 16 nitor colors |
| ProjectRed Illumination (`1.12.x`) | Normal/inverted block lamps via their TileEntity color and current contextual emission; multipart lights are not included |
| Tinkers' Construct (`1.12`) | Stone torches, retaining their original light level |
| Biomes O' Plenty (`1.12.x`) | Crystal blocks and only the glowshroom variant of mushrooms |
| Natura (`1.12`) | Green, blue and purple glowshrooms, including their large mushroom blocks |
| Chisel (`1.12/dev`) | All glowstone variants across `glowstone`, `glowstone1` and `glowstone2` |
| Applied Energistics 2 (`rv6-1.12`) | Quartz vibrant glass and quartz fixtures; neutral white presets |
| Natura Legacy (`1.12`) | Existing Natura sources plus amber glowshrooms and large amber glowshroom blocks |
| Ars Magica 2: Rekindled (`1.12.2`) | All 16 Mage Light dye states, vinteum torches, warding candles, Otherworld Aura and celestial prisms |

The additional mappings were checked against the upstream 1.12 sources:
[Tinkers' Construct](https://github.com/SlimeKnights/TinkersConstruct/tree/c01173c0408352c50a2e8c5017552323ce42f5b4),
[Biomes O' Plenty](https://github.com/Glitchfiend/BiomesOPlenty/tree/5ad77116382bec782a023926b8dab17a6ea5599d),
[Natura](https://github.com/progwml6/Natura/tree/d5b4bdd1767176dfb17da2298c5f9458f1839588),
[Chisel](https://github.com/Chisel-Team/Chisel/tree/a0df1047a979bc55b3c81d5875e6890c21063bec), and
[AE2](https://github.com/AppliedEnergistics/Applied-Energistics-2/tree/5554ba903e74c2980b2e395c78555b40fc379e80).
Natura colors use the named `type` property, since its metadata order changed.
BoP glowshrooms use `mushroom[variant=glowshroom]`, not the old `flowers2` ID.
Non-emitting Chisel lavastone and old-only targets are not assigned invented light levels.
These additions have registry/state-selection tests; full modpack runtime validation is pending.

Natura Legacy mappings were checked at [d3ff86b](https://github.com/Elite-Modding-Team/NaturaLegacy/tree/d3ff86b4dc31a3d2b95fa8db53f6518a4ed55fe8),
and Rekindled at [0031912](https://github.com/WinDanesz/ArsMagica2/tree/00319125d18ae89d4a403e7b3661aefc166509c4).
Amber uses a Pulsar-selected warm hue `(15,10,3)`. Rekindled torch/aura hues follow
their particle/renderer colors; candles use warm amber, prisms neutral white, and
Mage Light uses the existing bright dye palette. These are source-color presets,
not changes to particles or spell rendering. Mana-battery power colors, flicker
affinities, crystal-marker variants and other unlisted Rekindled sources do not
yet have dedicated RGB presets.

Missing blocks, unknown layouts and blocks providing their own RGB source API
are left alone by the static presets. ProjectRed's adapter uses the positional
API on the world thread; workers never inspect its TileEntity. Off lamps stay
off. Explicit API registrations and user palette files take precedence. Presets
adopt hues while retaining each mod's contextual brightness, rather than copying
SuperNova's absolute light strengths. Source/binary checks and adapter tests do
not certify every combination of the full mods or their addons.

The entire SuperNova mod catalogue and palette-dump command are not yet ported.
Public relight-completion futures and general OptiFine rendering compatibility
remain outside the verified release-candidate scope.

Select `RGB` or `SCALAR` using `general.lightingMode` in `config/pulsar.cfg`.
Changing the engine or gameplay-light policy requires restarting Minecraft and
the server. RGB source colors and filters can be customized in
`config/pulsar-colors.cfg` and `config/pulsar-filters.cfg`. After editing the
**server's** files, an operator can run `/pulsar reload` without restarting.

Both files are validated as one transaction. Missing files, invalid values,
unknown block/state selectors or files larger than 2 MiB reject the reload and
retain the current palette. Removing an override restores the original preset
or mod/API registration, including dynamic callbacks; it does not turn registration
back on after initialization. Startup still logs/skips invalid rows as before.

The coordinator waits for running RGB tasks on a separate thread, then updates
the palette and contextual samples on the world thread. State/section caches are
versioned, old saved-light profiles are invalidated, and **all currently loaded
server chunks** are queued for relighting. Clients receive normal authoritative
light updates, including shader fields. The success message means applied/queued,
not that every chunk or client frame has already finished. Large worlds or slow
mod callbacks can cause a temporary sampling/relighting load. If sampling fails,
the old palette and its actual samples are restored; details are logged.

Only these two RGB palette files are reloaded. `pulsar.cfg` engine/gameplay policy
changes still require restarting. In RGB_MAX, changed transmitted light also
changes gameplay brightness; VANILLA retains its independent scalar light.

Stained glass and panes use SuperNova's dedicated light-transmittance palette,
not the block texture's dye/albedo values. Red, green and blue glass preserve
their selected component (with the normal propagation falloff); gray, brown and
black intentionally absorb more light. A warm torch still contains less blue
than red, so blue glass cannot make it as bright as an unfiltered source.
Default torch, glowstone, lava and redstone-torch hues follow SuperNova while
retaining Pulsar's existing contextual source brightness. Explicit palette/API
overrides keep precedence. Restart the server and client after updating; old RGB
caches with a different palette profile are rejected and rebuilt on chunk load.
In RGB_MAX, changed transmitted light also changes canonical gameplay brightness;
VANILLA retains its independent scalar gameplay light. SCALAR is unaffected.

Reference: [SuperNova's palette and filters](https://github.com/GTNewHorizons/SuperNova/tree/22d8559c3064d77d115e58523c58cde824f4e214/src/main/java/com/mitchej123/supernova/config).

The shared server lighting pool uses a 5x5 read halo and a 3x3 write region per task.
Only read-only halos may overlap. For compatibility diagnosis, `performance.conservativeLightTaskReservations=true`
restores the previous exclusive 5x5 policy after restarting Minecraft/the server.

### TileEntity Lighting

Pulsar samples position-dependent light values on the server thread because MOD callbacks may access live TileEntities.
Fallback polling for MODs that change light without notifying Minecraft uses a world-wide round-robin cursor.
It also checks neighbouring contextual blocks and fluids which may depend on a TileEntity, even if that TileEntity's own light stays unchanged.
By default, each world polls up to **4096 sources or 1 ms per tick**, whichever comes first.
The `performance.tileEntityLightSamplesPerTick` and `performance.tileEntityLightBudgetMicros` settings control these limits without a restart.
A single MOD callback can exceed the soft time limit.

With many tracked sources, unnotified light changes may take multiple ticks to appear.
Explicit block changes, TileEntity installation/removal and worker cache misses are processed before fallback polling and do not share its budget.
Disabling `features.trackTileEntityLight` stops fallback polling; explicit updates still work, but unnotified changes may remain stale.

## Mod integration API

Use the public API jar as a **compile-only** dependency. Supply the matching full
Pulsar mod at runtime (development jar in a development environment, remapped jar
in a normal game). Do not bundle/shade Pulsar or its API classes, or put the API
jar in a game's mods directory. The matching full development jar remains a valid
compile-only dependency for existing integrations.

Build the API artifacts with `./gradlew apiJar apiSourcesJar` (also included in
`build`). `build/libs/*-api.jar` contains the public integration types in MCP
stable_39 development names; `*-api-sources.jar` supplies matching sources.
Internal caches, renderer bridges, Mixins and mod metadata are excluded. This is
a compile-time artifact, not a standalone implementation or a replacement for Pulsar.
Consumers must configure their own Minecraft 1.12.2/Cleanroom platform and Java 25.

For a local Maven repository, run:

```sh
./gradlew publishPulsarApiPublicationToApiLocalRepository
```

The result is written under `build/api-repository`, with coordinates
`com.sumirelabs:pulsar-api:<version>`. Use the exact generated version from that
directory; branch/dirty builds include a suffix. For example, in another project:

```groovy
repositories {
    maven { url = uri('../Pulsar/build/api-repository') }
}
dependencies {
    compileOnly 'com.sumirelabs:pulsar-api:<version>'
}
```

Replace the path and `<version>` with your local values. Alternatively, use
`compileOnly files('libs/pulsar-<version>-api.jar')`. This task publishes locally
only; no public Maven endpoint has been configured. `check` compiles the
[integration example](src/apiExample/java/example/pulsar/PulsarCompat.java) against
the packaged API and the platform dependencies, without the full Pulsar classes.

### Static RGB registration

Register a listener on `MinecraftForge.EVENT_BUS` during your mod's init or
post-init, on both physical sides. Pulsar posts `RegisterLightPropertiesEvent`
once during its load-complete handler, including in SCALAR mode. This avoids
depending on the order of mods' load-complete handlers.

```java
import com.sumirelabs.pulsar.api.BlockLightColors;
import com.sumirelabs.pulsar.api.BlockLightFilters;
import com.sumirelabs.pulsar.api.LightColors;
import com.sumirelabs.pulsar.api.PackedLightColor;
import com.sumirelabs.pulsar.api.RegisterLightPropertiesEvent;
import net.minecraft.block.Block;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;

public final class PulsarCompat {
    private final Block lamp;
    private final Block glass;

    public PulsarCompat(Block lamp, Block glass) {
        this.lamp = lamp;
        this.glass = glass;
    }

    @SubscribeEvent
    public void registerLight(RegisterLightPropertiesEvent event) {
        // Actual component strengths for every state of the block.
        BlockLightColors.registerEmissionAll(lamp, PackedLightColor.pack(15, 8, 3));
        // Transmit red: ABSORPTION is the complement of red transmittance.
        BlockLightFilters.registerAll(glass, PackedLightColor.complement(LightColors.RED));
    }
}
```

Register that instance with
`MinecraftForge.EVENT_BUS.register(new PulsarCompat(yourLamp, yourGlass))`.
Built-in defaults are installed before the event. State-interface defaults fill
unregistered entries afterward, then user palette files override registrations
in RGB mode. Registries are finally frozen in both modes; late registration throws
`IllegalStateException`. Among explicit registrations for one state, the last
write wins. Never call `freeze`, `defaults`, `interfaceDefaults` or other registry
lifecycle/cache methods from an integration.

`BlockLightColors.register(state, r, g, b)` instead registers a **hue** whose peak
is normalized to existing scalar emission. It rejects black; use `disable(state)`
to suppress RGB emission. `registerEmission` accepts black. In VANILLA policy,
disabling RGB emission does not remove the independent scalar gameplay light.
In SCALAR mode these RGB registrations do not affect propagation.

`registerAll(block, color)` applies a hue to every canonical state;
`registerEmissionAll(block, color)` applies actual strengths and
`disableAll(block)` suppresses RGB emission. `BlockLightFilters.registerAll`
sets minimum absorption for all states. These methods take packed `0xRGB` values.
They validate the request before writing and cannot be interrupted by registry
freezing. Register a common value first, then call the single-state method to
override selected states. A later All call overwrites earlier per-state settings.
Packed overloads of `register(state, color)` and `registerEmission(state, color)`
accept the same values; the original three-component overloads remain supported.

### Block callbacks and changing lights

Implement `ColoredLightSource` or `ColoredTranslucency` on your own Block for
state-dependent properties. These callbacks must remain stable after init and
must not access a world. For TileEntity/position-dependent values, implement
`PositionalColoredLightSource` or `PositionalColoredTranslucency`. Pulsar samples
loaded dynamic positions on the world thread, including color-only changes;
lighting workers consume snapshots and never call these positional callbacks.
Keep callbacks quick, read-only, and avoid loading neighbouring chunks.
Explicit registry entries override the corresponding callback, including positional
ones, so do not register a static value for a property you intend to vary dynamically.

All callbacks return **`0xRGB` with 0..15 per component**, not a vanilla packed
lightmap value or 24-bit display color. For example,
`PackedLightColor.pack(15, 8, 3)` returns `0xF83`. The public helper also validates
values and extracts components. Translucency callbacks return **transmittance**:
15 passes a component and 0 absorbs it. This is the opposite of
`BlockLightFilters.register`, which takes absorption. `PackedLightColor.complement`
converts between these conventions. Scalar, fluid and face opacity still applies;
an RGB filter cannot make an opaque face transparent.

`LightColors` provides BLACK, WHITE, RED, GREEN, BLUE, YELLOW, CYAN and MAGENTA as
basic additive colors; these are not Minecraft dye shades. For ports using
[SuperNova's light format](https://github.com/GTNewHorizons/SuperNova#api),
`PackedLightColor.fromSuperNova(0x0F0803)` returns `0xF83`, and `toSuperNova` performs
the reverse conversion. Both use four bits per component. This only converts the
numeric layout: it does not adapt world APIs, brightness policies, or arbitrary
24-bit display RGB. Nonzero padding bits in a SuperNova value are rejected.

For scalar emission/opacity changes without a block-state change, call the ordinary
`world.checkLight(pos)` on the owning world thread after updating your data.
Pulsar queues the checks and refreshes contextual samples; this is not synchronous
completion. Normal block changes already notify the lighting engine. Positional
RGB callbacks are also sampled each tick, so they do not require a separate Pulsar
notification API.

### Readiness, compatibility and scope

`ExtendedWorld.pulsar$getAnyChunkImmediately` queries Pulsar's registered chunks
without loading them; null also covers worlds without a Pulsar manager.
`ExtendedChunk.pulsar$isLightReady` describes initial/persisted/received base light
readiness, not completion of later changes or receipt of separate client RGB
snapshots. `pulsar$hasChunkPendingLight` is a queue status
query, not a guarantee that neighbouring work or rendering is finished.
**`pulsar$flushLightUpdates` is a deprecated no-op**, retained for binary
compatibility. There is no public await/future contract for completed relights yet.
Never spin or block the world/render thread waiting for these queries to change.

If Pulsar is optional, check `Loader.isModLoaded("pulsar")` before loading a
separate integration class containing Pulsar references. Keep those references
out of always-loaded block classes; implementing a Pulsar interface directly
requires Pulsar to be installed. Use Forge light queries for gameplay brightness.
The engine's internal managers, nibble arrays, packed caches and renderer bridges
are not public extension contracts. Registration and callback APIs work on both
logical sides; custom RGB rendering still needs a supported renderer.

## Fixed Bugs

- [MC-92](https://bugs.mojang.com/browse/MC-92)
- [MC-1531](https://bugs.mojang.com/browse/MC-1531) — smooth lighting across painting tile boundaries.
- [MC-3329](https://bugs.mojang.com/browse/MC-3329)
- [MC-80966](https://bugs.mojang.com/browse/MC-80966)
- [MC-104532](https://bugs.mojang.com/browse/MC-104532)
- [MC-116690](https://bugs.mojang.com/browse/MC-116690)
- [MC-117067](https://bugs.mojang.com/browse/MC-117067)
- [MC-117094](https://bugs.mojang.com/browse/MC-117094)
- [MC-249343](https://bugs.mojang.com/browse/MC-249343)

### Supported Mods

- [Celeritas](https://git.taumc.org/embeddedt/celeritas): fixes lighting bugs involving stairs and slabs.
- [Fluidlogged API](https://modrinth.com/mod/fluidlogged-api)
- [Depths Update](https://modrinth.com/mod/depths-update): supports dimensions extending below Y=0 or above Y=255.
- [JSON Paintings](https://www.curseforge.com/minecraft/mc-mods/json-paintings): fixes smooth-lighting bugs affecting paintings.

### Incompatible

- [Alfheim](https://www.curseforge.com/minecraft/mc-mods/alfheim-lighting-engine)
- Phosphor for Forge
- Hesperus
- Other Starlight backports or mods that replace or rewrite the lighting engine
- The standard edition of The Aether II, which bundles Phosphor. Use [The Aether II: Phosphor Not Included](https://www.curseforge.com/minecraft/mc-mods/the-aether-ii-phosphor-not-included) instead.

### Unsupported or Untested

- SpongeForge: the chunk-packet async-lighting redirect conflict reported in [issue #24](https://github.com/Sumire-Labs/Pulsar/issues/24) is addressed by composing with its captured section arrays. The full SpongeForge server integration remains untested; disabling `optimizations.async-lighting.enabled` in `config/sponge/global.conf` remains a diagnostic workaround.
- CubicChunks
- OptiFine: the G5 startup injection conflict reported in [issue #26](https://github.com/Sumire-Labs/Pulsar/issues/26)
  is handled by skipping Pulsar's vanilla-only partial-face renderer correction
  when OptiFine is present. That path uses OptiFine's lighting behavior; full
  OptiFine/OptiNotFine rendering and RGB compatibility remains unverified.

## Performance

### Pulsar 0.5.0 Benchmarks

<details>
<summary>0.5.0</summary>

#### Block Placement and Removal

![Block update completion time](https://raw.githubusercontent.com/Sumire-Labs/Pulsar/main/docs/benchmarks/2026-10-12-pulsar-0.5.0/block-latency.png)

#### Sky Opening and Closing

![Sky opening and closing completion time](https://raw.githubusercontent.com/Sumire-Labs/Pulsar/main/docs/benchmarks/2026-10-12-pulsar-0.5.0/sky-latency.png)

#### Server Load During Continuous Editing

These values measure main-thread MSPT. Async worker CPU time is not included.

![Main-thread MSPT during continuous editing](https://raw.githubusercontent.com/Sumire-Labs/Pulsar/main/docs/benchmarks/2026-10-12-pulsar-0.5.0/continuous-server-ticks.png)

#### Flat Terrain Generation

![Equal-output flat terrain generation](https://raw.githubusercontent.com/Sumire-Labs/Pulsar/main/docs/benchmarks/2026-10-12-pulsar-0.5.0/flat-generation.png)

#### Travel in a Flat World

![Server chunk readiness at travel destinations](https://raw.githubusercontent.com/Sumire-Labs/Pulsar/main/docs/benchmarks/2026-10-12-pulsar-0.5.0/flat-travel-readiness.png)

</details>

## Credits

- [Starlight](https://github.com/PaperMC/Starlight) by Spottedleaf: the algorithms on which Pulsar is based.
- [SuperNova](https://github.com/GTNewHorizons/SuperNova) by GTNewHorizons: an RGB lighting engine inspired by Starlight.
- [Alfheim](https://github.com/Red-Studio-Ragnarok/Alfheim) by Red Studio: a reference for directional neighbor brightness, liquid and emissive-block rendering, and light-only chunk packet handling.
- [CleanroomModTemplate](https://github.com/CleanroomMC/CleanroomModTemplate), TokenEnvoy, and CleanroomVersioning by CleanroomMC.

## AI Disclosure

Pulsar uses LLM assistance in the following areas:

- Some code: primarily understanding and implementing algorithms.
- Translation and writing: translating into other languages and generating documentation.

## License

Pulsar is licensed under the [LGPL-3.0](https://github.com/Sumire-Labs/Pulsar/blob/main/LICENSE.md).
