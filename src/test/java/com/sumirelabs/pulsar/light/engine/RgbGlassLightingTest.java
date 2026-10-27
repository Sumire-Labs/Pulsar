package com.sumirelabs.pulsar.light.engine;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;

class RgbGlassLightingTest {
    @TempDir Path directory;

    @Test void torchEnclosureMatchesReferenceFiltersAndBothEnginePaths() throws Exception {
        for(boolean legacy:new boolean[]{true,false}) {
            Path log=directory.resolve("glass-"+legacy+".log");
            var child=new ProcessBuilder(Path.of(System.getProperty("java.home"),"bin","java").toString(),
                    "-cp",System.getProperty("pulsar.test.classpath"),RgbGlassProbe.class.getName(),Boolean.toString(legacy))
                    .redirectErrorStream(true).redirectOutput(log.toFile()).start();
            if(!child.waitFor(60,TimeUnit.SECONDS)){child.destroyForcibly();fail("Glass probe timeout");}
            String output=Files.readString(log);
            assertEquals(0,child.exitValue(),output);
            assertTrue(output.contains("GLASS_BFS_PASS legacy="+legacy+" cases=35"),output);
            assertTrue(output.contains(legacy?"type=red source=E96 outside=800":"type=red source=EB8 outside=C00"),output);
            output.lines().filter(line->line.startsWith("GLASS ")).forEach(System.out::println);
        }
    }
}
