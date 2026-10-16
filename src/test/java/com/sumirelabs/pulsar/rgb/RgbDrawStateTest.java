package com.sumirelabs.pulsar.rgb;

import net.minecraft.client.renderer.BufferBuilder;
import net.minecraft.client.renderer.vertex.DefaultVertexFormats;
import net.minecraft.client.renderer.vertex.VertexFormat;
import net.minecraft.client.renderer.vertex.VertexFormatElement;
import org.junit.jupiter.api.Test;
import java.util.BitSet;
import static org.junit.jupiter.api.Assertions.*;

class RgbDrawStateTest {
    private static final int RED_GREEN = 0xFF0080FF;
    @Test void nestedScopesRestoreModesCoordinatesAndOwnerWithoutDoubleEntry() {
        var s=new RgbDrawState();s.lightmap=true;s.lightCoordinates(112,80);
        Object owner=new Object();int a=s.push(owner,RED_GREEN,true,false);
        assertTrue(s.uniform());assertEquals(0,s.push(owner,0xFFFFFFFF,false,false));
        int b=s.push(new Object(),0xFFFF0000,false,true);s.lightmap=false;s.lightCoordinates(240,240);
        assertFalse(s.uniform());s.pop(b);assertTrue(s.poppedSession);assertTrue(s.uniform());
        assertEquals(RED_GREEN,s.tint());assertEquals(112,s.lightU);assertEquals(80,s.lightV);
        s.pop(a);assertFalse(s.active());assertEquals(0,s.depth());assertFalse(s.poppedSession);
    }
    @Test void fullbrightAndVertexCoordinatesRespectAlphaAndSeparateDrawSuppression() {
        var s=new RgbDrawState();s.lightmap=true;int token=s.push(new Object(),RED_GREEN,true,false);
        int base=0x4D96C864;assertEquals(0x4D006464,s.tintVertex(base,true,false));
        assertEquals(base,s.tintVertex(base,true,true));s.lightCoordinates(240,240);
        assertFalse(s.uniform());assertEquals(base,s.tintVertex(base,false,false));
        assertEquals(0x4D006464,s.tintVertex(base,true,false));s.lightCoordinates(0,0);
        int draw=s.suppressDraw();assertFalse(s.uniform());assertEquals(0x4D006464,s.tintVertex(base,true,false));
        s.restoreDraw(draw);assertTrue(s.uniform());int vertices=s.suppressVertices();
        assertEquals(base,s.tintVertex(base,true,false));s.restoreVertices(vertices);s.pop(token);
        assertEquals(base,s.tintVertex(base,true,false));
    }
    @Test void finallyClosesNestedWorldAndDrawFramesIncludingGrownStorage() {
        var s=new RgbDrawState();int world=s.enterWorld();int[] tokens=new int[40];
        try { for(int i=0;i<tokens.length;i++)tokens[i]=s.push(new Object(),RED_GREEN,true,false);
            throw new IllegalArgumentException("renderer failure");
        } catch(IllegalArgumentException expected) { assertEquals("renderer failure",expected.getMessage()); }
        finally { for(int i=tokens.length-1;i>=0;i--)s.pop(tokens[i]);s.leaveWorld(world); }
        assertEquals(0,s.depth());assertFalse(s.worldPass());assertFalse(s.active());
    }
    private static final class Tags implements RgbDynamicVertices {
        final BitSet processed=new BitSet();long generation=1;
        public long pulsar$rgbGeneration(){return generation;}
        public long pulsar$rgbLayout(VertexFormat f){return RgbVertexTint.layout(f);}
        public boolean pulsar$rgbProcessed(int v){return processed.get(v);}
        public void pulsar$markRgb(int a,int b){processed.set(a,b);}
    }
    @Test void completedParticleRangesAreTintedOnceAndFullbrightRemainsUnmodified() {
        var b=new BufferBuilder(256);var format=DefaultVertexFormats.PARTICLE_POSITION_TEX_COLOR_LMAP;
        b.begin(7,format);
        b.pos(0,0,0).tex(0,0).color(100,200,150,77).lightmap(112,0).endVertex();
        b.pos(1,0,0).tex(0,0).color(100,200,150,77).lightmap(240,240).endVertex();
        var s=new RgbDrawState();s.lightmap=true;int token=s.push(new Object(),RED_GREEN,false,false);var tags=new Tags();
        RgbVertexTint.apply(b,tags,0,1,s);var bytes=b.getByteBuffer();int color=format.getColorOffset(),stride=format.getSize();
        assertEquals(100,Byte.toUnsignedInt(bytes.get(color)));assertEquals(100,Byte.toUnsignedInt(bytes.get(color+1)));
        assertEquals(0,Byte.toUnsignedInt(bytes.get(color+2)));assertEquals(77,Byte.toUnsignedInt(bytes.get(color+3)));
        assertEquals(200,Byte.toUnsignedInt(bytes.get(stride+color+1)));
        RgbVertexTint.apply(b,tags,0,1,s);assertEquals(100,Byte.toUnsignedInt(bytes.get(color+1)));
        s.pop(token);
    }
    @Test void negativeSignedLightmapCoordinatesAreNotFullbright() {
        var b=new BufferBuilder(256);var f=DefaultVertexFormats.PARTICLE_POSITION_TEX_COLOR_LMAP;b.begin(7,f);
        b.pos(0,0,0).tex(0,0).color(100,200,150,77).lightmap(-1,-1).endVertex();
        b.pos(1,0,0).tex(0,0).color(100,200,150,77).lightmap(240,240).endVertex();
        var state=new RgbDrawState();state.lightmap=true;int token=state.push(new Object(),RED_GREEN,false,false);
        RgbVertexTint.apply(b,new Tags(),0,1,state);int at=f.getColorOffset();
        assertEquals(100,Byte.toUnsignedInt(b.getByteBuffer().get(at+1)));
        assertEquals(200,Byte.toUnsignedInt(b.getByteBuffer().get(f.getSize()+at+1)));
        assertEquals(77,Byte.toUnsignedInt(b.getByteBuffer().get(at+3)));state.pop(token);
    }
    @Test void floatColorAlphaAndThreeComponentPaddingAreNotOverwritten() {
        var f=new VertexFormat().addElement(DefaultVertexFormats.POSITION_3F)
                .addElement(new VertexFormatElement(0,VertexFormatElement.EnumType.FLOAT,VertexFormatElement.EnumUsage.COLOR,4));
        var b=new BufferBuilder(256);b.begin(7,f);b.pos(0,0,0).color(102,204,51,96).endVertex();
        var s=new RgbDrawState();s.lightmap=true;int token=s.push(new Object(),RED_GREEN,false,false);var tags=new Tags();
        int at=f.getColorOffset();float alpha=b.getByteBuffer().getFloat(at+12);
        RgbVertexTint.apply(b,tags,0,1,s);assertEquals(alpha,b.getByteBuffer().getFloat(at+12));
        assertEquals(0,b.getByteBuffer().getFloat(at+8));assertEquals(.8f*128/255,b.getByteBuffer().getFloat(at+4),.00001f);
        f=new VertexFormat().addElement(DefaultVertexFormats.POSITION_3F)
                .addElement(new VertexFormatElement(0,VertexFormatElement.EnumType.UBYTE,VertexFormatElement.EnumUsage.COLOR,3))
                .addElement(new VertexFormatElement(0,VertexFormatElement.EnumType.UBYTE,VertexFormatElement.EnumUsage.PADDING,1));
        b=new BufferBuilder(256);b.begin(7,f);b.pos(0,0,0).color(100,200,150,77).endVertex();tags=new Tags();
        at=f.getColorOffset();int padding=Byte.toUnsignedInt(b.getByteBuffer().get(at+3));RgbVertexTint.apply(b,tags,0,1,s);
        assertEquals(padding,Byte.toUnsignedInt(b.getByteBuffer().get(at+3)));assertEquals(100,Byte.toUnsignedInt(b.getByteBuffer().get(at+1)));
        s.pop(token);
    }
}
