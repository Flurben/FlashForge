package com.flurben.flashbackreforged.selftest;

import com.flurben.flashbackreforged.FlashbackReforged;
import com.flurben.flashbackreforged.client.ui.EditorUi;
import com.flurben.flashbackreforged.editor.EditorState;
import com.flurben.flashbackreforged.editor.Interpolation;
import com.flurben.flashbackreforged.editor.Keyframe;
import com.flurben.flashbackreforged.editor.KeyframeTrack;
import com.flurben.flashbackreforged.editor.Scene;
import com.flurben.flashbackreforged.editor.TrackType;
import com.flurben.flashbackreforged.export.ExportManager;
import com.flurben.flashbackreforged.export.ExportSettings;
import com.flurben.flashbackreforged.replay.ReplayStorage;
import com.flurben.flashbackreforged.playback.ReplayPlayer;
import com.flurben.flashbackreforged.record.Recorder;
import com.flurben.flashbackreforged.replay.ReplayMeta;
import com.google.gson.GsonBuilder;
import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.screens.GenericDirtMessageScreen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.Difficulty;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.LevelSettings;
import net.minecraft.world.level.WorldDataConfiguration;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.presets.WorldPresets;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * End-to-end test driven from inside the running game, enabled with {@code -Dflashback_reforged.selftest=<scenario>}.
 * Each step is a real user-level action (join world, record, open replay, seek) and the outcome is written to
 * {@code flashback_reforged-selftest.json} with screenshots, so behaviour can be checked without a human at the keyboard.
 */
public final class SelfTest {
    static {
        loadRequestFile();
    }

    private static final String SCENARIO = System.getProperty("flashback_reforged.selftest", "");
    private static final String WORLD = System.getProperty("flashback_reforged.selftest.world", "flashback_reforged_selftest");
    private static final int RECORD_TICKS = Integer.getInteger("flashback_reforged.selftest.ticks", 200);

    // Modpacks have their own terrain, which is the one worth testing.
    private static final net.minecraft.resources.ResourceKey<net.minecraft.world.level.levelgen.presets.WorldPreset> PRESET = System.getProperty("flashback_reforged.selftest.preset") == null ? WorldPresets.NORMAL
            : net.minecraft.resources.ResourceKey.create(Registries.WORLD_PRESET, new net.minecraft.resources.ResourceLocation(System.getProperty("flashback_reforged.selftest.preset")));

    private static Step step = Step.WAIT_TITLE;
    private static int wait;
    private static Path replayDir;
    private static Path combinedDir;
    private static final Map<String, Object> RESULT = new LinkedHashMap<>();
    private static final List<String> FAILURES = new ArrayList<>();
    private static int entitiesAtEnd;

    private SelfTest() {}

    /**
     * A launcher that is already open won't pick up new JVM arguments, so a test can also be requested with a properties
     * file in the game directory. It is removed on reading, so only the next launch runs the test.
     */
    private static void loadRequestFile() {
        Path file = net.minecraftforge.fml.loading.FMLPaths.GAMEDIR.get().resolve("flashback_reforged-selftest.properties");
        if (!java.nio.file.Files.exists(file)) return;
        try (java.io.Reader reader = java.nio.file.Files.newBufferedReader(file)) {
            java.util.Properties request = new java.util.Properties();
            request.load(reader);
            request.stringPropertyNames().forEach(key -> System.setProperty(key, request.getProperty(key)));
        } catch (java.io.IOException e) {
            FlashbackReforged.LOGGER.error("Could not read {}", file, e);
        }
        try {
            java.nio.file.Files.delete(file);
        } catch (java.io.IOException e) {
            FlashbackReforged.LOGGER.error("Could not remove {}", file, e);
        }
    }

    private enum Step {
        WAIT_TITLE, WAIT_WORLD, SETUP_WORLD, RECORDING, LEAVE, OPEN_REPLAY, PLAYING, SEEK_FORWARD, SEEK_BACK, FAR_VIEW, EDITOR, VISUALS, EXPORT_VIDEO, WAIT_VIDEO, WAIT_SCREENSHOT, SPECTATE, SPECTATE_PLAY, F_SNOW, F_OVERCAST, F_COMMANDS, F_PICK, F_AUDIO, F_ASPECT, F_ASPECT_DONE, F_TIP, F_HIDE, F_RNG_START, F_RNG_A, F_RNG_B, F_RNG_DONE, COMBINE, BROWSER, OPEN_COMBINED, COMBINED_CROSS, COMBINED_DONE, FINISH, DONE
    }

    public static boolean enabled() {
        return !SCENARIO.isEmpty();
    }

    public static void tick() {
        if (!enabled() || step == Step.DONE) return;
        try {
            run(Minecraft.getInstance());
        } catch (Throwable t) {
            FlashbackReforged.LOGGER.error("Self test crashed at step {}", step, t);
            FAILURES.add("crash at " + step + ": " + t);
            finish(Minecraft.getInstance());
        }
    }

    private static void go(Step next, int delay) {
        FlashbackReforged.LOGGER.info("[selftest] {} -> {}", step, next);
        step = next;
        wait = delay;
    }

    private static void run(Minecraft mc) throws Exception {
        if (wait > 0) {
            wait--;
            return;
        }
        if (afterShot != null) {
            Runnable action = afterShot;
            afterShot = null;
            action.run();
            wait = 20;
            return;
        }
        switch (step) {
            case WAIT_TITLE -> {
                if (mc.screen instanceof TitleScreen && mc.getOverlay() == null) {
                    joinWorld(mc);
                    go(Step.WAIT_WORLD, 20);
                }
            }
            case WAIT_WORLD -> {
                if (mc.level != null && mc.player != null && mc.screen == null) go(Step.SETUP_WORLD, 60);
            }
            case SETUP_WORLD -> {
                setupWorld(mc);
                Recorder.start();
                go(Step.RECORDING, 0);
            }
            case RECORDING -> {
                Recorder recorder = Recorder.active();
                if (recorder == null) throw new IllegalStateException("Recorder stopped unexpectedly");
                // Enough activity to make the replay's correctness visible in its packet stream and screenshots.
                if (recorder.tick() % 10 == 0 && mc.player != null) mc.player.setYRot(mc.player.getYRot() + 15f);
                if (recorder.tick() % 40 == 0 && mc.player != null) mc.player.swing(net.minecraft.world.InteractionHand.MAIN_HAND);
                if (recorder.tick() == RECORD_TICKS / 2) server(mc, "weather rain");
                // Open across the moment the thumbnail is taken, which must show the world and not this.
                if (recorder.tick() == 90) mc.setScreen(new net.minecraft.client.gui.screens.ChatScreen("thumbnail test"));
                if (recorder.tick() == 110) mc.setScreen(null);
                if (recorder.tick() >= RECORD_TICKS) {
                    screenshot(mc, "live");
                    entitiesAtEnd = count(mc);
                    replayDir = recorder.stop();
                    RESULT.put("recordedTicks", recorder.tick());
                    RESULT.put("liveEntities", entitiesAtEnd);
                    RESULT.put("liveTypes", entityTypes(mc));
                    go(Step.LEAVE, 5);
                }
            }
            case LEAVE -> {
                mc.level.disconnect();
                mc.clearLevel(new GenericDirtMessageScreen(Component.literal("Leaving")));
                mc.setScreen(new TitleScreen());
                go(Step.OPEN_REPLAY, 40);
            }
            case OPEN_REPLAY -> {
                ReplayMeta meta = ReplayMeta.read(replayDir);
                check(meta.finished, "metadata not marked finished");
                Path thumbnail = replayDir.resolve(com.flurben.flashbackreforged.record.Thumbnails.FILE);
                check(Files.exists(thumbnail), "replay has no thumbnail");
                if (Files.exists(thumbnail)) Files.copy(thumbnail, mc.gameDirectory.toPath().resolve("flashback_reforged-selftest/thumbnail.png"), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                check(Files.exists(replayDir.resolve("registries.nbt")), "registry snapshot missing");
                RESULT.put("replaySizeBytes", Files.walk(replayDir).filter(Files::isRegularFile).mapToLong(p -> p.toFile().length()).sum());
                // Same route as the replay browser: opening renders nested frames, so it can't happen inside a tick.
                mc.tell(() -> {
                    try {
                        ReplayPlayer.open(replayDir).setPaused(false);
                    } catch (Throwable t) {
                        FlashbackReforged.LOGGER.error("Could not open replay", t);
                        FAILURES.add("open failed: " + t);
                        finish(mc);
                    }
                });
                go(Step.PLAYING, 60);
            }
            case PLAYING -> {
                ReplayPlayer replay = ReplayPlayer.active();
                check(mc.level != null, "no level during playback");
                check(replay.tick() > 30, "replay did not advance: tick " + replay.tick());
                RESULT.put("tickAfterPlaying", replay.tick());
                RESULT.put("entitiesPlaying", count(mc));
                check(mc.level.getEntity(replay.recordedPlayerId()) != null, "recorded player not present");
                placeCamera(mc, replay);
                screenshot(mc, "playing");
                replay.setPaused(true);
                replay.seek(replay.totalTicks() - 5);
                go(Step.SEEK_FORWARD, 40);
            }
            case SEEK_FORWARD -> {
                ReplayPlayer replay = ReplayPlayer.active();
                check(replay.tick() == replay.totalTicks() - 5, "forward seek landed on " + replay.tick());
                RESULT.put("rainAtEnd", mc.level.isRaining());
                RESULT.put("replayEntitiesAtEnd", count(mc));
                RESULT.put("replayTypesAtEnd", entityTypes(mc));
                check(entityTypes(mc).containsKey("minecraft:armor_stand"), "entity summoned as recording began is missing from the replay");
                placeCamera(mc, replay);
                screenshot(mc, "seek_forward");
                replay.seek(10);
                go(Step.SEEK_BACK, 80);
            }
            case SEEK_BACK -> {
                ReplayPlayer replay = ReplayPlayer.active();
                check(replay.tick() == 10, "backward seek landed on " + replay.tick());
                check(mc.level.getEntity(replay.recordedPlayerId()) != null, "recorded player lost after backward seek");
                RESULT.put("entitiesAfterBackSeek", count(mc));
                RESULT.put("chunksAfterBackSeek", mc.level.getChunkSource().getLoadedChunksCount());
                check(mc.level.getChunkSource().getLoadedChunksCount() > 0, "no chunks after backward seek");
                RESULT.put("rainAfterBackSeek", mc.level.isRaining());
                placeCamera(mc, replay);
                screenshot(mc, "seek_back");
                RESULT.put("packetsApplied", replay.packetsApplied());
                RESULT.put("packetFailures", replay.packetFailures());
                if (SCENARIO.equals("basic")) {
                    go(Step.FINISH, 40);
                } else {
                    mc.getWindow().setWindowed(1600, 900);
                    // High above the world, whatever lies past the render distance is Distant Horizons' LODs or nothing.
                    Entity target = mc.level.getEntity(replay.recordedPlayerId());
                    replay.camera().teleport(target.getX(), target.getY() + 150, target.getZ(), 0f, 20f);
                    go(Step.FAR_VIEW, 200);
                }
            }
            case FAR_VIEW -> {
                screenshot(mc, "far_view");
                go(Step.EDITOR, 40);
            }
            case EDITOR -> {
                ReplayPlayer replay = ReplayPlayer.active();
                EditorState editor = replay.editor();
                Entity target = mc.level.getEntity(replay.recordedPlayerId());
                double px = target.getX(), py = target.getY(), pz = target.getZ();
                editor.checkpoint();
                Scene scene = editor.scene();
                KeyframeTrack camera = scene.trackForAdding(TrackType.CAMERA);
                camera.keyframes.put(20, new Keyframe(new double[]{px + 8, py + 4, pz + 8, 135, 20, 0}, Interpolation.SMOOTH));
                camera.keyframes.put(120, new Keyframe(new double[]{px - 8, py + 2, pz + 8, 225, 10, 0}, Interpolation.SMOOTH));
                scene.inTick = 20;
                scene.outTick = 120;
                replay.seek(70);
                EditorUi.get().requestWindowShot(mc.gameDirectory.toPath().resolve("flashback_reforged-selftest/editor.png"));
                go(Step.VISUALS, 40);
            }
            case VISUALS -> {
                ReplayPlayer replay = ReplayPlayer.active();
                EditorState editor = replay.editor();
                check(editor.canUndo(), "editor change left nothing to undo");
                // Keyframes should have moved the camera to the path's midpoint when the playhead moved.
                Entity target = mc.level.getEntity(replay.recordedPlayerId());
                RESULT.put("cameraAtMidpoint", replay.camera().x() + "," + replay.camera().y() + "," + replay.camera().z());
                check(target != null && Math.abs(replay.camera().x() - target.getX()) < 2, "keyframes did not drive the camera");
                editor.visuals().renderEntities = false;
                editor.visuals().renderPlayers = false;
                EditorUi.get().requestWindowShot(mc.gameDirectory.toPath().resolve("flashback_reforged-selftest/no_entities.png"));
                go(Step.EXPORT_VIDEO, 20);
            }
            case EXPORT_VIDEO -> {
                ReplayPlayer replay = ReplayPlayer.active();
                replay.editor().visuals().renderEntities = true;
                replay.editor().visuals().renderPlayers = true;
                ExportSettings video = new ExportSettings();
                video.name = "selftest";
                video.width = 1280;
                video.height = 720;
                video.fps = 30;
                video.startTick = 20;
                video.endTick = 120;
                video.bitrateKbps = 8000;
                video.warmupFrames = 10;
                video.output = ReplayStorage.exports().resolve("selftest.mp4");
                Files.deleteIfExists(video.output);
                ExportManager.enqueue(video);
                go(Step.WAIT_VIDEO, 5);
            }
            case WAIT_VIDEO -> {
                if (ExportManager.isExporting() || !ExportManager.queue().isEmpty()) return;
                Path file = ReplayStorage.exports().resolve("selftest.mp4");
                RESULT.put("exportError", ExportManager.lastError());
                check(Files.exists(file) && Files.size(file) > 10_000, "exported video missing or empty: " + ExportManager.lastError());
                RESULT.put("exportBytes", Files.exists(file) ? Files.size(file) : 0);
                ExportSettings shot = new ExportSettings();
                shot.name = "selftest";
                shot.width = 2560;
                shot.height = 1440;
                shot.screenshot = true;
                shot.format = ExportSettings.Format.PNG_SEQUENCE;
                shot.audio = false;
                shot.startTick = ReplayPlayer.active().tick();
                shot.endTick = shot.startTick + 1;
                shot.warmupFrames = 10;
                shot.output = ReplayStorage.exports().resolve("selftest.png");
                Files.deleteIfExists(shot.output);
                ExportManager.enqueue(shot);
                go(Step.WAIT_SCREENSHOT, 5);
            }
            case WAIT_SCREENSHOT -> {
                if (ExportManager.isExporting() || !ExportManager.queue().isEmpty()) return;
                Path file = ReplayStorage.exports().resolve("selftest.png");
                check(Files.exists(file) && Files.size(file) > 10_000, "exported screenshot missing or empty: " + ExportManager.lastError());
                RESULT.put("packetsApplied", ReplayPlayer.active().packetsApplied());
                RESULT.put("packetFailures", ReplayPlayer.active().packetFailures());
                ReplayPlayer replay = ReplayPlayer.active();
                replay.spectate(mc.level.getEntity(replay.recordedPlayerId()));
                replay.editor().visuals().showHud = true;
                replay.editor().visuals().showHotbar = true;
                replay.editor().visuals().showHand = true;
                go(Step.SPECTATE, 20);
            }
            case SPECTATE -> {
                EditorUi.get().requestWindowShot(mc.gameDirectory.toPath().resolve("flashback_reforged-selftest/spectate_hud.png"));
                ReplayPlayer.active().seek(20);
                ReplayPlayer.active().setPaused(false);
                go(Step.SPECTATE_PLAY, 40);
            }
            case SPECTATE_PLAY -> {
                EditorUi.get().requestWindowShot(mc.gameDirectory.toPath().resolve("flashback_reforged-selftest/spectate_playing.png"));
                ReplayPlayer replay = ReplayPlayer.active();
                replay.setPaused(true);
                replay.spectate(null);
                replay.editor().visuals().showHud = false;
                replay.editor().visuals().showHand = false;
                replay.editor().setKeyframesDriveCamera(false);
                replay.editor().visuals().weather = 5;
                placeCamera(mc, replay);
                go(Step.F_SNOW, 40);
            }
            case F_SNOW -> {
                check(mc.level.getRainLevel(1f) == 1f, "snow override did not start precipitation");
                shot(mc, "weather_snow", () -> ReplayPlayer.active().editor().visuals().weather = 4);
                go(Step.F_OVERCAST, 5);
            }
            case F_OVERCAST -> {
                check(com.flurben.flashbackreforged.client.VisualsRuntime.hideWeather(), "overcast still draws rain");
                shot(mc, "weather_overcast", () -> ReplayPlayer.active().editor().visuals().weather = 0);
                go(Step.F_COMMANDS, 5);
            }
            case F_COMMANDS -> {
                ReplayPlayer replay = ReplayPlayer.active();
                var hidden = replay.editor().visuals().hiddenEntities;
                mc.player.connection.sendCommand("hide @e[type=minecraft:pig]");
                check(!hidden.isEmpty(), "/hide did not hide the pig");
                RESULT.put("hiddenByCommand", hidden.size());
                mc.player.connection.sendCommand("show @e[type=minecraft:pig]");
                check(hidden.isEmpty(), "/show did not bring the pig back");
                pickTarget = placeFacingClearEntity(mc, replay);
                check(pickTarget != null, "no entity with a clear view from any side");
                RESULT.put("pickTarget", pickTarget == null ? "none" : net.minecraft.world.entity.EntityType.getKey(pickTarget.getType()).toString());
                go(Step.F_PICK, 20);
            }
            case F_PICK -> {
                Entity picked = EditorUi.get().pickAt(0, 0);
                check(picked != null && pickTarget != null && picked.getType() == pickTarget.getType(), "picking at the center found " + picked);
                if (picked == null) picked = pickTarget;
                Entity elsewhere = EditorUi.get().pickAt(0.95, 0.95);
                RESULT.put("pickedOffCenter", elsewhere == null ? "nothing" : elsewhere.getType().toString());
                check(elsewhere != picked, "picking far from the entity still found it");
                pickTarget = picked;
                EditorUi.get().entityPopup().open(picked);
                ReplayPlayer.active().editor().visuals().audioSource = picked.getUUID().toString();
                go(Step.F_AUDIO, 20);
            }
            case F_AUDIO -> {
                ReplayPlayer replay = ReplayPlayer.active();
                Entity source = null;
                for (Entity e : mc.level.entitiesForRendering()) {
                    if (e.getUUID().toString().equals(replay.editor().visuals().audioSource)) source = e;
                }
                var heard = com.flurben.flashbackreforged.client.VisualsRuntime.lastListener();
                RESULT.put("heardFromDistance", heard == null || source == null ? -1 : heard.distanceTo(source.getEyePosition()));
                check(source != null && heard != null && heard.distanceTo(source.getEyePosition()) < 0.5, "sounds are not heard from the audio source");
                shot(mc, "entity_popup", () -> {
                    replay.editor().visuals().audioSource = null;
                    EditorUi.get().entityPopup().close();
                    replay.editor().visuals().sizing = 1;
                    replay.editor().visuals().viewAspect = 1f;
                });
                go(Step.F_ASPECT, 5);
            }
            case F_ASPECT -> {
                check(mc.getWindow().getWidth() == mc.getWindow().getHeight(), "1:1 view renders at " + mc.getWindow().getWidth() + "x" + mc.getWindow().getHeight());
                RESULT.put("squareSize", mc.getWindow().getWidth());
                shot(mc, "aspect_square", () -> ReplayPlayer.active().editor().visuals().sizing = 3);
                go(Step.F_ASPECT_DONE, 5);
            }
            case F_ASPECT_DONE -> {
                shot(mc, "behind_panels", () -> {
                    ReplayPlayer.active().editor().visuals().sizing = 0;
                    EditorUi.get().tips().show();
                });
                testKeyframeMath();
                go(Step.F_TIP, 5);
            }
            case F_TIP -> {
                check(mc.getWindow().getWidth() != mc.getWindow().getHeight(), "view stayed square after going back to the window's shape");
                shot(mc, "tip", () -> {
                    EditorUi.get().tips().close();
                    mc.player.connection.sendCommand("hide " + pickTarget.getUUID());
                });
                go(Step.F_HIDE, 5);
            }
            case F_HIDE -> {
                check(!ReplayPlayer.active().editor().visuals().hiddenEntities.isEmpty(), "/hide by UUID did not work");
                shot(mc, "hidden_outline");
                go(Step.F_RNG_START, 5);
            }
            case F_RNG_START -> {
                rngExport("rng_a");
                go(Step.F_RNG_A, 5);
            }
            case F_RNG_A -> {
                if (ExportManager.isExporting() || !ExportManager.queue().isEmpty()) return;
                rngExport("rng_b");
                go(Step.F_RNG_B, 5);
            }
            case F_RNG_B -> {
                if (ExportManager.isExporting() || !ExportManager.queue().isEmpty()) return;
                Path a = lastFrame("rng_a");
                Path b = lastFrame("rng_b");
                RESULT.put("repeatExportIdentical", java.util.Arrays.equals(Files.readAllBytes(a), Files.readAllBytes(b)));
                Files.copy(a, mc.gameDirectory.toPath().resolve("flashback_reforged-selftest/hidden_export.png"), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                ReplayPlayer replay = ReplayPlayer.active();
                replay.editor().visuals().hiddenEntities.clear();
                replay.editor().setKeyframesDriveCamera(true);
                go(Step.F_RNG_DONE, 5);
            }
            case F_RNG_DONE -> {
                RESULT.put("packetFailures", ReplayPlayer.active().packetFailures());
                go(Step.COMBINE, 10);
            }
            case COMBINE -> {
                ReplayPlayer.active().editor().visuals().showHud = false;
                ReplayPlayer.active().editor().visuals().showHand = false;
                combinedDir = com.flurben.flashbackreforged.replay.ReplayCombiner.combine(replayDir, replayDir);
                ReplayPlayer.active().exit();
                mc.setScreen(new com.flurben.flashbackreforged.client.ui.ReplayBrowserScreen(new TitleScreen()));
                go(Step.BROWSER, 20);
            }
            case BROWSER -> {
                EditorUi.get().requestWindowShot(mc.gameDirectory.toPath().resolve("flashback_reforged-selftest/browser.png"));
                go(Step.OPEN_COMBINED, 10);
            }
            case OPEN_COMBINED -> {
                mc.setScreen(new TitleScreen());
                mc.tell(() -> {
                    try {
                        ReplayPlayer opened = ReplayPlayer.open(combinedDir);
                        // Just short of the join between the two recordings, then play across it.
                        opened.seek(opened.totalTicks() / 2 - 3);
                        opened.setPaused(false);
                    } catch (Throwable t) {
                        FlashbackReforged.LOGGER.error("Could not open combined replay", t);
                        FAILURES.add("combined open failed: " + t);
                        finish(mc);
                    }
                });
                go(Step.COMBINED_CROSS, 60);
            }
            case COMBINED_CROSS -> {
                ReplayPlayer replay = ReplayPlayer.active();
                RESULT.put("combinedTotal", replay.totalTicks());
                RESULT.put("combinedTick", replay.tick());
                check(replay.totalTicks() == 2 * RECORD_TICKS, "combined length is " + replay.totalTicks());
                check(replay.tick() > replay.totalTicks() / 2 + 20, "did not play across the join: tick " + replay.tick());
                check(mc.level.getEntity(replay.recordedPlayerId()) != null, "recorded player lost across the join");
                RESULT.put("combinedEntities", count(mc));
                RESULT.put("combinedChunks", mc.level.getChunkSource().getLoadedChunksCount());
                check(mc.level.getChunkSource().getLoadedChunksCount() > 0, "no chunks after the join");
                RESULT.put("combinedFailures", replay.packetFailures());
                replay.setPaused(true);
                go(Step.COMBINED_DONE, 5);
            }
            case COMBINED_DONE -> {
                ReplayPlayer.active().exit();
                com.flurben.flashbackreforged.replay.ReplayStorage.delete(combinedDir);
                go(Step.FINISH, 20);
            }
            case FINISH -> finish(mc);
            default -> {
            }
        }
    }

    private static void shot(Minecraft mc, String name) {
        EditorUi.get().requestWindowShot(mc.gameDirectory.toPath().resolve("flashback_reforged-selftest/" + name + ".png"));
    }

    /** A shot is taken on the next frame, so a change that would spoil it has to wait until after. */
    private static void shot(Minecraft mc, String name, Runnable after) {
        shot(mc, name);
        afterShot = after;
    }

    private static Runnable afterShot;

    private static Entity pickTarget;

    /** A mod pack's rough terrain can hide any one entity, so this looks for one that can be seen, and from which side. */
    private static Entity placeFacingClearEntity(Minecraft mc, ReplayPlayer replay) {
        float eye = mc.player.getEyeHeight();
        java.util.List<Entity> entities = new java.util.ArrayList<>();
        for (Entity e : mc.level.entitiesForRendering()) {
            if (e != mc.player) entities.add(e);
        }
        Entity recorded = mc.level.getEntity(replay.recordedPlayerId());
        entities.sort(java.util.Comparator.comparingDouble(e -> recorded == null ? 0 : e.distanceToSqr(recorded)));
        for (Entity target : entities) {
            net.minecraft.world.phys.Vec3 aim = target.getBoundingBox().getCenter();
            for (float yaw : new float[]{90f, 270f, 0f, 180f}) {
                net.minecraft.world.phys.Vec3 from = aim.subtract(net.minecraft.world.phys.Vec3.directionFromRotation(0, yaw).scale(4));
                var hit = mc.level.clip(new net.minecraft.world.level.ClipContext(from, aim, net.minecraft.world.level.ClipContext.Block.OUTLINE, net.minecraft.world.level.ClipContext.Fluid.NONE, mc.player));
                if (hit.getType() == net.minecraft.world.phys.HitResult.Type.MISS) {
                    replay.camera().teleport(from.x, from.y - eye, from.z, yaw, 0f);
                    return target;
                }
            }
        }
        return null;
    }

    private static Entity findStand(Minecraft mc) {
        for (Entity e : mc.level.entitiesForRendering()) {
            if (e.getType() == net.minecraft.world.entity.EntityType.ARMOR_STAND) return e;
        }
        return null;
    }

    /** Twenty ticks at a frame per tick, from the same place, so two runs can be compared. */
    private static void rngExport(String name) throws java.io.IOException {
        Path dir = ReplayStorage.exports().resolve(name);
        if (Files.exists(dir)) {
            try (var files = Files.walk(dir)) {
                for (Path p : files.sorted(java.util.Comparator.reverseOrder()).toList()) Files.delete(p);
            }
        }
        ExportSettings s = new ExportSettings();
        s.name = name;
        s.width = 1280;
        s.height = 720;
        s.fps = 20;
        s.startTick = 20;
        s.endTick = 40;
        s.warmupFrames = 5;
        s.audio = false;
        s.format = ExportSettings.Format.PNG_SEQUENCE;
        s.output = dir;
        ExportManager.enqueue(s);
    }

    private static Path lastFrame(String name) throws java.io.IOException {
        try (var files = Files.list(ReplayStorage.exports().resolve(name))) {
            return files.sorted().reduce((a, b) -> b).orElseThrow();
        }
    }

    private static void close(double[] actual, double... expected) {
        for (int i = 0; i < expected.length; i++) {
            check(Math.abs(actual[i] - expected[i]) < 1e-6, "keyframe math: got " + java.util.Arrays.toString(actual) + ", wanted " + java.util.Arrays.toString(expected));
        }
    }

    /** The results can be worked out by hand, so these don't depend on anything in the world. */
    private static void testKeyframeMath() {
        // Two blocks ahead of a camera at the origin facing +Z (yaw 0).
        Keyframe k = new Keyframe(new double[]{0, 0, 2, 10, 5, 0}, Interpolation.SMOOTH);
        com.flurben.flashbackreforged.editor.RelativeOffset turn = new com.flurben.flashbackreforged.editor.RelativeOffset();
        turn.newX = 100;
        turn.newY = 64;
        turn.newZ = -50;
        turn.newYaw = 90;
        turn.apply(TrackType.CAMERA, k);
        // Yaw 90 looks toward -X, so "two blocks ahead" is now two blocks along -X from the new spot.
        close(k.values, 98, 64, -50, 100, 5, 0);

        Keyframe down = new Keyframe(new double[]{0, 0, 2, 0, 0, 0}, Interpolation.SMOOTH);
        com.flurben.flashbackreforged.editor.RelativeOffset tilt = new com.flurben.flashbackreforged.editor.RelativeOffset();
        tilt.newPitch = 90;
        tilt.apply(TrackType.CAMERA, down);
        // Pitch 90 looks straight down.
        close(down.values, 0, -2, 0, 0, 90, 0);

        KeyframeTrack track = new KeyframeTrack(TrackType.FOV);
        track.keyframes.put(10, new Keyframe(new double[]{1}, Interpolation.SMOOTH));
        track.keyframes.put(20, new Keyframe(new double[]{2}, Interpolation.SMOOTH));
        track.keyframes.put(30, new Keyframe(new double[]{3}, Interpolation.SMOOTH));
        track.scale(java.util.List.of(10, 20, 30), 10, 2.0);
        check(track.keyframes.keySet().equals(java.util.Set.of(10, 30, 50)), "scaling gave ticks " + track.keyframes.keySet());
        check(track.keyframes.get(50).values[0] == 3, "scaling mixed up the keyframes");
    }

    private static void placeCamera(Minecraft mc, ReplayPlayer replay) {
        Entity target = mc.level.getEntity(replay.recordedPlayerId());
        if (target != null) {
            replay.camera().teleport(target.getX() + 4, target.getY() + 3, target.getZ() + 4, 135f, 25f);
        }
    }

    private static Map<String, Integer> entityTypes(Minecraft mc) {
        Map<String, Integer> types = new java.util.TreeMap<>();
        for (Entity entity : mc.level.entitiesForRendering()) types.merge(net.minecraft.world.entity.EntityType.getKey(entity.getType()).toString(), 1, Integer::sum);
        return types;
    }

    private static int count(Minecraft mc) {
        int n = 0;
        for (Entity ignored : mc.level.entitiesForRendering()) n++;
        return n;
    }

    private static void joinWorld(Minecraft mc) {
        if (mc.getLevelSource().levelExists(WORLD)) {
            mc.createWorldOpenFlows().loadLevel(new TitleScreen(), WORLD);
            return;
        }
        LevelSettings settings = new LevelSettings(WORLD, GameType.CREATIVE, false, Difficulty.EASY, true, new GameRules(), WorldDataConfiguration.DEFAULT);
        WorldOptions options = new WorldOptions(1234L, true, false);
        mc.createWorldOpenFlows().createFreshLevel(WORLD, settings, options, access -> access.registryOrThrow(Registries.WORLD_PRESET).getHolderOrThrow(PRESET).value().createWorldDimensions());
    }

    private static void setupWorld(Minecraft mc) {
        server(mc, "time set 1000");
        server(mc, "weather clear");
        server(mc, "summon minecraft:zombie ~3 ~ ~3 {NoAI:1b,CustomName:'\"Zed\"',CustomNameVisible:1b}");
        server(mc, "summon minecraft:armor_stand ~-3 ~ ~3 {CustomName:'\"Stand\"',CustomNameVisible:1b}");
        server(mc, "summon minecraft:pig ~ ~ ~4");
        server(mc, "setblock ~2 ~ ~2 minecraft:gold_block");
    }

    private static void server(Minecraft mc, String command) {
        if (mc.getSingleplayerServer() == null || mc.player == null) return;
        var server = mc.getSingleplayerServer();
        var player = server.getPlayerList().getPlayer(mc.player.getUUID());
        if (player == null) return;
        server.execute(() -> server.getCommands().performPrefixedCommand(player.createCommandSourceStack().withPermission(4).withSuppressedOutput(), command));
    }

    private static void screenshot(Minecraft mc, String name) throws Exception {
        File dir = new File(mc.gameDirectory, "flashback_reforged-selftest");
        Files.createDirectories(dir.toPath());
        NativeImage image = Screenshot.takeScreenshot(mc.getMainRenderTarget());
        image.writeToFile(new File(dir, name + ".png"));
        image.close();
    }

    private static void check(boolean ok, String message) {
        if (!ok) {
            FlashbackReforged.LOGGER.error("[selftest] FAILED: {}", message);
            FAILURES.add(message);
        }
    }

    private static void finish(Minecraft mc) {
        step = Step.DONE;
        RESULT.put("failures", FAILURES);
        RESULT.put("passed", FAILURES.isEmpty());
        try {
            Path out = mc.gameDirectory.toPath().resolve("flashback_reforged-selftest.json");
            Files.writeString(out, new GsonBuilder().setPrettyPrinting().create().toJson(RESULT));
        } catch (Exception e) {
            FlashbackReforged.LOGGER.error("Could not write self test result", e);
        }
        FlashbackReforged.LOGGER.info("[selftest] RESULT {}", RESULT);
        if (ReplayPlayer.active() != null) ReplayPlayer.active().exit();
        mc.stop();
    }
}
