package com.sumirelabs.pulsar.mixin;

import com.sumirelabs.pulsar.compat.FluidLightBridge;
import com.sumirelabs.pulsar.light.PulsarChunk;
import com.sumirelabs.pulsar.light.engine.LightInfo;
import com.sumirelabs.pulsar.util.WorldHeightContext;
import com.sumirelabs.pulsar.util.WorldUtil;
import net.minecraft.block.state.IBlockState;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.NibbleArray;
import net.minecraft.world.chunk.storage.ExtendedBlockStorage;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Replaces vanilla chunk-light bookkeeping that would otherwise write around
 * Pulsar's asynchronous engine. Persistent Pulsar state remains in
 * {@link MixinChunk}; this mixin owns only vanilla method interception.
 */
@Mixin(Chunk.class)
@SuppressWarnings("deprecation")
public abstract class MixinChunkVanillaLighting {

    @Shadow
    @Final
    private World world;

    @Shadow
    @Final
    public int x;

    @Shadow
    @Final
    public int z;

    @Shadow
    public boolean isLightPopulated;

    @Shadow
    public boolean isTerrainPopulated;

    @Shadow
    public int[] heightMap;

    @Shadow
    public int[] precipitationHeightMap;

    @Shadow
    public int heightMapMinimum;

    @Shadow
    public abstract ExtendedBlockStorage[] getBlockStorageArray();

    @Shadow
    public abstract int getTopFilledSegment();

    @Shadow
    public abstract IBlockState getBlockState(int x, int y, int z);

    @Shadow
    public abstract void markDirty();

    @Unique
    private WorldHeightContext pulsar$vanillaHeightContext;

    @Unique
    private BlockPos.MutableBlockPos pulsar$vanillaLightLookupPos;

    @Unique
    private WorldHeightContext pulsar$getVanillaHeightContext() {
        if (this.pulsar$vanillaHeightContext == null) {
            this.pulsar$vanillaHeightContext = WorldUtil.getHeightContext(this.world);
        }
        return this.pulsar$vanillaHeightContext;
    }

    @Unique
    private BlockPos.MutableBlockPos pulsar$getVanillaLightLookupPos() {
        if (this.pulsar$vanillaLightLookupPos == null) {
            this.pulsar$vanillaLightLookupPos = new BlockPos.MutableBlockPos();
        }
        return this.pulsar$vanillaLightLookupPos;
    }

    /**
     * Rebuilds the heightmap and a scalar vanilla skylight baseline while the
     * asynchronous engine owns the real propagation pass.
     */
    @Inject(method = "generateSkylightMap", at = @At("HEAD"), cancellable = true, require = 0)
    private void pulsar$generateSkylightMap(final CallbackInfo ci) {
        final int minBlockY = this.pulsar$getVanillaHeightContext().getMinBlockY();
        final int topSegment = this.getTopFilledSegment();
        this.heightMapMinimum = Integer.MAX_VALUE;

        for (int localX = 0; localX < 16; ++localX) {
            for (int localZ = 0; localZ < 16; ++localZ) {
                this.precipitationHeightMap[localX + (localZ << 4)] = -999;
                this.heightMap[localZ << 4 | localX] = minBlockY;

                // Vanilla starts at topSegment + 16. Starting one lower skips
                // the top row of the highest filled section.
                for (int y = topSegment + 16; y > minBlockY; --y) {
                    if (this.pulsar$opacityAt(localX, y - 1, localZ) != 0) {
                        this.heightMap[localZ << 4 | localX] = y;
                        if (y < this.heightMapMinimum) {
                            this.heightMapMinimum = y;
                        }
                        break;
                    }
                }
            }
        }

        // This fill is only a bootstrap baseline. Repainting an already-lit
        // chunk would overwrite the engine's propagated values.
        if (this.world.provider.hasSkyLight()
                && !((PulsarChunk) (Object) this).pulsar$isLightReady()) {
            this.pulsar$fillVanillaSkyColumns(topSegment);
        }

        this.isLightPopulated = true;
        this.markDirty();
        ci.cancel();
    }

    /**
     * Keeps vanilla heightmap bookkeeping but leaves skylight writes to the
     * asynchronous engine.
     */
    @Inject(method = "relightBlock", at = @At("HEAD"), cancellable = true, require = 0)
    private void pulsar$relightBlock(final int x, final int y, final int z, final CallbackInfo ci) {
        ci.cancel();
        final int minBlockY = this.pulsar$getVanillaHeightContext().getMinBlockY();
        final int oldHeight = this.heightMap[z << 4 | x];
        int newHeight = Math.max(oldHeight, y);
        while (newHeight > minBlockY && this.pulsar$opacityAt(x, newHeight - 1, z) == 0) {
            --newHeight;
        }
        if (newHeight == oldHeight) {
            return;
        }

        this.world.markBlocksDirtyVertical(x + (this.x << 4), z + (this.z << 4), newHeight, oldHeight);
        this.heightMap[z << 4 | x] = newHeight;
        if (newHeight < this.heightMapMinimum) {
            this.heightMapMinimum = newHeight;
        } else {
            int minimum = Integer.MAX_VALUE;
            for (int height : this.heightMap) {
                if (height < minimum) {
                    minimum = height;
                }
            }
            this.heightMapMinimum = minimum;
        }
        this.markDirty();
    }

    /**
     * Vanilla-style opacity lookup with Forge contextual values and optional
     * Fluidlogged API contribution.
     */
    @Unique
    private int pulsar$opacityAt(final int x, final int y, final int z) {
        final IBlockState state = this.getBlockState(x, y, z);
        int info = LightInfo.of(state);
        if (LightInfo.hasContextualValues(info)) {
            info = LightInfo.resolveContextual(
                    info, state, this.world, this.pulsar$getVanillaLightLookupPos(),
                    (this.x << 4) + x, y, (this.z << 4) + z);
        }
        final int opacity = LightInfo.opacity(info);
        if (!FluidLightBridge.LOADED) {
            return opacity;
        }
        return FluidLightBridge.maxOpacityAt(
                (Chunk) (Object) this, opacity, x, y, z, this.pulsar$getVanillaLightLookupPos());
    }

    @Unique
    private void pulsar$fillVanillaSkyColumns(final int topSegment) {
        for (int localX = 0; localX < 16; ++localX) {
            for (int localZ = 0; localZ < 16; ++localZ) {
                this.pulsar$fillVanillaSkyColumn(localX, localZ, topSegment);
            }
        }
    }

    /**
     * Walks one sky column top-down, producing the pre-BFS vanilla baseline.
     */
    @Unique
    private void pulsar$fillVanillaSkyColumn(final int x, final int z, final int topSegment) {
        final WorldHeightContext heightContext = this.pulsar$getVanillaHeightContext();
        final ExtendedBlockStorage[] storageArrays = this.getBlockStorageArray();
        int skyLevel = 15;
        for (int y = topSegment + 15; y >= heightContext.getMinBlockY(); --y) {
            final int storageIndex = heightContext.getStorageIndex(y >> 4);
            final ExtendedBlockStorage section = storageIndex >= 0 && storageIndex < storageArrays.length
                    ? storageArrays[storageIndex] : null;
            if (section == null) {
                if (skyLevel != 15) {
                    skyLevel = Math.max(0, skyLevel - 1);
                }
                continue;
            }
            int opacity = this.pulsar$opacityAt(x, y, z);
            if (opacity == 0 && skyLevel != 15) {
                opacity = 1;
            }
            skyLevel = Math.max(0, skyLevel - opacity);
            final NibbleArray skyArray = section.getSkyLight();
            if (skyArray != null) {
                skyArray.set(x, y & 15, z, skyLevel);
            }
            if (skyLevel <= 0) {
                break;
            }
        }
    }

    /** Pulsar's edge reconciliation replaces vanilla gap checks. */
    @Inject(method = "recheckGaps", at = @At("HEAD"), cancellable = true, require = 0)
    private void pulsar$recheckGaps(final boolean isClient, final CallbackInfo ci) {
        ci.cancel();
    }

    /** Pulsar's queue replaces vanilla incremental relight checks. */
    @Inject(method = "enqueueRelightChecks", at = @At("HEAD"), cancellable = true, require = 0)
    private void pulsar$enqueueRelightChecks(final CallbackInfo ci) {
        ci.cancel();
    }

    /**
     * Vanilla's method is also the canonical place that marks terrain and
     * light population complete; preserve those flags when bypassing it.
     */
    @Inject(method = "checkLight()V", at = @At("HEAD"), cancellable = true, require = 0)
    private void pulsar$checkLight(final CallbackInfo ci) {
        this.isTerrainPopulated = true;
        this.isLightPopulated = true;
        ci.cancel();
    }
}
