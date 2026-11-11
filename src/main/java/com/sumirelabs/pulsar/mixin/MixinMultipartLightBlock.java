/*
 * SPDX-License-Identifier: LGPL-3.0-only
 */

package com.sumirelabs.pulsar.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;

import com.sumirelabs.pulsar.compat.MultipartLightBlock;

@Pseudo
@Mixin(targets = "codechicken.multipart.BlockMultipart", remap = false)
public abstract class MixinMultipartLightBlock implements MultipartLightBlock {
}
