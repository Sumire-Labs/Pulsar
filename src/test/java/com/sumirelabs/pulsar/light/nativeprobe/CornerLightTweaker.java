package com.sumirelabs.pulsar.light.nativeprobe;

import com.cleanroommc.common.CleanroomEnvironment;
import com.llamalad7.mixinextras.MixinExtrasBootstrap;
import net.minecraft.launchwrapper.ITweaker;
import net.minecraft.launchwrapper.Launch;
import net.minecraft.launchwrapper.LaunchClassLoader;
import net.minecraftforge.fml.relauncher.Side;
import org.spongepowered.asm.launch.MixinBootstrap;
import org.spongepowered.asm.mixin.MixinEnvironment;
import org.spongepowered.asm.mixin.Mixins;

import java.io.File;
import java.util.HashMap;
import java.util.List;

/** Minimal Cleanroom launch for testing transformed lighting without starting the game. */
public final class CornerLightTweaker implements ITweaker {
    public CornerLightTweaker() {
        CleanroomEnvironment.setSide(Side.CLIENT);
        Launch.blackboard.put("forgeLaunchArgs", new HashMap<String, String>());
    }

    public void acceptOptions(List<String> args, File game, File assets, String version) {}

    public void injectIntoClassLoader(LaunchClassLoader loader) {
        MixinBootstrap.init();
        MixinExtrasBootstrap.init();
        MixinEnvironment.getDefaultEnvironment().setSide(MixinEnvironment.Side.CLIENT);
        if (Boolean.getBoolean("issue20.celeritas")) {
            Mixins.addConfiguration("pulsar-surface-celeritas-quads.mixins.json");
        }
        if (Boolean.getBoolean("issue20.previous")) {
            Mixins.addConfiguration("pulsar-previous-corners-test.mixins.json");
            Mixins.addConfiguration("pulsar-previous-forge-test.mixins.json");
        } else if (!Boolean.getBoolean("issue20.baseline")) {
            Mixins.addConfiguration("pulsar-smoothlight-test.mixins.json");
        }
    }

    public String getLaunchTarget() {
        return "com.sumirelabs.pulsar.light.nativeprobe.CornerLightProbe";
    }

    public String[] getLaunchArguments() { return new String[0]; }
}
