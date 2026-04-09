package com.sumirelabs.pulsar.proxy;

import com.sumirelabs.pulsar.world.PulsarWorld;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.world.WorldEvent;
import net.minecraftforge.fml.common.event.FMLPreInitializationEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;

public class CommonProxy implements IProxy {

    @Override
    public void preInit(final FMLPreInitializationEvent event) {
        MinecraftForge.EVENT_BUS.register(this);
    }

    @SubscribeEvent
    public void onWorldUnload(final WorldEvent.Unload event) {
        if (event.getWorld() instanceof PulsarWorld) {
            ((PulsarWorld) event.getWorld()).pulsar$shutdown();
        }
    }
}
