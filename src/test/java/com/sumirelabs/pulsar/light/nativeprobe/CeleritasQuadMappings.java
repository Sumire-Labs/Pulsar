package com.sumirelabs.pulsar.light.nativeprobe;

import java.util.Map;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.commons.ClassRemapper;
import org.objectweb.asm.commons.SimpleRemapper;

import net.minecraft.launchwrapper.IClassTransformer;

/** Maps external Celeritas mixin shadows for the isolated MCP test runtime. */
public final class CeleritasQuadMappings implements IClassTransformer {

    private static final String TARGET = "org.taumc.celeritas.mixin.core.terrain.BakedQuadMixin";
    @Override
    public byte[] transform(String name, String transformedName, byte[] bytes) {
        if (bytes == null || !TARGET.equals(transformedName))
            return bytes;
        // CleanroomGradle maps game owners, but a mixin's own SRG shadow declarations
        // have no game superclass. Keep the real renderer implementation, changing
        // only its four vanilla shadow names to match the development BakedQuad.
        String owner = TARGET.replace('.', '/');
        var remapper = new SimpleRemapper(Map.of(
            owner + ".func_178209_a()[I", "getVertexData",
            owner + ".field_178214_c", "face",
            owner + ".field_187509_d", "sprite",
            owner + ".field_178213_b", "tintIndex"));
        var writer = new ClassWriter(0);
        new ClassReader(bytes).accept(new ClassRemapper(writer, remapper), 0);
        return writer.toByteArray();
    }

}
