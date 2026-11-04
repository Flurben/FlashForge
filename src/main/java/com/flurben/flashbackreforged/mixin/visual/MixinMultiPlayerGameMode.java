package com.flurben.flashbackreforged.mixin.visual;

import com.flurben.flashbackreforged.client.SpectatorHud;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.world.level.GameType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(MultiPlayerGameMode.class)
public abstract class MixinMultiPlayerGameMode {
    @Inject(method = "getPlayerMode", at = @At("HEAD"), cancellable = true)
    private void flashbackreforged$mode(CallbackInfoReturnable<GameType> cir) {
        GameType mode = SpectatorHud.renderMode();
        if (mode != null) cir.setReturnValue(mode);
    }

    @Inject(method = "canHurtPlayer", at = @At("HEAD"), cancellable = true)
    private void flashbackreforged$hearts(CallbackInfoReturnable<Boolean> cir) {
        GameType mode = SpectatorHud.renderMode();
        if (mode != null) cir.setReturnValue(mode.isSurvival());
    }
}
