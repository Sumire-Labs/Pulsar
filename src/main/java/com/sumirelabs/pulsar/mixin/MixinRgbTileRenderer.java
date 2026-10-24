package com.sumirelabs.pulsar.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.sumirelabs.pulsar.rgb.RgbDrawContext;
import com.sumirelabs.pulsar.rgb.RgbDynamicVertices;
import com.sumirelabs.pulsar.rgb.RgbVertexTint;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.renderer.BufferBuilder;
import org.spongepowered.asm.mixin.Shadow;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.tileentity.TileEntityRendererDispatcher;
import net.minecraft.tileentity.TileEntity;
import org.spongepowered.asm.mixin.Mixin;

@Mixin(TileEntityRendererDispatcher.class)
public abstract class MixinRgbTileRenderer {
    @Shadow(remap=false) private Tessellator batchBuffer;
    @Shadow(remap=false) private boolean drawingBatch;
    @org.spongepowered.asm.mixin.Unique
    private static int pulsar$enter(TileEntity tile,int destroy,boolean batch) {
        if ((!RgbDrawContext.state().worldPass() && destroy<0) || tile.getWorld() != Minecraft.getMinecraft().world) return 0;
        var pos = tile.getPos();
        return batch ? RgbDrawContext.enterVertices(tile,pos.getX()+.5,pos.getY()+.5,pos.getZ()+.5)
                : RgbDrawContext.enter(tile,pos.getX()+.5,pos.getY()+.5,pos.getZ()+.5);
    }
    @WrapMethod(method="render(Lnet/minecraft/tileentity/TileEntity;FI)V")
    private void pulsar$tileBatch(TileEntity tile,float partial,int destroy,Operation<Void> original) {
        boolean batch=drawingBatch && tile.hasFastRenderer();
        BufferBuilder buffer=batch?batchBuffer.getBuffer():null;
        int start=batch?buffer.getVertexCount():0;long generation=batch?((RgbDynamicVertices)buffer).pulsar$rgbGeneration():0;
        int token = pulsar$enter(tile,destroy,batch);
        try { original.call(tile,partial,destroy);if(batch)RgbVertexTint.apply(buffer,start,generation,RgbDrawContext.state()); }
        finally { RgbDrawContext.close(token); }
    }
    @WrapMethod(method="render(Lnet/minecraft/tileentity/TileEntity;DDDFIF)V")
    private void pulsar$tileDraw(TileEntity tile,double x,double y,double z,float partial,int destroy,float alpha,Operation<Void> original) {
        int token = pulsar$enter(tile,destroy,false);
        try { original.call(tile,x,y,z,partial,destroy,alpha); }
        finally { RgbDrawContext.close(token); }
    }
}
