package com.sumirelabs.pulsar.light.nativeprobe;

import com.sumirelabs.pulsar.light.PulsarChunk;
import com.sumirelabs.pulsar.light.SWMRNibbleArray;
import com.sumirelabs.pulsar.util.WorldHeightContext;
import net.minecraft.init.Blocks;
import net.minecraft.init.Bootstrap;
import net.minecraft.network.play.server.SPacketChunkData;
import net.minecraft.world.*;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.storage.ExtendedBlockStorage;
import net.minecraft.world.storage.WorldInfo;
import net.minecraftforge.fml.common.Loader;

public final class PacketLightProbe {
    public static void main(String[] args) throws Exception {
        Bootstrap.register();
        var mods = Loader.class.getDeclaredField("namedMods");
        mods.setAccessible(true);
        if (mods.get(Loader.instance()) == null) mods.set(Loader.instance(), new java.util.HashMap<>());
        int cases = 0;
        for (boolean sky : new boolean[]{false, true}) for (int level : new int[]{0, 14})
                for (int mask : new int[]{0xffff, 1 << 4, 0}) {
            World world = world(sky);
            Chunk source = new Chunk(world, 0, 0);
            PulsarChunk light = (PulsarChunk) source;
            SWMRNibbleArray nibble = light.pulsar$getBlockNibbles()[WorldHeightContext.VANILLA.getLightSectionIndex(4)];
            nibble.set(15, 0, 8, level);
            nibble.updateVisible();
            // An ordinary existing section must also survive the packet path.
            source.getBlockStorageArray()[0] = new ExtendedBlockStorage(0, sky);
            source.getBlockStorageArray()[0].set(0, 0, 0, Blocks.STONE.getDefaultState());
            SPacketChunkData packet = new SPacketChunkData(source, mask);
            boolean selected = (mask & (1 << 4)) != 0 && (mask != 0xffff || level != 0);
            if (((packet.getExtractedSize() & (1 << 4)) != 0) != selected) {
                throw new AssertionError("Packet lost the absent light-only section: sky=" + sky + " mask=" + mask);
            }
            Chunk received = new Chunk(world, 0, 0);
            var buffer = packet.getReadBuffer();
            received.read(buffer, packet.getExtractedSize(), packet.isFullChunk());
            if ((mask & 1) != 0 && (received.getBlockStorageArray()[0] == null
                    || received.getBlockStorageArray()[0].get(0, 0, 0).getBlock() != Blocks.STONE)) {
                throw new AssertionError("Packet ignored Sponge's captured storage array");
            }
            if (selected && received.getBlockStorageArray()[4].getBlockLight().get(15, 0, 8) != level) {
                throw new AssertionError("Client did not receive light level " + level);
            }
            if (buffer.readableBytes() != 0) throw new AssertionError("Packet sizing and extraction disagree");
            if (source.getBlockStorageArray()[4] != null) throw new AssertionError("Packet installed temporary storage in live chunk");
            if (Boolean.getBoolean("issue24.sponge")) {
                PacketSnapshotProbe snapshot = (PacketSnapshotProbe) packet;
                if (snapshot.snapshotCalls() != 1 || snapshot.extractionCalls() != 1 || !snapshot.snapshotReleased()) {
                    throw new AssertionError("Sponge snapshot/cleanup was bypassed");
                }
            }
            cases++;
        }
        System.out.println("PACKET_LIGHT_PASS cases=" + cases + " sponge=" + Boolean.getBoolean("issue24.sponge"));
    }

    private static World world(boolean sky) throws Exception {
        var unsafe = Class.forName("sun.misc.Unsafe");
        var singleton = unsafe.getDeclaredField("theUnsafe"); singleton.setAccessible(true);
        World world = (World) unsafe.getMethod("allocateInstance", Class.class).invoke(singleton.get(null), WorldServer.class);
        var provider = World.class.getDeclaredField("provider"); provider.setAccessible(true);
        provider.set(world, new WorldProvider() {
            public DimensionType getDimensionType() { return DimensionType.OVERWORLD; }
            public boolean hasSkyLight() { return sky; }
        });
        var info = World.class.getDeclaredField("worldInfo"); info.setAccessible(true);
        info.set(world, new WorldInfo(new WorldSettings(1L, GameType.CREATIVE, false, false, WorldType.FLAT), "packet-probe"));
        return world;
    }
}
