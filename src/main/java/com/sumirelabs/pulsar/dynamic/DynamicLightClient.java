package com.sumirelabs.pulsar.dynamic;

import net.minecraft.client.Minecraft;
import net.minecraft.world.World;
import java.util.*;

/** Installs on the client thread, exposes immutable fields to mesh workers. */
public final class DynamicLightClient {
    public static volatile boolean serverHasChannel;
    private static volatile World owner;
    private static Object connection;
    private static Object player;
    private static long session, sequence;
    private static boolean requested, acknowledged;
    private static int retryTicks;
    private static volatile boolean rgb, projected;
    private static volatile Map<Integer, DynamicLightField> fields = Map.of();
    private static volatile long generation;
    private DynamicLightClient() {}
    public static long generation() { return generation; }
    public static boolean rgbActive() { return rgb && !fields.isEmpty(); }
    public static boolean intersects(int chunkX, int sectionY, int chunkZ) {
        if (!rgbActive()) return false;
        int x = chunkX << 4, y = sectionY << 4, z = chunkZ << 4;
        for (var field : fields.values()) {
            int r = field.radius;
            if (x <= field.x + r && x + 15 >= field.x - r && y <= field.y + r && y + 15 >= field.y - r
                    && z <= field.z + r && z + 15 >= field.z - r) return true;
        }
        return false;
    }
    public static int shaderCell(int x, int y, int z) {
        int cell = sample(x, y, z);
        return (cell & 0xFFF) | brightness(cell) << 12;
    }
    public static int sample(World world, int x, int y, int z) {
        if (world == null || world != owner) return 0;
        int value = 0;
        for (var field : fields.values()) value = DynamicLightField.merge(value, field.get(x, y, z));
        return value;
    }
    public static int sample(int x, int y, int z) { return sample(owner, x, y, z); }
    public static int brightness(int cell) { return DynamicLightField.brightness(cell, projected); }
    public static int light(World world, int x, int y, int z) { return brightness(sample(world, x, y, z)); }
    public static void reset() {
        fields = Map.of(); owner = null; connection = null; player = null; requested = false; acknowledged = false; rgb = false;
        serverHasChannel = false; ++generation; ++session;
    }
    public static void tick() {
        Minecraft mc = Minecraft.getMinecraft();
        if (mc.world != owner || mc.getConnection() != connection || mc.player != player) {
            if (mc.world == owner) fields.values().forEach(DynamicLightClient::invalidate);
            fields = Map.of(); owner = mc.world; connection = mc.getConnection(); player = mc.player;
            requested = false; acknowledged = false; rgb = false; sequence = 0; retryTicks = 0; ++session; ++generation;
        }
        if (requested && !acknowledged && ++retryTicks >= 40) { requested = false; retryTicks = 0; }
        if (!requested && owner != null && mc.player != null && serverHasChannel) {
            requested = true; DynamicLightNetwork.request(session, owner.provider.getDimension());
        }
    }
    public static void receive(DynamicLightPacket packet, Object handler) {
        Minecraft mc = Minecraft.getMinecraft();
        mc.addScheduledTask(() -> apply(packet, handler, mc.world, mc.getConnection()));
    }
    static void apply(DynamicLightPacket packet, Object handler, World currentWorld, Object currentConnection) {
        if (handler != connection || currentConnection != handler || currentWorld == null || currentWorld != owner
                || packet.session != session || packet.dimension != owner.provider.getDimension() || packet.revision <= sequence) return;
        sequence = packet.revision;
        acknowledged = true;
        rgb = packet.rgb && com.sumirelabs.pulsar.core.RgbMixinSelection.rgbEnabled(); projected = packet.projected;
        if (packet.mode == DynamicLights.Mode.OFF) {
            fields.values().forEach(DynamicLightClient::invalidate); fields = Map.of(); ++generation; return;
        }
        if (packet.source == Integer.MIN_VALUE) return;
        var next = new HashMap<>(fields);
        DynamicLightField previous = next.remove(packet.source);
        if (packet.field != null) next.put(packet.source, packet.field);
        fields = Map.copyOf(next); ++generation;
        invalidate(previous); invalidate(packet.field);
    }
    private static void invalidate(DynamicLightField field) {
        if (field == null || owner == null) return;
        int r = field.radius + 1;
        owner.markBlockRangeForRenderUpdate(field.x - r, field.y - r, field.z - r, field.x + r, field.y + r, field.z + r);
    }
}
