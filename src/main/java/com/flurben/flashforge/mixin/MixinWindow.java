package com.flurben.flashforge.mixin;

import com.flurben.flashforge.client.EditorSizing;
import com.flurben.flashforge.export.ExportManager;
import com.mojang.blaze3d.platform.Window;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Everything sizes itself from these two, including shader packs, so this is how an export renders at any resolution. */
@Mixin(Window.class)
public abstract class MixinWindow {
    @Shadow public abstract double getGuiScale();

    private static int[] flashforge$size() {
        int[] size = ExportManager.renderSize();
        return size != null ? size : EditorSizing.current();
    }

    @Inject(method = "getWidth", at = @At("HEAD"), cancellable = true)
    private void flashforge$width(CallbackInfoReturnable<Integer> cir) {
        int[] size = flashforge$size();
        if (size != null) cir.setReturnValue(size[0]);
    }

    @Inject(method = "getHeight", at = @At("HEAD"), cancellable = true)
    private void flashforge$height(CallbackInfoReturnable<Integer> cir) {
        int[] size = flashforge$size();
        if (size != null) cir.setReturnValue(size[1]);
    }

    /** The HUD lays itself out from these, so without them it would stretch when the shape differs from the window's. */
    @Inject(method = "getGuiScaledWidth", at = @At("HEAD"), cancellable = true)
    private void flashforge$guiWidth(CallbackInfoReturnable<Integer> cir) {
        int[] size = flashforge$size();
        if (size != null) cir.setReturnValue((int) Math.ceil(size[0] / getGuiScale()));
    }

    @Inject(method = "getGuiScaledHeight", at = @At("HEAD"), cancellable = true)
    private void flashforge$guiHeight(CallbackInfoReturnable<Integer> cir) {
        int[] size = flashforge$size();
        if (size != null) cir.setReturnValue((int) Math.ceil(size[1] / getGuiScale()));
    }
}
