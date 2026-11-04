package com.flurben.flashbackreforged.client.ui;

import com.flurben.flashbackreforged.editor.EditorState;
import com.flurben.flashbackreforged.editor.Interpolation;
import com.flurben.flashbackreforged.editor.Keyframe;
import com.flurben.flashbackreforged.editor.KeyframeTrack;
import com.flurben.flashbackreforged.editor.RelativeOffset;
import com.flurben.flashbackreforged.editor.Scene;
import com.flurben.flashbackreforged.editor.TrackType;
import com.flurben.flashbackreforged.playback.ReplayPlayer;
import com.flurben.flashbackreforged.replay.ReplayMeta;
import imgui.ImDrawList;
import imgui.ImGui;
import imgui.flag.ImGuiCond;
import imgui.flag.ImGuiKey;
import imgui.flag.ImGuiMouseButton;
import imgui.flag.ImGuiStyleVar;
import imgui.flag.ImGuiWindowFlags;
import imgui.type.ImDouble;
import imgui.type.ImInt;
import imgui.type.ImString;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/** Transport controls, tick ruler, markers, and the keyframe tracks. */
public final class TimelinePanel {
    private static final float RULER_HEIGHT = 24f;
    private static final float ROW_HEIGHT = 22f;
    private static final float LABEL_WIDTH = 190f;
    private static final float DIAMOND = 6f;

    private double viewStart = 0;
    private double ticksPerPixel = -1;
    private boolean scrubbing;

    private record Ref(KeyframeTrack track, int tick) {}

    private final Set<Ref> selected = new HashSet<>();
    private boolean draggingKeys;
    private int dragOriginTick;
    private int dragDelta;
    private Ref editing;
    private List<Copied> clipboard = new ArrayList<>();
    private final ImString textBuffer = new ImString(512);

    // Where the camera was when keyframes were copied, and which parts of that the paste should be relative to.
    private double copyX, copyY, copyZ;
    private float copyYaw, copyPitch;
    private boolean pasteRelPosition, pasteRelYaw, pasteRelPitch;
    private boolean relPosition = true, relYaw = true, relPitch;
    private final imgui.type.ImFloat scalePercent = new imgui.type.ImFloat(100f);

    private boolean scaling;
    private int scalePivot;
    private int scaleGrabbed;
    private boolean boxing;
    private float boxX, boxY;

    private record Row(KeyframeTrack track, float y) {}

    private record Copied(TrackType type, int offset, Keyframe keyframe) {}

    float preferredHeight() {
        return 230f;
    }

    void draw(ReplayPlayer replay, float x, float y, float width, float height) {
        ImGui.setNextWindowPos(x, y, ImGuiCond.Always);
        ImGui.setNextWindowSize(width, height, ImGuiCond.Always);
        int flags = ImGuiWindowFlags.NoMove | ImGuiWindowFlags.NoResize | ImGuiWindowFlags.NoCollapse | ImGuiWindowFlags.NoTitleBar | ImGuiWindowFlags.NoScrollbar;
        if (ImGui.begin("Timeline", flags)) {
            drawTransport(replay);
            drawTracks(replay);
        }
        ImGui.end();
        handleShortcuts(replay);
        drawKeyframeEditor(replay);
    }

    private void drawTransport(ReplayPlayer replay) {
        Scene scene = replay.editor().scene();
        if (ImGui.button("|<")) replay.seek(scene.inTick >= 0 ? scene.inTick : 0);
        ImGui.sameLine();
        if (ImGui.button("<")) replay.seek(replay.tick() - 20);
        ImGui.sameLine();
        if (ImGui.button(replay.paused() ? "  Play  " : " Pause  ")) replay.setPaused(!replay.paused());
        ImGui.sameLine();
        if (ImGui.button(">")) replay.seek(replay.tick() + 20);
        ImGui.sameLine();
        if (ImGui.button(">|")) replay.seek(scene.outTick >= 0 ? scene.outTick : replay.totalTicks());
        ImGui.sameLine();
        ImGui.text(formatTime(replay.tick()) + " / " + formatTime(replay.totalTicks()) + "   tick " + replay.tick());
        ImGui.sameLine();
        ImGui.setNextItemWidth(110);
        float[] speed = {replay.speed()};
        if (ImGui.sliderFloat("Speed", speed, 0.1f, 8f, "%.2fx")) replay.setSpeed(speed[0]);
        ImGui.sameLine();
        if (ImGui.button("Set In")) mark(replay, true);
        ImGui.sameLine();
        if (ImGui.button("Set Out")) mark(replay, false);
        ImGui.sameLine();
        if (ImGui.button("Clear Range")) {
            replay.editor().checkpoint();
            scene.inTick = -1;
            scene.outTick = -1;
        }
        ImGui.sameLine();
        if (ImGui.button("+ Keyframe")) ImGui.openPopup("add_keyframe");
        if (ImGui.beginPopup("add_keyframe")) {
            for (TrackType type : TrackType.values()) {
                if (ImGui.menuItem(type.label)) addKeyframe(replay, type, null);
            }
            ImGui.endPopup();
        }
        ImGui.sameLine();
        if (ImGui.button("Keys...")) ImGui.openPopup("keys_menu");
        if (ImGui.beginPopup("keys_menu")) {
            drawKeysMenu(replay);
            ImGui.endPopup();
        }
        ImGui.sameLine();
        boolean drive = replay.editor().keyframesDriveCamera();
        if (ImGui.checkbox("Keyframes drive camera", drive)) replay.editor().setKeyframesDriveCamera(!drive);
    }

    private void drawKeysMenu(ReplayPlayer replay) {
        ImGui.text(this.selected.size() + " keyframes selected");
        ImGui.beginDisabled(this.selected.isEmpty());
        ImGui.separator();
        ImGui.text("Copy Relative To Camera");
        if (ImGui.checkbox("Position", this.relPosition)) this.relPosition = !this.relPosition;
        ImGui.sameLine();
        if (ImGui.checkbox("Yaw", this.relYaw)) this.relYaw = !this.relYaw;
        ImGui.sameLine();
        if (ImGui.checkbox("Pitch", this.relPitch)) this.relPitch = !this.relPitch;
        if (ImGui.button("Copy Relative")) {
            copySelected(replay, this.relPosition, this.relYaw, this.relPitch);
            ImGui.closeCurrentPopup();
        }
        if (ImGui.isItemHovered(imgui.flag.ImGuiHoveredFlags.AllowWhenDisabled)) ImGui.setTooltip("Paste with Ctrl+V to repeat the move from wherever the camera is then.");
        ImGui.separator();
        ImGui.setNextItemWidth(120);
        ImGui.inputFloat("Scale %", this.scalePercent, 10f, 50f, "%.0f");
        if (ImGui.button("Scale From First Keyframe") && this.scalePercent.get() > 0) {
            int pivot = this.selected.stream().mapToInt(Ref::tick).min().orElse(0);
            applyScale(replay, pivot, this.scalePercent.get() / 100.0);
            ImGui.closeCurrentPopup();
        }
        ImGui.endDisabled();
    }

    private void copySelected(ReplayPlayer replay, boolean relPosition, boolean relYaw, boolean relPitch) {
        int first = this.selected.stream().mapToInt(Ref::tick).min().orElse(0);
        this.clipboard = new ArrayList<>();
        for (Ref ref : this.selected) {
            Keyframe k = ref.track.keyframes.get(ref.tick);
            if (k != null) this.clipboard.add(new Copied(ref.track.type, ref.tick - first, k.copy()));
        }
        var player = Minecraft.getInstance().player;
        this.copyX = replay.camera().x();
        this.copyY = replay.camera().y();
        this.copyZ = replay.camera().z();
        this.copyYaw = player.getYRot();
        this.copyPitch = player.getXRot();
        this.pasteRelPosition = relPosition;
        this.pasteRelYaw = relYaw;
        this.pasteRelPitch = relPitch;
    }

    private void paste(ReplayPlayer replay) {
        EditorState editor = replay.editor();
        editor.checkpoint();
        var player = Minecraft.getInstance().player;
        RelativeOffset offset = new RelativeOffset();
        offset.oldX = this.copyX;
        offset.oldY = this.copyY;
        offset.oldZ = this.copyZ;
        offset.newX = this.pasteRelPosition ? replay.camera().x() : this.copyX;
        offset.newY = this.pasteRelPosition ? replay.camera().y() : this.copyY;
        offset.newZ = this.pasteRelPosition ? replay.camera().z() : this.copyZ;
        offset.oldYaw = this.copyYaw;
        offset.newYaw = this.pasteRelYaw ? player.getYRot() : this.copyYaw;
        offset.oldPitch = this.copyPitch;
        offset.newPitch = this.pasteRelPitch ? player.getXRot() : this.copyPitch;
        for (Copied c : this.clipboard) {
            Keyframe k = c.keyframe.copy();
            if (this.pasteRelPosition || this.pasteRelYaw || this.pasteRelPitch) offset.apply(c.type, k);
            editor.scene().trackForAdding(c.type).keyframes.put(replay.tick() + c.offset, k);
        }
    }

    private void applyScale(ReplayPlayer replay, int pivot, double factor) {
        replay.editor().checkpoint();
        Map<KeyframeTrack, List<Integer>> byTrack = new java.util.HashMap<>();
        for (Ref ref : this.selected) byTrack.computeIfAbsent(ref.track, t -> new ArrayList<>()).add(ref.tick);
        this.selected.clear();
        byTrack.forEach((track, ticks) -> track.scale(ticks, pivot, factor).values().forEach(tick -> this.selected.add(new Ref(track, tick))));
    }

    private void mark(ReplayPlayer replay, boolean in) {
        replay.editor().checkpoint();
        Scene scene = replay.editor().scene();
        if (in) scene.inTick = replay.tick();
        else scene.outTick = replay.tick();
        if (scene.inTick >= 0 && scene.outTick >= 0 && scene.outTick < scene.inTick) {
            int swap = scene.inTick;
            scene.inTick = scene.outTick;
            scene.outTick = swap;
        }
    }

    /** Records the current camera (or a sensible default) as a keyframe at the current tick. */
    public void addKeyframe(ReplayPlayer replay, TrackType type, KeyframeTrack into) {
        EditorState editor = replay.editor();
        editor.checkpoint();
        Scene scene = editor.scene();
        KeyframeTrack track = into != null ? into : scene.trackForAdding(type);
        Minecraft mc = Minecraft.getInstance();
        double[] values = type.defaults.clone();
        String text = null;
        var player = mc.player;
        switch (type) {
            case CAMERA -> {
                values = new double[]{replay.camera().x(), replay.camera().y(), replay.camera().z(), player.getYRot(), player.getXRot(), replay.camera().roll()};
            }
            case ORBIT -> {
                var look = net.minecraft.world.phys.Vec3.directionFromRotation(player.getXRot(), player.getYRot());
                double distance = 8;
                values = new double[]{replay.camera().x() + look.x * distance, replay.camera().y() + look.y * distance, replay.camera().z() + look.z * distance, distance, player.getYRot(), player.getXRot()};
            }
            case FOV -> values[0] = mc.options.fov().get();
            case TIME_OF_DAY -> values[0] = mc.level.getDayTime() % 24000;
            case BLOCK_OVERRIDE -> {
                values = new double[]{Math.floor(replay.camera().x()), Math.floor(replay.camera().y()), Math.floor(replay.camera().z())};
                text = "minecraft:stone";
            }
            case TRACK_ENTITY -> {
                Entity target = replay.spectated();
                if (target == null && replay.recordedPlayerId() != Integer.MIN_VALUE) target = mc.level.getEntity(replay.recordedPlayerId());
                if (target != null) text = target.getUUID().toString();
            }
            default -> {
            }
        }
        Keyframe keyframe = new Keyframe(values, type == TrackType.CAMERA ? Interpolation.CATMULL_ROM : Interpolation.SMOOTH);
        keyframe.text = text;
        track.keyframes.put(replay.tick(), keyframe);
    }

    private void drawTracks(ReplayPlayer replay) {
        EditorState editor = replay.editor();
        Scene scene = editor.scene();
        ImDrawList draw = ImGui.getWindowDrawList();
        float originX = ImGui.getCursorScreenPosX();
        float originY = ImGui.getCursorScreenPosY();
        float regionW = ImGui.getContentRegionAvailX();
        float regionH = ImGui.getContentRegionAvailY();
        float areaX = originX + LABEL_WIDTH;
        float areaW = Math.max(10, regionW - LABEL_WIDTH);
        int total = Math.max(1, replay.totalTicks());

        if (this.ticksPerPixel < 0) this.ticksPerPixel = total / (double) areaW;
        this.ticksPerPixel = Math.max(0.02, Math.min(this.ticksPerPixel, total / (double) areaW * 1.1));
        this.viewStart = Math.max(0, Math.min(this.viewStart, Math.max(0, total - areaW * this.ticksPerPixel)));

        draw.addRectFilled(areaX, originY, areaX + areaW, originY + RULER_HEIGHT, 0xFF2A2522);
        drawRuler(draw, areaX, originY, areaW);

        // In/out shading
        if (scene.inTick >= 0 || scene.outTick >= 0) {
            float a = tickX(Math.max(0, scene.inTick), areaX);
            float b = tickX(scene.outTick >= 0 ? scene.outTick : total, areaX);
            draw.addRectFilled(Math.max(a, areaX), originY, Math.min(b, areaX + areaW), originY + RULER_HEIGHT, 0x4840B0FF);
        }

        for (Map.Entry<Integer, ReplayMeta.Marker> marker : replay.meta().markers.entrySet()) {
            float mx = tickX(marker.getKey(), areaX);
            if (mx >= areaX && mx <= areaX + areaW) {
                int c = marker.getValue().color;
                int abgr = 0xFF000000 | ((c & 0xFF) << 16) | (c & 0xFF00) | ((c >> 16) & 0xFF);
                draw.addTriangleFilled(mx - 5, originY, mx + 5, originY, mx, originY + 9, abgr);
                if (ImGui.isMouseHoveringRect(mx - 5, originY, mx + 5, originY + 10) && marker.getValue().description != null) {
                    ImGui.setTooltip(marker.getValue().description);
                }
            }
        }

        // Ruler scrubbing
        boolean overRuler = ImGui.isMouseHoveringRect(areaX, originY, areaX + areaW, originY + RULER_HEIGHT);
        if (overRuler && ImGui.isMouseClicked(ImGuiMouseButton.Left)) this.scrubbing = true;
        if (this.scrubbing) {
            if (ImGui.isMouseDown(ImGuiMouseButton.Left)) {
                int target = xTick(ImGui.getMousePosX(), areaX);
                if (target != replay.tick()) replay.seek(target);
            } else {
                this.scrubbing = false;
            }
        }

        // Zoom and pan
        boolean overArea = ImGui.isMouseHoveringRect(areaX, originY, areaX + areaW, originY + regionH);
        if (overArea) {
            float wheel = ImGui.getIO().getMouseWheel();
            if (wheel != 0) {
                double anchor = this.viewStart + (ImGui.getMousePosX() - areaX) * this.ticksPerPixel;
                this.ticksPerPixel *= Math.pow(0.85, wheel);
                this.ticksPerPixel = Math.max(0.02, Math.min(this.ticksPerPixel, total / (double) areaW * 1.1));
                this.viewStart = Math.max(0, anchor - (ImGui.getMousePosX() - areaX) * this.ticksPerPixel);
            }
            if (ImGui.isMouseDragging(ImGuiMouseButton.Middle)) {
                this.viewStart = Math.max(0, this.viewStart - ImGui.getIO().getMouseDeltaX() * this.ticksPerPixel);
            }
        }

        // Tracks
        float rowY = originY + RULER_HEIGHT;
        List<KeyframeTrack> tracks = new ArrayList<>(scene.tracks);
        int rowIndex = 0;
        boolean clickedKey = false;
        KeyframeTrack deleteTrack = null;
        List<Row> rows = new ArrayList<>();
        for (KeyframeTrack track : tracks) {
            rows.add(new Row(track, rowY));
            draw.addRectFilled(originX, rowY, areaX + areaW, rowY + ROW_HEIGHT, rowIndex % 2 == 0 ? 0xFF201C1A : 0xFF1A1716);
            ImGui.setCursorScreenPos(originX + 2, rowY + 1);
            ImGui.pushID(rowIndex);
            ImGui.pushStyleVar(ImGuiStyleVar.FramePadding, 2f, 0f);
            if (ImGui.checkbox("##on", track.enabled)) {
                editor.checkpoint();
                track.enabled = !track.enabled;
            }
            ImGui.sameLine();
            if (ImGui.selectable(track.name + "##label", false, 0, LABEL_WIDTH - 32, ROW_HEIGHT - 4)) {
                // Selecting the row is only a target for the context menu.
            }
            if (ImGui.beginPopupContextItem("track_menu")) {
                if (ImGui.menuItem("Add Keyframe Here")) addKeyframe(replay, track.type, track);
                if (ImGui.menuItem("Clear Keyframes")) {
                    editor.checkpoint();
                    track.keyframes.clear();
                }
                if (ImGui.menuItem("Delete Track")) deleteTrack = track;
                ImGui.separator();
                int rgb = track.color != null ? track.color : track.type.color;
                float[] color = {((rgb >> 16) & 0xFF) / 255f, ((rgb >> 8) & 0xFF) / 255f, (rgb & 0xFF) / 255f};
                if (ImGui.colorEdit3("Track Color", color, imgui.flag.ImGuiColorEditFlags.NoInputs)) {
                    track.color = (Math.round(color[0] * 255) << 16) | (Math.round(color[1] * 255) << 8) | Math.round(color[2] * 255);
                }
                if (track.color != null && ImGui.menuItem("Reset Color")) track.color = null;
                ImGui.endPopup();
            }
            ImGui.popStyleVar();
            ImGui.popID();

            float midY = rowY + ROW_HEIGHT / 2;
            drawInterpolationLines(draw, track, areaX, areaW, midY);
            for (Map.Entry<Integer, Keyframe> entry : track.keyframes.entrySet()) {
                Ref ref = new Ref(track, entry.getKey());
                boolean isSelected = this.selected.contains(ref);
                int tick = entry.getKey() + (isSelected && this.draggingKeys ? this.dragDelta : 0);
                float kx = tickX(tick, areaX);
                if (kx < areaX - DIAMOND || kx > areaX + areaW + DIAMOND) continue;
                int base = rgbToAbgr(track.color != null ? 0xFF000000 | track.color : track.type.color);
                int color = track.enabled ? base : 0xFF666666;
                draw.addQuadFilled(kx, midY - DIAMOND, kx + DIAMOND, midY, kx, midY + DIAMOND, kx - DIAMOND, midY, color);
                if (isSelected) draw.addQuad(kx, midY - DIAMOND - 1, kx + DIAMOND + 1, midY, kx, midY + DIAMOND + 1, kx - DIAMOND - 1, midY, 0xFFFFFFFF, 1.5f);

                if (ImGui.isMouseHoveringRect(kx - DIAMOND, midY - DIAMOND, kx + DIAMOND, midY + DIAMOND)) {
                    ImGui.setTooltip(track.name + " @ " + entry.getKey() + "\n" + entry.getValue().interpolation.label);
                    if (ImGui.isMouseClicked(ImGuiMouseButton.Left)) {
                        clickedKey = true;
                        if (!ImGui.getIO().getKeyCtrl() && !isSelected) this.selected.clear();
                        this.selected.add(ref);
                        this.draggingKeys = true;
                        this.dragOriginTick = xTick(ImGui.getMousePosX(), areaX);
                        this.dragDelta = 0;
                    }
                    if (ImGui.isMouseClicked(ImGuiMouseButton.Right)) {
                        this.editing = ref;
                        ImGui.openPopup("keyframe_editor");
                    }
                    if (ImGui.isMouseDoubleClicked(ImGuiMouseButton.Left)) replay.seek(entry.getKey());
                }
            }
            rowY += ROW_HEIGHT;
            rowIndex++;
        }
        if (deleteTrack != null) {
            editor.checkpoint();
            scene.tracks.remove(deleteTrack);
            this.selected.removeIf(r -> !scene.tracks.contains(r.track));
        }
        if (tracks.isEmpty()) {
            draw.addText(areaX + 12, originY + RULER_HEIGHT + 8, 0xFF9A9A9A, "No tracks yet. Use + Keyframe to add one at the current tick.");
        }

        // Keyframe dragging
        if (this.draggingKeys) {
            if (ImGui.isMouseDown(ImGuiMouseButton.Left)) {
                this.dragDelta = xTick(ImGui.getMousePosX(), areaX) - this.dragOriginTick;
            } else {
                if (this.dragDelta != 0) commitDrag(replay);
                this.draggingKeys = false;
                this.dragDelta = 0;
            }
        }
        // Handles at the ends of a selection stretch it in time.
        if (this.selected.size() >= 2 && !this.draggingKeys) {
            int low = this.selected.stream().mapToInt(Ref::tick).min().orElse(0);
            int high = this.selected.stream().mapToInt(Ref::tick).max().orElse(0);
            if (high > low) {
                float top = originY + RULER_HEIGHT;
                float bottom = rowY;
                float lx = tickX(low, areaX) - DIAMOND - 5;
                float hx = tickX(high, areaX) + DIAMOND + 5;
                draw.addRectFilled(lx - 2, top, lx + 2, bottom, 0xFFFFFFFF);
                draw.addRectFilled(hx - 2, top, hx + 2, bottom, 0xFFFFFFFF);
                boolean overLow = ImGui.isMouseHoveringRect(lx - 4, top, lx + 4, bottom);
                boolean overHigh = ImGui.isMouseHoveringRect(hx - 4, top, hx + 4, bottom);
                if ((overLow || overHigh) && ImGui.isMouseClicked(ImGuiMouseButton.Left)) {
                    clickedKey = true;
                    this.scaling = true;
                    this.scalePivot = overLow ? high : low;
                    this.scaleGrabbed = overLow ? low : high;
                }
            }
        }
        if (this.scaling) {
            int at = xTick(ImGui.getMousePosX(), areaX);
            double factor = Math.max(0.01, (at - this.scalePivot) / (double) (this.scaleGrabbed - this.scalePivot));
            ImGui.setTooltip(Math.round(factor * 100) + "%");
            if (!ImGui.isMouseDown(ImGuiMouseButton.Left)) {
                this.scaling = false;
                if (Math.abs(factor - 1) > 1e-3) applyScale(replay, this.scalePivot, factor);
            }
        }

        // Dragging across empty space selects everything inside the box.
        if (overArea && !overRuler && !clickedKey && !this.scaling && ImGui.isMouseClicked(ImGuiMouseButton.Left)) {
            if (!ImGui.getIO().getKeyCtrl()) this.selected.clear();
            this.boxing = true;
            this.boxX = ImGui.getMousePosX();
            this.boxY = ImGui.getMousePosY();
        }
        if (this.boxing) {
            float bx0 = Math.min(this.boxX, ImGui.getMousePosX()), bx1 = Math.max(this.boxX, ImGui.getMousePosX());
            float by0 = Math.min(this.boxY, ImGui.getMousePosY()), by1 = Math.max(this.boxY, ImGui.getMousePosY());
            draw.addRectFilled(bx0, by0, bx1, by1, 0x30FFFFFF);
            draw.addRect(bx0, by0, bx1, by1, 0xC0FFFFFF);
            if (!ImGui.isMouseDown(ImGuiMouseButton.Left)) {
                this.boxing = false;
                for (Row row : rows) {
                    if (row.y + ROW_HEIGHT < by0 || row.y > by1) continue;
                    for (Integer tick : row.track.keyframes.keySet()) {
                        float kx = tickX(tick, areaX);
                        if (kx >= bx0 && kx <= bx1) this.selected.add(new Ref(row.track, tick));
                    }
                }
            }
        }

        // Playhead
        float px = tickX(replay.tick(), areaX);
        if (px >= areaX && px <= areaX + areaW) draw.addLine(px, originY, px, originY + regionH, 0xFF3B9BFF, 2f);
    }

    private void drawInterpolationLines(ImDrawList draw, KeyframeTrack track, float areaX, float areaW, float midY) {
        Integer previous = null;
        for (Integer tick : track.keyframes.keySet()) {
            if (previous != null) {
                float a = Math.max(tickX(previous, areaX), areaX);
                float b = Math.min(tickX(tick, areaX), areaX + areaW);
                if (b > a) draw.addLine(a, midY, b, midY, 0x60FFFFFF);
            }
            previous = tick;
        }
    }

    private void commitDrag(ReplayPlayer replay) {
        replay.editor().checkpoint();
        Set<Ref> moved = new HashSet<>();
        Map<KeyframeTrack, TreeMap<Integer, Keyframe>> picked = new java.util.HashMap<>();
        for (Ref ref : this.selected) {
            Keyframe k = ref.track.keyframes.remove(ref.tick);
            if (k != null) picked.computeIfAbsent(ref.track, t -> new TreeMap<>()).put(ref.tick, k);
        }
        picked.forEach((track, keys) -> keys.forEach((tick, k) -> {
            int target = Math.max(0, tick + this.dragDelta);
            track.keyframes.put(target, k);
            moved.add(new Ref(track, target));
        }));
        this.selected.clear();
        this.selected.addAll(moved);
    }

    private void handleShortcuts(ReplayPlayer replay) {
        if (ImGui.getIO().getWantTextInput()) return;
        EditorState editor = replay.editor();
        boolean ctrl = ImGui.getIO().getKeyCtrl();
        if (ImGui.isKeyPressed(ImGuiKey.Space, false)) replay.setPaused(!replay.paused());
        if (ctrl && ImGui.isKeyPressed(ImGuiKey.Z, false)) editor.undo();
        if (ctrl && ImGui.isKeyPressed(ImGuiKey.Y, false)) editor.redo();
        if (ctrl && ImGui.isKeyPressed(ImGuiKey.S, false)) editor.save();
        if (!ctrl && ImGui.isKeyPressed(ImGuiKey.I, false)) mark(replay, true);
        if (!ctrl && ImGui.isKeyPressed(ImGuiKey.O, false)) mark(replay, false);
        if (ImGui.isKeyPressed(ImGuiKey.LeftArrow, true)) replay.seek(replay.tick() - 1);
        if (ImGui.isKeyPressed(ImGuiKey.RightArrow, true)) replay.seek(replay.tick() + 1);
        if (ImGui.isKeyPressed(ImGuiKey.Delete, false) && !this.selected.isEmpty()) {
            editor.checkpoint();
            for (Ref ref : this.selected) ref.track.keyframes.remove(ref.tick);
            this.selected.clear();
        }
        if (ctrl && ImGui.isKeyPressed(ImGuiKey.C, false) && !this.selected.isEmpty()) copySelected(replay, false, false, false);
        if (ctrl && ImGui.isKeyPressed(ImGuiKey.V, false) && !this.clipboard.isEmpty()) paste(replay);
    }

    private void drawKeyframeEditor(ReplayPlayer replay) {
        if (!ImGui.beginPopup("keyframe_editor")) return;
        Ref ref = this.editing;
        Keyframe k = ref == null ? null : ref.track.keyframes.get(ref.tick);
        if (k == null) {
            ImGui.closeCurrentPopup();
            ImGui.endPopup();
            return;
        }
        EditorState editor = replay.editor();
        ImGui.text(ref.track.name + " at tick " + ref.tick);
        ImGui.separator();

        if (ref.track.type.interpolated() && ImGui.beginCombo("Interpolation", k.interpolation.label)) {
            for (Interpolation option : Interpolation.values()) {
                if (ImGui.selectable(option.label, option == k.interpolation)) {
                    editor.checkpoint();
                    k.interpolation = option;
                }
            }
            ImGui.endCombo();
        }
        String[] slots = ref.track.type.slots;
        for (int i = 0; i < slots.length; i++) {
            ImDouble value = new ImDouble(k.values[i]);
            if (ImGui.inputDouble(slots[i], value, 0.1, 1.0, "%.3f")) k.values[i] = value.get();
            if (ImGui.isItemActivated()) editor.checkpoint();
        }
        TrackType type = ref.track.type;
        if (type == TrackType.TRACK_ENTITY || type == TrackType.AUDIO || type == TrackType.BLOCK_OVERRIDE) {
            this.textBuffer.set(k.text == null ? "" : k.text);
            String label = type == TrackType.TRACK_ENTITY ? "Entity UUID" : type == TrackType.AUDIO ? "Audio File" : "Block State";
            if (ImGui.inputText(label, this.textBuffer)) k.text = this.textBuffer.get();
            if (ImGui.isItemActivated()) editor.checkpoint();
        }
        ImInt tick = new ImInt(ref.tick);
        if (ImGui.inputInt("Tick", tick, 1, 20, imgui.flag.ImGuiInputTextFlags.EnterReturnsTrue)) {
            editor.checkpoint();
            ref.track.keyframes.remove(ref.tick);
            int target = Math.max(0, tick.get());
            ref.track.keyframes.put(target, k);
            this.editing = new Ref(ref.track, target);
        }
        ImGui.separator();
        if (ImGui.button("Go To")) replay.seek(ref.tick);
        ImGui.sameLine();
        if (type == TrackType.CAMERA && ImGui.button("Set To Current Camera")) {
            editor.checkpoint();
            var player = Minecraft.getInstance().player;
            k.values = new double[]{replay.camera().x(), replay.camera().y(), replay.camera().z(), player.getYRot(), player.getXRot(), replay.camera().roll()};
        }
        ImGui.sameLine();
        if (ImGui.button("Delete")) {
            editor.checkpoint();
            ref.track.keyframes.remove(ref.tick);
            this.selected.remove(ref);
            ImGui.closeCurrentPopup();
        }
        ImGui.endPopup();
    }

    private void drawRuler(ImDrawList draw, float areaX, float y, float areaW) {
        double[] steps = {1, 2, 5, 10, 20, 40, 100, 200, 400, 1200, 2400, 6000, 12000, 24000, 72000};
        double step = steps[steps.length - 1];
        for (double s : steps) {
            if (s / this.ticksPerPixel >= 70) {
                step = s;
                break;
            }
        }
        double first = Math.ceil(this.viewStart / step) * step;
        for (double t = first; t < this.viewStart + areaW * this.ticksPerPixel; t += step) {
            float x = tickX(t, areaX);
            draw.addLine(x, y + RULER_HEIGHT - 8, x, y + RULER_HEIGHT, 0xFFB0B0B0);
            draw.addText(x + 3, y + 3, 0xFFB0B0B0, formatTime((int) t));
        }
    }

    private float tickX(double tick, float areaX) {
        return areaX + (float) ((tick - this.viewStart) / this.ticksPerPixel);
    }

    private int xTick(float x, float areaX) {
        return (int) Math.round(this.viewStart + (x - areaX) * this.ticksPerPixel);
    }

    private static int rgbToAbgr(int argb) {
        return (argb & 0xFF00FF00) | ((argb & 0xFF) << 16) | ((argb >> 16) & 0xFF);
    }

    static String formatTime(int ticks) {
        // Drawn many times a frame, and String.format is slow enough to show up.
        int seconds = ticks / 20, minutes = seconds / 60, rest = seconds % 60, hundredths = (ticks % 20) * 5;
        StringBuilder out = new StringBuilder(10).append(minutes).append(':');
        if (rest < 10) out.append('0');
        out.append(rest).append('.');
        if (hundredths < 10) out.append('0');
        return out.append(hundredths).toString();
    }

}
