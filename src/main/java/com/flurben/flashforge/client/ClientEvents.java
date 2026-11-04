package com.flurben.flashforge.client;

import com.flurben.flashforge.FlashForge;
import com.flurben.flashforge.playback.ReplayPlayer;
import com.flurben.flashforge.record.Recorder;
import com.flurben.flashforge.selftest.SelfTest;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.toasts.SystemToast;
import net.minecraft.network.chat.Component;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.client.event.RegisterKeyMappingsEvent;
import net.minecraftforge.client.event.RenderPlayerEvent;
import net.minecraftforge.client.event.ViewportEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.lwjgl.glfw.GLFW;

/** Forge event wiring for the client. */
public final class ClientEvents {
    private ClientEvents() {}

    private static final String CATEGORY = "key.categories.flashforge";
    public static final KeyMapping TOGGLE_RECORDING = new KeyMapping("key.flashforge.toggle_recording", GLFW.GLFW_KEY_UNKNOWN, CATEGORY);
    public static final KeyMapping PAUSE_RECORDING = new KeyMapping("key.flashforge.pause_recording", GLFW.GLFW_KEY_UNKNOWN, CATEGORY);
    public static final KeyMapping ADD_MARKER = new KeyMapping("key.flashforge.add_marker", GLFW.GLFW_KEY_UNKNOWN, CATEGORY);
    public static final KeyMapping ADD_MARKER_2 = new KeyMapping("key.flashforge.add_marker_2", GLFW.GLFW_KEY_UNKNOWN, CATEGORY);
    public static final KeyMapping ADD_MARKER_3 = new KeyMapping("key.flashforge.add_marker_3", GLFW.GLFW_KEY_UNKNOWN, CATEGORY);
    public static final KeyMapping ADD_MARKER_4 = new KeyMapping("key.flashforge.add_marker_4", GLFW.GLFW_KEY_UNKNOWN, CATEGORY);

    @Mod.EventBusSubscriber(modid = FlashForge.MOD_ID, value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.MOD)
    public static final class ModBus {
        private ModBus() {}

        @SubscribeEvent
        public static void keys(RegisterKeyMappingsEvent event) {
            event.register(TOGGLE_RECORDING);
            event.register(PAUSE_RECORDING);
            event.register(ADD_MARKER);
            event.register(ADD_MARKER_2);
            event.register(ADD_MARKER_3);
            event.register(ADD_MARKER_4);
        }
    }

    @Mod.EventBusSubscriber(modid = FlashForge.MOD_ID, value = Dist.CLIENT)
    public static final class GameBus {
        private GameBus() {}

        @SubscribeEvent
        public static void clientTick(TickEvent.ClientTickEvent event) {
            Minecraft mc = Minecraft.getInstance();
            if (event.phase == TickEvent.Phase.START) {
                ReplayPlayer replay = ReplayPlayer.active();
                if (replay != null) replay.clientTick();
                return;
            }
            Recorder recorder = Recorder.active();
            if (recorder != null) recorder.endTick();
            handleKeys(mc);
            SelfTest.tick();
        }

        @SubscribeEvent
        public static void renderTick(TickEvent.RenderTickEvent event) {
            ReplayPlayer replay = ReplayPlayer.active();
            if (event.phase != TickEvent.Phase.START) {
                com.flurben.flashforge.client.SpectatorHud.endFrame();
                return;
            }
            if (replay == null) return;
            com.flurben.flashforge.client.SpectatorHud.beginFrame(replay);
            // The HUD setting travels with the replay and is put back when it closes.
            Minecraft mc = Minecraft.getInstance();
            // The hand is also hidden by this flag, so it stays off only when neither is wanted.
            mc.options.hideGui = !replay.editor().visuals().showHud && !replay.editor().visuals().showHand;
            replay.camera().setInputEnabled(!com.flurben.flashforge.export.ExportManager.isExporting()
                    && (com.flurben.flashforge.client.ui.EditorUi.get().flying() || com.flurben.flashforge.client.ui.EditorUi.get().hidden()));
            // An export has already set this frame's scene state from its own clock.
            if (!com.flurben.flashforge.export.ExportManager.isExporting()) replay.editor().applyFrame(replay.tick() + mc.getFrameTime());
            replay.camera().frame();
        }

        @SubscribeEvent
        public static void loggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
            Recorder recorder = Recorder.active();
            if (recorder != null) recorder.stop();
            Recorder.journal().clear();
            ReplayPlayer replay = ReplayPlayer.active();
            if (replay != null) replay.close();
        }

        @SubscribeEvent
        public static void hideViewer(RenderPlayerEvent.Pre event) {
            if (ReplayPlayer.isActive() && event.getEntity().getId() == ReplayPlayer.VIEWER_ID) event.setCanceled(true);
        }

        @SubscribeEvent
        public static void cameraAngles(ViewportEvent.ComputeCameraAngles event) {
            ReplayPlayer replay = ReplayPlayer.active();
            if (replay != null) event.setRoll(event.getRoll() + replay.camera().roll());
        }

        @SubscribeEvent
        public static void fov(ViewportEvent.ComputeFov event) {
            ReplayPlayer replay = ReplayPlayer.active();
            if (replay != null && event.usedConfiguredFov() && replay.camera().fov() > 0) event.setFOV(replay.camera().fov());
        }
    }

    private static void handleKeys(Minecraft mc) {
        while (TOGGLE_RECORDING.consumeClick()) toggleRecording(mc);
        while (PAUSE_RECORDING.consumeClick()) {
            Recorder recorder = Recorder.active();
            if (recorder != null) recorder.setPaused(!recorder.paused());
        }
        while (ADD_MARKER.consumeClick()) {
            Recorder recorder = Recorder.active();
            if (recorder != null) addMarker(mc, recorder);
        }
        // Different keys, different colors: easy to tell apart on the timeline later.
        markerKey(mc, ADD_MARKER_2, 0x55FF55);
        markerKey(mc, ADD_MARKER_3, 0x5599FF);
        markerKey(mc, ADD_MARKER_4, 0xFFDD33);
        Recorder recorder = Recorder.active();
        if (recorder != null && mc.level != null) {
            String dimension = mc.level.dimension().location().toString();
            if (lastDimension != null && !lastDimension.equals(dimension)) {
                var marker = new com.flurben.flashforge.replay.ReplayMeta.Marker();
                marker.color = 0xAA55FF;
                marker.description = dimension;
                recorder.addMarker(marker);
            }
            lastDimension = dimension;
        } else {
            lastDimension = null;
        }
    }

    private static String lastDimension;

    private static void markerKey(Minecraft mc, KeyMapping key, int color) {
        while (key.consumeClick()) {
            Recorder recorder = Recorder.active();
            if (recorder != null) {
                addMarker(mc, recorder);
                recorder.meta().markers.get(recorder.tick()).color = color;
            }
        }
    }

    public static void addMarker(Minecraft mc, Recorder recorder) {
        var marker = new com.flurben.flashforge.replay.ReplayMeta.Marker();
        if (mc.player != null) {
            marker.x = mc.player.getX();
            marker.y = mc.player.getY();
            marker.z = mc.player.getZ();
            marker.dimension = mc.player.level().dimension().location().toString();
        }
        recorder.addMarker(marker);
        toast(mc, "Marker added", "At tick " + recorder.tick());
    }

    public static void toggleRecording(Minecraft mc) {
        if (ReplayPlayer.isActive() || mc.level == null) return;
        Recorder recorder = Recorder.active();
        try {
            if (recorder == null) {
                Recorder.start();
                toast(mc, "Recording started", "");
            } else {
                recorder.stop();
                toast(mc, "Recording finished", recorder.meta().name);
            }
        } catch (Exception e) {
            FlashForge.LOGGER.error("Could not toggle recording", e);
            toast(mc, "Recording failed", String.valueOf(e.getMessage()));
        }
    }

    private static void toast(Minecraft mc, String title, String body) {
        SystemToast.add(mc.getToasts(), SystemToast.SystemToastIds.PERIODIC_NOTIFICATION, Component.literal(title), Component.literal(body));
    }
}
