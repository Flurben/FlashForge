package com.flurben.flashforge.mixin;

import com.flurben.flashforge.client.ui.EditorUi;
import net.minecraft.client.MouseHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** The cursor stays free for the editor panels unless the user is flying the camera. */
@Mixin(MouseHandler.class)
public abstract class MixinMouseHandler {
    @Inject(method = "grabMouse", at = @At("HEAD"), cancellable = true)
    private void flashforge$grab(CallbackInfo ci) {
        if (EditorUi.get().captureMouse()) ci.cancel();
    }

    @Inject(method = "onPress", at = @At("HEAD"), cancellable = true)
    private void flashforge$press(long window, int button, int action, int mods, CallbackInfo ci) {
        if (EditorUi.get().captureMouse()) ci.cancel();
    }

    @Inject(method = "onScroll", at = @At("HEAD"), cancellable = true)
    private void flashforge$scroll(long window, double x, double y, CallbackInfo ci) {
        if (EditorUi.get().captureMouse()) ci.cancel();
    }
}
