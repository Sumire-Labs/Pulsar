package com.sumirelabs.pulsar.light.engine;

import com.sumirelabs.pulsar.light.SWMRNibbleArray;
import com.sumirelabs.pulsar.rgb.PackedRgbLight;
import org.junit.jupiter.api.Test;
import java.util.Arrays;
import java.util.Random;
import static org.junit.jupiter.api.Assertions.*;

class PackedRgbCacheTest {
    @Test void packsEveryCellWithoutDirtyingPublishedPlanes() {
        var random = new Random(9231);
        SWMRNibbleArray[] planes = new SWMRNibbleArray[3];
        for (int c = 0; c < 3; c++) {
            byte[] bytes = new byte[2048]; random.nextBytes(bytes);
            planes[c] = new SWMRNibbleArray(bytes); planes[c].updateVisible();
        }
        int[] values = new int[4096];
        PackedRgbCache.packPlanes(planes[0], planes[1], planes[2], values);
        for (int i = 0; i < 4096; i++)
            assertEquals(PackedRgbLight.pack(planes[0].getUpdating(i), planes[1].getUpdating(i), planes[2].getUpdating(i)), values[i]);
        for (var plane : planes) assertFalse(plane.isDirty());
    }

    @Test void partialByteWriteDetachesVisibleStorageAndPreservesAdjacentNibble() {
        byte[] bytes = new byte[2048]; Arrays.fill(bytes, (byte)0x11);
        var plane = new SWMRNibbleArray(bytes); plane.updateVisible();
        int[] values = new int[4096]; Arrays.fill(values, PackedRgbLight.pack(1, 1, 1));
        values[1] = PackedRgbLight.pack(1, 9, 1);
        PackedRgbCache.unpackPlane(plane, values, 1, 1, 8);
        assertEquals(9, plane.getUpdating(1));
        assertEquals(1, plane.getUpdating(0));
        assertEquals(1, plane.getVisible(1));
        plane.updateVisible();
        for (int i = 0; i < 4096; i++) assertEquals(i == 1 ? 9 : 1, plane.getVisible(i));
    }

    @Test void constantPlanesRemainCleanUntilAnActualEndCellChange() {
        var full = new SWMRNibbleArray(); full.setFull(); full.updateVisible();
        var zero = new SWMRNibbleArray(); zero.updateVisible();
        int[] values = new int[4096];
        PackedRgbCache.packPlanes(full, zero, null, values);
        for (int value : values) assertEquals(PackedRgbLight.pack(15, 0, 0), value);
        PackedRgbCache.unpackPlane(full, values, 0, 4095, 16);
        PackedRgbCache.unpackPlane(zero, values, 0, 4095, 8);
        assertFalse(full.isDirty()); assertFalse(zero.isDirty());
        values[4095] = 0;
        PackedRgbCache.unpackPlane(full, values, 4095, 4095, 16);
        assertEquals(15, full.getVisible(4095));
        assertEquals(15, full.getUpdating(4094)); assertEquals(0, full.getUpdating(4095));
        full.updateVisible(); assertEquals(0, full.getVisible(4095));
    }
}
