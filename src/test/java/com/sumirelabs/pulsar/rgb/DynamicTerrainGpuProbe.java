package com.sumirelabs.pulsar.rgb;

import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.regex.*;
import java.util.zip.ZipFile;

import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL;

import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.opengl.GL33C.*;

/** Compiles real Celeritas shaders and renders the shipped moving-light fragment path on a hidden GPU context. */
public final class DynamicTerrainGpuProbe {

    private static int program, loss;
    public static void main(String[] args) throws Exception {
        if (!glfwInit())
            throw new AssertionError("GLFW init");
        long window = 0;
        try {
            glfwWindowHint(GLFW_VISIBLE, GLFW_FALSE);
            glfwWindowHint(GLFW_CONTEXT_VERSION_MAJOR, 3);
            glfwWindowHint(GLFW_CONTEXT_VERSION_MINOR, 3);
            glfwWindowHint(GLFW_OPENGL_PROFILE, GLFW_OPENGL_CORE_PROFILE);
            window = glfwCreateWindow(16, 16, "Pulsar dynamic GPU test", 0, 0);
            if (window == 0)
                throw new AssertionError("GL context");
            glfwMakeContextCurrent(window);
            GL.createCapabilities();
            int variants = 0;
            for (String jar : System.getProperty("pulsar.celeritas.jar")
                .split(java.util.regex.Pattern.quote(System.getProperty("path.separator")))) {
                try (var zip = new ZipFile(jar)) {
                    for (boolean compressed : new boolean[]{false, true})
                        for (boolean vanilla : new boolean[]{false, true}) {
                            String defines = (compressed
                                ? "#define USE_VERTEX_COMPRESSION\n#define VERT_POS_SCALE 1.0\n#define VERT_POS_OFFSET 0.0\n#define VERT_TEX_SCALE 1.0\n"
                                : "") + (vanilla ? "#define USE_VANILLA_COLOR_FORMAT\n" : "");
                            String vertex = DynamicTerrainShader.patch("sodium:blocks/block_layer_opaque.vsh",
                                read(zip, "sodium:blocks/block_layer_opaque.vsh"));
                            String fragment = DynamicTerrainShader.patch("sodium:blocks/block_layer_opaque.fsh",
                                read(zip, "sodium:blocks/block_layer_opaque.fsh"));
                            if (!vertex.contains("PULSAR_FRAME_LIGHT_V1")
                                || !fragment.contains("PULSAR_FRAME_LIGHT_V1"))
                                throw new AssertionError("Celeritas contract not patched");
                            link(defines(resolve(zip, vertex), defines), defines(resolve(zip, fragment), defines));
                            link(defines(resolve(zip, vertex), defines + "#define CELERITAS_NO_LIGHTMAP\n"),
                                defines(resolve(zip, fragment), defines + "#define CELERITAS_NO_LIGHTMAP\n"));
                            variants += 2;
                        }
                }
            }
            testStaticTints();
            String helper;
            try (var in = DynamicTerrainGpuProbe.class
                .getResourceAsStream("/assets/pulsar/shaders/dynamic_terrain_fragment.glsl")) {
                helper = new String(Objects.requireNonNull(in).readAllBytes(), StandardCharsets.UTF_8);
            }
            String vertex = "#version 330 core\nuniform vec3 testPosition;uniform int testPlane;out vec3 pulsarRelativePosition;out vec4 pulsarMaterial;out vec2 pulsarLightUV;void main(){vec2 p=vec2((gl_VertexID<<1)&2,gl_VertexID&2);gl_Position=vec4(p*2-1,0,1);pulsarRelativePosition=testPlane!=0?vec3(p.x*2-1,0,1-p.y*2):testPosition;pulsarMaterial=vec4(1);pulsarLightUV=vec2(0);}";
            program = link(vertex, "#version 330 core\n" + helper
                + "\nout vec4 result;void main(){result=vec4(pulsarFrameLight(vec3(0.05)),1);}");
            glUseProgram(program);
            loss = glGenTextures();
            glActiveTexture(GL_TEXTURE0);
            glBindTexture(GL_TEXTURE_2D, loss);
            nearest();
            fillLoss(0, 0, 0, 0);
            glUniform1i(at("pulsarDynamicLoss"), 0);
            glUniform1i(at("pulsarDynamicCount"), 1);
            glUniform1i(at("pulsarDynamicRgb"), 1);
            glUniform4i(at("pulsarDynamicBox[0]"), -11, -11, -11, 23);
            glUniform4f(at("pulsarDynamicPosition[0]"), 0, 0, 0, 0);
            glUniform4f(at("pulsarDynamicEmission[0]"), 14, 10, 6, 14);
            int light = glGenTextures();
            glActiveTexture(GL_TEXTURE1);
            glBindTexture(GL_TEXTURE_2D, light);
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
            float[] lightmap = new float[16 * 16 * 4];
            for (int y = 0; y < 16; y++)
                for (int x = 0; x < 16; x++) {
                    int i = (x + y * 16) * 4;
                    float v = .05f + .95f * x / 15;
                    lightmap[i] = lightmap[i + 1] = lightmap[i + 2] = v;
                    lightmap[i + 3] = 1;
                }
            glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA32F, 16, 16, 0, GL_RGBA, GL_FLOAT, lightmap);
            glUniform1i(at("u_LightTex"), 1);
            glActiveTexture(GL_TEXTURE2);
            int target = glGenTextures();
            glBindTexture(GL_TEXTURE_2D, target);
            nearest();
            glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA32F, 1, 1, 0, GL_RGBA, GL_FLOAT, (java.nio.ByteBuffer) null);
            int fbo = glGenFramebuffers();
            glBindFramebuffer(GL_FRAMEBUFFER, fbo);
            glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, target, 0);
            if (glCheckFramebufferStatus(GL_FRAMEBUFFER) != GL_FRAMEBUFFER_COMPLETE)
                throw new AssertionError("Framebuffer");
            glViewport(0, 0, 1, 1);
            glBindVertexArray(glGenVertexArrays());
            glUniform3f(at("testPosition"), 0, 0, 0);
            float[] center = render();
            check(center[0] > .8 && center[0] > center[1] && center[1] > center[2], "Source hue");
            for (float distance : new float[]{7.0f, 7.3f, 7.4f, 7.5f, 7.7f, 7.75f, 8f}) {
                glUniform3f(at("testPosition"), distance, 0, 0);
                for (float value : render())
                    check(value >= .05f - 1e-6, "Adding dim light darkened ambient");
            }
            fillLoss(0, 1024, 1024, 0);
            glUniform3f(at("testPosition"), 0, 0, 0);
            float[] filtered = render();
            check(filtered[0] > .8 && Math.abs(filtered[1] - .05) < 1e-6 && Math.abs(filtered[2] - .05) < 1e-6,
                "Absorbed components acquired dynamic energy");
            fillLoss(1024, 1024, 1024, 1024);
            for (float value : render())
                check(Math.abs(value - .05) < 1e-6, "Opaque mask leaked");
            glActiveTexture(GL_TEXTURE0);
            glBindTexture(GL_TEXTURE_2D, loss);
            float[] floor = new float[1024 * 24 * 4];
            for (int i = 0; i < 23 * 23 * 23; i++)
                if (i / (23 * 23) - 11 < 0)
                    Arrays.fill(floor, i * 4, i * 4 + 4, 1024);
            glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA16F, 1024, 24, 0, GL_RGBA, GL_FLOAT, floor);
            glUniform1i(at("testPlane"), 1);
            glUniform4f(at("pulsarDynamicPosition[0]"), 0, 1, 0, 0);
            glUniform4f(at("pulsarDynamicEmission[0]"), 14, 14, 14, 14);
            check(render()[0] > .7, "Opaque floor halved the surface lighting");
            glUniform3f(at("pulsarViewFraction"), 0, -1, 0);
            for (float value : render())
                check(Math.abs(value - .05) < 1e-6, "Back face sampled light through opaque floor");
            glUniform3f(at("pulsarViewFraction"), 0, 0, 0);
            glUniform1i(at("testPlane"), 0);
            glUniform4f(at("pulsarDynamicPosition[0]"), 0, 0, 0, 0);
            fillLoss(0, 0, 0, 0);
            glUniform3f(at("testPosition"), 4, 0, 0);
            float previous = -1;
            Set<Float> values = new HashSet<>();
            for (int frame = 0; frame <= 60; frame++) {
                glUniform4f(at("pulsarDynamicPosition[0]"), frame / 60f, 0, 0, 0);
                float value = render()[0];
                values.add(value);
                if (previous >= 0)
                    check(value > previous && value - previous < .01, "Position changed in discrete light-level steps");
                previous = value;
            }
            check(values.size() == 61, "No sub-tick visual movement");
            if (glGetError() != GL_NO_ERROR)
                throw new AssertionError("GL error");
            String correctedVertex = vertex.replace("out vec3 pulsarRelativePosition;",
                "uniform vec4 testCorrection;out vec4 pulsarMaterialCorrection;out vec2 pulsarQuadCoord;out vec3 pulsarRelativePosition;")
                .replace("pulsarMaterial=vec4(1);",
                    "pulsarMaterial=vec4(1);pulsarMaterialCorrection=testCorrection;pulsarQuadCoord=vec2(.5);");
            program = link(correctedVertex, "#version 330 core\n#define PULSAR_BILINEAR_MATERIAL\n" + helper
                + "\nout vec4 result;void main(){result=vec4(pulsarFrameLight(vec3(.05)),1);}");
            glUseProgram(program);
            glUniform1i(at("pulsarDynamicLoss"), 0);
            glUniform1i(at("u_LightTex"), 1);
            glUniform1i(at("pulsarDynamicCount"), 1);
            glUniform1i(at("pulsarDynamicRgb"), 1);
            glUniform4i(at("pulsarDynamicBox[0]"), -11, -11, -11, 23);
            glUniform4f(at("pulsarDynamicEmission[0]"), 14, 14, 14, 14);
            float[] uncorrected = render();
            glUniform4f(at("testCorrection"), -.8f, -.4f, 0, 0);
            float[] corrected = render();
            check(
                Math.abs(corrected[0] / uncorrected[0] - .8f) < 1e-5
                    && Math.abs(corrected[1] / uncorrected[1] - .9f) < 1e-5
                    && Math.abs(corrected[2] - uncorrected[2]) < 1e-5,
                "Bilinear material correction was lost under dynamic lighting");
            if (glGetError() != GL_NO_ERROR)
                throw new AssertionError("Bilinear GL error");
            System.out.println("DYNAMIC_GPU_PASS terrainVariants=" + variants + " continuousFrames=" + values.size()
                + " ambientFloor=true filters=true bilinearMaterial=true");
        } finally {
            if (window != 0)
                glfwDestroyWindow(window);
            glfwTerminate();
        }
    }
    private static String defines(String source, String d) {
        int at = source.indexOf('\n') + 1;
        return source.substring(0, at) + d + source.substring(at);
    }
    private static String resource(String name) throws Exception {
        try (var in = DynamicTerrainGpuProbe.class.getResourceAsStream("/assets/pulsar/shaders/" + name)) {
            return new String(Objects.requireNonNull(in).readAllBytes(), StandardCharsets.UTF_8);
        }
    }
    private static void testStaticTints() throws Exception {
        int p = link("#version 330 core\n" + resource("lightfield.glsl") + resource("dynamic_terrain_vertex.glsl")
            + "\nout vec3 testTint;void main(){vec2 v=vec2((gl_VertexID<<1)&2,gl_VertexID&2);gl_Position=vec4(v*2-1,0,1);testTint=pulsarStaticTint(vec3(8));}",
            "#version 330 core\nin vec3 testTint;out vec4 result;void main(){result=vec4(testTint,1);}");
        glUseProgram(p);
        int index = glGenTextures();
        glActiveTexture(GL_TEXTURE0);
        glBindTexture(GL_TEXTURE_2D, index);
        nearest();
        glUniform1i(glGetUniformLocation(p, "pulsarLightIndex"), 0);
        glActiveTexture(GL_TEXTURE1);
        glBindTexture(GL_TEXTURE_2D, glGenTextures());
        nearest();
        glTexImage2D(GL_TEXTURE_2D, 0, GL_R32UI, 1, 1, 0, GL_RED_INTEGER, GL_UNSIGNED_INT, new int[]{0});
        glUniform1i(glGetUniformLocation(p, "pulsarLightData"), 1);
        glUniform1i(glGetUniformLocation(p, "pulsarLightSide"), 1);
        glUniform1i(glGetUniformLocation(p, "pulsarLightSections"), 1);
        glUniform1i(glGetUniformLocation(p, "pulsarLightEnabled"), 1);
        glUniform1i(glGetUniformLocation(p, "pulsarStaticRgb"), 1);
        glActiveTexture(GL_TEXTURE2);
        int target = glGenTextures();
        glBindTexture(GL_TEXTURE_2D, target);
        nearest();
        glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA32F, 1, 1, 0, GL_RGBA, GL_FLOAT, (java.nio.ByteBuffer) null);
        int fbo = glGenFramebuffers();
        glBindFramebuffer(GL_FRAMEBUFFER, fbo);
        glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, target, 0);
        glViewport(0, 0, 1, 1);
        glBindVertexArray(glGenVertexArrays());
        for (int[] sample : new int[][]{{0, 0, 0, 0}, {0xF60, 0, 15, 0}, {0x700, 0, 7, 0}, {0xF00, 0x00F, 15, 15},
            {0x246, 0xFFF, 15, 15}})
            for (float sky : new float[]{1, .2f})
                for (var mode : RgbTintBlendMode.values()) {
                    int[] entries = new int[256 * 4];
                    entries[0] = -2;
                    entries[1] = RgbShaderLightData.pack(sample[0], sample[1], sample[2], sample[3]);
                    glActiveTexture(GL_TEXTURE0);
                    glBindTexture(GL_TEXTURE_2D, index);
                    glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA32I, 256, 1, 0, GL_RGBA_INTEGER, GL_INT, entries);
                    glUniform1i(glGetUniformLocation(p, "pulsarBlendMode"), mode.ordinal());
                    glUniform1f(glGetUniformLocation(p, "pulsarSkyScale"), sky);
                    int expected = mode.tintAbgr(0xFFFFFFFF, sample[0] >>> 8 & 15, sample[0] >>> 4 & 15, sample[0] & 15,
                        sample[2], sample[1] >>> 8 & 15, sample[1] >>> 4 & 15, sample[1] & 15, sample[3], sky);
                    float[] actual = render();
                    for (int lane = 0; lane < 3; lane++)
                        check(Math.abs(actual[lane] - (expected >>> (lane * 8) & 255) / 255f) < .004,
                            "Static tint mismatch: " + mode + " lane=" + lane);
                }
        System.out.println("STATIC_TINT_GPU_PASS cases=70");
    }
    private static String read(ZipFile zip, String name) throws Exception {
        String[] p = name.split(":", 2);
        try (var in = zip.getInputStream(zip.getEntry("assets/" + p[0] + "/shaders/" + p[1]))) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
    private static String resolve(ZipFile zip, String source) throws Exception {
        Matcher matcher = Pattern.compile("(?m)^#import <([^>]+)>\\s*$").matcher(source);
        StringBuffer result = new StringBuffer();
        while (matcher.find())
            matcher.appendReplacement(result, Matcher.quoteReplacement(resolve(zip, read(zip, matcher.group(1)))));
        matcher.appendTail(result);
        return result.toString();
    }
    private static int link(String vertex, String fragment) {
        int p = glCreateProgram();
        glAttachShader(p, compile(GL_VERTEX_SHADER, vertex));
        glAttachShader(p, compile(GL_FRAGMENT_SHADER, fragment));
        glLinkProgram(p);
        if (glGetProgrami(p, GL_LINK_STATUS) == 0)
            throw new AssertionError(glGetProgramInfoLog(p));
        return p;
    }
    private static int compile(int type, String source) {
        int s = glCreateShader(type);
        glShaderSource(s, source);
        glCompileShader(s);
        if (glGetShaderi(s, GL_COMPILE_STATUS) == 0)
            throw new AssertionError(glGetShaderInfoLog(s) + "\n" + source);
        return s;
    }
    private static int at(String name) {
        return glGetUniformLocation(program, name);
    }
    private static void nearest() {
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
    }
    private static void fillLoss(float r, float g, float b, float scalar) {
        glActiveTexture(GL_TEXTURE0);
        glBindTexture(GL_TEXTURE_2D, loss);
        var bytes = BufferUtils.createByteBuffer(1024 * 24 * 4 * Float.BYTES).order(java.nio.ByteOrder.nativeOrder());
        var values = bytes.asFloatBuffer();
        for (int i = 0; i < 1024 * 24; i++)
            values.put(r).put(g).put(b).put(scalar);
        glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA16F, 1024, 24, 0, GL_RGBA, GL_FLOAT, (java.nio.ByteBuffer) null);
        glTexSubImage2D(GL_TEXTURE_2D, 0, 0, 0, 1024, 24, GL_RGBA, GL_FLOAT, bytes);
    }
    private static float[] render() {
        glDrawArrays(GL_TRIANGLES, 0, 3);
        var pixels = BufferUtils.createFloatBuffer(4);
        glReadPixels(0, 0, 1, 1, GL_RGBA, GL_FLOAT, pixels);
        return new float[]{pixels.get(0), pixels.get(1), pixels.get(2)};
    }
    private static void check(boolean ok, String message) {
        if (!ok)
            throw new AssertionError(message);
    }

}
