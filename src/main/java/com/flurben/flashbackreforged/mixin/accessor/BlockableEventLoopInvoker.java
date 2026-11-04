package com.flurben.flashbackreforged.mixin.accessor;

import net.minecraft.util.thread.BlockableEventLoop;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(BlockableEventLoop.class)
public interface BlockableEventLoopInvoker {
    @Invoker("runAllTasks")
    void flashbackreforged$runAllTasks();
}
