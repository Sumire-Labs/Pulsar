package com.sumirelabs.pulsar.mixin;

import com.sumirelabs.pulsar.light.ChunkLightHelper;
import com.sumirelabs.pulsar.light.PulsarChunk;
import com.sumirelabs.pulsar.light.SWMRNibbleArray;
import com.sumirelabs.pulsar.light.WorldLightManager;
import com.sumirelabs.pulsar.util.WorldHeightContext;
import com.sumirelabs.pulsar.util.WorldUtil;
import com.sumirelabs.pulsar.world.PulsarWorld;
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
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Keeps Pulsar and vanilla nibble storage aligned when setBlockState creates a
 * previously absent chunk section.
 */
@Mixin(Chunk.class)
public abstract class MixinChunkSectionChanges {

    @Shadow
    @Final
    public int x;
    @Shadow
    @Final
    public int z;
    @Shadow
    @Final
    private World world;
    @Unique
    private boolean pulsar$sectionWasEmpty;
    @Unique
    private WorldHeightContext pulsar$sectionHeightContext;

    @Shadow
    public abstract ExtendedBlockStorage[] getBlockStorageArray();

    @Unique
    private WorldHeightContext pulsar$getSectionHeightContext() {
        if (this.pulsar$sectionHeightContext == null) {
            this.pulsar$sectionHeightContext = WorldUtil.getHeightContext(this.world);
        }
        return this.pulsar$sectionHeightContext;
    }

    @Inject(method = "setBlockState", at = @At("HEAD"), require = 0)
    private void pulsar$preSetBlockState(final BlockPos pos, final IBlockState state,
                                         final CallbackInfoReturnable<IBlockState> cir) {
        final ExtendedBlockStorage[] storage = this.getBlockStorageArray();
        final int sectionY = pos.getY() >> 4;
        final int storageIndex = this.pulsar$getSectionHeightContext().getStorageIndex(sectionY);
        this.pulsar$sectionWasEmpty = storageIndex >= 0 && storageIndex < storage.length
                && storage[storageIndex] == Chunk.NULL_BLOCK_STORAGE;
    }

    @Inject(method = "setBlockState", at = @At("RETURN"), require = 0)
    private void pulsar$postSetBlockState(final BlockPos pos, final IBlockState state,
                                          final CallbackInfoReturnable<IBlockState> cir) {
        if (!this.pulsar$sectionWasEmpty || cir.getReturnValue() == null) {
            return;
        }

        final WorldHeightContext heightContext = this.pulsar$getSectionHeightContext();
        final int sectionY = pos.getY() >> 4;
        final int storageIndex = heightContext.getStorageIndex(sectionY);
        final ExtendedBlockStorage[] storage = this.getBlockStorageArray();
        if (storageIndex < 0 || storageIndex >= storage.length) {
            return;
        }
        final ExtendedBlockStorage section = storage[storageIndex];
        if (section == Chunk.NULL_BLOCK_STORAGE) {
            return;
        }

        this.pulsar$sectionWasEmpty = false;
        final PulsarChunk pulsarChunk = (PulsarChunk) (Object) this;
        ChunkLightHelper.fillVanillaFromEngine(
                heightContext, pulsarChunk.pulsar$getSkyNibbles(), pulsarChunk.pulsar$getBlockNibbles(),
                section, sectionY, this.world.provider.hasSkyLight());

        if (this.world.isRemote) {
            this.pulsar$rewrapClientNibbles(pulsarChunk, section, sectionY);
        }

        final WorldLightManager manager = ((PulsarWorld) this.world).pulsar$getLightManager();
        if (manager != null) {
            manager.queueSectionChange(this.x, sectionY, this.z, false);
        }
    }

    @Unique
    private void pulsar$rewrapClientNibbles(final PulsarChunk pulsarChunk,
                                            final ExtendedBlockStorage section,
                                            final int sectionY) {
        final int lightIndex = this.pulsar$getSectionHeightContext().getLightSectionIndex(sectionY);
        if (lightIndex < 0) {
            return;
        }
        final NibbleArray blockNibble = section.getBlockLight();
        if (blockNibble != null) {
            final SWMRNibbleArray[] blockNibbles = pulsarChunk.pulsar$getBlockNibbles();
            blockNibbles[lightIndex] = new SWMRNibbleArray(blockNibble.getData());
        }
        final NibbleArray skyNibble = section.getSkyLight();
        if (skyNibble != null) {
            final SWMRNibbleArray[] skyNibbles = pulsarChunk.pulsar$getSkyNibbles();
            skyNibbles[lightIndex] = new SWMRNibbleArray(skyNibble.getData());
        }
    }
}
