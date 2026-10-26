package com.sumirelabs.pulsar.api;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;

class PublicApiRegistrationTest {
    @TempDir Path temp;

    @Test void scalarAndRgbLifecycleKeepRegistrationAndUserPrecedence() throws Exception {
        // Registry freezing is process-global. Each mode gets a fresh JVM rather
        // than resetting production singletons or contaminating other tests.
        for (String mode : new String[] {"SCALAR", "RGB"}) {
            Path home = Files.createDirectory(temp.resolve(mode));
            Path output = home.resolve("probe.log");
            var process = new ProcessBuilder(
                    Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                    "-cp", System.getProperty("pulsar.test.classpath"),
                    PublicApiRegistrationProbe.class.getName(), home.toString(), mode)
                    .redirectErrorStream(true).redirectOutput(output.toFile()).start();
            boolean finished = process.waitFor(60, TimeUnit.SECONDS);
            if (!finished) process.destroyForcibly();
            assertTrue(finished, mode + " probe timed out");
            assertEquals(0, process.exitValue(), () -> {
                try { return Files.readString(output); }
                catch (Exception e) { return e.toString(); }
            });
        }
    }
}
