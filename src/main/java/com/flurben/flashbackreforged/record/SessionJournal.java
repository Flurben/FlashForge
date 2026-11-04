package com.flurben.flashbackreforged.record;

import com.flurben.flashbackreforged.FlashbackReforged;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.*;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Everything a recording that starts mid-session can't reconstruct from client state: session-defining
 * packets (login, tags, feature flags) and modded custom payloads, since mods keep private client state
 * (TFC chunk climate data, GregTech machine sync, ...) that only ever arrives through their own channels.
 * <p>
 * Runs from login whether or not anything is recording, and every payload here is replayed by every seek, so it has to stay
 * small. A modded pack sends thousands of these a minute, most of them the same few values again or periodic updates:
 * <ul>
 * <li>An exact repeat replaces the earlier copy, since a later value is what state should end up as.</li>
 * <li>Each kind of payload (same channel, type byte and length) keeps its newest {@link #MAX_PER_KIND}. That's far more than
 * a player has chunks loaded, and leaves one-time setup payloads alone, however long the session runs.</li>
 * <li>A cap on total size is the last resort.</li>
 * </ul>
 */
public final class SessionJournal {
    private static final long MAX_PAYLOAD_BYTES = 128L * 1024 * 1024;
    private static final int MAX_PER_KIND = 4096;

    private ClientboundLoginPacket login;
    private ClientboundUpdateTagsPacket tags;
    private ClientboundUpdateEnabledFeaturesPacket features;
    private ClientboundChangeDifficultyPacket difficulty;
    private ClientboundSetChunkCacheRadiusPacket chunkRadius;
    private ClientboundSetSimulationDistancePacket simulationDistance;
    private ClientboundSetChunkCacheCenterPacket chunkCenter;
    private final Map<Integer, ClientboundMapItemDataPacket> maps = new HashMap<>();
    private final LinkedHashMap<Key, Stored> payloads = new LinkedHashMap<>();
    private final Map<Long, Kind> kinds = new HashMap<>();
    private long payloadBytes;
    private boolean warnedOverflow;

    private record Key(byte[] bytes, int hash) {
        static Key of(byte[] bytes) {
            return new Key(bytes, Arrays.hashCode(bytes));
        }

        @Override
        public int hashCode() {
            return this.hash;
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof Key key && key.hash == this.hash && Arrays.equals(key.bytes, this.bytes);
        }
    }

    private static final class Stored {
        final Key key;
        final Kind kind;
        boolean gone;

        Stored(Key key, Kind kind) {
            this.key = key;
            this.kind = kind;
        }
    }

    private static final class Kind {
        final ArrayDeque<Stored> order = new ArrayDeque<>();
        int live;
    }

    /** @return the packet's encoded form if making it was necessary here, so it needn't be encoded again for a recording. */
    public synchronized byte[] observe(Packet<?> p) {
        if (p instanceof ClientboundCustomPayloadPacket) return recordPayload(p);
        if (p instanceof ClientboundLoginPacket q) {
            login = q;
            payloads.clear();
            kinds.clear();
            payloadBytes = 0;
            maps.clear();
        } else if (p instanceof ClientboundUpdateTagsPacket q) tags = q;
        else if (p instanceof ClientboundUpdateEnabledFeaturesPacket q) features = q;
        else if (p instanceof ClientboundChangeDifficultyPacket q) difficulty = q;
        else if (p instanceof ClientboundSetChunkCacheRadiusPacket q) chunkRadius = q;
        else if (p instanceof ClientboundSetSimulationDistancePacket q) simulationDistance = q;
        else if (p instanceof ClientboundSetChunkCacheCenterPacket q) chunkCenter = q;
        else if (p instanceof ClientboundMapItemDataPacket q) maps.put(q.getMapId(), q);
        return null;
    }

    private byte[] recordPayload(Packet<?> p) {
        byte[] bytes;
        try {
            bytes = PacketCodec.encode(p);
        } catch (Exception e) {
            FlashbackReforged.LOGGER.debug("Could not journal custom payload", e);
            return null;
        }
        if (bytes == null) return null;

        Key key = Key.of(bytes);
        Stored earlier = payloads.remove(key);
        if (earlier != null) {
            earlier.gone = true;
            earlier.kind.live--;
            payloadBytes -= bytes.length;
        }
        Kind kind = kinds.computeIfAbsent(kindOf(bytes), k -> new Kind());
        Stored stored = new Stored(key, kind);
        payloads.put(key, stored);
        kind.order.addLast(stored);
        kind.live++;
        payloadBytes += bytes.length;
        trim(kind);
        return bytes;
    }

    private void trim(Kind kind) {
        while (kind.live > MAX_PER_KIND) {
            Stored oldest = kind.order.pollFirst();
            if (oldest != null && !oldest.gone) forget(oldest);
        }
        // Replaced entries are dropped from the queue in bulk, which keeps a repeat from costing a search.
        if (kind.order.size() > MAX_PER_KIND * 2) kind.order.removeIf(s -> s.gone);

        if (payloadBytes <= MAX_PAYLOAD_BYTES) return;
        if (!warnedOverflow) {
            warnedOverflow = true;
            FlashbackReforged.LOGGER.warn("Session journal exceeded {} MB; dropping the oldest modded payloads", MAX_PAYLOAD_BYTES >> 20);
        }
        var it = payloads.values().iterator();
        while (payloadBytes > MAX_PAYLOAD_BYTES && it.hasNext()) {
            Stored oldest = it.next();
            it.remove();
            oldest.gone = true;
            oldest.kind.live--;
            payloadBytes -= oldest.key.bytes.length;
        }
    }

    private void forget(Stored stored) {
        payloads.remove(stored.key);
        stored.gone = true;
        stored.kind.live--;
        payloadBytes -= stored.key.bytes.length;
    }

    /** Channel, the byte most mods use to say which message it is, and length. */
    private static long kindOf(byte[] bytes) {
        int at = 0;
        while (at < bytes.length && (bytes[at] & 0x80) != 0) at++;
        at++;
        int channelLength = 0, shift = 0;
        while (at < bytes.length) {
            byte b = bytes[at++];
            channelLength |= (b & 0x7F) << shift;
            shift += 7;
            if ((b & 0x80) == 0) break;
        }
        int end = Math.min(bytes.length, at + channelLength);
        int hash = 1;
        for (int i = at; i < end; i++) hash = 31 * hash + bytes[i];
        int first = end < bytes.length ? bytes[end] : -1;
        return ((long) hash << 32) | ((first & 0xFFL) << 24) | (bytes.length & 0xFFFFFFL);
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
        kinds.clear();
        payloadBytes = 0;
        warnedOverflow = false;
    }

    public synchronized ClientboundLoginPacket login() {
        return login;
    }

    public synchronized ClientboundUpdateTagsPacket tags() {
        return tags;
    }

    /** Session packets that must precede world state in a snapshot, apart from login and tags. */
    public synchronized List<Packet<?>> preamble() {
        List<Packet<?>> out = new ArrayList<>();
        if (features != null) out.add(features);
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
        List<byte[]> out = new ArrayList<>(payloads.size());
        for (Key key : payloads.keySet()) out.add(key.bytes);
        return out;
    }
}
