package com.sumirelabs.pulsar.core;

import net.minecraft.launchwrapper.Launch;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;
import java.nio.file.Files;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/** Reads the restart-only switch before mixin application. SCALAR gets no RGB chunk fields or renderer hooks. */
public final class RgbMixinSelection implements IMixinConfigPlugin {
    private boolean rgb = true;
    private boolean optifine;
    private static boolean enabled;
    public static boolean rgbEnabled() { return enabled; }
    public void onLoad(String mixinPackage) {
        // Resource lookup only: defining an OptiFine/renderer class here can
        // load Minecraft targets before Mixins have been applied.
        ClassLoader loader = Launch.classLoader != null ? Launch.classLoader : getClass().getClassLoader();
        optifine = loader.getResource("net/optifine/reflect/ReflectorForge.class") != null;
        try {
            var home = Launch.minecraftHome == null ? new java.io.File(".") : Launch.minecraftHome;
            var path = home.toPath().resolve("config/pulsar.cfg");
            if (Files.isRegularFile(path)) rgb = selectsRgb(Files.readString(path));
            enabled = rgb;
        } catch (Exception e) { throw new IllegalStateException("Cannot read Pulsar RGB mode", e); }
    }
    public static boolean selectsRgb(String config) {
        // Missing/old versions are regenerated before Forge reads settings. Match
        // that new-file default here, before any RGB mixins can be selected.
        if (!Pattern.compile("(?m)^\\s*I:configVersion=2\\s*$").matcher(config).find()) return true;
        return !Pattern.compile("(?m)^\\s*S:lightingMode=SCALAR\\s*$").matcher(config).find();
    }
    public boolean shouldApplyMixin(String target, String mixin) {
        String name = mixin.substring(mixin.lastIndexOf('.') + 1);
        // OptiFine G5 replaces the vanilla renderQuadsSmooth descriptor with
        // a RenderEnv overload. Leave its partial-face path to OptiFine; keep
        // strict injection requirements and all other corrections unchanged.
        if (optifine && name.equals("MixinPartialBlockModelLight")) return false;
        return !name.startsWith("MixinRgb") || rgb;
    }
    public String getRefMapperConfig() { return null; }
    public void acceptTargets(Set<String> mine, Set<String> others) {}
    public List<String> getMixins() { return null; }
    public void preApply(String target, ClassNode node, String mixin, IMixinInfo info) {}
    public void postApply(String target, ClassNode node, String mixin, IMixinInfo info) {}
}
