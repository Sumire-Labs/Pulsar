package com.sumirelabs.pulsar.proxy;

import com.sumirelabs.pulsar.light.WorldLightManager;
import com.sumirelabs.pulsar.world.PulsarWorld;
import net.minecraft.client.Minecraft;
import net.minecraft.world.World;
import net.minecraft.world.WorldServer;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.common.event.FMLPreInitializationEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;

public class ClientProxy extends CommonProxy {
    @SubscribeEvent public void rgbWorldUnload(net.minecraftforge.event.world.WorldEvent.Unload event) {
        if (event.getWorld().isRemote) com.sumirelabs.pulsar.rgb.RgbClient.unload(event.getWorld());
    }
    @SubscribeEvent public void rgbChannels(net.minecraftforge.fml.common.network.FMLNetworkEvent.CustomPacketRegistrationEvent<?> event) {
        if (event.getSide() == net.minecraftforge.fml.relauncher.Side.CLIENT && event.getRegistrations().contains("pulsar_rgb"))
            com.sumirelabs.pulsar.rgb.RgbClient.serverHasRgbChannel = event.getOperation().equals("REGISTER");
    }
    @SubscribeEvent public void rgbDisconnected(net.minecraftforge.fml.common.network.FMLNetworkEvent.ClientDisconnectionFromServerEvent event) {
        com.sumirelabs.pulsar.rgb.RgbClient.serverHasRgbChannel = false;
    }

    @Override public void receiveRgb(com.sumirelabs.pulsar.rgb.RgbPacket packet, Object connection) {
        com.sumirelabs.pulsar.rgb.RgbClient.receive(packet, connection);
    }


    @Override
    public void preInit(final FMLPreInitializationEvent event) {
        super.preInit(event);
        MinecraftForge.EVENT_BUS.register(this);
        net.minecraftforge.client.ClientCommandHandler.instance.registerCommand(
                new com.sumirelabs.pulsar.command.CommandPulsarClient());
    }

    @Override
    public boolean isRealMainWorld(final World world) {
        if (world instanceof WorldServer) return true;
        return Minecraft.getMinecraft().world == world;
    }

    /**
     * Thin-client drive: drain Pulsar's light queues on the main thread at
     * the end of each client tick. This runs before the tick's frame renders,
     * with a shared time budget. Unfinished tasks remain queued for the next
     * tick, keeping large packet bursts from draining without a deadline.
     */
    @SubscribeEvent
    public void onClientTick(final TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        final Minecraft mc = Minecraft.getMinecraft();
        if (mc.world == null) return;
        final WorldLightManager mgr = ((PulsarWorld) mc.world).pulsar$getLightManager();
        if (mgr != null) {
            mgr.processClientRenderUpdates();
        }
        if (com.sumirelabs.pulsar.core.RgbMixinSelection.rgbEnabled())
            com.sumirelabs.pulsar.rgb.RgbClient.tick();
    }
}
