package com.flurben.flashforge.client.ui;

import com.flurben.flashforge.editor.Scene;
import com.flurben.flashforge.export.ExportManager;
import com.flurben.flashforge.export.ExportSettings;
import com.flurben.flashforge.export.FfmpegLocator;
import com.flurben.flashforge.playback.ReplayPlayer;
import com.flurben.flashforge.replay.ReplayStorage;
import imgui.ImGui;
import imgui.flag.ImGuiCond;
import imgui.flag.ImGuiWindowFlags;
import imgui.type.ImBoolean;
import imgui.type.ImInt;
import imgui.type.ImString;

import java.nio.file.Path;

/** The export settings window, queue and results. */
public final class ExportDialog {
    public enum Mode { VIDEO, SCREENSHOT }

    private static final ExportDialog INSTANCE = new ExportDialog();
    private static final int[][] SIZES = {{3840, 2160}, {2560, 1440}, {1920, 1080}, {1280, 720}, {1080, 1920}, {1080, 1080}};
    private static final String[] SIZE_NAMES = {"3840 x 2160 (4K)", "2560 x 1440", "1920 x 1080", "1280 x 720", "1080 x 1920 (Vertical)", "1080 x 1080 (Square)"};

    private final ImBoolean open = new ImBoolean(false);
    private Mode mode = Mode.VIDEO;
    private final ImString name = new ImString("export", 128);
    private final ImInt width = new ImInt(1920);
    private final ImInt height = new ImInt(1080);
    private final ImInt fps = new ImInt(60);
    private final ImInt bitrate = new ImInt(20000);
    private final ImInt supersample = new ImInt(1);
    private final ImInt warmup = new ImInt(30);
    private ExportSettings.Format format = ExportSettings.Format.MP4_H264;
    private final ImBoolean audio = new ImBoolean(true);
    private final ImBoolean transparent = new ImBoolean(false);

    public static ExportDialog get() {
        return INSTANCE;
    }

    public void open(Mode mode) {
        this.mode = mode;
        this.open.set(true);
    }

    void draw(ReplayPlayer replay) {
        if (!this.open.get()) return;
        ImGui.setNextWindowSize(520, 0, ImGuiCond.Appearing);
        ImGui.setNextWindowPos(ImGui.getIO().getDisplaySizeX() / 2 - 260, 80, ImGuiCond.Appearing);
        String title = this.mode == Mode.VIDEO ? "Export Video" : "Export Screenshot";
        if (ImGui.begin(title, this.open, ImGuiWindowFlags.NoCollapse)) {
            ImGui.inputText("Name", this.name);
            if (ImGui.beginCombo("Preset Size", this.width.get() + " x " + this.height.get())) {
                for (int i = 0; i < SIZES.length; i++) {
                    if (ImGui.selectable(SIZE_NAMES[i])) {
                        this.width.set(SIZES[i][0]);
                        this.height.set(SIZES[i][1]);
                    }
                }
                ImGui.endCombo();
            }
            ImGui.inputInt("Width", this.width);
            ImGui.inputInt("Height", this.height);
            ImGui.sliderInt("Supersampling", this.supersample.getData(), 1, 4);

            if (this.mode == Mode.VIDEO) {
                ImGui.inputInt("Framerate", this.fps);
                if (ImGui.beginCombo("Format", this.format.label)) {
                    for (ExportSettings.Format option : ExportSettings.Format.values()) {
                        if (ImGui.selectable(option.label, option == this.format)) this.format = option;
                    }
                    ImGui.endCombo();
                }
                if (this.format != ExportSettings.Format.PNG_SEQUENCE && this.format != ExportSettings.Format.MOV_PRORES && this.format != ExportSettings.Format.GIF) {
                    ImGui.inputInt("Bitrate (kbps)", this.bitrate, 1000, 5000);
                }
                if (this.format != ExportSettings.Format.PNG_SEQUENCE && this.format != ExportSettings.Format.GIF) {
                    ImGui.checkbox("Include audio", this.audio);
                }
                if (this.format.alpha) ImGui.checkbox("Transparent background (hides the sky)", this.transparent);

                Scene scene = replay.editor().scene();
                int start = Math.max(0, scene.inTick);
                int end = scene.outTick >= 0 ? scene.outTick : replay.totalTicks();
                ImGui.textDisabled("Range: " + TimelinePanel.formatTime(start) + " to " + TimelinePanel.formatTime(end) + " (use Set In / Set Out on the timeline)");

                if (this.format != ExportSettings.Format.PNG_SEQUENCE && FfmpegLocator.find() == null) {
                    ImGui.textColored(1f, 0.5f, 0.3f, 1f, "FFmpeg was not found. Install it on your PATH, or copy ffmpeg into the flashforge folder.");
                }
            }
            ImGui.sliderInt("Warm-up frames", this.warmup.getData(), 0, 240);
            if (ImGui.isItemHovered()) ImGui.setTooltip("Frames rendered but not saved before the export begins, so temporal shaders can settle.");
            ImGui.separator();

            boolean canStart = this.width.get() > 0 && this.height.get() > 0 && this.fps.get() > 0;
            if (ImGui.button(this.mode == Mode.VIDEO ? "Start Export" : "Take Screenshot") && canStart) {
                ExportManager.enqueue(build(replay));
                this.open.set(false);
            }
            ImGui.sameLine();
            if (this.mode == Mode.VIDEO && ImGui.button("Add To Queue") && canStart) ExportManager.enqueue(build(replay));

            if (!ExportManager.queue().isEmpty()) ImGui.text("Queued: " + ExportManager.queue().size());
            if (ExportManager.lastError() != null) ImGui.textColored(1f, 0.4f, 0.4f, 1f, "Last export failed: " + ExportManager.lastError());
            for (String line : ExportManager.history()) ImGui.textDisabled(line);
        }
        ImGui.end();
    }

    private ExportSettings build(ReplayPlayer replay) {
        ExportSettings s = new ExportSettings();
        Scene scene = replay.editor().scene();
        s.name = ReplayStorage.safeName(this.name.get());
        s.width = evenSize(this.width.get());
        s.height = evenSize(this.height.get());
        s.supersample = Math.max(1, this.supersample.get());
        s.warmupFrames = this.warmup.get();
        s.screenshot = this.mode == Mode.SCREENSHOT;
        Path exports = ReplayStorage.exports();
        if (s.screenshot) {
            s.format = ExportSettings.Format.PNG_SEQUENCE;
            s.startTick = replay.tick();
            s.endTick = replay.tick() + 1;
            s.output = exports.resolve(s.name + ".png");
            s.audio = false;
        } else {
            s.fps = this.fps.get();
            s.format = this.format;
            s.bitrateKbps = this.bitrate.get();
            s.audio = this.audio.get();
            s.transparent = this.format.alpha && this.transparent.get();
            s.startTick = Math.max(0, scene.inTick);
            s.endTick = scene.outTick >= 0 ? scene.outTick : replay.totalTicks();
            s.output = this.format == ExportSettings.Format.PNG_SEQUENCE ? exports.resolve(s.name) : exports.resolve(s.name + "." + this.format.extension);
        }
        return s;
    }

    /** Most encoders reject odd dimensions with 4:2:0 chroma. */
    private static int evenSize(int value) {
        return value % 2 == 0 ? value : value + 1;
    }
}
