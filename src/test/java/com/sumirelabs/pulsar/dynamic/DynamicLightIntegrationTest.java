package com.sumirelabs.pulsar.dynamic;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.concurrent.TimeUnit;
import java.util.jar.*;
import static org.junit.jupiter.api.Assertions.*;

class DynamicLightIntegrationTest {
    @TempDir Path directory;
    @Test void transformedWorldQueriesSourcesAndRenderingRespectTheMode() throws Exception {
        Path config=directory.resolve("dynamic-probe.jar");
        try(var jar=new JarOutputStream(Files.newOutputStream(config));var input=getClass().getResourceAsStream("/pulsar-dynamic-test.mixins.json")) {
            assertNotNull(input);jar.putNextEntry(new JarEntry("pulsar-dynamic-test.mixins.json"));input.transferTo(jar);jar.closeEntry();
        }
        Path log=directory.resolve("probe.log");
        var process=new ProcessBuilder(Path.of(System.getProperty("java.home"),"bin","java").toString(),
                "-Dfoundation.loadsall=true","-Dpulsar.dynamicProbe=true","-Djdk.net.unixdomain.tmpdir="+directory.toAbsolutePath(),
                "-cp",config+System.getProperty("path.separator")+System.getProperty("pulsar.test.classpath"),
                "top.outlands.foundation.boot.Foundation","--gameDir",directory.toString(),
                "--tweakClass","com.sumirelabs.pulsar.light.nativeprobe.CornerLightTweaker",
                "--tweakClass","org.spongepowered.asm.launch.MixinTweaker")
                .directory(directory.toFile()).redirectErrorStream(true).redirectOutput(log.toFile()).start();
        if(!process.waitFor(60,TimeUnit.SECONDS)){process.destroyForcibly();fail("Dynamic light probe timed out");}
        String output=Files.readString(log);
        assertEquals(0,process.exitValue(),output);assertTrue(output.contains("DYNAMIC_LIGHT_PASS"),output);
    }
}
