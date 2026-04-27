package com.sumirelabs.pulsar.mixin;

import com.sumirelabs.pulsar.Pulsar;
import com.sumirelabs.pulsar.api.ExtendedWorld;
import com.sumirelabs.pulsar.light.WorldLightManager;
import com.sumirelabs.pulsar.world.PulsarWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.EnumSkyBlock;
import net.minecraft.world.World;
import net.minecraft.world.chunk.Chunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(World.class)
public abstract class MixinWorld implements PulsarWorld, ExtendedWorld {

    @Unique
    private volatile WorldLightManager pulsar$lightInterface;

    @Unique
    private volatile boolean pulsar$ready;

    @Unique
    private volatile boolean pulsar$playerAction;

    @Inject(method = "<init>*", at = @At("RETURN"))
    private void pulsar$onInit(final CallbackInfo ci) {
        this.pulsar$ready = true;
    }

    /**
     * Forward {@code checkLightFor} to Pulsar.
     *
     * <p>Server side: enqueue and let the worker process asynchronously.
     *
     * <p>Client side: if the change came from a player place/break (the
     * {@link #pulsar$playerAction} flag is set), run BFS synchronously on
     * the calling thread for instant visual feedback. Otherwise (server-pushed
     * changes like explosions, pistons, etc.) enqueue the same way as the
     * server side. Mirrors SuperNova's
     * {@code MixinWorld.updateLightByType} / {@code func_147451_t} dispatch.
     */
    @Inject(method = "checkLightFor", at = @At("HEAD"), cancellable = true)
    private void pulsar$checkLightFor(final EnumSkyBlock lightType, final BlockPos pos,
                                      final CallbackInfoReturnable<Boolean> cir) {
        if (!this.pulsar$ready) {
            return;
        }
        final WorldLightManager mgr = this.pulsar$getLightManager();
        if (mgr == null) {
            return;
        }
        final World self = (World) (Object) this;
        if (!self.isRemote) {
            mgr.queueBlockChange(pos.getX(), pos.getY(), pos.getZ());
            cir.setReturnValue(true);
            return;
        }

        // Client-side: only dispatch if the chunk is light-ready, otherwise
        // we'd race the initial BFS pass.
        final Chunk chunk = this.pulsar$getAnyChunkImmediately(pos.getX() >> 4, pos.getZ() >> 4);
        if (chunk == null || !((com.sumirelabs.pulsar.light.PulsarChunk) chunk).pulsar$isLightReady()) {
            cir.setReturnValue(false);
            return;
        }
        if (this.pulsar$playerAction) {
            mgr.blockChange(pos.getX(), pos.getY(), pos.getZ());
        } else {
            mgr.queueBlockChange(pos.getX(), pos.getY(), pos.getZ());
            mgr.scheduleUpdate();
        }
        cir.setReturnValue(true);
    }

    @Override
    public WorldLightManager pulsar$getLightManager() {
        WorldLightManager mgr = this.pulsar$lightInterface;
        if (mgr != null) return mgr;
        synchronized (this) {
            mgr = this.pulsar$lightInterface;
            if (mgr != null) return mgr;
            final World self = (World) (Object) this;
            // Skip GregTech WorldSceneRenderer / JEI multiblock-preview fake worlds.
            if (!Pulsar.proxy.isRealMainWorld(self)) {
                return null;
            }
            this.pulsar$lightInterface = mgr = new WorldLightManager(self, self.provider.hasSkyLight(), true);
        }
        return mgr;
    }

    @Override
    public Chunk pulsar$getAnyChunkImmediately(final int chunkX, final int chunkZ) {
        final WorldLightManager mgr = this.pulsar$lightInterface;
        if (mgr == null) return null;
        return mgr.getLoadedChunk(chunkX, chunkZ);
    }

    @Override
    public boolean pulsar$hasChunkPendingLight(final int chunkX, final int chunkZ) {
        final WorldLightManager mgr = this.pulsar$lightInterface;
        return mgr != null && mgr.hasChunkPendingLight(chunkX, chunkZ);
    }

    @Override
    public void pulsar$flushLightUpdates() {
        // Best-effort: no synchronous flush primitive yet; this hook exists
        // so external callers can be wired up later without an API break.
    }

    @Override
    public void pulsar$shutdown() {
        final WorldLightManager mgr = this.pulsar$lightInterface;
        if (mgr != null) {
            mgr.shutdown();
            this.pulsar$lightInterface = null;
        }
    }

    @Override
    public void pulsar$setPlayerAction(final boolean value) {
        this.pulsar$playerAction = value;
    }

    @Override
    public boolean pulsar$isPlayerAction() {
        return this.pulsar$playerAction;
    }
}
