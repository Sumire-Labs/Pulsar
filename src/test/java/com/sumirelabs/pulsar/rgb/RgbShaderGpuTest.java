package com.sumirelabs.pulsar.rgb;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Explicit opt-in: CI without a GPU still runs the shader contract/unit tests. */
class RgbShaderGpuTest {
    @TempDir Path directory;
    @Test void actualGpuCompilesAndRendersTheLightfield() throws Exception {
        assumeTrue(Boolean.getBoolean("pulsar.test.gpu"), "Enable with -PpulsarGpuTests=true");
        Path log=directory.resolve("gpu.log");
        var process=new ProcessBuilder(Path.of(System.getProperty("java.home"),"bin","java").toString(),
                "-cp",System.getProperty("pulsar.test.gpuClasspath")+System.getProperty("path.separator")
                        +System.getProperty("pulsar.test.classpath"),RgbShaderGpuProbe.class.getName())
                .redirectErrorStream(true).redirectOutput(log.toFile()).start();
        if(!process.waitFor(60,TimeUnit.SECONDS)){process.destroyForcibly();fail("GPU probe timeout");}
        String output=Files.readString(log);
        assertEquals(0,process.exitValue(),output);
        assertTrue(output.contains("RGB_GPU_PASS cases=8"),output);
        System.out.println(output);
    }
}
