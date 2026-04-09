package com.sumirelabs.pulsar.core;

import com.google.common.collect.ImmutableList;
import net.minecraftforge.fml.relauncher.IFMLLoadingPlugin;
import org.jetbrains.annotations.Nullable;
import zone.rong.mixinbooter.IEarlyMixinLoader;

import java.util.List;
import java.util.Map;

/**
 * FML coremod entry point for Pulsar.
 *
 * <p>Implements both {@link IFMLLoadingPlugin} (so Forge sees Pulsar as a
 * coremod and applies the {@code FMLAT} access transformer + the
 * {@code FMLCorePluginContainsFMLMod} attachment) and
 * {@link IEarlyMixinLoader} (so mixinbooter on Cleanroom queues the Pulsar
 * mixin config during the early phase, before mods are loaded).
 *
 * <p>This mirrors REID's {@code JEIDLoadingPlugin} structure — REID
 * coexistence with Pulsar relies on both mods being early-loaded through
 * mixinbooter.
 */
public class PulsarLoadingPlugin implements IFMLLoadingPlugin, IEarlyMixinLoader {

    @Override
    public List<String> getMixinConfigs() {
        return ImmutableList.of("pulsar.default.mixin.json");
    }

    @Override
    public @Nullable String[] getASMTransformerClass() {
        return new String[0];
    }

    @Override
    public @Nullable String getModContainerClass() {
        return null;
    }

    @Override
    public @Nullable String getSetupClass() {
        return null;
    }

    @Override
    public void injectData(Map<String, Object> map) {
        // No-op.
    }

    @Override
    public @Nullable String getAccessTransformerClass() {
        return null;
    }
}
