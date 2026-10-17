package com.sumirelabs.pulsar.proxy;

import com.sumirelabs.pulsar.light.LightDataSerializer;
import com.sumirelabs.pulsar.light.WorldLightManager;
import com.sumirelabs.pulsar.world.PulsarWorld;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.world.WorldEvent;
import net.minecraftforge.fml.common.event.FMLPreInitializationEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;

public class CommonProxy implements IProxy {

    @Override
    public void preInit(final FMLPreInitializationEvent event) {
        MinecraftForge.EVENT_BUS.register(this);
        MinecraftForge.EVENT_BUS.register(new LightDataSerializer());
    }

    @SubscribeEvent
    public void onServerTick(final TickEvent.ServerTickEvent event) {
        if(event.phase!=TickEvent.Phase.START)return;
        final var server=net.minecraftforge.fml.common.FMLCommonHandler.instance().getMinecraftServerInstance();
        if(server==null || server.worlds==null)return;
        for(final var world:server.worlds)if(world instanceof PulsarWorld pulsar) {
            final WorldLightManager manager=pulsar.pulsar$getLightManager();
            if(manager!=null)manager.retryInitialChunkSends();
        }
    }

    @SubscribeEvent
    public void onWorldTick(final TickEvent.WorldTickEvent event) {
        if (event.world.isRemote || !(event.world instanceof PulsarWorld)) {
            return;
        }
        final WorldLightManager manager = ((PulsarWorld) event.world).pulsar$getLightManager();
        if (manager == null) {
            return;
        }
        if (event.phase == TickEvent.Phase.START) {
            manager.beginTickUnloadWaitBudget();
        } else if (event.phase == TickEvent.Phase.END) {
            manager.publishContextualLight();
            manager.tickServerStats();
        }
    }

    @SubscribeEvent
    public void onWorldUnload(final WorldEvent.Unload event) {
        if (event.getWorld() instanceof PulsarWorld) {
            ((PulsarWorld) event.getWorld()).pulsar$shutdown();
        }
    }
}
