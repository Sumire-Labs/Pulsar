package com.sumirelabs.pulsar.mixin;

import com.sumirelabs.pulsar.rgb.*;
import net.minecraft.util.BitArray;
import net.minecraft.world.chunk.*;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Entire mixin is excluded in SCALAR, including mutation hooks and cache fields. */
@Mixin(BlockStateContainer.class)
public abstract class MixinRgbFilterPalette implements RgbFilterPalette {
    @Shadow protected IBlockStatePalette palette;
    @Shadow protected BitArray storage;
    @Unique private volatile long pulsar$filterRevision;
    @Unique private int pulsar$filterWriteDepth;
    @Unique private volatile Cache pulsar$filterCache;

    @Inject(method = "set(ILnet/minecraft/block/state/IBlockState;)V", at = @At("HEAD"))
    private void pulsar$beforeFilterWrite(CallbackInfo ci) { pulsar$beginFilterWrite(); }
    @Inject(method = "set(ILnet/minecraft/block/state/IBlockState;)V", at = @At("RETURN"))
    private void pulsar$afterFilterWrite(CallbackInfo ci) { pulsar$endFilterWrite(); }
    @Inject(method = "read", at = @At("HEAD"), require = 0)
    private void pulsar$beforeFilterRead(CallbackInfo ci) { pulsar$beginFilterWrite(); }
    @Inject(method = "read", at = @At("RETURN"), require = 0)
    private void pulsar$afterFilterRead(CallbackInfo ci) { pulsar$endFilterWrite(); }
    @Unique private void pulsar$beginFilterWrite() {
        if (pulsar$filterWriteDepth++ == 0) ++pulsar$filterRevision;
    }
    @Unique private void pulsar$endFilterWrite() {
        if (--pulsar$filterWriteDepth == 0) ++pulsar$filterRevision;
    }
    public void pulsar$invalidateSkyFilterCache() { pulsar$filterCache = null; }
    public boolean pulsar$mayFilterSky() {
        if (((Object)this).getClass() != BlockStateContainer.class) return true;
        long revision = pulsar$filterRevision;
        if ((revision & 1) != 0) return true;
        IBlockStatePalette palette = this.palette;
        BitArray storage = this.storage;
        Cache cached = pulsar$filterCache;
        if (cached != null && cached.revision() == revision && cached.palette() == palette && cached.storage() == storage)
            return pulsar$filterRevision == revision ? cached.filtered() : true;
        boolean filtered;
        try { filtered = RgbFilterPaletteChecks.hasFilters(palette, storage); }
        catch (IndexOutOfBoundsException resizing) { return true; }
        if (pulsar$filterRevision != revision) return true;
        pulsar$filterCache = new Cache(revision, palette, storage, filtered);
        return filtered;
    }
}
