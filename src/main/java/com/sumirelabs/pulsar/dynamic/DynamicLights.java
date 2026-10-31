package com.sumirelabs.pulsar.dynamic;

import com.sumirelabs.pulsar.Pulsar;
import com.sumirelabs.pulsar.config.PulsarConfig;
import com.sumirelabs.pulsar.core.RgbMixinSelection;
import com.sumirelabs.pulsar.rgb.RgbSettings;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.world.WorldEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import java.util.*;
import java.util.concurrent.*;

/** Server-owned transient lighting. Published fields never enter chunk arrays or saved light. */
public final class DynamicLights {
    public enum Mode { OFF, VISUAL, GAMEPLAY }
    private static Mode mode = Mode.OFF;
    private static final Map<World, State> WORLDS = new ConcurrentHashMap<>();
    private static final ExecutorService WORKER = new ThreadPoolExecutor(2, 2, 0, TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(32), r -> {
        Thread t = new Thread(r, "Pulsar dynamic light"); t.setDaemon(true); return t;
    });
    private static final class Source {
        BlockPos pos; int emission; long sampled = -100; long revision;
        boolean dirty = true;
        CompletableFuture<DynamicLightField> pending;
        DynamicLightField field;
    }
    private static final class State {
        final Map<Integer, Source> sources = new LinkedHashMap<>();
        volatile List<DynamicLightField> visible = List.of();
        long tick, revision;
    }
    private DynamicLights() {}
    public static void initialize() {
        mode = PulsarConfig.enabled ? PulsarConfig.dynamicLights.mode : Mode.OFF;
        DynamicLightNetwork.initialize();
        MinecraftForge.EVENT_BUS.register(new DynamicLights());
    }
    public static Mode mode() { return mode; }
    public static int gameplay(World world, int x, int y, int z) {
        if (world.isRemote || mode != Mode.GAMEPLAY) return 0;
        State state = WORLDS.get(world);
        if (state == null) return 0;
        int value = 0;
        for (var field : state.visible) value = DynamicLightField.merge(value, field.get(x, y, z));
        return DynamicLightField.brightness(value, RgbSettings.projectsGameplayLight());
    }
    @SubscribeEvent public void unload(WorldEvent.Unload event) {
        if (event.getWorld().isRemote) return;
        State state = WORLDS.remove(event.getWorld());
        if (state != null) for (var source : state.sources.values()) if (source.pending != null) source.pending.cancel(false);
        DynamicLightNetwork.unload(event.getWorld());
    }
    @SubscribeEvent public void tick(TickEvent.WorldTickEvent event) {
        World world = event.world;
        if (world.isRemote || mode == Mode.OFF || event.phase != TickEvent.Phase.END) return;
        State state = WORLDS.computeIfAbsent(world, w -> new State());
        ++state.tick;
        Set<Integer> present = new HashSet<>();
        boolean changed = false;
        for (EntityPlayer player : world.playerEntities) {
            if (player.isDead || player.isSpectator()) continue;
            BlockPos pos = new BlockPos(player.posX, player.posY + player.getEyeHeight(), player.posZ);
            int emission;
            try {
                emission = DynamicLightField.merge(DynamicLightGeometry.held(world, pos, player.getHeldItemMainhand(), RgbMixinSelection.rgbEnabled()),
                        DynamicLightGeometry.held(world, pos, player.getHeldItemOffhand(), RgbMixinSelection.rgbEnabled()));
            } catch (RuntimeException exception) { continue; } // Unsupported item states are not light sources.
            if (emission == 0) continue;
            int id = player.getEntityId(); present.add(id);
            Source source = state.sources.computeIfAbsent(id, k -> new Source());
            if (!pos.equals(source.pos) || source.emission != emission) {
                boolean remove = source.emission != emission || source.field != null
                        && (Math.abs(source.field.x - pos.getX()) + Math.abs(source.field.y - pos.getY()) + Math.abs(source.field.z - pos.getZ()) > 2);
                source.pos = pos; source.emission = emission; source.dirty = true;
                if (source.pending != null) source.pending.cancel(false);
                source.pending = null;
                if (source.field != null && remove) {
                    source.field = null; source.revision = ++state.revision; changed = true;
                }
            }
        }
        for (var it = state.sources.entrySet().iterator(); it.hasNext();) {
            var entry = it.next();
            if (present.contains(entry.getKey())) continue;
            if (entry.getValue().pending != null) entry.getValue().pending.cancel(false);
            it.remove(); changed = true;
        }
        // Oldest-first scheduling prevents a moving player from monopolizing the capture budget.
        var scheduled = new ArrayList<>(state.sources.values());
        scheduled.sort(Comparator.comparingLong(source -> source.sampled));
        int captures = 0;
        long deadline = System.nanoTime() + 2_000_000L;
        for (Source source : scheduled) {
            if (source.pending != null && source.pending.isDone()) {
                try {
                    DynamicLightField next = source.pending.join();
                    if (!next.sameLight(source.field)) { source.field = next; source.revision = ++state.revision; changed = true; }
                }
                catch (CompletionException | CancellationException exception) { Pulsar.LOGGER.warn("Dynamic light propagation failed", exception); }
                source.pending = null;
            }
            // Refresh stationary lights too: doors, fluids, filters and palette changes are sampled afresh.
            if (source.pending != null || !source.dirty && state.tick - source.sampled < 10 || captures >= 2 || System.nanoTime() >= deadline) continue;
            try {
                var snapshot = DynamicLightGeometry.capture(world, source.pos, source.emission, RgbMixinSelection.rgbEnabled());
                source.pending = CompletableFuture.supplyAsync(snapshot::propagate, WORKER);
            } catch (RuntimeException exception) {
                if (source.field != null) { source.field = null; source.revision = ++state.revision; changed = true; }
                Pulsar.LOGGER.debug("Unable to sample a dynamic light source", exception);
            }
            source.sampled = state.tick; source.dirty = false; captures++;
        }
        if (changed) state.visible = state.sources.values().stream().map(source -> source.field).filter(Objects::nonNull).toList();
        Map<Integer, DynamicLightNetwork.Publication> publications = new HashMap<>();
        state.sources.forEach((id, source) -> { if (source.field != null)
            publications.put(id, new DynamicLightNetwork.Publication(source.revision, source.field)); });
        DynamicLightNetwork.publish(world, publications);
    }
}
