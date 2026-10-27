package com.sumirelabs.pulsar.mixin;

import com.sumirelabs.pulsar.api.RgbStateProperties;
import com.sumirelabs.pulsar.rgb.RgbCachedState;
import net.minecraft.block.state.IBlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

/** Excluded entirely in SCALAR. One atomic long carries palette epoch + properties;
 * a late computation from an old epoch cannot poison a new palette's cache. */
@Mixin(targets = "net.minecraft.block.state.BlockStateContainer$StateImplementation")
public abstract class MixinRgbStateProperties implements RgbCachedState {
    @Unique private volatile long pulsar$rgbProperties;
    public int pulsar$rgbProperties() {
        var palette = com.sumirelabs.pulsar.rgb.RgbPaletteOverrides.current();
        long cached = pulsar$rgbProperties;
        int value = (int) cached;
        if (value != 0 && (int)(cached >>> 32) == palette.generation) return value;
        value = RgbStateProperties.compute((IBlockState)(Object)this,palette);
        if (RgbStateProperties.cacheable(value)) pulsar$rgbProperties = (long)palette.generation << 32 | value & 0xFFFFFFFFL;
        return value;
    }
}
