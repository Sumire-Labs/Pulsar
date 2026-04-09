/**
 * Pulsar public API.
 *
 * <p>Stable surface for other mods that need to interact with Pulsar:
 * <ul>
 *     <li>{@link com.sumirelabs.pulsar.api.ExtendedChunk} — query whether a
 *         chunk's initial light propagation has finished.</li>
 *     <li>{@link com.sumirelabs.pulsar.api.ExtendedWorld} — query world-level
 *         light state and flush pending updates.</li>
 *     <li>{@link com.sumirelabs.pulsar.api.FaceLightOcclusion} — declare
 *         direction-dependent light opacity for a block.</li>
 * </ul>
 *
 * <p>Method names use a {@code pulsar$} prefix to avoid colliding with
 * vanilla / Forge methods on the mixed-in classes.
 */
package com.sumirelabs.pulsar.api;
