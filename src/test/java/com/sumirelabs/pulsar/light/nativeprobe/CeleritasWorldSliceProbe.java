package com.sumirelabs.pulsar.light.nativeprobe;

import com.sumirelabs.pulsar.api.FaceLightInfo;
import com.sumirelabs.pulsar.api.RawLightAccess;
import net.minecraft.block.BlockSlab;
import net.minecraft.block.BlockStairs;
import net.minecraft.block.state.IBlockState;
import net.minecraft.init.Blocks;
import net.minecraft.init.Bootstrap;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.*;
import net.minecraft.world.biome.Biome;
import net.minecraft.world.chunk.storage.ExtendedBlockStorage;
import net.minecraft.world.gen.structure.StructureBoundingBox;

import java.lang.reflect.Array;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Real transformed WorldSlice/ClonedChunkSection light reads, without starting a client or GL. */
public final class CeleritasWorldSliceProbe {
    private static Object unsafe;
    private static Method allocate;
    private static Class<?> sliceType, sectionType;

    public static void main(String[] ignored) throws Exception {
        var field = Class.forName("sun.misc.Unsafe").getDeclaredField("theUnsafe");
        field.setAccessible(true); unsafe = field.get(null);
        allocate = unsafe.getClass().getMethod("allocateInstance", Class.class);
        Bootstrap.register();
        sliceType = Class.forName("org.taumc.celeritas.impl.world.WorldSlice");
        sectionType = Class.forName("org.taumc.celeritas.impl.world.cloned.ClonedChunkSection");
        if (Arrays.stream(sliceType.getDeclaredMethods()).noneMatch(m -> m.getName().contains("useFaceAwareNeighborLight"))) {
            throw new AssertionError("Production WorldSlice hook was not applied");
        }
        int cases = 0;
        for (BlockPos base : List.of(BlockPos.ORIGIN, new BlockPos(-32, 48, 64), new BlockPos(16, -16, -32))) {
            for (IBlockState receiver : List.of(Blocks.CARPET.getDefaultState(), Blocks.RED_FLOWER.getDefaultState(),
                    Blocks.STONE.getDefaultState())) {
                for (IBlockState source : sources()) for (EnumSkyBlock channel : EnumSkyBlock.values()) {
                    for (int own : new int[]{0, 7, 15}) for (int sourceLight : new int[]{14, 15}) {
                        BlockPos pos = base.add(8, 8, 8);
                        Reference reference = new Reference(pos, receiver, source, channel, own, sourceLight);
                        IBlockAccess slice = slice(base, reference);
                        // Independent expectations, plus parity with the ordinary production path.
                        int expected = expected(reference);
                        int ordinary = reference.getCombinedLight(pos, 0);
                        int actual = slice.getCombinedLight(pos, 0);
                        if (ordinary != expected || actual != expected) {
                            throw new AssertionError("receiver=" + receiver + " source=" + source + " channel=" + channel
                                    + " base=" + base + " own=" + own + " neighbor=" + sourceLight
                                    + " expected=" + Integer.toHexString(expected) + " ordinary=" + Integer.toHexString(ordinary)
                                    + " celeritas=" + Integer.toHexString(actual));
                        }
                        cases++;
                    }
                }
            }
        }
        System.out.println("CELERITAS_WORLDSLICE_PASS cases=" + cases);
    }

    private static List<IBlockState> sources() {
        List<IBlockState> states = new ArrayList<>();
        for (EnumFacing facing : EnumFacing.HORIZONTALS) for (BlockStairs.EnumHalf half : BlockStairs.EnumHalf.values()) {
            states.add(Blocks.OAK_STAIRS.getDefaultState().withProperty(BlockStairs.FACING, facing).withProperty(BlockStairs.HALF, half));
        }
        for (BlockSlab.EnumBlockHalf half : BlockSlab.EnumBlockHalf.values()) {
            states.add(Blocks.WOODEN_SLAB.getDefaultState().withProperty(BlockSlab.HALF, half));
        }
        states.addAll(List.of(Blocks.STONE.getDefaultState(), Blocks.GLASS.getDefaultState(),
                Blocks.GLOWSTONE.getDefaultState(), Blocks.LIT_PUMPKIN.getDefaultState(),
                Blocks.LEAVES.getDefaultState(), Blocks.WATER.getDefaultState()));
        return states;
    }

    private static int expected(Reference r) {
        int level = r.own;
        if (r.receiver.useNeighborBrightness()) {
            // Glowstone uses transparent material in 1.12; the jack o'lantern
            // exercises an opaque emitter, whose own block light must survive.
            boolean closed = r.source.getBlock() == Blocks.STONE || r.source.getBlock() == Blocks.LIT_PUMPKIN
                    || (r.source.getBlock() instanceof BlockStairs && r.source.getValue(BlockStairs.HALF) == BlockStairs.EnumHalf.BOTTOM)
                    || (r.source.getBlock() instanceof BlockSlab && r.source.getValue(BlockSlab.HALF) == BlockSlab.EnumBlockHalf.BOTTOM);
            int incoming = closed ? (r.channel == EnumSkyBlock.BLOCK ? r.source.getLightValue(r, r.pos.up()) : 0) : r.sourceLight;
            int attenuated = r.channel == EnumSkyBlock.SKY && incoming == 15 ? 15 : incoming - 1;
            level = Math.max(level, attenuated);
        }
        return level << (r.channel == EnumSkyBlock.SKY ? 20 : 4);
    }

    private static IBlockAccess slice(BlockPos base, Reference reference) throws Exception {
        World world = (World) allocate.invoke(unsafe, WorldServer.class);
        var provider = World.class.getDeclaredField("provider"); provider.setAccessible(true);
        provider.set(world, new WorldProvider() {
            public DimensionType getDimensionType() { return DimensionType.OVERWORLD; }
            public boolean hasSkyLight() { return true; }
        });
        Object slice = allocate.invoke(unsafe, sliceType);
        set(slice, "world", world);
        set(slice, "baseX", base.getX()); set(slice, "baseY", base.getY()); set(slice, "baseZ", base.getZ());
        set(slice, "volume", new StructureBoundingBox(base.getX(), base.getY(), base.getZ(),
                base.getX() + 15, base.getY() + 15, base.getZ() + 15));
        IBlockState[][] states = new IBlockState[64][];
        states[0] = new IBlockState[4096]; Arrays.fill(states[0], Blocks.AIR.getDefaultState());
        states[0][8 | 8 << 8 | 8 << 4] = reference.receiver;
        states[0][8 | 9 << 8 | 8 << 4] = reference.source;
        set(slice, "blockStatesArrays", states);
        Object section = allocate.invoke(unsafe, sectionType);
        ExtendedBlockStorage storage = new ExtendedBlockStorage(0, true);
        var nibble = reference.channel == EnumSkyBlock.SKY ? storage.getSkyLight() : storage.getBlockLight();
        nibble.set(8, 8, 8, reference.own); nibble.set(8, 9, 8, reference.sourceLight);
        set(section, "data", storage);
        Object sections = Array.newInstance(sectionType, 64); Array.set(sections, 0, section); set(slice, "sections", sections);
        return (IBlockAccess) slice;
    }

    private static void set(Object target, String name, Object value) throws Exception {
        var field = target.getClass().getDeclaredField(name); field.setAccessible(true); field.set(target, value);
    }

    private static final class Reference implements IBlockAccess, RawLightAccess {
        final BlockPos pos;
        final IBlockState receiver, source;
        final EnumSkyBlock channel;
        final int own, sourceLight;
        Reference(BlockPos pos, IBlockState receiver, IBlockState source, EnumSkyBlock channel, int own, int sourceLight) {
            this.pos = pos; this.receiver = receiver; this.source = source; this.channel = channel; this.own = own; this.sourceLight = sourceLight;
        }
        public IBlockState getBlockState(BlockPos p) { return p.equals(pos) ? receiver : p.equals(pos.up()) ? source : Blocks.AIR.getDefaultState(); }
        public int pulsar$getRawLight(EnumSkyBlock type, BlockPos p) { return type != channel ? 0 : p.equals(pos) ? own : p.equals(pos.up()) ? sourceLight : 0; }
        public int getCombinedLight(BlockPos p, int emission) {
            FaceLightInfo info = (FaceLightInfo) getBlockState(p);
            return info.pulsar$getLightFor(this, EnumSkyBlock.SKY, p) << 20
                    | Math.max(emission, info.pulsar$getLightFor(this, EnumSkyBlock.BLOCK, p)) << 4;
        }
        public TileEntity getTileEntity(BlockPos p) { return null; }
        public boolean isAirBlock(BlockPos p) { return getBlockState(p).getBlock() == Blocks.AIR; }
        public Biome getBiome(BlockPos p) { return null; }
        public int getStrongPower(BlockPos p, EnumFacing face) { return 0; }
        public WorldType getWorldType() { return WorldType.DEFAULT; }
        public boolean isSideSolid(BlockPos p, EnumFacing face, boolean fallback) { return getBlockState(p).isSideSolid(this, p, face); }
    }
}
