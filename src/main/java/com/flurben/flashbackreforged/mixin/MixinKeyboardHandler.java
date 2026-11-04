package com.flurben.flashbackreforged.mixin;

import com.flurben.flashbackreforged.client.ui.EditorUi;
import net.minecraft.client.KeyboardHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(KeyboardHandler.class)
public abstract class MixinKeyboardHandler {
    @Inject(method = "keyPress", at = @At("HEAD"), cancellable = true)
    private void flashbackreforged$key(long window, int key, int scancode, int action, int mods, CallbackInfo ci) {
        if (EditorUi.get().captureKeyboard()) ci.cancel();
    }

    @Inject(method = "charTyped", at = @At("HEAD"), cancellable = true)
    private void flashbackreforged$chars(long window, int codepoint, int mods, CallbackInfo ci) {
        if (EditorUi.get().captureKeyboard()) ci.cancel();
    }
}
