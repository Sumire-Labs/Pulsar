/* SPDX-License-Identifier: LGPL-3.0-only */
package com.sumirelabs.pulsar.mixin;

import com.sumirelabs.pulsar.rgb.RgbShaderSourcePatcher;
import com.sumirelabs.pulsar.rgb.RgbShaderRuntime;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import java.util.*;

@Pseudo
@Mixin(targets = "net.coderbot.iris.pipeline.transform.TransformPatcher", remap = false)
public abstract class MixinRgbShaderSources {
    @Inject(method = "transform(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Lnet/coderbot/iris/pipeline/transform/parameter/Parameters;)Ljava/util/Map;",
            at = @At("RETURN"), cancellable = true, require = 0, remap = false)
    private static void pulsar$lighting(String vertex, String geometry, String control, String evaluation,
                                      String fragment, @Coerce Object parameters, CallbackInfoReturnable<Map<?, String>> cir) {
        Map<?, String> original = cir.getReturnValue(); if (original == null) return;
        Object vertexKey = null, fragmentKey = null;
        for (Object key : original.keySet()) {
            String name = key instanceof Enum<?> type ? type.name() : key.toString();
            if (name.equals("VERTEX")) vertexKey = key; else if (name.equals("FRAGMENT")) fragmentKey = key;
        }
        if (fragmentKey == null) return;
        var result = RgbShaderSourcePatcher.patch(vertexKey == null ? null : original.get(vertexKey), original.get(fragmentKey),
                geometry != null || control != null || evaluation != null);
        if (!result.changed()) return;
        RgbShaderRuntime.patched();
        Map<Object, String> copy = new LinkedHashMap<>(); original.forEach(copy::put);
        if (vertexKey != null) copy.put(vertexKey, result.vertex()); copy.put(fragmentKey, result.fragment());
        cir.setReturnValue(Collections.unmodifiableMap(copy));
    }
}
