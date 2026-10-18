/* SPDX-License-Identifier: LGPL-3.0-only */
package com.sumirelabs.pulsar.rgb;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.nio.FloatBuffer;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;

/** Original adapter to the loader's emulated fixed-function state, not raw removed GL queries. */
final class RgbLegacyTexture {
    private RgbLegacyTexture() {}
    private static final boolean EMULATED = RgbLegacyTexture.class.getClassLoader().getResource("com/gtnewhorizons/angelica/glsm/GLStateManager.class") != null;
    static boolean emulated() { return EMULATED; }
    // The supported GLSM FFP renderer exposes four logical combiner stages.
    static int units() { return emulated() ? 4 : Math.min(32, GL11.glGetInteger(GL13.GL_MAX_TEXTURE_UNITS)); }
    private static final class Api {
        static final MethodHandle GET_INT, GET_FLOATS, PUT_INT, PUT_FLOATS, ENABLE, DISABLE, ENABLED, RECORDING;
        static {
            try {
                Class<?> api = Class.forName("com.gtnewhorizons.angelica.glsm.GLStateManager");
                var lookup = MethodHandles.publicLookup();
                GET_INT = lookup.unreflect(api.getMethod("glGetTexEnvi", int.class, int.class));
                GET_FLOATS = lookup.unreflect(api.getMethod("glGetTexEnv", int.class, int.class, FloatBuffer.class));
                PUT_INT = lookup.unreflect(api.getMethod("glTexEnvi", int.class, int.class, int.class));
                PUT_FLOATS = lookup.unreflect(api.getMethod("glTexEnv", int.class, int.class, FloatBuffer.class));
                ENABLE = lookup.unreflect(api.getMethod("glEnable", int.class));
                DISABLE = lookup.unreflect(api.getMethod("glDisable", int.class));
                ENABLED = lookup.unreflect(api.getMethod("glIsEnabled", int.class));
                RECORDING = lookup.unreflect(Class.forName("com.gtnewhorizons.angelica.glsm.DisplayListManager").getMethod("isRecording"));
            } catch (ReflectiveOperationException failure) { throw new IllegalStateException("Unsupported GLSM state API", failure); }
        }
    }
    static boolean recording() {
        if (!emulated()) return GL11.glGetInteger(GL11.GL_LIST_INDEX) != 0;
        try { return (boolean)Api.RECORDING.invokeExact(); }
        catch (Throwable failure) { throw new IllegalStateException(failure); }
    }
    static int get(int name) {
        if (!emulated()) return GL11.glGetTexEnvi(GL11.GL_TEXTURE_ENV, name);
        try { return (int)Api.GET_INT.invokeExact(GL11.GL_TEXTURE_ENV, name); }
        catch (Throwable failure) { throw new IllegalStateException(failure); }
    }
    static void getColor(FloatBuffer target) {
        if (!emulated()) { GL11.glGetTexEnv(GL11.GL_TEXTURE_ENV, GL11.GL_TEXTURE_ENV_COLOR, target); return; }
        try { Api.GET_FLOATS.invokeExact(GL11.GL_TEXTURE_ENV, GL11.GL_TEXTURE_ENV_COLOR, target); }
        catch (Throwable failure) { throw new IllegalStateException(failure); }
    }
    static void set(int name, int value) {
        if (!emulated()) { net.minecraft.client.renderer.GlStateManager.glTexEnvi(GL11.GL_TEXTURE_ENV, name, value); return; }
        try { Api.PUT_INT.invokeExact(GL11.GL_TEXTURE_ENV, name, value); }
        catch (Throwable failure) { throw new IllegalStateException(failure); }
    }
    static void setColor(FloatBuffer value) {
        if (!emulated()) { net.minecraft.client.renderer.GlStateManager.glTexEnv(GL11.GL_TEXTURE_ENV, GL11.GL_TEXTURE_ENV_COLOR, value); return; }
        try { Api.PUT_FLOATS.invokeExact(GL11.GL_TEXTURE_ENV, GL11.GL_TEXTURE_ENV_COLOR, value); }
        catch (Throwable failure) { throw new IllegalStateException(failure); }
    }
    static boolean enabled2d() {
        try { return (boolean)Api.ENABLED.invokeExact(GL11.GL_TEXTURE_2D); }
        catch (Throwable failure) { throw new IllegalStateException(failure); }
    }
    static void enable2d(boolean enabled) {
        try { if (enabled) Api.ENABLE.invokeExact(GL11.GL_TEXTURE_2D); else Api.DISABLE.invokeExact(GL11.GL_TEXTURE_2D); }
        catch (Throwable failure) { throw new IllegalStateException(failure); }
    }
}
