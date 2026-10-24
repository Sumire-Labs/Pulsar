package com.sumirelabs.pulsar.api;

import net.minecraft.block.state.IBlockState;

/** State-dependent RGB emission, encoded as 0xRGB with four bits per component.
 * RGB_MAX uses actual component strengths, including sources with no vanilla emission.
 * VANILLA caps the peak to contextual scalar emission while retaining the hue.
 * Implement on the Block; registry overrides take precedence.
 * This callback must be stable after initialization and must not access a live world.
 */
public interface ColoredLightSource {
    int getColoredLightEmission(IBlockState state);
}
