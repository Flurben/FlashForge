package com.flurben.flashforge.mixin.accessor;

import net.minecraft.client.Camera;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(GameRenderer.class)
public interface GameRendererInvoker {
    @Invoker("getFov") double flashforge$getFov(Camera camera, float partialTicks, boolean useConfigured);
    @Accessor("fov") void flashforge$setFovModifier(float value);
    @Accessor("oldFov") void flashforge$setOldFovModifier(float value);
}
