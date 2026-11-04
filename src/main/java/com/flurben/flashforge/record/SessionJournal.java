package com.flurben.flashforge.record;

import com.flurben.flashforge.FlashForge;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.*;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Everything a recording that starts mid-session can't reconstruct from client state: session-defining
 * packets (login, tags, feature flags) and modded custom payloads, since mods keep private client state
 * (TFC chunk climate data, GregTech machine sync, ...) that only ever arrives through their own channels.
 * <p>
 * Runs from login whether or not anything is recording, so it is memory-bounded: the oldest payloads are
 * dropped first, which only matters for very long sessions on chatty servers.
 */
public final class SessionJournal {
    private static final long MAX_PAYLOAD_BYTES = 128L * 1024 * 1024;

    private ClientboundLoginPacket login;
    private ClientboundUpdateTagsPacket tags;
    private ClientboundUpdateEnabledFeaturesPacket features;
    private ClientboundChangeDifficultyPacket difficulty;
    private ClientboundSetChunkCacheRadiusPacket chunkRadius;
    private ClientboundSetSimulationDistancePacket simulationDistance;
    private ClientboundSetChunkCacheCenterPacket chunkCenter;
    private final Map<Integer, ClientboundMapItemDataPacket> maps = new HashMap<>();
    private final ArrayDeque<byte[]> payloads = new ArrayDeque<>();
    private long payloadBytes;
    private boolean warnedOverflow;

    public synchronized void observe(Packet<?> p) {
        if (p instanceof ClientboundLoginPacket q) {
            login = q;
            payloads.clear();
            payloadBytes = 0;
            maps.clear();
        } else if (p instanceof ClientboundUpdateTagsPacket q) tags = q;
        else if (p instanceof ClientboundUpdateEnabledFeaturesPacket q) features = q;
        else if (p instanceof ClientboundChangeDifficultyPacket q) difficulty = q;
        else if (p instanceof ClientboundSetChunkCacheRadiusPacket q) chunkRadius = q;
        else if (p instanceof ClientboundSetSimulationDistancePacket q) simulationDistance = q;
        else if (p instanceof ClientboundSetChunkCacheCenterPacket q) chunkCenter = q;
        else if (p instanceof ClientboundMapItemDataPacket q) maps.put(q.getMapId(), q);
        else if (p instanceof ClientboundCustomPayloadPacket) recordPayload(p);
    }

    private void recordPayload(Packet<?> p) {
        try {
            byte[] bytes = PacketCodec.encode(p);
            if (bytes == null) return;
            payloads.addLast(bytes);
            payloadBytes += bytes.length;
            while (payloadBytes > MAX_PAYLOAD_BYTES && !payloads.isEmpty()) {
                payloadBytes -= payloads.removeFirst().length;
                if (!warnedOverflow) {
                    warnedOverflow = true;
                    FlashForge.LOGGER.warn("Session journal exceeded {} MB; dropping the oldest modded payloads", MAX_PAYLOAD_BYTES >> 20);
                }
            }
        } catch (Exception e) {
            FlashForge.LOGGER.debug("Could not journal custom payload", e);
        }
    }

    public synchronized void clear() {
        login = null;
        tags = null;
        features = null;
        difficulty = null;
        chunkRadius = null;
        simulationDistance = null;
        chunkCenter = null;
        maps.clear();
        payloads.clear();
        payloadBytes = 0;
        warnedOverflow = false;
    }

    public synchronized ClientboundLoginPacket login() {
        return login;
    }

    /** Session packets that must precede world state in a snapshot. */
    public synchronized List<Packet<?>> preamble() {
        List<Packet<?>> out = new ArrayList<>();
        if (features != null) out.add(features);
        if (tags != null) out.add(tags);
        if (difficulty != null) out.add(difficulty);
        if (chunkRadius != null) out.add(chunkRadius);
        if (simulationDistance != null) out.add(simulationDistance);
        if (chunkCenter != null) out.add(chunkCenter);
        return out;
    }

    public synchronized List<Packet<?>> maps() {
        return new ArrayList<>(maps.values());
    }

    /** Encoded custom payloads, oldest first. Applied after chunks, since many attach state to loaded chunks. */
    public synchronized List<byte[]> payloads() {
        return new ArrayList<>(payloads);
    }
}
