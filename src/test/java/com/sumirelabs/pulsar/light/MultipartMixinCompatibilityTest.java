package com.sumirelabs.pulsar.light;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;

class MultipartMixinCompatibilityTest {
    @TempDir Path directory;
    @Test void realMultipartMixinsAndSnapshotLifecycleWorkInBothModes() throws Exception {
        for (String mode : List.of("SCALAR", "RGB_MAX", "RGB_VANILLA")) {
            Path home = Files.createDirectories(directory.resolve(mode));
            Files.createDirectories(home.resolve("config"));
            Files.writeString(home.resolve("config/pulsar.cfg"), "general {\nI:configVersion=2\nS:lightingMode="
                    + (mode.equals("SCALAR") ? "SCALAR" : "RGB") + "\n}\n");
            Path log = home.resolve("probe.log");
            String renderer=System.getProperty("pulsar.test.celeritasClasspath","");
            Path mixins = home.resolve("mixins.jar");
            try(var jar = new java.util.jar.JarOutputStream(Files.newOutputStream(mixins));
                var input = getClass().getResourceAsStream("/pulsar-multipart-test.mixins.json")) {
                assertNotNull(input);
                jar.putNextEntry(new java.util.jar.JarEntry("pulsar-multipart-test.mixins.json"));
                input.transferTo(jar); jar.closeEntry();
            }
            var command = List.of(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                    "-Dfoundation.loadsall=true", "-Dpulsar.multipartProbe=true", "-Dpulsar.multipartMode=" + mode,
                    "-Dpulsar.traceRenderer="+!renderer.isEmpty(),
                    "-cp", mixins + System.getProperty("path.separator") + System.getProperty("pulsar.test.classpath")
                            + (renderer.isEmpty()?"":System.getProperty("path.separator")+renderer),
                    "top.outlands.foundation.boot.Foundation", "--gameDir", home.toString(),
                    "--tweakClass", "com.sumirelabs.pulsar.light.nativeprobe.CornerLightTweaker",
                    "--tweakClass", "org.spongepowered.asm.launch.MixinTweaker");
            Process child = new ProcessBuilder(command).directory(home.toFile())
                    .redirectErrorStream(true).redirectOutput(log.toFile()).start();
            if (!child.waitFor(60, TimeUnit.SECONDS)) { child.destroyForcibly(); fail("Multipart probe timed out"); }
            String output = Files.readString(log);
            assertEquals(0, child.exitValue(), output);
            assertTrue(output.contains("MULTIPART_PASS mode=" + mode), output);
            output.lines().filter(line -> line.contains("MULTIPART_PASS")).forEach(System.out::println);
        }
    }
}
