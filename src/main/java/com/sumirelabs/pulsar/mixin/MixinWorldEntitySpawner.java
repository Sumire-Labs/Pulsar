package com.sumirelabs.pulsar.mixin;

import com.sumirelabs.pulsar.config.PulsarConfig;
import com.sumirelabs.pulsar.world.PulsarWorld;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.WorldEntitySpawner;
import net.minecraft.world.WorldServer;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Iterator;
import java.util.Set;

/**
 * Skip mob spawning in chunks whose Pulsar BFS hasn't completed yet.
 *
 * <p>Mirrors Hodgepodge's {@code MixinSpawnerAnimals_optimizeSpawning}
 * Supernova-aware skip (1.7.10) but inlined into Pulsar so we don't need
 * Hodgepodge as a runtime dependency on 1.12.2.
 *
 * <p>Without this, mobs may spawn during the brief window where a chunk has
 * been registered with Pulsar but its initial light pass hasn't run yet,
 * causing peaceful mobs to appear in caves and similar artefacts.
 */
@Mixin(WorldEntitySpawner.class)
public abstract class MixinWorldEntitySpawner {

    @Shadow
    @Final
    private Set<ChunkPos> eligibleChunksForSpawning;

    @Inject(method = "findChunksForSpawning", at = @At("HEAD"))
    private void pulsar$skipPendingLightChunks(final WorldServer worldServerIn,
                                               final boolean spawnHostileMobs,
                                               final boolean spawnPeacefulMobs,
                                               final boolean spawnOnSetTickRate,
                                               final CallbackInfoReturnable<Integer> cir) {
        if (!PulsarConfig.enabled) return;
        if (this.eligibleChunksForSpawning == null || this.eligibleChunksForSpawning.isEmpty()) return;

        final PulsarWorld pulsarWorld = (PulsarWorld) worldServerIn;
        final Iterator<ChunkPos> it = this.eligibleChunksForSpawning.iterator();
        while (it.hasNext()) {
            final ChunkPos pos = it.next();
            if (pulsarWorld.pulsar$hasChunkPendingLight(pos.x, pos.z)) {
                it.remove();
            }
        }
    }
}
