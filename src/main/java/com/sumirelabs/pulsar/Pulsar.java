package com.sumirelabs.pulsar;

import com.sumirelabs.pulsar.command.CommandPulsar;
import com.sumirelabs.pulsar.config.PulsarConfig;
import com.sumirelabs.pulsar.light.engine.FaceOcclusion;
import com.sumirelabs.pulsar.proxy.IProxy;
import net.minecraftforge.common.config.ConfigManager;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.SidedProxy;
import net.minecraftforge.fml.common.event.FMLInitializationEvent;
import net.minecraftforge.fml.common.event.FMLPostInitializationEvent;
import net.minecraftforge.fml.common.event.FMLPreInitializationEvent;
import net.minecraftforge.fml.common.event.FMLServerStartingEvent;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

@Mod(modid = Reference.MOD_ID, name = Reference.MOD_NAME, version = Reference.VERSION, acceptableRemoteVersions = "*")
public class Pulsar {

    public static final Logger LOGGER = LogManager.getLogger(Reference.MOD_NAME);

    @SidedProxy(
            modId = Reference.MOD_ID,
            clientSide = "com.sumirelabs.pulsar.proxy.ClientProxy",
            serverSide = "com.sumirelabs.pulsar.proxy.CommonProxy"
    )
    public static IProxy proxy;

    @Mod.EventHandler
    public void preInit(FMLPreInitializationEvent event) {
        LOGGER.info("{} {} preInit - loading config", Reference.MOD_NAME, Reference.VERSION);
        ConfigManager.register(PulsarConfig.class);
        LOGGER.info(
                "{} config: enabled={}, sendChunksWithoutLight={}, trackPlayerAction={}, debugStats={}",
                Reference.MOD_NAME,
                PulsarConfig.enabled,
                PulsarConfig.features.sendChunksWithoutLight,
                PulsarConfig.features.trackPlayerAction,
                PulsarConfig.debug.enableDebugStats);
        proxy.preInit(event);
    }

    @Mod.EventHandler
    public void init(FMLInitializationEvent event) {
        proxy.init(event);
    }

    @Mod.EventHandler
    public void postInit(FMLPostInitializationEvent event) {
        proxy.postInit(event);
        FaceOcclusion.registerDefaults();
        LOGGER.info("{} ready - scalar BFS lighting engine active.", Reference.MOD_NAME);
    }

    @Mod.EventHandler
    public void serverStarting(FMLServerStartingEvent event) {
        event.registerServerCommand(new CommandPulsar());
        proxy.serverStarting(event);
    }
}
