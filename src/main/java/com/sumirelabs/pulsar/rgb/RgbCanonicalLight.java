package com.sumirelabs.pulsar.rgb;

import com.sumirelabs.pulsar.light.PulsarChunk;
import com.sumirelabs.pulsar.light.SWMRNibbleArray;
import com.sumirelabs.pulsar.util.WorldHeightContext;
import net.minecraft.world.chunk.Chunk;

/** Install a completed RGB maximum under the caller's chunk monitor. No propagation is needed. */
public final class RgbCanonicalLight {
    private RgbCanonicalLight() {}
    public static void install(Chunk chunk, RgbSnapshot snapshot, boolean sky, boolean[] empty, WorldHeightContext height) {
        if (!Thread.holdsLock(chunk)) throw new IllegalStateException("Canonical RGB publication requires its chunk monitor");
        PulsarChunk target = (PulsarChunk)chunk;
        SWMRNibbleArray[] old = sky ? target.pulsar$getSkyNibbles() : target.pulsar$getBlockNibbles();
        SWMRNibbleArray[] next = old != null && old.length == snapshot.sectionCount() ? old : new SWMRNibbleArray[snapshot.sectionCount()];
        int mask = 0;
        for (int s = 0; s < snapshot.sectionCount(); s++) {
            SWMRNibbleArray previous = next[s];
            SWMRNibbleArray value;
            if (snapshot.canonicalFull(s)) {
                if (previous != null && previous.isFullVisible()) continue;
                value = new SWMRNibbleArray(); value.setFull(); value.updateVisible();
            } else {
                if (snapshot.canonicalAbsent(s)) {
                    if (previous != null && previous.isZeroVisible() && !previous.isNullNibbleVisible()) continue;
                    value = new SWMRNibbleArray(); // UNINIT zero, never NULL sky extrusion.
                } else {
                    if (previous != null && snapshot.matchesCanonical(s, previous.getVisibleData())) continue;
                    value = snapshot.canonicalNibble(s);
                }
            }
            if (next == old) next = old.clone();
            next[s] = value;
            int slot = height.getStorageIndex(snapshot.minSection + s);
            if (slot >= 0 && slot < 32) mask |= 1 << slot;
        }
        if (sky) {
            if (next != old) target.pulsar$setSkyNibbles(next);
            if (empty != null) target.pulsar$setSkyEmptinessMap(empty);
        } else {
            if (next != old) target.pulsar$setBlockNibbles(next);
            if (empty != null) target.pulsar$setBlockEmptinessMap(empty);
        }
        if (mask != 0) target.pulsar$syncLightToVanilla(mask);
    }
}
