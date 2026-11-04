package com.flurben.flashbackreforged.editor;

import com.flurben.flashbackreforged.FlashbackReforged;
import com.flurben.flashbackreforged.playback.ReplayPlayer;
import com.flurben.flashbackreforged.playback.ViewerCamera;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.minecraft.client.Minecraft;
import net.minecraft.commands.arguments.blocks.BlockStateParser;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * The editable project attached to a replay: scenes, visual settings and undo history. Applies the current scene to
 * the game once per rendered frame. Saved next to the recording, so the recording itself stays untouched.
 */
public final class EditorState {
    public static final String FILE = "editor.json";
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final int MAX_UNDO = 64;

    /** The portion that is written to disk. */
    private static final class Data {
        List<Scene> scenes = new ArrayList<>();
        int currentScene;
        Visuals visuals = new Visuals();
    }

    private final Path file;
    private final ReplayPlayer player;
    private Data data;
    private final ArrayDeque<String> undo = new ArrayDeque<>();
    private final ArrayDeque<String> redo = new ArrayDeque<>();
    private boolean keyframesDriveCamera = true;
    private double lastShakeX, lastShakeY;

    private EditorState(Path file, ReplayPlayer player, Data data) {
        this.file = file;
        this.player = player;
        this.data = data;
        if (data.scenes.isEmpty()) data.scenes.add(new Scene());
        if (data.visuals == null) data.visuals = new Visuals();
    }

    public static EditorState load(Path dir, ReplayPlayer player) {
        Path file = dir.resolve(FILE);
        Data data = new Data();
        if (Files.exists(file)) {
            try {
                data = GSON.fromJson(Files.readString(file, StandardCharsets.UTF_8), Data.class);
            } catch (Exception e) {
                // Keep the broken file for inspection instead of overwriting the only copy of someone's work.
                FlashbackReforged.LOGGER.error("Could not read {}; starting with an empty project", file, e);
                try {
                    Files.move(file, dir.resolve(FILE + ".broken"));
                } catch (IOException ignored) {
                }
                data = new Data();
            }
        }
        return new EditorState(file, player, data);
    }

    public void save() {
        try {
            Files.writeString(this.file, GSON.toJson(this.data), StandardCharsets.UTF_8);
        } catch (IOException e) {
            FlashbackReforged.LOGGER.error("Could not save editor state", e);
        }
    }

    public Scene scene() {
        return this.data.scenes.get(Math.min(this.data.currentScene, this.data.scenes.size() - 1));
    }

    public List<Scene> scenes() {
        return this.data.scenes;
    }

    public int currentSceneIndex() {
        return this.data.currentScene;
    }

    public void selectScene(int index) {
        this.data.currentScene = Math.max(0, Math.min(index, this.data.scenes.size() - 1));
    }

    public Visuals visuals() {
        return this.data.visuals;
    }

    public boolean keyframesDriveCamera() {
        return this.keyframesDriveCamera;
    }

    public void setKeyframesDriveCamera(boolean drive) {
        this.keyframesDriveCamera = drive;
    }

    /** Call before any change the user should be able to undo. */
    public void checkpoint() {
        this.undo.addLast(GSON.toJson(this.data));
        if (this.undo.size() > MAX_UNDO) this.undo.removeFirst();
        this.redo.clear();
    }

    public boolean canUndo() {
        return !this.undo.isEmpty();
    }

    public boolean canRedo() {
        return !this.redo.isEmpty();
    }

    public void undo() {
        if (this.undo.isEmpty()) return;
        this.redo.addLast(GSON.toJson(this.data));
        this.data = GSON.fromJson(this.undo.removeLast(), Data.class);
    }

    public void redo() {
        if (this.redo.isEmpty()) return;
        this.undo.addLast(GSON.toJson(this.data));
        this.data = GSON.fromJson(this.redo.removeLast(), Data.class);
    }

    public void onSeek(int tick) {
        applyBlockOverrides(tick, true);
        this.lastBlockTick = tick;
    }

    private int lastBlockTick = -1;
    private double lastDrivenTick = Double.NaN;

    /** Applied per rendered frame, so camera moves are smooth regardless of tick rate. */
    public void applyFrame(double tick) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) return;
        Scene scene = scene();
        ViewerCamera camera = this.player.camera();
        Visuals visuals = this.data.visuals;

        // Time and FOV
        double[] time = scene.evaluate(TrackType.TIME_OF_DAY, tick);
        if (time != null) mc.level.getLevelData().setDayTime((long) time[0]);
        else if (visuals.overrideTime) mc.level.getLevelData().setDayTime(visuals.time);

        double[] fov = scene.evaluate(TrackType.FOV, tick);
        if (fov != null) camera.setFov((float) fov[0]);
        else camera.setFov(visuals.overrideFov ? visuals.fov : ViewerCamera.DEFAULT_FOV);

        int whole = (int) Math.floor(tick);
        if (whole != this.lastBlockTick) {
            applyBlockOverrides(whole, false);
            this.lastBlockTick = whole;
        }

        // While paused the path only takes over when the playhead moves, leaving the camera free to line up a keyframe.
        boolean sameFrame = this.player.paused() && tick == this.lastDrivenTick;
        this.lastDrivenTick = tick;
        if (!this.keyframesDriveCamera || sameFrame) return;
        boolean moved = false;
        double x = camera.x(), y = camera.y(), z = camera.z();
        float yaw = mc.player.getYRot(), pitch = mc.player.getXRot(), roll = camera.roll();

        double[] cam = scene.evaluate(TrackType.CAMERA, tick);
        if (cam != null) {
            x = cam[0];
            y = cam[1];
            z = cam[2];
            yaw = (float) cam[3];
            pitch = (float) cam[4];
            roll = (float) cam[5];
            moved = true;
        }

        double[] orbit = scene.evaluate(TrackType.ORBIT, tick);
        if (orbit != null) {
            Vec3 direction = Vec3.directionFromRotation((float) orbit[5], (float) orbit[4]);
            x = orbit[0] - direction.x * orbit[3];
            y = orbit[1] - direction.y * orbit[3];
            z = orbit[2] - direction.z * orbit[3];
            yaw = (float) orbit[4];
            pitch = (float) orbit[5];
            roll = 0;
            moved = true;
        }

        for (KeyframeTrack track : scene.tracks) {
            if (track.type != TrackType.TRACK_ENTITY) continue;
            double[] v = track.evaluate(tick);
            if (v == null) continue;
            var kf = track.keyframes.floorEntry(whole);
            if (kf == null) kf = track.keyframes.firstEntry();
            Entity target = kf.getValue().text == null ? null : findEntity(kf.getValue().text);
            if (target == null) continue;
            float partial = mc.getFrameTime();
            Vec3 at = target.getPosition(partial).add(v[1], v[2] + (v[4] > 0.5 ? target.getEyeHeight() : target.getBbHeight() / 2), v[3]);
            if (v[0] > 0.5) {
                // Follow: keep the existing offset direction behind the target.
                Vec3 back = Vec3.directionFromRotation(pitch, yaw).scale(-4);
                x = at.x + back.x;
                y = at.y + back.y;
                z = at.z + back.z;
            }
            double dx = at.x - x, dy = at.y - y, dz = at.z - z;
            double flat = Math.sqrt(dx * dx + dz * dz);
            yaw = (float) (Math.toDegrees(Math.atan2(dz, dx)) - 90.0);
            pitch = (float) -Math.toDegrees(Math.atan2(dy, flat));
            moved = true;
        }

        double[] shake = scene.evaluate(TrackType.SHAKE, tick);
        if (shake != null) {
            // Sums of unrelated sine waves look like handheld motion and are repeatable between preview and export.
            double f = shake[2];
            double sx = (Math.sin(tick * 0.31 * f) + Math.sin(tick * 0.73 * f + 1.3) * 0.5 + Math.sin(tick * 1.57 * f + 2.1) * 0.25) / 1.75;
            double sy = (Math.sin(tick * 0.27 * f + 4.1) + Math.sin(tick * 0.89 * f + 0.7) * 0.5 + Math.sin(tick * 1.43 * f + 3.3) * 0.25) / 1.75;
            yaw += (float) (sx * shake[0] * 4);
            pitch += (float) (sy * shake[1] * 4);
            moved = true;
        }

        if (moved) {
            camera.teleport(x, y, z, yaw, pitch);
            camera.setRoll(roll);
        }
    }

    private static String lastFindText;
    private static UUID lastFindId;

    private static Entity findEntity(String uuid) {
        Minecraft mc = Minecraft.getInstance();
        // Asked every frame while a track follows an entity, for what's usually the same one as last time.
        if (!uuid.equals(lastFindText)) {
            lastFindText = uuid;
            try {
                lastFindId = UUID.fromString(uuid);
            } catch (IllegalArgumentException e) {
                lastFindId = null;
            }
        }
        if (lastFindId == null) return null;
        for (Entity e : mc.level.entitiesForRendering()) {
            if (e.getUUID().equals(lastFindId)) return e;
        }
        return null;
    }

    /** Block overrides are client-only changes, re-applied after a seek since seeking rebuilds the world from packets. */
    private void applyBlockOverrides(int tick, boolean all) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return;
        for (KeyframeTrack track : scene().tracks) {
            if (track.type != TrackType.BLOCK_OVERRIDE || !track.enabled) continue;
            var range = all ? track.keyframes.headMap(tick, true) : track.keyframes.subMap(this.lastBlockTick, false, tick, true);
            if (!all && tick < this.lastBlockTick) continue;
            for (Keyframe k : range.values()) {
                try {
                    BlockState state = BlockStateParser.parseForBlock(BuiltInRegistries.BLOCK.asLookup(), k.text == null ? "minecraft:air" : k.text, false).blockState();
                    BlockPos pos = BlockPos.containing(k.values[0], k.values[1], k.values[2]);
                    mc.level.setBlock(pos, state, 11);
                    this.player.chunkChanged(pos);
                } catch (Exception e) {
                    FlashbackReforged.LOGGER.debug("Bad block override {}", k.text, e);
                }
            }
        }
    }
}
