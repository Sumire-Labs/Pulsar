# Pulsar Lighting Engine

## Overview

Pulsar is a lighting engine mod for Cleanroom, based on Starlight and SuperNova.
It replaces Minecraft's vanilla lighting engine with queue-based breadth-first search (BFS) light propagation derived from Starlight.
It offers a non-colored SCALAR lighting mode like Starlight and a full RGB colored lighting mode like SuperNova.

## Features

- RGB colored lighting: Calculates block light and sky light per RGB channel, accounting for light source colors and filtering through blocks such as stained glass.
- SCALAR mode: Uses conventional, non-colored lighting calculations, like Starlight.
- Asynchronous and parallel processing: Runs server-side light propagation on separate worker threads.
- Batched lighting updates: Groups lighting updates caused by large numbers of block changes.
- Light data caching: Saves calculated light data in chunk NBT to avoid recalculation when chunks are loaded.
- Various additional bug fixes and quality-of-life improvements not listed here.
- Natural lighting for Thaumcraft crystals.

### Fixed Bugs

- [MC-92](https://bugs.mojang.com/browse/MC-92)
- [MC-1531](https://bugs.mojang.com/browse/MC-1531)
- [MC-3329](https://bugs.mojang.com/browse/MC-3329)
- [MC-80966](https://bugs.mojang.com/browse/MC-80966)
- [MC-104532](https://bugs.mojang.com/browse/MC-104532)
- [MC-116690](https://bugs.mojang.com/browse/MC-116690)
- [MC-117067](https://bugs.mojang.com/browse/MC-117067)
- [MC-117094](https://bugs.mojang.com/browse/MC-117094)
- [MC-249343](https://bugs.mojang.com/browse/MC-249343)

### Compatibility

- JSON Paintings: Applies smooth lighting to paintings added by the mod.
- SpongeForge: Includes compatibility support for asynchronous lighting.
- Fluidlogged API: Accounts for the opacity and light emission of fluids stored inside blocks.
- CBMultipart: Standard microblocks inherit their materials' light source colors. Custom parts retain neutral emission unless handled by a dedicated integration.
- ProjectRed: RGB emission for full-block lamps, multipart fixtures, light buttons, and light microblock materials, preserving their actual brightness and power state.
- Depths Update: Supports extended world height ranges.
- Thaumcraft: Adds smooth lighting to crystals.
- Celeritas: Supports its rendering pipeline.
- Demonica Shader: Tested and confirmed working with Complementary Reimagined.
  - Compatibility with forks such as Actinium, Pintonium, and Impetus is not guaranteed.

### Incompatibilities

Pulsar completely replaces the lighting engine and is incompatible with other lighting engine mods.

- [Alfheim](https://www.curseforge.com/minecraft/mc-mods/alfheim-lighting-engine)
- Phosphor
- Hesperus
- Starlight-1.12
- OptiFine: Use Celeritas instead. Demonica Shader provides shader support, while Celeritas Extra and Celeritas Leaf Culling should cover the additional video options.
- The Aether II: Use [The Aether II: Phosphor Not Included](https://www.curseforge.com/minecraft/mc-mods/the-aether-ii-phosphor-not-included) instead.

## Configuration

- `config/pulsar.cfg`: Selects the lighting mode and configures additional features.
  - `lightingMode`: `SCALAR` (default, conventional, non-colored lighting) or `RGB` (RGB colored lighting).
  - `lightingModeSelected`: `false` by default. On the first client launch, choose SCALAR or RGB once for this instance; the selection sets this to `true`. Changing the active mode requires restarting Minecraft. Modpacks can set this to `true` to skip the prompt; dedicated servers use `lightingMode` directly.
  - `rgbGameplayLight`: `RGB_MAX` (default, uses the brightest RGB component as gameplay brightness; per-channel attenuation affects light checks such as mob spawning) or `VANILLA` (calculates non-colored lighting separately for gameplay light checks).
  - `rgbBlendMode`: Includes an optional `MAXIMUM` mode that combines block and sky light by taking the brightest value per channel. Full daylight stays neutral around colored sources. This controls normal rendering; shader packs handle their own shading.
  - `debug.traceLightUpdates`: Opt-in diagnostics for lighting queues, render requests, and Celeritas rebuilds. Logs a bounded summary every 200 client ticks; caller and position samples cover 1 in 64 requests.
- `config/pulsar-colors.cfg`: Defines light source colors.
- `config/pulsar-filters.cfg`: Defines attenuation for each RGB channel.

## API

Pulsar provides APIs for other mods to define light source colors and light transmission properties.

- `ColoredLightSource` / `PositionalColoredLightSource`: Define RGB emission based on block state or position.
- `ColoredTranslucency` / `PositionalColoredTranslucency`: Define per-channel RGB transmittance.
- `FaceLightOcclusion`: Define light opacity for individual block faces.
- `BlockLightColors` / `BlockLightFilters`: Register light source colors and minimum per-channel absorption for blocks or block states.
- `RegisterLightPropertiesEvent`: Forge event for registering light source colors and filters.
- `PackedLightColor`: Pack and unpack `0xRGB` values with components from 0 to 15, and convert to and from SuperNova's format.
- `LightColors`: Provides basic color constants.
- `ExtendedChunk` / `ExtendedWorld`: Query chunk lighting readiness and pending world lighting updates.

See the [API documentation](https://raw.githubusercontent.com/Sumire-Labs/Pulsar/main/src/main/java/com/sumirelabs/pulsar/api/package-info.java) and [integration example](https://raw.githubusercontent.com/Sumire-Labs/Pulsar/main/src/apiExample/java/example/pulsar/PulsarCompat.java) for details.

## Benchmarks

Measured on 2026-10-30 with Minecraft 1.12.2 / Cleanroom 0.6.13-alpha, comparing vanilla lighting, Alfheim 1.6, and Pulsar `0.6.0` in SCALAR and RGB (`RGB_MAX`) modes.

The charts measure dedicated-server latency from a block change through completed lighting, including the completion adapter. Each value is the median of four fresh-JVM run means; whiskers show the minimum and maximum. The CPU is a Ryzen AI MAX+ 395, with Java 25 and a fixed 4 GiB heap. Lower is better; axes use a logarithmic scale.

![Block light update completion times](https://raw.githubusercontent.com/Sumire-Labs/Pulsar/main/assets/benchmarks/2026-10-30/block-updates.png)

![Sky light update completion times](https://raw.githubusercontent.com/Sumire-Labs/Pulsar/main/assets/benchmarks/2026-10-30/sky-updates.png)

Vanilla passes the open-sky block-light test, but fails the roof fixture's initial stored sky-light validation, so no vanilla sky-update time is scored. RGB computes additional colored-light information. These repeated, individually awaited updates do not measure FPS, chunk generation, or concurrent update throughput.

See the [measured values and run ranges](https://raw.githubusercontent.com/Sumire-Labs/Pulsar/main/assets/benchmarks/2026-10-30/results.md) and [raw measurement results](https://raw.githubusercontent.com/Sumire-Labs/Pulsar/main/assets/benchmarks/2026-10-30/measurement-evidence.zip) for the full comparison.

See [lighting validation notes](https://raw.githubusercontent.com/Sumire-Labs/Pulsar/main/docs/lighting-validation.md) for compatibility probes and the opt-in empty-section experiment.

## Credits

- [Starlight](https://github.com/PaperMC/Starlight): The foundation for the lighting engine's architecture and core algorithms.
- [SuperNova](https://github.com/GTNewHorizons/SuperNova): The source of the ported lighting engine, per-world task management, and packed RGB propagation code.
- [Alfheim](https://github.com/Red-Studio-Ragnarok/Alfheim): A reference for various lighting bug fixes.
- [CleanroomModTemplate](https://github.com/CleanroomMC/CleanroomModTemplate): An excellent mod development template for Cleanroom.
- CleanroomVersioning: Build plugin for version management.
- TokenEnvoy: Build plugin for embedding metadata at build time.

## LLM Usage

LLMs are used to help write some of Pulsar's code and documentation.

## License

Pulsar is licensed under [LGPL-3.0](https://raw.githubusercontent.com/Sumire-Labs/Pulsar/main/LICENSE.md).
