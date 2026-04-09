package com.sumirelabs.pulsar.config;

import com.sumirelabs.pulsar.Pulsar;
import net.minecraftforge.common.config.Configuration;

import java.io.File;

/**
 * Forge {@link Configuration}-backed runtime configuration for Pulsar. The
 * config file lives at {@code config/pulsar.cfg} and is loaded once during
 * {@code FMLPreInitializationEvent}.
 */
public final class PulsarConfig {

    private static final String CATEGORY_GENERAL = "general";
    private static final String CATEGORY_PERFORMANCE = "performance";
    private static final String CATEGORY_FEATURES = "features";
    private static final String CATEGORY_DEBUG = "debug";

    /**
     * Master switch. When false, Pulsar's mob-spawn gate falls through to
     * vanilla behaviour. (The chunk / world / lighting mixins remain
     * applied because unapplying them mid-run is not safe — see
     * {@link com.sumirelabs.pulsar.mixin.MixinChunk} for details.)
     */
    public static boolean enabled = true;

    /** Java thread priority for the BFS worker threads (1–10). */
    public static int workerThreadPriority = Thread.NORM_PRIORITY;

    /**
     * Per-tick wall-clock budget for the block-change drain phase, in
     * milliseconds. The worker yields once this budget is exceeded so that
     * initial-light tasks can preempt long block-change runs.
     */
    public static int lightUpdateBudgetMillisPerTick = 5;

    /** Per-tick wall-clock budget for the edge-check phase, in milliseconds. */
    public static int edgeCheckBudgetMillisPerTick = 10;

    /**
     * Track whether the player is mid-place/break on the client and use it
     * to fast-path the renderer's reaction to the change. Disable to test
     * whether a rendering glitch is caused by the player-action heuristic.
     *
     * <p>Read by
     * {@link com.sumirelabs.pulsar.mixin.MixinPlayerControllerMP} — when
     * false, the inject is a no-op.
     */
    public static boolean trackPlayerAction = true;

    /**
     * Allow the server to send chunks to clients before initial lighting
     * has fully propagated. Pulsar's worker threads push the corrected
     * values shortly afterwards.
     *
     * <p>Read by
     * {@link com.sumirelabs.pulsar.mixin.MixinChunk#pulsar$alwaysPopulated}
     * — when false, {@code Chunk.isPopulated()} falls through to vanilla
     * behaviour. Note that this can cause the client to sit on
     * "Waiting for chunk…" until Pulsar's BFS completes its initial pass.
     */
    public static boolean sendChunksWithoutLight = true;

    /** Emit per-tick stats to {@code logs/pulsar-stats.log}. */
    public static boolean enableDebugStats = false;

    /**
     * Alfheim-style BFS queue dedup. When true (default),
     * {@link com.sumirelabs.pulsar.light.engine.PulsarEngine#appendToIncreaseQueue(long)}
     * and {@code appendToDecreaseQueue(long)} consult a {@code LongOpenHashSet}
     * keyed by (coord, level, write/recheck flags) and reject duplicates.
     * The kill-switch exists so the dedup layer can be disabled at runtime
     * if it is ever suspected of dropping legitimate propagation work.
     */
    public static boolean enableBfsDedup = true;

    private static Configuration config;

    private PulsarConfig() {}

    public static void load(final File configDir) {
        final File file = new File(configDir, "pulsar.cfg");
        config = new Configuration(file);
        try {
            config.load();
            sync();
        } catch (final Throwable t) {
            Pulsar.LOGGER.error("Failed to load pulsar.cfg", t);
        } finally {
            if (config.hasChanged()) {
                config.save();
            }
        }
    }

    private static void sync() {
        enabled = config.getBoolean("enabled", CATEGORY_GENERAL, true,
                "Master switch. When false, Pulsar's mixins fall through to vanilla lighting.");

        workerThreadPriority = config.getInt("workerThreadPriority", CATEGORY_PERFORMANCE,
                Thread.NORM_PRIORITY, Thread.MIN_PRIORITY, Thread.MAX_PRIORITY,
                "Java thread priority for the BFS worker threads (1=lowest, 10=highest).");

        lightUpdateBudgetMillisPerTick = config.getInt("lightUpdateBudgetMillisPerTick", CATEGORY_PERFORMANCE,
                5, 1, 100,
                "Per-tick wall-clock budget for the block-change drain phase, in milliseconds.");

        edgeCheckBudgetMillisPerTick = config.getInt("edgeCheckBudgetMillisPerTick", CATEGORY_PERFORMANCE,
                10, 1, 100,
                "Per-tick wall-clock budget for the edge-check phase, in milliseconds.");

        trackPlayerAction = config.getBoolean("trackPlayerAction", CATEGORY_FEATURES, true,
                "Track player place/break on the client to fast-path renderer reaction.");

        sendChunksWithoutLight = config.getBoolean("sendChunksWithoutLight", CATEGORY_FEATURES, true,
                "Allow the server to send chunks to clients before initial lighting has propagated.");

        enableDebugStats = config.getBoolean("enableDebugStats", CATEGORY_DEBUG, false,
                "Emit per-tick stats to logs/pulsar-stats.log.");

        enableBfsDedup = config.getBoolean("enableBfsDedup", CATEGORY_PERFORMANCE, true,
                "Alfheim-style BFS queue dedup. Rejects duplicate (coord,level,flags) "
                        + "enqueues before they reach the drain loop. Disable only to "
                        + "diagnose a suspected dropped-update bug.");
    }

    public static void save() {
        if (config != null && config.hasChanged()) {
            config.save();
        }
    }
}
