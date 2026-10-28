package com.sumirelabs.pulsar.memory;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import java.util.jar.*;
import static org.junit.jupiter.api.Assertions.*;

@EnabledIfSystemProperty(named="pulsar.test.memoryBenchmark",matches="true")
class MemoryFootprintTest {
    @TempDir Path directory;
    @Test void measureOwnedStructuresWithInstrumentation()throws Exception {
        Path agent=directory.resolve("agent.jar");
        var manifest=new Manifest();manifest.getMainAttributes().putValue("Manifest-Version","1.0");
        manifest.getMainAttributes().putValue("Premain-Class",MemoryAgent.class.getName());
        String resource=MemoryAgent.class.getName().replace('.','/')+".class";
        try(var out=new JarOutputStream(Files.newOutputStream(agent),manifest);var in=getClass().getClassLoader().getResourceAsStream(resource)) {
            assertNotNull(in);out.putNextEntry(new JarEntry(resource));in.transferTo(out);out.closeEntry();
        }
        for(int run=0;run<3;run++) {
            Path log=directory.resolve("memory-"+run+".log");
            var process=new ProcessBuilder(Path.of(System.getProperty("java.home"),"bin","java").toString(),"-Xmx512m","-XX:+UseCompressedOops",
                    "-javaagent:"+agent,"--add-opens=java.base/java.util=ALL-UNNAMED","--add-opens=java.base/java.util.concurrent=ALL-UNNAMED",
                    "-cp",System.getProperty("pulsar.test.classpath"),"com.sumirelabs.pulsar.rgb.MemoryFootprintProbe")
                    .redirectErrorStream(true).redirectOutput(log.toFile()).start();
            if(!process.waitFor(60,TimeUnit.SECONDS)){process.destroyForcibly();fail("Memory probe timeout");}
            String output=new String(Files.readAllBytes(log),java.nio.charset.StandardCharsets.UTF_8);
            assertEquals(0,process.exitValue(),output);
            output.lines().filter(line->line.startsWith("MEMORY ")||line.startsWith("PUBLICATION ")).forEach(System.out::println);
        }
    }
}
