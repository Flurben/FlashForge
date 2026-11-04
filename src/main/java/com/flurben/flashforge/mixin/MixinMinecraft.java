package com.flurben.flashforge.mixin;

import com.flurben.flashforge.playback.ReplayPlayer;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

@Mixin(Minecraft.class)
public abstract class MixinMinecraft {
    /**
     * The vanilla "pause the game" flag freezes level ticking and partial ticks while frames keep rendering, which is
     * exactly what a paused replay needs. Vanilla only allows it for singleplayer pause screens.
     */
    // Boolean locals in runTick: the parameter (0), the frame-profiler flag (1), then the pause flag (2).
    @ModifyVariable(method = "runTick", at = @At("STORE"), ordinal = 2)
    private boolean flashforge$pauseReplay(boolean vanilla) {
        ReplayPlayer replay = ReplayPlayer.active();
        return replay != null ? replay.worldStopped() : vanilla;
    }
}
