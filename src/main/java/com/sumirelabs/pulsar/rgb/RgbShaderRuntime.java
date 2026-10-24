/* SPDX-License-Identifier: LGPL-3.0-only */
package com.sumirelabs.pulsar.rgb;

import com.sumirelabs.pulsar.Pulsar;
import net.minecraft.client.Minecraft;
import net.minecraft.world.World;
import java.lang.reflect.Method;
import java.util.function.DoubleSupplier;
import java.util.function.IntSupplier;
import java.util.concurrent.atomic.AtomicLong;

/** Original optional bridge to Iris-family loader contracts. No loader implementation is bundled. */
public final class RgbShaderRuntime {
    private static final boolean PRESENT = RgbShaderRuntime.class.getClassLoader().getResource("net/coderbot/iris/Iris.class") != null;
    private static RgbShaderAtlas atlas;
    private static volatile boolean active;
    private static boolean warned;
    private static Method pipelineManager, pipeline, camera, x, y, z;
    private static final double[] CAMERA = new double[3];
    private static final java.util.Set<Object> FRAME_NOTIFIERS = java.util.Collections.newSetFromMap(new java.util.WeakHashMap<>());
    private static final AtomicLong PATCHED = new AtomicLong(), SAMPLERS = new AtomicLong();
    private static final java.util.Set<Object> SAMPLER_HOLDERS = java.util.Collections.newSetFromMap(new java.util.WeakHashMap<>());
    private RgbShaderRuntime() {}
    public static boolean active() { return PRESENT && active; }
    public static long patchedPrograms() { return PATCHED.get(); }
    public static long samplerPrograms() { return SAMPLERS.get(); }
    public static long uploadedSections() { return atlas == null ? 0 : atlas.uploadedSections(); }
    public static boolean fieldReady() { return atlas != null && atlas.ready(); }
    public static void patched() { PATCHED.incrementAndGet(); }
    static boolean present() { return PRESENT; }
    private static RgbShaderAtlas atlas() { if (atlas == null) atlas = new RgbShaderAtlas(); return atlas; }
    static void clear() { active = false; if (atlas != null) atlas.clear(); }
    static void tick(World world) {
        if (!PRESENT) return;
        try {
            if (pipelineManager == null) {
                pipelineManager = Class.forName("net.coderbot.iris.Iris").getMethod("getPipelineManager");
                pipeline = pipelineManager.getReturnType().getMethod("getPipelineNullable");
            }
            Object value = pipeline.invoke(pipelineManager.invoke(null));
            active = world != null && value != null && value.getClass().getName().endsWith("DeferredWorldRenderingPipeline");
            if (active) atlas().tick(world); else if (atlas != null) atlas.clear();
        } catch (ReflectiveOperationException | LinkageError failure) { fail(failure); }
    }
    private static void fail(Throwable failure) {
        active = false;
        if (!warned) { warned = true; Pulsar.LOGGER.warn("RGB shader bridge unavailable for this loader API", failure); }
    }
    public static void registerSamplers(Object holder) {
        if (!PRESENT) return;
        try {
            synchronized (SAMPLER_HOLDERS) { if (!SAMPLER_HOLDERS.add(holder)) return; }
            Class<?> api = Class.forName("net.coderbot.iris.gl.sampler.SamplerHolder");
            Class<?> type = Class.forName("net.coderbot.iris.gl.texture.TextureType");
            Object texture2d = type.getField("TEXTURE_2D").get(null);
            Method add = api.getMethod("addDynamicSampler", type, IntSupplier.class, String[].class);
            Method has = api.getMethod("hasSampler", String.class);
            if ((boolean)has.invoke(holder, "pulsarLightData")) {
                if (Boolean.TRUE.equals(add.invoke(holder, texture2d, (IntSupplier)() -> atlas().dataTexture(), new String[]{"pulsarLightData"}))) SAMPLERS.incrementAndGet();
            }
            if ((boolean)has.invoke(holder, "pulsarLightIndex"))
                add.invoke(holder, texture2d, (IntSupplier)() -> atlas().indexTexture(), new String[]{"pulsarLightIndex"});
        } catch (ReflectiveOperationException | LinkageError failure) { fail(failure); }
    }
    public static void registerUniforms(Object holder, Object notifier) {
        if (!PRESENT) return;
        try {
            Class<?> api = Class.forName("net.coderbot.iris.gl.uniform.UniformHolder");
            Class<?> frequency = Class.forName("net.coderbot.iris.gl.uniform.UniformUpdateFrequency");
            Object perFrame = frequency.getField("PER_FRAME").get(null);
            Method integer = api.getMethod("uniform1i", frequency, String.class, IntSupplier.class);
            Method floating = api.getMethod("uniform1f", frequency, String.class, DoubleSupplier.class);
            integer.invoke(holder, perFrame, "pulsarLightEnabled", (IntSupplier)() -> active && atlas != null && atlas.ready() ? 1 : 0);
            integer.invoke(holder, perFrame, "pulsarLightSide", (IntSupplier)() -> atlas == null ? 1 : atlas.side());
            integer.invoke(holder, perFrame, "pulsarLightMinSection", (IntSupplier)() -> atlas == null ? 0 : atlas.minSection());
            integer.invoke(holder, perFrame, "pulsarLightSections", (IntSupplier)() -> atlas == null ? 0 : atlas.sectionCount());
            integer.invoke(holder, perFrame, "pulsarHasSky", (IntSupplier)() -> Minecraft.getMinecraft().world != null && Minecraft.getMinecraft().world.provider.hasSkyLight() ? 1 : 0);
            floating.invoke(holder, perFrame, "pulsarSkyScale", (DoubleSupplier)RgbClient::shaderSkyScale);
            camera = Class.forName("net.coderbot.iris.uniforms.CameraUniforms").getMethod("getUnshiftedCameraPosition");
            Class<?> vector = camera.getReturnType(); x = vector.getMethod("x"); y = vector.getMethod("y"); z = vector.getMethod("z");
            synchronized (FRAME_NOTIFIERS) {
                if (FRAME_NOTIFIERS.add(notifier))
                    Class.forName("net.coderbot.iris.uniforms.FrameUpdateNotifier").getMethod("addListener", Runnable.class)
                            .invoke(notifier, (Runnable)RgbShaderRuntime::updateCamera);
            }
            updateCamera();
            String[] names = {"X", "Y", "Z"};
            for (int i = 0; i < 3; i++) {
                int axis = i;
                integer.invoke(holder, perFrame, "pulsarCameraBlock" + names[i], (IntSupplier)() -> (int)Math.floor(CAMERA[axis]));
                floating.invoke(holder, perFrame, "pulsarCameraFract" + names[i], (DoubleSupplier)() -> { double value = CAMERA[axis]; return value - Math.floor(value); });
            }
        } catch (ReflectiveOperationException | LinkageError failure) { fail(failure); }
    }
    private static void updateCamera() {
        try {
            Object value = camera.invoke(null);
            CAMERA[0] = (double)x.invoke(value); CAMERA[1] = (double)y.invoke(value); CAMERA[2] = (double)z.invoke(value);
        } catch (ReflectiveOperationException failure) { fail(failure); }
    }
}
