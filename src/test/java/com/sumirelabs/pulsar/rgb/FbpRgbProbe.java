package com.sumirelabs.pulsar.rgb;

import com.sumirelabs.pulsar.util.CoordinateUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.WorldClient;
import net.minecraft.client.particle.Particle;
import net.minecraft.client.renderer.BufferBuilder;
import net.minecraft.client.renderer.vertex.DefaultVertexFormats;
import net.minecraft.client.renderer.vertex.VertexFormat;
import net.minecraft.init.Bootstrap;
import net.minecraft.util.math.Vec2f;

import java.lang.reflect.*;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/** Loads each published FBP renderer in isolation and inspects its real emitted vertex bytes. */
public final class FbpRgbProbe {
    private static final int MATERIAL = 0x4d96c864;
    private static Object unsafe;
    private static Method allocate;
    private static String variant, mode;

    public static void main(String[] ignored) throws Exception {
        variant = System.getProperty("pulsar.fbp.variant"); mode = System.getProperty("pulsar.fbp.mode");
        Bootstrap.register();
        var field = Class.forName("sun.misc.Unsafe").getDeclaredField("theUnsafe"); field.setAccessible(true);
        unsafe = field.get(null); allocate = unsafe.getClass().getMethod("allocateInstance", Class.class);
        Minecraft mc = (Minecraft) allocate.invoke(unsafe, Minecraft.class);
        var instance = Minecraft.class.getDeclaredField("instance"); instance.setAccessible(true); instance.set(null, mc);
        mc.world = (WorldClient) allocate.invoke(unsafe, WorldClient.class);
        Particle.interpPosX = 100; Particle.interpPosY = 64; Particle.interpPosZ = 100;
        if (variant.equals("absent")) {
            BufferBuilder buffer = new BufferBuilder(256); buffer.begin(7, DefaultVertexFormats.BLOCK);
            buffer.pos(0, 0, 0).color(100, 200, 150, 77).tex(0, 0).lightmap(112, 0).endVertex();
            if ((buffer instanceof RgbDynamicVertices) != mode.equals("RGB")) throw new AssertionError("SCALAR selection mismatch");
            pass(); return;
        }
        RgbTintBlendMode.current = RgbTintBlendMode.SQUARED_WEIGHT;
        setFlag(RgbShaderRuntime.class, "active", mode.equals("SHADER"));
        Path baseline = Path.of(System.getProperty("pulsar.fbp.root"), "baseline.bin");
        BufferBuilder buffer = null;
        for (boolean sky : mode.equals("RGB") ? new boolean[]{false, true} : new boolean[]{false}) {
            installLight(sky);
            buffer = mesh(buffer);
            byte[] actual = bytes(buffer);
            if (mode.equals("SCALAR")) Files.write(baseline, actual);
            else {
                byte[] original = Files.readAllBytes(baseline);
                if (mode.equals("SHADER")) {
                    if (!Arrays.equals(original, actual)) throw new AssertionError("Shader-owned colors changed");
                } else {
                    compare(buffer, original, actual);
                    // A deferred upload must not use the last particle's tint on this whole batch.
                    RgbDrawState flush = new RgbDrawState(); flush.lightmap = true;
                    int token = flush.push(new Object(), 0xff00ff00, false, false);
                    RgbVertexTint.apply(buffer, 0, ((RgbDynamicVertices) buffer).pulsar$rgbGeneration(), flush);
                    flush.pop(token);
                    if (!Arrays.equals(actual, bytes(buffer))) throw new AssertionError("Batch tinted twice");
                }
            }
        }
        pass();
    }

    private static void pass() { System.out.println("FBP_RGB_PASS variant=" + variant + " mode=" + mode); }

    private static BufferBuilder mesh(BufferBuilder reuse) throws Exception {
        boolean legacy = variant.equals("legacy");
        VertexFormat format;
        Class<?> renderer;
        if (legacy) {
            renderer = Class.forName("com.TominoCZ.FBP.renderer.FBPRenderer");
        } else {
            renderer = Class.forName((variant.equals("fancier") ? "dev.redstudio.fbp" : "com.fbp") + ".renderer.FastCubeUploader");
        }
        // FBP fills its format during mod initialization, which this headless probe omits.
        format = new VertexFormat().addElement(DefaultVertexFormats.POSITION_3F).addElement(DefaultVertexFormats.TEX_2F)
                .addElement(DefaultVertexFormats.COLOR_4UB).addElement(DefaultVertexFormats.TEX_2S).addElement(DefaultVertexFormats.NORMAL_3B);
        BufferBuilder buffer = reuse == null ? new BufferBuilder(4096) : reuse;
        if (reuse != null) { buffer.finishDrawing(); buffer.reset(); }
        buffer.begin(7, format);
        double[] centers = {5.5, 20.5, 6.5, -4.5};
        for (int i = 0; i < centers.length; i++) {
            if (legacy) legacyCube(renderer, buffer, centers[i] - 100, i == 1, i == 2);
            else modernGeometry(renderer, "putCube", buffer, (float) centers[i] - 100, i == 2);
        }
        if (!legacy) {
            Class<?> quad = Class.forName((variant.equals("fancier") ? "dev.redstudio.fbp" : "com.fbp") + ".renderer.FastQuadUploader");
            for (int i = 0; i < centers.length; i++) modernGeometry(quad, "putQuad", buffer, (float) centers[i] - 100, i == 2);
        }
        if (buffer.getVertexCount() != (legacy ? 96 : 112)) throw new AssertionError("Missing vertices: " + buffer.getVertexCount());
        return buffer;
    }

    private static void modernGeometry(Class<?> renderer, String method, BufferBuilder buffer, float x, boolean full) throws Exception {
        String root = variant.equals("fancier") ? "dev.redstudio.fbp" : "com.fbp";
        Class<?> tex = Class.forName(root + ".renderer.texture.ITexCoordProvider");
        Class<?> color = Class.forName(root + ".renderer.color.IColorProvider");
        Class<?> light = Class.forName(root + ".renderer.light.ILightCoordProvider");
        Object texture = Proxy.newProxyInstance(tex.getClassLoader(), new Class[]{tex}, (p, m, a) -> m.getName().endsWith("1") ? 1f : 0f);
        Object colors = Proxy.newProxyInstance(color.getClassLoader(), new Class[]{color}, (p, m, a) -> MATERIAL);
        Object lights = Proxy.newProxyInstance(light.getClassLoader(), new Class[]{light}, (p, m, a) -> full ? 0x00f000f0 : 112);
        renderer.getMethod(method, BufferBuilder.class, float.class, float.class, float.class,
                float.class, float.class, float.class, float.class, float.class, float.class, tex, color, light)
                .invoke(null, buffer, x, 1f, -93.5f, 0f, 0f, 0f, .2f, .2f, .2f, texture, colors, lights);
    }

    private static void legacyCube(Class<?> renderer, BufferBuilder buffer, double x, boolean weather, boolean full) throws Exception {
        Class<?> rotation = Class.forName("com.TominoCZ.FBP.vector.FBPVector3d");
        Object rot = rotation.getConstructor(double.class, double.class, double.class).newInstance(0d, 0d, 0d);
        Vec2f[] texture = {new Vec2f(0, 0), new Vec2f(0, 1), new Vec2f(1, 1), new Vec2f(1, 0)};
        buffer.setTranslation(x, 1, -93.5);
        Method emit = weather ? renderer.getDeclaredMethod("putCube_WH", BufferBuilder.class, Vec2f[].class,
                double.class, double.class, rotation, int.class, int.class, float.class, float.class, float.class, float.class)
                : renderer.getDeclaredMethod("putCube_S", BufferBuilder.class, Vec2f[].class,
                double.class, rotation, int.class, int.class, float.class, float.class, float.class, float.class);
        emit.setAccessible(true);
        Object[] args = weather ? new Object[]{buffer, texture, .2d, .3d, rot, full ? 240 : 112, full ? 240 : 0, 100/255f, 200/255f, 150/255f, 77/255f}
                : new Object[]{buffer, texture, .2d, rot, full ? 240 : 112, full ? 240 : 0, 100/255f, 200/255f, 150/255f, 77/255f};
        emit.invoke(null, args); buffer.setTranslation(0, 0, 0);
    }

    private static byte[] bytes(BufferBuilder buffer) {
        byte[] result = new byte[buffer.getVertexCount() * buffer.getVertexFormat().getSize()];
        ByteBuffer copy = buffer.getByteBuffer().duplicate(); copy.clear(); copy.get(result); return result;
    }

    private static void compare(BufferBuilder buffer, byte[] original, byte[] actual) {
        if (original.length != actual.length) throw new AssertionError("Geometry size changed");
        int stride = buffer.getVertexFormat().getSize(), offset = buffer.getVertexFormat().getColorOffset();
        for (int v = 0; v < buffer.getVertexCount(); v++) for (int c = 0; c < stride; c++) {
            int expected = Byte.toUnsignedInt(original[v * stride + c]);
            int particle = v < 96 ? v / 24 : (v - 96) / 4;
            if (particle != 2 && c >= offset && c < offset + 3) {
                int retained = particle == 1 ? 2 : 0;
                if (c - offset != retained) expected = 0;
            }
            if (Byte.toUnsignedInt(actual[v * stride + c]) != expected) {
                throw new AssertionError("Changed vertex=" + v + " byte=" + c + " expected=" + expected
                        + " actual=" + Byte.toUnsignedInt(actual[v * stride + c]));
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static void installLight(boolean sky) throws Exception {
        var field = RgbClient.class.getDeclaredField("VIEWS"); field.setAccessible(true);
        Map<Long, RgbClient.View> views = (Map<Long, RgbClient.View>) field.get(null); views.clear();
        for (int chunk : new int[]{-1, 0, 1}) {
            int rgb = chunk == 1 ? 0x00f : 0xf00;
            byte[][][] channels = new byte[3][1][];
            for (int c = 0; c < 3; c++) { channels[c][0] = new byte[2048]; Arrays.fill(channels[c][0], (byte) (RgbLight.channel(rgb, c) * 17)); }
            byte[][] canonical = {new byte[2048]}; Arrays.fill(canonical[0], (byte) 255);
            RgbSnapshot snapshot = new RgbSnapshot(4, 1, channels, canonical);
            views.put(CoordinateUtils.getChunkKey(chunk, 0), new RgbClient.View(null, sky ? null : snapshot, sky ? snapshot : null, new byte[0][]));
        }
        setFlag(RgbClient.class, "active", !sky); setFlag(RgbClient.class, "skyActive", sky); setFlag(RgbClient.class, "skyScale", 1f);
    }

    private static void setFlag(Class<?> type, String name, Object value) throws Exception {
        var field = type.getDeclaredField(name); field.setAccessible(true); field.set(null, value);
    }
}
