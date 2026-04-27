package com.sumirelabs.pulsar.proxy;

import net.minecraft.world.World;
import net.minecraft.world.WorldServer;
import net.minecraftforge.fml.common.event.FMLInitializationEvent;
import net.minecraftforge.fml.common.event.FMLPostInitializationEvent;
import net.minecraftforge.fml.common.event.FMLPreInitializationEvent;
import net.minecraftforge.fml.common.event.FMLServerStartingEvent;

public interface IProxy {
    default void preInit(FMLPreInitializationEvent event) {}

    default void init(FMLInitializationEvent event) {}

    default void postInit(FMLPostInitializationEvent event) {}

    default void serverStarting(FMLServerStartingEvent event) {}

    // Reject GregTech WorldSceneRenderer / JEI multiblock-preview fake worlds.
    // Default (dedicated server): only WorldServer instances are real.
    default boolean isRealMainWorld(final World world) {
        return world instanceof WorldServer;
    }
}
