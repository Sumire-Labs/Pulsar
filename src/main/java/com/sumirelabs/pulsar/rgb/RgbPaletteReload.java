package com.sumirelabs.pulsar.rgb;

import com.sumirelabs.pulsar.light.WorldLightManager;
import com.sumirelabs.pulsar.world.PulsarWorld;
import net.minecraft.server.MinecraftServer;
import java.util.ArrayList;
import java.util.concurrent.CompletableFuture;

/** Server-owner transaction. Queued relights publish new colors through the normal network path. */
public final class RgbPaletteReload {
    private RgbPaletteReload() {}
    public static CompletableFuture<Integer> request(MinecraftServer server,RgbPaletteOverrides.Values values) {
        return RgbReloadBarrier.submit(command->server.addScheduledTask(command),server::isServerRunning,()->{
            if(RgbPaletteOverrides.current().generation>=Integer.MAX_VALUE-1)
                throw new IllegalStateException("Palette generation exhausted; restart before reloading again");
            var managers=new ArrayList<WorldLightManager>();
            for(var world:server.worlds)if(world instanceof PulsarWorld access) {
                var manager=access.pulsar$getLightManager();if(manager!=null)managers.add(manager);
            }
            // Invalidate readiness before publishing a new persistence profile. In-flight
            // jobs are stopped at the barrier; queued old initial generations are superseded.
            int chunks=0;
            for(var manager:managers)chunks+=manager.queuePaletteRelights();
            var previous=RgbPaletteOverrides.current().values;
            var previousSamples=new java.util.IdentityHashMap<WorldLightManager,RgbContextualLightManager>();
            for(var manager:managers)previousSamples.put(manager,manager.rgbContextualLight());
            RgbPaletteOverrides.install(values);
            try {
                for(var manager:managers)manager.refreshPaletteSamples();
            } catch(Throwable failure) {
                // Roll back with a fresh epoch so late cache writes cannot revive failed values.
                RgbPaletteOverrides.install(previous);
                // Do not call a failing mod again to roll back: retain the actual
                // pre-transaction samples, including pending dynamic positions.
                for(var manager:managers)manager.restorePaletteSamples(previousSamples.get(manager));
                throw new IllegalStateException("RGB palette rolled back; relights remain queued",failure);
            }
            return chunks;
        });
    }
}
