package com.sumirelabs.pulsar.dynamic;

import java.util.ArrayDeque;

/** Bounded render-only snapshots for entities whose network interpolation trails the server. */
public final class DynamicLightHistory {
    private record Sample(DynamicLightField field, long tick) {}
    private final ArrayDeque<Sample> samples = new ArrayDeque<>();
    private DynamicLightField latest;

    public DynamicLightField select(DynamicLightField next, long tick, double x, double y, double z) {
        if (next != latest) {
            // Never carry old colors, teleport trails or superseded stationary geometry forward.
            if (latest != null && (next.emission() != latest.emission()
                    || next.x == latest.x && next.y == latest.y && next.z == latest.z
                    || distance(next, latest.x + .5, latest.y + .5, latest.z + .5) > 64)) samples.clear();
            latest = next;
            samples.addFirst(new Sample(next, tick));
        }
        while (samples.size() > 4 || !samples.isEmpty() && tick - samples.getLast().tick > 6) samples.removeLast();
        double limit = DynamicLightField.MAX_RENDER_OFFSET * DynamicLightField.MAX_RENDER_OFFSET;
        // Always prefer current occlusion when it covers the interpolated position.
        if (distance(next, x, y, z) <= limit) return next;
        for (Sample sample : samples) if (distance(sample.field, x, y, z) <= limit) return sample.field;
        return next; // Keep the existing safe clamp when no recent snapshot covers the position.
    }
    private static double distance(DynamicLightField f, double x, double y, double z) {
        double dx=x-f.x-.5,dy=y-f.y-.5,dz=z-f.z-.5;
        return dx*dx+dy*dy+dz*dz;
    }
}
