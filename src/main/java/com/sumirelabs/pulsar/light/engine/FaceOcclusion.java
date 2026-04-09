package com.sumirelabs.pulsar.light.engine;

import com.sumirelabs.pulsar.Pulsar;
import com.sumirelabs.pulsar.api.FaceLightOcclusion;
import it.unimi.dsi.fastutil.objects.Reference2ObjectOpenHashMap;
import net.minecraft.block.Block;
import net.minecraft.block.state.IBlockState;
import net.minecraft.init.Blocks;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.IBlockAccess;
import net.minecraft.world.WorldType;
import net.minecraft.world.biome.Biome;
import net.minecraftforge.fml.common.registry.ForgeRegistries;

/**
 * Per-face light occlusion registry and lookup.
 *
 * <p>Resolution order during scalar BFS propagation:
 * <ol>
 *     <li>{@code block instanceof FaceLightOcclusion} → dynamic per-face
 *         opacity from the block's own logic</li>
 *     <li>{@link #hasSidedTransparency(Block)} → precomputed isSideSolid
 *         table built at postInit</li>
 *     <li>Neither → scalar {@link IBlockState#getLightOpacity()} (current
 *         vanilla behaviour)</li>
 * </ol>
 *
 * <p>Pulsar (scalar release) keeps the per-face table keyed by {@link Block}
 * objects rather than block IDs, since 1.12.2 uses {@link IBlockState}
 * everywhere and there is no useful "block ID" the way 1.7.10 had one.
 */
public final class FaceOcclusion {

    /** {@link Block} → 96 bits packed face solidity. */
    private static final Reference2ObjectOpenHashMap<Block, long[]> FACE_SOLIDITY =
            new Reference2ObjectOpenHashMap<>();

    /** Blocks marked sided-transparent (either via interface or scan). */
    private static final java.util.Set<Block> HAS_SIDED_TRANSPARENCY =
            java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());

    /** AxisDirection ordinal → EnumFacing for isSideSolid calls. */
    private static final EnumFacing[] AXIS_TO_FACING = {
            EnumFacing.EAST,   // 0: POSITIVE_X
            EnumFacing.WEST,   // 1: NEGATIVE_X
            EnumFacing.SOUTH,  // 2: POSITIVE_Z
            EnumFacing.NORTH,  // 3: NEGATIVE_Z
            EnumFacing.UP,     // 4: POSITIVE_Y
            EnumFacing.DOWN,   // 5: NEGATIVE_Y
    };

    private FaceOcclusion() {}

    public static boolean hasSidedTransparency(final Block block) {
        return HAS_SIDED_TRANSPARENCY.contains(block);
    }

    /**
     * Returns true if the given face of a non-interface block is solid (blocks
     * light). Only valid when {@link #hasSidedTransparency(Block)} returns
     * true and the block does NOT implement {@link FaceLightOcclusion}.
     */
    public static boolean isFaceSolid(final Block block, final int meta, final int axisDir) {
        final long[] bits = FACE_SOLIDITY.get(block);
        if (bits == null) return true;
        // Table only covers meta 0-15 — extended-meta blocks must implement
        // FaceLightOcclusion for per-face control; default to solid here.
        if (meta > 15) return true;
        final int bitIndex = meta * 6 + axisDir;
        return (bits[bitIndex >> 6] & (1L << (bitIndex & 63))) != 0;
    }

    /**
     * Scalar absorption resolution: returns a single int (1–15) using vanilla
     * {@link IBlockState#getLightOpacity()} and directional face checks.
     *
     * @param state    destination block state
     * @param dirOrdinal axis direction ordinal (0–5)
     * @return absorption (1–15)
     */
    public static int resolveScalarAbsorption(final IBlockState state, final int dirOrdinal) {
        final Block block = state.getBlock();
        if (block instanceof FaceLightOcclusion) {
            final int v = ((FaceLightOcclusion) block).getDirectionalLightOpacity(state, AXIS_TO_FACING[dirOrdinal]);
            return Math.max(1, v);
        }
        @SuppressWarnings("deprecation") final int opacity = state.getLightOpacity();
        if (opacity > 1 && hasSidedTransparency(block)) {
            return isFaceSolid(block, block.getMetaFromState(state), dirOrdinal) ? opacity : 1;
        }
        return Math.max(1, opacity);
    }

    /**
     * Scan all registered blocks at postInit. For blocks where
     * {@code !isFullCube() && getLightOpacity() > 0}, probe
     * {@code isSideSolid} for all 16 metas × 6 faces.
     */
    public static void registerDefaults() {
        int count = 0;
        final FakeBlockAccess fake = new FakeBlockAccess();

        for (final Block block : ForgeRegistries.BLOCKS) {
            // Interface implementors: just mark, no table needed
            if (block instanceof FaceLightOcclusion) {
                HAS_SIDED_TRANSPARENCY.add(block);
                count++;
                continue;
            }

            // Probe meta 0-15 × 6 faces.
            boolean anySidedDifference = false;
            long bits0 = 0, bits1 = 0;

            for (int meta = 0; meta < 16; meta++) {
                final IBlockState state;
                try {
                    state = block.getStateFromMeta(meta);
                } catch (final Throwable t) {
                    continue;
                }
                @SuppressWarnings("deprecation") final int opacity = state.getLightOpacity();
                if (state.isFullCube() || opacity <= 0) {
                    continue;
                }
                fake.setState(state);

                for (int dir = 0; dir < 6; dir++) {
                    final boolean solid;
                    try {
                        solid = block.isSideSolid(state, fake, BlockPos.ORIGIN, AXIS_TO_FACING[dir]);
                    } catch (final Throwable t) {
                        continue;
                    }
                    if (solid) {
                        final int bitIndex = meta * 6 + dir;
                        if (bitIndex < 64) {
                            bits0 |= 1L << bitIndex;
                        } else {
                            bits1 |= 1L << (bitIndex - 64);
                        }
                    } else {
                        anySidedDifference = true;
                    }
                }
            }

            if (anySidedDifference) {
                FACE_SOLIDITY.put(block, new long[] { bits0, bits1 });
                HAS_SIDED_TRANSPARENCY.add(block);
                count++;
            }
        }

        Pulsar.LOGGER.info("FaceOcclusion: registered {} blocks with per-face transparency", count);
    }

    /**
     * Minimal {@link IBlockAccess} for probing {@code isSideSolid} at
     * registration time. Returns the configured state at {@link BlockPos#ORIGIN},
     * air everywhere else.
     */
    private static final class FakeBlockAccess implements IBlockAccess {

        private IBlockState state = Blocks.AIR.getDefaultState();

        void setState(final IBlockState state) {
            this.state = state;
        }

        @Override
        public IBlockState getBlockState(final BlockPos pos) {
            return pos.equals(BlockPos.ORIGIN) ? this.state : Blocks.AIR.getDefaultState();
        }

        @Override
        public boolean isAirBlock(final BlockPos pos) {
            return !pos.equals(BlockPos.ORIGIN);
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
            if (pos.equals(BlockPos.ORIGIN)) {
                return this.state.isSideSolid(this, pos, side);
            }
            return _default;
        }

        @Override
        public WorldType getWorldType() {
            return WorldType.DEFAULT;
        }
    }
}
