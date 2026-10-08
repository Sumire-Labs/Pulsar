package com.sumirelabs.pulsar.light;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class LightThreadCountTest {
    @Test void automaticCountIsBoundedAndExplicitValuesKeepTheirMeaning() {
        assertEquals(1, LightThreadCount.resolve(-1, 1));
        assertEquals(4, LightThreadCount.resolve(-1, 12));
        assertEquals(16, LightThreadCount.resolve(-1, 128));
        assertEquals(0, LightThreadCount.resolve(0, 12));
        assertEquals(7, LightThreadCount.resolve(7, 12));
    }
}
