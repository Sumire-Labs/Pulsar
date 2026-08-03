package com.sumirelabs.pulsar.core;

import net.minecraft.launchwrapper.Launch;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import java.util.Set;

/**
 * Gates config-controlled mixins at APPLICATION time. Mixins are woven long
 * before Forge's config system loads, so the flag is read from
 * {@code config/pulsar.cfg} directly (a missing file or key means the
 * default: disabled). Restart-only by nature — the matching config entries
 * are annotated {@code @RequiresMcRestart}.
 */
public final class PulsarMixinPlugin implements IMixinConfigPlugin {

    private static final Logger LOGGER = LogManager.getLogger("Pulsar");

    private Boolean celeritasDirectionalMeshLight;

    @Override
    public boolean shouldApplyMixin(final String targetClassName, final String mixinClassName) {
        if (mixinClassName.endsWith(".MixinCeleritasWorldSlice")) {
            if (this.celeritasDirectionalMeshLight == null) {
                this.celeritasDirectionalMeshLight = readBooleanFlag("celeritasDirectionalMeshLight");
                LOGGER.info("Celeritas directional mesh light (MC-92 in WorldSlice): {}",
                        this.celeritasDirectionalMeshLight ? "ENABLED" : "disabled (default; compat.celeritasDirectionalMeshLight)");
            }
            return this.celeritasDirectionalMeshLight;
        }
        return true;
    }

    /**
     * Minimal scan of Forge's cfg format for {@code B:<key>=true|false}.
     * Any parse problem falls back to {@code false} (the shipped default).
     */
    private static boolean readBooleanFlag(final String key) {
        try {
            final File home = Launch.minecraftHome != null ? Launch.minecraftHome : new File(".");
            final File cfg = new File(home, "config/pulsar.cfg");
            if (!cfg.isFile()) {
                return false;
            }
            final String prefix = "B:" + key + "=";
            for (final String rawLine : Files.readAllLines(cfg.toPath(), StandardCharsets.UTF_8)) {
                final String line = rawLine.trim();
                if (line.startsWith(prefix)) {
                    return Boolean.parseBoolean(line.substring(prefix.length()).trim());
                }
            }
        } catch (final Throwable t) {
            LOGGER.warn("Could not read config/pulsar.cfg early; Celeritas mesh-light mixin stays disabled", t);
        }
        return false;
    }

    @Override
    public void onLoad(final String mixinPackage) {
    }

    @Override
    public String getRefMapperConfig() {
        return null;
    }

    @Override
    public void acceptTargets(final Set<String> myTargets, final Set<String> otherTargets) {
    }

    @Override
    public List<String> getMixins() {
        return null;
    }

    @Override
    public void preApply(final String targetClassName, final ClassNode targetClass, final String mixinClassName, final IMixinInfo mixinInfo) {
    }

    @Override
    public void postApply(final String targetClassName, final ClassNode targetClass, final String mixinClassName, final IMixinInfo mixinInfo) {
    }
}
