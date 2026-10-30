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
    @SubscribeEvent(priority = net.minecraftforge.fml.common.eventhandler.EventPriority.HIGHEST)
    public void selectInitialLightingMode(net.minecraftforge.client.event.GuiOpenEvent event) {
        if (!com.sumirelabs.pulsar.config.PulsarConfig.lightingModeSelected
                && event.getGui() instanceof net.minecraft.client.gui.GuiMainMenu) {
            // Intercept the vanilla menu before menu-replacement mods. Returning to
            // it after selection lets those mods install their usual menu normally.
            event.setGui(new com.sumirelabs.pulsar.client.GuiLightingModeSelection(event.getGui()));
        }
    }

    @SubscribeEvent public void rgbWorldUnload(net.minecraftforge.event.world.WorldEvent.Unload event) {
        if (event.getWorld().isRemote) com.sumirelabs.pulsar.rgb.RgbClient.unload(event.getWorld());
    }
    @SubscribeEvent public void rgbChannels(net.minecraftforge.fml.common.network.FMLNetworkEvent.CustomPacketRegistrationEvent<?> event) {
        if (event.getSide() == net.minecraftforge.fml.relauncher.Side.CLIENT && event.getRegistrations().contains("pulsar_light"))
            com.sumirelabs.pulsar.rgb.CanonicalLightClient.serverHasChannel = event.getOperation().equals("REGISTER");
        if (event.getSide() == net.minecraftforge.fml.relauncher.Side.CLIENT && event.getRegistrations().contains("pulsar_rgb"))
            com.sumirelabs.pulsar.rgb.RgbClient.serverHasRgbChannel = event.getOperation().equals("REGISTER");
    }
    @SubscribeEvent public void rgbDisconnected(net.minecraftforge.fml.common.network.FMLNetworkEvent.ClientDisconnectionFromServerEvent event) {
        com.sumirelabs.pulsar.rgb.CanonicalLightClient.reset();
        com.sumirelabs.pulsar.rgb.RgbClient.serverHasRgbChannel = false;
    }

    @Override public void receiveRgb(com.sumirelabs.pulsar.rgb.RgbPacket packet, Object connection) {
        com.sumirelabs.pulsar.rgb.RgbClient.receive(packet, connection);
    }
    @Override public void receiveCanonicalAuthority(boolean enabled, Object connection) {
        com.sumirelabs.pulsar.rgb.CanonicalLightClient.acknowledge(enabled, connection);
    }
    @Override public boolean authoritativeClientLight(World world) {
        return com.sumirelabs.pulsar.rgb.CanonicalLightClient.active(world);
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
        com.sumirelabs.pulsar.light.LightUpdateTracer.tick(mc.world!=null);
        com.sumirelabs.pulsar.rgb.CanonicalLightClient.tick();
        if (mc.world == null) return;
        final WorldLightManager mgr = ((PulsarWorld) mc.world).pulsar$getLightManager();
        if (mgr != null) {
            mgr.processClientRenderUpdates();
        }
        if (com.sumirelabs.pulsar.core.RgbMixinSelection.rgbEnabled())
            com.sumirelabs.pulsar.rgb.RgbClient.tick();
    }
}
