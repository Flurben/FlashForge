package com.flurben.flashforge.mixin.accessor;

import net.minecraft.client.Timer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(Timer.class)
public interface TimerAccessor {
    @Mutable
    @Accessor("msPerTick")
    void flashforge$setMsPerTick(float value);

    @Accessor("lastMs")
    void flashforge$setLastMs(long value);
}
