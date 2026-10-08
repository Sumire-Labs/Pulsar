package com.sumirelabs.pulsar.light;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import java.util.Arrays;
import java.util.Random;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;

class SWMRNibbleArrayTest {

    @org.junit.jupiter.api.Test
    void scalarWriterRechecksOwnershipAfterUniformAndVisibleTransitions() {
        SWMRNibbleArray nibble = new SWMRNibbleArray(null, true);
        SWMRNibbleArray untouched = new SWMRNibbleArray(null, true);
        untouched.setFull();
        untouched.updateVisible();
        for (int pass = 0; pass < 64; pass++) {
            nibble.setFull();
            nibble.set(0, 7);
            nibble.set(1, 3);
            nibble.updateVisible();
            org.junit.jupiter.api.Assertions.assertEquals(7, nibble.getVisible(0));
            org.junit.jupiter.api.Assertions.assertEquals(3, nibble.getVisible(1));
            org.junit.jupiter.api.Assertions.assertEquals(15, untouched.getVisible(0));
            nibble.setZero();
            nibble.set(0, 9);
            nibble.updateVisible();
            org.junit.jupiter.api.Assertions.assertEquals(9, nibble.getVisible(0));
            org.junit.jupiter.api.Assertions.assertEquals(0, nibble.getVisible(1));
            nibble.setNull();
            nibble.set(1, 11);
            nibble.updateVisible();
            org.junit.jupiter.api.Assertions.assertEquals(0, nibble.getVisible(0));
            org.junit.jupiter.api.Assertions.assertEquals(11, nibble.getVisible(1));
        }
    }
    @AfterEach void clearPool() { SWMRNibbleArray.WORKING_BYTES_POOL.remove(); }

    private static SWMRNibbleArray full() {
        final SWMRNibbleArray nibble = new SWMRNibbleArray();
        nibble.setFull();
        nibble.updateVisible();
        return nibble;
    }

    @Test void fullSectionsShareStorageAndDetachBeforeAndAfterPublication() {
        final SWMRNibbleArray first = full(), other = full();
        assertSame(first.getVisibleData(), other.getVisibleData());
        first.set(42, 3);
        assertEquals(15, first.getVisible(42));
        assertEquals(15, other.getUpdating(42));
        first.updateVisible();
        assertEquals(3, first.getVisible(42));
        assertNotSame(first.getVisibleData(), other.getVisibleData());
        first.setFull();
        first.set(9, 7); // Full state was not published yet.
        first.updateVisible();
        assertEquals(7, first.getVisible(9));
        assertEquals(15, other.getVisible(9));
    }

    @Test void everyWritableArrayPathKeepsOtherFullSectionsIntact() {
        final SWMRNibbleArray other = full();
        final byte[] zeros = new byte[2048];
        SWMRNibbleArray n = full();
        n.setZero(); n.updateVisible(); assertEquals(0, n.getVisible(0));
        n.setFull(); n.bulkWriteAll(zeros); n.updateVisible(); assertEquals(0, n.getVisible(0));
        n.setFull(); Arrays.fill(n.prepareForBulkWrite(), (byte) 0x37);
        n.updateVisible(); assertEquals(7, n.getVisible(0));
        n.setFull(); n.getUpdatingStorage()[0] = 0x24; n.markDirtyAll();
        n.updateVisible(); assertEquals(4, n.getVisible(0));
        n.setFull(); n.markDirtyAll(); n.getUpdatingStorage()[0] = 0x56;
        n.updateVisible(); assertEquals(6, n.getVisible(0));
        final SWMRNibbleArray source = new SWMRNibbleArray(new byte[2048]);
        n.setFull(); n.extrudeLower(source); n.updateVisible();
        assertEquals(0, n.getVisible(0));
        for (int i = 0; i < 4096; i++) assertEquals(15, other.getVisible(i));
    }

    @Test void clientWrappersRetainVanillaArrayIdentity() {
        for (int state : new int[]{SWMRNibbleArray.INIT_STATE_INIT, SWMRNibbleArray.INIT_STATE_HIDDEN}) {
            final byte[] vanilla = new byte[2048];
            final SWMRNibbleArray n = new SWMRNibbleArray(vanilla, state);
            n.setFull(); n.updateVisible();
            assertSame(vanilla, n.getVisibleData());
            assertEquals(255, vanilla[0] & 255);
            n.set(0, 2); n.updateVisible();
            assertSame(vanilla, n.getVisibleData());
            assertEquals(2, vanilla[0] & 15);
        }
    }

    @Test void fullExtrusionAndHiddenStateKeepTheirSemantics() {
        final SWMRNibbleArray source = full(), destination = new SWMRNibbleArray();
        destination.extrudeLower(source); destination.updateVisible();
        assertSame(source.getVisibleData(), destination.getVisibleData());
        destination.setHidden(); destination.updateVisible();
        assertTrue(destination.isHiddenVisible());
        assertNull(destination.toVanillaNibble());
        destination.setNonNull(); destination.updateVisible();
        assertEquals(15, destination.getVisible(0));
        destination.setUninitialised(); destination.updateVisible();
        assertEquals(0, destination.getVisible(0));
        destination.setFull(); destination.setNull(); destination.updateVisible();
        assertNull(destination.getVisibleData());
        assertEquals(15, source.getVisible(0));
    }

    @Test void snapshotsAndVanillaConversionsAreIndependentCopies() {
        final SWMRNibbleArray n = full();
        final byte[] saved = n.getSaveState().data;
        saved[0] = 0;
        n.toVanillaNibble().getData()[0] = 0;
        assertEquals(15, n.getVisible(0));
    }

    @Test void repeatedFullToEditedTransitionsReusePrivateVisibleStorage() {
        final SWMRNibbleArray n = full(), other = full();
        n.set(0, 3); n.updateVisible();
        final byte[] privateBytes = n.getVisibleData();
        for (int i = 0; i < 100; i++) {
            n.setFull(); n.updateVisible();
            assertSame(other.getVisibleData(), n.getVisibleData());
            n.set(0, i & 15); n.updateVisible();
            assertSame(privateBytes, n.getVisibleData());
            assertEquals(i & 15, n.getVisible(0));
            assertEquals(15, other.getVisible(0));
        }
        n.setUninitialised(); n.updateVisible();
        n.setFull(); n.updateVisible(); n.set(0, 2); n.updateVisible();
        assertNotSame(privateBytes, n.getVisibleData());
    }

    @Test void burstPoolIsBoundedAndNeverContainsSharedVisibleStorage() {
        SWMRNibbleArray.WORKING_BYTES_POOL.get().clear();
        final SWMRNibbleArray[] batch = new SWMRNibbleArray[SWMRNibbleArray.MAX_POOLED_WORKING_BYTES * 2];
        for (int i = 0; i < batch.length; i++) {
            batch[i] = new SWMRNibbleArray(); batch[i].set(0, 1);
        }
        for (SWMRNibbleArray n : batch) n.updateVisible();
        assertEquals(SWMRNibbleArray.MAX_POOLED_WORKING_BYTES, SWMRNibbleArray.WORKING_BYTES_POOL.get().size());
        final SWMRNibbleArray full = full();
        full.setNull(); full.updateVisible();
        for (byte[] bytes : SWMRNibbleArray.WORKING_BYTES_POOL.get()) assertNotSame(full().getVisibleData(), bytes);
    }

    @Test void randomizedWritesMatchAnIndependentPackedByteModel() {
        final SWMRNibbleArray n = new SWMRNibbleArray();
        final byte[] model = new byte[2048];
        final Random random = new Random(8317);
        for (int step = 0; step < 10000; step++) {
            switch (random.nextInt(5)) {
                case 0 -> { n.setFull(); Arrays.fill(model, (byte) 255); }
                case 1 -> { n.setZero(); Arrays.fill(model, (byte) 0); }
                case 2 -> { random.nextBytes(model); n.bulkWriteAll(model); }
                case 3 -> { random.nextBytes(model); System.arraycopy(model, 0, n.prepareForBulkWrite(), 0, 2048); }
                default -> {
                    int index = random.nextInt(4096), value = random.nextInt(16), shift = (index & 1) * 4;
                    n.set(index, value);
                    model[index / 2] = (byte) ((model[index / 2] & ~(15 << shift)) | value << shift);
                }
            }
            n.updateVisible();
            assertArrayEquals(model, n.getVisibleData(), "step " + step);
            assertEquals(15, full().getVisible(random.nextInt(4096)));
        }
    }

    @Test void concurrentReadersNeverObserveWritesToAnotherFullSection() throws Exception {
        final SWMRNibbleArray writer = full(), independent = full();
        try (var executor = Executors.newSingleThreadExecutor()) {
            final var writing = executor.submit(() -> {
                for (int i = 0; i < 10000; i++) {
                    writer.setFull(); writer.updateVisible();
                    writer.set(42, 3); writer.updateVisible();
                }
                SWMRNibbleArray.WORKING_BYTES_POOL.remove();
            });
            for (int i = 0; i < 10000; i++) {
                assertEquals(15, independent.getVisible(42));
                final byte[] saved = writer.getSaveState().data;
                assertTrue((saved[21] & 15) == 15 || (saved[21] & 15) == 3);
                assertEquals(255, saved[0] & 255);
            }
            writing.get(5, TimeUnit.SECONDS);
        }
    }
}
