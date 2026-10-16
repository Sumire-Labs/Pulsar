package com.sumirelabs.pulsar.rgb;

import com.sumirelabs.pulsar.config.PulsarConfig;
import com.sumirelabs.pulsar.util.CoordinateUtils;
import com.sumirelabs.pulsar.util.WorldUtil;
import com.sumirelabs.pulsar.light.PulsarChunk;
import com.sumirelabs.pulsar.light.SWMRNibbleArray;
import net.minecraft.client.Minecraft;
import net.minecraft.world.World;
import net.minecraft.world.chunk.Chunk;
import java.util.concurrent.ConcurrentHashMap;

/** Main-thread installs; mesh workers only read immutable copies, never live World data. */
public final class RgbClient {
    private record View(Chunk chunk, RgbSnapshot rgb, byte[][] sky) {}
    private static final ConcurrentHashMap<Long, View> VIEWS = new ConcurrentHashMap<>();
    private static World owner;
    public static volatile boolean serverHasRgbChannel;
    private static boolean requested;
    private static Object requester;
    private static volatile boolean active;
    public static volatile boolean vanillaRendered, celeritasRendered;
    private static volatile float skyScale = 1;
    private static final ConcurrentHashMap<Long, Chunk> REFRESH = new ConcurrentHashMap<>();
    private RgbClient() {}
    public static boolean active() { return active; }
    public static void unload(World world) {
        if (owner == world) { VIEWS.clear(); REFRESH.clear(); owner = null; active = false; requested = false; requester = null; }
    }
    public static void tick() {
        World world = Minecraft.getMinecraft().world;
        if (owner != world) { VIEWS.clear(); REFRESH.clear(); owner = world; active = false; requested = false; }
        if (world == null) return;
        if (requester != Minecraft.getMinecraft().player) {
            requester = Minecraft.getMinecraft().player;
            requested = false;
        }
        if (!requested && serverHasRgbChannel && Minecraft.getMinecraft().player != null) {
            requested = true;
            RgbNetwork.requestColors();
        }
        float nextSky = Math.max(0, 15 - world.getSkylightSubtracted()) / 15f;
        if (skyScale != nextSky) {
            skyScale = nextSky;
            var height = WorldUtil.getHeightContext(world);
            for (View view : VIEWS.values()) world.markBlockRangeForRenderUpdate(view.chunk.x << 4, height.getMinBlockY(), view.chunk.z << 4,
                    (view.chunk.x << 4) + 15, height.getMaxBlockY(), (view.chunk.z << 4) + 15);
        }
        for (var entry : REFRESH.entrySet()) if (REFRESH.remove(entry.getKey(), entry.getValue())
                && world.getChunkProvider().getLoadedChunk(entry.getValue().x, entry.getValue().z) == entry.getValue()) refresh(entry.getValue());
        VIEWS.entrySet().removeIf(e -> world.getChunkProvider().getLoadedChunk(e.getValue().chunk.x, e.getValue().chunk.z) != e.getValue().chunk);
    }
    public static void receive(RgbPacket packet, Object connection) {
        Minecraft mc = Minecraft.getMinecraft();
        mc.addScheduledTask(() -> {
            if (mc.getConnection() != connection || mc.world == null || mc.world.provider.getDimension() != packet.dimension
                    || !com.sumirelabs.pulsar.core.RgbMixinSelection.rgbEnabled()) return;
            if (owner != mc.world) { VIEWS.clear(); owner = mc.world; }
            Chunk chunk = mc.world.getChunkProvider().getLoadedChunk(packet.x, packet.z);
            if (chunk == null || chunk instanceof net.minecraft.world.chunk.EmptyChunk
                    || chunk.x != packet.x || chunk.z != packet.z) return;
            RgbChunk rgb = (RgbChunk) chunk;
            RgbSnapshot previous = rgb.pulsar$getRgbSnapshot();
            if (previous != null && previous.revision > packet.snapshot.revision) return;
            var height = WorldUtil.getHeightContext(mc.world);
            if (packet.snapshot.minSection != height.getMinLightSection() || packet.snapshot.sectionCount() != height.getTotalLightSections()) return;
            rgb.pulsar$setRgbSnapshot(packet.snapshot);
            SWMRNibbleArray[] block = new SWMRNibbleArray[height.getTotalLightSections()];
            var sections = chunk.getBlockStorageArray();
            for (int i = 0; i < block.length; i++) {
                byte[] bytes = packet.snapshot.canonicalSection(i);
                int slot = height.getStorageIndex(height.getMinLightSection() + i);
                if (slot >= 0 && slot < sections.length && sections[slot] != null) {
                    byte[] vanilla = sections[slot].getBlockLight().getData();
                    if (bytes == null) java.util.Arrays.fill(vanilla, (byte) 0); else System.arraycopy(bytes, 0, vanilla, 0, 2048);
                    block[i] = new SWMRNibbleArray(vanilla);
                } else block[i] = new SWMRNibbleArray(bytes);
            }
            ((PulsarChunk) chunk).pulsar$setBlockNibbles(block);
            refresh(chunk);
            active = true;
            int min = WorldUtil.getHeightContext(mc.world).getMinBlockY();
            int max = WorldUtil.getHeightContext(mc.world).getMaxBlockY();
            mc.world.markBlockRangeForRenderUpdate((packet.x << 4) - 1, min, (packet.z << 4) - 1,
                    (packet.x << 4) + 16, max, (packet.z << 4) + 16);
        });
    }
    public static void chunkRead(Chunk chunk) {
        if (((RgbChunk) chunk).pulsar$getRgbSnapshot() != null)
            REFRESH.put(CoordinateUtils.getChunkKey(chunk.x, chunk.z), chunk);
    }
    public static void refresh(Chunk chunk) {
        if (!chunk.getWorld().isRemote) return;
        RgbSnapshot rgb = ((RgbChunk) chunk).pulsar$getRgbSnapshot();
        if (rgb == null) return;
        SWMRNibbleArray[] sky = ((PulsarChunk) chunk).pulsar$getSkyNibbles();
        byte[][] copy = new byte[rgb.sectionCount()][];
        if (chunk.getWorld().provider.hasSkyLight() && sky != null) for (int i = 0; i < Math.min(copy.length, sky.length); i++) {
            SWMRNibbleArray nibble = sky[i];
            if (nibble != null && !nibble.isNullNibbleVisible()) {
                byte[] bytes = nibble.getVisibleData();
                if (bytes != null) copy[i] = bytes.clone();
                continue;
            }
            // Match Pulsar's extrusion for absent sections, including shadows from above.
            byte[] bytes = new byte[2048];
            int y = (rgb.minSection + i) << 4;
            var height = WorldUtil.getHeightContext(chunk.getWorld());
            for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++) {
                int index = x | z << 4;
                int value = com.sumirelabs.pulsar.light.ChunkLightHelper.getSkyLight(height, sky, x, y, z);
                bytes[index >> 1] |= (byte) (value << ((index & 1) * 4));
            }
            for (int row = 1; row < 16; row++) System.arraycopy(bytes, 0, bytes, row * 128, 128);
            copy[i] = bytes;
        }
        VIEWS.put(CoordinateUtils.getChunkKey(chunk.x, chunk.z), new View(chunk, rgb, copy));
    }
    /** Prototype vertex modulation retains vanilla AO and the scalar lightmap.
     * Sky and a small ambient term desaturate color; it is not shader-level RGB lightmaps.
     */
    public static int tintAbgr(int color, double x, double y, double z) {
        if (!active) return color;
        int r = 0, g = 0, b = 0, sky = 0, canonical = 0;
        boolean found = false;
        int bx = (int) Math.floor(x - .5), by = (int) Math.floor(y - .5), bz = (int) Math.floor(z - .5);
        for (int dx = 0; dx < 2; dx++) for (int dy = 0; dy < 2; dy++) for (int dz = 0; dz < 2; dz++) {
            int xx = bx + dx, yy = by + dy, zz = bz + dz;
            View view = VIEWS.get(CoordinateUtils.getChunkKey(xx >> 4, zz >> 4));
            if (view == null) continue;
            found = true;
            int rgb = view.rgb.get(xx, yy, zz);
            canonical = Math.max(canonical, view.rgb.canonical(xx, yy, zz));
            r = Math.max(r, RgbLight.channel(rgb, 0));
            g = Math.max(g, RgbLight.channel(rgb, 1));
            b = Math.max(b, RgbLight.channel(rgb, 2));
            int s = (yy >> 4) - view.rgb.minSection;
            if (s >= 0 && s < view.sky.length && view.sky[s] != null) {
                int index = (xx & 15) | (zz & 15) << 4 | (yy & 15) << 8;
                sky = Math.max(sky, (view.sky[s][index >> 1] & 255) >> ((index & 1) * 4) & 15);
            }
        }
        if (!found) return color;
        return modulate(color, r, g, b, sky * skyScale, canonical);
    }
    public static int modulate(int color, int r, int g, int b, float sky) {
        return modulate(color, r, g, b, sky, Math.max(r, Math.max(g, b)));
    }
    public static int modulate(int color, int r, int g, int b, float sky, int canonical) {
        float ambient = .6f + sky, max = ambient + Math.max(canonical, Math.max(r, Math.max(g, b)));
        int red = Math.round((color & 255) * (ambient + r) / max);
        int green = Math.round((color >> 8 & 255) * (ambient + g) / max);
        int blue = Math.round((color >> 16 & 255) * (ambient + b) / max);
        return color & 0xFF000000 | red | green << 8 | blue << 16;
    }
}
