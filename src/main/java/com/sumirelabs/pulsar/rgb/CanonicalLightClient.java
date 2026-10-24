package com.sumirelabs.pulsar.rgb;

import net.minecraft.client.Minecraft;
import net.minecraft.client.network.NetHandlerPlayClient;
import net.minecraft.world.World;

/** Canonical authority needs no RGB chunk fields, buffers or renderer mixins in a SCALAR client. */
public final class CanonicalLightClient {
    public static volatile boolean serverHasChannel;
    private static volatile NetHandlerPlayClient requested;
    private static volatile NetHandlerPlayClient authoritative;
    private CanonicalLightClient() {}
    public static void reset() { requested = null; authoritative = null; serverHasChannel = false; }
    public static void tick() {
        NetHandlerPlayClient current = Minecraft.getMinecraft().getConnection();
        if (authoritative != null && authoritative != current) authoritative = null;
        if (current != null && serverHasChannel && requested != current) {
            requested = current; CanonicalLightNetwork.request();
        }
        if (current == null) requested = null;
    }
    public static void acknowledge(boolean enabled, Object connection) {
        Minecraft mc = Minecraft.getMinecraft();
        mc.addScheduledTask(() -> {
            if (connection != mc.getConnection()) return;
            authoritative = enabled ? (NetHandlerPlayClient)connection : null;
        });
    }
    public static boolean active(World world) {
        return authoritative != null && authoritative == Minecraft.getMinecraft().getConnection()
                && world == Minecraft.getMinecraft().world;
    }
}
