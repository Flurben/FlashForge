package com.flurben.flashforge.mixin;

import com.flurben.flashforge.export.ExportManager;
import com.mojang.blaze3d.platform.Window;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Everything sizes itself from these two, including shader packs, so this is how an export renders at any resolution. */
@Mixin(Window.class)
public abstract class MixinWindow {
    @Inject(method = "getWidth", at = @At("HEAD"), cancellable = true)
    private void flashforge$width(CallbackInfoReturnable<Integer> cir) {
        int[] size = ExportManager.renderSize();
        if (size != null) cir.setReturnValue(size[0]);
    }

    @Inject(method = "getHeight", at = @At("HEAD"), cancellable = true)
    private void flashforge$height(CallbackInfoReturnable<Integer> cir) {
        int[] size = ExportManager.renderSize();
        if (size != null) cir.setReturnValue(size[1]);
    }
}
