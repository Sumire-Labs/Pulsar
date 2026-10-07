package com.sumirelabs.pulsar.mixin;

import com.sumirelabs.pulsar.light.ContextualLightPalette;
import com.sumirelabs.pulsar.light.ContextualLightPaletteChecks;
import net.minecraft.world.chunk.BlockStateContainer;
import net.minecraft.world.chunk.IBlockStatePalette;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

@Mixin(BlockStateContainer.class)
public abstract class MixinBlockStateContainerSamples implements ContextualLightPalette {
    @Shadow protected IBlockStatePalette palette;

    @Override
    public boolean pulsar$needsContextualSamples() {
        // A modded subclass can store states outside the inherited palette.
        if (((Object) this).getClass() != BlockStateContainer.class) return true;
        return ContextualLightPaletteChecks.mayNeedSamples(this.palette);
    }
}
