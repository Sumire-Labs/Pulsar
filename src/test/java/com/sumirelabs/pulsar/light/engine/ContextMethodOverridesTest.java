package com.sumirelabs.pulsar.light.engine;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ContextMethodOverridesTest {

    private static final int FIRST = 1;
    private static final int SECOND = 2;

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

    private static int detect(final Class<?> implementationClass) {
        return ContextMethodOverrides.detect(
                implementationClass, Base.class,
                "first", FIRST,
                "second", SECOND,
                int.class);
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
