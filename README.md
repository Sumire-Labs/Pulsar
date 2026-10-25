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

Select `RGB` or `SCALAR` using `general.lightingMode` in `config/pulsar.cfg`.
Changing the engine or gameplay-light policy requires restarting Minecraft and
the server. RGB source colors and filters can be customized in
`config/pulsar-colors.cfg` and `config/pulsar-filters.cfg`; restart the server
after editing these files.

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

Use the matching Pulsar development jar as a **compile-only** dependency and the
normal remapped jar at runtime. Do not bundle/shade Pulsar or its API classes.
There is currently no configured public Maven repository for Pulsar.

### Static RGB registration

Register a listener on `MinecraftForge.EVENT_BUS` during your mod's init or
post-init, on both physical sides. Pulsar posts `RegisterLightPropertiesEvent`
once during its load-complete handler, including in SCALAR mode. This avoids
depending on the order of mods' load-complete handlers.

```java
import com.sumirelabs.pulsar.api.BlockLightColors;
import com.sumirelabs.pulsar.api.BlockLightFilters;
import com.sumirelabs.pulsar.api.RegisterLightPropertiesEvent;
import net.minecraft.block.Block;
import net.minecraft.block.state.IBlockState;
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
        for (IBlockState state : lamp.getBlockState().getValidStates()) {
            // Actual component strengths. RGB_MAX can light a source whose
            // vanilla emission is zero; VANILLA caps it to scalar emission.
            BlockLightColors.registerEmission(state, 15, 8, 3);
        }
        for (IBlockState state : glass.getBlockState().getValidStates()) {
            // Minimum ABSORPTION: preserve red, absorb green and blue.
            BlockLightFilters.register(state, 0, 15, 15);
        }
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
- OptiFine

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
