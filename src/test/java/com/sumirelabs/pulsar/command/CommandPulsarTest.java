package com.sumirelabs.pulsar.command;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;

class CommandPulsarTest {
    @Test void relightCentersOnTheOccupiedChunkOnBothSidesOfZeroAndBoundaries() {
        assertEquals(-1,CommandPulsar.playerChunk(-.5));
        assertEquals(-2,CommandPulsar.playerChunk(-16.5));
        assertEquals(-1,CommandPulsar.playerChunk(-16));
        assertEquals(0,CommandPulsar.playerChunk(0));
        assertEquals(0,CommandPulsar.playerChunk(15.999));
        assertEquals(1,CommandPulsar.playerChunk(16));
    }
}
