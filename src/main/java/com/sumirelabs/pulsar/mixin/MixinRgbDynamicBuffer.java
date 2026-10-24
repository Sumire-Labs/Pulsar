package com.sumirelabs.pulsar.mixin;

import com.sumirelabs.pulsar.rgb.RgbDynamicVertices;
import com.sumirelabs.pulsar.rgb.RgbVertexTint;
import net.minecraft.client.renderer.BufferBuilder;
import net.minecraft.client.renderer.vertex.VertexFormat;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import java.util.BitSet;

@Mixin(BufferBuilder.class)
public abstract class MixinRgbDynamicBuffer implements RgbDynamicVertices {
    @Unique private BitSet pulsar$processed;
    @Unique private long pulsar$generation,pulsar$layout;
    @Unique private VertexFormat pulsar$format;
    public long pulsar$rgbGeneration(){return pulsar$generation;}
    public long pulsar$rgbLayout(VertexFormat format){if(format!=pulsar$format){pulsar$format=format;pulsar$layout=RgbVertexTint.layout(format);}return pulsar$layout;}
    public boolean pulsar$rgbProcessed(int vertex){return pulsar$processed!=null && pulsar$processed.get(vertex);}
    public void pulsar$markRgb(int start,int end){if(start==end)return;if(pulsar$processed==null)pulsar$processed=new BitSet(end);pulsar$processed.set(start,end);}
    @Inject(method={"begin","reset","setVertexState"},at=@At("HEAD"))
    private void pulsar$newBuffer(CallbackInfo ci){++pulsar$generation;pulsar$format=null;if(pulsar$processed!=null)pulsar$processed.clear();}
    @Unique private void pulsar$invalidateColor(int index){if(pulsar$processed!=null){int stride=((BufferBuilder)(Object)this).getVertexFormat().getSize();if(index>=0 && stride>0)pulsar$processed.clear(index*4/stride);}}
    @Inject(method="putColorRGBA(IIII)V",at=@At("HEAD"))
    private void pulsar$color4(int index,int r,int g,int b,CallbackInfo ci){pulsar$invalidateColor(index);}
    @Inject(method="putColorRGBA(IIIII)V",at=@At("HEAD"))
    private void pulsar$color5(int index,int r,int g,int b,int a,CallbackInfo ci){pulsar$invalidateColor(index);}
}
