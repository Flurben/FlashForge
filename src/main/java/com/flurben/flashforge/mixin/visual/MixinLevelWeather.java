package com.flurben.flashforge.mixin.visual;

import com.flurben.flashforge.client.VisualsRuntime;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Ahead of mods with their own weather, like TerraFirmaCraft, for the same reason as MixinBiome. */
@Mixin(value = Level.class, priority = 500)
public abstract class MixinLevelWeather {
    @Inject(method = "getRainLevel", at = @At("HEAD"), cancellable = true, require = 0)
    private void flashforge$rain(float partial, CallbackInfoReturnable<Float> cir) {
        Float forced = VisualsRuntime.forcedRainLevel();
        if (forced != null) cir.setReturnValue(forced);
    }

    @Inject(method = "getThunderLevel", at = @At("HEAD"), cancellable = true, require = 0)
    private void flashforge$thunder(float partial, CallbackInfoReturnable<Float> cir) {
        Float forced = VisualsRuntime.forcedThunderLevel();
        if (forced != null) cir.setReturnValue(forced);
    }
}
