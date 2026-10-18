# Pulsar Lighting Engine


This is a mod exclusively for Cleanroom. It requires Java 25 and Cleanroom 0.6.10 or later.

Pulsar is an asynchronous lighting engine built for [Cleanroom](https://github.com/CleanroomMC/Cleanroom).
It reimplements Starlight-style algorithms for Cleanroom and runs lighting calculations on separate threads.

## Requirements

- [Cleanroom 0.6.10-alpha or newer](https://github.com/CleanroomMC/Cleanroom/releases/tag/0.6.10-alpha)

## Features

- Runs server-side light propagation on separate threads.
- Batches large groups of block changes instead of processing lighting separately for every edit.
- Caches light data in each chunk's NBT to avoid unnecessary full relights.
- Keeps Vanilla's block-light and sky-light data synchronized, so removing Pulsar does not corrupt your world.
- Includes various lighting bug fixes and quality-of-life improvements.
- Adds natural lighting to Thaumcraft crystals.
- SCALAR integrates with Celeritas, Actinium, and Nothirium; RGB rendering is validated with Vanilla/Forge and Celeritas.

### RGB lighting branch

`feat/rgb-engine` adds SuperNova-style packed colored block/sky lighting for
terrain, fluids, entities, normal/Fast TESRs, particles and held items. New
configurations select RGB, squared-weight blending and RGB_MAX gameplay light;
valid explicit SCALAR settings are retained. RGB_MAX requires compatible Pulsar
clients. The optional VANILLA policy keeps independent gameplay brightness.

Vanilla/Forge and Celeritas rendering are validated on Cleanroom 0.6.12-alpha
and 0.7.7-alpha; other renderer integrations require separate validation.
RGB shader lighting is also validated with Demonica 0.8.2 on Cleanroom 0.7.7
and Actinium alpha-0.0.12 on Cleanroom 0.6.12, including the seven tested packs
listed in the [shader compatibility guide](docs/rgb-lighting.md#demonica-and-actinium-shaderpacks).
See the [RGB setup and API guide](docs/rgb-lighting.md),
[SuperNova implementation comparison](docs/research/supernova-parity.md) and
[native validation and measurements](docs/research/supernova-parity/README.md).

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
