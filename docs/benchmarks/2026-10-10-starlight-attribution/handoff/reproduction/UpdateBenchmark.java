package com.sumirelabs.lightbench;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import net.minecraft.block.Block;
import net.minecraft.block.material.Material;
import net.minecraft.block.state.IBlockState;
import net.minecraft.command.ICommandSender;
import net.minecraft.init.Blocks;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.text.TextComponentString;
import net.minecraft.world.EnumSkyBlock;
import net.minecraft.world.World;
import net.minecraftforge.fml.common.Loader;

/** Controlled, correctness-checked block-update benchmark used for publishable results. */
final class UpdateBenchmark {

    static final int CENTER_X = 20008;
    static final int CENTER_Z = 20008;
    static final int PLATFORM_SIZE = 64;
    static final int PLATFORM_Y = 254;
    static final int CHUNK_HALO = 1;
    static final int UPDATE_FLAGS = 16;
    static final int WARMUP_PAIRS = 20;
    static final int MEASURED_PAIRS = 200;

    private static final int MINIMUM_CLEAR_HEIGHT = 240;
    // Keep the warmup and measured light-radius-14 footprints disjoint while
    // retaining a 16-block opaque margin to every platform edge.
    static final int WARMUP_OFFSET = -16;

    private UpdateBenchmark() {}

    static void run(final ICommandSender sender, final World world, final LightProbe probe) throws Exception {
        run(sender, world, probe, WARMUP_PAIRS, MEASURED_PAIRS);
    }

    static void run(
            final ICommandSender sender,
            final World world,
            final LightProbe probe,
            final int warmupPairs,
            final int measuredPairs)
            throws Exception {
        run(sender, world, probe, warmupPairs, measuredPairs, false);
    }

    static void run(
            final ICommandSender sender,
            final World world,
            final LightProbe probe,
            final int warmupPairs,
            final int measuredPairs,
            final boolean blockOnly)
            throws Exception {
        if (warmupPairs < 0 || warmupPairs > 100000 || measuredPairs < 1 || measuredPairs > 100000) {
            throw new IllegalArgumentException("update pair counts are outside supported bounds");
        }
        requireUnambiguousEngineDetection(probe);
        probe.requireUpdateBenchmarkSupport();
        if (world.isRemote) {
            throw new IllegalStateException("the update benchmark must run on the logical server");
        }
        if (!world.provider.hasSkyLight()) {
            throw new IllegalStateException("the update benchmark requires a dimension with sky light");
        }

        say(
                sender,
                "updates preflight: loading a fixed 64x64 platform footprint and checking for uniform flat terrain");
        final Setup setup = prepareControlledEnvironment(world, probe, blockOnly);
        say(
                sender,
                String.format(
                        Locale.ROOT,
                        "updates preflight passed: floor y=%d (%s:%d), %,d clear air blocks, %d loaded chunks",
                        setup.floorY,
                        setup.floorBlock,
                        setup.floorMeta,
                        setup.checkedAirBlocks,
                        setup.loadedChunks));

        final BlockPos skyWarmup = new BlockPos(CENTER_X + WARMUP_OFFSET, PLATFORM_Y, CENTER_Z + WARMUP_OFFSET);
        final BlockPos skyMeasured = new BlockPos(CENTER_X, PLATFORM_Y, CENTER_Z);
        final BlockPos blockWarmup = new BlockPos(CENTER_X + WARMUP_OFFSET, setup.floorY + 1, CENTER_Z + WARMUP_OFFSET);
        final BlockPos blockMeasured = new BlockPos(CENTER_X, setup.floorY + 1, CENTER_Z);

        com.sumirelabs.benchprobe.BenchTiming.prepare();
        say(sender, "updates warmup: " + warmupPairs + " pairs per workload");
        if (!blockOnly) runSkyPairs(world, probe, skyWarmup, setup.floorY, warmupPairs, null, null);
        runBlockPairs(world, probe, blockWarmup, warmupPairs, null, null);
        say(sender, "updates measurement: sky first, then glowstone; no console or file output until both finish");

        final long[] skyRemoveSubmission = new long[measuredPairs];
        final long[] skyRemoveBarrier = new long[measuredPairs];
        final long[] skyRemoveCompletion = new long[measuredPairs];
        final long[] skyPlaceSubmission = new long[measuredPairs];
        final long[] skyPlaceBarrier = new long[measuredPairs];
        final long[] skyPlaceCompletion = new long[measuredPairs];
        final long[] blockPlaceSubmission = new long[measuredPairs];
        final long[] blockPlaceBarrier = new long[measuredPairs];
        final long[] blockPlaceCompletion = new long[measuredPairs];
        final long[] blockRemoveSubmission = new long[measuredPairs];
        final long[] blockRemoveBarrier = new long[measuredPairs];
        final long[] blockRemoveCompletion = new long[measuredPairs];
        final SampleColumns skyRemoveSamples =
                new SampleColumns(skyRemoveSubmission, skyRemoveBarrier, skyRemoveCompletion);
        final SampleColumns skyPlaceSamples =
                new SampleColumns(skyPlaceSubmission, skyPlaceBarrier, skyPlaceCompletion);
        final SampleColumns blockPlaceSamples =
                new SampleColumns(blockPlaceSubmission, blockPlaceBarrier, blockPlaceCompletion);
        final SampleColumns blockRemoveSamples =
                new SampleColumns(blockRemoveSubmission, blockRemoveBarrier, blockRemoveCompletion);

        com.sumirelabs.benchprobe.BenchTiming.discardWarmup();
        final String startedAtUtc = BenchmarkReport.nowUtc();
        final long cpuBefore = probe.engine() == LightProbe.Engine.PULSAR ? LightProbe.pulsarWorkerCpuNanos() : -1;
        final GcSnapshot gcBefore = GcSnapshot.capture();
        if (!blockOnly)
            runSkyPairs(world, probe, skyMeasured, setup.floorY, measuredPairs, skyRemoveSamples, skyPlaceSamples);
        runBlockPairs(world, probe, blockMeasured, measuredPairs, blockPlaceSamples, blockRemoveSamples);

        final GcSnapshot gcAfter = GcSnapshot.capture();
        final long cpuAfter = probe.engine() == LightProbe.Engine.PULSAR ? LightProbe.pulsarWorkerCpuNanos() : -1;
        final long workerCpuNanos = cpuBefore >= 0 && cpuAfter >= 0 ? cpuAfter - cpuBefore : -1;

        // A sparse column probe can miss stale light near the roof boundary.
        // These complete independent volume checks are outside every latency sample.
        if (blockOnly) verifyOpenSkyBlockVolumes(world, probe, blockMeasured);
        else verifyFinalVolumes(world, probe, skyMeasured, blockMeasured, setup.floorY);

        final List<UpdatePhaseResult> phases = new ArrayList<>(4);
        if (!blockOnly)
            phases.add(new UpdatePhaseResult(
                    "sky_remove",
                    "sky",
                    "remove",
                    skyMeasured.getX(),
                    skyMeasured.getY(),
                    skyMeasured.getZ(),
                    skyRemoveSubmission,
                    skyRemoveBarrier,
                    skyRemoveCompletion));
        if (!blockOnly)
            phases.add(new UpdatePhaseResult(
                    "sky_place",
                    "sky",
                    "place",
                    skyMeasured.getX(),
                    skyMeasured.getY(),
                    skyMeasured.getZ(),
                    skyPlaceSubmission,
                    skyPlaceBarrier,
                    skyPlaceCompletion));
        phases.add(new UpdatePhaseResult(
                "block_place",
                "block",
                "place",
                blockMeasured.getX(),
                blockMeasured.getY(),
                blockMeasured.getZ(),
                blockPlaceSubmission,
                blockPlaceBarrier,
                blockPlaceCompletion));
        phases.add(new UpdatePhaseResult(
                "block_remove",
                "block",
                "remove",
                blockMeasured.getX(),
                blockMeasured.getY(),
                blockMeasured.getZ(),
                blockRemoveSubmission,
                blockRemoveBarrier,
                blockRemoveCompletion));

        for (final UpdatePhaseResult phase : phases) {
            report(sender, phase);
        }
        if (workerCpuNanos >= 0) {
            say(
                    sender,
                    String.format(
                            Locale.ROOT,
                            "updates Pulsar worker CPU (all measured phases): %.3fms",
                            workerCpuNanos * 1.0e-6));
        }

        final JsonObject preflight = createPreflight(setup);
        final BenchmarkReport.Plan plan =
                createPlan(setup, skyWarmup, skyMeasured, blockWarmup, blockMeasured, warmupPairs, measuredPairs);
        if (blockOnly) {
            plan.json.addProperty("workload_selection", "block_only_open_sky");
            plan.json.getAsJsonObject("platform").addProperty("block", "minecraft:air");
            plan.json.getAsJsonArray("workloads").get(0).getAsJsonObject().addProperty("executed", false);
        }
        final long gcCollectionsDelta =
                gcBefore.available && gcAfter.available ? gcAfter.collectionCount - gcBefore.collectionCount : -1;
        final long gcTimeMillisDelta = gcBefore.available && gcAfter.available
                ? gcAfter.collectionTimeMillis - gcBefore.collectionTimeMillis
                : -1;
        try {
            final Path output = BenchmarkReport.writeUpdates(
                    world,
                    probe.engine(),
                    startedAtUtc,
                    probe.completionBarrierName(),
                    probe.rawLightReaderName(),
                    preflight,
                    plan,
                    phases,
                    workerCpuNanos,
                    gcCollectionsDelta,
                    gcTimeMillisDelta);
            com.sumirelabs.benchprobe.BenchTiming.write(output.resolveSibling("sampled-timing.json"));
            java.nio.file.Files.write(
                    output.resolveSibling("handoff-wait.json"),
                    ("{\"mode\":\"" + System.getProperty("handoff.wait", "legacy") + "\",\"await_calls\":"
                                    + probe.diagnosticAwaitCalls() + ",\"await_false\":" + probe.diagnosticAwaitFalse()
                                    + "}")
                            .getBytes(java.nio.charset.StandardCharsets.UTF_8));
            say(sender, "raw result saved: " + output.toAbsolutePath());
        } catch (final Exception e) {
            Lightbench.LOGGER.error("could not write Lightbench update result", e);
            say(sender, "raw result write failed; the completed console measurements remain available in the log");
        }
    }

    private static void requireUnambiguousEngineDetection(final LightProbe probe) {
        final boolean pulsarLoaded = Loader.isModLoaded("pulsar");
        final boolean alfheimLoaded = Loader.isModLoaded("alfheim");
        final boolean starlightLoaded = Loader.isModLoaded("starlight");
        final int loadedEngines = (pulsarLoaded ? 1 : 0) + (alfheimLoaded ? 1 : 0) + (starlightLoaded ? 1 : 0);
        if (loadedEngines > 1) {
            throw new IllegalStateException("multiple supported light engines are loaded; select exactly one");
        }
        if (pulsarLoaded && probe.engine() != LightProbe.Engine.PULSAR) {
            throw new IllegalStateException("Pulsar is loaded, but its completion barrier could not be detected");
        }
        if (alfheimLoaded && probe.engine() != LightProbe.Engine.ALFHEIM) {
            throw new IllegalStateException("Alfheim is loaded, but its completion barrier could not be detected");
        }
        if (starlightLoaded && probe.engine() != LightProbe.Engine.STARLIGHT) {
            throw new IllegalStateException("Starlight is loaded, but its completion barrier could not be detected");
        }
        if (loadedEngines == 0 && probe.engine() != LightProbe.Engine.VANILLA) {
            throw new IllegalStateException("a light-engine adapter was detected without its matching loaded mod");
        }
    }

    private static Setup prepareControlledEnvironment(final World world, final LightProbe probe) throws Exception {
        return prepareControlledEnvironment(world, probe, false);
    }

    private static Setup prepareControlledEnvironment(
            final World world, final LightProbe probe, final boolean blockOnly) throws Exception {
        final int baseX = CENTER_X - PLATFORM_SIZE / 2;
        final int baseZ = CENTER_Z - PLATFORM_SIZE / 2;
        final int maximumX = baseX + PLATFORM_SIZE - 1;
        final int maximumZ = baseZ + PLATFORM_SIZE - 1;
        int loadedChunks = 0;
        for (int chunkX = (baseX >> 4) - CHUNK_HALO; chunkX <= (maximumX >> 4) + CHUNK_HALO; ++chunkX) {
            for (int chunkZ = (baseZ >> 4) - CHUNK_HALO; chunkZ <= (maximumZ >> 4) + CHUNK_HALO; ++chunkZ) {
                world.getChunkProvider().provideChunk(chunkX, chunkZ);
                ++loadedChunks;
            }
        }
        probe.drainLight();

        final int floorY = findFloorY(world, CENTER_X, CENTER_Z);
        if (floorY < 0) {
            throw new IllegalStateException("controlled-world check failed: no floor was found below y=254");
        }
        if (PLATFORM_Y - floorY - 1 < MINIMUM_CLEAR_HEIGHT) {
            throw new IllegalStateException("controlled-world check failed: the clear air column must be at least "
                    + MINIMUM_CLEAR_HEIGHT + " blocks tall");
        }

        final BlockPos referenceFloorPos = new BlockPos(CENTER_X, floorY, CENTER_Z);
        final IBlockState floorState = world.getBlockState(referenceFloorPos);
        if (floorState.getMaterial() == Material.AIR) {
            throw new IllegalStateException("controlled-world check failed: the detected floor is air");
        }
        if (floorState.getLightOpacity(world, referenceFloorPos) < 15) {
            throw new IllegalStateException("controlled-world check failed: the floor must be fully opaque");
        }
        if (floorState.getLightValue(world, referenceFloorPos) != 0) {
            throw new IllegalStateException("controlled-world check failed: the floor must not emit light");
        }

        long checkedAirBlocks = 0;
        final IBlockState stone = Blocks.STONE.getDefaultState();
        final IBlockState platformState = blockOnly ? Blocks.AIR.getDefaultState() : stone;
        for (int x = baseX; x <= maximumX; ++x) {
            for (int z = baseZ; z <= maximumZ; ++z) {
                final BlockPos floorPos = new BlockPos(x, floorY, z);
                if (!floorState.equals(world.getBlockState(floorPos))) {
                    throw new IllegalStateException(
                            "controlled-world check failed: floor block differs at " + format(floorPos));
                }
                for (int y = floorY + 1; y < PLATFORM_Y; ++y) {
                    final BlockPos clearPos = new BlockPos(x, y, z);
                    if (world.getBlockState(clearPos).getMaterial() != Material.AIR) {
                        throw new IllegalStateException(
                                "controlled-world check failed: expected air at " + format(clearPos));
                    }
                    ++checkedAirBlocks;
                }
                final BlockPos platformPos = new BlockPos(x, PLATFORM_Y, z);
                final IBlockState existing = world.getBlockState(platformPos);
                if (existing.getMaterial() != Material.AIR && !stone.equals(existing)) {
                    throw new IllegalStateException(
                            "controlled-world check failed: y=254 contains a non-platform block at "
                                    + format(platformPos));
                }
            }
        }

        for (int x = baseX; x <= maximumX; ++x) {
            for (int z = baseZ; z <= maximumZ; ++z) {
                final BlockPos platformPos = new BlockPos(x, PLATFORM_Y, z);
                if (!platformState.equals(world.getBlockState(platformPos))) {
                    if (!world.setBlockState(platformPos, platformState, UPDATE_FLAGS)) {
                        throw new IllegalStateException("could not build the test platform at " + format(platformPos));
                    }
                }
            }
        }
        probe.drainLight();

        final BlockPos measured = new BlockPos(CENTER_X, PLATFORM_Y, CENTER_Z);
        final BlockPos warmup = new BlockPos(CENTER_X + WARMUP_OFFSET, PLATFORM_Y, CENTER_Z + WARMUP_OFFSET);
        // Building the roof one column at a time can leave vanilla skylight in
        // an order-dependent intermediate state. In particular, an early roof
        // column may retain light that entered horizontally while neighbouring
        // columns were still open. Re-run one complete open/close transition
        // at each benchmark column after the final roof exists. This is outside
        // every timed interval and gives all engines the same reachable,
        // correctness-checked closed baseline.
        if (!blockOnly) normalizeSkyBaseline(world, probe, measured);
        if (!blockOnly) normalizeSkyBaseline(world, probe, warmup);
        if (!blockOnly) verifySkyColumn(world, probe, measured, floorY, false);
        if (!blockOnly) verifySkyColumn(world, probe, warmup, floorY, false);
        if (!blockOnly) verifySkyVolume(world, probe, measured, floorY, false);
        else {
            final BlockPos blockAt = new BlockPos(CENTER_X, floorY + 1, CENTER_Z);
            verifyBlockVolume(world, probe, blockAt, false);
            verifyDirectSkyVolume(world, probe, blockAt, false);
        }

        final ResourceLocation floorName = Block.REGISTRY.getNameForObject(floorState.getBlock());
        final String floorBlock =
                floorName == null ? floorState.getBlock().getClass().getName() : floorName.toString();
        final int floorMeta = floorState.getBlock().getMetaFromState(floorState);
        return new Setup(
                baseX,
                baseZ,
                floorY,
                floorBlock,
                floorState.toString(),
                floorMeta,
                PLATFORM_SIZE * PLATFORM_SIZE,
                checkedAirBlocks,
                loadedChunks);
    }

    private static void normalizeSkyBaseline(final World world, final LightProbe probe, final BlockPos platformPosition)
            throws Exception {
        final IBlockState air = Blocks.AIR.getDefaultState();
        final IBlockState stone = Blocks.STONE.getDefaultState();
        requireState(world, platformPosition, stone, "sky normalization baseline");
        if (!world.setBlockState(platformPosition, air, UPDATE_FLAGS)) {
            throw new IllegalStateException(
                    "could not open the sky normalization column at " + format(platformPosition));
        }
        probe.drainLight(platformPosition);
        if (!world.setBlockState(platformPosition, stone, UPDATE_FLAGS)) {
            throw new IllegalStateException(
                    "could not close the sky normalization column at " + format(platformPosition));
        }
        probe.drainLight(platformPosition);
    }

    private static int findFloorY(final World world, final int x, final int z) {
        final BlockPos.MutableBlockPos position = new BlockPos.MutableBlockPos();
        for (int y = PLATFORM_Y - 1; y >= 0; --y) {
            position.setPos(x, y, z);
            if (world.getBlockState(position).getMaterial() != Material.AIR) {
                return y;
            }
        }
        return -1;
    }

    private static void runSkyPairs(
            final World world,
            final LightProbe probe,
            final BlockPos position,
            final int floorY,
            final int pairs,
            final SampleColumns removeSamples,
            final SampleColumns placeSamples)
            throws Exception {
        final IBlockState air = Blocks.AIR.getDefaultState();
        final IBlockState stone = Blocks.STONE.getDefaultState();
        requireState(world, position, stone, "sky baseline");
        for (int index = 0; index < pairs; ++index) {
            final TimedEdit remove = performEdit(
                    world, probe, position, air, removeSamples == null ? "warmup_sky_remove" : "sky_remove", index);
            verifySkyColumn(world, probe, position, floorY, true);
            if (removeSamples != null) {
                removeSamples.set(index, remove);
            }

            final TimedEdit place = performEdit(
                    world, probe, position, stone, placeSamples == null ? "warmup_sky_place" : "sky_place", index);
            verifySkyColumn(world, probe, position, floorY, false);
            if (placeSamples != null) {
                placeSamples.set(index, place);
            }
        }
    }

    private static void runBlockPairs(
            final World world,
            final LightProbe probe,
            final BlockPos position,
            final int pairs,
            final SampleColumns placeSamples,
            final SampleColumns removeSamples)
            throws Exception {
        final IBlockState air = Blocks.AIR.getDefaultState();
        final IBlockState glowstone = Blocks.GLOWSTONE.getDefaultState();
        requireState(world, position, air, "block-light baseline");
        verifyBlockLight(world, probe, position, false);
        for (int index = 0; index < pairs; ++index) {
            final TimedEdit place = performEdit(
                    world,
                    probe,
                    position,
                    glowstone,
                    placeSamples == null ? "warmup_block_place" : "block_place",
                    index);
            verifyBlockLight(world, probe, position, true);
            if (placeSamples != null) {
                placeSamples.set(index, place);
            }

            final TimedEdit remove = performEdit(
                    world, probe, position, air, removeSamples == null ? "warmup_block_remove" : "block_remove", index);
            verifyBlockLight(world, probe, position, false);
            if (removeSamples != null) {
                removeSamples.set(index, remove);
            }
        }
    }

    private static TimedEdit performEdit(
            final World world, final LightProbe probe, final BlockPos position, final IBlockState state)
            throws Exception {
        return performEdit(world, probe, position, state, null, -1);
    }

    private static TimedEdit performEdit(
            final World world,
            final LightProbe probe,
            final BlockPos position,
            final IBlockState state,
            final String phase,
            final int sampleIndex)
            throws Exception {
        probe.beginDiagnosticEdit();
        final boolean capture = com.sumirelabs.benchprobe.BenchTiming.begin(phase, sampleIndex);
        final long start = System.nanoTime();
        if (!world.setBlockState(position, state, UPDATE_FLAGS)) {
            throw new IllegalStateException("timed block edit did not change state at " + format(position));
        }
        final long submitted = System.nanoTime();
        final long barrierNanos = probe.drainLight(position);
        final long completed = System.nanoTime();
        if (capture)
            com.sumirelabs.benchprobe.BenchTiming.end(submitted - start, barrierNanos, completed - start, start);
        probe.endDiagnosticEdit();
        return new TimedEdit(submitted - start, barrierNanos, completed - start);
    }

    private static void verifySkyColumn(
            final World world,
            final LightProbe probe,
            final BlockPos platformPosition,
            final int floorY,
            final boolean open)
            throws Exception {
        final int middleY = floorY + (PLATFORM_Y - floorY) / 2;
        requireLight(world, probe, EnumSkyBlock.SKY, platformPosition.down(), open ? 15 : 0);
        requireLight(
                world,
                probe,
                EnumSkyBlock.SKY,
                new BlockPos(platformPosition.getX(), middleY, platformPosition.getZ()),
                open ? 15 : 0);
        requireLight(
                world,
                probe,
                EnumSkyBlock.SKY,
                new BlockPos(platformPosition.getX(), floorY + 1, platformPosition.getZ()),
                open ? 15 : 0);
        final BlockPos floorProbe = new BlockPos(platformPosition.getX(), floorY + 1, platformPosition.getZ());
        requireLight(world, probe, EnumSkyBlock.SKY, floorProbe.east(14), open ? 1 : 0);
        requireLight(world, probe, EnumSkyBlock.SKY, floorProbe.east(15), 0);
    }

    private static void verifyBlockLight(
            final World world, final LightProbe probe, final BlockPos source, final boolean present) throws Exception {
        requireLight(world, probe, EnumSkyBlock.BLOCK, source, present ? 15 : 0);
        requireLight(world, probe, EnumSkyBlock.BLOCK, source.east(14), present ? 1 : 0);
        requireLight(world, probe, EnumSkyBlock.BLOCK, source.east(15), 0);
    }

    private static void requireLight(
            final World world,
            final LightProbe probe,
            final EnumSkyBlock type,
            final BlockPos position,
            final int expected)
            throws Exception {
        final int actual = probe.readRawLight(world, type, position);
        if (actual != expected) {
            throw new IllegalStateException(type.name().toLowerCase(Locale.ROOT) + " light verification failed at "
                    + format(position) + ": expected " + expected + ", got " + actual);
        }
    }

    private static void requireState(
            final World world, final BlockPos position, final IBlockState expected, final String label) {
        if (!expected.equals(world.getBlockState(position))) {
            throw new IllegalStateException(label + " state verification failed at " + format(position));
        }
    }

    private static JsonObject createPreflight(final Setup setup) {
        final JsonObject preflight = new JsonObject();
        preflight.addProperty("controlled_environment", true);
        preflight.addProperty("checked_columns", setup.checkedColumns);
        preflight.addProperty("checked_air_blocks", setup.checkedAirBlocks);
        preflight.addProperty("loaded_chunks", setup.loadedChunks);
        preflight.addProperty("floor_y", setup.floorY);
        preflight.addProperty("floor_block", setup.floorBlock);
        preflight.addProperty("floor_state", setup.floorState);
        preflight.addProperty("floor_meta", setup.floorMeta);
        preflight.addProperty("platform_normalized", true);
        preflight.addProperty("initial_light_verified", true);
        preflight.addProperty("post_measurement_volume_verified", true);
        return preflight;
    }

    private static void verifyFinalVolumes(
            final World world, final LightProbe probe, final BlockPos skyAt, final BlockPos blockAt, final int floorY)
            throws Exception {
        performEdit(world, probe, skyAt, Blocks.AIR.getDefaultState());
        verifySkyVolume(world, probe, skyAt, floorY, true);
        performEdit(world, probe, skyAt, Blocks.STONE.getDefaultState());
        verifySkyVolume(world, probe, skyAt, floorY, false);
        performEdit(world, probe, blockAt, Blocks.GLOWSTONE.getDefaultState());
        verifyBlockVolume(world, probe, blockAt, true);
        performEdit(world, probe, blockAt, Blocks.AIR.getDefaultState());
        verifyBlockVolume(world, probe, blockAt, false);
    }

    private static void verifySkyVolume(
            final World world, final LightProbe probe, final BlockPos at, final int floorY, final boolean open)
            throws Exception {
        final int baseX = CENTER_X - PLATFORM_SIZE / 2, baseZ = CENTER_Z - PLATFORM_SIZE / 2;
        for (int y : new int[] {floorY + 1, (floorY + PLATFORM_Y) / 2, PLATFORM_Y - 1}) {
            for (int z = 0; z < PLATFORM_SIZE; z++)
                for (int x = 0; x < PLATFORM_SIZE; x++) {
                    final BlockPos pos = new BlockPos(baseX + x, y, baseZ + z);
                    requireLight(
                            world,
                            probe,
                            EnumSkyBlock.SKY,
                            pos,
                            expectedSkyValue(x, z, at.getX() - baseX, at.getZ() - baseZ, open));
                    requireLight(world, probe, EnumSkyBlock.BLOCK, pos, 0);
                }
        }
    }

    static int expectedSkyValue(int x, int z, int holeX, int holeZ, boolean open) {
        int distance = Math.min(Math.min(x + 1, PLATFORM_SIZE - x), Math.min(z + 1, PLATFORM_SIZE - z));
        if (open) distance = Math.min(distance, Math.abs(x - holeX) + Math.abs(z - holeZ));
        return Math.max(0, 15 - distance);
    }

    private static void verifyOpenSkyBlockVolumes(World world, LightProbe probe, BlockPos at) throws Exception {
        performEdit(world, probe, at, Blocks.GLOWSTONE.getDefaultState());
        verifyBlockVolume(world, probe, at, true);
        verifyDirectSkyVolume(world, probe, at, true);
        performEdit(world, probe, at, Blocks.AIR.getDefaultState());
        verifyBlockVolume(world, probe, at, false);
        verifyDirectSkyVolume(world, probe, at, false);
    }

    private static void verifyDirectSkyVolume(World world, LightProbe probe, BlockPos at, boolean emitting)
            throws Exception {
        for (int y = 0; y < 16; y++)
            for (int z = -15; z <= 15; z++)
                for (int x = -15; x <= 15; x++)
                    requireLight(
                            world,
                            probe,
                            EnumSkyBlock.SKY,
                            at.add(x, y, z),
                            emitting && x == 0 && y == 0 && z == 0 ? 0 : 15);
    }

    private static void verifyBlockVolume(
            final World world, final LightProbe probe, final BlockPos at, final boolean emitting) throws Exception {
        for (int y = 0; y < 16; y++)
            for (int z = -15; z <= 15; z++)
                for (int x = -15; x <= 15; x++) {
                    final int expected = emitting ? Math.max(0, 15 - Math.abs(x) - Math.abs(z) - y) : 0;
                    requireLight(world, probe, EnumSkyBlock.BLOCK, at.add(x, y, z), expected);
                }
    }

    private static BenchmarkReport.Plan createPlan(
            final Setup setup,
            final BlockPos skyWarmup,
            final BlockPos skyMeasured,
            final BlockPos blockWarmup,
            final BlockPos blockMeasured,
            final int warmupPairs,
            final int measuredPairs) {
        final JsonObject plan = new JsonObject();
        plan.addProperty("coordinate_unit", "block");
        plan.addProperty("logical_side", "server");
        plan.addProperty("measurement_scope", "block_state_change_and_server_light_completion");
        plan.addProperty("primary_metric", "completion_nanos");
        plan.addProperty("timed_interval", "before_set_block_state_to_after_completion_barrier");
        plan.addProperty("submission_interval", "before_to_after_set_block_state");
        plan.addProperty("barrier_interval", "engine_specific_completion_wait");
        plan.addProperty("completion_barrier", "after_each_edit");
        plan.addProperty("update_flags", UPDATE_FLAGS);
        plan.addProperty("warmup_pairs", warmupPairs);
        plan.addProperty("measured_pairs", measuredPairs);
        plan.addProperty("same_position_each_sample", true);
        plan.addProperty("validation", "after_each_completion_outside_timed_interval");

        final JsonObject platform = new JsonObject();
        platform.addProperty("base_x", setup.baseX);
        platform.addProperty("base_z", setup.baseZ);
        platform.addProperty("size_x", PLATFORM_SIZE);
        platform.addProperty("size_z", PLATFORM_SIZE);
        platform.addProperty("top_y", PLATFORM_Y);
        platform.addProperty("block", "minecraft:stone");
        platform.addProperty("loaded_chunk_halo", CHUNK_HALO);
        platform.addProperty("minimum_clear_height", MINIMUM_CLEAR_HEIGHT);
        platform.addProperty(
                "minimum_sample_edge_margin",
                Math.min(
                        minimumEdgeMargin(skyWarmup.getX(), skyWarmup.getZ(), setup.baseX, setup.baseZ, PLATFORM_SIZE),
                        minimumEdgeMargin(
                                skyMeasured.getX(), skyMeasured.getZ(), setup.baseX, setup.baseZ, PLATFORM_SIZE)));
        plan.add("platform", platform);

        final JsonObject floor = new JsonObject();
        floor.addProperty("y", setup.floorY);
        floor.addProperty("block", setup.floorBlock);
        floor.addProperty("state", setup.floorState);
        floor.addProperty("meta", setup.floorMeta);
        plan.add("floor", floor);

        final JsonArray workloads = new JsonArray();
        workloads.add(skyWorkload(skyWarmup, skyMeasured, setup.floorY));
        workloads.add(blockWorkload(blockWarmup, blockMeasured));
        plan.add("workloads", workloads);
        return new BenchmarkReport.Plan(plan);
    }

    private static JsonObject skyWorkload(
            final BlockPos warmupPosition, final BlockPos measuredPosition, final int floorY) {
        final JsonObject workload = new JsonObject();
        workload.addProperty("light_type", "sky");
        workload.addProperty("baseline_block", "minecraft:stone");
        workload.addProperty("changed_block", "minecraft:air");
        final JsonArray phaseOrder = new JsonArray();
        phaseOrder.add(new JsonPrimitive("sky_remove"));
        phaseOrder.add(new JsonPrimitive("sky_place"));
        workload.add("phase_order", phaseOrder);
        workload.add("warmup_position", coordinate(warmupPosition));
        workload.add("measured_position", coordinate(measuredPosition));

        final BlockPos[] directProbes = {
            measuredPosition.down(),
            new BlockPos(measuredPosition.getX(), floorY + (PLATFORM_Y - floorY) / 2, measuredPosition.getZ()),
            new BlockPos(measuredPosition.getX(), floorY + 1, measuredPosition.getZ())
        };
        final JsonArray openExpected = new JsonArray();
        final JsonArray closedExpected = new JsonArray();
        for (final BlockPos probe : directProbes) {
            openExpected.add(lightExpectation(probe, 15));
            closedExpected.add(lightExpectation(probe, 0));
        }
        final BlockPos floorProbe = new BlockPos(measuredPosition.getX(), floorY + 1, measuredPosition.getZ());
        openExpected.add(lightExpectation(floorProbe.east(14), 1));
        openExpected.add(lightExpectation(floorProbe.east(15), 0));
        closedExpected.add(lightExpectation(floorProbe.east(14), 0));
        closedExpected.add(lightExpectation(floorProbe.east(15), 0));
        workload.add("open_expected_light", openExpected);
        workload.add("closed_expected_light", closedExpected);
        return workload;
    }

    private static JsonObject blockWorkload(final BlockPos warmupPosition, final BlockPos measuredPosition) {
        final JsonObject workload = new JsonObject();
        workload.addProperty("light_type", "block");
        workload.addProperty("baseline_block", "minecraft:air");
        workload.addProperty("changed_block", "minecraft:glowstone");
        final JsonArray phaseOrder = new JsonArray();
        phaseOrder.add(new JsonPrimitive("block_place"));
        phaseOrder.add(new JsonPrimitive("block_remove"));
        workload.add("phase_order", phaseOrder);
        workload.add("warmup_position", coordinate(warmupPosition));
        workload.add("measured_position", coordinate(measuredPosition));

        final JsonArray presentExpected = new JsonArray();
        presentExpected.add(lightExpectation(measuredPosition, 15));
        presentExpected.add(lightExpectation(measuredPosition.east(14), 1));
        presentExpected.add(lightExpectation(measuredPosition.east(15), 0));
        workload.add("present_expected_light", presentExpected);

        final JsonArray absentExpected = new JsonArray();
        absentExpected.add(lightExpectation(measuredPosition, 0));
        absentExpected.add(lightExpectation(measuredPosition.east(14), 0));
        absentExpected.add(lightExpectation(measuredPosition.east(15), 0));
        workload.add("absent_expected_light", absentExpected);
        return workload;
    }

    private static JsonObject lightExpectation(final BlockPos position, final int level) {
        final JsonObject expectation = new JsonObject();
        expectation.add("position", coordinate(position));
        expectation.addProperty("level", level);
        return expectation;
    }

    private static JsonArray coordinate(final BlockPos position) {
        final JsonArray coordinate = new JsonArray();
        coordinate.add(new JsonPrimitive(position.getX()));
        coordinate.add(new JsonPrimitive(position.getY()));
        coordinate.add(new JsonPrimitive(position.getZ()));
        return coordinate;
    }

    static int minimumEdgeMargin(final int x, final int z, final int baseX, final int baseZ, final int platformSize) {
        final int maximumX = baseX + platformSize - 1;
        final int maximumZ = baseZ + platformSize - 1;
        return Math.min(Math.min(x - baseX, maximumX - x), Math.min(z - baseZ, maximumZ - z));
    }

    private static void report(final ICommandSender sender, final UpdatePhaseResult phase) {
        final long[] sorted = phase.completionNanos.clone();
        java.util.Arrays.sort(sorted);
        long sum = 0;
        for (final long value : sorted) {
            sum += value;
        }
        say(
                sender,
                String.format(
                        Locale.ROOT,
                        "%s completion: avg %.3fms | p50 %.3fms | p95 %.3fms | p99 %.3fms | max %.3fms",
                        phase.name,
                        (sum / (double) sorted.length) * 1.0e-6,
                        percentile(sorted, 0.50) * 1.0e-6,
                        percentile(sorted, 0.95) * 1.0e-6,
                        percentile(sorted, 0.99) * 1.0e-6,
                        sorted[sorted.length - 1] * 1.0e-6));
    }

    private static long percentile(final long[] sorted, final double quantile) {
        final int index = Math.max(0, Math.min(sorted.length - 1, (int) Math.ceil(sorted.length * quantile) - 1));
        return sorted[index];
    }

    private static String format(final BlockPos position) {
        return position.getX() + "," + position.getY() + "," + position.getZ();
    }

    private static void say(final ICommandSender sender, final String message) {
        sender.sendMessage(new TextComponentString("[lightbench] " + message));
        Lightbench.LOGGER.info("[lightbench] " + message);
    }

    private static final class Setup {

        final int baseX;
        final int baseZ;
        final int floorY;
        final String floorBlock;
        final String floorState;
        final int floorMeta;
        final int checkedColumns;
        final long checkedAirBlocks;
        final int loadedChunks;

        Setup(
                final int baseX,
                final int baseZ,
                final int floorY,
                final String floorBlock,
                final String floorState,
                final int floorMeta,
                final int checkedColumns,
                final long checkedAirBlocks,
                final int loadedChunks) {
            this.baseX = baseX;
            this.baseZ = baseZ;
            this.floorY = floorY;
            this.floorBlock = floorBlock;
            this.floorState = floorState;
            this.floorMeta = floorMeta;
            this.checkedColumns = checkedColumns;
            this.checkedAirBlocks = checkedAirBlocks;
            this.loadedChunks = loadedChunks;
        }
    }

    private static final class TimedEdit {

        final long submissionNanos;
        final long barrierNanos;
        final long completionNanos;

        TimedEdit(final long submissionNanos, final long barrierNanos, final long completionNanos) {
            this.submissionNanos = submissionNanos;
            this.barrierNanos = barrierNanos;
            this.completionNanos = completionNanos;
        }
    }

    private static final class SampleColumns {

        final long[] submissionNanos;
        final long[] barrierNanos;
        final long[] completionNanos;

        SampleColumns(final long[] submissionNanos, final long[] barrierNanos, final long[] completionNanos) {
            this.submissionNanos = submissionNanos;
            this.barrierNanos = barrierNanos;
            this.completionNanos = completionNanos;
        }

        void set(final int index, final TimedEdit edit) {
            this.submissionNanos[index] = edit.submissionNanos;
            this.barrierNanos[index] = edit.barrierNanos;
            this.completionNanos[index] = edit.completionNanos;
        }
    }

    private static final class GcSnapshot {

        final boolean available;
        final long collectionCount;
        final long collectionTimeMillis;

        GcSnapshot(final boolean available, final long collectionCount, final long collectionTimeMillis) {
            this.available = available;
            this.collectionCount = collectionCount;
            this.collectionTimeMillis = collectionTimeMillis;
        }

        static GcSnapshot capture() {
            long count = 0;
            long time = 0;
            for (final GarbageCollectorMXBean collector : ManagementFactory.getGarbageCollectorMXBeans()) {
                if (collector.getCollectionCount() < 0 || collector.getCollectionTime() < 0) {
                    return new GcSnapshot(false, 0, 0);
                }
                count += collector.getCollectionCount();
                time += collector.getCollectionTime();
            }
            return new GcSnapshot(true, count, time);
        }
    }
}
