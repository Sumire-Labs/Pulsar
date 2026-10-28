package com.sumirelabs.pulsar.light.engine;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;

@EnabledIfSystemProperty(named="pulsar.test.emptySectionBenchmark",matches="true")
class EmptySectionBenchmarkTest {
    @TempDir Path directory;
    @Test void compareRealAccessorAcrossIsolatedJvms() throws Exception {
        for(int round=0;round<3;round++)for(boolean fast:new boolean[]{false,true}) {
            Path log=directory.resolve(round+"-"+fast+".log");
            var process=new ProcessBuilder(Path.of(System.getProperty("java.home"),"bin","java").toString(),
                    "-Dpulsar.light.emptySectionFastPath="+fast,"-cp",System.getProperty("pulsar.test.classpath"),EmptySectionProbe.class.getName())
                    .redirectErrorStream(true).redirectOutput(log.toFile()).start();
            if(!process.waitFor(60,TimeUnit.SECONDS)){process.destroyForcibly();fail("Accessor benchmark timed out");}
            String output=Files.readString(log);assertEquals(0,process.exitValue(),output);
            assertTrue(output.contains("EMPTY_ACCESS"),output);
            output.lines().filter(line->line.startsWith("EMPTY_ACCESS")).forEach(System.out::println);
        }
    }
}
