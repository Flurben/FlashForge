package com.flurben.flashforge.mixin.compat.voicechat;

import com.flurben.flashforge.playback.ReplayPlayer;
import de.maxhenkel.voicechat.voice.client.ClientPlayerStateManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(value = ClientPlayerStateManager.class, remap = false)
public abstract class MixinClientPlayerStateManager {
    /** A replay has no voice server, and the mod won't play anything while it believes it's disconnected. */
    @Inject(method = "isPlayerDisconnected", at = @At("HEAD"), cancellable = true, require = 0)
    private void flashforge$connectedInReplay(CallbackInfoReturnable<Boolean> cir) {
        if (ReplayPlayer.isActive()) cir.setReturnValue(false);
    }
}
