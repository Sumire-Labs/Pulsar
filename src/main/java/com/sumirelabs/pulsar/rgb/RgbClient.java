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
    private record View(Chunk chunk, RgbSnapshot rgb, RgbSnapshot skyRgb, byte[][] sky) {}
    private static final ConcurrentHashMap<Long, View> VIEWS = new ConcurrentHashMap<>();
    private static World owner;
    public static volatile boolean serverHasRgbChannel;
    private static boolean requested;
    private static Object requester;
    private static volatile boolean active, skyActive;
    public static volatile boolean vanillaRendered, celeritasRendered;
    private static volatile float skyScale = 1;
    private static final ConcurrentHashMap<Long, Chunk> REFRESH = new ConcurrentHashMap<>();
    private RgbClient() {}
    public static boolean active() { return active; }
    public static boolean skyActive() { return skyActive; }
    public static void unload(World world) {
        if (owner == world) { VIEWS.clear(); REFRESH.clear(); owner = null; active = false; skyActive = false; requested = false; requester = null; }
    }
    public static void tick() {
        World world = Minecraft.getMinecraft().world;
        if (owner != world) { VIEWS.clear(); REFRESH.clear(); owner = world; active = false; skyActive = false; requested = false; }
        if (world == null) return;
        if (requester != Minecraft.getMinecraft().player) {
            requester = Minecraft.getMinecraft().player;
            requested = false;
        }
        if (!requested && serverHasRgbChannel && Minecraft.getMinecraft().player != null && RgbDrawContext.supported()) {
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
            RgbSnapshot previous = packet.sky ? rgb.pulsar$getRgbSkySnapshot() : rgb.pulsar$getRgbSnapshot();
            if (previous != null && previous.revision > packet.snapshot.revision) return;
            var height = WorldUtil.getHeightContext(mc.world);
            if (packet.sky && !mc.world.provider.hasSkyLight()) return;
            if (packet.snapshot.minSection != height.getMinLightSection() || packet.snapshot.sectionCount() != height.getTotalLightSections()) return;
            if (packet.sky) rgb.pulsar$setRgbSkySnapshot(packet.snapshot);
            else rgb.pulsar$setRgbSnapshot(packet.snapshot);
            SWMRNibbleArray[] block = new SWMRNibbleArray[height.getTotalLightSections()];
            var sections = chunk.getBlockStorageArray();
            for (int i = 0; i < block.length; i++) {
                int slot = height.getStorageIndex(height.getMinLightSection() + i);
                if (slot >= 0 && slot < sections.length && sections[slot] != null) {
                    byte[] vanilla = (packet.sky ? sections[slot].getSkyLight() : sections[slot].getBlockLight()).getData();
                    packet.snapshot.copyCanonicalTo(i, vanilla);
                    block[i] = new SWMRNibbleArray(vanilla);
                } else if (packet.snapshot.canonicalFull(i)) {
                    SWMRNibbleArray nibble = new SWMRNibbleArray(); nibble.setFull(); nibble.updateVisible();
                    block[i] = nibble;
                } else block[i] = new SWMRNibbleArray(packet.snapshot.canonicalSection(i));
            }
            if (packet.sky) ((PulsarChunk) chunk).pulsar$setSkyNibbles(block);
            else ((PulsarChunk) chunk).pulsar$setBlockNibbles(block);
            refresh(chunk);
            if (packet.sky) skyActive = true; else active = true;
            int min = WorldUtil.getHeightContext(mc.world).getMinBlockY();
            int max = WorldUtil.getHeightContext(mc.world).getMaxBlockY();
            mc.world.markBlockRangeForRenderUpdate((packet.x << 4) - 1, min, (packet.z << 4) - 1,
                    (packet.x << 4) + 16, max, (packet.z << 4) + 16);
        });
    }
    public static void chunkRead(Chunk chunk) {
        if (((RgbChunk) chunk).pulsar$getRgbSnapshot() != null || ((RgbChunk) chunk).pulsar$getRgbSkySnapshot() != null)
            REFRESH.put(CoordinateUtils.getChunkKey(chunk.x, chunk.z), chunk);
    }
    public static void refresh(Chunk chunk) {
        if (!chunk.getWorld().isRemote) return;
        RgbSnapshot rgb = ((RgbChunk) chunk).pulsar$getRgbSnapshot();
        RgbSnapshot skyRgb = ((RgbChunk) chunk).pulsar$getRgbSkySnapshot();
        if (rgb == null && skyRgb == null) return;
        RgbSnapshot layout = rgb == null ? skyRgb : rgb;
        SWMRNibbleArray[] sky = ((PulsarChunk) chunk).pulsar$getSkyNibbles();
        byte[][] copy = new byte[layout.sectionCount()][];
        if (skyRgb == null && chunk.getWorld().provider.hasSkyLight() && sky != null) for (int i = 0; i < Math.min(copy.length, sky.length); i++) {
            SWMRNibbleArray nibble = sky[i];
            if (nibble != null && !nibble.isNullNibbleVisible()) {
                byte[] bytes = nibble.getVisibleData();
                if (bytes != null) copy[i] = bytes.clone();
                continue;
            }
            // Match Pulsar's extrusion for absent sections, including shadows from above.
            byte[] bytes = new byte[2048];
            int y = (layout.minSection + i) << 4;
            var height = WorldUtil.getHeightContext(chunk.getWorld());
            for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++) {
                int index = x | z << 4;
                int value = com.sumirelabs.pulsar.light.ChunkLightHelper.getSkyLight(height, sky, x, y, z);
                bytes[index >> 1] |= (byte) (value << ((index & 1) * 4));
            }
            for (int row = 1; row < 16; row++) System.arraycopy(bytes, 0, bytes, row * 128, 128);
            copy[i] = bytes;
        }
        VIEWS.put(CoordinateUtils.getChunkKey(chunk.x, chunk.z), new View(chunk, rgb, skyRgb, copy));
    }
    /** Prototype vertex modulation retains vanilla AO and the scalar lightmap.
     * Sky and a small ambient term desaturate color; it is not shader-level RGB lightmaps.
     */
    public static int tintAbgr(int color, double x, double y, double z) {
        if (!active && !skyActive) return color;
        int r = 0, g = 0, b = 0, sky = 0, canonical = 0, sr = 0, sg = 0, sb = 0;
        boolean found = false;
        int bx = (int) Math.floor(x - .5), by = (int) Math.floor(y - .5), bz = (int) Math.floor(z - .5);
        long previousKey = 0;
        View previousView = null;
        boolean havePrevious = false;
        // Both Y samples share a chunk; adjacent columns normally share it too.
        for (int dx = 0; dx < 2; dx++) for (int dz = 0; dz < 2; dz++) {
            int xx = bx + dx, zz = bz + dz;
            long key = CoordinateUtils.getChunkKey(xx >> 4, zz >> 4);
            View view;
            if (havePrevious && key == previousKey) view = previousView;
            else {
                previousKey = key;
                previousView = view = VIEWS.get(key);
                havePrevious = true;
            }
            if (view == null) continue;
            found = true;
            for (int dy = 0; dy < 2; dy++) {
                int yy = by + dy;
                int rgb = view.rgb == null ? 0 : view.rgb.get(xx, yy, zz);
                if (view.rgb != null) canonical = Math.max(canonical, view.rgb.canonical(xx, yy, zz));
                r = Math.max(r, RgbLight.channel(rgb, 0));
                g = Math.max(g, RgbLight.channel(rgb, 1));
                b = Math.max(b, RgbLight.channel(rgb, 2));
                int s = (yy >> 4) - (view.rgb == null ? view.skyRgb.minSection : view.rgb.minSection);
                int skyColor;
                if (view.skyRgb != null) {
                    skyColor = view.skyRgb.get(xx, yy, zz);
                    sky = Math.max(sky, view.skyRgb.canonical(xx, yy, zz));
                } else {
                    int value = 0;
                    if (s >= 0 && s < view.sky.length && view.sky[s] != null) {
                        int index = (xx & 15) | (zz & 15) << 4 | (yy & 15) << 8;
                        value = (view.sky[s][index >> 1] & 255) >> ((index & 1) * 4) & 15;
                    }
                    skyColor = value * 0x111;
                    sky = Math.max(sky, value);
                }
                sr = Math.max(sr, RgbLight.channel(skyColor, 0));
                sg = Math.max(sg, RgbLight.channel(skyColor, 1));
                sb = Math.max(sb, RgbLight.channel(skyColor, 2));
            }
        }
        if (!found) return color;
        return modulateSky(color, r, g, b, canonical, sr, sg, sb, sky, skyScale);
    }
    public static int modulate(int color, int r, int g, int b, float sky) {
        return modulate(color, r, g, b, sky, Math.max(r, Math.max(g, b)));
    }
    public static int modulateSky(int color, int r, int g, int b, int canonical,
                                  int sr, int sg, int sb, int skyCanonical, float skyScale) {
        float red = r + sr * skyScale, green = g + sg * skyScale, blue = b + sb * skyScale;
        float max = .6f + Math.max(canonical + skyCanonical * skyScale, Math.max(red, Math.max(green, blue)));
        int rr = Math.round((color & 255) * (.6f + red) / max);
        int gg = Math.round((color >> 8 & 255) * (.6f + green) / max);
        int bb = Math.round((color >> 16 & 255) * (.6f + blue) / max);
        return color & 0xFF000000 | rr | gg << 8 | bb << 16;
    }
    public static int modulate(int color, int r, int g, int b, float sky, int canonical) {
        float ambient = .6f + sky, max = ambient + Math.max(canonical, Math.max(r, Math.max(g, b)));
        int red = Math.round((color & 255) * (ambient + r) / max);
        int green = Math.round((color >> 8 & 255) * (ambient + g) / max);
        int blue = Math.round((color >> 16 & 255) * (ambient + b) / max);
        return color & 0xFF000000 | red | green << 8 | blue << 16;
    }
}
