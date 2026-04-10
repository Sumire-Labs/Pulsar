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

    @Config.Comment("Performance tuning")
    public static final Performance performance = new Performance();

    @Config.Comment("Feature toggles")
    public static final Features features = new Features();

    @Config.Comment("Debug options")
    public static final Debug debug = new Debug();

    public static class Performance {

        @Config.Comment("Java thread priority for the BFS worker threads (1=lowest, 10=highest).")
        @Config.RangeInt(min = 1, max = 10)
        public int workerThreadPriority = Thread.NORM_PRIORITY;

        @Config.Comment("Per-tick wall-clock budget for the block-change drain phase, in milliseconds.")
        @Config.RangeInt(min = 1, max = 100)
        public int lightUpdateBudgetMillisPerTick = 5;

        @Config.Comment("Per-tick wall-clock budget for the edge-check phase, in milliseconds.")
        @Config.RangeInt(min = 1, max = 100)
        public int edgeCheckBudgetMillisPerTick = 10;

        @Config.Comment({
                "Alfheim-style BFS queue dedup. Rejects duplicate (coord,level,flags)",
                "enqueues before they reach the drain loop. Disable only to diagnose",
                "a suspected dropped-update bug."
        })
        public boolean enableBfsDedup = true;
    }

    public static class Features {

        @Config.Comment({
                "Track player place/break on the client to fast-path renderer reaction.",
                "Disable to test whether a rendering glitch is caused by the player-action heuristic."
        })
        public boolean trackPlayerAction = true;

        @Config.Comment({
                "Allow the server to send chunks to clients before initial lighting has propagated.",
                "Pulsar's worker threads push the corrected values shortly afterwards."
        })
        public boolean sendChunksWithoutLight = true;
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
