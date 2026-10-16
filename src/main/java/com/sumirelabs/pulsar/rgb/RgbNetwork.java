package com.sumirelabs.pulsar.rgb;

import com.sumirelabs.pulsar.Pulsar;
import com.sumirelabs.pulsar.config.PulsarConfig;
import com.sumirelabs.pulsar.light.WorldLightManager;
import com.sumirelabs.pulsar.world.PulsarWorld;
import com.sumirelabs.pulsar.util.CoordinateUtils;
import net.minecraft.world.World;
import net.minecraft.world.WorldServer;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.world.WorldEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import net.minecraftforge.fml.common.network.NetworkRegistry;
import net.minecraftforge.fml.common.network.simpleimpl.*;
import net.minecraftforge.fml.relauncher.Side;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public final class RgbNetwork {
    private static final SimpleNetworkWrapper CHANNEL = NetworkRegistry.INSTANCE.newSimpleChannel("pulsar_rgb");
    private record Change(Chunk chunk, RgbSnapshot snapshot, RgbSnapshot sky) {}
    private static final Map<World, ConcurrentHashMap<Long, Change>> DIRTY = new ConcurrentHashMap<>();
    private static final Set<EntityPlayerMP> CLIENTS = Collections.newSetFromMap(new IdentityHashMap<>());
    public static void initialize() {
        CHANNEL.registerMessage(Handler.class, RgbPacket.class, 0, Side.CLIENT);
        CHANNEL.registerMessage(HelloHandler.class, RgbHello.class, 1, Side.SERVER);
        MinecraftForge.EVENT_BUS.register(new RgbNetwork());
    }
    public static final class Handler implements IMessageHandler<RgbPacket, IMessage> {
        public IMessage onMessage(RgbPacket message, MessageContext context) {
            Pulsar.proxy.receiveRgb(message, context.netHandler);
            return null;
        }
    }
    public static void changed(Chunk chunk) {
        DIRTY.computeIfAbsent(chunk.getWorld(), w -> new ConcurrentHashMap<>())
                .put(CoordinateUtils.getChunkKey(chunk.x, chunk.z), new Change(chunk, ((RgbChunk) chunk).pulsar$getRgbSnapshot(), ((RgbChunk) chunk).pulsar$getRgbSkySnapshot()));
    }
    public static void requestColors() { CHANNEL.sendToServer(new RgbHello()); }
    public static final class HelloHandler implements IMessageHandler<RgbHello, IMessage> {
        public IMessage onMessage(RgbHello hello, MessageContext context) {
            EntityPlayerMP player = context.getServerHandler().player;
            player.getServerWorld().addScheduledTask(() -> {
                if (context.getServerHandler().player != player || hello.version != RgbHello.VERSION || !com.sumirelabs.pulsar.core.RgbMixinSelection.rgbEnabled()
                        || !player.connection.netManager.isChannelOpen()) return;
                CLIENTS.removeIf(p -> p != player && p.connection.netManager == player.connection.netManager);
                CLIENTS.add(player);
                WorldServer world = player.getServerWorld();
                WorldLightManager manager = ((PulsarWorld) world).pulsar$getLightManager();
                if (manager == null) return;
                int radius = world.getMinecraftServer().getPlayerList().getViewDistance();
                int cx = player.getPosition().getX() >> 4, cz = player.getPosition().getZ() >> 4;
                for (int x = cx - radius; x <= cx + radius; x++) for (int z = cz - radius; z <= cz + radius; z++) {
                    var entry = world.getPlayerChunkMap().getEntry(x, z);
                    if (entry == null || !entry.getWatchingPlayers().contains(player) || manager.isPendingChunkWatch(entry, player)) continue;
                    Chunk chunk = entry.getChunk();
                    if (chunk == null) continue;
                    if (manager.canSendUpdatedChunkLight(x, z)) send(player, chunk); else changed(chunk);
                }
            });
            return null;
        }
    }
    private static boolean supported(EntityPlayerMP player) { return CLIENTS.contains(player); }
    private static void send(EntityPlayerMP player, Chunk chunk) {
        if (!supported(player)) return;
        RgbChunk rgb = (RgbChunk) chunk;
        RgbSnapshot block = rgb.pulsar$getRgbSnapshot(), sky = rgb.pulsar$getRgbSkySnapshot();
        if (block != null) CHANNEL.sendTo(new RgbPacket(chunk.getWorld().provider.getDimension(), chunk.x, chunk.z, block, false), player);
        if (sky != null) CHANNEL.sendTo(new RgbPacket(chunk.getWorld().provider.getDimension(), chunk.x, chunk.z, sky, true), player);
    }
    public static void afterChunkPacket(EntityPlayerMP player, int x, int z) {
        // Some mods send from other threads. Preserve vanilla-before-RGB order
        // while resolving live World and readiness only on the world thread.
        WorldServer world = player.getServerWorld();
        Runnable action = () -> {
            if (!supported(player) || player.world != world || !player.connection.netManager.isChannelOpen()) return;
            Chunk chunk = world.getChunkProvider().getLoadedChunk(x, z);
            WorldLightManager manager = ((PulsarWorld) world).pulsar$getLightManager();
            if (chunk != null && manager != null) {
                if (manager.canSendUpdatedChunkLight(x, z)) send(player, chunk); else changed(chunk);
            }
        };
        if (world.isCallingFromMinecraftThread()) action.run(); else world.addScheduledTask(action);
    }
    @SubscribeEvent public void beforeServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.START || !com.sumirelabs.pulsar.core.RgbMixinSelection.rgbEnabled()) return;
        var server = net.minecraftforge.fml.common.FMLCommonHandler.instance().getMinecraftServerInstance();
        if (server == null || server.worlds == null) return;
        // RGB tasks can outlive the previous world's tick tail. Retry before
        // the next tick adds more work; scalar-only worlds retain their old path.
        for (WorldServer world : server.worlds) if (world instanceof PulsarWorld pulsar) {
            WorldLightManager manager = pulsar.pulsar$getLightManager();
            if (manager != null) manager.processDeferredChunkUpdates();
        }
    }
    @SubscribeEvent public void tick(TickEvent.WorldTickEvent event) {
        if (event.phase != TickEvent.Phase.END || !(event.world instanceof WorldServer world)) return;
        Map<Long, Change> dirty = DIRTY.get(world);
        if (dirty == null || dirty.isEmpty()) return;
        WorldLightManager manager = ((PulsarWorld) world).pulsar$getLightManager();
        if (manager == null) return;
        for (var entry : dirty.entrySet()) {
            Chunk chunk = entry.getValue().chunk();
            if (world.getChunkProvider().getLoadedChunk(chunk.x, chunk.z) != chunk) { dirty.remove(entry.getKey(), entry.getValue()); continue; }
            // A published RGB snapshot already contains all three completed channels.
            // Waiting for unrelated later work can starve color updates indefinitely.
            if (!((com.sumirelabs.pulsar.light.PulsarChunk) chunk).pulsar$isLightReady()) continue;
            if (!dirty.remove(entry.getKey(), entry.getValue())) continue;
            var watchers = world.getPlayerChunkMap().getEntry(chunk.x, chunk.z);
            if (watchers != null && watchers.getChunk() == chunk)
                for (EntityPlayerMP player : watchers.getWatchingPlayers())
                    if (!manager.isPendingChunkWatch(watchers, player) && player.connection.netManager.isChannelOpen()) send(player, chunk);
        }
    }
    @SubscribeEvent public void unload(WorldEvent.Unload event) {
        if (event.getWorld().isRemote) return;
        DIRTY.remove(event.getWorld());
    }
    @SubscribeEvent public void logout(net.minecraftforge.fml.common.gameevent.PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.player instanceof EntityPlayerMP player)
            CLIENTS.removeIf(p -> p.connection.netManager == player.connection.netManager || !p.connection.netManager.isChannelOpen());
    }
}
