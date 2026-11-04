package com.flurben.flashbackreforged.mixin.accessor;

import net.minecraft.client.Camera;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(GameRenderer.class)
public interface GameRendererInvoker {
    @Invoker("getFov") double flashbackreforged$getFov(Camera camera, float partialTicks, boolean useConfigured);
    @Accessor("fov") void flashbackreforged$setFovModifier(float value);
    @Accessor("oldFov") void flashbackreforged$setOldFovModifier(float value);
}
