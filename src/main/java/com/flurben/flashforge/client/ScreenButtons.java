package com.flurben.flashforge.client;

import com.flurben.flashforge.FlashForge;
import com.flurben.flashforge.client.ui.EditorUi;
import com.flurben.flashforge.client.ui.ReplayBrowserScreen;
import com.flurben.flashforge.export.ExportManager;
import com.flurben.flashforge.playback.ReplayPlayer;
import com.flurben.flashforge.record.Recorder;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.network.chat.Component;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.client.event.RenderGuiEvent;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import net.minecraftforge.client.event.ScreenEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import com.flurben.flashforge.editor.KeyframeTrack;
import com.flurben.flashforge.editor.TrackType;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;

/** Entry points on vanilla screens, the recording indicator, auto-recording and the in-world camera path. */
@Mod.EventBusSubscriber(modid = FlashForge.MOD_ID, value = Dist.CLIENT)
public final class ScreenButtons {
    private static boolean autoStartPending;

    private ScreenButtons() {}

    @SubscribeEvent
    public static void screenInit(ScreenEvent.Init.Post event) {
        Minecraft mc = Minecraft.getInstance();
        if (event.getScreen() instanceof TitleScreen screen) {
            int y = screen.height / 4 + 48;
            event.addListener(Button.builder(Component.translatable("flashforge.title.replays"), b -> mc.setScreen(new ReplayBrowserScreen(screen)))
                    .bounds(screen.width / 2 - 100 - 4 - 80, y, 80, 20).build());
        } else if (event.getScreen() instanceof PauseScreen screen) {
            int y = 6;
            if (ReplayPlayer.isActive()) {
                event.addListener(Button.builder(Component.translatable("flashforge.pause.exit_replay"), b -> ReplayPlayer.active().exit()).bounds(6, y, 120, 20).build());
                return;
            }
            Recorder recorder = Recorder.active();
            event.addListener(Button.builder(Component.translatable(recorder == null ? "flashforge.pause.start" : "flashforge.pause.stop"), b -> {
                ClientEvents.toggleRecording(mc);
                mc.setScreen(null);
            }).bounds(6, y, 120, 20).build());
            if (recorder != null) {
                event.addListener(Button.builder(Component.translatable(recorder.paused() ? "flashforge.pause.resume" : "flashforge.pause.pause"), b -> {
                    recorder.setPaused(!recorder.paused());
                    mc.setScreen(null);
                }).bounds(6, y + 24, 120, 20).build());
                event.addListener(Button.builder(Component.translatable("flashforge.pause.cancel"), b ->
                        mc.setScreen(new net.minecraft.client.gui.screens.ConfirmScreen(yes -> {
                            if (yes) recorder.cancel();
                            mc.setScreen(null);
                        }, Component.translatable("flashforge.pause.cancel_confirm"), Component.translatable("flashforge.browser.delete_warning"))))
                        .bounds(6, y + 72, 120, 20).build());
                event.addListener(Button.builder(Component.translatable("flashforge.pause.marker"), b -> {
                    ClientEvents.addMarker(mc, recorder);
                    mc.setScreen(null);
                }).bounds(6, y + 48, 120, 20).build());
            }
        }
    }

    @SubscribeEvent
    public static void loggedIn(ClientPlayerNetworkEvent.LoggingIn event) {
        if (ReplayPlayer.isActive()) return;
        boolean local = Minecraft.getInstance().hasSingleplayerServer();
        FlashForgeConfig.AutoRecord mode = FlashForgeConfig.AUTO_RECORD.get();
        autoStartPending = mode == FlashForgeConfig.AutoRecord.ALWAYS
                || (local && mode == FlashForgeConfig.AutoRecord.SINGLEPLAYER)
                || (!local && mode == FlashForgeConfig.AutoRecord.MULTIPLAYER);
    }

    @SubscribeEvent
    public static void tick(TickEvent.ClientTickEvent event) {
        Minecraft mc = Minecraft.getInstance();
        if (event.phase != TickEvent.Phase.END || !autoStartPending || mc.level == null || mc.player == null || mc.screen != null) return;
        autoStartPending = false;
        if (Recorder.active() == null && !ReplayPlayer.isActive()) ClientEvents.toggleRecording(mc);
    }

    /** The recording state is never shown in the recording itself: this is drawn on the live HUD only. */
    @SubscribeEvent
    public static void indicator(RenderGuiEvent.Post event) {
        Recorder recorder = Recorder.active();
        if (recorder == null || !FlashForgeConfig.SHOW_INDICATOR.get()) return;
        Minecraft mc = Minecraft.getInstance();
        GuiGraphics g = event.getGuiGraphics();
        g.fill(5, 5, 11, 11, recorder.paused() ? 0xFFFFAA00 : 0xFFFF3030);
        int seconds = recorder.tick() / 20;
        g.drawString(mc.font, String.format("%d:%02d", seconds / 60, seconds % 60), 14, 4, 0xFFFFFF);
    }

    /** Shows where the camera will travel. Editor only, so it can't appear in an export. */
    @SubscribeEvent
    public static void cameraPath(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS) return;
        ReplayPlayer replay = ReplayPlayer.active();
        if (replay == null || ExportManager.isExporting() || EditorUi.get().hidden() || !replay.editor().visuals().showCameraPath) return;
        KeyframeTrack track = replay.editor().scene().firstTrack(TrackType.CAMERA);
        if (track == null || !track.enabled || track.keyframes.size() < 2) return;

        Minecraft mc = Minecraft.getInstance();
        Vec3 eye = event.getCamera().getPosition();
        PoseStack pose = event.getPoseStack();
        pose.pushPose();
        pose.translate(-eye.x, -eye.y, -eye.z);
        MultiBufferSource.BufferSource buffers = mc.renderBuffers().bufferSource();
        VertexConsumer lines = buffers.getBuffer(RenderType.lines());
        double first = track.keyframes.firstKey(), last = track.keyframes.lastKey();
        double[] from = track.evaluate(first);
        for (double t = first + 0.5; t <= last; t += 0.5) {
            double[] to = track.evaluate(t);
            float nx = (float) (to[0] - from[0]), ny = (float) (to[1] - from[1]), nz = (float) (to[2] - from[2]);
            float length = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
            if (length > 1e-4f) {
                nx /= length;
                ny /= length;
                nz /= length;
                lines.vertex(pose.last().pose(), (float) from[0], (float) from[1], (float) from[2]).color(0.25f, 0.75f, 1f, 1f).normal(pose.last().normal(), nx, ny, nz).endVertex();
                lines.vertex(pose.last().pose(), (float) to[0], (float) to[1], (float) to[2]).color(0.25f, 0.75f, 1f, 1f).normal(pose.last().normal(), nx, ny, nz).endVertex();
            }
            from = to;
        }
        buffers.endBatch(RenderType.lines());
        pose.popPose();
        RenderSystem.lineWidth(1f);
    }
}
