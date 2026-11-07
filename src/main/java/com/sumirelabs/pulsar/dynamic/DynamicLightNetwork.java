package com.sumirelabs.pulsar.dynamic;

import com.sumirelabs.pulsar.Pulsar;
import io.netty.buffer.ByteBuf;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.network.NetworkManager;
import net.minecraft.world.World;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.PlayerEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import net.minecraftforge.fml.common.network.NetworkRegistry;
import net.minecraftforge.fml.common.network.simpleimpl.*;
import net.minecraftforge.fml.relauncher.Side;
import java.util.*;

public final class DynamicLightNetwork {
    // Cold shader compilation can block the client's world ticker for well over 15 seconds.
    // Keep a finite compatibility deadline without rejecting a supported client during initialization.
    static final int HANDSHAKE_TIMEOUT_TICKS = 20 * 120;
    private static final SimpleNetworkWrapper CHANNEL = NetworkRegistry.INSTANCE.newSimpleChannel("pulsar_dynamic");
    private static final Map<NetworkManager, Client> CLIENTS = new IdentityHashMap<>();
    private static final Map<EntityPlayerMP, Integer> PENDING = new IdentityHashMap<>();
    record Publication(long revision, DynamicLightField field) {}
    private static final class Client {
        final EntityPlayerMP player; final World world; final long session;
        final Map<Integer, Long> sent = new HashMap<>();
        long sequence;
        Client(EntityPlayerMP player, long session) { this.player = player; world = player.world; this.session = session; }
        void send(int id, DynamicLightField field) {
            CHANNEL.sendTo(new DynamicLightPacket(session, world.provider.getDimension(), id, ++sequence, field), player);
        }
    }
    private DynamicLightNetwork() {}
    public static void initialize() {
        CHANNEL.registerMessage(HelloHandler.class, Hello.class, 0, Side.SERVER);
        CHANNEL.registerMessage(FieldHandler.class, DynamicLightPacket.class, 1, Side.CLIENT);
        MinecraftForge.EVENT_BUS.register(new DynamicLightNetwork());
    }
    public static void request(long session, int dimension) { CHANNEL.sendToServer(new Hello(session, dimension)); }
    public static final class Hello implements IMessage {
        int version = 2, dimension; long session;
        public Hello() {}
        Hello(long session, int dimension) { this.session = session; this.dimension = dimension; }
        public void toBytes(ByteBuf out) { out.writeInt(version).writeLong(session).writeInt(dimension); }
        public void fromBytes(ByteBuf in) {
            if (in.readableBytes() != 16) throw new IllegalArgumentException("Invalid dynamic light handshake");
            version = in.readInt(); session = in.readLong(); dimension = in.readInt();
        }
    }
    public static final class HelloHandler implements IMessageHandler<Hello, IMessage> {
        public IMessage onMessage(Hello hello, MessageContext context) {
            var handler = context.getServerHandler();
            handler.player.getServerWorld().addScheduledTask(() -> {
                var player = handler.player;
                if (hello.version != 2 || player.connection != handler || !handler.netManager.isChannelOpen()
                        || player.dimension != hello.dimension) return;
                Client existing = CLIENTS.get(handler.netManager);
                if (existing != null && existing.session == hello.session && existing.player == player && existing.world == player.world) {
                    existing.send(Integer.MIN_VALUE, null); return;
                }
                Client client = new Client(player, hello.session);
                CLIENTS.put(handler.netManager, client); PENDING.remove(player);
                client.send(Integer.MIN_VALUE, null);
            });
            return null;
        }
    }
    public static final class FieldHandler implements IMessageHandler<DynamicLightPacket, IMessage> {
        public IMessage onMessage(DynamicLightPacket packet, MessageContext context) {
            Pulsar.proxy.receiveDynamicLight(packet, context.netHandler); return null;
        }
    }
    static void publish(World world, Map<Integer, Publication> fields) {
        for (Client client : CLIENTS.values()) {
            if (client.world != world || client.player.world != world) continue;
            Set<Integer> wanted = new HashSet<>();
            int distance = (client.player.getServerWorld().getMinecraftServer().getPlayerList().getViewDistance() + 2) * 16;
            fields.forEach((id, publication) -> {
                var field = publication.field();
                if (Math.abs(field.x - client.player.posX) > distance || Math.abs(field.z - client.player.posZ) > distance) return;
                wanted.add(id);
                if (Objects.equals(client.sent.get(id), publication.revision())) return;
                client.send(id, field); client.sent.put(id, publication.revision());
            });
            for (var it = client.sent.keySet().iterator(); it.hasNext();) {
                int id = it.next();
                if (!wanted.contains(id)) { client.send(id, null); it.remove(); }
            }
        }
    }
    static void unload(World world) { CLIENTS.values().removeIf(client -> client.world == world); PENDING.keySet().removeIf(player -> player.world == world); }
    @SubscribeEvent public void login(PlayerEvent.PlayerLoggedInEvent event) {
        if (DynamicLights.mode() != DynamicLights.Mode.OFF && event.player instanceof EntityPlayerMP player) PENDING.put(player, 0);
    }
    @SubscribeEvent public void logout(PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.player instanceof EntityPlayerMP player) { PENDING.remove(player); CLIENTS.remove(player.connection.netManager); }
    }
    @SubscribeEvent public void tick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        CLIENTS.entrySet().removeIf(entry -> !entry.getKey().isChannelOpen() || entry.getValue().player.world != entry.getValue().world);
        for (var it = PENDING.entrySet().iterator(); it.hasNext();) {
            var entry = it.next(); var player = entry.getKey();
            if (!player.connection.netManager.isChannelOpen() || CLIENTS.containsKey(player.connection.netManager)) { it.remove(); continue; }
            if (entry.getValue() < HANDSHAKE_TIMEOUT_TICKS) { entry.setValue(entry.getValue() + 1); continue; }
            it.remove();
            player.connection.disconnect(new net.minecraft.util.text.TextComponentString(
                    "This server requires a Pulsar client supporting dynamic lights. Server dynamicLights.mode can be set to OFF for older clients."));
        }
    }
}
