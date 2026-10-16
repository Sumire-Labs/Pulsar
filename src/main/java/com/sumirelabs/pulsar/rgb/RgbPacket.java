package com.sumirelabs.pulsar.rgb;

import io.netty.buffer.ByteBuf;
import net.minecraftforge.fml.common.network.simpleimpl.IMessage;

/** Bounded, sparse section payload. Zero sections explicitly replace previous color. */
public final class RgbPacket implements IMessage {
    public int dimension, x, z;
    public RgbSnapshot snapshot;
    public boolean sky;
    public RgbPacket() {}
    public RgbPacket(int dimension, int x, int z, RgbSnapshot snapshot) {
        this.dimension = dimension; this.x = x; this.z = z; this.snapshot = snapshot;
    }
    public RgbPacket(int dimension, int x, int z, RgbSnapshot snapshot, boolean sky) {
        this(dimension, x, z, snapshot); this.sky = sky;
    }
    public void toBytes(ByteBuf out) {
        out.writeInt(dimension).writeInt(x).writeInt(z).writeInt(snapshot.minSection)
                .writeLong(snapshot.revision).writeInt(snapshot.sectionCount() | (sky ? 1 << 16 : 0));
        for (int s = 0; s < snapshot.sectionCount(); s++) snapshot.writeSection(out, s);
    }
    public void fromBytes(ByteBuf in) {
        dimension = in.readInt(); x = in.readInt(); z = in.readInt();
        int min = in.readInt(); long revision = in.readLong(); int encodedCount = in.readInt();
        if ((encodedCount & ~0x11FFF) != 0) throw new IllegalArgumentException("Invalid RGB lane/count");
        sky = (encodedCount & 0x10000) != 0;
        int count = encodedCount & 0xFFFF;
        if (count < 1 || count > 4098 || min < -2049 || (long) min + count > 2050 || revision < 1)
            throw new IllegalArgumentException("Invalid RGB packet bounds");
        byte[][][] data = new byte[3][count][];
        byte[][] canonical = new byte[count][];
        for (int s = 0; s < count; s++) {
            int mask = in.readUnsignedByte();
            if (((mask >>> 4) & ~(mask & 15)) != 0) throw new IllegalArgumentException("Invalid RGB channel mask");
            for (int c = 0; c < 3; c++) if ((mask & 1 << c) != 0) { data[c][s] = RgbSnapshot.readPlane(in, (mask & 1 << (c + 4)) != 0); }
            if ((mask & 8) != 0) { canonical[s] = RgbSnapshot.readPlane(in, (mask & 128) != 0); }
        }
        if (in.isReadable()) throw new IllegalArgumentException("Trailing RGB packet data");
        snapshot = RgbSnapshot.capture(min, revision, data, canonical, null);
    }
}
