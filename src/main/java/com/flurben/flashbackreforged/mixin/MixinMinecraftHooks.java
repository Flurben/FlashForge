package com.flurben.flashbackreforged.mixin;

import com.flurben.flashbackreforged.client.VisualsRuntime;
import com.flurben.flashbackreforged.client.ui.EditorUi;
import com.flurben.flashbackreforged.export.ExportManager;
import com.flurben.flashbackreforged.playback.ReplayPlayer;
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
    private void flashbackreforged$frameDone(boolean ticking, CallbackInfo ci) {
        ExportManager.onFrameRendered();
    }

    @Inject(method = "runTick", at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/platform/Window;updateDisplay()V"))
    private void flashbackreforged$drawEditor(boolean ticking, CallbackInfo ci) {
        EditorUi.get().render();
    }

    /** Export as fast as the machine allows. */
    @Inject(method = "getFramerateLimit", at = @At("HEAD"), cancellable = true)
    private void flashbackreforged$unlimited(CallbackInfoReturnable<Integer> cir) {
        if (ExportManager.isExporting()) cir.setReturnValue(260);
    }

    /** A replay has no use for attack, inventory or hotbar keys; the viewer is not a player in the recorded world. */
    @Inject(method = "handleKeybinds", at = @At("HEAD"), cancellable = true)
    private void flashbackreforged$noGameKeys(CallbackInfo ci) {
        if (!ReplayPlayer.isActive()) return;
        ci.cancel();
        // Chat stays reachable, because /hide and /show are typed there.
        Minecraft mc = (Minecraft) (Object) this;
        if (ExportManager.isExporting()) return;
        while (mc.options.keyChat.consumeClick()) mc.setScreen(new net.minecraft.client.gui.screens.ChatScreen(""));
        while (mc.options.keyCommand.consumeClick()) mc.setScreen(new net.minecraft.client.gui.screens.ChatScreen("/"));
    }

    /** Clicking away from the game must not interrupt an export or cover the editor with the pause menu. */
    @Inject(method = "pauseGame", at = @At("HEAD"), cancellable = true)
    private void flashbackreforged$noFocusPause(boolean showMenu, CallbackInfo ci) {
        if (ExportManager.isExporting() || (ReplayPlayer.isActive() && !this.isWindowActive())) ci.cancel();
    }

    @Inject(method = "shouldEntityAppearGlowing", at = @At("HEAD"), cancellable = true)
    private void flashbackreforged$glow(Entity entity, CallbackInfoReturnable<Boolean> cir) {
        Boolean glow = VisualsRuntime.glowOverride(entity);
        if (glow != null) cir.setReturnValue(glow);
    }
}
