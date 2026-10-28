package com.sumirelabs.pulsar.light;

import org.junit.jupiter.api.Test;
import java.util.Arrays;
import static org.junit.jupiter.api.Assertions.*;

class ImmutableNibbleTest {
    @Test void freezingVisibleDataDoesNotPublishOrCorruptPendingWrites() {
        byte[] bytes=new byte[2048];Arrays.fill(bytes,(byte)0x77);
        var owner=SWMRNibbleArray.fromOwned(bytes,SWMRNibbleArray.INIT_STATE_INIT);
        owner.set(0,3);var reader=SWMRNibbleArray.fromImmutable(owner.shareVisibleData());
        assertEquals(7,reader.getVisible(0));owner.updateVisible();
        assertEquals(3,owner.getVisible(0));assertEquals(7,reader.getVisible(0));
        owner.setFull();owner.updateVisible();assertEquals(7,reader.getVisible(0));
    }
    @Test void firstWriteTransfersPrivateBufferWithoutPublishingOrPoolingTheBorrowedPlane() {
        byte[] original=new byte[2048];Arrays.fill(original,(byte)0x77);
        var nibble=SWMRNibbleArray.fromImmutable(original);nibble.set(0,3);
        byte[] privateBytes=nibble.getUpdatingData();
        assertNotSame(original,privateBytes);assertSame(original,nibble.getVisibleData());
        nibble.updateVisible();assertSame(privateBytes,nibble.getVisibleData());
        assertEquals(7,original[0]&15);assertEquals(3,nibble.getVisible(0));
        for(byte[] pooled:SWMRNibbleArray.WORKING_BYTES_POOL.get())assertNotSame(privateBytes,pooled);
    }
    @Test void savingDuringAnUnpublishedWriteStillReadsThePreviousGeneration() {
        byte[] original=new byte[2048];Arrays.fill(original,(byte)0x66);
        var nibble=SWMRNibbleArray.fromImmutable(original);
        nibble.set(0,9);
        assertArrayEquals(original,nibble.getSaveState().data);
        assertEquals(6,nibble.getVisible(0));
        nibble.updateVisible();assertEquals(9,nibble.getVisible(0));assertEquals(0x66,original[0]&255);
    }
    @Test void stateTransitionsAndEveryWritePathPreserveBorrowedBytes() {
        for(int operation=0;operation<8;operation++) {
            byte[] original=new byte[2048];Arrays.fill(original,(byte)0x37);
            byte[] expected=original.clone();var nibble=SWMRNibbleArray.fromImmutable(original);
            nibble.setHidden();nibble.updateVisible();assertSame(original,nibble.getVisibleData());
            nibble.setNonNull();nibble.updateVisible();assertSame(original,nibble.getVisibleData());
            switch(operation) {
                case 0 -> nibble.set(0,12);
                case 1 -> nibble.setFull();
                case 2 -> nibble.setZero();
                case 3 -> {nibble.setNull();nibble.updateVisible();nibble.setNonNull();nibble.set(0,12);}
                case 4 -> {nibble.setUninitialised();nibble.updateVisible();nibble.set(0,12);}
                case 5 -> nibble.bulkWriteAll(new byte[2048]);
                case 6 -> Arrays.fill(nibble.prepareForBulkWrite(),(byte)0xAA);
                case 7 -> {nibble.getUpdatingStorage()[0]=0x22;nibble.markDirtyAll();}
            }
            nibble.updateVisible();assertArrayEquals(expected,original);
            nibble.setFull();nibble.updateVisible();nibble.set(0,1);nibble.updateVisible();
            assertArrayEquals(expected,original,"Full/nonuniform cycle reused immutable storage");
            for(byte[] pooled:SWMRNibbleArray.WORKING_BYTES_POOL.get())assertNotSame(original,pooled);
        }
    }
}
