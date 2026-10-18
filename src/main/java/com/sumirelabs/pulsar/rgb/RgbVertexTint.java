package com.sumirelabs.pulsar.rgb;

import net.minecraft.client.renderer.BufferBuilder;
import net.minecraft.client.renderer.vertex.VertexFormat;
import net.minecraft.client.renderer.vertex.VertexFormatElement;
import java.nio.ByteBuffer;

/** Completed ranges are tinted once, after material-color writes and before draw. */
public final class RgbVertexTint {
    private RgbVertexTint() {}
    private static int type(VertexFormatElement.EnumType type) {
        return switch(type) { case FLOAT -> 1;case BYTE -> 2;case SHORT -> 3;case USHORT -> 4;case INT -> 5;case UINT -> 6;default -> 0; };
    }
    public static long layout(VertexFormat format) {
        int uv=-1,uvType=0,colorType=0,count=0;
        for(int i=0;i<format.getElementCount();i++) {
            var element=format.getElement(i);
            if(element.getUsage()==VertexFormatElement.EnumUsage.COLOR){colorType=type(element.getType());count=element.getElementCount();}
            if(element.getUsage()==VertexFormatElement.EnumUsage.UV && element.getIndex()==1){uv=format.getUvOffsetById(1);uvType=type(element.getType());}
        }
        return (format.getSize()&0xFFFFL)|(format.getColorOffset()&0xFFFFL)<<16|((uv+1)&0xFFFFL)<<32
                |(long)uvType<<48|(long)colorType<<51|(long)count<<54;
    }
    private static int size(int type){return type==1 || type==5 || type==6?4:type==3 || type==4?2:1;}
    private static boolean bright(ByteBuffer b,int at,int type) {
        return switch(type) {
            case 1 -> b.getFloat(at)>=240;
            case 2 -> b.get(at)>=240;
            case 3 -> b.getShort(at)>=240;
            case 4 -> Short.toUnsignedInt(b.getShort(at))>=240;
            case 5 -> b.getInt(at)>=240;
            case 6 -> Integer.toUnsignedLong(b.getInt(at))>=240;
            default -> Byte.toUnsignedInt(b.get(at))>=240;
        };
    }
    public static void apply(BufferBuilder buffer,int start,long generation,RgbDrawState state) {
        apply(buffer,(RgbDynamicVertices)buffer,start,generation,state);
    }
    public static void apply(BufferBuilder buffer,RgbDynamicVertices tags,int start,long generation,RgbDrawState state) {
        if (RgbShaderRuntime.active()) return;
        if(!state.active() || !buffer.getVertexFormat().hasColor())return;
        int end=buffer.getVertexCount();if(generation!=tags.pulsar$rgbGeneration())start=0;
        start=Math.max(0,Math.min(start,end));
        long layout=tags.pulsar$rgbLayout(buffer.getVertexFormat());
        int stride=(int)layout&65535,offset=(int)(layout>>>16)&65535,uv=((int)(layout>>>32)&65535)-1;
        int uvType=(int)(layout>>>48)&7,colorType=(int)(layout>>>51)&7,count=(int)(layout>>>54)&7;
        if(count<3 || count>4)return;
        var bytes=buffer.getByteBuffer();int component=size(colorType);
        for(int v=start;v<end;v++) {
            if(tags.pulsar$rgbProcessed(v))continue;
            int base=v*stride;
            boolean full=uv>=0 && bright(bytes,base+uv,uvType) && bright(bytes,base+uv+size(uvType),uvType);
            if(state.tintsVertices(uv>=0,full)) {
                for(int c=0;c<3;c++) {
                    float factor=state.factor(c);if(factor==1)continue;
                    int at=base+offset+c*component;
                    switch(colorType) {
                        case 1 -> bytes.putFloat(at,Math.max(0,Math.min(1,bytes.getFloat(at)))*factor);
                        case 2 -> bytes.put(at,(byte)Math.round(bytes.get(at)*factor));
                        case 3 -> bytes.putShort(at,(short)Math.round(bytes.getShort(at)*factor));
                        case 4 -> bytes.putShort(at,(short)Math.round(Short.toUnsignedInt(bytes.getShort(at))*factor));
                        case 5 -> bytes.putInt(at,(int)Math.round(bytes.getInt(at)*(double)factor));
                        case 6 -> bytes.putInt(at,(int)Math.round(Integer.toUnsignedLong(bytes.getInt(at))*(double)factor));
                        default -> bytes.put(at,(byte)Math.round(Byte.toUnsignedInt(bytes.get(at))*factor));
                    }
                }
            }
            tags.pulsar$markRgb(v,v+1);
        }
    }
    public static void mark(BufferBuilder buffer,int start,long generation) {
        var tags=(RgbDynamicVertices)buffer;
        if(generation!=tags.pulsar$rgbGeneration())start=0;
        int end=buffer.getVertexCount();start=Math.max(0,Math.min(start,end));
        tags.pulsar$markRgb(start,end);
    }
}
