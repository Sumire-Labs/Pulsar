package com.sumirelabs.pulsar.light;

import com.google.common.util.concurrent.SettableFuture;
import net.minecraft.world.chunk.Chunk;

/**
 * Completion handle for one generation of a chunk's initial lighting.
 *
 * <p>The coordinator owns the lifecycle of this object. Package-local access
 * lets {@link WorldLightManager} attach the server-side resend callback without
 * exposing generation details as public API.
 */
final class ChunkLightCompletion {

    final long generation;
    final int edgeRecoveryAttempts;
    final InitialLightCompletionState state;
    final SettableFuture<Void> future;
    final Chunk chunk;
    volatile boolean published;

    ChunkLightCompletion(final long generation, final int requiredLanes, final Chunk chunk,
                         final int edgeRecoveryAttempts) {
        this.generation = generation;
        this.edgeRecoveryAttempts = edgeRecoveryAttempts;
        this.state = new InitialLightCompletionState(generation, requiredLanes);
        this.future = SettableFuture.create();
        this.chunk = chunk;
    }

    synchronized void finish(final boolean published) {
        if (this.future.isDone()) {
            return;
        }
        this.published = published;
        this.future.set(null);
    }
}
