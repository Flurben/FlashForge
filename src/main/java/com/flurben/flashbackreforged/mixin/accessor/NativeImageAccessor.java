package com.flurben.flashbackreforged.mixin.accessor;

import com.mojang.blaze3d.platform.NativeImage;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(NativeImage.class)
public interface NativeImageAccessor {
    /** The address of the image's memory, for filling it faster than a pixel at a time. */
    @Accessor("pixels")
    long flashbackreforged$pixels();
}
