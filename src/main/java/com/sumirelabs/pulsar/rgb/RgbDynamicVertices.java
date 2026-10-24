package com.sumirelabs.pulsar.rgb;

import net.minecraft.client.renderer.vertex.VertexFormat;

/** Per-buffer generation and processed ranges; bulk quad writes are supported. */
public interface RgbDynamicVertices {
    long pulsar$rgbGeneration();
    long pulsar$rgbLayout(VertexFormat format);
    boolean pulsar$rgbProcessed(int vertex);
    void pulsar$markRgb(int start,int end);
}
