package com.sumirelabs.pulsar.light;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import static org.junit.jupiter.api.Assertions.*;

class OptifineMixinCompatibilityTest {
    @TempDir Path directory;

    @Test void replacedRendererLoadsWithoutDisablingOtherCorrections() throws Exception {
        for (String mode : List.of("SCALAR", "RGB")) {
            for (String renderer : List.of("vanilla", "optifine", "unknown")) {
                boolean replaced = !renderer.equals("vanilla");
                Path home = Files.createDirectories(directory.resolve(mode + "-" + renderer));
                Files.createDirectories(home.resolve("config"));
                Files.writeString(home.resolve("config/pulsar.cfg"), "I:configVersion=2\nS:lightingMode=" + mode + "\n");
                Path config = home.resolve("mixins.jar");
                try (var jar = new JarOutputStream(Files.newOutputStream(config))) {
                    String resource = "pulsar-optifine-test.mixins.json";
                    try (var input = getClass().getResourceAsStream("/" + resource)) {
                        assertNotNull(input);
                        jar.putNextEntry(new JarEntry(resource)); input.transferTo(jar); jar.closeEntry();
                    }
                    if (renderer.equals("optifine")) {
                        // Valid for Forge's scanner, but initialization fails:
                        // detection must only inspect the resource.
                        jar.putNextEntry(new JarEntry("net/optifine/reflect/ReflectorForge.class"));
                        jar.write(markerClass()); jar.closeEntry();
                    }
                }
                Path log = home.resolve("probe.log");
                var command = List.of(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                        "-Dfoundation.loadsall=true", "-Dissue26.probe=true", "-Dissue26.replacedRenderer=" + replaced,
                        "-Dissue26.mode=" + mode,
                        "-Djdk.net.unixdomain.tmpdir=" + directory.toAbsolutePath(), "-cp",
                        config + System.getProperty("path.separator") + System.getProperty("pulsar.test.classpath"),
                        "top.outlands.foundation.boot.Foundation", "--gameDir", home.toString(),
                        "--tweakClass", "com.sumirelabs.pulsar.light.nativeprobe.CornerLightTweaker",
                        "--tweakClass", "org.spongepowered.asm.launch.MixinTweaker");
                Process child = new ProcessBuilder(command).directory(home.toFile())
                        .redirectErrorStream(true).redirectOutput(log.toFile()).start();
                if (!child.waitFor(60, TimeUnit.SECONDS)) {
                    child.destroyForcibly(); fail("Renderer probe timed out: " + Files.readString(log));
                }
                String output = Files.readString(log);
                if (renderer.equals("unknown")) {
                    assertNotEquals(0, child.exitValue(), "Missing targets without OptiFine must remain fatal");
                    assertTrue(output.contains("pulsar$lightActualSurface could not find any targets"), output);
                    continue;
                }
                assertEquals(0, child.exitValue(), mode + " replaced=" + replaced + "\n" + output);
                assertTrue(output.contains("OPTIFINE_SHAPE_PASS replaced=" + replaced), output);
            }
        }
    }

    private static byte[] markerClass() {
        var writer = new org.objectweb.asm.ClassWriter(0);
        writer.visit(org.objectweb.asm.Opcodes.V1_8, org.objectweb.asm.Opcodes.ACC_PUBLIC,
                "net/optifine/reflect/ReflectorForge", null, "java/lang/Object", null);
        var init = writer.visitMethod(org.objectweb.asm.Opcodes.ACC_STATIC, "<clinit>", "()V", null, null);
        init.visitCode();
        init.visitTypeInsn(org.objectweb.asm.Opcodes.NEW, "java/lang/AssertionError");
        init.visitInsn(org.objectweb.asm.Opcodes.DUP);
        init.visitMethodInsn(org.objectweb.asm.Opcodes.INVOKESPECIAL, "java/lang/AssertionError", "<init>", "()V", false);
        init.visitInsn(org.objectweb.asm.Opcodes.ATHROW);
        init.visitMaxs(2, 0); init.visitEnd(); writer.visitEnd();
        return writer.toByteArray();
    }
}
