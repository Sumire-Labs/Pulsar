package com.sumirelabs.pulsar.light;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;

class IndustrialLightCompatibilityTest {
    @TempDir Path directory;
    @Test void releasedIndustrialModsUseSafeContextualSamples()throws Exception {
        for(String mod:List.of("experimental","classic","enderio")) {
            String cp=System.getProperty("pulsar.test.classpath");
            if(mod.equals("classic")) {
                cp=java.util.Arrays.stream(cp.split(java.util.regex.Pattern.quote(java.io.File.pathSeparator)))
                        .filter(path->!path.toLowerCase(java.util.Locale.ROOT).contains("industrialcraft-2"))
                        .collect(java.util.stream.Collectors.joining(java.io.File.pathSeparator));
                cp+=java.io.File.pathSeparator+System.getProperty("pulsar.test.classicClasspath");
            }
            Path log=directory.resolve(mod+".log");
            var child=new ProcessBuilder(Path.of(System.getProperty("java.home"),"bin","java").toString(),
                    "-cp",cp,IndustrialLightProbe.class.getName(),mod).redirectErrorStream(true).redirectOutput(log.toFile()).start();
            if(!child.waitFor(60,TimeUnit.SECONDS)){child.destroyForcibly();fail("Industrial probe timed out");}
            String output=Files.readString(log);assertEquals(0,child.exitValue(),output);
            assertTrue(output.contains("INDUSTRIAL_PASS "+mod),output);
            output.lines().filter(line->line.contains("INDUSTRIAL_PASS")).forEach(System.out::println);
        }
    }
}
