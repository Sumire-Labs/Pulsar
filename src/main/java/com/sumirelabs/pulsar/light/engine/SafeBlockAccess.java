package com.sumirelabs.pulsar.light.engine;

import com.sumirelabs.pulsar.util.SnapshotChunkMap;
import net.minecraft.block.state.IBlockState;
import net.minecraft.init.Blocks;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.IBlockAccess;
import net.minecraft.world.WorldType;
import net.minecraft.world.biome.Biome;
import net.minecraft.world.chunk.Chunk;

/**
 * Thread-safe {@link IBlockAccess} that routes chunk lookups through Pulsar's
 * copy-on-write chunk map instead of {@code ChunkProviderServer}. This
 * prevents off-thread access to the chunk provider's internal data
 * structures.
 *
 * <p>Any access to an unloaded chunk returns air / 0 / null — no chunk loading
 * is triggered.
 */
public final class SafeBlockAccess implements IBlockAccess {

    private final SnapshotChunkMap chunkMap;

    public SafeBlockAccess(final SnapshotChunkMap chunkMap) {
        this.chunkMap = chunkMap;
    }

    private Chunk getChunk(final int cx, final int cz) {
        return this.chunkMap.get(((long) cx << 32) | (cz & 0xFFFFFFFFL));
    }

    @Override
    public IBlockState getBlockState(final BlockPos pos) {
        final Chunk chunk = this.getChunk(pos.getX() >> 4, pos.getZ() >> 4);
        if (chunk == null) return Blocks.AIR.getDefaultState();
        return chunk.getBlockState(pos);
    }

    @Override
    public boolean isAirBlock(final BlockPos pos) {
        return this.getBlockState(pos).getBlock() == Blocks.AIR;
    }

    @Override
    public TileEntity getTileEntity(final BlockPos pos) {
        return null;
    }

    @Override
    public int getCombinedLight(final BlockPos pos, final int lightValue) {
        return lightValue;
    }

    @Override
    public int getStrongPower(final BlockPos pos, final EnumFacing direction) {
        return 0;
    }

    @Override
    public Biome getBiome(final BlockPos pos) {
        return Biome.getBiomeForId(0);
    }

    @Override
    public boolean isSideSolid(final BlockPos pos, final EnumFacing side, final boolean _default) {
        final Chunk chunk = this.getChunk(pos.getX() >> 4, pos.getZ() >> 4);
        if (chunk == null) return _default;
        return this.getBlockState(pos).isSideSolid(this, pos, side);
    }

    @Override
    public WorldType getWorldType() {
        return WorldType.DEFAULT;
    }
}
