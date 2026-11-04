package com.flurben.flashforge.mixin;

import com.flurben.flashforge.client.ui.EditorUi;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.platform.GlStateManager;
import net.minecraft.client.Minecraft;
import org.lwjgl.opengl.GL11;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(RenderTarget.class)
public abstract class MixinRenderTarget {
    /** Shows the finished game image inside the space the editor panels leave, without changing what is rendered. */
    @Redirect(method = "_blitToScreen", at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/platform/GlStateManager;_viewport(IIII)V"))
    private void flashforge$viewport(int x, int y, int width, int height) {
        int[] view = (Object) this == Minecraft.getInstance().getMainRenderTarget() ? EditorUi.get().blitArea() : null;
        if (view == null) {
            GlStateManager._viewport(x, y, width, height);
            return;
        }
        GL11.glClearColor(0.06f, 0.06f, 0.07f, 1f);
        GL11.glClear(GL11.GL_COLOR_BUFFER_BIT);
        GlStateManager._viewport(view[0], view[1], view[2], view[3]);
    }
}
