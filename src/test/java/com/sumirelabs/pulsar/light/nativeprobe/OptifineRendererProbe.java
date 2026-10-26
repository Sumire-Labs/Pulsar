package com.sumirelabs.pulsar.light.nativeprobe;

import java.util.Arrays;
import net.minecraft.init.Bootstrap;

/** Load actual Mixin-transformed renderer classes, without a display or world. */
public final class OptifineRendererProbe {
    public static void main(String[] args) throws Exception {
        Bootstrap.register();
        boolean replaced = Boolean.getBoolean("issue26.replacedRenderer");
        boolean rgb = System.getProperty("issue26.mode").equals("RGB");
        if (com.sumirelabs.pulsar.core.RgbMixinSelection.rgbEnabled() != rgb)
            throw new AssertionError("RGB/SCALAR selection changed");
        Class<?> renderer = Class.forName("net.minecraft.client.renderer.BlockModelRenderer");
        boolean partial = Arrays.stream(renderer.getDeclaredMethods())
                .anyMatch(method -> method.getName().contains("pulsar$lightActualSurface"));
        if (partial == replaced) throw new AssertionError("Partial-face selection: " + partial);
        boolean ao = Arrays.stream(renderer.getDeclaredMethods())
                .anyMatch(method -> method.getName().contains("pulsar$adjustAmbientOcclusionLightValue"));
        if (!ao) throw new AssertionError("Unrelated AO redirect must remain enabled");
        Class<?> corners = Class.forName("net.minecraft.client.renderer.BlockModelRenderer$AmbientOcclusionFace");
        if (Arrays.stream(corners.getDeclaredMethods()).noneMatch(method -> method.getName().contains("pulsar$beginFace")))
            throw new AssertionError("Corner-light correction must remain enabled");
        System.out.println("OPTIFINE_SHAPE_PASS replaced=" + replaced);
    }
}
