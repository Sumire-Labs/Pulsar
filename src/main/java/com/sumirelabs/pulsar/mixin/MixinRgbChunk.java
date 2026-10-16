package com.sumirelabs.pulsar.mixin;

import com.sumirelabs.pulsar.rgb.*;
import net.minecraft.world.chunk.Chunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

@Mixin(Chunk.class)
public abstract class MixinRgbChunk implements RgbChunk {
    @Unique private volatile RgbStorage pulsar$rgbStorage;
    @Unique private volatile RgbSnapshot pulsar$rgbSnapshot;
    public RgbStorage pulsar$getRgbStorage() { return pulsar$rgbStorage; }
    public void pulsar$setRgbStorage(RgbStorage storage) { pulsar$rgbStorage = storage; }
    public RgbSnapshot pulsar$getRgbSnapshot() { return pulsar$rgbSnapshot; }
    public void pulsar$setRgbSnapshot(RgbSnapshot snapshot) { pulsar$rgbSnapshot = snapshot; }
}
