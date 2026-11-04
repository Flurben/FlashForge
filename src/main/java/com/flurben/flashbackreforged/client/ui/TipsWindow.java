package com.flurben.flashbackreforged.client.ui;

import com.flurben.flashbackreforged.client.FlashbackReforgedConfig;
import com.flurben.flashbackreforged.playback.ReplayPlayer;
import com.flurben.flashbackreforged.selftest.SelfTest;
import imgui.ImGui;
import imgui.flag.ImGuiCond;
import imgui.flag.ImGuiWindowFlags;
import imgui.type.ImBoolean;

/** A short tip when a replay opens, working through the list across sessions. */
public final class TipsWindow {
    private static final String[] TIPS = {
            "Hold right mouse over the game to fly the camera with your movement keys. Sprint moves faster.",
            "Right-click an entity in the game view for quick actions: look at it, spectate it, hear from it, or hide it from exports.",
            "Type /hide @e[type=minecraft:pig] in chat to leave all pigs out of an export. Hidden entities get a red outline in the editor, and /show brings them back.",
            "Press I and O to set the start and end of the part you want to export.",
            "Copy Relative under Keys... lets you paste a camera move somewhere else in the world, turned to face the way the camera faces now.",
            "Drag across empty timeline space to select keyframes. Drag the bars at the ends of a selection to speed the whole move up or slow it down.",
            "Set an audio source from the right-click menu to hear the replay from that entity instead of from the camera.",
            "Change the view's aspect ratio under Visuals to frame a vertical or square video before you export it.",
            "Shader packs take a few frames to settle. Raise Warm-up frames in the export window if the first frames of a video look wrong.",
            "F1 hides the editor. Press it again to bring the panels back."
    };

    private final ImBoolean open = new ImBoolean(false);
    private boolean dontShowAgain;
    private ReplayPlayer lastReplay;

    /** For the View menu and the self test. */
    public void show() {
        this.open.set(true);
    }

    public void close() {
        this.open.set(false);
    }

    void draw(ReplayPlayer replay) {
        if (replay != this.lastReplay) {
            this.lastReplay = replay;
            if (FlashbackReforgedConfig.SHOW_TIPS.get() && !SelfTest.enabled()) this.open.set(true);
        }
        if (!this.open.get()) return;
        ImGui.setNextWindowSize(460, 0, ImGuiCond.Always);
        ImGui.setNextWindowPos(ImGui.getIO().getDisplaySizeX() / 2 - 230, ImGui.getIO().getDisplaySizeY() / 3, ImGuiCond.Appearing);
        if (ImGui.begin("Tip", this.open, ImGuiWindowFlags.NoCollapse | ImGuiWindowFlags.NoResize)) {
            int index = Math.floorMod(FlashbackReforgedConfig.NEXT_TIP.get(), TIPS.length);
            ImGui.textWrapped(TIPS[index]);
            ImGui.separator();
            if (ImGui.checkbox("Don't show tips when opening a replay", this.dontShowAgain)) this.dontShowAgain = !this.dontShowAgain;
            if (ImGui.button("Next Tip")) advance();
            ImGui.sameLine();
            if (ImGui.button("Close")) {
                advance();
                if (this.dontShowAgain) {
                    FlashbackReforgedConfig.SHOW_TIPS.set(false);
                    FlashbackReforgedConfig.SHOW_TIPS.save();
                }
                this.open.set(false);
            }
        }
        ImGui.end();
    }

    private static void advance() {
        FlashbackReforgedConfig.NEXT_TIP.set(Math.floorMod(FlashbackReforgedConfig.NEXT_TIP.get() + 1, TIPS.length));
        FlashbackReforgedConfig.NEXT_TIP.save();
    }
}
