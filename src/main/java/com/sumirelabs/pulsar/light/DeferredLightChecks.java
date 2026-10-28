package com.sumirelabs.pulsar.light;

import net.minecraft.util.math.BlockPos;
import java.util.LinkedHashMap;
import java.util.function.BiPredicate;

/** World-thread checks released after both scalar and RGB samples are published.
 * Captured owner identity prevents a replacement chunk from inheriting old work.
 */
final class DeferredLightChecks<C> {
    private record Check<C>(C owner, int lanes) {}
    private final LinkedHashMap<BlockPos, Check<C>> checks = new LinkedHashMap<>();
    private volatile boolean pending;

    void add(C owner, int x, int y, int z, int lanes) {
        if (owner == null || lanes == 0) return;
        checks.merge(new BlockPos(x, y, z), new Check<>(owner, lanes),
                (old, next) -> old.owner == owner ? new Check<>(owner, old.lanes | lanes) : next);
        pending = true;
    }
    void drain(BiPredicate<BlockPos, C> current, ContextualLightManager.LightChanges consumer) {
        if (checks.isEmpty()) return;
        var batch = new LinkedHashMap<>(checks);
        checks.clear(); // Reentrant requests remain pending for the next publication.
        try {
            batch.forEach((pos, check) -> {
                if (current.test(pos, check.owner)) consumer.changed(pos.getX(), pos.getY(), pos.getZ(), check.lanes);
            });
        } finally { pending = !checks.isEmpty(); }
    }
    boolean hasPending() { return pending; }
    void removeChunk(int x, int z) {
        checks.keySet().removeIf(pos -> pos.getX() >> 4 == x && pos.getZ() >> 4 == z);
        pending = !checks.isEmpty();
    }
    void clear() { checks.clear(); pending = false; }
}
