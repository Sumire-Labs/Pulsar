package com.sumirelabs.pulsar.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.sumirelabs.pulsar.rgb.*;
import net.minecraft.block.state.IBlockState;
import net.minecraft.client.renderer.BlockRendererDispatcher;
import net.minecraft.client.renderer.BufferBuilder;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.IBlockAccess;
import org.spongepowered.asm.mixin.Mixin;
import java.nio.ByteOrder;

@Mixin(BlockRendererDispatcher.class)
public abstract class MixinRgbBlockRenderer {
    @WrapMethod(method="renderBlock")
    private boolean pulsar$terrainDraw(IBlockState state,BlockPos pos,IBlockAccess world,BufferBuilder buffer,Operation<Boolean> original) {
        if(!RgbClient.active() && !RgbClient.skyActive())return original.call(state,pos,world,buffer);
        int start=buffer.getVertexCount(),token=RgbDrawContext.suppressVertices();
        long generation=((RgbDynamicVertices)buffer).pulsar$rgbGeneration();
        try {
            boolean result=original.call(state,pos,world,buffer);
            RgbClient.vanillaRendered=true;
            var format=buffer.getVertexFormat();if(!format.hasColor())return result;
            var bytes=buffer.getByteBuffer();RgbBuffer offsets=(RgbBuffer)buffer;
            int stride=format.getSize(),colorOffset=format.getColorOffset();
            if(generation!=((RgbDynamicVertices)buffer).pulsar$rgbGeneration())start=0;
            for(int i=start;i<buffer.getVertexCount();i++) {
                int at=i*stride,color=bytes.getInt(at+colorOffset);
                if(ByteOrder.nativeOrder()==ByteOrder.BIG_ENDIAN)color=Integer.reverseBytes(color);
                color=RgbClient.tintAbgr(color,bytes.getFloat(at)-offsets.pulsar$xOffset(),
                        bytes.getFloat(at+4)-offsets.pulsar$yOffset(),bytes.getFloat(at+8)-offsets.pulsar$zOffset());
                bytes.putInt(at+colorOffset,ByteOrder.nativeOrder()==ByteOrder.BIG_ENDIAN?Integer.reverseBytes(color):color);
            }
            if(RgbDrawContext.state().active())RgbVertexTint.mark(buffer,start,generation);
            return result;
        } finally { RgbDrawContext.restoreVertices(token); }
    }
}
