package com.sumirelabs.pulsar.dynamic;
import java.lang.reflect.Method;

import com.sumirelabs.pulsar.Pulsar;

import net.minecraft.client.Minecraft;
import net.minecraft.world.World;

/** Optional Celeritas scheduling bridge; vanilla/unknown renderers keep their normal invalidation path. */
final class DynamicLightRebuilds {

    private static final boolean PRESENT = DynamicLightRebuilds.class.getClassLoader()
        .getResource("org/taumc/celeritas/impl/render/terrain/CeleritasWorldRenderer.class") != null;
    private static final DynamicLightRebuildQueue QUEUE = new DynamicLightRebuildQueue();
    private static volatile World owner;
    private static boolean failed;
    private static Method instance, manager, section, pending, building, schedule;
    private DynamicLightRebuilds() {}
    static void clear() {
        QUEUE.clear();
        owner = null;
    }
    private static Object renderer() throws ReflectiveOperationException {
        if (instance == null) {
            var renderer = Class.forName("org.taumc.celeritas.impl.render.terrain.CeleritasWorldRenderer");
            var sections = Class.forName("org.embeddedt.embeddium.impl.render.chunk.RenderSectionManager");
            var entry = Class.forName("org.embeddedt.embeddium.impl.render.chunk.RenderSection");
            instance = renderer.getMethod("instanceNullable");
            manager = renderer.getMethod("getRenderSectionManager");
            section = sections.getDeclaredMethod("getRenderSection", int.class, int.class, int.class);
            section.setAccessible(true);
            pending = entry.getMethod("getPendingUpdate");
            building = entry.getMethod("getBuildCancellationToken");
            schedule = renderer.getMethod("scheduleRebuildForChunk", int.class, int.class, int.class, boolean.class);
        }
        return instance.invoke(null);
    }
    static boolean invalidate(World world, int x, int y, int z, int xx, int yy, int zz) {
        if (!PRESENT || failed)
            return false;
        var mc = Minecraft.getMinecraft();
        if (mc == null || world != mc.world)
            return false;
        try {
            Object renderer = renderer();
            if (renderer == null || manager.invoke(renderer) == null)
                return false;
            if (owner != world) {
                QUEUE.clear();
                owner = world;
            }
            QUEUE.add(x >> 4, y >> 4, z >> 4, xx >> 4, yy >> 4, zz >> 4);
            flush(world);
            return true;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError error) {
            fail(error);
            return false;
        }
    }
    static void flush(World world) {
        if (QUEUE.isEmpty())
            return;
        if (world == null || world != owner) {
            clear();
            return;
        }
        try {
            Object renderer = renderer(), sections = renderer == null ? null : manager.invoke(renderer);
            if (sections == null) {
                clear();
                return;
            }
            QUEUE.drain(64, key -> {
                try {
                    Object entry = section.invoke(sections, key.x(), key.y(), key.z());
                    return entry != null && (pending.invoke(entry) != null || building.invoke(entry) != null);
                } catch (ReflectiveOperationException e) {
                    throw new IllegalStateException(e);
                }
            }, key -> {
                try {
                    schedule.invoke(renderer, key.x(), key.y(), key.z(), false);
                } catch (ReflectiveOperationException e) {
                    throw new IllegalStateException(e);
                }
            });
        } catch (ReflectiveOperationException | RuntimeException | LinkageError error) {
            fail(error);
        }
    }
    private static void fail(Throwable error) {
        failed = true;
        Pulsar.LOGGER.warn("Dynamic light rebuild coalescing unavailable; using normal render updates", error);
        World world = owner;
        if (world != null)
            QUEUE.drain(Integer.MAX_VALUE, key -> false, key -> world.markBlockRangeForRenderUpdate(key.x() << 4,
                key.y() << 4, key.z() << 4, (key.x() << 4) + 15, (key.y() << 4) + 15, (key.z() << 4) + 15));
        clear();
    }

}
