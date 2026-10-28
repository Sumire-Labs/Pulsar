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
  - `lightingMode`: `RGB` (default, RGB colored lighting) or `SCALAR` (conventional, non-colored lighting).
  - `rgbGameplayLight`: `RGB_MAX` (default, uses the brightest RGB component as gameplay brightness; per-channel attenuation affects light checks such as mob spawning) or `VANILLA` (calculates non-colored lighting separately for gameplay light checks).
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

See the [API documentation](src/main/java/com/sumirelabs/pulsar/api/package-info.java) and [integration example](src/apiExample/java/example/pulsar/PulsarCompat.java) for details.

## Benchmarks

Benchmarks for 0.6.0-Dev.27 will be added later.

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

Pulsar is licensed under [LGPL-3.0](LICENSE.md).
