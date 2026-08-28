package com.sumirelabs.pulsar.core;

import net.minecraftforge.fml.relauncher.IFMLLoadingPlugin;
import org.jetbrains.annotations.Nullable;

import java.util.Map;

/**
 * No-op FML coremod entry point retained to match CleanroomModTemplate's
 * coremod layout.
 *
 * <p>This class does not register an ASM transformer or a mixin config.
 * CleanMix discovers {@code pulsar.mixin.json} through the jar's
 * {@code MixinConfigs} manifest attribute, or through {@code crl.dev.mixin}
 * in development runs. The access transformer is declared separately in the
 * jar manifest and the Unimined development configuration.
 */
public class PulsarLoadingPlugin implements IFMLLoadingPlugin {

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
