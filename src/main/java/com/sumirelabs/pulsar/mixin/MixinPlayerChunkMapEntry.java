package com.sumirelabs.pulsar.mixin;

import com.sumirelabs.pulsar.config.PulsarConfig;
import com.sumirelabs.pulsar.light.PulsarChunk;
import com.sumirelabs.pulsar.light.WorldLightManager;
import com.sumirelabs.pulsar.world.PulsarWorld;
import net.minecraft.server.management.PlayerChunkMapEntry;
import net.minecraft.world.chunk.Chunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import javax.annotation.Nullable;

/**
 * Gates chunk sending on Pulsar's light state. This is deliberately NOT done
 * by overriding {@code Chunk.isPopulated()}: that method also gates
 * {@code World.setBlockState}'s notifyBlockUpdate, so overriding it
 * suppressed block-change packets and render marks whenever light (or a
 * neighbour's light) wasn't ready.
 *
 * <p>The entry retries every tick, so returning {@code false} only delays
 * the send. Conditions on top of vanilla's own populated check:
 * <ul>
 *   <li>the chunk's initial propagation and own edge reconciliation are
 *       complete ({@code lightReady}), and</li>
 *   <li>all four horizontal neighbours are lit — their edge checks are what
 *       finalise this chunk's seam light, and 1.12.2 has no light packet to
 *       correct an already-sent chunk.</li>
 * </ul>
 */
@Mixin(PlayerChunkMapEntry.class)
public abstract class MixinPlayerChunkMapEntry {

    @Shadow
    @Nullable
    private Chunk chunk;
    @Shadow private int changes;
    @Shadow private int changedSectionFilter;
    @Shadow public abstract void sendPacket(net.minecraft.network.Packet<?> packet);

    @Inject(method="update",at=@At("HEAD"),cancellable=true)
    private void pulsar$waitForSectionPacketLight(final CallbackInfo ci) {
        if(this.chunk==null || this.changes<net.minecraftforge.common.ForgeModContainer.clumpingThreshold) return;
        final WorldLightManager manager=((PulsarWorld)this.chunk.getWorld()).pulsar$getLightManager();
        if(manager!=null && !manager.canSendUpdatedChunkLight(this.chunk.x,this.chunk.z)) {
            manager.deferChunkPacketUpdate(this.chunk);
            ci.cancel(); // Keep changes and changedSectionFilter for the manager's retry.
        }
    }

    @Redirect(method="update",at=@At(value="INVOKE",target="Lnet/minecraft/server/management/PlayerChunkMapEntry;sendPacket(Lnet/minecraft/network/Packet;)V"))
    private void pulsar$preserveEntitiesOnSectionRefresh(final PlayerChunkMapEntry entry,final net.minecraft.network.Packet<?> packet) {
        if(packet instanceof net.minecraft.network.play.server.SPacketChunkData data && data.isFullChunk() && this.chunk!=null) {
            final WorldLightManager manager=((PulsarWorld)this.chunk.getWorld()).pulsar$getLightManager();
            if(manager!=null) { manager.sendChunkLightRefresh(entry,this.chunk,this.changedSectionFilter); return; }
        }
        this.sendPacket(packet);
    }

    @Inject(method = "sendToPlayers", at = @At("HEAD"), cancellable = true, require = 0)
    private void pulsar$gateSendOnLight(final CallbackInfoReturnable<Boolean> cir) {
        if (PulsarConfig.features.sendChunksWithoutLight) {
            return;
        }
        final Chunk c = this.chunk;
        if (c == null) {
            return; // vanilla returns false itself
        }
        if (!((PulsarChunk) c).pulsar$isLightReady()) {
            cir.setReturnValue(false);
            return;
        }
        final WorldLightManager mgr = ((PulsarWorld) c.getWorld()).pulsar$getLightManager();
        if (mgr != null && !mgr.areNeighboursLightReady(c.x, c.z)) {
            cir.setReturnValue(false);
        }
    }
}
