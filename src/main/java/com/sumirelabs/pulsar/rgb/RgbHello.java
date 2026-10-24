package com.sumirelabs.pulsar.rgb;
import io.netty.buffer.ByteBuf;
import net.minecraftforge.fml.common.network.simpleimpl.IMessage;

/** Explicit opt-in avoids sending color payloads to SCALAR and older clients. */
public final class RgbHello implements IMessage {
    public static final int VERSION = 2;
    public int version = VERSION;
    public void toBytes(ByteBuf out) { out.writeInt(version); }
    public void fromBytes(ByteBuf in) {
        if (in.readableBytes() != 4) throw new IllegalArgumentException("Invalid RGB handshake");
        version = in.readInt();
    }
}
