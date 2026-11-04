package com.flurben.flashbackreforged.mixin.visual;

import com.flurben.flashbackreforged.client.VisualsRuntime;
import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.texture.DynamicTexture;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(LightTexture.class)
public abstract class MixinLightTexture {
    @Shadow @Final private NativeImage lightPixels;
    @Shadow @Final private DynamicTexture lightTexture;

    @Inject(method = "updateLightTexture", at = @At("HEAD"), cancellable = true)
    private void flashbackreforged$fullBright(float partial, CallbackInfo ci) {
        if (!VisualsRuntime.fullBright()) return;
        for (int y = 0; y < 16; y++) {
            for (int x = 0; x < 16; x++) this.lightPixels.setPixelRGBA(x, y, -1);
        }
        this.lightTexture.upload();
        ci.cancel();
    }
}
