package com.sumirelabs.pulsar.rgb;

import net.minecraft.block.state.IBlockState;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Map;

/** Internal immutable config overlay. Mod/API registrations remain frozen underneath it. */
public final class RgbPaletteOverrides {
    public record Values(Map<IBlockState,Integer> colors, Map<IBlockState,Integer> filters) {
        public Values {
            colors = immutable(colors); filters = immutable(filters);
        }
        private static Map<IBlockState,Integer> immutable(Map<IBlockState,Integer> source) {
            var copy = new IdentityHashMap<IBlockState,Integer>();
            source.forEach((state, value) -> {
                java.util.Objects.requireNonNull(state, "state");
                if (value == null || (value & ~0xFFF) != 0) throw new IllegalArgumentException("Invalid RGB override");
                copy.put(state,value);
            });
            return Collections.unmodifiableMap(copy);
        }
    }
    public static final class State {
        public final int generation;
        public final Values values;
        public volatile String profile;
        public volatile Boolean dynamicStates;
        private State(int generation, Values values) { this.generation=generation;this.values=values; }
    }
    private static volatile State current = new State(0,new Values(Map.of(),Map.of()));
    private RgbPaletteOverrides() {}
    public static State current() { return current; }
    /** Only during startup or while the reload coordinator has stopped all lighting tasks. */
    public static synchronized void install(Values values) {
        java.util.Objects.requireNonNull(values,"values");
        if (current.generation == Integer.MAX_VALUE) throw new IllegalStateException("RGB palette generation exhausted");
        current = new State(current.generation+1,values);
    }
}
