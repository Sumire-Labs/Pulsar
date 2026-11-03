# Pulsar Lighting Engine

## Overview

Pulsar is a lighting engine mod for Cleanroom, based on Starlight and SuperNova.
It replaces Minecraft's vanilla lighting engine with queue-based breadth-first search (BFS) light propagation derived from Starlight.
It offers a non-colored SCALAR lighting mode like Starlight and a full RGB colored lighting mode like SuperNova.

Current builds require Minecraft 1.12.2, Java 25, and Cleanroom Loader 0.7.14-alpha
or newer within the 0.7.x series.

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

- Fancier Block Particles / Fanciest Block Particles: Applies RGB lighting to their 3D particles while preserving material colors, transparency and full-bright effects.
- JSON Paintings: Applies smooth lighting to paintings added by the mod.
- SpongeForge: Includes compatibility support for asynchronous lighting.
- Fluidlogged API: Accounts for the opacity and light emission of fluids stored inside blocks.
- Future MC: Warm lanterns and campfires, and pale cyan soul lanterns, retaining their original brightness and lit/unlit behavior.
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

Starting with releases containing the Maven publishing workflow, the compile-only
API and its sources are published to Sumire Labs' Artifact Keeper:

```groovy
repositories {
    maven {
        url = 'https://artifact.sumirelabs.com/maven/releases'
        content { includeGroup 'com.sumirelabs' }
    }
}

dependencies {
    // Replace <version> with a published Pulsar release version.
    compileOnly 'com.sumirelabs:pulsar-api:<version>'
}
```

The API uses MCP stable_39 development names. Supply your own Cleanroom platform
and install the matching full Pulsar mod at runtime. Do not shade or bundle the
API into your mod. Artifact downloads do not require authentication.

## Release publishing

Pushing a release tag builds and checks the mod once, creates a GitHub release,
publishes `com.sumirelabs:pulsar-api` and its sources to Artifact Keeper, and
uploads the runtime mod JAR to CurseForge and Modrinth. API, development and source
JARs are excluded from the CurseForge and Modrinth uploads.

Configure these repository secrets under **Settings > Secrets and variables >
Actions** before pushing a release tag:

| Secret | Value |
| --- | --- |
| `MAVEN_USER` | Artifact Keeper publishing account username |
| `MAVEN_PASS` | That account's password for HTTP Basic authentication |
| `CURSEFORGE_TOKEN` | CurseForge upload API token with access to project `1641056` |
| `MODRINTH_TOKEN` | Modrinth token with version creation/upload access to project `gs7RbryI` |

The Maven account needs write access to the `releases` repository. Credentials
belong in Actions secrets or local environment variables, never in this file or
the project's `gradle.properties`.

Cleanroom Versioning derives the release channel from Git tags. Stage suffixes
such as `-alpha` and `-beta` remain in the version; RC builds use the beta upload
channel. Bare 0.x tags default to beta unless they inherit an explicit stage.
GitHub marks alpha and beta releases as prereleases. Release notes are generated
with GitHub's release-notes API and reused for CurseForge and Modrinth.

For manual publication, run **Release** from the Actions tab and select a version
tag in **Use workflow from**. The selected tag must contain this build setup.
The workflow uses the template's Gradle publishing plugin for the runtime mod;
the separate API publication keeps its original Maven coordinates. Existing
`MAVEN_USER` / `MAVEN_PASS` Actions secrets are mapped to the template's
`MAVEN_USERNAME` / `MAVEN_PASSWORD` environment variables.

Verify the Maven layout locally without credentials or remote uploads:

```text
./gradlew build publishPulsarApiPublicationToApiLocalRepository
```

The local repository is written to `build/api-repository`. To publish the API
manually with `MAVEN_USERNAME` and `MAVEN_PASSWORD` in the environment:

```text
./gradlew -Ppublish_to_maven=true publishPulsarApiPublicationToArtifactKeeperRepository
```

## Development

The development scaffold is based on [CleanroomDevEnv](https://github.com/Sumire-Labs/CleanroomDevEnv)
at `a1de707`: CleanroomGradle 0.23.0, Cleanroom USERDEV 0.7.14-alpha, Gradle 9.8.2,
and Java 25. Cleanroom supplies Mixin, MixinExtras, and the LWJGL compatibility
APIs. There is no Unimined build path.

```text
./gradlew build
./gradlew runClient
./gradlew runServer
./gradlew spotlessApply
```

Spotless is enabled and checked by `build`. The template's Eclipse profile uses
four spaces, a 120-column wrapping preference, UTF-8, LF, and its prescribed
import order. It removes unused imports and trailing whitespace while preserving
comment/Javadoc prose and existing comment wrapping. Formatter toggles are
supported for code that must retain its layout. The API integration example is
formatted and compiled as part of the same checks.

Pulsar's optional compatibility probes and GPU/benchmark settings are in
`gradle/scripts/pulsar-testing.gradle`. The normal check includes a packaged API
consumer and verifies the remapped SRG Mod JAR, metadata, Mixin refmap, coremod,
and access-transformer packaging. Gradle's remapping dependency helper handles
release Mod JARs used by compatibility tests.

Renovate configuration follows the template's Monday 00:00-06:00 Japan-time
window. Enable the official GitHub App for Pulsar as described in
[the setup instructions](.github/RENOVATE.md) if it is not already enabled.

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
- [CleanroomDevEnv](https://github.com/Sumire-Labs/CleanroomDevEnv): Current development scaffold; its MIT notice is retained in [licenses/CleanroomDevEnv-MIT.txt](licenses/CleanroomDevEnv-MIT.txt).
- CleanroomVersioning: Build plugin for version management.
- TokenEnvoy: Build plugin for embedding metadata at build time.

## LLM Usage

LLMs are used to help write some of Pulsar's code and documentation.

## License

Pulsar is licensed under [LGPL-3.0](https://raw.githubusercontent.com/Sumire-Labs/Pulsar/main/LICENSE.md).
