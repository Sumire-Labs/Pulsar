package com.sumirelabs.pulsar.compat;

import com.sumirelabs.pulsar.Pulsar;
import com.sumirelabs.pulsar.config.PulsarConfig;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * Celeritas (2.4.0-dev) clones chunk sections into a
 * {@code ClonedChunkSectionCache} for its async meshing workers, but its
 * rebuild-scheduling chain never calls {@code invalidate} — a cached clone
 * is returned as-is. A light-only change (no geometry) therefore rebuilds
 * the mesh from a stale clone captured BEFORE the BFS ran, and the wrong
 * lighting sticks (even through F3+A, since the periodic cleanup isn't
 * wired either). Upstream Sodium invalidates the clone cache inside
 * {@code RenderSectionManager.scheduleRebuild}.
 *
 * <p>This bridge reflectively invalidates the clone cache for the sections
 * Pulsar's client engine is about to mark for render update. Reflection
 * keeps Celeritas a soft dependency; on any failure the bridge disables
 * itself permanently (one warning).
 */
public final class CeleritasCloneCacheBridge {

    private static final int UNRESOLVED = 0;
    private static final int OK = 1;
    private static final int ABSENT = 2;

    private static volatile int state = UNRESOLVED;
    private static Method instanceNullable;
    private static Field renderSectionManagerField;
    private static Method getSectionCache;
    private static Method invalidate;

    private CeleritasCloneCacheBridge() {
    }

    /**
     * Invalidate every cloned section touching the given BLOCK range.
     */
    public static void invalidateBlockRange(final int x1, final int y1, final int z1,
                                            final int x2, final int y2, final int z2) {
        // Opt-in while the FPS impact during chunk streaming is being
        // measured; runtime-toggleable via Mod Options.
        if (!PulsarConfig.compat.celeritasCloneInvalidation) {
            return;
        }
        if (state == ABSENT) {
            return;
        }
        try {
            if (state == UNRESOLVED) {
                resolve();
                if (state != OK) {
                    return;
                }
            }
            final Object renderer = instanceNullable.invoke(null);
            if (renderer == null) {
                return;
            }
            final Object manager = renderSectionManagerField.get(renderer);
            if (manager == null) {
                return;
            }
            if (getSectionCache == null) {
                getSectionCache = manager.getClass().getMethod("getSectionCache");
            }
            final Object cache = getSectionCache.invoke(manager);
            if (cache == null) {
                return;
            }
            if (invalidate == null) {
                invalidate = cache.getClass().getMethod("invalidate", int.class, int.class, int.class);
            }
            for (int sy = y1 >> 4, maxSy = y2 >> 4; sy <= maxSy; ++sy) {
                for (int sz = z1 >> 4, maxSz = z2 >> 4; sz <= maxSz; ++sz) {
                    for (int sx = x1 >> 4, maxSx = x2 >> 4; sx <= maxSx; ++sx) {
                        invalidate.invoke(cache, sx, sy, sz);
                    }
                }
            }
        } catch (final Throwable t) {
            state = ABSENT;
            Pulsar.LOGGER.warn("Celeritas clone-cache bridge disabled (light-only render updates may lag)", t);
        }
    }

    private static synchronized void resolve() {
        if (state != UNRESOLVED) {
            return;
        }
        try {
            final Class<?> rendererClass =
                    Class.forName("org.taumc.celeritas.impl.render.terrain.CeleritasWorldRenderer");
            instanceNullable = rendererClass.getMethod("instanceNullable");

            Field field = null;
            for (Class<?> c = rendererClass; c != null && field == null; c = c.getSuperclass()) {
                try {
                    field = c.getDeclaredField("renderSectionManager");
                } catch (final NoSuchFieldException ignored) {
                }
            }
            if (field == null) {
                throw new NoSuchFieldException("renderSectionManager");
            }
            field.setAccessible(true);
            renderSectionManagerField = field;
            state = OK;
        } catch (final Throwable t) {
            state = ABSENT;
            // No Celeritas (or an incompatible version): vanilla render path
            // re-reads live light on rebuild, so nothing to do.
        }
    }
}
