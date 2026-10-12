package com.sumirelabs.lightbench;

import java.lang.management.ManagementFactory;
import java.lang.management.ThreadInfo;
import java.lang.management.ThreadMXBean;
import java.lang.reflect.Method;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.EnumSkyBlock;
import net.minecraft.world.World;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.gen.ChunkProviderServer;

/**
 * Engine detection + the minimal per-engine adapters, all via reflection so
 * this mod compiles and runs against any combination of engines.
 */
public final class LightProbe {

    public enum Engine {
        PULSAR,
        ALFHEIM,
        STARLIGHT,
        VANILLA
    }

    private static final String WAIT_MODE = System.getProperty("handoff.wait", "legacy");
    private Method diagnosticBegin, diagnosticDrain, diagnosticEnd, diagnosticFuture;
    private Object diagnosticWorld;
    private boolean diagnosticEdit;
    private long diagnosticAwaitCalls, diagnosticAwaitFalse;

    public void beginDiagnosticEdit() throws Exception {
        if (this.engine == Engine.PULSAR
                && (WAIT_MODE.equals("caller") || WAIT_MODE.equals("gated") || WAIT_MODE.equals("gatedpoll"))) {
            this.diagnosticBegin.invoke(null, this.diagnosticWorld);
            this.diagnosticEdit = true;
        }
    }

    public void endDiagnosticEdit() throws Exception {
        if (this.engine == Engine.PULSAR && WAIT_MODE.equals("caller") && this.diagnosticEdit)
            this.diagnosticEnd.invoke(null, this.diagnosticWorld);
        this.diagnosticEdit = false;
    }

    private final Engine engine;
    private World vanillaWorld;
    private final Object pulsarManager; // com.sumirelabs.pulsar.light.WorldLightManager
    private final BooleanMethodPoller pulsarHasUpdates;
    private final Method pulsarAwaitPendingWork;
    private final Object alfheimEngine; // dev.redstudio.alfheim.lighting.LightingEngine
    private final Method alfheimProcess;
    private final Method alfheimCachedLight;
    private final Object starlightWorldLight; // com.github.starlight.world.WorldLight
    private final Method starlightPropagateChanges;
    private final Class<?> starlightLightChunk;
    private final Method starlightIsLightReady;
    private final Method starlightGetBlockLight;
    private final Method starlightGetSkyLight;
    private final Class<?> readinessChunkType;
    private final Method chunkLightReady;

    private LightProbe(
            final Engine engine,
            final Object pulsarManager,
            final Method pulsarHasUpdates,
            final Method pulsarAwaitPendingWork,
            final Object alfheimEngine,
            final Method alfheimProcess,
            final Method alfheimCachedLight,
            final Object starlightWorldLight,
            final Method starlightPropagateChanges,
            final Class<?> starlightLightChunk,
            final Method starlightIsLightReady,
            final Method starlightGetBlockLight,
            final Method starlightGetSkyLight,
            final Class<?> readinessChunkType,
            final Method chunkLightReady) {
        this.engine = engine;
        this.pulsarManager = pulsarManager;
        this.pulsarHasUpdates =
                pulsarHasUpdates == null ? null : new BooleanMethodPoller(pulsarManager, pulsarHasUpdates);
        this.pulsarAwaitPendingWork = pulsarAwaitPendingWork;
        this.alfheimEngine = alfheimEngine;
        this.alfheimProcess = alfheimProcess;
        this.alfheimCachedLight = alfheimCachedLight;
        this.starlightWorldLight = starlightWorldLight;
        this.starlightPropagateChanges = starlightPropagateChanges;
        this.starlightLightChunk = starlightLightChunk;
        this.starlightIsLightReady = starlightIsLightReady;
        this.starlightGetBlockLight = starlightGetBlockLight;
        this.starlightGetSkyLight = starlightGetSkyLight;
        this.readinessChunkType = readinessChunkType;
        this.chunkLightReady = chunkLightReady;
    }

    public static LightProbe create(final World world) {
        final LightProbe probe = createForWorld(world);
        if (probe.engine == Engine.VANILLA) probe.vanillaWorld = world;
        if (probe.engine == Engine.PULSAR) {
            try {
                probe.diagnosticWorld = world;
                probe.diagnosticFuture = probe.pulsarManager
                        .getClass()
                        .getDeclaredMethod("getPendingWorkFutureNear", int.class, int.class);
                probe.diagnosticFuture.setAccessible(true);
                if (WAIT_MODE.equals("caller") || WAIT_MODE.equals("gated") || WAIT_MODE.equals("gatedpoll")) {
                    Class<?> scheduler = Class.forName("com.sumirelabs.pulsar.light.ParallelLightScheduler");
                    probe.diagnosticBegin = scheduler.getDeclaredMethod("diagnosticBegin", Object.class);
                    probe.diagnosticDrain = scheduler.getDeclaredMethod("diagnosticDrain", Object.class);
                    probe.diagnosticEnd = scheduler.getDeclaredMethod("diagnosticEnd", Object.class);
                    probe.diagnosticBegin.setAccessible(true);
                    probe.diagnosticDrain.setAccessible(true);
                    probe.diagnosticEnd.setAccessible(true);
                }
            } catch (ReflectiveOperationException error) {
                throw new IllegalStateException(error);
            }
        }
        return probe;
    }

    static LightProbe createForWorld(final Object world) {
        try {
            final Class<?> pulsarWorld = Class.forName("com.sumirelabs.pulsar.world.PulsarWorld");
            if (pulsarWorld.isInstance(world)) {
                final Object manager =
                        pulsarWorld.getMethod("pulsar$getLightManager").invoke(world);
                if (manager != null) {
                    final Method hasUpdates = manager.getClass().getMethod("hasUpdates");
                    Method awaitPendingWork = null;
                    Class<?> readinessChunkType = null;
                    Method chunkLightReady = null;
                    try {
                        awaitPendingWork = manager.getClass().getMethod("awaitPendingWork", int.class, int.class);
                    } catch (final NoSuchMethodException ignored) {
                        // Older Pulsar builds expose only the global pending-work probe.
                    }
                    try {
                        readinessChunkType = Class.forName("com.sumirelabs.pulsar.api.ExtendedChunk");
                        chunkLightReady = readinessChunkType.getMethod("pulsar$isLightReady");
                    } catch (final ReflectiveOperationException ignored) {
                        // Generation audits report an unavailable readiness flag as unready.
                    }
                    return new LightProbe(
                            Engine.PULSAR,
                            manager,
                            hasUpdates,
                            awaitPendingWork,
                            null,
                            null,
                            null,
                            null,
                            null,
                            null,
                            null,
                            null,
                            null,
                            readinessChunkType,
                            chunkLightReady);
                }
            }
        } catch (final Throwable ignored) {
        }
        try {
            final Class<?> provider = Class.forName("dev.redstudio.alfheim.api.ILightingEngineProvider");
            if (provider.isInstance(world)) {
                final Object engine =
                        provider.getMethod("getAlfheim$lightingEngine").invoke(world);
                if (engine != null) {
                    final Method process = engine.getClass().getMethod("processLightUpdates");
                    final Class<?> chunkLightingData = Class.forName("dev.redstudio.alfheim.api.IChunkLightingData");
                    Method cachedLight = null;
                    try {
                        cachedLight = chunkLightingData.getMethod(
                                "alfheim$getCachedLightFor", EnumSkyBlock.class, BlockPos.class);
                    } catch (final ReflectiveOperationException ignored) {
                        // Update-result validation will refuse a flushing fallback.
                    }
                    final Method chunkLightReady = chunkLightingData.getMethod("alfheim$isLightInitialized");
                    return new LightProbe(
                            Engine.ALFHEIM,
                            null,
                            null,
                            null,
                            engine,
                            process,
                            cachedLight,
                            null,
                            null,
                            null,
                            null,
                            null,
                            null,
                            chunkLightingData,
                            chunkLightReady);
                }
            }
        } catch (final Throwable ignored) {
        }
        try {
            final Class<?> provider = Class.forName("com.github.starlight.world.StarlightWorld");
            if (provider.isInstance(world)) {
                final Object worldLight =
                        provider.getMethod("starlight$getLight").invoke(world);
                if (worldLight != null) {
                    final Method propagateChanges = worldLight.getClass().getMethod("propagateChanges");
                    final Class<?> lightChunk = Class.forName("com.github.starlight.light.LightChunk");
                    final Method isLightReady = lightChunk.getMethod("starlight$isLightReady");
                    final Method getBlockLight = findLightReader(worldLight.getClass(), "getBlockLight");
                    final Method getSkyLight = findLightReader(worldLight.getClass(), "getSkyLight");
                    return new LightProbe(
                            Engine.STARLIGHT,
                            null,
                            null,
                            null,
                            null,
                            null,
                            null,
                            worldLight,
                            propagateChanges,
                            lightChunk,
                            isLightReady,
                            getBlockLight,
                            getSkyLight,
                            lightChunk,
                            isLightReady);
                }
            }
        } catch (final Throwable ignored) {
        }
        return new LightProbe(
                Engine.VANILLA, null, null, null, null, null, null, null, null, null, null, null, null, null, null);
    }

    private static Method findLightReader(final Class<?> worldLightClass, final String name)
            throws NoSuchMethodException {
        for (final Method method : worldLightClass.getMethods()) {
            if (method.getName().equals(name)
                    && method.getParameterCount() == 4
                    && method.getParameterTypes()[1] == int.class
                    && method.getParameterTypes()[2] == int.class
                    && method.getParameterTypes()[3] == int.class) {
                return method;
            }
        }
        throw new NoSuchMethodException(worldLightClass.getName() + "." + name);
    }

    public Engine engine() {
        return this.engine;
    }

    public String completionBarrierName() {
        switch (this.engine) {
            case PULSAR:
                return this.pulsarAwaitPendingWork == null
                        ? "pulsar_global_pending_poll"
                        : "diagnostic_" + WAIT_MODE + "_then_global_pending";
            case ALFHEIM:
                return "alfheim_process_light_updates";
            case STARLIGHT:
                return "starlight_propagate_changes_synchronous";
            case VANILLA:
                return "vanilla_inline_then_chunk_ticks";
            default:
                throw new IllegalStateException("unknown light engine " + this.engine);
        }
    }

    public String rawLightReaderName() {
        switch (this.engine) {
            case ALFHEIM:
                return "alfheim_cached_light";
            case STARLIGHT:
                return "starlight_worldlight_light_arrays";
            default:
                return "world_stored_light";
        }
    }

    public void requireUpdateBenchmarkSupport() {
        if (this.engine == Engine.PULSAR && this.pulsarAwaitPendingWork == null) {
            throw new IllegalStateException(
                    "this Pulsar build lacks the per-chunk completion future required by the update benchmark");
        }
        if (this.engine == Engine.ALFHEIM && this.alfheimCachedLight == null) {
            throw new IllegalStateException(
                    "this Alfheim build lacks the non-flushing light reader required by the update benchmark");
        }
        if (this.engine == Engine.ALFHEIM && this.chunkLightReady == null) {
            throw new IllegalStateException("this Alfheim build lacks the per-chunk light initialization flag");
        }
        if (this.engine == Engine.STARLIGHT
                && (this.starlightLightChunk == null
                        || this.starlightIsLightReady == null
                        || this.starlightGetBlockLight == null
                        || this.starlightGetSkyLight == null)) {
            throw new IllegalStateException(
                    "this Starlight build lacks the light-readiness or non-flushing light reader required by the update benchmark");
        }
    }

    /**
     * Block until the engine has fully lit everything generated so far.
     * Returns the wall nanos spent draining, including Vanilla's deferred sky-gap maintenance.
     */
    public long drainLight() throws Exception {
        return drainLight(null);
    }

    /**
     * Position-aware completion barrier for update-latency samples. Current
     * Pulsar builds expose an awaitable per-chunk future, which avoids adding
     * millisecond polling granularity to short samples. The global queue check
     * remains the final correctness barrier and the fallback for older builds.
     */
    public long drainLight(final BlockPos affectedPosition) throws Exception {
        if (this.engine == Engine.VANILLA) {
            // Generation has its own explicit, scored-neighborhood finalization.
            // Scanning every loaded chunk after each generation batch would add
            // unrelated quadratic harness work to Vanilla's timing.
            if (this.vanillaWorld == null || affectedPosition == null) return 0;
            final long start = System.nanoTime();
            drainVanillaSkyGaps(affectedPosition);
            return System.nanoTime() - start;
        }
        final long start = System.nanoTime();
        switch (this.engine) {
            case PULSAR:
                if (WAIT_MODE.equals("gatedpoll") && affectedPosition != null) {
                    if (this.diagnosticEdit) this.diagnosticEnd.invoke(null, this.diagnosticWorld);
                } else if (WAIT_MODE.equals("caller") && affectedPosition != null) {
                    this.diagnosticDrain.invoke(null, this.diagnosticWorld);
                } else if ((WAIT_MODE.equals("future") || WAIT_MODE.equals("gated")) && affectedPosition != null) {
                    if (WAIT_MODE.equals("gated") && this.diagnosticEdit)
                        this.diagnosticEnd.invoke(null, this.diagnosticWorld);
                    final long futureDeadline = start + 300_000_000_000L;
                    while (true) {
                        java.util.concurrent.Future<?> future =
                                (java.util.concurrent.Future<?>) this.diagnosticFuture.invoke(
                                        this.pulsarManager, affectedPosition.getX() >> 4, affectedPosition.getZ() >> 4);
                        if (future == null) break;
                        long remaining = futureDeadline - System.nanoTime();
                        if (remaining <= 0) throw new IllegalStateException("Diagnostic future timeout");
                        future.get(remaining, java.util.concurrent.TimeUnit.NANOSECONDS);
                    }
                } else if (affectedPosition != null && this.pulsarAwaitPendingWork != null) {
                    this.diagnosticAwaitCalls++;
                    if (!((Boolean) this.pulsarAwaitPendingWork.invoke(
                            this.pulsarManager, affectedPosition.getX() >> 4, affectedPosition.getZ() >> 4)))
                        this.diagnosticAwaitFalse++;
                }
                // Async workers: poll until both queues are empty. Busy-spin
                // for the first few ms so sub-millisecond drains are not
                // quantised by sleep granularity (matters for the edits test).
                final long spinUntil = start + 5_000_000L;
                final long deadline = start + 300_000_000_000L; // 5 min safety
                while (this.pulsarHasUpdates.read()) {
                    final long now = System.nanoTime();
                    if (now > deadline) {
                        throw new IllegalStateException("Pulsar queues did not drain within 5 minutes");
                    }
                    if (now < spinUntil) {
                        Thread.yield();
                    } else {
                        Thread.sleep(1);
                    }
                }
                break;
            case ALFHEIM:
                // Deferred queue, normally flushed on reads: flush it all now.
                this.alfheimProcess.invoke(this.alfheimEngine);
                break;
            case STARLIGHT:
                // Starlight queues edits until this explicit synchronous propagation call.
                this.starlightPropagateChanges.invoke(this.starlightWorldLight);
                break;
            case VANILLA:
                break;
        }
        return System.nanoTime() - start;
    }

    public long diagnosticAwaitCalls() {
        return this.diagnosticAwaitCalls;
    }

    public long diagnosticAwaitFalse() {
        return this.diagnosticAwaitFalse;
    }

    /** Use normal public chunk maintenance, never synthetic per-cell relighting. */
    private void drainVanillaSkyGaps(final BlockPos affectedPosition) {
        if (!(this.vanillaWorld.getChunkProvider() instanceof ChunkProviderServer)) return;
        final ChunkProviderServer provider = (ChunkProviderServer) this.vanillaWorld.getChunkProvider();
        for (int pass = 0; pass < 2; pass++) {
            if (affectedPosition == null) {
                for (final Chunk chunk : new java.util.ArrayList<>(provider.getLoadedChunks())) chunk.onTick(false);
            } else {
                final int cx = affectedPosition.getX() >> 4, cz = affectedPosition.getZ() >> 4;
                for (int dz = -2; dz <= 2; dz++)
                    for (int dx = -2; dx <= 2; dx++) {
                        final Chunk chunk = provider.getLoadedChunk(cx + dx, cz + dz);
                        if (chunk != null) chunk.onTick(false);
                    }
            }
        }
    }

    /**
     * Read a stored light value without processing deferred work. Alfheim's
     * normal Chunk#getLightFor method drains its queue before reading, which
     * could otherwise hide an early-returning completion barrier during the
     * out-of-band correctness check.
     */
    public int readRawLight(final World world, final EnumSkyBlock lightType, final BlockPos position) throws Exception {
        return readRawLight(world.getChunk(position), lightType, position);
    }

    /** Read light from an already-held chunk, without consulting a world or chunk provider. */
    public int readRawLight(final Chunk chunk, final EnumSkyBlock lightType, final BlockPos position) throws Exception {
        if (this.engine == Engine.ALFHEIM) {
            if (this.alfheimCachedLight == null) {
                throw new IllegalStateException("Alfheim does not expose non-flushing cached-light reads");
            }
            final Number value = (Number) this.alfheimCachedLight.invoke(chunk, lightType, position);
            return value.intValue() & 0xff;
        }
        if (this.engine == Engine.STARLIGHT) {
            return readStarlightLight(chunk, lightType, position);
        }
        return chunk.getLightFor(lightType, position) & 0xff;
    }

    /** Fast path for an audit that has already confirmed readiness for this retained chunk. */
    int readRawLightFromReadyChunk(final Chunk chunk, final EnumSkyBlock lightType, final BlockPos position)
            throws Exception {
        if (this.engine == Engine.ALFHEIM) {
            final Number value = (Number) this.alfheimCachedLight.invoke(chunk, lightType, position);
            return value.intValue() & 0xff;
        }
        if (this.engine == Engine.STARLIGHT) {
            return readStarlightLightValue(chunk, lightType, position);
        }
        return chunk.getLightFor(lightType, position) & 0xff;
    }

    /** Whether this engine says the supplied chunk's light data is ready to read. */
    public boolean isChunkLightReady(final Chunk chunk) throws Exception {
        return isChunkLightReadyObject(chunk);
    }

    boolean isChunkLightReadyObject(final Object chunk) throws Exception {
        switch (this.engine) {
            case VANILLA:
                return chunk instanceof Chunk && ((Chunk) chunk).isLightPopulated();
            case PULSAR:
            case ALFHEIM:
            case STARLIGHT:
                return this.readinessChunkType != null
                        && this.chunkLightReady != null
                        && this.readinessChunkType.isInstance(chunk)
                        && (Boolean) this.chunkLightReady.invoke(chunk);
            default:
                throw new IllegalStateException("unknown light engine " + this.engine);
        }
    }

    String chunkLightReadinessName() {
        return readinessName(this.engine.name().toLowerCase(java.util.Locale.ROOT));
    }

    static String readinessName(final String engineName) {
        switch (engineName) {
            case "vanilla":
                return "chunk_is_light_populated";
            case "pulsar":
                return "pulsar_chunk_light_ready";
            case "alfheim":
                return "alfheim_light_initialized";
            case "starlight":
                return "starlight_chunk_light_ready";
            default:
                throw new IllegalArgumentException("unknown light engine " + engineName);
        }
    }

    int readStarlightLight(final Object chunk, final EnumSkyBlock lightType, final BlockPos position) throws Exception {
        if (!this.starlightLightChunk.isInstance(chunk) || !((Boolean) this.starlightIsLightReady.invoke(chunk))) {
            throw new IllegalStateException("Starlight chunk is not light-ready at " + position);
        }
        return readStarlightLightValue(chunk, lightType, position);
    }

    private int readStarlightLightValue(final Object chunk, final EnumSkyBlock lightType, final BlockPos position)
            throws Exception {
        final Method reader = lightType == EnumSkyBlock.SKY ? this.starlightGetSkyLight : this.starlightGetBlockLight;
        return ((Number) reader.invoke(
                                this.starlightWorldLight, chunk, position.getX(), position.getY(), position.getZ()))
                        .intValue()
                & 0xff;
    }

    /** Total CPU nanos of all live threads whose name starts with {@code Pulsar-}. */
    public static long pulsarWorkerCpuNanos() {
        final ThreadMXBean bean = ManagementFactory.getThreadMXBean();
        if (!bean.isThreadCpuTimeSupported()) {
            return -1;
        }
        if (!bean.isThreadCpuTimeEnabled()) {
            try {
                bean.setThreadCpuTimeEnabled(true);
            } catch (final SecurityException | UnsupportedOperationException ignored) {
                return -1;
            }
        }
        long total = 0;
        for (final long id : bean.getAllThreadIds()) {
            final ThreadInfo info = bean.getThreadInfo(id);
            if (info == null || !info.getThreadName().startsWith("Pulsar-")) {
                continue;
            }
            final long cpu = bean.getThreadCpuTime(id);
            if (cpu > 0) {
                total += cpu;
            }
        }
        return total;
    }
}
