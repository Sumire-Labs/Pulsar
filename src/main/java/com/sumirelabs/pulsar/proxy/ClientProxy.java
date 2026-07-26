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
     * so queued block changes (including the player's own place/break) are
     * lit and render-marked within the same frame.
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
    }
}
