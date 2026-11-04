package com.flurben.flashforge.mixin.visual;

import com.flurben.flashforge.client.SpectatorHud;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.resources.ResourceLocation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(AbstractClientPlayer.class)
public abstract class MixinAbstractClientPlayer {
    @Inject(method = "getCloakTextureLocation", at = @At("HEAD"), cancellable = true)
    private void flashforge$cape(CallbackInfoReturnable<ResourceLocation> cir) {
        if (com.flurben.flashforge.client.VisualsRuntime.hideCape((AbstractClientPlayer) (Object) this)) cir.setReturnValue(null);
    }

    @Inject(method = "getSkinTextureLocation", at = @At("HEAD"), cancellable = true)
    private void flashforge$skin(CallbackInfoReturnable<ResourceLocation> cir) {
        ResourceLocation skin = SpectatorHud.skinFor((AbstractClientPlayer) (Object) this);
        if (skin != null) cir.setReturnValue(skin);
    }
}
