package com.sumirelabs.pulsar.dynamic;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.zip.*;

import net.minecraftforge.fml.common.network.simpleimpl.IMessage;

import io.netty.buffer.ByteBuf;

/** Bounded compressed volume, or a tombstone. The client world-session token rejects late packets. */
public final class DynamicLightPacket implements IMessage {

    public long session, revision;
    public int dimension, source;
    public DynamicLights.Mode mode;
    public boolean rgb, projected;
    public DynamicLightField field;
    public DynamicLightPacket() {}
    DynamicLightPacket(long session, int dimension, int source, long revision, DynamicLightField field) {
        this.session = session;
        this.dimension = dimension;
        this.source = source;
        this.revision = revision;
        this.field = field;
        mode = DynamicLights.mode();
        rgb = com.sumirelabs.pulsar.core.RgbMixinSelection.rgbEnabled();
        projected = com.sumirelabs.pulsar.rgb.RgbSettings.projectsGameplayLight();
    }
    public void toBytes(ByteBuf out) {
        out.writeLong(session).writeInt(dimension).writeInt(source).writeLong(revision)
            .writeByte(mode.ordinal()).writeBoolean(rgb).writeBoolean(projected).writeBoolean(field != null);
        if (field == null)
            return;
        out.writeInt(field.x).writeInt(field.y).writeInt(field.z).writeByte(field.radius)
            .writeBoolean(field.hasVisualData());
        byte[] raw = field.packetData();
        Deflater deflater = new Deflater(Deflater.BEST_SPEED);
        try {
            deflater.setInput(raw);
            deflater.finish();
            int lengthIndex = out.writerIndex();
            out.writeInt(0);
            byte[] buffer = new byte[4096];
            while (!deflater.finished())
                out.writeBytes(buffer, 0, deflater.deflate(buffer));
            out.setInt(lengthIndex, out.writerIndex() - lengthIndex - Integer.BYTES);
        } finally {
            deflater.end();
        }
    }
    public void fromBytes(ByteBuf in) {
        session = in.readLong();
        dimension = in.readInt();
        source = in.readInt();
        revision = in.readLong();
        int ordinal = in.readUnsignedByte();
        if (ordinal >= DynamicLights.Mode.values().length)
            throw new IllegalArgumentException("Invalid dynamic light mode");
        mode = DynamicLights.Mode.values()[ordinal];
        rgb = in.readBoolean();
        projected = in.readBoolean();
        if (in.readBoolean()) {
            int x = in.readInt(), y = in.readInt(), z = in.readInt(), radius = in.readUnsignedByte();
            if (radius > 14)
                throw new IllegalArgumentException("Invalid dynamic light radius");
            boolean visual = in.readBoolean();
            int length = in.readInt();
            if (length < 1 || length > 262144 || length != in.readableBytes())
                throw new IllegalArgumentException("Invalid dynamic light payload");
            byte[] compressed = new byte[length];
            in.readBytes(compressed);
            int size = DynamicLightField.cube(radius);
            byte[] raw = new byte[size * (visual ? 10 : 2)];
            Inflater inflater = new Inflater();
            try {
                inflater.setInput(compressed);
                int offset = 0;
                while (offset < raw.length) {
                    int count = inflater.inflate(raw, offset, raw.length - offset);
                    if (count == 0)
                        break;
                    offset += count;
                }
                if (offset != raw.length || !inflater.finished() || inflater.getRemaining() != 0)
                    throw new IllegalArgumentException("Invalid compressed dynamic light volume");
            } catch (DataFormatException exception) {
                throw new IllegalArgumentException("Invalid dynamic light compression", exception);
            } finally {
                inflater.end();
            }
            short[] cells = new short[size];
            var bytes = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN);
            bytes.asShortBuffer().get(cells);
            short[] losses = visual ? readLosses(bytes, size) : null;
            field = DynamicLightField.fromOwned(x, y, z, radius, cells, losses);
        }
        if (in.isReadable())
            throw new IllegalArgumentException("Trailing dynamic light data");
    }

    // The wire carries four 16-bit values per cell, including on older peers.
    private static short[] readLosses(ByteBuffer bytes, int size) {
        int offset = size * Short.BYTES;
        boolean shared = true;
        for (int i = offset; i < bytes.limit(); i += Long.BYTES) {
            long lanes = bytes.getLong(i);
            if (lanes != (lanes & 0xFFFFL) * 0x0001000100010001L) {
                shared = false;
                break;
            }
        }
        short[] result = new short[shared ? size : size * 4];
        bytes.position(offset);
        if (shared) {
            var lanes = bytes.asLongBuffer();
            for (int i = 0; i < size; i++)
                result[i] = (short) lanes.get();
        } else {
            bytes.asShortBuffer().get(result);
        }
        return result;
    }

}
