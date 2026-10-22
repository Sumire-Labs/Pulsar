package com.sumirelabs.pulsar.light;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import static org.junit.jupiter.api.Assertions.*;

class RegionExclusionTest {
    @TempDir Path directory;
    @Test void overlappingReadHalosMatchSerialLightAndNeverEscapeWriteRegions()throws Exception { run(false); }
    @Test @EnabledIfSystemProperty(named="pulsar.benchmark.region",matches="true")
    void measureNativeRegionTasks()throws Exception {run(true);}
    private void run(boolean benchmark)throws Exception {
        Path config=directory.resolve("region-mixins.jar");
        try(var jar=new JarOutputStream(Files.newOutputStream(config))) {
            for(String resource:List.of("pulsar-region-test.mixins.json","pulsar-region-audit.mixins.json")) {
                try(var input=getClass().getResourceAsStream("/"+resource)) {
                    assertNotNull(input);jar.putNextEntry(new JarEntry(resource));input.transferTo(jar);jar.closeEntry();
                }
            }
        }
        Path log=directory.resolve("region.log");
        boolean audit=!benchmark&&!Boolean.getBoolean("pulsar.region.noAudit");
        var command=List.of(Path.of(System.getProperty("java.home"),"bin","java").toString(),
                benchmark?"-da":"-ea", "-Dfoundation.loadsall=true","-Dpulsar.region=true","-Dpulsar.region.audit="+audit,
                "-Dpulsar.region.rounds="+Integer.getInteger("pulsar.region.rounds",8),
                "-Dpulsar.region.benchmark="+benchmark,"-Dpulsar.region.distance="+Integer.getInteger("pulsar.region.distance",4),
                "-Dpulsar.region.scheduler="+Boolean.getBoolean("pulsar.region.scheduler"),
                "-Djdk.net.unixdomain.tmpdir="+directory.toAbsolutePath(),"-cp",
                config+System.getProperty("path.separator")+System.getProperty("pulsar.test.classpath"),
                "top.outlands.foundation.boot.Foundation","--gameDir",directory.toString(),
                "--tweakClass","com.sumirelabs.pulsar.light.nativeprobe.CornerLightTweaker",
                "--tweakClass","org.spongepowered.asm.launch.MixinTweaker");
        Process child=new ProcessBuilder(command).directory(directory.toFile()).redirectErrorStream(true).redirectOutput(log.toFile()).start();
        if(!child.waitFor(120,TimeUnit.SECONDS)){child.destroyForcibly();fail("Region probe timed out: "+Files.readString(log));}
        String output=Files.readString(log);assertEquals(0,child.exitValue(),output);
        if(!benchmark) {
            assertTrue(output.contains("REGION_CHECK_PASS audit="+audit),output);
            if(audit)assertFalse(output.contains("writes=0"),output);
        }
        output.lines().filter(line->line.startsWith("REGION")||line.startsWith("PIPELINE")).forEach(System.out::println);
    }
}
