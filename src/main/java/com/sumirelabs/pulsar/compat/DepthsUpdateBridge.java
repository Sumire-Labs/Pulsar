package com.sumirelabs.pulsar.compat;

import com.sumirelabs.pulsar.Pulsar;
import com.sumirelabs.pulsar.util.WorldHeightContext;
import net.minecraft.world.World;
import net.minecraftforge.fml.common.Loader;

import java.lang.reflect.Method;
import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * Soft integration with Depths Update's per-dimension height API.
 *
 * <p>Depths Update extends {@code Chunk.storageArrays}, but deliberately keeps
 * vanilla sections at physical indices {@code 0..15}; negative sections are
 * appended in a different order. Pulsar therefore cannot infer a world
 * section from an array index. This bridge asks Depths Update for both the
 * configured bounds and its authoritative {@code toStorageIndex} mapping,
 * then snapshots those values into a reflection-free
 * {@link WorldHeightContext} used by the engines.
 *
 * <p>All Depths Update types are accessed reflectively so the mod remains an
 * optional dependency. Contexts are cached weakly per world; height changes
 * require rebuilding chunk storage in Depths Update itself, so a context is
 * immutable for a world's lifetime.
 */
public final class DepthsUpdateBridge {

    public static final boolean LOADED = Loader.isModLoaded("depthsupdate");

    private static final int UNRESOLVED = 0;
    private static final int OK = 1;
    private static final int FAILED = 2;

    private static volatile int state = UNRESOLVED;
    private static Method getHeightInfo;
    private static Method isExtended;
    private static Method minY;
    private static Method maxY;
    private static DepthsUpdateStorageIndexInvoker storageIndexInvoker;

    private static final Map<World, WorldHeightContext> CONTEXTS =
            Collections.synchronizedMap(new WeakHashMap<>());

    private DepthsUpdateBridge() {
    }

    public static WorldHeightContext getHeightContext(final World world) {
        if (!LOADED || world == null || state == FAILED) {
            return WorldHeightContext.VANILLA;
        }

        final WorldHeightContext cached = CONTEXTS.get(world);
        if (cached != null) {
            return cached;
        }

        if (state == UNRESOLVED) {
            try {
                resolve();
            } catch (final Throwable t) {
                state = FAILED;
                Pulsar.LOGGER.warn(
                        "Depths Update height integration disabled; extended sections will not be lit", t);
                return WorldHeightContext.VANILLA;
            }
        }
        if (state != OK) {
            return WorldHeightContext.VANILLA;
        }

        try {
            final Object heightInfo = getHeightInfo.invoke(null, world);
            if (heightInfo == null || !(Boolean) isExtended.invoke(heightInfo)) {
                CONTEXTS.put(world, WorldHeightContext.VANILLA);
                return WorldHeightContext.VANILLA;
            }

            final int minimumY = (Integer) minY.invoke(heightInfo);
            final int maximumY = (Integer) maxY.invoke(heightInfo);
            final int minimumSection = minimumY >> 4;
            final int maximumSection = (maximumY - 1) >> 4;
            final int[] mapping = new int[maximumSection - minimumSection + 1];

            for (int sectionY = minimumSection; sectionY <= maximumSection; ++sectionY) {
                mapping[sectionY - minimumSection] =
                        storageIndexInvoker.invoke(heightInfo, world, sectionY << 4);
            }

            final WorldHeightContext context =
                    WorldHeightContext.mapped(minimumSection, maximumSection, mapping);
            CONTEXTS.put(world, context);
            Pulsar.LOGGER.info(
                    "Depths Update height integration active for dimension {}: sections {}..{} using {}",
                    world.provider.getDimension(), minimumSection, maximumSection,
                    storageIndexInvoker.getDescription());
            return context;
        } catch (final Throwable t) {
            CONTEXTS.put(world, WorldHeightContext.VANILLA);
            Pulsar.LOGGER.warn(
                    "Failed to read Depths Update height context for dimension {}; extended sections will not be lit",
                    world.provider.getDimension(), t);
            return WorldHeightContext.VANILLA;
        }
    }

    private static synchronized void resolve() throws ReflectiveOperationException {
        if (state != UNRESOLVED) {
            return;
        }

        final Class<?> apiClass = Class.forName("sayys.depthsupdate.api.DepthsUpdateAPI");
        final Class<?> heightInfoClass = Class.forName("sayys.depthsupdate.api.HeightInfo");
        final Class<?> heightManagerClass = Class.forName("sayys.depthsupdate.core.HeightManager");
        final Class<?> heightContextClass = Class.forName("sayys.depthsupdate.core.HeightContext");

        getHeightInfo = apiClass.getMethod("getHeightInfo", World.class);
        isExtended = heightInfoClass.getMethod("isExtended");
        minY = heightInfoClass.getMethod("minY");
        maxY = heightInfoClass.getMethod("maxY");
        storageIndexInvoker = DepthsUpdateStorageIndexInvoker.resolve(
                heightManagerClass, heightContextClass, World.class);
        state = OK;
    }
}
