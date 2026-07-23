package com.sumirelabs.pulsar.mixin;

import com.sumirelabs.pulsar.light.LightCachedState;
import com.sumirelabs.pulsar.light.engine.LightInfo;
import net.minecraft.block.state.IBlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

/**
 * Attaches the memoised packed light info to every block state. 1.12.2
 * analogue of Starlight's {@code BlockStateBaseMixin} per-state light cache
 * (1.14+ {@code BlockBehaviour.BlockStateBase}).
 *
 * <p>Forge's {@code ExtendedBlockState$ExtendedStateImplementation} extends
 * this class, so extended states inherit the field.
 *
 * <p>The lazy init is racy on purpose: {@link LightInfo#compute} is pure and
 * deterministic, an {@code int} write is atomic, and both BFS workers plus
 * the client thread computing the same value concurrently simply store the
 * same result. First use happens in-world, safely after
 * {@code FaceOcclusion.registerDefaults()} has run at postInit.
 */
@Mixin(targets = "net.minecraft.block.state.BlockStateContainer$StateImplementation")
public abstract class MixinBlockStateImplementation implements LightCachedState {

    @Unique
    private int pulsar$lightInfo;

    @Override
    public int pulsar$lightInfo() {
        final int info = this.pulsar$lightInfo;
        if (info != 0) {
            return info;
        }
        return this.pulsar$lightInfo = LightInfo.compute((IBlockState) this);
    }
}
