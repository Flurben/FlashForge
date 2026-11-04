package com.flurben.flashbackreforged.selftest;

import com.flurben.flashbackreforged.FlashbackReforged;
import com.flurben.flashbackreforged.client.ui.EditorUi;
import com.flurben.flashbackreforged.client.ui.ReplayBrowserScreen;
import com.flurben.flashbackreforged.compat.RenderReadiness;
import com.flurben.flashbackreforged.export.ExportManager;
import com.flurben.flashbackreforged.export.ExportSettings;
import com.flurben.flashbackreforged.perf.Perf;
import com.flurben.flashbackreforged.playback.ReplayAnalyzer;
import com.flurben.flashbackreforged.playback.ReplayPlayer;
import com.flurben.flashbackreforged.record.Recorder;
import com.flurben.flashbackreforged.replay.ReplayStorage;
import com.google.gson.GsonBuilder;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.GenericDirtMessageScreen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.Difficulty;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.LevelSettings;
import net.minecraft.world.level.WorldDataConfiguration;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.presets.WorldPresets;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The profiling run, started like the self test with scenario {@code perf}. It plays the same scripted session every time:
 * a fresh world from a fixed seed, a flight out and back with mobs, block changes and particles going, then opening the
 * recording, playing, seeking and exporting. Results go to {@code flashback_reforged-perf.json}.
 */
final class PerfRun {
    private static final String WORLD = "flashback_reforged_perf";
    private static final int FLY_TICKS = Integer.getInteger("flashback_reforged.perf.flyTicks", 1200);
    private static final int HOVER_TICKS = Integer.getInteger("flashback_reforged.perf.hoverTicks", 1200);
    private static final double BLOCKS_PER_TICK = 0.3;
    private static final int RENDER_DISTANCE = Integer.getInteger("flashback_reforged.perf.renderDistance", 12);

    private static final net.minecraft.resources.ResourceKey<net.minecraft.world.level.levelgen.presets.WorldPreset> PRESET = System.getProperty("flashback_reforged.selftest.preset") == null ? WorldPresets.NORMAL
            : net.minecraft.resources.ResourceKey.create(Registries.WORLD_PRESET, new net.minecraft.resources.ResourceLocation(System.getProperty("flashback_reforged.selftest.preset")));

    private enum Step {
        TITLE, WORLD, SETTLE, IDLE, FLY_OUT, HOVER_FAR, FLY_BACK, HOVER_HOME, STOP, LEAVE, OPEN, OPENED, PLAY_EDITOR, PLAY_HIDDEN, SEEKS, SEEK_WAIT, WORLD_CHECK, EXPORT_VIDEO, WAIT_VIDEO, EXPORT_PNG, WAIT_PNG, BROWSER, FINISH, DONE
    }

    private static Step step = Step.TITLE;
    private static int wait;
    private static int ticks;
    private static double startX, startY, startZ;
    private static Path replayDir;
    private static final Map<String, Object> OUT = new LinkedHashMap<>();
    private static final List<Map<String, Object>> SEEKS = new ArrayList<>();
    private static int seekIndex;
    private static long seekStart;
    private static double seekMainMs;
    private static boolean toggle;
    private static int checkWait;

    private PerfRun() {}

    static void tick(Minecraft mc) {
        try {
            run(mc);
        } catch (Throwable t) {
            FlashbackReforged.LOGGER.error("Profiling run crashed at {}", step, t);
            OUT.put("crash", step + ": " + t);
            finish(mc);
        }
    }

    private static void go(Step next, int delay) {
        FlashbackReforged.LOGGER.info("[perf] {} -> {}", step, next);
        step = next;
        wait = delay;
        ticks = 0;
    }

    private static void run(Minecraft mc) throws Exception {
        // Waits count down in real time terms, including while the world is standing still.
        if (wait > 0) {
            wait--;
            return;
        }
        ticks++;
        switch (step) {
            case TITLE -> {
                if (mc.screen instanceof TitleScreen && mc.getOverlay() == null) {
                    configure(mc);
                    join(mc);
                    go(Step.WORLD, 20);
                }
            }
            case WORLD -> {
                if (mc.level != null && mc.player != null && mc.screen == null) {
                    configure(mc);
                    prepareWorld(mc);
                    go(Step.SETTLE, 100);
                }
            }
            case SETTLE -> {
                hold(mc);
                // A pack's worldgen can take a while; everything measured later should start from a loaded world.
                if (RenderReadiness.terrainReady() || ticks > 2400) {
                    OUT.put("settleTicks", ticks);
                    Perf.begin("idle_no_recording");
                    Perf.startFlight();
                    go(Step.IDLE, 0);
                }
            }
            case IDLE -> {
                hold(mc);
                activity(mc);
                if (ticks >= 600) {
                    flight(mc, "idle");
                    Perf.begin("record_start");
                    Perf.startFlight();
                    Recorder.start();
                    Perf.begin("record_fly_out");
                    go(Step.FLY_OUT, 0);
                }
            }
            case FLY_OUT -> {
                moveTo(mc, startX + ticks * BLOCKS_PER_TICK);
                activity(mc);
                WorldCheck.maybeCapture(mc);
                if (ticks >= FLY_TICKS) {
                    Perf.begin("record_hover_far");
                    go(Step.HOVER_FAR, 0);
                }
            }
            case HOVER_FAR -> {
                moveTo(mc, startX + FLY_TICKS * BLOCKS_PER_TICK);
                activity(mc);
                WorldCheck.maybeCapture(mc);
                if (ticks >= HOVER_TICKS) {
                    Perf.begin("record_fly_back");
                    go(Step.FLY_BACK, 0);
                }
            }
            case FLY_BACK -> {
                moveTo(mc, startX + (FLY_TICKS - ticks) * BLOCKS_PER_TICK);
                activity(mc);
                WorldCheck.maybeCapture(mc);
                if (ticks >= FLY_TICKS) {
                    Perf.begin("record_hover_home");
                    go(Step.HOVER_HOME, 0);
                }
            }
            case HOVER_HOME -> {
                hold(mc);
                activity(mc);
                WorldCheck.maybeCapture(mc);
                if (ticks >= HOVER_TICKS) {
                    Perf.begin("record_stop");
                    go(Step.STOP, 0);
                }
            }
            case STOP -> {
                Recorder recorder = Recorder.active();
                OUT.put("recordedTicks", recorder.tick());
                replayDir = recorder.stop();
                flight(mc, "record");
                Perf.finish();
                go(Step.LEAVE, 5);
            }
            case LEAVE -> {
                mc.level.disconnect();
                mc.clearLevel(new GenericDirtMessageScreen(Component.literal("Leaving")));
                mc.setScreen(new TitleScreen());
                go(Step.OPEN, 40);
            }
            case OPEN -> {
                Perf.begin("replay_open");
                Perf.startFlight();
                mc.tell(() -> {
                    try {
                        ReplayPlayer.open(replayDir);
                    } catch (Throwable t) {
                        FlashbackReforged.LOGGER.error("Could not open replay", t);
                        OUT.put("openFailed", t.toString());
                        finish(mc);
                    }
                });
                go(Step.OPENED, 2);
            }
            case OPENED -> {
                if (ReplayPlayer.active() == null || mc.level == null || mc.player == null) return;
                if (RenderReadiness.terrainReady() || ticks > 1200) {
                    OUT.put("openToTerrainReadyTicks", ticks);
                    flight(mc, "open");
                    Perf.begin("play_with_editor");
                    Perf.startFlight();
                    ReplayPlayer.active().setPaused(false);
                    go(Step.PLAY_EDITOR, 0);
                }
            }
            case PLAY_EDITOR -> {
                if (ticks >= 400) {
                    EditorUi.get().toggleHidden();
                    flight(mc, "play_editor");
                    Perf.begin("play_editor_hidden");
                    Perf.startFlight();
                    go(Step.PLAY_HIDDEN, 0);
                }
            }
            case PLAY_HIDDEN -> {
                if (ticks >= 400) {
                    EditorUi.get().toggleHidden();
                    ReplayPlayer.active().setPaused(true);
                    flight(mc, "play_hidden");
                    Perf.begin("seeks");
                    Perf.startFlight();
                    seekIndex = 0;
                    go(Step.SEEKS, 0);
                }
            }
            case SEEKS -> {
                ReplayPlayer replay = ReplayPlayer.active();
                int total = replay.totalTicks();
                // Short and long hops in both directions, some crossing into other segments.
                int[] targets = {total / 2 + 200, total / 2 + 800, total / 2 + 100, total - 20, 20, total / 4, total / 4 + 20, total / 4 + 10, total / 2, 10};
                if (seekIndex >= targets.length) {
                    OUT.put("seeks", SEEKS);
                    flight(mc, "seeks");
                    Perf.begin("world_check");
                    go(Step.WORLD_CHECK, 0);
                    return;
                }
                replay.seek(targets[seekIndex]);
                Map<String, Object> entry = new LinkedHashMap<>();
                entry.put("from", replay.tick());
                entry.put("to", targets[seekIndex]);
                SEEKS.add(entry);
                seekStart = System.nanoTime();
                seekIndex++;
                go(Step.SEEK_WAIT, 0);
            }
            case WORLD_CHECK -> {
                ReplayPlayer replay = ReplayPlayer.active();
                if (replay.seekPending()) return;
                // Each landing is compared with what the world was when recording, then it's on to the next.
                if (checkWait > 0) {
                    checkWait--;
                    return;
                }
                if (WorldCheck.verifyPending(mc, replay)) {
                    checkWait = 3;
                    return;
                }
                OUT.put("worldCheck", WorldCheck.results());
                {
                    Perf.begin("export_video");
                    Perf.startFlight();
                    ExportSettings video = new ExportSettings();
                    video.name = "perf";
                    video.width = 1920;
                    video.height = 1080;
                    video.fps = 60;
                    video.startTick = 100;
                    video.endTick = 300;
                    video.bitrateKbps = 20000;
                    video.warmupFrames = 10;
                    video.output = ReplayStorage.exports().resolve("perf.mp4");
                    Files.deleteIfExists(video.output);
                    OUT.put("exportFrames", (video.endTick - video.startTick) * 3);
                    ExportManager.enqueue(video);
                    go(Step.WAIT_VIDEO, 5);
                    return;
                }
            }
            case SEEK_WAIT -> {
                ReplayPlayer replay = ReplayPlayer.active();
                if (replay.seekPending()) return;
                Map<String, Object> entry = SEEKS.get(SEEKS.size() - 1);
                if (!entry.containsKey("worldMs")) entry.put("worldMs", (System.nanoTime() - seekStart) / 1_000_000);
                if (RenderReadiness.terrainReady() || ticks > 1200) {
                    entry.put("terrainReadyMs", (System.nanoTime() - seekStart) / 1_000_000);
                    go(Step.SEEKS, 20);
                }
            }
            case WAIT_VIDEO -> {
                if (ExportManager.isExporting() || !ExportManager.queue().isEmpty()) return;
                flight(mc, "export");
                Perf.finish();
                Path file = ReplayStorage.exports().resolve("perf.mp4");
                OUT.put("exportError", ExportManager.lastError());
                OUT.put("exportBytes", Files.exists(file) ? Files.size(file) : 0);
                go(Step.EXPORT_PNG, 40);
            }
            case EXPORT_PNG -> {
                Perf.begin("export_png");
                ExportSettings png = new ExportSettings();
                png.name = "perf_png";
                png.width = 1920;
                png.height = 1080;
                png.fps = 20;
                png.startTick = 100;
                png.endTick = 160;
                png.warmupFrames = 5;
                png.audio = false;
                png.format = ExportSettings.Format.PNG_SEQUENCE;
                png.output = ReplayStorage.exports().resolve("perf_png");
                if (Files.exists(png.output)) ReplayStorage.delete(png.output);
                ExportManager.enqueue(png);
                go(Step.WAIT_PNG, 5);
            }
            case WAIT_PNG -> {
                if (ExportManager.isExporting() || !ExportManager.queue().isEmpty()) return;
                Perf.finish();
                OUT.put("packetFailures", ReplayPlayer.active().packetFailures());
                OUT.put("packetsApplied", ReplayPlayer.active().packetsApplied());
                ReplayPlayer.active().exit();
                Perf.begin("browser");
                go(Step.BROWSER, 20);
            }
            case BROWSER -> {
                mc.setScreen(new ReplayBrowserScreen(new TitleScreen()));
                go(Step.FINISH, 100);
            }
            case FINISH -> finish(mc);
            default -> {
            }
        }
    }

    private static void flight(Minecraft mc, String name) {
        Perf.stopFlight(mc.gameDirectory.toPath().resolve("flashback_reforged-perf-" + name + ".jfr"));
    }

    /** Uncapped, so that time the mod costs shows up as lost frames rather than being hidden by a frame limit. */
    private static void configure(Minecraft mc) {
        mc.options.enableVsync().set(false);
        mc.getWindow().updateVsync(false);
        mc.options.framerateLimit().set(260);
        mc.options.pauseOnLostFocus = false;
        mc.options.renderDistance().set(RENDER_DISTANCE);
        mc.options.simulationDistance().set(Math.min(RENDER_DISTANCE, 12));
        mc.getWindow().setWindowed(1600, 900);
    }

    private static void join(Minecraft mc) throws Exception {
        LevelSettings settings = new LevelSettings(WORLD, GameType.CREATIVE, false, Difficulty.EASY, true, new GameRules(), WorldDataConfiguration.DEFAULT);
        WorldOptions options = new WorldOptions(1234L, true, false);
        mc.createWorldOpenFlows().createFreshLevel(WORLD, settings, options, access -> access.registryOrThrow(Registries.WORLD_PRESET).getHolderOrThrow(PRESET).value().createWorldDimensions());
    }

    private static void prepareWorld(Minecraft mc) {
        startX = mc.player.getX();
        startY = Math.max(mc.player.getY() + 20, 110);
        startZ = mc.player.getZ();
        mc.player.getAbilities().flying = true;
        server(mc, "time set 1000");
        server(mc, "weather clear");
        server(mc, "gamerule doDaylightCycle false");
        // Mobs that move, so entity traffic is part of the recording. They stay around the starting point.
        for (int i = 0; i < 30; i++) {
            int dx = (i % 6) * 4 - 10, dz = (i / 6) * 4 - 10;
            server(mc, "summon minecraft:pig " + (startX + dx) + " " + (startY - 3) + " " + (startZ + dz) + " {NoGravity:1b}");
        }
    }

    private static void hold(Minecraft mc) {
        moveTo(mc, startX);
    }

    private static void moveTo(Minecraft mc, double x) {
        if (mc.player == null) return;
        mc.player.getAbilities().flying = true;
        mc.player.setDeltaMovement(0, 0, 0);
        mc.player.setPos(x, startY, startZ);
        mc.player.setYRot(90f);
        mc.player.setXRot(20f);
    }

    /** Block changes every second and particles a few times a second, near the player wherever they are. */
    private static void activity(Minecraft mc) {
        if (mc.player == null) return;
        int x = (int) mc.player.getX(), y = (int) mc.player.getY() - 6, z = (int) mc.player.getZ();
        if (ticks % 20 == 0) {
            toggle = !toggle;
            server(mc, "fill " + (x - 2) + " " + y + " " + (z - 2) + " " + (x + 2) + " " + y + " " + (z + 2) + " " + (toggle ? "minecraft:stone" : "minecraft:air"));
        }
        if (ticks % 5 == 0) server(mc, "particle minecraft:flame " + x + " " + y + " " + z + " 2 2 2 0 20 force");
        if (ticks % 40 == 0) mc.player.swing(net.minecraft.world.InteractionHand.MAIN_HAND);
    }

    private static void server(Minecraft mc, String command) {
        if (mc.getSingleplayerServer() == null || mc.player == null) return;
        var server = mc.getSingleplayerServer();
        var player = server.getPlayerList().getPlayer(mc.player.getUUID());
        if (player == null) return;
        server.execute(() -> server.getCommands().performPrefixedCommand(player.createCommandSourceStack().withPermission(4).withSuppressedOutput(), command));
    }

    private static void finish(Minecraft mc) {
        step = Step.DONE;
        com.flurben.flashbackreforged.record.Recorder.awaitWrites();
        Perf.finish();
        Perf.on = false;
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("version", net.minecraftforge.fml.ModList.get().getModFileById(FlashbackReforged.MOD_ID).versionString());
        result.put("renderDistance", RENDER_DISTANCE);
        result.put("modCount", net.minecraftforge.fml.ModList.get().size());
        result.put("heapMaxMb", Runtime.getRuntime().maxMemory() >> 20);
        result.putAll(OUT);
        result.put("phases", Perf.phases());
        try {
            if (replayDir != null) {
                String dump = System.getProperty("flashback_reforged.perf.dump");
                result.put("replay", ReplayAnalyzer.analyze(replayDir, dump == null ? null : Path.of(dump)));
            }
        } catch (Throwable t) {
            FlashbackReforged.LOGGER.error("Could not analyze the replay", t);
            result.put("analyzeError", t.toString());
        }
        try {
            Files.writeString(mc.gameDirectory.toPath().resolve("flashback_reforged-perf.json"), new GsonBuilder().setPrettyPrinting().create().toJson(result));
        } catch (Exception e) {
            FlashbackReforged.LOGGER.error("Could not write profiling results", e);
        }
        FlashbackReforged.LOGGER.info("[perf] finished");
        if (ReplayPlayer.active() != null) ReplayPlayer.active().exit();
        mc.stop();
    }
}
