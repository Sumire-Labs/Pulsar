package com.sumirelabs.pulsar.dynamic;

import io.netty.buffer.ByteBuf;
import net.minecraftforge.fml.common.network.simpleimpl.IMessage;
import java.io.ByteArrayOutputStream;
import java.util.zip.*;

/** Bounded compressed volume, or a tombstone. The client world-session token rejects late packets. */
public final class DynamicLightPacket implements IMessage {
    public long session, revision;
    public int dimension, source;
    public DynamicLights.Mode mode;
    public boolean rgb, projected;
    public DynamicLightField field;
    public DynamicLightPacket() {}
    DynamicLightPacket(long session, int dimension, int source, long revision, DynamicLightField field) {
        this.session = session; this.dimension = dimension; this.source = source; this.revision = revision; this.field = field;
        mode = DynamicLights.mode(); rgb = com.sumirelabs.pulsar.core.RgbMixinSelection.rgbEnabled();
        projected = com.sumirelabs.pulsar.rgb.RgbSettings.projectsGameplayLight();
    }
    public void toBytes(ByteBuf out) {
        out.writeLong(session).writeInt(dimension).writeInt(source).writeLong(revision)
                .writeByte(mode.ordinal()).writeBoolean(rgb).writeBoolean(projected).writeBoolean(field != null);
        if (field == null) return;
        out.writeInt(field.x).writeInt(field.y).writeInt(field.z).writeByte(field.radius).writeBoolean(field.hasVisualData());
        byte[] raw = new byte[field.size() * (field.hasVisualData() ? 10 : 2)];
        for (int i = 0; i < field.size(); i++) { raw[i * 2] = (byte) field.cell(i); raw[i * 2 + 1] = (byte) (field.cell(i) >>> 8); }
        if (field.hasVisualData()) for (int i=0;i<field.size();i++) for(int lane=0;lane<4;lane++) {
            int at=field.size()*2+(i*4+lane)*2,value=field.visualLoss(i,lane);
            raw[at]=(byte)value;raw[at+1]=(byte)(value>>>8);
        }
        Deflater deflater = new Deflater(Deflater.BEST_SPEED);
        try {
            deflater.setInput(raw); deflater.finish();
            ByteArrayOutputStream bytes = new ByteArrayOutputStream(); byte[] buffer = new byte[4096];
            while (!deflater.finished()) bytes.write(buffer, 0, deflater.deflate(buffer));
            out.writeInt(bytes.size()).writeBytes(bytes.toByteArray());
        } finally { deflater.end(); }
    }
    public void fromBytes(ByteBuf in) {
        session = in.readLong(); dimension = in.readInt(); source = in.readInt(); revision = in.readLong();
        int ordinal = in.readUnsignedByte();
        if (ordinal >= DynamicLights.Mode.values().length) throw new IllegalArgumentException("Invalid dynamic light mode");
        mode = DynamicLights.Mode.values()[ordinal]; rgb = in.readBoolean(); projected = in.readBoolean();
        if (in.readBoolean()) {
            int x = in.readInt(), y = in.readInt(), z = in.readInt(), radius = in.readUnsignedByte();
            if (radius > 14) throw new IllegalArgumentException("Invalid dynamic light radius");
            boolean visual=in.readBoolean();
            int length = in.readInt();
            if (length < 1 || length > 262144 || length != in.readableBytes()) throw new IllegalArgumentException("Invalid dynamic light payload");
            byte[] compressed = new byte[length]; in.readBytes(compressed);
            int size=DynamicLightField.cube(radius);
            byte[] raw = new byte[size * (visual ? 10 : 2)];
            Inflater inflater = new Inflater();
            try {
                inflater.setInput(compressed);
                int offset = 0;
                while (offset < raw.length) {
                    int count = inflater.inflate(raw, offset, raw.length - offset);
                    if (count == 0) break;
                    offset += count;
                }
                if (offset != raw.length || !inflater.finished() || inflater.getRemaining() != 0)
                    throw new IllegalArgumentException("Invalid compressed dynamic light volume");
            } catch (DataFormatException exception) { throw new IllegalArgumentException("Invalid dynamic light compression", exception); }
            finally { inflater.end(); }
            short[] cells = new short[size];
            for (int i = 0; i < cells.length; i++) cells[i] = (short) ((raw[i * 2] & 255) | (raw[i * 2 + 1] & 255) << 8);
            short[] losses=visual?new short[size*4]:null;
            if(visual)for(int i=0;i<losses.length;i++){int at=size*2+i*2;losses[i]=(short)((raw[at]&255)|(raw[at+1]&255)<<8);}
            field = new DynamicLightField(x, y, z, radius, cells, losses);
        }
        if (in.isReadable()) throw new IllegalArgumentException("Trailing dynamic light data");
    }
}
