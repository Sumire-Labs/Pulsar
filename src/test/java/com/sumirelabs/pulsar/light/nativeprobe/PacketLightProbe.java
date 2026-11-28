/*
 * SPDX-License-Identifier: LGPL-3.0-only
 */

package com.sumirelabs.pulsar.light.nativeprobe;

import com.sumirelabs.pulsar.Pulsar;
import com.sumirelabs.pulsar.api.ExtendedWorld;
import com.sumirelabs.pulsar.light.PulsarChunk;
import com.sumirelabs.pulsar.light.SWMRNibbleArray;
import com.sumirelabs.pulsar.light.WorldLightManager;
import com.sumirelabs.pulsar.proxy.IProxy;
import com.sumirelabs.pulsar.util.WorldHeightContext;
import com.sumirelabs.pulsar.world.PulsarWorld;

import net.minecraft.init.Blocks;
import net.minecraft.init.Bootstrap;
import net.minecraft.network.play.server.SPacketChunkData;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.*;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.storage.ExtendedBlockStorage;
import net.minecraft.world.storage.WorldInfo;
import net.minecraftforge.fml.common.Loader;

public final class PacketLightProbe {

    private static final class ClientWorld extends WorldServer implements PulsarWorld, ExtendedWorld {

        private WorldLightManager manager;
        private boolean markedNeighborForRender;
        private ClientWorld() {
            super(null, null, null, 0, null);
        }
        public WorldLightManager pulsar$getLightManager() {
            return this.manager;
        }
        public Chunk pulsar$getAnyChunkImmediately(int x, int z) {
            return this.manager.getLoadedChunk(x, z);
        }
        public boolean pulsar$hasChunkPendingLight(int x, int z) {
            return this.manager.hasChunkPendingLight(x, z);
        }
        public void pulsar$shutdown() {
            this.manager.shutdown();
        }
        public void pulsar$flushLightUpdates() {}
        @Override
        public void markBlockRangeForRenderUpdate(int x, int y, int z, int xx, int yy, int zz) {
            if (x <= 32 && 32 <= xx && y <= 64 && 64 <= yy && z <= 8 && 8 <= zz)
                this.markedNeighborForRender = true;
        }

    }

    public static void main(String[] args) throws Exception {
        Bootstrap.register();
        Pulsar.proxy = new IProxy() {
        };
        var mods = Loader.class.getDeclaredField("namedMods");
        mods.setAccessible(true);
        if (mods.get(Loader.instance()) == null)
            mods.set(Loader.instance(), new java.util.HashMap<>());
        int cases = 0;
        for (boolean sky : new boolean[]{false, true})
            for (int level : new int[]{0, 14})
                for (int mask : new int[]{0xffff, 1 << 4, 0}) {
                    World world = world(sky);
                    Chunk source = new Chunk(world, -7, 11);
                    PulsarChunk light = (PulsarChunk) source;
                    SWMRNibbleArray nibble = light.pulsar$getBlockNibbles()[WorldHeightContext.VANILLA
                        .getLightSectionIndex(4)];
                    nibble.set(15, 0, 8, level);
                    nibble.updateVisible();
                    // An ordinary existing section must also survive the packet path.
                    source.getBlockStorageArray()[0] = new ExtendedBlockStorage(0, sky);
                    source.getBlockStorageArray()[0].set(0, 0, 0, Blocks.STONE.getDefaultState());
                    SPacketChunkData packet = new SPacketChunkData(source, mask);
                    var metadata = (com.sumirelabs.pulsar.light.ChunkPacketCoordinates) packet;
                    if (metadata.pulsar$chunkX() != -7 || metadata.pulsar$chunkZ() != 11
                        || metadata.pulsar$fullChunk() != (mask == 0xffff)) {
                        throw new AssertionError("Common-side packet metadata lost coordinates or full-chunk flag");
                    }
                    boolean selected = (mask & (1 << 4)) != 0 && (mask != 0xffff || level != 0);
                    if (((packet.getExtractedSize() & (1 << 4)) != 0) != selected) {
                        throw new AssertionError(
                            "Packet lost the absent light-only section: sky=" + sky + " mask=" + mask);
                    }
                    Chunk received = new Chunk(world, -7, 11);
                    var buffer = packet.getReadBuffer();
                    received.read(buffer, packet.getExtractedSize(), packet.isFullChunk());
                    if ((mask & 1) != 0 && (received.getBlockStorageArray()[0] == null
                        || received.getBlockStorageArray()[0].get(0, 0, 0).getBlock() != Blocks.STONE)) {
                        throw new AssertionError("Packet ignored Sponge's captured storage array");
                    }
                    if (selected && received.getBlockStorageArray()[4].getBlockLight().get(15, 0, 8) != level) {
                        throw new AssertionError("Client did not receive light level " + level);
                    }
                    if (buffer.readableBytes() != 0)
                        throw new AssertionError("Packet sizing and extraction disagree");
                    if (source.getBlockStorageArray()[4] != null)
                        throw new AssertionError("Packet installed temporary storage in live chunk");
                    if (Boolean.getBoolean("issue24.sponge")) {
                        PacketSnapshotProbe snapshot = (PacketSnapshotProbe) packet;
                        if (snapshot.snapshotCalls() != 1 || snapshot.extractionCalls() != 1
                            || !snapshot.snapshotReleased()) {
                            throw new AssertionError("Sponge snapshot/cleanup was bypassed");
                        }
                    }
                    cases++;
                }
        verifyDenseRemovalAcrossPacketBoundary(false);
        verifyDenseRemovalAcrossPacketBoundary(true);
        System.out.println("PACKET_SCALAR_SEAMS_PASS cases=2");
        System.out.println("PACKET_LIGHT_PASS cases=" + cases + " sponge=" + Boolean.getBoolean("issue24.sponge"));
    }

    private static void verifyDenseRemovalAcrossPacketBoundary(boolean keepLamp) throws Exception {
        World server = world(false);
        ClientWorld client = world(false, ClientWorld.class);
        var remote = World.class.getDeclaredField("isRemote");
        remote.setAccessible(true);
        remote.setBoolean(client, true);
        client.manager = new WorldLightManager(client, false, true);
        try {
            for (int cx = -1; cx <= 2; ++cx) {
                for (int cz = -1; cz <= 2; ++cz) {
                    receive(new Chunk(client, cx, cz),
                        new SPacketChunkData(litChunk(server, cx, cz, true, keepLamp), 0xffff));
                }
            }
            drain(client.manager);
            var neighbor = client.manager.getLoadedChunk(2, 0);
            var edge = new BlockPos(32, 64, 8);
            if (neighbor.getLightFor(EnumSkyBlock.BLOCK, edge) != 14)
                throw new AssertionError("Dense packet fixture did not light its neighbor");
            client.markedNeighborForRender = false;
            updatePlate(server, client.manager, false, keepLamp);
            assertLight(neighbor, edge.getX(), edge.getY(), edge.getZ(), keepLamp ? 9 : 0);
            if (!client.markedNeighborForRender)
                throw new AssertionError("Neighbor lighting changed without marking its render section");
            assertField(client.manager, false, keepLamp);
            updatePlate(server, client.manager, true, keepLamp);
            assertField(client.manager, true, keepLamp);
            updatePlate(server, client.manager, false, keepLamp);
            assertField(client.manager, false, keepLamp);
        } finally {
            client.pulsar$shutdown();
        }
    }

    private static void updatePlate(World server, WorldLightManager manager, boolean plate, boolean keepLamp) {
        for (int cx = 0; cx <= 1; ++cx)
            for (int cz = 0; cz <= 1; ++cz)
                receive(manager.getLoadedChunk(cx, cz),
                    new SPacketChunkData(litChunk(server, cx, cz, plate, keepLamp), 1 << 4));
        drain(manager);
    }

    private static void assertField(WorldLightManager manager, boolean plate, boolean keepLamp) {
        for (int x = -16; x < 48; ++x) {
            for (int z = -16; z < 48; ++z) {
                var target = manager.getLoadedChunk(x >> 4, z >> 4);
                for (int y = 50; y <= 78; ++y)
                    assertLight(target, x, y, z, fixtureLevel(x, y, z, plate, keepLamp));
            }
        }
    }

    private static Chunk litChunk(World world, int cx, int cz, boolean plate, boolean keepLamp) {
        Chunk chunk = new Chunk(world, cx, cz);
        if (plate && cx >= 0 && cx <= 1 && cz >= 0 && cz <= 1) {
            var section = new ExtendedBlockStorage(64, false);
            chunk.getBlockStorageArray()[4] = section;
            for (int x = 0; x < 16; ++x)
                for (int z = 0; z < 16; ++z)
                    section.set(x, 0, z, Blocks.GLOWSTONE.getDefaultState());
        } else if (keepLamp && cx == 2 && cz == 0) {
            var section = new ExtendedBlockStorage(64, false);
            chunk.getBlockStorageArray()[4] = section;
            section.set(6, 0, 8, Blocks.GLOWSTONE.getDefaultState());
        }
        var light = (PulsarChunk) chunk;
        var nibbles = light.pulsar$getBlockNibbles();
        for (int y = 50; y <= 78; ++y) {
            var nibble = nibbles[WorldHeightContext.VANILLA.getLightSectionIndex(y >> 4)];
            for (int x = cx << 4; x < (cx + 1) << 4; ++x) {
                for (int z = cz << 4; z < (cz + 1) << 4; ++z) {
                    int level = fixtureLevel(x, y, z, plate, keepLamp);
                    if (level > 0)
                        nibble.set(x, y, z, level);
                }
            }
        }
        for (var nibble : nibbles)
            nibble.updateVisible();
        light.pulsar$setLightReady(true);
        return chunk;
    }

    private static int fixtureLevel(int x, int y, int z, boolean plate, boolean keepLamp) {
        int plateDistance = Math.max(0, Math.max(-x, x - 31)) + Math.max(0, Math.max(-z, z - 31));
        int lampDistance = Math.abs(x - 38) + Math.abs(z - 8);
        int horizontalDistance = Math.min(plate ? plateDistance : 15, keepLamp ? lampDistance : 15);
        return Math.max(0, 15 - Math.abs(y - 64) - horizontalDistance);
    }

    private static void assertLight(Chunk chunk, int x, int y, int z, int expected) {
        var pos = new BlockPos(x, y, z);
        int actual = chunk.getLightFor(EnumSkyBlock.BLOCK, pos);
        if (actual != expected)
            throw new AssertionError("Block light at " + pos + ": " + actual + " != " + expected);
    }

    private static void receive(Chunk target, SPacketChunkData packet) {
        target.read(packet.getReadBuffer(), packet.getExtractedSize(), packet.isFullChunk());
    }

    private static void drain(WorldLightManager manager) {
        for (int i = 0; i < 32 && manager.hasUpdates(); i++)
            manager.processClientRenderUpdates();
        if (manager.hasUpdates())
            throw new AssertionError("Client light queue did not drain");
        manager.processClientRenderUpdates();
    }

    private static World world(boolean sky) throws Exception {
        return world(sky, WorldServer.class);
    }

    private static <T extends World> T world(boolean sky, Class<T> type) throws Exception {
        var unsafe = Class.forName("sun.misc.Unsafe");
        var singleton = unsafe.getDeclaredField("theUnsafe");
        singleton.setAccessible(true);
        T world = type.cast(unsafe.getMethod("allocateInstance", Class.class).invoke(singleton.get(null), type));
        var provider = World.class.getDeclaredField("provider");
        provider.setAccessible(true);
        provider.set(world, new WorldProvider() {

            public DimensionType getDimensionType() {
                return DimensionType.OVERWORLD;
            }
            public boolean hasSkyLight() {
                return sky;
            }

        });
        var info = World.class.getDeclaredField("worldInfo");
        info.setAccessible(true);
        info.set(world,
            new WorldInfo(new WorldSettings(1L, GameType.CREATIVE, false, false, WorldType.FLAT), "packet-probe"));
        return world;
    }

}
