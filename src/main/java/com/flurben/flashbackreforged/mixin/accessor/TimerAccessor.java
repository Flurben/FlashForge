package com.flurben.flashbackreforged.mixin.accessor;

import net.minecraft.client.Timer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(Timer.class)
public interface TimerAccessor {
    @Mutable
    @Accessor("msPerTick")
    void flashbackreforged$setMsPerTick(float value);

    @Accessor("lastMs")
    void flashbackreforged$setLastMs(long value);
}
