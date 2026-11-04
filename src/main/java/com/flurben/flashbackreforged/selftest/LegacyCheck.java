package com.flurben.flashbackreforged.selftest;

import com.flurben.flashbackreforged.FlashbackReforged;
import com.flurben.flashbackreforged.playback.ReplayPlayer;
import com.flurben.flashbackreforged.replay.ReplayStorage;
import com.google.gson.GsonBuilder;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.TitleScreen;

import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Opens replays that already exist, such as ones made by earlier versions or left behind by a crash, and plays and seeks
 * through each. Scenario {@code legacy}, with the folder names in {@code flashback_reforged.selftest.replays}.
 */
final class LegacyCheck {
    private enum Step { WAIT_TITLE, OPEN, PLAYING, SEEK_MIDDLE, SEEK_START, CLOSE, DONE }

    private static final List<String> NAMES = new ArrayList<>(List.of(System.getProperty("flashback_reforged.selftest.replays", "").split(",")));
    private static final List<Map<String, Object>> RESULTS = new ArrayList<>();
    private static Step step = Step.WAIT_TITLE;
    private static int wait;
    private static Map<String, Object> current;
    private static boolean opening;

    private LegacyCheck() {}

    static void tick(Minecraft mc) {
        if (wait > 0) {
            wait--;
            return;
        }
        ReplayPlayer replay = ReplayPlayer.active();
        switch (step) {
            case WAIT_TITLE -> {
                if (mc.screen instanceof TitleScreen && mc.getOverlay() == null) {
                    step = Step.OPEN;
                    wait = 20;
                }
            }
            case OPEN -> {
                if (NAMES.isEmpty()) {
                    finish(mc);
                    return;
                }
                String name = NAMES.remove(0);
                current = new LinkedHashMap<>();
                current.put("replay", name);
                RESULTS.add(current);
                opening = true;
                mc.tell(() -> {
                    opening = false;
                    try {
                        ReplayPlayer.open(ReplayStorage.root().resolve(name)).setPaused(false);
                        step = Step.PLAYING;
                    } catch (Throwable t) {
                        FlashbackReforged.LOGGER.error("Could not open {}", name, t);
                        current.put("openError", t.toString());
                        step = Step.OPEN;
                    }
                    wait = 60;
                });
                wait = 2;
            }
            case PLAYING -> {
                if (replay == null || mc.level == null) return;
                current.put("totalTicks", replay.totalTicks());
                current.put("tickAfterPlaying", replay.tick());
                current.put("chunksPlaying", mc.level.getChunkSource().getLoadedChunksCount());
                replay.setPaused(true);
                replay.seek(replay.totalTicks() / 2);
                step = Step.SEEK_MIDDLE;
                wait = 40;
            }
            case SEEK_MIDDLE -> {
                current.put("tickAtMiddle", replay.tick());
                current.put("chunksAtMiddle", mc.level.getChunkSource().getLoadedChunksCount());
                replay.seek(5);
                step = Step.SEEK_START;
                wait = 40;
            }
            case SEEK_START -> {
                current.put("tickAtStart", replay.tick());
                current.put("chunksAtStart", mc.level.getChunkSource().getLoadedChunksCount());
                current.put("recordedPlayerPresent", mc.level.getEntity(replay.recordedPlayerId()) != null);
                current.put("packetsApplied", replay.packetsApplied());
                current.put("packetFailures", replay.packetFailures());
                replay.exit();
                step = Step.CLOSE;
                wait = 40;
            }
            case CLOSE -> {
                step = Step.OPEN;
                wait = 20;
            }
            default -> {
            }
        }
    }

    private static void finish(Minecraft mc) {
        step = Step.DONE;
        try {
            Files.writeString(mc.gameDirectory.toPath().resolve("flashback_reforged-legacy.json"), new GsonBuilder().setPrettyPrinting().create().toJson(RESULTS));
        } catch (Exception e) {
            FlashbackReforged.LOGGER.error("Could not write results", e);
        }
        mc.stop();
    }
}
