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
 * <p>Implements {@link IFMLLoadingPlugin} so Forge sees Pulsar as a coremod
 * and applies the {@code FMLAT} access transformer plus the
 * {@code FMLCorePluginContainsFMLMod} attachment. CleanMix 0.6.x discovers
 * the Pulsar mixin config natively through the jar manifest (and through
 * {@code crl.dev.mixin} in development runs); {@link IEarlyMixinLoader} is
 * retained as a compatibility fallback for Cleanroom 0.5.x.
 *
 * <p>This mirrors REID's {@code JEIDLoadingPlugin} structure — REID
 * coexistence with Pulsar relies on both mods being available during the
 * early transformation phase.
 */
public class PulsarLoadingPlugin implements IFMLLoadingPlugin, IEarlyMixinLoader {

    @Override
    public List<String> getMixinConfigs() {
        return ImmutableList.of("pulsar.mixin.json");
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
