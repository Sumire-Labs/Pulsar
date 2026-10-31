package com.sumirelabs.pulsar.rgb;

import net.minecraft.client.particle.Particle;
import net.minecraft.client.renderer.BufferBuilder;

/** Tint FBP's own particle buffers while their geometry is appended, before its deferred batch flush. */
public final class RgbFbpLighting {
    private static final ThreadLocal<RgbDrawState> TINT = ThreadLocal.withInitial(RgbDrawState::new);

    private RgbFbpLighting() {}

    public static void apply(BufferBuilder buffer, int start, long generation) {
        if (RgbShaderRuntime.active() || !RgbDrawContext.available()) return;
        RgbDynamicVertices tags = (RgbDynamicVertices) buffer;
        if (generation != tags.pulsar$rgbGeneration()) start = 0;
        int end = buffer.getVertexCount();
        if (start < 0 || start >= end || !buffer.getVertexFormat().hasColor()) return;
        // Both FBP generations store camera-relative float XYZ first, including
        // any BufferBuilder translation in the legacy renderer.
        int stride = buffer.getVertexFormat().getSize();
        var bytes = buffer.getByteBuffer();
        double x = 0, y = 0, z = 0;
        boolean pending = false;
        for (int vertex = start; vertex < end; vertex++) {
            int at = vertex * stride;
            x += bytes.getFloat(at); y += bytes.getFloat(at + 4); z += bytes.getFloat(at + 8);
            pending |= !tags.pulsar$rgbProcessed(vertex);
        }
        if (!pending) return;
        int count = end - start;
        int tint = RgbClient.tintAbgr(0xffffffff, x / count + Particle.interpPosX,
                y / count + Particle.interpPosY, z / count + Particle.interpPosZ);
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
