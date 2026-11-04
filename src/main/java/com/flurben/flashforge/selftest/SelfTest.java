package com.flurben.flashforge.selftest;

import com.flurben.flashforge.FlashForge;
import com.flurben.flashforge.playback.ReplayPlayer;
import com.flurben.flashforge.record.Recorder;
import com.flurben.flashforge.replay.ReplayMeta;
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
 * End-to-end test driven from inside the running game, enabled with {@code -Dflashforge.selftest=<scenario>}.
 * Each step is a real user-level action (join world, record, open replay, seek) and the outcome is written to
 * {@code flashforge-selftest.json} with screenshots, so behaviour can be checked without a human at the keyboard.
 */
public final class SelfTest {
    private static final String SCENARIO = System.getProperty("flashforge.selftest", "");
    private static final String WORLD = System.getProperty("flashforge.selftest.world", "flashforge_selftest");
    private static final int RECORD_TICKS = Integer.getInteger("flashforge.selftest.ticks", 200);

    private static Step step = Step.WAIT_TITLE;
    private static int wait;
    private static Path replayDir;
    private static final Map<String, Object> RESULT = new LinkedHashMap<>();
    private static final List<String> FAILURES = new ArrayList<>();
    private static int entitiesAtEnd;

    private SelfTest() {}

    private enum Step {
        WAIT_TITLE, WAIT_WORLD, SETUP_WORLD, RECORDING, LEAVE, OPEN_REPLAY, PLAYING, SEEK_FORWARD, SEEK_BACK, FINISH, DONE
    }

    public static boolean enabled() {
        return !SCENARIO.isEmpty();
    }

    public static void tick() {
        if (!enabled() || step == Step.DONE) return;
        try {
            run(Minecraft.getInstance());
        } catch (Throwable t) {
            FlashForge.LOGGER.error("Self test crashed at step {}", step, t);
            FAILURES.add("crash at " + step + ": " + t);
            finish(Minecraft.getInstance());
        }
    }

    private static void go(Step next, int delay) {
        FlashForge.LOGGER.info("[selftest] {} -> {}", step, next);
        step = next;
        wait = delay;
    }

    private static void run(Minecraft mc) throws Exception {
        if (wait > 0) {
            wait--;
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
                if (recorder.tick() >= RECORD_TICKS) {
                    screenshot(mc, "live");
                    entitiesAtEnd = count(mc);
                    replayDir = recorder.stop();
                    RESULT.put("recordedTicks", recorder.tick());
                    RESULT.put("liveEntities", entitiesAtEnd);
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
                check(Files.exists(replayDir.resolve("registries.nbt")), "registry snapshot missing");
                RESULT.put("replaySizeBytes", Files.walk(replayDir).filter(Files::isRegularFile).mapToLong(p -> p.toFile().length()).sum());
                // Same route as the replay browser: opening renders nested frames, so it can't happen inside a tick.
                mc.tell(() -> {
                    try {
                        ReplayPlayer.open(replayDir).setPaused(false);
                    } catch (Throwable t) {
                        FlashForge.LOGGER.error("Could not open replay", t);
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
                go(Step.FINISH, 40);
            }
            case FINISH -> finish(mc);
            default -> {
            }
        }
    }

    private static void placeCamera(Minecraft mc, ReplayPlayer replay) {
        Entity target = mc.level.getEntity(replay.recordedPlayerId());
        if (target != null) {
            replay.camera().teleport(target.getX() + 4, target.getY() + 3, target.getZ() + 4, 135f, 25f);
        }
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
        mc.createWorldOpenFlows().createFreshLevel(WORLD, settings, options, access -> access.registryOrThrow(Registries.WORLD_PRESET).getHolderOrThrow(WorldPresets.NORMAL).value().createWorldDimensions());
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
        File dir = new File(mc.gameDirectory, "flashforge-selftest");
        Files.createDirectories(dir.toPath());
        NativeImage image = Screenshot.takeScreenshot(mc.getMainRenderTarget());
        image.writeToFile(new File(dir, name + ".png"));
        image.close();
    }

    private static void check(boolean ok, String message) {
        if (!ok) {
            FlashForge.LOGGER.error("[selftest] FAILED: {}", message);
            FAILURES.add(message);
        }
    }

    private static void finish(Minecraft mc) {
        step = Step.DONE;
        RESULT.put("failures", FAILURES);
        RESULT.put("passed", FAILURES.isEmpty());
        try {
            Path out = mc.gameDirectory.toPath().resolve("flashforge-selftest.json");
            Files.writeString(out, new GsonBuilder().setPrettyPrinting().create().toJson(RESULT));
        } catch (Exception e) {
            FlashForge.LOGGER.error("Could not write self test result", e);
        }
        FlashForge.LOGGER.info("[selftest] RESULT {}", RESULT);
        if (ReplayPlayer.active() != null) ReplayPlayer.active().exit();
        mc.stop();
    }
}
