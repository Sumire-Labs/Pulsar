package com.sumirelabs.pulsar.light.engine;

import com.sumirelabs.pulsar.light.SWMRNibbleArray;
import com.sumirelabs.pulsar.rgb.PackedRgbLight;
import com.sumirelabs.pulsar.rgb.RgbLight;
import com.sumirelabs.pulsar.util.WorldHeightContext;
import net.minecraft.world.World;
import net.minecraft.world.chunk.storage.ExtendedBlockStorage;
import java.util.HashSet;

/** Keeps the same real BFS buffers across power/color changes, including decreases. */
public final class MultipartPropagation {
    public static void publishNeighbour(World world, net.minecraft.world.chunk.Chunk chunk, int level) {
        var engine = new ScalarBlockEngine(world, WorldHeightContext.VANILLA);
        engine.setupEncodeOffset(chunk.x * 16 + 7, 64, chunk.z * 16 + 7);
        engine.setChunkInCache(chunk.x, chunk.z, chunk);
        var nibble = ((com.sumirelabs.pulsar.light.PulsarChunk)chunk).pulsar$getBlockNibbles()[5];
        nibble.setNonNull(); nibble.set(0,0,0,level);
        int idx=chunk.x+5*chunk.z+100+engine.chunkSectionIndexOffset;
        engine.publishCacheNibble(idx,nibble,false);
    }
    private final ScalarBlockEngine scalar;
    private final RgbPackedBlockChannelEngine rgb;
    public MultipartPropagation(World world, ExtendedBlockStorage section, boolean colored) {
        scalar = new ScalarBlockEngine(world,WorldHeightContext.VANILLA);
        setup(scalar, section);
        rgb = colored ? new RgbPackedBlockChannelEngine(world,WorldHeightContext.VANILLA,new HashSet<>()) : null;
        if(rgb!=null)setup(rgb, section);
    }
    private static void setup(PulsarEngine engine, ExtendedBlockStorage section) {
        engine.setupEncodeOffset(7,64,7);
        engine.setChunkSectionInCache(0,4,0,section);
        if(engine instanceof PackedRgbChannelEngine packed) {
            int idx=100+engine.chunkSectionIndexOffset;
            for(int c=0;c<3;c++)packed.rgb.replacePlane(c,idx,new SWMRNibbleArray());
        } else engine.setNibbleInCache(0,4,0,new SWMRNibbleArray());
    }
    public void update(int expectedScalar,int expectedRgb) {
        update(scalar);
        check(scalar.getLightLevel(8,64,8)==expectedScalar,"Scalar source");
        check(scalar.getLightLevel(10,64,8)==Math.max(0,expectedScalar-2),"Scalar neighbour");
        if(rgb!=null) {
            update(rgb);
            check(PackedRgbLight.toCompact(rgb.getLightLevel(8,64,8))==expectedRgb,"RGB source");
            int expected=RgbLight.pack(Math.max(0,(expectedRgb>>>8&15)-2),Math.max(0,(expectedRgb>>>4&15)-2),Math.max(0,(expectedRgb&15)-2));
            check(PackedRgbLight.toCompact(rgb.getLightLevel(10,64,8))==expected,"RGB neighbour");
        }
    }
    private static void update(PulsarEngine engine) {
        engine.checkBlock(8,64,8);
        engine.performLightDecrease();
        engine.performLightIncrease();
        check(!engine.wasQueueOverflowed(),"Queue overflow");
    }
    private static void check(boolean ok,String message) { if(!ok)throw new AssertionError(message); }
}
