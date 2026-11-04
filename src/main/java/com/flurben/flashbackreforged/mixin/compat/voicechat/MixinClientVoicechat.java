package com.flurben.flashbackreforged.mixin.compat.voicechat;

import com.flurben.flashbackreforged.playback.ReplayPlayer;
import de.maxhenkel.voicechat.voice.client.ClientVoicechat;
import de.maxhenkel.voicechat.voice.client.ClientVoicechatConnection;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = ClientVoicechat.class, remap = false)
public abstract class MixinClientVoicechat {
    /** Watching a replay must never open the microphone. */
    @Inject(method = "startMicThread", at = @At("HEAD"), cancellable = true, require = 0)
    private void flashbackreforged$noMicInReplay(ClientVoicechatConnection connection, CallbackInfo ci) {
        if (ReplayPlayer.isActive()) ci.cancel();
    }
}
