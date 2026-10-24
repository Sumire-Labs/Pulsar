package com.sumirelabs.pulsar.mixin;

import com.sumirelabs.pulsar.api.RgbStateProperties;
import com.sumirelabs.pulsar.rgb.RgbCachedState;
import net.minecraft.block.state.IBlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

/** Excluded entirely in SCALAR. Frozen registry values are deterministic;
 * concurrent first reads may repeat computation, but publish one atomic int. */
@Mixin(targets = "net.minecraft.block.state.BlockStateContainer$StateImplementation")
public abstract class MixinRgbStateProperties implements RgbCachedState {
    @Unique private int pulsar$rgbProperties;
    public int pulsar$rgbProperties() {
        int value = pulsar$rgbProperties;
        if (value != 0) return value;
        value = RgbStateProperties.compute((IBlockState)(Object)this);
        if (RgbStateProperties.cacheable(value)) pulsar$rgbProperties = value;
        return value;
    }
}
