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
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Exercise the actual cloned world, rather than substituting a face-aware test IBlockAccess. */
class CeleritasWorldSliceTest {
    @TempDir Path directory;

    @Test void clonedLightRespectsClosedFacesAndPreservesOpenLight() throws Exception {
        String celeritas = System.getProperty("pulsar.test.celeritasClasspath", "");
        assumeTrue(!celeritas.isEmpty(), "Supply -PceleritasTestClasspath=<Celeritas jar>");
        Path config = directory.resolve("mixins.jar");
        try (var jar = new JarOutputStream(Files.newOutputStream(config))) {
            for (String name : List.of("pulsar-stair-wall-core.mixins.json", "pulsar-celeritas-worldslice-test.mixins.json")) {
                try (var input = getClass().getResourceAsStream("/" + name)) {
                    assertNotNull(input, name);
                    jar.putNextEntry(new JarEntry(name)); input.transferTo(jar); jar.closeEntry();
                }
            }
        }
        String separator = System.getProperty("path.separator");
        Path log = directory.resolve("worldslice.log");
        Process child = new ProcessBuilder(
                Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-Dfoundation.loadsall=true", "-Dissue20.worldSlice=true",
                "-Djdk.net.unixdomain.tmpdir=" + directory.toAbsolutePath(), "-cp",
                config + separator + System.getProperty("pulsar.test.classpath") + separator + celeritas,
                "top.outlands.foundation.boot.Foundation", "--gameDir", directory.toString(),
                "--tweakClass", "com.sumirelabs.pulsar.light.nativeprobe.CornerLightTweaker",
                "--tweakClass", "org.spongepowered.asm.launch.MixinTweaker")
                .directory(directory.toFile()).redirectErrorStream(true).redirectOutput(log.toFile()).start();
        if (!child.waitFor(60, TimeUnit.SECONDS)) {
            child.destroyForcibly(); fail("WorldSlice probe timed out: " + Files.readString(log));
        }
        String output = Files.readString(log);
        assertEquals(0, child.exitValue(), output);
        assertTrue(output.contains("CELERITAS_WORLDSLICE_PASS cases=1728"), output);
        output.lines().filter(line -> line.startsWith("CELERITAS_WORLDSLICE_PASS")).forEach(System.out::println);
    }
}
