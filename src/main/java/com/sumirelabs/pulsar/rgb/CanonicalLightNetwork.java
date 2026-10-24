package com.sumirelabs.pulsar.rgb;

import com.sumirelabs.pulsar.Pulsar;
import io.netty.buffer.ByteBuf;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.network.NetworkManager;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.PlayerEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import net.minecraftforge.fml.common.network.NetworkRegistry;
import net.minecraftforge.fml.common.network.simpleimpl.*;
import net.minecraftforge.fml.relauncher.Side;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** Separate capability channel keeps older RGB protocol clients from seeing unknown discriminators. */
public final class CanonicalLightNetwork {
    private static final SimpleNetworkWrapper CHANNEL = NetworkRegistry.INSTANCE.newSimpleChannel("pulsar_light");
    private static final Set<NetworkManager> CLIENTS = ConcurrentHashMap.newKeySet();
    private static final class Pending {
        final EntityPlayerMP player; int ticks;
        Pending(EntityPlayerMP player) { this.player = player; }
    }
    private static final ConcurrentHashMap<NetworkManager, Pending> PENDING = new ConcurrentHashMap<>();
    private CanonicalLightNetwork() {}
    public static void initialize() {
        CHANNEL.registerMessage(HelloHandler.class, Hello.class, 0, Side.SERVER);
        CHANNEL.registerMessage(AckHandler.class, Ack.class, 1, Side.CLIENT);
        MinecraftForge.EVENT_BUS.register(new CanonicalLightNetwork());
    }
    public static void request() { CHANNEL.sendToServer(new Hello()); }
    public static boolean supported(EntityPlayerMP player) {
        return player.connection != null && CLIENTS.contains(player.connection.netManager);
    }
    public static final class Hello implements IMessage {
        int version = 1;
        public void toBytes(ByteBuf out) { out.writeInt(version); }
        public void fromBytes(ByteBuf in) {
            if (in.readableBytes() != 4) throw new IllegalArgumentException("Invalid canonical-light handshake");
            version = in.readInt();
        }
    }
    public static final class Ack implements IMessage {
        boolean enabled;
        public Ack() {}
        Ack(boolean enabled) { this.enabled = enabled; }
        public void toBytes(ByteBuf out) { out.writeBoolean(enabled); }
        public void fromBytes(ByteBuf in) {
            if (in.readableBytes() != 1) throw new IllegalArgumentException("Invalid canonical-light acknowledgement");
            enabled = in.readBoolean();
        }
    }
    public static final class HelloHandler implements IMessageHandler<Hello,IMessage> {
        public IMessage onMessage(Hello hello, MessageContext context) {
            var handler = context.getServerHandler(); EntityPlayerMP player = handler.player;
            player.getServerWorld().addScheduledTask(() -> {
                if (hello.version != 1 || player.connection != handler || !handler.netManager.isChannelOpen()) return;
                boolean enabled = RgbSettings.projectsGameplayLight();
                if (enabled) CLIENTS.add(handler.netManager); else CLIENTS.remove(handler.netManager);
                PENDING.remove(handler.netManager);
                CHANNEL.sendTo(new Ack(enabled), player);
                if (enabled) RgbNetwork.canonicalHello(player);
            });
            return null;
        }
    }
    public static final class AckHandler implements IMessageHandler<Ack,IMessage> {
        public IMessage onMessage(Ack ack, MessageContext context) {
            Pulsar.proxy.receiveCanonicalAuthority(ack.enabled, context.netHandler);
            return null;
        }
    }
    @SubscribeEvent public void logout(PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.player instanceof EntityPlayerMP player && player.connection != null) {
            CLIENTS.remove(player.connection.netManager); PENDING.remove(player.connection.netManager);
        }
    }
    @SubscribeEvent public void login(PlayerEvent.PlayerLoggedInEvent event) {
        if (RgbSettings.projectsGameplayLight() && event.player instanceof EntityPlayerMP player && player.connection != null)
            PENDING.put(player.connection.netManager, new Pending(player));
    }
    @SubscribeEvent public void tick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        if (!CLIENTS.isEmpty()) CLIENTS.removeIf(connection -> !connection.isChannelOpen());
        if (PENDING.isEmpty()) return;
        for (var entry : PENDING.entrySet()) {
            NetworkManager connection = entry.getKey(); Pending pending = entry.getValue();
            // RGB protocol v2 predates canonical authority. Color reception alone
            // does not stop an older client from recomputing vanilla brightness.
            if (!connection.isChannelOpen() || CLIENTS.contains(connection)) {
                PENDING.remove(connection, pending); continue;
            }
            if (++pending.ticks < 300) continue;
            PENDING.remove(connection, pending);
            if (pending.player.connection != null && pending.player.connection.netManager == connection)
                pending.player.connection.disconnect(new net.minecraft.util.text.TextComponentString(
                        "Pulsar RGB_MAX lighting requires a compatible Pulsar client. Use server SCALAR mode for vanilla clients."));
        }
    }
}
