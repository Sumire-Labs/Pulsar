/*
 * SPDX-License-Identifier: LGPL-3.0-only
 */

package com.sumirelabs.pulsar.rgb;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.zip.ZipFile;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.*;

/** Real release renderers emit differently lit particles into one deferred buffer. No GL or display required. */
class FbpRgbCompatibilityTest {

    @TempDir Path directory;

    @ParameterizedTest
    @ValueSource(strings = {"legacy", "fancier", "fanciest"})
    void realCubeBuffersKeepPerParticleColorsAndFullbright(String variant) throws Exception {
        String classpath = System.getProperty("pulsar.test.fbp." + variant);
        assertNotNull(classpath);
        List<String> extra = new ArrayList<>();
        extra.add(classpath);
        // Fanciest uses Cleanroom jar-in-jar libraries. Give the isolated launcher
        // those exact bundled libraries without running the mod's game initialization.
        for (String jarPath : classpath.split(java.util.regex.Pattern.quote(File.pathSeparator))) {
            try (var jar = new ZipFile(jarPath)) {
                for (var entry : java.util.Collections.list(jar.entries())) {
                    if (!entry.isDirectory() && entry.getName().endsWith(".jar")) {
                        Path nested = directory.resolve(Path.of(entry.getName()).getFileName());
                        try (var input = jar.getInputStream(entry)) {
                            Files.copy(input, nested);
                        }
                        extra.add(nested.toString());
                    }
                }
            }
        }
        for (String mode : List.of("SCALAR", "RGB", "SHADER")) {
            run(variant, mode, String.join(File.pathSeparator, extra));
        }
    }

    @Test
    void absentFbpDoesNotBreakRgbOrScalarStartup() throws Exception {
        run("absent", "RGB", "");
        run("absent", "SCALAR", "");
    }

    private void run(String variant, String mode, String extra) throws Exception {
        Path home = Files.createDirectories(directory.resolve(mode));
        Files.createDirectories(home.resolve("config"));
        Files.writeString(home.resolve("config/pulsar.cfg"), "general {\n I:configVersion=3\n S:lightingMode="
            + (mode.equals("SCALAR") ? "SCALAR" : "RGB") + "\n}\n");
        Path config = home.resolve("mixins.jar");
        try (var output = new JarOutputStream(Files.newOutputStream(config));
            var input = getClass().getResourceAsStream("/pulsar-fbp-test.mixins.json")) {
            assertNotNull(input);
            output.putNextEntry(new JarEntry("pulsar-fbp-test.mixins.json"));
            input.transferTo(output);
            output.closeEntry();
            if (mode.equals("SHADER")) {
                // Only presence is queried here; a real shader pipeline/context is outside this CPU probe.
                var marker = new org.objectweb.asm.ClassWriter(0);
                marker.visit(org.objectweb.asm.Opcodes.V1_8, org.objectweb.asm.Opcodes.ACC_PUBLIC,
                    "net/coderbot/iris/Iris", null, "java/lang/Object", null);
                marker.visitEnd();
                output.putNextEntry(new JarEntry("net/coderbot/iris/Iris.class"));
                output.write(marker.toByteArray());
                output.closeEntry();
            }
        }
        Path log = home.resolve("probe.log");
        String classpath = config + File.pathSeparator + System.getProperty("pulsar.test.classpath")
            + (extra.isEmpty() ? "" : File.pathSeparator + extra);
        Process child = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
            "-Dfoundation.loadsall=true", "-Dpulsar.fbpProbe=true", "-Dpulsar.fbp.variant=" + variant,
            "-Dpulsar.fbp.mode=" + mode, "-Dpulsar.fbp.root=" + directory,
            "-Djdk.net.unixdomain.tmpdir=" + directory.toAbsolutePath(), "-cp", classpath,
            "top.outlands.foundation.boot.Foundation", "--gameDir", home.toString(),
            "--tweakClass", "com.sumirelabs.pulsar.light.nativeprobe.CornerLightTweaker",
            "--tweakClass", "org.spongepowered.asm.launch.MixinTweaker")
            .directory(home.toFile()).redirectErrorStream(true).redirectOutput(log.toFile()).start();
        if (!child.waitFor(60, TimeUnit.SECONDS)) {
            child.destroyForcibly();
            fail("FBP probe timeout: " + Files.readString(log));
        }
        String text = Files.readString(log);
        assertEquals(0, child.exitValue(), text);
        assertTrue(text.contains("FBP_RGB_PASS variant=" + variant + " mode=" + mode), text);
        System.out.println("FBP_RGB_PASS variant=" + variant + " mode=" + mode);
    }

}
