package com.flurben.flashforge.mixin;

import com.flurben.flashforge.export.ExportManager;
import net.minecraft.client.Timer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Timer.class)
public abstract class MixinTimer {
    @Shadow private long lastMs;

    /** During an export, video frames set the pace instead of the wall clock. */
    @Inject(method = "advanceTime", at = @At("HEAD"), cancellable = true)
    private void flashforge$exportClock(long ms, CallbackInfoReturnable<Integer> cir) {
        int ticks = ExportManager.onAdvanceTime((Timer) (Object) this);
        if (ticks >= 0) {
            this.lastMs = ms;
            cir.setReturnValue(ticks);
        }
    }
}
