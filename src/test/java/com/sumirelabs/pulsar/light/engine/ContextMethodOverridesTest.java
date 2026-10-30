package com.sumirelabs.pulsar.light.engine;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ContextMethodOverridesTest {
    @Test void unrelatedMissingClientTypeDoesNotForceBothFlags() {
        var writer=new org.objectweb.asm.ClassWriter(0);
        String name="pulsar/test/StrippedClientSignature";
        writer.visit(org.objectweb.asm.Opcodes.V1_8,org.objectweb.asm.Opcodes.ACC_PUBLIC,name,null,
                org.objectweb.asm.Type.getInternalName(Base.class),null);
        var first=writer.visitMethod(org.objectweb.asm.Opcodes.ACC_PUBLIC,"first","(I)I",null,null);
        first.visitCode();first.visitVarInsn(org.objectweb.asm.Opcodes.ILOAD,1);
        first.visitInsn(org.objectweb.asm.Opcodes.IRETURN);first.visitMaxs(1,2);first.visitEnd();
        var client=writer.visitMethod(org.objectweb.asm.Opcodes.ACC_PUBLIC,"render","(Lpulsar/missing/ClientParticle;)V",null,null);
        client.visitCode();client.visitInsn(org.objectweb.asm.Opcodes.RETURN);client.visitMaxs(0,2);client.visitEnd();writer.visitEnd();
        class StrippedLoader extends ClassLoader {
            StrippedLoader(){super(Base.class.getClassLoader());}
            Class<?> define(){byte[] bytes=writer.toByteArray();return defineClass(name.replace('/','.'),bytes,0,bytes.length);}
        }
        Class<?> type=new StrippedLoader().define();
        assertThrows(NoClassDefFoundError.class,()->type.getMethod("first",int.class));
        assertEquals(FIRST,detect(type));
    }

    private static final int FIRST = 1;
    private static final int SECOND = 2;

    private static int detect(final Class<?> implementationClass) {
        return ContextMethodOverrides.detect(
                implementationClass, Base.class,
                "first", FIRST,
                "second", SECOND,
                int.class);
    }

    @Test
    void detectsEachOverrideIndependentlyAndThroughInheritance() {
        assertEquals(0, detect(Base.class));
        assertEquals(FIRST, detect(FirstOverride.class));
        assertEquals(SECOND, detect(SecondOverride.class));
        assertEquals(FIRST | SECOND, detect(BothOverrides.class));
        assertEquals(FIRST, detect(InheritedFirstOverride.class));
    }

    @Test
    void takesTheConservativePathWhenARequiredMethodCannotBeFound() {
        assertEquals(FIRST | SECOND, ContextMethodOverrides.detect(
                Base.class, Base.class,
                "missingFirst", FIRST,
                "second", SECOND,
                int.class));
    }

    public static class Base {

        public int first(final int value) {
            return value;
        }

        public int second(final int value) {
            return value;
        }
    }

    public static class FirstOverride extends Base {

        @Override
        public int first(final int value) {
            return value + 1;
        }
    }

    public static class SecondOverride extends Base {

        @Override
        public int second(final int value) {
            return value + 1;
        }
    }

    public static class BothOverrides extends FirstOverride {

        @Override
        public int second(final int value) {
            return value + 1;
        }
    }

    public static final class InheritedFirstOverride extends FirstOverride {
    }
}
