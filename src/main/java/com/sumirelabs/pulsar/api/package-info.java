/**
 * Pulsar public API.
 *
 * <p>Integration entry points for other mods:
 * <ul>
 *     <li>{@link com.sumirelabs.pulsar.api.RegisterLightPropertiesEvent} —
 *         register static properties before the registries freeze.</li>
 *     <li>{@link com.sumirelabs.pulsar.api.BlockLightColors} and
 *         {@link com.sumirelabs.pulsar.api.BlockLightFilters} — state-specific
 *         emission and minimum absorption. User palette files take precedence.</li>
 *     <li>{@link com.sumirelabs.pulsar.api.ColoredLightSource} and
 *         {@link com.sumirelabs.pulsar.api.ColoredTranslucency} — immutable
 *         state callbacks, sampled during initialization.</li>
 *     <li>{@link com.sumirelabs.pulsar.api.PositionalColoredLightSource} and
 *         {@link com.sumirelabs.pulsar.api.PositionalColoredTranslucency} —
 *         world-dependent callbacks sampled on the owning world thread.</li>
 *     <li>{@link com.sumirelabs.pulsar.api.PackedLightColor} — validated
 *         four-bit-per-channel {@code 0xRGB} values.</li>
 *     <li>{@link com.sumirelabs.pulsar.api.ExtendedChunk} — query whether a
 *         chunk has ready initial, persisted or received base light.</li>
 *     <li>{@link com.sumirelabs.pulsar.api.ExtendedWorld} — query world-level
 *         light state without loading chunks; its legacy flush method is a no-op.</li>
 *     <li>{@link com.sumirelabs.pulsar.api.FaceLightOcclusion} — declare
 *         direction-dependent light opacity for a block.</li>
 * </ul>
 *
 * <p>Registry lifecycle methods, {@code RgbStateProperties}, and renderer bridges
 * ({@code FaceLitBlock}, {@code FaceLightInfo}, {@code RawLightAccess}) are engine
 * plumbing, not extension points for arbitrary implementations. Java visibility
 * does not make internal queues, managers or storage formats part of this contract.
 *
 * <p>For optional dependencies, isolate all Pulsar references in a compatibility
 * class loaded only when Forge reports Pulsar present. Do not shade these classes.
 * A class implementing a Pulsar interface requires that interface at class load time.
 * RGB data is not a vanilla lightmap value. Use Minecraft's light queries for
 * gameplay brightness; RGB_MAX and VANILLA policies intentionally differ.
 *
 * <p>Method names use a {@code pulsar$} prefix to avoid colliding with
 * vanilla / Forge methods on the mixed-in classes.
 */
package com.sumirelabs.pulsar.api;
