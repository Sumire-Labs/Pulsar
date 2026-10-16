package com.sumirelabs.pulsar.mixin;

import com.sumirelabs.pulsar.rgb.RgbClient;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.util.math.BlockPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Optional Celeritas 2.4 vintage mesh path; preserves its light/AO and material pipeline. */
@Pseudo
@Mixin(targets = "org.taumc.celeritas.impl.render.terrain.compile.pipeline.VintageBlockRenderer", remap = false)
public abstract class MixinRgbCeleritasRenderer {
    @Inject(method = "getVertexColors", at = @At("RETURN"), require = 0, remap = false)
    private void pulsar$colorVertices(BlockPos pos, @Coerce Object provider, @Coerce Object quad,
                                      CallbackInfoReturnable<int[]> cir) {
        if (!RgbClient.active() || !(quad instanceof BakedQuad baked)) return;
        RgbClient.celeritasRendered = true;
        int[] vertices = baked.getVertexData(), colors = cir.getReturnValue();
        int stride = baked.getFormat().getIntegerSize();
        for (int i = 0; i < 4; i++) colors[i] = RgbClient.tintAbgr(colors[i],
                pos.getX() + Float.intBitsToFloat(vertices[i * stride]),
                pos.getY() + Float.intBitsToFloat(vertices[i * stride + 1]),
                pos.getZ() + Float.intBitsToFloat(vertices[i * stride + 2]));
    }
}
