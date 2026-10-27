package com.sumirelabs.pulsar.light.engine;

import com.sumirelabs.pulsar.api.BlockLightColors;
import com.sumirelabs.pulsar.api.BlockLightFilters;
import com.sumirelabs.pulsar.api.RgbStateProperties;
import com.sumirelabs.pulsar.light.SWMRNibbleArray;
import com.sumirelabs.pulsar.rgb.PackedRgbLight;
import com.sumirelabs.pulsar.rgb.RgbLight;
import com.sumirelabs.pulsar.util.WorldHeightContext;
import net.minecraft.block.state.IBlockState;
import net.minecraft.init.Blocks;
import net.minecraft.init.Bootstrap;
import net.minecraft.item.EnumDyeColor;
import net.minecraft.world.World;
import net.minecraft.world.WorldServer;
import net.minecraft.world.chunk.storage.ExtendedBlockStorage;
import java.util.HashMap;
import java.util.HashSet;

/** Actual packed/per-channel BFS over real sections, with a torch in a closed 3x3x3 glass shell. */
public final class RgbGlassProbe {
    // Independent reference values from SuperNova LightColors, in wool metadata order.
    private static final int[] REFERENCE = {0xFFF,0xFCA,0xF0F,0x08F,0xFF0,0x8F0,0xF7A,0x555,
            0xAAA,0x0FF,0xA0F,0x00F,0x830,0x0F0,0xF00,0x111};

    public static void main(String[] args) throws Exception {
        boolean legacy = Boolean.parseBoolean(args[0]);
        Bootstrap.register();
        var mods = net.minecraftforge.fml.common.Loader.class.getDeclaredField("namedMods");
        mods.setAccessible(true);
        if (mods.get(net.minecraftforge.fml.common.Loader.instance()) == null)
            mods.set(net.minecraftforge.fml.common.Loader.instance(), new HashMap<>());
        BlockLightColors.defaults(); BlockLightFilters.defaults();
        if (legacy) {
            for (var state : Blocks.TORCH.getBlockState().getValidStates()) BlockLightColors.register(state,15,10,6);
            for (EnumDyeColor dye : EnumDyeColor.values()) {
                float[] rgb = dye.getColorComponentValues();
                for (var block : new net.minecraft.block.Block[] {Blocks.STAINED_GLASS, Blocks.STAINED_GLASS_PANE})
                    for (var state : block.getBlockState().getValidStates())
                        if (block.getMetaFromState(state) == dye.getMetadata())
                            BlockLightFilters.register(state, Math.round(15*(1-rgb[0])), Math.round(15*(1-rgb[1])), Math.round(15*(1-rgb[2])));
            }
        }
        BlockLightColors.freeze();
        int source = BlockLightColors.emission(Blocks.TORCH.getDefaultState(),14);
        test("bare", Blocks.AIR.getDefaultState(), source, legacy);
        test("clear", Blocks.GLASS.getDefaultState(), source, legacy);
        test("opaque", Blocks.STONE.getDefaultState(), source, legacy);
        for (EnumDyeColor dye : EnumDyeColor.values()) {
            for (var block : new net.minecraft.block.Block[] {Blocks.STAINED_GLASS, Blocks.STAINED_GLASS_PANE}) {
                var state = block.getStateFromMeta(dye.getMetadata());
                if (!legacy && RgbStateProperties.filter(RgbStateProperties.of(state)) != (0xFFF ^ REFERENCE[dye.getMetadata()]))
                    throw new AssertionError("Reference glass palette mismatch: " + dye);
                test(dye.getName() + (block == Blocks.STAINED_GLASS ? "" : "_pane"), state, source, legacy);
            }
        }
        System.out.println("GLASS_BFS_PASS legacy=" + legacy + " cases=35");
    }

    private static void test(String name, IBlockState shell, int source, boolean legacy) throws Exception {
        World world = world();
        var height = WorldHeightContext.contiguous(0,0);
        var packed = new RgbPackedBlockChannelEngine(world,height,new HashSet<>());
        RgbChannelEngine[] scalar = new RgbChannelEngine[3];
        setup(packed,shell);
        for (int c=0;c<3;c++) { scalar[c]=new RgbChannelEngine(world,height,c,new HashSet<>()); setup(scalar[c],shell); }
        packed.checkBlock(8,8,8); packed.performLightIncrease();
        for (var engine : scalar) { engine.checkBlock(8,8,8); engine.performLightIncrease(); }
        // Compare every cell, not just the sample points, against the independent lane engine.
        for (int x=0;x<16;x++) for (int y=0;y<16;y++) for (int z=0;z<16;z++) {
            int expected=PackedRgbLight.pack(scalar[0].getLightLevel(x,y,z),scalar[1].getLightLevel(x,y,z),scalar[2].getLightLevel(x,y,z));
            if(packed.getLightLevel(x,y,z)!=expected)throw new AssertionError("Packed/lane mismatch: "+name);
        }
        int filter=RgbStateProperties.filter(RgbStateProperties.of(shell));
        for(int distance=2;distance<=5;distance++) for(var direction:PulsarEngine.AXIS_DIRECTIONS) {
            int value=packed.getLightLevel(8+distance*direction.x,8+distance*direction.y,8+distance*direction.z);
            for(int c=0;c<3;c++) {
                int absorption=shell.getBlock()==Blocks.STONE?15:Math.max(1,RgbLight.channel(filter,c));
                int expected=Math.max(0,RgbLight.channel(source,c)-absorption-distance+1);
                if(RgbLight.channel(PackedRgbLight.toCompact(value),c)!=expected)
                    throw new AssertionError(name+" distance="+distance+" channel="+c+" expected="+expected+" actual="+value);
            }
        }
        System.out.printf("GLASS legacy=%s type=%s source=%03X outside=%03X%n",legacy,name,source,
                PackedRgbLight.toCompact(packed.getLightLevel(10,8,8)));
    }

    private static void setup(PulsarEngine engine,IBlockState shell) {
        engine.setupEncodeOffset(7,8,7);
        // Only one real section: null neighbouring sections terminate propagation at its boundary.
        var section=new ExtendedBlockStorage(0,true);
        engine.setChunkSectionInCache(0,0,0,section);
        if(engine instanceof PackedRgbChannelEngine packed) {
            for(int c=0;c<3;c++)packed.rgb.replacePlane(c,engine.chunkSectionIndexOffset,new SWMRNibbleArray());
        } else engine.setNibbleInCache(0,0,0,new SWMRNibbleArray());
        for(int x=7;x<=9;x++)for(int y=7;y<=9;y++)for(int z=7;z<=9;z++)
            section.set(x,y,z,x==8&&y==8&&z==8?Blocks.TORCH.getDefaultState():shell);
    }

    private static World world() throws Exception {
        Class<?> type=Class.forName("sun.misc.Unsafe");var field=type.getDeclaredField("theUnsafe");field.setAccessible(true);
        return (World)type.getMethod("allocateInstance",Class.class).invoke(field.get(null),WorldServer.class);
    }
}
