/*
 * SPDX-License-Identifier: LGPL-3.0-only
 */

package com.sumirelabs.pulsar.rgb;

import com.sumirelabs.pulsar.dynamic.DynamicLightClient;

import net.minecraft.client.particle.Particle;
import net.minecraft.client.renderer.BufferBuilder;

/** Tint FBP's own particle buffers while their geometry is appended, before its deferred batch flush. */
public final class RgbFbpLighting {

    private static final ThreadLocal<RgbDrawState> TINT = ThreadLocal.withInitial(RgbDrawState::new);

    private static final ThreadLocal<float[]> LIGHT = ThreadLocal.withInitial(() -> new float[4]);

    private RgbFbpLighting() {}

    public static void apply(BufferBuilder buffer, int start, long generation) {
        boolean dynamic = !DynamicLightClient.visualFields().isEmpty();
        if (RgbShaderRuntime.active() || !dynamic && !RgbDrawContext.available())
            return;
        RgbDynamicVertices tags = buffer instanceof RgbDynamicVertices t ? t : null;
        if (tags != null && generation != tags.pulsar$rgbGeneration())
            start = 0;
        int end = buffer.getVertexCount();
        if (start < 0 || start >= end || !buffer.getVertexFormat().hasColor())
            return;
        // Both FBP generations store camera-relative float XYZ first, including
        // any BufferBuilder translation in the legacy renderer.
        int stride = buffer.getVertexFormat().getSize();
        var bytes = buffer.getByteBuffer();
        double x = 0, y = 0, z = 0;
        boolean pending = false;
        for (int vertex = start; vertex < end; vertex++) {
            int at = vertex * stride;
            x += bytes.getFloat(at);
            y += bytes.getFloat(at + 4);
            z += bytes.getFloat(at + 8);
            pending |= tags == null || !tags.pulsar$rgbProcessed(vertex);
        }
        if (!pending)
            return;
        int count = end - start;
        x = x / count + Particle.interpPosX;
        y = y / count + Particle.interpPosY;
        z = z / count + Particle.interpPosZ;
        float[] light = null;
        if (dynamic) {
            light = LIGHT.get();
            DynamicLightClient.sampleSurface(x, y, z, 0, 0, 0, light);
            int blockUV = Math.round(16 * DynamicLightClient.surfaceBrightness(light));
            var format = buffer.getVertexFormat();
            if (blockUV > 0 && format.hasUvOffset(1)) {
                int uv = format.getUvOffsetById(1);
                for (int vertex = start; vertex < end; vertex++) {
                    int at = vertex * stride + uv;
                    if (Short.toUnsignedInt(bytes.getShort(at)) < blockUV)
                        bytes.putShort(at, (short) blockUV);
                }
            }
        }
        if (tags == null || !RgbDrawContext.available())
            return;
        int tint = RgbClient.tintAbgr(0xffffffff, x, y, z, light);
        RgbDrawState state = TINT.get();
        int token = state.push(buffer, tint, false, false);
        state.lightmap = true;
        try {
            // Uses each vertex's lightmap to preserve full-bright faces, and
            // tags the range so ordinary particle/uploader hooks cannot tint it twice.
            RgbVertexTint.apply(buffer, tags, start, generation, state);
        } finally {
            state.pop(token);
        }
    }

}
