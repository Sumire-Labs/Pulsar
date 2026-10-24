package com.sumirelabs.pulsar.api;

import net.minecraft.block.state.IBlockState;

/** State-dependent 0xRGB transmittance: 15 transmits and 0 absorbs a component.
 * Scalar, face and fluid opacity still applies. Callback values must be stable after init;
 * do not access a live world. Registry overrides take precedence.
 */
public interface ColoredTranslucency {
    int getColoredTransmittance(IBlockState state);
}
