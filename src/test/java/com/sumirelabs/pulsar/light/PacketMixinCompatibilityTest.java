package com.sumirelabs.pulsar.light;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import static org.junit.jupiter.api.Assertions.*;

class PacketMixinCompatibilityTest {
    @TempDir Path directory;

    @Test void packetsRetainLightAndSpongeSnapshotsInEitherMixinOrder() throws Exception {
        Path config = directory.resolve("packet-mixins.jar");
        try (var jar = new JarOutputStream(Files.newOutputStream(config))) {
            for (String resource : List.of("pulsar-packet-test.mixins.json", "pulsar-sponge-packet-test.mixins.json")) {
                try (var input = getClass().getResourceAsStream("/" + resource)) {
                    assertNotNull(input); jar.putNextEntry(new JarEntry(resource)); input.transferTo(jar); jar.closeEntry();
                }
            }
        }
        for (String mode : List.of("vanilla", "sponge-first", "sponge-last")) {
            boolean sponge = !mode.equals("vanilla");
            Path log = directory.resolve(mode + ".log");
            var command = List.of(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                    "-Dfoundation.loadsall=true", "-Dissue24.packet=true", "-Dissue24.sponge=" + sponge,
                    "-Dissue24.spongeFirst=" + mode.equals("sponge-first"),
                    "-Djdk.net.unixdomain.tmpdir=" + directory.toAbsolutePath(), "-cp",
                    config + System.getProperty("path.separator") + System.getProperty("pulsar.test.classpath"),
                    "top.outlands.foundation.boot.Foundation", "--gameDir", directory.toString(),
                    "--tweakClass", "com.sumirelabs.pulsar.light.nativeprobe.CornerLightTweaker",
                    "--tweakClass", "org.spongepowered.asm.launch.MixinTweaker");
            Process child = new ProcessBuilder(command).directory(directory.toFile()).redirectErrorStream(true).redirectOutput(log.toFile()).start();
            if (!child.waitFor(60, TimeUnit.SECONDS)) { child.destroyForcibly(); fail("Packet probe timed out: " + Files.readString(log)); }
            String output = Files.readString(log);
            assertEquals(0, child.exitValue(), mode + "\n" + output);
            assertTrue(output.contains("PACKET_LIGHT_PASS cases=12 sponge=" + sponge), output);
            assertFalse(output.contains("@Redirect conflict"), output);
            System.out.println(mode + ": PACKET_LIGHT_PASS cases=12");
        }
    }
}
