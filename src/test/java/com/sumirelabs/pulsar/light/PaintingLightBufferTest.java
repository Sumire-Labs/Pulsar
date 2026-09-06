package com.sumirelabs.pulsar.light;

import net.minecraft.client.renderer.BufferBuilder;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PaintingLightBufferTest {
    @Test
    void lightmapBufferStoresBlockThenSky() {
        // Unequal channels catch a swapped lightmap axis, which a fully lit scene hides.
        for (int packed : new int[]{14 << 4, 15 << 20, (5 << 20) | (11 << 4)}) {
            BufferBuilder buffer = new BufferBuilder(256);
            buffer.begin(7, PaintingLightSampler.VERTEX_FORMAT);
            buffer.pos(0, 0, 0).tex(0, 0);
            PaintingLightSampler.writeVertexLight(buffer, packed);
            buffer.normal(0, 0, -1).endVertex();
            buffer.finishDrawing();

            ByteBuffer bytes = buffer.getByteBuffer().order(ByteOrder.nativeOrder());
            int offset = PaintingLightSampler.VERTEX_FORMAT.getUvOffsetById(1);
            assertEquals(packed & 0xFFFF, Short.toUnsignedInt(bytes.getShort(offset)), "block UV");
            assertEquals(packed >>> 16 & 0xFFFF, Short.toUnsignedInt(bytes.getShort(offset + 2)), "sky UV");
        }
    }
}
