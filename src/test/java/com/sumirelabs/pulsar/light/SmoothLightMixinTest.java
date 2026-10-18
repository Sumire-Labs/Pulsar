package com.sumirelabs.pulsar.light;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import static org.junit.jupiter.api.Assertions.*;

/** Runs the actual transformed renderers in fresh JVMs; no display or game save is needed. */
class SmoothLightMixinTest {
    @TempDir Path directory;

    @Test void nativeRenderersExcludeHiddenLightAndRetainOpenGradients() throws Exception {
        Path config = directory.resolve("mixins.jar");
        try (var jar = new JarOutputStream(Files.newOutputStream(config))) {
            for (String resource : List.of("pulsar-smoothlight-test.mixins.json", "pulsar-surface-celeritas-quads.mixins.json",
                    "pulsar-previous-corners-test.mixins.json", "pulsar-previous-forge-test.mixins.json")) {
                try (var source = getClass().getResourceAsStream("/" + resource)) {
                    assertNotNull(source);
                    jar.putNextEntry(new JarEntry(resource));
                    source.transferTo(jar);
                    jar.closeEntry();
                }
            }
        }
        String baseline = run(config, true, false);
        String previous = run(config, true, true);
        String candidate = run(config, false, false);
        assertTrue(baseline.contains("VANILLA_MIXIN_PASS cases=76 baseline=true"), baseline);
        assertTrue(candidate.contains("VANILLA_MIXIN_PASS cases=76 baseline=false"), candidate);
        assertTrue(baseline.contains("FORGE_MIXIN_PASS cases=76 baseline=true"), baseline);
        assertTrue(candidate.contains("FORGE_MIXIN_PASS cases=76 baseline=false"), candidate);
        assertTrue(baseline.contains("PARTIAL_SURFACES_PASS states=42"), baseline);
        assertTrue(previous.contains("PARTIAL_SURFACES_PASS states=42"), previous);
        assertTrue(candidate.contains("PARTIAL_SURFACES_PASS states=42"), candidate);
        assertTrue(candidate.contains("leaks=0 insetLeaks=0 baseline=false"), candidate);
        if (!System.getProperty("pulsar.test.celeritasClasspath", "").isEmpty()) {
            assertTrue(baseline.contains("CELERITAS_MIXIN_PASS cases=76 baseline=true"), baseline);
            assertTrue(candidate.contains("CELERITAS_MIXIN_PASS cases=76 baseline=false"), candidate);
        }
        assertEquals(baseline.lines().filter(line -> line.startsWith("OPEN ")).toList(),
                candidate.lines().filter(line -> line.startsWith("OPEN ")).toList());
        for (String report : List.of(baseline, previous, candidate)) {
            report.lines().filter(line -> line.startsWith("PARTIAL_SURFACES_PASS")).forEach(System.out::println);
        }
    }

    private String run(Path config, boolean baseline, boolean previous) throws Exception {
        String classpath = System.getProperty("pulsar.test.classpath");
        assertNotNull(classpath, "Run through Gradle test so the child gets the complete runtime classpath");
        String celeritas = System.getProperty("pulsar.test.celeritasClasspath", "");
        String separator = System.getProperty("path.separator");
        List<String> command = new ArrayList<>(List.of(
                Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-Dfoundation.loadsall=true", "-Dissue20.baseline=" + baseline,
                "-Dissue20.previous=" + previous,
                "-Dissue20.celeritas=" + !celeritas.isEmpty()));
        // Windows Unix-domain sockets cannot connect through some DOS-short temp paths.
        command.add("-Djdk.net.unixdomain.tmpdir=" + directory.toAbsolutePath());
        command.addAll(List.of("-cp", config + separator + classpath + (celeritas.isEmpty() ? "" : separator + celeritas),
                "top.outlands.foundation.boot.Foundation", "--gameDir", directory.toString(),
                "--tweakClass", "com.sumirelabs.pulsar.light.nativeprobe.CornerLightTweaker",
                "--tweakClass", "org.spongepowered.asm.launch.MixinTweaker"));
        Path log = directory.resolve(previous ? "previous.log" : baseline ? "baseline.log" : "candidate.log");
        Process process = new ProcessBuilder(command).directory(directory.toFile())
                .redirectErrorStream(true).redirectOutput(log.toFile()).start();
        if (!process.waitFor(60, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            fail("Native corner probe timed out: " + Files.readString(log));
        }
        String output = Files.readString(log);
        assertEquals(0, process.exitValue(), output);
        return output;
    }
}
