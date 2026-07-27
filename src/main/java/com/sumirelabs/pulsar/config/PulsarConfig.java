package com.sumirelabs.pulsar.config;

import com.sumirelabs.pulsar.Reference;
import net.minecraftforge.common.config.Config;
import net.minecraftforge.common.config.ConfigManager;
import net.minecraftforge.fml.client.event.ConfigChangedEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;

/**
 * Forge {@link Config @Config}-based runtime configuration for Pulsar.
 *
 * <p>The config file lives at {@code config/pulsar.cfg} and is managed
 * automatically by Forge's {@link ConfigManager}. Values can be changed
 * at runtime through the Mod Options GUI; the {@link EventHandler}
 * re-syncs them on every {@link ConfigChangedEvent}.
 */
@Config(modid = Reference.MOD_ID, name = "pulsar")
public class PulsarConfig {

    @Config.Comment("Master switch. When false, Pulsar's mob-spawn gate falls through to vanilla behaviour.")
    public static boolean enabled = true;

    @Config.Comment("Feature toggles")
    public static final Features features = new Features();

    @Config.Comment("Compatibility integrations")
    public static final Compat compat = new Compat();

    @Config.Comment("Debug options")
    public static final Debug debug = new Debug();

    public static class Features {

        @Config.Comment({
                "Allow the server to send chunks to clients before initial lighting has propagated.",
                "1.12.2 has no light-update packet, so light sent wrong stays wrong on the client",
                "until a block change. Chunks with valid persisted light are ready instantly, so",
                "keeping this off only delays freshly generated chunks by a few worker milliseconds."
        })
        public boolean sendChunksWithoutLight = false;
    }

    public static class Compat {

        @Config.Comment({
                "Route Celeritas's chunk-meshing light lookups through the directional",
                "(MC-92) fix, so slabs and stairs in terrain get the corrected lighting.",
                "Adds per-face lookups to the meshing hot loop; under investigation for",
                "FPS impact while streaming chunks, so it is OFF by default for now.",
                "The mixin is applied at startup - changing this requires a restart."})
        @Config.RequiresMcRestart
        public boolean celeritasDirectionalMeshLight = false;

        @Config.Comment({
                "Invalidate Celeritas's cloned-section cache whenever Pulsar publishes",
                "light changes, so chunk meshes never rebuild from stale light.",
                "Fixes lighting sticking in sealed rooms (bright floors in closed boxes,",
                "rooms staying dark after opening a window). Can cost FPS during heavy",
                "chunk streaming, so it is OFF by default while that is being measured.",
                "Enable it if you see stale lighting with Celeritas installed."})
        public boolean celeritasCloneInvalidation = false;
    }

    public static class Debug {

        @Config.Comment("Emit per-tick stats to logs/pulsar-stats.log.")
        public boolean enableDebugStats = false;
    }

    @Mod.EventBusSubscriber(modid = Reference.MOD_ID)
    public static class EventHandler {

        @SubscribeEvent
        public static void onConfigChanged(final ConfigChangedEvent.OnConfigChangedEvent event) {
            if (Reference.MOD_ID.equals(event.getModID())) {
                ConfigManager.sync(Reference.MOD_ID, Config.Type.INSTANCE);
            }
        }
    }
}
