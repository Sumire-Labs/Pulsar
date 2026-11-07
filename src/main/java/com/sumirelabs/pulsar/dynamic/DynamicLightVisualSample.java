package com.sumirelabs.pulsar.dynamic;

/** Continuous object-light sampling of the same immutable loss mask used by terrain. RGBA = RGB + scalar. */
public final class DynamicLightVisualSample {

    private DynamicLightVisualSample() {}
    public static void merge(DynamicLightField field, double sx, double sy, double sz, double x, double y, double z,
        boolean ownSource, float[] light) {
        int emission = field.emission();
        if (ownSource) {
            // An emitter illuminates itself at its source strength, including near opaque floors.
            // This only applies to the matching source entity; it never bypasses filters for another object.
            for (int channel = 0; channel < 4; channel++) {
                int lane = channel == 0 ? 2 : channel == 2 ? 0 : channel;
                light[channel] = Math.max(light[channel], emission >>> (lane * 4) & 15);
            }
            return;
        }
        double dx = x - sx, dy = y - sy, dz = z - sz, distance = Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (distance >= DynamicLightField.RANGE || !field.hasVisualData())
            return;
        int bx = (int) Math.floor(x - .5), by = (int) Math.floor(y - .5), bz = (int) Math.floor(z - .5);
        double fx = x - .5 - bx, fy = y - .5 - by, fz = z - .5 - bz;
        for (int channel = 0; channel < 4; channel++) {
            int lane = channel == 0 ? 2 : channel == 2 ? 0 : channel;
            double value = 0;
            for (int yy = 0; yy < 2; yy++)
                for (int zz = 0; zz < 2; zz++)
                    for (int xx = 0; xx < 2; xx++) {
                        int px = bx + xx - field.x + field.radius, py = by + yy - field.y + field.radius,
                            pz = bz + zz - field.z + field.radius;
                        if (px < 0 || py < 0 || pz < 0 || px >= field.side || py >= field.side || pz >= field.side)
                            continue;
                        int loss = field.visualLoss(px + field.side * (pz + field.side * py), lane);
                        if (loss == DynamicLightField.BLOCKED)
                            continue;
                        double weight = (xx == 0 ? 1 - fx : fx) * (yy == 0 ? 1 - fy : fy) * (zz == 0 ? 1 - fz : fz);
                        value += Math.max(0, 1 - (distance + loss / 256.0) / DynamicLightField.RANGE) * weight;
                    }
            light[channel] = Math.max(light[channel], (float) (value * (emission >>> (lane * 4) & 15)));
        }
    }

}
