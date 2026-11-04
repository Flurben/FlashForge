package com.flurben.flashbackreforged.record;

import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.network.protocol.game.ClientboundBlockUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundChunksBiomesPacket;
import net.minecraft.network.protocol.game.ClientboundExplodePacket;
import net.minecraft.network.protocol.game.ClientboundForgetLevelChunkPacket;
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;
import net.minecraft.network.protocol.game.ClientboundLightUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundLoginPacket;
import net.minecraft.network.protocol.game.ClientboundRespawnPacket;
import net.minecraft.network.protocol.game.ClientboundSectionBlocksUpdatePacket;
import net.minecraft.world.level.ChunkPos;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * What each loaded chunk is, in terms of what's already stored: one stored copy, plus the packets that have changed it since.
 * A snapshot can then point at that copy and repeat those few packets, rather than encode the chunk again. That saves the
 * time, and more than that, it saves the space: the game's own encoding of a chunk differs from what the server sent, and in
 * a live world random ticks touch a hundred chunks a minute, so otherwise those get stored whole, over and over, for the
 * sake of a leaf or two. It also means playback can tell it's already showing a chunk.
 */
final class ChunkVersions {
    /** A packet seen just before a chunk was encoded might not have reached the world yet. */
    private static final long SETTLE_NANOS = 1_000_000_000L;
    /**
     * A mod's block entities can change without any packet saying so here, so a chunk that has some gets looked at again
     * eventually. Not every snapshot, though: their data tends to differ a little each time, and storing whole chunks over
     * that was most of what an idle recording in a big pack wrote to disk. Mods' own packets carry the changes in between.
     */
    private static final long MODDED_MAX_AGE_NANOS = 300_000_000_000L;

    /** Past this much in changes, it's better to store the chunk afresh. */
    private static final int MAX_CHANGE_BYTES = 64 * 1024;
    private static final byte[][] NO_CHANGES = new byte[0][];

    /** One stored copy of a chunk. Its place in the blob file is filled in when the writer gets to it. */
    static final class Version {
        final long created = System.nanoTime();
        final List<byte[]> changes = new ArrayList<>(0);
        int changeBytes;
        /** Identifies the changes, so that two snapshots holding the same ones can be recognized as the same content. */
        long changeHash;
        volatile long offset = -1;
        /** Still waiting behind a snapshot, so a reference to it would reach the writer first. */
        volatile boolean parked;
    }

    /** A chunk as stored plus what has changed since. */
    record Usable(Version version, byte[][] changes, long changeHash) {}

    private final Long2ObjectOpenHashMap<Version> current = new Long2ObjectOpenHashMap<>();
    private final Long2LongOpenHashMap lastChange = new Long2LongOpenHashMap();

    /**
     * Netty thread, in arrival order.
     * @param version the stored copy, if this packet is a chunk
     * @param encoded the packet as written to the stream
     */
    synchronized void onPacket(Packet<?> packet, Version version, byte[] encoded) {
        if (packet instanceof ClientboundLevelChunkWithLightPacket p) {
            long pos = ChunkPos.asLong(p.getX(), p.getZ());
            this.current.put(pos, version);
            this.lastChange.remove(pos);
        } else if (packet instanceof ClientboundBlockUpdatePacket p) {
            changed(ChunkPos.asLong(p.getPos()), encoded);
        } else if (packet instanceof ClientboundSectionBlocksUpdatePacket p) {
            // All of one section, so any of its positions says which chunk.
            long[] chunk = {Long.MAX_VALUE};
            p.runUpdates((pos, state) -> chunk[0] = ChunkPos.asLong(pos));
            if (chunk[0] != Long.MAX_VALUE) changed(chunk[0], encoded);
        } else if (packet instanceof ClientboundBlockEntityDataPacket p) {
            changed(ChunkPos.asLong(p.getPos()), encoded);
        } else if (packet instanceof ClientboundLightUpdatePacket p) {
            changed(ChunkPos.asLong(p.getX(), p.getZ()), encoded);
        } else if (packet instanceof ClientboundForgetLevelChunkPacket p) {
            long pos = ChunkPos.asLong(p.getX(), p.getZ());
            this.current.remove(pos);
            this.lastChange.put(pos, System.nanoTime());
        } else if (packet instanceof ClientboundExplodePacket || packet instanceof ClientboundChunksBiomesPacket
                || packet instanceof ClientboundRespawnPacket || packet instanceof ClientboundLoginPacket) {
            clear();
        }
    }

    private void changed(long pos, byte[] encoded) {
        this.lastChange.put(pos, System.nanoTime());
        Version version = this.current.get(pos);
        if (version == null) return;
        if (encoded == null || version.changeBytes + encoded.length > MAX_CHANGE_BYTES) {
            this.current.remove(pos);
            return;
        }
        version.changes.add(encoded);
        version.changeBytes += encoded.length;
        version.changeHash = version.changeHash * 0x9E3779B97F4A7C15L + Arrays.hashCode(encoded) + 1;
    }

    /** Everything is unknown again: packets were missed, or the world was replaced. */
    synchronized void clear() {
        this.current.clear();
        this.lastChange.clear();
    }

    /**
     * @param modded whether this chunk has block entities from a mod
     * @return the stored copy that's still accurate, if there's one that can be pointed to right now.
     */
    synchronized Usable usable(long pos, boolean modded) {
        Version version = this.current.get(pos);
        if (version == null || version.parked) return null;
        if (modded && System.nanoTime() - version.created > MODDED_MAX_AGE_NANOS) return null;
        return new Usable(version, version.changes.isEmpty() ? NO_CHANGES : version.changes.toArray(NO_CHANGES), version.changeHash);
    }

    /** Client thread, having just encoded this chunk. */
    synchronized void encoded(long pos, Version version) {
        // Anything that changed it a moment ago may still be waiting to be applied, in which case this copy is already old.
        if (this.lastChange.containsKey(pos) && System.nanoTime() - this.lastChange.get(pos) < SETTLE_NANOS) return;
        this.current.put(pos, version);
        this.lastChange.remove(pos);
    }
}
