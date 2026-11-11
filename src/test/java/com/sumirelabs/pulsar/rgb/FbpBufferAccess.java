/*
 * SPDX-License-Identifier: LGPL-3.0-only
 */

package com.sumirelabs.pulsar.rgb;

import org.objectweb.asm.*;

import net.minecraft.launchwrapper.IClassTransformer;

/** Supply the release's ATs and CPU buffer-address binding without the full game/LWJGL bootstrap. */
public final class FbpBufferAccess implements IClassTransformer {

    public static long directAddress(java.nio.ByteBuffer buffer, int offset) {
        try {
            var field = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
            field.setAccessible(true);
            var unsafe = (sun.misc.Unsafe) field.get(null);
            long address = unsafe.objectFieldOffset(java.nio.Buffer.class.getDeclaredField("address"));
            return unsafe.getLong(buffer, address) + offset;
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException(failure);
        }
    }

    public byte[] transform(String name, String transformedName, byte[] bytes) {
        boolean buffer = "net.minecraft.client.renderer.BufferBuilder".equals(transformedName);
        boolean cube = "dev.redstudio.fbp.renderer.FastCubeUploader".equals(transformedName)
            || "com.fbp.renderer.FastCubeUploader".equals(transformedName)
            || "dev.redstudio.fbp.renderer.FastQuadUploader".equals(transformedName)
            || "com.fbp.renderer.FastQuadUploader".equals(transformedName);
        if (bytes == null || (!buffer && !cube))
            return bytes;
        ClassWriter writer = new ClassWriter(0);
        new ClassReader(bytes).accept(new ClassVisitor(Opcodes.ASM9, writer) {

            private int opened(int access) {
                return (access & ~(Opcodes.ACC_PRIVATE | Opcodes.ACC_PROTECTED)) | Opcodes.ACC_PUBLIC;
            }
            @Override
            public FieldVisitor visitField(int access, String name, String descriptor, String signature, Object value) {
                if (buffer && (name.equals("vertexCount") || name.equals("isDrawing")))
                    access = opened(access);
                return super.visitField(access, name, descriptor, signature, value);
            }
            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor, String signature,
                String[] exceptions) {
                if (buffer && name.equals("growBuffer") && descriptor.equals("(I)V"))
                    access = opened(access);
                MethodVisitor method = super.visitMethod(access, name, descriptor, signature, exceptions);
                if (!cube)
                    return method;
                return new MethodVisitor(Opcodes.ASM9, method) {

                    @Override
                    public void visitMethodInsn(int opcode, String owner, String name, String descriptor, boolean itf) {
                        if (owner.equals("org/lwjgl/MemoryUtil") && name.equals("getAddress")
                            && descriptor.equals("(Ljava/nio/ByteBuffer;I)J")) {
                            owner = "com/sumirelabs/pulsar/rgb/FbpBufferAccess";
                            name = "directAddress";
                        }
                        super.visitMethodInsn(opcode, owner, name, descriptor, itf);
                    }

                };
            }

        }, 0);
        return writer.toByteArray();
    }

}
