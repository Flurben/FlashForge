package com.flurben.flashbackreforged.replay;

/**
 * One record in a segment stream. Every entry is length-delimited, so one payload that can't be decoded
 * (mod removed, bad modded codec) is skipped by the reader without corrupting anything after it.
 */
public enum EntryType {
    /** varint protocol id + the packet's wire bytes. */
    PACKET,
    /** The boundary between two game ticks. */
    TICK,
    /** Everything before this entry is the segment's state snapshot, applied instantly. */
    SNAPSHOT_END,
    /** Exact pose of the recording player for this tick. See {@link LocalPose}. */
    LOCAL_POSE,
    /** HUD-relevant state of the recording player (hotbar, health, food, xp). See {@link LocalHud}. */
    LOCAL_HUD,
    /** A block of PCM voice from Simple Voice Chat. */
    VOICE,
    /**
     * A packet held in the replay's blob file, so identical ones are stored once: varlong offset, then chunk x and z as
     * big-endian ints if the packet is a chunk. A chunk may then have an int count and a long hash, for that many entries
     * straight after this one which bring the stored chunk up to date.
     */
    BLOB_REF;

    private static final EntryType[] VALUES = values();

    public static EntryType byId(int id) {
        return id >= 0 && id < VALUES.length ? VALUES[id] : null;
    }
}
