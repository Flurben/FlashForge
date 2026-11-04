package com.flurben.flashforge.mixin;

import com.flurben.flashforge.export.AudioCapture;
import com.mojang.blaze3d.audio.Library;
import org.lwjgl.openal.ALC10;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.nio.IntBuffer;

/** Swaps the output device for a silent loopback device while an export is capturing audio. */
@Mixin(Library.class)
public abstract class MixinLibrary {
    @Inject(method = "openDeviceOrFallback", at = @At("HEAD"), cancellable = true)
    private static void flashforge$openLoopback(CallbackInfoReturnable<Long> cir) {
        if (!AudioCapture.armed()) return;
        long device = AudioCapture.openLoopback();
        if (device != 0) cir.setReturnValue(device);
    }

    @Redirect(method = "init", at = @At(value = "INVOKE", target = "Lorg/lwjgl/openal/ALC10;alcCreateContext(JLjava/nio/IntBuffer;)J", remap = false))
    private long flashforge$createContext(long device, IntBuffer attributes) {
        return ALC10.alcCreateContext(device, AudioCapture.armed() ? AudioCapture.contextAttributes() : attributes);
    }
}
