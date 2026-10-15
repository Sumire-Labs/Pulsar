package com.sumirelabs.pulsar.compat;

import com.sumirelabs.pulsar.Pulsar;
import com.sumirelabs.pulsar.light.WorldLightManager;
import com.sumirelabs.pulsar.util.CoordinateUtils;
import com.sumirelabs.pulsar.world.PulsarWorld;
import net.minecraft.world.World;
import net.minecraft.world.WorldServer;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.concurrent.atomic.AtomicBoolean;

/** CQR writes section palettes/nibbles directly; its vanilla repair pass cannot update SWMR storage. */
public final class CqrBulkLighting {
    private CqrBulkLighting() {}
    private static final AtomicBoolean warned = new AtomicBoolean();
    private record MapAccess(Field chunks, Method values) {}
    private record Coordinates(Method x, Method z) {}
    private static final ClassValue<MapAccess> MAP = new ClassValue<>() {
        protected MapAccess computeValue(Class<?> type) {
            try {
                Field field = type.getDeclaredField("chunkInfoMapExtended");
                field.setAccessible(true);
                return new MapAccess(field, field.getType().getMethod("values"));
            } catch (ReflectiveOperationException e) { throw new IllegalStateException(e); }
        }
    };
    private static final ClassValue<Coordinates> COORDINATES = new ClassValue<>() {
        protected Coordinates computeValue(Class<?> type) {
            try { return new Coordinates(type.getMethod("getChunkX"), type.getMethod("getChunkZ")); }
            catch (ReflectiveOperationException e) { throw new IllegalStateException(e); }
        }
    };

    public static void relight(World world, Object dungeon) {
        if (world.isRemote || !(world instanceof WorldServer server)) return;
        final WorldLightManager manager = ((PulsarWorld) world).pulsar$getLightManager();
        if (manager == null) return;
        final LinkedHashSet<Long> chunks = new LinkedHashSet<>();
        try {
            MapAccess map = MAP.get(dungeon.getClass());
            for (Object info : (Collection<?>) map.values.invoke(map.chunks.get(dungeon))) {
                Coordinates coords = COORDINATES.get(info.getClass());
                chunks.add(CoordinateUtils.getChunkKey((Integer) coords.x.invoke(info), (Integer) coords.z.invoke(info)));
            }
        } catch (ReflectiveOperationException | IllegalStateException | ClassCastException e) {
            if (warned.compareAndSet(false, true)) Pulsar.LOGGER.warn("Cannot refresh CQR bulk lighting; unsupported CQR internals", e);
            return;
        }
        if (manager.contextualLight().isOwnerThread()) manager.forceRelightChunks(chunks);
        else server.addScheduledTask(() -> manager.forceRelightChunks(chunks));
    }
}
