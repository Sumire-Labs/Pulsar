package com.sumirelabs.pulsar.dynamic;

/** Immutable CPU render position paired with the server's occlusion/filter field. */
public record DynamicLightRenderSource(DynamicLightField field, double x, double y, double z) {

    public static DynamicLightRenderSource at(DynamicLightField field, double x, double y, double z) {
        double cx = field.x + .5, cy = field.y + .5, cz = field.z + .5;
        double dx = x - cx, dy = y - cy, dz = z - cz;
        // Never drag an old mask through a teleport or beyond its captured geometry.
        if (!Double.isFinite(dx + dy + dz)
            || dx * dx + dy * dy + dz * dz > DynamicLightField.MAX_RENDER_OFFSET * DynamicLightField.MAX_RENDER_OFFSET)
            return new DynamicLightRenderSource(field, cx, cy, cz);
        return new DynamicLightRenderSource(field, x, y, z);
    }
    public boolean movedFrom(DynamicLightRenderSource previous) {
        return previous == null || previous.field != field || Math.abs(x - previous.x) > .1
            || Math.abs(y - previous.y) > .1 || Math.abs(z - previous.z) > .1;
    }
    public void sample(double px, double py, double pz, boolean own, float[] light) {
        DynamicLightVisualSample.merge(field, x, y, z, px, py, pz, own, light);
    }

}
