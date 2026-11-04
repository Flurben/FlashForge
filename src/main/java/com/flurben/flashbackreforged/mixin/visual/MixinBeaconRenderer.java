package com.flurben.flashbackreforged.mixin.visual;

import com.flurben.flashbackreforged.client.VisualsRuntime;
import net.minecraft.client.renderer.blockentity.BeaconRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(BeaconRenderer.class)
public abstract class MixinBeaconRenderer {
    @Inject(method = "render(Lnet/minecraft/world/level/block/entity/BeaconBlockEntity;FLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;II)V", at = @At("HEAD"), cancellable = true, require = 0)
    private void flashbackreforged$beam(CallbackInfo ci) {
        if (VisualsRuntime.hideBeacons()) ci.cancel();
    }
}
