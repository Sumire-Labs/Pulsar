package com.sumirelabs.pulsar.light.nativeprobe;

import net.minecraft.launchwrapper.IClassTransformer;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;

/**
 * Structural fixture only, not an OptiFine implementation. G5 removes the
 * vanilla renderQuadsSmooth descriptor in favour of a RenderEnv overload.
 * Rename the vanilla method and its callers to reproduce the missing target
 * without shipping OptiFine classes or changing the fixture's rendering logic.
 */
public final class OptifineRendererShape implements IClassTransformer {
    public byte[] transform(String name, String transformedName, byte[] bytes) {
        if (bytes == null || !transformedName.equals("net.minecraft.client.renderer.BlockModelRenderer")) return bytes;
        ClassNode node = new ClassNode();
        new ClassReader(bytes).accept(node, 0);
        for (var method : node.methods) {
            if (method.name.equals("renderQuadsSmooth")) method.name = "fixture$renderQuadsSmooth";
            for (var instruction : method.instructions) {
                if (instruction instanceof MethodInsnNode call && call.owner.equals(node.name)
                        && call.name.equals("renderQuadsSmooth")) call.name = "fixture$renderQuadsSmooth";
            }
        }
        ClassWriter writer = new ClassWriter(0);
        node.accept(writer);
        return writer.toByteArray();
    }
}
