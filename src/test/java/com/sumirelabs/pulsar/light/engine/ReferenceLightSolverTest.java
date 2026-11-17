/*
 * SPDX-License-Identifier: LGPL-3.0-only
 */
package com.sumirelabs.pulsar.light.engine;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.*;

class ReferenceLightSolverTest {

    @TempDir Path directory;
    @Test
    void propagationAndIncrementalEditsMatchIndependentRelaxation() throws Exception {
        Path log = directory.resolve("reference.log");
        Process child = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
            "-cp", System.getProperty("pulsar.test.classpath"), ReferenceLightProbe.class.getName())
            .redirectErrorStream(true).redirectOutput(log.toFile()).start();
        if (!child.waitFor(60, TimeUnit.SECONDS)) {
            child.destroyForcibly();
            fail("Reference solver timeout");
        }
        String output = new String(Files.readAllBytes(log), java.nio.charset.StandardCharsets.UTF_8);
        assertEquals(0, child.exitValue(), output);
        assertTrue(output.contains("REFERENCE_LIGHT_PASS comparisons=360 cellsPerComparison=16384"), output);
        output.lines().filter(s -> s.startsWith("REFERENCE_LIGHT_PASS")).forEach(System.out::println);
    }

}
