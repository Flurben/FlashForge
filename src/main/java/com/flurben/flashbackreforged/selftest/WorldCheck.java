package com.flurben.flashbackreforged.selftest;

import com.flurben.flashbackreforged.FlashbackReforged;
import com.flurben.flashbackreforged.playback.ReplayPlayer;
import com.flurben.flashbackreforged.record.Recorder;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.chunk.LevelChunk;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Proves that a seek lands on the world as it really was. While recording, this notes the blocks around the player and where
 * each entity is at a number of moments, and afterwards it seeks to each of those moments and compares. It's what would
 * catch a snapshot that's out of date, or a chunk wrongly kept.
 */
final class WorldCheck {
    private static final int RADIUS = 4;
    private static final int CAPTURE_INTERVAL_TICKS = Integer.getInteger("flashback_reforged.perf.checkInterval", 360);

    private record Capture(int tick, Map<Long, int[]> chunks, Map<Integer, double[]> entities) {}

    private static final List<Capture> CAPTURES = new ArrayList<>();
    private static final List<Map<String, Object>> RESULTS = new ArrayList<>();
    private static int next;
    private static boolean seeking;
    /** Backward and forward hops, and into other segments, in an order that makes each a different kind of hop. */
    private static List<Capture> order;

    private WorldCheck() {}

    /** Client thread, each tick while recording. */
    static void maybeCapture(Minecraft mc) {
        Recorder recorder = Recorder.active();
        // Halfway between the scripted block changes, which are every 20 ticks, so a packet in transit can't make it look off.
        if (CAPTURE_INTERVAL_TICKS <= 0 || recorder == null || recorder.tick() % CAPTURE_INTERVAL_TICKS != 10 || mc.level == null || mc.player == null) return;
        ChunkPos center = mc.player.chunkPosition();
        Map<Long, int[]> chunks = new HashMap<>();
        for (int dx = -RADIUS; dx <= RADIUS; dx++) {
            for (int dz = -RADIUS; dz <= RADIUS; dz++) {
                LevelChunk chunk = mc.level.getChunkSource().getChunk(center.x + dx, center.z + dz, false);
                if (chunk != null) chunks.put(ChunkPos.asLong(center.x + dx, center.z + dz), blocks(mc, chunk));
            }
        }
        Map<Integer, double[]> entities = new HashMap<>();
        for (Entity entity : mc.level.entitiesForRendering()) {
            if (entity != mc.player) entities.put(entity.getId(), new double[]{entity.getX(), entity.getY(), entity.getZ()});
        }
        CAPTURES.add(new Capture(recorder.tick(), chunks, entities));
    }

    private static int[] blocks(Minecraft mc, LevelChunk chunk) {
        int min = mc.level.getMinBuildHeight(), height = mc.level.getHeight();
        int[] out = new int[16 * 16 * height];
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        int at = 0;
        int baseX = chunk.getPos().getMinBlockX(), baseZ = chunk.getPos().getMinBlockZ();
        for (int y = 0; y < height; y++) {
            for (int z = 0; z < 16; z++) {
                for (int x = 0; x < 16; x++) {
                    out[at++] = Block.getId(chunk.getBlockState(pos.set(baseX + x, min + y, baseZ + z)));
                }
            }
        }
        return out;
    }

    /** @return true while there's more to do. Called repeatedly, it seeks to one captured moment, then checks it. */
    static boolean verifyPending(Minecraft mc, ReplayPlayer replay) {
        if (order == null) {
            order = new ArrayList<>();
            // Latest first, so it starts with long hops backward, then the same moments again going forward.
            for (int i = CAPTURES.size() - 1; i >= 0; i--) order.add(CAPTURES.get(i));
            order.addAll(CAPTURES);
        }
        if (seeking) {
            seeking = false;
            compare(mc, replay, order.get(next++));
            return next < order.size();
        }
        if (next >= order.size()) return false;
        replay.seek(order.get(next).tick);
        seeking = true;
        return true;
    }

    private static void compare(Minecraft mc, ReplayPlayer replay, Capture capture) {
        long different = 0, checked = 0;
        int missing = 0;
        for (var e : capture.chunks.entrySet()) {
            LevelChunk chunk = mc.level.getChunkSource().getChunk(ChunkPos.getX(e.getKey()), ChunkPos.getZ(e.getKey()), false);
            if (chunk == null) {
                missing++;
                continue;
            }
            int[] now = blocks(mc, chunk);
            int[] then = e.getValue();
            for (int i = 0; i < then.length; i++) {
                checked++;
                if (now[i] != then[i]) different++;
            }
        }
        int entitiesMissing = 0, entitiesMoved = 0;
        for (var e : capture.entities.entrySet()) {
            Entity entity = mc.level.getEntity(e.getKey());
            if (entity == null) {
                entitiesMissing++;
                continue;
            }
            double[] p = e.getValue();
            if (entity.distanceToSqr(p[0], p[1], p[2]) > 4) entitiesMoved++;
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("tick", capture.tick);
        result.put("landedOn", replay.tick());
        result.put("blocksChecked", checked);
        result.put("blocksDifferent", different);
        result.put("chunksMissing", missing);
        result.put("entities", capture.entities.size());
        result.put("entitiesMissing", entitiesMissing);
        result.put("entitiesMoved", entitiesMoved);
        RESULTS.add(result);
        FlashbackReforged.LOGGER.info("[perf] world check {}", result);
    }

    static List<Map<String, Object>> results() {
        return RESULTS;
    }
}
