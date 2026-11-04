package com.flurben.flashbackreforged.mixin.visual;

import com.flurben.flashbackreforged.client.VisualsRuntime;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.LightTexture;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** require = 0: renderer mods replace some of these, and losing one toggle beats failing to start. */
@Mixin(LevelRenderer.class)
public abstract class MixinLevelRenderer {
    @Inject(method = "renderSky(Lcom/mojang/blaze3d/vertex/PoseStack;Lorg/joml/Matrix4f;FLnet/minecraft/client/Camera;ZLjava/lang/Runnable;)V", at = @At("HEAD"), cancellable = true, require = 0)
    private void flashbackreforged$sky(CallbackInfo ci) {
        if (VisualsRuntime.hideSky()) ci.cancel();
    }

    @Inject(method = "renderClouds(Lcom/mojang/blaze3d/vertex/PoseStack;Lorg/joml/Matrix4f;FDDD)V", at = @At("HEAD"), cancellable = true, require = 0)
    private void flashbackreforged$clouds(CallbackInfo ci) {
        if (VisualsRuntime.hideClouds()) ci.cancel();
    }

    @Inject(method = "renderSnowAndRain", at = @At("HEAD"), cancellable = true, require = 0)
    private void flashbackreforged$weather(LightTexture light, float partial, double x, double y, double z, CallbackInfo ci) {
        if (VisualsRuntime.hideWeather()) ci.cancel();
    }

    @Inject(method = "renderWorldBorder", at = @At("HEAD"), cancellable = true, require = 0)
    private void flashbackreforged$border(CallbackInfo ci) {
        if (VisualsRuntime.hideWorldBorder()) ci.cancel();
    }

    @Inject(method = "renderChunkLayer", at = @At("HEAD"), cancellable = true, require = 0)
    private void flashbackreforged$blocks(CallbackInfo ci) {
        if (VisualsRuntime.hideBlocks()) ci.cancel();
    }
}
