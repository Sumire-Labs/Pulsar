package com.sumirelabs.pulsar.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.sumirelabs.pulsar.rgb.RgbDrawContext;
import com.sumirelabs.pulsar.rgb.RgbDynamicVertices;
import com.sumirelabs.pulsar.rgb.RgbVertexTint;
import net.minecraft.client.renderer.BufferBuilder;
import net.minecraft.client.renderer.WorldVertexBufferUploader;
import org.spongepowered.asm.mixin.Mixin;
import org.lwjgl.opengl.GL11;

/** COLOR arrays already carry RGB; only fixed-function material colors need the uniform multiplier. */
@Mixin(WorldVertexBufferUploader.class)
public abstract class MixinRgbVertexUploader {
    @WrapMethod(method="draw")
    private void pulsar$coloredDraw(BufferBuilder buffer,Operation<Void> original) {
        if(!RgbDrawContext.state().active() || !buffer.getVertexFormat().hasColor()){original.call(buffer);return;}
        if(GL11.glGetInteger(GL11.GL_LIST_INDEX)!=0){original.call(buffer);return;}
        if(RgbDrawContext.state().active() && buffer.getVertexCount()>0)
            RgbVertexTint.apply(buffer,0,((RgbDynamicVertices)buffer).pulsar$rgbGeneration(),RgbDrawContext.state());
        int token=RgbDrawContext.state().active() && buffer.getVertexCount()>0 && buffer.getVertexFormat().hasColor()
                ? RgbDrawContext.suppressDraw():0;
        try { original.call(buffer); }
        finally { if(token!=0)RgbDrawContext.restoreDraw(token); }
    }
}
