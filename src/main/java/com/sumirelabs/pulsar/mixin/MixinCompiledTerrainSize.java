/*
 * SPDX-License-Identifier: LGPL-3.0-only
 */
package com.sumirelabs.pulsar.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

import com.sumirelabs.pulsar.dynamic.TerrainMeshSize;

import net.minecraft.client.renderer.chunk.CompiledChunk;

@Mixin(CompiledChunk.class)
public abstract class MixinCompiledTerrainSize implements TerrainMeshSize {

    @Unique private boolean pulsar$largeLayer;

    public void pulsar$recordLayerSize(int vertices) {
        // About a MiB of terrain vertices, before sorting and copying for upload.
        if (vertices >= 32768)
            pulsar$largeLayer = true;
    }

    public boolean pulsar$hasLargeLayer() {
        return pulsar$largeLayer;
    }

}
