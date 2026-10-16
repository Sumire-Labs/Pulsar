package com.sumirelabs.pulsar.rgb;

import io.netty.buffer.ByteBuf;
import net.minecraftforge.fml.common.network.simpleimpl.IMessage;

/** Bounded, sparse section payload. Zero sections explicitly replace previous color. */
public final class RgbPacket implements IMessage {
    public int dimension, x, z;
    public RgbSnapshot snapshot;
    public RgbPacket() {}
    public RgbPacket(int dimension, int x, int z, RgbSnapshot snapshot) {
        this.dimension = dimension; this.x = x; this.z = z; this.snapshot = snapshot;
    }
    public void toBytes(ByteBuf out) {
        out.writeInt(dimension).writeInt(x).writeInt(z).writeInt(snapshot.minSection)
                .writeLong(snapshot.revision).writeInt(snapshot.sectionCount());
        for (int s = 0; s < snapshot.sectionCount(); s++) {
            byte[][] bytes = new byte[4][];
            int mask = 0;
            for (int c = 0; c < 3; c++) { bytes[c] = snapshot.section(c, s); if (bytes[c] != null) mask |= 1 << c; }
            bytes[3] = snapshot.canonicalSection(s); if (bytes[3] != null) mask |= 8;
            out.writeByte(mask);
            for (int c = 0; c < 4; c++) if (bytes[c] != null) out.writeBytes(bytes[c]);
        }
    }
    public void fromBytes(ByteBuf in) {
        dimension = in.readInt(); x = in.readInt(); z = in.readInt();
        int min = in.readInt(); long revision = in.readLong(); int count = in.readInt();
        if (count < 1 || count > 4098 || min < -2049 || (long) min + count > 2050 || revision < 1)
            throw new IllegalArgumentException("Invalid RGB packet bounds");
        byte[][][] data = new byte[3][count][];
        byte[][] canonical = new byte[count][];
        for (int s = 0; s < count; s++) {
            int mask = in.readUnsignedByte();
            if ((mask & ~15) != 0) throw new IllegalArgumentException("Invalid RGB channel mask");
            for (int c = 0; c < 3; c++) if ((mask & 1 << c) != 0) { data[c][s] = new byte[2048]; in.readBytes(data[c][s]); }
            if ((mask & 8) != 0) { canonical[s] = new byte[2048]; in.readBytes(canonical[s]); }
        }
        if (in.isReadable()) throw new IllegalArgumentException("Trailing RGB packet data");
        snapshot = new RgbSnapshot(min, revision, data, canonical);
    }
}
