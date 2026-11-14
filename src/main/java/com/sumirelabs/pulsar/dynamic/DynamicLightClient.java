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
    private static volatile Map<Integer, DynamicLightRenderSource> renderSources = Map.of();
    private static final ThreadLocal<float[]> SCRATCH = ThreadLocal.withInitial(() -> new float[4]);
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
    private record ReadView(World world, Map<Integer, DynamicLightField> fields, boolean rgb, boolean projected,
        Map<Integer, DynamicLightRenderSource> sources) {
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
                MESH_VIEW.set(new ReadView(world, snapshot, color, projection, renderSources));
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
        float[] light = SCRATCH.get();
        Arrays.fill(light, 0);
        var sources = view == null ? renderSources : view.sources;
        for (var entry : (view == null ? fields : view.fields).entrySet()) {
            var field = entry.getValue();
            var source = sources.get(entry.getKey());
            if (!field.hasVisualData() || source == null || source.field() != field) {
                value = DynamicLightField.merge(value, field.get(x, y, z));
            } else
                source.sample(x + .5, y + .5, z + .5, false, light);
        }
        int sampled = (int) light[2] | (int) light[1] << 4 | (int) light[0] << 8 | (int) light[3] << 12;
        return DynamicLightField.merge(value, sampled);
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
        Integer objectLight = com.sumirelabs.pulsar.rgb.DynamicObjectLight.blockLight(world);
        if (objectLight != null)
            return objectLight;
        return brightness(sample(world, x, y, z));
    }
    public static void reset() {
        DynamicLightRebuilds.clear();
        fields = Map.of();
        renderSources = Map.of();
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
            renderSources = Map.of();
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
        updateRenderSources();
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
            renderSources = Map.of();
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
        invalidate(previous, packet.field);
    }
    private static DynamicLightRenderSource source(int id, DynamicLightField field, float partial,
        boolean interpolate) {
        var entity = owner == null ? null : owner.getEntityByID(id);
        if (entity == null || entity.isDead)
            return DynamicLightRenderSource.at(field, field.x + .5, field.y + .5, field.z + .5);
        double x = entity.posX, y = entity.posY, z = entity.posZ;
        if (interpolate) {
            x = entity.lastTickPosX + (x - entity.lastTickPosX) * partial;
            y = entity.lastTickPosY + (y - entity.lastTickPosY) * partial;
            z = entity.lastTickPosZ + (z - entity.lastTickPosZ) * partial;
        }
        y += entity instanceof net.minecraft.entity.player.EntityPlayer ? entity.getEyeHeight() : entity.height * .5;
        return DynamicLightRenderSource.at(field, x, y, z);
    }
    private static void updateRenderSources() {
        if (owner == null || fields.isEmpty()) {
            renderSources = Map.of();
            return;
        }
        var next = new HashMap<Integer, DynamicLightRenderSource>();
        var invalidated = new ArrayList<DynamicLightRenderSource>();
        for (var entry : fields.entrySet()) {
            var current = source(entry.getKey(), entry.getValue(), 1, false);
            var previous = renderSources.get(entry.getKey());
            if (current.movedFrom(previous)) {
                if (previous != null)
                    invalidated.add(previous);
                invalidated.add(current);
            } else
                current = previous;
            next.put(entry.getKey(), current);
        }
        renderSources = Map.copyOf(next);
        if (!invalidated.isEmpty())
            ++generation;
        // Publish before scheduling workers: each new mesh must see the new positions.
        invalidated.forEach(DynamicLightClient::invalidate);
    }
    private static void invalidate(DynamicLightRenderSource source) {
        if (owner == null || source == null)
            return;
        int r = (int) Math.ceil(DynamicLightField.RANGE) + 1;
        int x = (int) Math.floor(source.x()), y = (int) Math.floor(source.y()), z = (int) Math.floor(source.z());
        mark(x - r, y - r, z - r, x + r, y + r, z + r);
    }
    /** Object light is sampled per draw; terrain uses immutable 20 Hz positions instead. */
    public static void sampleObject(int id, double x, double y, double z, float partial, float[] light) {
        Arrays.fill(light, 0);
        for (var entry : fields.entrySet())
            source(entry.getKey(), entry.getValue(), partial, true).sample(x, y, z, entry.getKey() == id, light);
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
