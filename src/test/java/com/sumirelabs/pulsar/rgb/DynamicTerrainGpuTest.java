package com.sumirelabs.pulsar.rgb;

import java.nio.file.*;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class DynamicTerrainGpuTest {

    @TempDir Path directory;
    @Test
    void actualGpuKeepsAmbientAndInterpolatesMovingLight() throws Exception {
        assumeTrue(Boolean.getBoolean("pulsar.test.gpu"), "Enable with -PpulsarGpuTests=true");
        String celeritas = System.getProperty("pulsar.test.celeritasClasspath", "");
        assumeTrue(!celeritas.isEmpty(), "Supply the Celeritas test jar");
        Path log = directory.resolve("frame-gpu.log");
        Process child = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
            "-Dpulsar.celeritas.jar=" + celeritas, "-cp",
            System.getProperty("pulsar.test.gpuClasspath") + System.getProperty("path.separator")
                + System.getProperty("pulsar.test.classpath"),
            DynamicTerrainGpuProbe.class.getName()).redirectErrorStream(true).redirectOutput(log.toFile()).start();
        if (!child.waitFor(60, TimeUnit.SECONDS)) {
            child.destroyForcibly();
            fail("GPU probe timeout");
        }
        String output = Files.readString(log);
        assertEquals(0, child.exitValue(), output);
        assertTrue(output.contains("DYNAMIC_GPU_PASS"), output);
        System.out.println(output);
    }

}
