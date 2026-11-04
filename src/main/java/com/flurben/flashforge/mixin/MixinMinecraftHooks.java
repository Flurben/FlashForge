package com.flurben.flashforge.mixin;

import com.flurben.flashforge.client.VisualsRuntime;
import com.flurben.flashforge.client.ui.EditorUi;
import com.flurben.flashforge.export.ExportManager;
import com.flurben.flashforge.playback.ReplayPlayer;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Minecraft.class)
public abstract class MixinMinecraftHooks {
    @Shadow public abstract boolean isWindowActive();

    /** The frame is complete here: game, shaders and HUD are all in the main target. */
    @Inject(method = "runTick", at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/pipeline/RenderTarget;blitToScreen(II)V"))
    private void flashforge$frameDone(boolean ticking, CallbackInfo ci) {
        ExportManager.onFrameRendered();
    }

    @Inject(method = "runTick", at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/platform/Window;updateDisplay()V"))
    private void flashforge$drawEditor(boolean ticking, CallbackInfo ci) {
        EditorUi.get().render();
    }

    /** Export as fast as the machine allows. */
    @Inject(method = "getFramerateLimit", at = @At("HEAD"), cancellable = true)
    private void flashforge$unlimited(CallbackInfoReturnable<Integer> cir) {
        if (ExportManager.isExporting()) cir.setReturnValue(260);
    }

    /** A replay has no use for attack, inventory or hotbar keys; the viewer is not a player in the recorded world. */
    @Inject(method = "handleKeybinds", at = @At("HEAD"), cancellable = true)
    private void flashforge$noGameKeys(CallbackInfo ci) {
        if (ReplayPlayer.isActive()) ci.cancel();
    }

    /** Clicking away from the game must not interrupt an export or cover the editor with the pause menu. */
    @Inject(method = "pauseGame", at = @At("HEAD"), cancellable = true)
    private void flashforge$noFocusPause(boolean showMenu, CallbackInfo ci) {
        if (ExportManager.isExporting() || (ReplayPlayer.isActive() && !this.isWindowActive())) ci.cancel();
    }

    @Inject(method = "shouldEntityAppearGlowing", at = @At("HEAD"), cancellable = true)
    private void flashforge$glow(Entity entity, CallbackInfoReturnable<Boolean> cir) {
        if (VisualsRuntime.forceGlow(entity)) cir.setReturnValue(true);
    }
}
