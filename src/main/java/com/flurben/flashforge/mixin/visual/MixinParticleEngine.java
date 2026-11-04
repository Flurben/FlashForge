package com.flurben.flashforge.mixin.visual;

import com.flurben.flashforge.client.VisualsRuntime;
import net.minecraft.client.particle.ParticleEngine;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ParticleEngine.class)
public abstract class MixinParticleEngine {
    // This overload is added by Forge, so it has no obfuscated name to remap.
    @Inject(method = "render(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource$BufferSource;Lnet/minecraft/client/renderer/LightTexture;Lnet/minecraft/client/Camera;FLnet/minecraft/client/renderer/culling/Frustum;)V", at = @At("HEAD"), cancellable = true, remap = false, require = 0)
    private void flashforge$particles(CallbackInfo ci) {
        if (VisualsRuntime.hideParticles()) ci.cancel();
    }
}
