package com.sumirelabs.pulsar.mixin;

import com.sumirelabs.pulsar.compat.MultipartLightBlock;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;

@Pseudo
@Mixin(targets = "codechicken.multipart.BlockMultipart", remap = false)
public abstract class MixinMultipartLightBlock implements MultipartLightBlock {}
