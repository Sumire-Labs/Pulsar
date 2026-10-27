package com.sumirelabs.pulsar.rgb;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.concurrent.TimeUnit;
import java.util.jar.*;
import static org.junit.jupiter.api.Assertions.*;

class RgbPaletteCacheTest {
    @TempDir Path directory;
    @Test void reloadInvalidatesRealStateAndSectionCachesAndRestoresDefaults() throws Exception {
        Path config=directory.resolve("palette-probe.jar");
        try(var jar=new JarOutputStream(Files.newOutputStream(config));var input=getClass().getResourceAsStream("/pulsar-palette-test.mixins.json")) {
            assertNotNull(input);jar.putNextEntry(new JarEntry("pulsar-palette-test.mixins.json"));input.transferTo(jar);jar.closeEntry();
        }
        Path log=directory.resolve("probe.log");
        var process=new ProcessBuilder(Path.of(System.getProperty("java.home"),"bin","java").toString(),
                "-Dfoundation.loadsall=true","-Dpulsar.paletteProbe=true","-Djdk.net.unixdomain.tmpdir="+directory.toAbsolutePath(),
                "-cp",config+System.getProperty("path.separator")+System.getProperty("pulsar.test.classpath"),
                "top.outlands.foundation.boot.Foundation","--gameDir",directory.toString(),
                "--tweakClass","com.sumirelabs.pulsar.light.nativeprobe.CornerLightTweaker",
                "--tweakClass","org.spongepowered.asm.launch.MixinTweaker")
                .directory(directory.toFile()).redirectErrorStream(true).redirectOutput(log.toFile()).start();
        if(!process.waitFor(60,TimeUnit.SECONDS)){process.destroyForcibly();fail("Palette cache probe timed out");}
        String output=new String(Files.readAllBytes(log),java.nio.charset.StandardCharsets.UTF_8);
        assertEquals(0,process.exitValue(),output);assertTrue(output.contains("RGB_PALETTE_CACHE_PASS"),output);
        assertTrue(output.contains("RGB_PALETTE_PERSISTENCE_PASS"),output);
    }
}
