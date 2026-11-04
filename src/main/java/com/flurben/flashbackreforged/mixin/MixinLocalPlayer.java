package com.flurben.flashbackreforged.mixin;

import com.flurben.flashbackreforged.playback.ReplayPlayer;
import net.minecraft.client.player.LocalPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** The viewer is positioned by ViewerCamera each frame, so physics and input handling must not run on it. */
@Mixin(LocalPlayer.class)
public abstract class MixinLocalPlayer {
    @Inject(method = "aiStep", at = @At("HEAD"), cancellable = true)
    private void flashbackreforged$noViewerPhysics(CallbackInfo ci) {
        if (ReplayPlayer.isActive()) ci.cancel();
    }
}
