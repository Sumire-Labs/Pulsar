/*
 * SPDX-License-Identifier: LGPL-3.0-only
 */
package com.sumirelabs.pulsar.dynamic;

/** Size classification belongs to the compiled geometry, including when a RenderChunk is reused. */
public interface TerrainMeshSize {

    void pulsar$recordLayerSize(int vertices);

    boolean pulsar$hasLargeLayer();

}
