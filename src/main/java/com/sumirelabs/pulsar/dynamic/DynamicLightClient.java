package com.sumirelabs.pulsar.dynamic;

import java.util.*;

import net.minecraft.client.Minecraft;
import net.minecraft.world.World;

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
    private static volatile DynamicLights.Mode mode = DynamicLights.Mode.OFF;
    public static DynamicLights.Mode mode() {
        return mode;
    }
    public static Map<Integer, DynamicLightField> visualFields() {
        return fields;
    }
    public static boolean projected() {
        return projected;
    }
    public static boolean colored() {
        return rgb;
    }
    public static boolean inMesh() {
        return MESH_VIEW.get() != null;
    }
    private record ReadView(World world, Map<Integer, DynamicLightField> fields, boolean rgb, boolean projected) {
    }
    private static final ThreadLocal<ReadView> MESH_VIEW = new ThreadLocal<>();
    /** Scalar brightness and RGB vertex tint must use the same publication for an entire mesh. */
    public static final class MeshScope implements AutoCloseable {

        private final ReadView previous;
        private MeshScope() {
            previous = MESH_VIEW.get();
            if (previous == null) {
                World world = owner;
                var snapshot = fields;
                boolean color = rgb, projection = projected;
                // A dimension transition must not attach a new world's fields to an old mesh.
                if (owner != world) {
                    snapshot = Map.of();
                    color = false;
                }
                MESH_VIEW.set(new ReadView(world, snapshot, color, projection));
            }
        }
        @Override
        public void close() {
            if (previous == null)
                MESH_VIEW.remove();
            else
                MESH_VIEW.set(previous);
        }

    }
    public static MeshScope beginMesh() {
        return new MeshScope();
    }
    private DynamicLightClient() {}
    public static long generation() {
        return generation;
    }
    public static boolean rgbActive() {
        ReadView view = MESH_VIEW.get();
        return view == null ? rgb && !fields.isEmpty() : view.rgb && !view.fields.isEmpty();
    }
    public static boolean intersects(int chunkX, int sectionY, int chunkZ) {
        if (!rgbActive())
            return false;
        int x = chunkX << 4, y = sectionY << 4, z = chunkZ << 4;
        for (var field : fields.values()) {
            int r = field.radius;
            if (x <= field.x + r && x + 15 >= field.x - r && y <= field.y + r && y + 15 >= field.y - r
                && z <= field.z + r && z + 15 >= field.z - r)
                return true;
        }
        return false;
    }
    public static int shaderCell(int x, int y, int z) {
        int cell = sample(x, y, z);
        return (cell & 0xFFF) | brightness(cell) << 12;
    }
    public static int sample(World world, int x, int y, int z) {
        ReadView view = MESH_VIEW.get();
        if (world == null || world != (view == null ? owner : view.world))
            return 0;
        int value = 0;
        for (var field : (view == null ? fields : view.fields).values())
            value = DynamicLightField.merge(value, field.get(x, y, z));
        return value;
    }
    public static int sample(int x, int y, int z) {
        ReadView view = MESH_VIEW.get();
        return sample(view == null ? owner : view.world, x, y, z);
    }
    public static int brightness(int cell) {
        ReadView view = MESH_VIEW.get();
        return DynamicLightField.brightness(cell, view == null ? projected : view.projected);
    }
    public static int light(World world, int x, int y, int z) {
        if (inMesh() && com.sumirelabs.pulsar.rgb.DynamicTerrainRuntime.enabled())
            return 0;
        Integer objectLight = com.sumirelabs.pulsar.rgb.DynamicObjectLight.blockLight(world);
        if (objectLight != null)
            return objectLight;
        return brightness(sample(world, x, y, z));
    }
    public static void reset() {
        DynamicLightRebuilds.clear();
        fields = Map.of();
        owner = null;
        connection = null;
        player = null;
        requested = false;
        acknowledged = false;
        rgb = false;
        serverHasChannel = false;
        ++generation;
        ++session;
        mode = DynamicLights.Mode.OFF;
    }
    public static void tick() {
        Minecraft mc = Minecraft.getMinecraft();
        if (mc.world != owner || mc.getConnection() != connection || mc.player != player) {
            DynamicLightRebuilds.clear();
            if (mc.world == owner)
                fields.values().forEach(DynamicLightClient::invalidate);
            fields = Map.of();
            owner = mc.world;
            connection = mc.getConnection();
            player = mc.player;
            requested = false;
            acknowledged = false;
            rgb = false;
            sequence = 0;
            retryTicks = 0;
            ++session;
            ++generation;
            mode = DynamicLights.Mode.OFF;
        }
        if (requested && !acknowledged && ++retryTicks >= 40) {
            requested = false;
            retryTicks = 0;
        }
        if (!requested && owner != null && mc.player != null && serverHasChannel) {
            requested = true;
            DynamicLightNetwork.request(session, owner.provider.getDimension());
        }
        DynamicLightRebuilds.flush(owner);
    }
    public static void receive(DynamicLightPacket packet, Object handler) {
        Minecraft mc = Minecraft.getMinecraft();
        mc.addScheduledTask(() -> apply(packet, handler, mc.world, mc.getConnection()));
    }
    static void apply(DynamicLightPacket packet, Object handler, World currentWorld, Object currentConnection) {
        if (handler != connection || currentConnection != handler || currentWorld == null || currentWorld != owner
            || packet.session != session || packet.dimension != owner.provider.getDimension()
            || packet.revision <= sequence)
            return;
        sequence = packet.revision;
        acknowledged = true;
        mode = packet.mode;
        rgb = packet.rgb && com.sumirelabs.pulsar.core.RgbMixinSelection.rgbEnabled();
        projected = packet.projected;
        if (packet.mode == DynamicLights.Mode.OFF) {
            fields.values().forEach(DynamicLightClient::invalidate);
            fields = Map.of();
            ++generation;
            return;
        }
        if (packet.source == Integer.MIN_VALUE)
            return;
        var next = new HashMap<>(fields);
        DynamicLightField previous = next.remove(packet.source);
        if (packet.field != null)
            next.put(packet.source, packet.field);
        fields = Map.copyOf(next);
        ++generation;
        if (!com.sumirelabs.pulsar.rgb.DynamicTerrainRuntime.enabled())
            invalidate(previous, packet.field);
    }
    private static void invalidate(DynamicLightField before, DynamicLightField after) {
        if (owner != null && before != null && after != null
            && Math.abs((long) before.x - after.x) <= meshRadius(before) + meshRadius(after)
            && Math.abs((long) before.y - after.y) <= meshRadius(before) + meshRadius(after)
            && Math.abs((long) before.z - after.z) <= meshRadius(before) + meshRadius(after)) {
            // Normal movement shares most of the affected sections. Issue one rebuild request,
            // rather than immediately invalidating the same section a second time.
            int a = meshRadius(before), b = meshRadius(after);
            mark(Math.min(before.x - a, after.x - b), Math.min(before.y - a, after.y - b),
                Math.min(before.z - a, after.z - b),
                Math.max(before.x + a, after.x + b), Math.max(before.y + a, after.y + b),
                Math.max(before.z + a, after.z + b));
        } else {
            // Do not rebuild the empty gap between distant teleports.
            invalidate(before);
            invalidate(after);
        }
    }
    private static void invalidate(DynamicLightField field) {
        if (field == null || owner == null)
            return;
        int r = meshRadius(field);
        mark(field.x - r, field.y - r, field.z - r, field.x + r, field.y + r, field.z + r);
    }
    private static int meshRadius(DynamicLightField field) {
        return Math.min(field.radius, (int) Math.ceil(DynamicLightField.RANGE)) + 1;
    }
    private static void mark(int x, int y, int z, int xx, int yy, int zz) {
        if (!DynamicLightRebuilds.invalidate(owner, x, y, z, xx, yy, zz))
            owner.markBlockRangeForRenderUpdate(x, y, z, xx, yy, zz);
    }

}
