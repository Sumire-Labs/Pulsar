package com.sumirelabs.pulsar.util;

import org.junit.jupiter.api.Test;
import net.minecraft.world.chunk.Chunk;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

class SnapshotChunkMapTest {

    @Test
    void distinctCoordinatesWithTheSameRawLongHashRemainIndependent() throws Exception {
        final SnapshotChunkMap chunks = new SnapshotChunkMap();
        final long firstKey = CoordinateUtils.getChunkKey(-7, -7);
        final long secondKey = CoordinateUtils.getChunkKey(0, 0);
        final long thirdKey = CoordinateUtils.getChunkKey(12, 12);
        final Chunk first = allocateChunk();
        final Chunk second = allocateChunk();
        final Chunk third = allocateChunk();

        assertEquals(Long.hashCode(firstKey), Long.hashCode(secondKey));
        assertEquals(Long.hashCode(secondKey), Long.hashCode(thirdKey));
        assertNotEquals(Long.hashCode(CoordinateUtils.mixChunkKey(firstKey)),
                Long.hashCode(CoordinateUtils.mixChunkKey(secondKey)));
        assertNotEquals(Long.hashCode(CoordinateUtils.mixChunkKey(secondKey)),
                Long.hashCode(CoordinateUtils.mixChunkKey(thirdKey)));

        chunks.put(firstKey, first);
        chunks.put(secondKey, second);
        chunks.put(thirdKey, third);
        assertSame(first, chunks.get(firstKey));
        assertSame(second, chunks.get(secondKey));
        assertSame(third, chunks.get(thirdKey));

        assertSame(second, chunks.remove(secondKey));
        assertNull(chunks.get(secondKey));
        assertSame(first, chunks.get(firstKey));
        assertSame(third, chunks.get(thirdKey));
    }

    @Test
    void concurrentPublicationAndRemovalPreserveNegativeAndPositiveChunkKeys() throws Exception {
        final SnapshotChunkMap chunks = new SnapshotChunkMap();
        final Chunk[] expected = new Chunk[2049];
        for (int index = 0; index < expected.length; ++index) {
            expected[index] = allocateChunk();
        }
        final AtomicBoolean writerDone = new AtomicBoolean();
        final AtomicReference<Throwable> failure = new AtomicReference<>();
        final Thread writer = new Thread(() -> {
            try {
                for (int coordinate = -1024; coordinate <= 1024; ++coordinate) {
                    final long key = CoordinateUtils.getChunkKey(coordinate, coordinate);
                    chunks.put(key, expected[coordinate + 1024]);
                }
            } catch (final Throwable t) {
                failure.set(t);
            } finally {
                writerDone.set(true);
            }
        }, "snapshot-map-test-writer");
        final Thread reader = new Thread(() -> {
            try {
                do {
                    for (int coordinate = -1024; coordinate <= 1024; ++coordinate) {
                        final long key = CoordinateUtils.getChunkKey(coordinate, coordinate);
                        final Chunk value = chunks.get(key);
                        if (value != null) {
                            assertSame(expected[coordinate + 1024], value);
                        }
                    }
                } while (!writerDone.get());
            } catch (final Throwable t) {
                failure.compareAndSet(null, t);
            }
        }, "snapshot-map-test-reader");

        reader.start();
        writer.start();
        writer.join();
        reader.join();

        if (failure.get() != null) {
            throw new AssertionError("Concurrent chunk publication failed", failure.get());
        }
        for (int coordinate = -1024; coordinate <= 1024; ++coordinate) {
            final long key = CoordinateUtils.getChunkKey(coordinate, coordinate);
            assertSame(expected[coordinate + 1024], chunks.get(key));
        }
    }

    private static Chunk allocateChunk() throws Exception {
        final Class<?> unsafeType = Class.forName("sun.misc.Unsafe");
        final Field singleton = unsafeType.getDeclaredField("theUnsafe");
        singleton.setAccessible(true);
        final Object unsafe = singleton.get(null);
        final Method allocateInstance = unsafeType.getMethod("allocateInstance", Class.class);
        return (Chunk) allocateInstance.invoke(unsafe, Chunk.class);
    }
}
