package com.sumirelabs.pulsar.mixin;

import com.sumirelabs.pulsar.rgb.*;
import net.minecraft.block.state.IBlockState;
import net.minecraft.client.renderer.BlockRendererDispatcher;
import net.minecraft.client.renderer.BufferBuilder;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.IBlockAccess;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import java.nio.ByteOrder;

/** Covers vanilla/Forge baked models and fluids at the completed vertex boundary. */
@Mixin(BlockRendererDispatcher.class)
public abstract class MixinRgbBlockRenderer {
    @Unique private final ThreadLocal<Integer> pulsar$rgbStart = new ThreadLocal<>();
    @Inject(method = "renderBlock", at = @At("HEAD"))
    private void pulsar$beginRgb(IBlockState state, BlockPos pos, IBlockAccess world, BufferBuilder buffer,
                                CallbackInfoReturnable<Boolean> cir) {
        if (RgbClient.active()) pulsar$rgbStart.set(buffer.getVertexCount());
    }
    @Inject(method = "renderBlock", at = @At("RETURN"))
    private void pulsar$finishRgb(IBlockState state, BlockPos pos, IBlockAccess world, BufferBuilder buffer,
                                 CallbackInfoReturnable<Boolean> cir) {
        Integer start = pulsar$rgbStart.get();
        if (start == null) return;
        pulsar$rgbStart.remove();
        RgbClient.vanillaRendered = true;
        var format = buffer.getVertexFormat();
        if (!format.hasColor()) return;
        var bytes = buffer.getByteBuffer();
        RgbBuffer offsets = (RgbBuffer) buffer;
        int stride = format.getSize(), colorOffset = format.getColorOffset();
        for (int i = start; i < buffer.getVertexCount(); i++) {
            int index = i * stride;
            int color = bytes.getInt(index + colorOffset);
            if (ByteOrder.nativeOrder() == ByteOrder.BIG_ENDIAN) color = Integer.reverseBytes(color);
            color = RgbClient.tintAbgr(color, bytes.getFloat(index) - offsets.pulsar$xOffset(),
                    bytes.getFloat(index + 4) - offsets.pulsar$yOffset(), bytes.getFloat(index + 8) - offsets.pulsar$zOffset());
            bytes.putInt(index + colorOffset, ByteOrder.nativeOrder() == ByteOrder.BIG_ENDIAN ? Integer.reverseBytes(color) : color);
        }
    }
}
