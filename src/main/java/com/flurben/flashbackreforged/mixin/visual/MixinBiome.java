package com.flurben.flashbackreforged.mixin.visual;

import com.flurben.flashbackreforged.client.VisualsRuntime;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.biome.Biome;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Lets a weather override show rain or snow in any biome. The low priority puts it ahead of mods that decide precipitation
 * themselves, like TerraFirmaCraft; it only steps in while an override is set.
 */
@Mixin(value = Biome.class, priority = 500)
public abstract class MixinBiome {
    @Inject(method = "getPrecipitationAt", at = @At("HEAD"), cancellable = true, require = 0)
    private void flashbackreforged$precipitation(BlockPos pos, CallbackInfoReturnable<Biome.Precipitation> cir) {
        Biome.Precipitation forced = VisualsRuntime.forcedPrecipitation();
        if (forced != null) cir.setReturnValue(forced);
    }

    @Inject(method = "hasPrecipitation", at = @At("HEAD"), cancellable = true, require = 0)
    private void flashbackreforged$hasPrecipitation(CallbackInfoReturnable<Boolean> cir) {
        if (VisualsRuntime.forcedPrecipitation() != null) cir.setReturnValue(true);
    }
}
