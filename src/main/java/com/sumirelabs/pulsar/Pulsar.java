package com.sumirelabs.pulsar;

import com.sumirelabs.pulsar.command.CommandPulsar;
import com.sumirelabs.pulsar.compat.ThaumcraftCrystalLighting;
import com.sumirelabs.pulsar.config.PulsarConfig;
import com.sumirelabs.pulsar.light.engine.FaceOcclusion;
import com.sumirelabs.pulsar.proxy.IProxy;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.Loader;
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
        PulsarConfig.initialize();
        com.sumirelabs.pulsar.rgb.RgbSettings.initialize();
        LOGGER.info(
                "{} config: enabled={}, sendChunksWithoutLight={}, debugStats={}",
                Reference.MOD_NAME,
                PulsarConfig.enabled,
                PulsarConfig.features.sendChunksWithoutLight,
                PulsarConfig.debug.enableDebugStats);
        com.sumirelabs.pulsar.rgb.RgbNetwork.initialize();
        com.sumirelabs.pulsar.rgb.CanonicalLightNetwork.initialize();
        proxy.preInit(event);
    }

    @Mod.EventHandler
    public void init(FMLInitializationEvent event) {
        proxy.init(event);
    }

    @Mod.EventHandler
    public void postInit(FMLPostInitializationEvent event) {
        proxy.postInit(event);
        ThaumcraftCrystalLighting.initialize(Loader.isModLoaded("thaumcraft"),
                PulsarConfig.features.thaumcraftCrystalLightLevel);
        if (Loader.isModLoaded("thaumcraft")) {
            LOGGER.info("Thaumcraft crystal minimum block light: {} (0 preserves original emission)",
                    ThaumcraftCrystalLighting.cacheProfile());
        }
        com.sumirelabs.pulsar.api.BlockLightColors.defaults();
        com.sumirelabs.pulsar.api.BlockLightFilters.defaults();
        FaceOcclusion.registerDefaults();
        LOGGER.info("{} ready - Pulsar Lighting Engine active.", Reference.MOD_NAME);
    }

    @Mod.EventHandler
    public void loadComplete(net.minecraftforge.fml.common.event.FMLLoadCompleteEvent event) {
        com.sumirelabs.pulsar.api.BlockLightColors.modDefaults();
        net.minecraftforge.common.MinecraftForge.EVENT_BUS.post(
                new com.sumirelabs.pulsar.api.RegisterLightPropertiesEvent());
        if (com.sumirelabs.pulsar.core.RgbMixinSelection.rgbEnabled()) {
            com.sumirelabs.pulsar.api.BlockLightColors.interfaceDefaults();
            com.sumirelabs.pulsar.api.BlockLightFilters.interfaceDefaults();
            var home = net.minecraft.launchwrapper.Launch.minecraftHome;
            com.sumirelabs.pulsar.config.RgbPaletteConfig.load(
                    (home == null ? java.nio.file.Path.of(".") : home.toPath()).resolve("config"));
        }
        com.sumirelabs.pulsar.api.BlockLightColors.freeze();
    }

    @Mod.EventHandler
    public void serverStarting(FMLServerStartingEvent event) {
        event.registerServerCommand(new CommandPulsar());
        proxy.serverStarting(event);
    }
}
