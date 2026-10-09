package com.sumirelabs.pulsar.config;

import com.sumirelabs.pulsar.Reference;
import net.minecraft.launchwrapper.Launch;
import net.minecraftforge.common.config.Config;
import net.minecraftforge.common.config.ConfigManager;
import net.minecraftforge.fml.client.event.ConfigChangedEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import org.apache.logging.log4j.LogManager;

import java.io.File;
import java.io.IOException;

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

    @Config.Ignore
    public static final int CURRENT_CONFIG_VERSION = 2;

    // ASM discovery can initialize this class before mod preInit. Do the migration
    // before either that injection or our explicit ConfigManager registration.
    static {
        if (Launch.minecraftHome != null) {
            final File file = new File(new File(Launch.minecraftHome, "config"), "pulsar.cfg");
            try {
                if (PulsarConfigVersion.prepare(file, CURRENT_CONFIG_VERSION)) {
                    LogManager.getLogger("Pulsar Config").info(
                            "Config version missing or mismatched; regenerating Pulsar Config Version {} with defaults",
                            CURRENT_CONFIG_VERSION);
                }
            } catch (final IOException exception) {
                throw new IllegalStateException("Cannot regenerate Pulsar config: " + file, exception);
            }
        }
    }

    @Config.Comment({
            "Pulsar Config Version " + CURRENT_CONFIG_VERSION,
            "Do not edit this version. Managed by Pulsar.",
            "A missing or mismatched version resets this entire config to current defaults at startup."
    })
    @Config.RangeInt(min = CURRENT_CONFIG_VERSION, max = CURRENT_CONFIG_VERSION)
    @Config.RequiresMcRestart
    public static int configVersion = CURRENT_CONFIG_VERSION;

    @Config.Comment("Feature toggles")
    public static final Features features = new Features();
    @Config.Comment("Debug options")
    public static final Debug debug = new Debug();
    @Config.Comment("Lighting scheduling and memory tuning. Defaults retain existing budgets and cache sizes.")
    public static final Performance performance = new Performance();
    @Config.Comment({
            "Enable Pulsar's mob-spawn lighting gate. False uses vanilla spawn checks.",
            "This does not disable the lighting engine or mixins. Changes apply without a restart."
    })
    public static boolean enabled = true;

    public static class Features {

        @Config.Comment({
                "Server side: resample tracked TileEntity emission/opacity each world tick.",
                "Disable to reduce sampling cost or diagnose compatibility; changing TileEntity lights may then stay stale.",
                "Normal block-change sampling remains enabled. Changes apply without a restart."
        })
        public boolean trackTileEntityLight = true;

        @Config.Comment({
                "Use shared private work maps for /pulsar relight <radius>. Experimental; default false.",
                "Only affects manual relighting, not ordinary lighting updates. Applies to the next relight command."
        })
        public boolean experimentalRangeRelight = false;

        @Config.Comment({
                "Client side: coalesce lighting render notifications within each tick. Default true.",
                "False sends notifications directly for comparison. Light values are unchanged.",
                "Changes apply without a restart."
        })
        public boolean coalesceClientRenderUpdates = true;

        @Config.Comment({
                "Server-side lighting workers, including the integrated server in singleplayer.",
                "Default -1 selects logical CPU threads/3 automatically, clamped to 1..16.",
                "0 uses the dedicated sky/block workers; 1..16 sets the shared pool size explicitly.",
                "Tasks reserve a shared 5x5 chunk footprint; only non-overlapping tasks run together.",
                "More threads may increase CPU and memory use without improving FPS or latency.",
                "Restart Minecraft/the server to apply. Values are preserved while the config version matches."
        })
        @Config.RangeInt(min = -1, max = 16)
        @Config.RequiresMcRestart
        public int experimentalServerLightThreads = -1;

        @Config.Comment({
                "Shared client lighting budget per tick in milliseconds. Default 2 favors frame pacing.",
                "Sky and block tasks alternate. A task already running may exceed this soft limit.",
                "Higher values finish queued lighting sooner but can delay rendering. Client only.",
                "Changes apply to the next tick without a restart."
        })
        @Config.RangeInt(min = 1, max = 10)
        public int clientLightBudgetMs = 2;

        @Config.Comment({
                "Minimum block-light level for Thaumcraft 6's placed vis crystal clusters.",
                "0 keeps Thaumcraft's original emission. Default 10 is dimmer than a torch (14).",
                "Uses normal uncolored block-light propagation; held items are not affected.",
                "Restart Minecraft/the server to apply. Saved light is recalculated on chunk load.",
                "Stronger emission from other mods is preserved. Use matching settings on both sides."
        })
        @Config.RangeInt(min = 0, max = 15)
        @Config.RequiresMcRestart
        public int thaumcraftCrystalLightLevel = 10;

        @Config.Comment({
                "Allow the server to send chunks to clients before initial lighting has propagated.",
                "1.12.2 has no light-update packet, so light sent wrong stays wrong on the client",
                "until a block change. Chunks with valid persisted light are ready instantly, so",
                "keeping this off only delays freshly generated chunks by a few worker milliseconds."
        })
        public boolean sendChunksWithoutLight = false;
    }

    public static class Performance {
        @Config.Comment({
                "Time in milliseconds spent draining one batch by a dedicated server lighting worker.",
                "Applies only when experimentalServerLightThreads=0; shared-pool workers run claimed tasks directly.",
                "Default 15. Smaller values check the queue between batches more often.",
                "A running chunk task is never interrupted and may exceed this soft limit.",
                "Changes apply to the next batch without a restart."
        })
        @Config.RangeInt(min = 1, max = 50)
        public volatile int dedicatedServerBatchBudgetMs = 15;

        @Config.Comment({
                "Total milliseconds per server world tick allowed for waiting on lighting during chunk unload.",
                "Default 10. Lower values reduce unload stalls but may invalidate saved light for recalculation.",
                "0 never waits for unfinished lighting; it does not discard blocks or bypass saved-light validation.",
                "Higher values allow more work to finish before unloading but may lengthen the server tick.",
                "Reload the world/restart the server to apply."
        })
        @Config.RangeInt(min = 0, max = 50)
        @Config.RequiresWorldRestart
        public int unloadLightWaitBudgetMs = 10;

        @Config.Comment({
                "Target number of idle calculation engines retained per world and light lane in the shared pool.",
                "Default 4. Higher values retain more memory and reduce engine allocation during bursts.",
                "0 disables this idle pool. Active jobs are never limited or cancelled by this setting.",
                "Dedicated server workers and client lanes retain their single reusable engine independently.",
                "Concurrent completions can briefly exceed this target. Reload the world/restart the server to apply."
        })
        @Config.RangeInt(min = 0, max = 16)
        @Config.RequiresWorldRestart
        public int cachedEnginesPerLane = 4;
    }

    public static class Debug {

        @Config.Comment("Emit per-tick stats to logs/pulsar-stats.log.")
        public boolean enableDebugStats = false;

        @Config.Comment({
                "Ticks per statistics log entry while enableDebugStats is true. Default 20 (about one second at 20 TPS).",
                "Smaller values give finer detail but increase log volume and formatting/disk overhead.",
                "Larger values aggregate a longer measurement window. Changes apply without a restart.",
                "Has no effect while statistics logging is disabled."
        })
        @Config.RangeInt(min = 1, max = 1200)
        public volatile int statsLogIntervalTicks = 20;
    }

    /** Calling this method initializes the version guard before registration reads the file. */
    public static void initialize() {
        ConfigManager.register(PulsarConfig.class);
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
