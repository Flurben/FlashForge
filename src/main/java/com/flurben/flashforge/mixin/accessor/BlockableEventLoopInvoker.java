package com.flurben.flashforge.mixin.accessor;

import net.minecraft.util.thread.BlockableEventLoop;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(BlockableEventLoop.class)
public interface BlockableEventLoopInvoker {
    @Invoker("runAllTasks")
    void flashforge$runAllTasks();
}
