package com.flurben.flashbackreforged.record;

import com.flurben.flashbackreforged.FlashbackReforged;
import com.flurben.flashbackreforged.compat.DistantHorizonsCompat;
import com.flurben.flashbackreforged.mixin.accessor.BlockableEventLoopInvoker;
import com.flurben.flashbackreforged.perf.Perf;
import com.flurben.flashbackreforged.replay.EntryType;
import com.flurben.flashbackreforged.replay.LocalHud;
import com.flurben.flashbackreforged.replay.LocalPose;
import com.flurben.flashbackreforged.replay.ReplayMeta;
import com.flurben.flashbackreforged.replay.ReplayStorage;
import com.flurben.flashbackreforged.replay.ServerConfigStore;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import net.minecraft.SharedConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundBundlePacket;
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.registries.ForgeRegistry;
import net.minecraftforge.registries.RegistryManager;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * An active recording. Packets arrive on the netty thread and are encoded there, in arrival order, so the
 * stream matches what the client saw. Ticks, snapshots and synthesized local-player data come from the client
 * thread; {@link #lock} merges the two. All disk work happens on the {@link ReplayWriter} thread.
 * <p>
 * Each segment starts with a full state snapshot, which is what makes seeking possible. A snapshot's chunks are encoded a
 * few at a time over several frames so the game doesn't hitch, while everything else in it is taken at one instant.
 * Whatever happens in the meantime is parked in {@link #parked} and lands after the snapshot. Replaying those changes over
 * a chunk that already includes them is harmless, because block, light and chunk packets state absolute values. Entities
 * use relative moves, which is why they can't be spread out the same way.
 */
public final class Recorder {
    private static final int META_SAVE_TICKS = 20 * 30;
    private static final long MAX_PACKET_WARNINGS = 20;
    private static final byte[] EMPTY = new byte[0];
    /** How long a frame can spend on a snapshot, and how much more for each tick it has been going on. */
    private static final long SLICE_NANOS = 2_000_000L;
    private static final long SLICE_GROWTH_PER_TICK_NANOS = 100_000L;
    /** Parked data is in memory, so past this the snapshot stops being polite and finishes. */
    private static final long MAX_PARKED_BYTES = 128L << 20;

    private static volatile Recorder active;
    private static final SessionJournal JOURNAL = new SessionJournal();
    /** Recordings still being written after they stopped. */
    private static final Set<ReplayWriter> CLOSING = ConcurrentHashMap.newKeySet();

    static {
        // The writer is on a daemon thread, so quitting right after stopping must still let it finish.
        Runtime.getRuntime().addShutdownHook(new Thread(Recorder::awaitWrites, "Flashback Reforged Shutdown"));
    }

    private record Parked(EntryType type, byte[] bytes, ChunkVersions.Version version) {}

    private final Object lock = new Object();
    private final Path dir;
    private final ReplayMeta meta = new ReplayMeta();
    private final List<Parked> parked = new ArrayList<>();
    private final LocalPlayerTracker tracker = new LocalPlayerTracker(this);
    private final ReplayWriter writer;
    private final ChunkVersions chunkVersions = new ChunkVersions();
    private long parkedBytes;
    private ReplayMeta.Segment segment;
    private SnapshotBuilder.Task capture;
    private int captureStartTick;
    private boolean parking;
    private volatile boolean paused;
    private volatile boolean stopped;
    private int tick;
    private int ticksSinceMetaSave;
    private long packetFailures;

    private Recorder(Path dir, String name) throws IOException {
        this.dir = dir;
        this.meta.name = name;
        this.writer = new ReplayWriter(dir);
    }

    public static Recorder active() {
        return active;
    }

    public static SessionJournal journal() {
        return JOURNAL;
    }

    /** Waits until every recording that has stopped is completely on disk. */
    public static void awaitWrites() {
        for (ReplayWriter closing : CLOSING) closing.await(60_000);
    }

    /** Called from the netty thread for each clientbound play packet. */
    public static void onIncoming(Packet<?> packet) {
        long start = Perf.start();
        byte[] encoded = JOURNAL.observe(packet);
        Perf.end("netty.journal", start);
        Recorder recorder = active;
        if (recorder != null) recorder.onPacket(packet, encoded);
    }

    private static int segmentTicks() {
        return Integer.getInteger("flashback_reforged.segmentTicks", 20 * 60);
    }

    /** Must be called on the client thread with a world loaded. */
    public static Recorder start() throws IOException {
        if (active != null) throw new IllegalStateException("Already recording");
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) throw new IllegalStateException("Not in a world");
        if (JOURNAL.login() == null) throw new IllegalStateException("No login packet was observed for this session");

        String name = new SimpleDateFormat("yyyy-MM-dd_HH-mm-ss").format(new Date());
        Path dir = ReplayStorage.root().resolve(ReplayStorage.safeName(name));
        Files.createDirectories(dir);

        Recorder r = new Recorder(dir, name);
        r.meta.recordedBy = mc.getUser().getName();
        r.meta.world = mc.getSingleplayerServer() != null ? mc.getSingleplayerServer().getWorldData().getLevelName()
                : mc.getCurrentServer() != null ? mc.getCurrentServer().name : "";
        r.meta.minecraftVersion = SharedConstants.getCurrentVersion().getName();
        r.meta.protocol = SharedConstants.getCurrentVersion().getProtocolVersion();
        ModList.get().getMods().forEach(m -> r.meta.mods.put(m.getModId(), m.getVersion().toString()));
        DistantHorizonsCompat.collectPaths(r.meta.distantHorizons);
        r.writer.run(r::saveRegistriesAndConfigs);
        r.beginSegment();
        active = r;
        // Apply packets the netty thread has already handed over, or they'd be in neither the snapshot nor the stream.
        ((BlockableEventLoopInvoker) (Object) mc).flashbackreforged$runAllTasks();
        r.beginSnapshot(true);
        FlashbackReforged.LOGGER.info("Recording started: {}", dir);
        return r;
    }

    /**
     * Numeric ids in packets (blocks, items, entity types, data serializers) are only meaningful together with the
     * id map negotiated with the server. Forge assigns them per world, so a viewer's own map can disagree: mobs turn
     * into other mobs and textures shift. The map is stored here and re-applied when the replay is opened.
     */
    private void saveRegistriesAndConfigs() throws IOException {
        long start = Perf.start();
        Map<ResourceLocation, ForgeRegistry.Snapshot> snapshot = RegistryManager.ACTIVE.takeSnapshot(false);
        CompoundTag root = new CompoundTag();
        snapshot.forEach((key, value) -> root.put(key.toString(), value.write()));
        NbtIo.writeCompressed(root, this.dir.resolve("registries.nbt").toFile());
        ServerConfigStore.save(this.dir);
        Perf.end("writer.saveRegistriesAndConfig", start);
    }

    public Path dir() {
        return this.dir;
    }

    public ReplayMeta meta() {
        return this.meta;
    }

    public int tick() {
        return this.tick;
    }

    public boolean paused() {
        return this.paused;
    }

    public boolean failed() {
        return this.writer.failed();
    }

    /** @param encoded the packet's encoded form, if whoever called this already had to make one. */
    private void onPacket(Packet<?> packet, byte[] encoded) {
        if (this.paused || this.stopped) return;
        if (packet instanceof ClientboundBundlePacket bundle) {
            for (Packet<?> inner : bundle.subPackets()) onPacket(inner, null);
            return;
        }
        if (PacketPolicy.dropAtRecord(packet)) return;
        long start = Perf.start();
        byte[] bytes = encoded;
        if (bytes == null) {
            try {
                bytes = PacketCodec.encode(packet);
            } catch (Throwable t) {
                // One mod's broken encoder must not end the recording.
                if (this.packetFailures++ < MAX_PACKET_WARNINGS) {
                    FlashbackReforged.LOGGER.warn("Skipping packet {} that failed to encode", packet.getClass().getName(), t);
                }
                return;
            }
        }
        Perf.end("netty.encode", start);
        if (bytes == null) return;
        ChunkVersions.Version version = packet instanceof ClientboundLevelChunkWithLightPacket ? new ChunkVersions.Version() : null;
        // Under the lock, so what this says about a chunk matches the order things reach the stream in.
        synchronized (this.lock) {
            this.chunkVersions.onPacket(packet, version, bytes);
            emit(EntryType.PACKET, bytes, version);
        }
    }

    /** Adds to the stream in order, which means after the snapshot if one is being built. */
    private void emit(EntryType type, byte[] bytes) {
        emit(type, bytes, null);
    }

    private void emit(EntryType type, byte[] bytes, ChunkVersions.Version version) {
        synchronized (this.lock) {
            if (this.stopped) return;
            if (this.parking) {
                if (version != null) version.parked = true;
                this.parked.add(new Parked(type, bytes, version));
                this.parkedBytes += bytes.length;
            } else if (version != null) {
                this.writer.chunk(bytes, version);
            } else {
                this.writer.entry(type, bytes);
            }
        }
    }

    /** Client thread: a packet synthesized on behalf of the local player. */
    public void writeSynthetic(Packet<?> packet) {
        if (this.paused) return;
        try {
            byte[] bytes = PacketCodec.encode(packet);
            if (bytes != null) emit(EntryType.PACKET, bytes);
        } catch (Throwable t) {
            FlashbackReforged.LOGGER.debug("Could not write synthetic packet {}", packet.getClass().getName(), t);
        }
    }

    void writeEncoded(byte[] bytes) {
        emit(EntryType.PACKET, bytes);
    }

    void writePose(LocalPose pose) {
        write(EntryType.LOCAL_POSE, pose::write);
    }

    void writeHud(LocalHud hud) {
        write(EntryType.LOCAL_HUD, hud::write);
    }

    public void writeVoice(Consumer<FriendlyByteBuf> body) {
        if (this.paused) return;
        this.meta.hasVoice = true;
        write(EntryType.VOICE, body);
    }

    private void write(EntryType type, Consumer<FriendlyByteBuf> body) {
        ByteBuf raw = Unpooled.buffer(64);
        try {
            body.accept(new FriendlyByteBuf(raw));
            byte[] bytes = new byte[raw.readableBytes()];
            raw.readBytes(bytes);
            emit(type, bytes);
        } finally {
            raw.release();
        }
    }

    /** Client thread, at the end of every client tick. */
    public void endTick() {
        if (this.paused) return;
        long tickStart = Perf.start();
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (player != null && mc.level != null) this.tracker.tick(player);
        Perf.end("record.tracker", tickStart);

        emit(EntryType.TICK, EMPTY);
        this.segment.ticks++;
        this.tick++;
        this.meta.totalTicks = this.tick;

        // No HUD means no chance to catch the frame before the HUD draws, but also nothing to keep out of the picture.
        if (this.tick >= THUMBNAIL_FALLBACK_TICK && this.nextThumbnailTick == THUMBNAIL_TICK && mc.screen == null) {
            this.nextThumbnailTick = Integer.MAX_VALUE;
            saveThumbnail();
        }

        Perf.end("record.endTickBeforeRoll", tickStart);
        if (this.segment.ticks >= segmentTicks() && this.capture == null) {
            long rollStart = Perf.start();
            rollSegment();
            Perf.end("record.roll", rollStart);
        }
        if (++this.ticksSinceMetaSave >= META_SAVE_TICKS) {
            this.ticksSinceMetaSave = 0;
            long metaStart = Perf.start();
            saveMeta();
            Perf.end("record.saveMeta", metaStart);
        }
    }

    /** Render thread, at the start of each frame: a little more of the snapshot, if one is in progress. */
    public void frame() {
        if (this.capture == null) return;
        long start = Perf.start();
        boolean behind;
        synchronized (this.lock) {
            behind = this.parkedBytes > MAX_PARKED_BYTES;
        }
        long budget = behind ? Long.MAX_VALUE / 2 : SLICE_NANOS + (this.tick - this.captureStartTick) * SLICE_GROWTH_PER_TICK_NANOS;
        if (this.capture.step(budget)) finishSnapshot();
        Perf.end("record.snapshotSlice", start);
    }

    private static final int THUMBNAIL_EARLY_TICK = 20;
    private static final int THUMBNAIL_TICK = 100;
    private static final int THUMBNAIL_FALLBACK_TICK = 140;
    private int nextThumbnailTick = THUMBNAIL_EARLY_TICK;
    private boolean thumbnailSaved;
    private Thumbnails.Capture thumbnail;

    private void rollSegment() {
        synchronized (this.lock) {
            this.parking = true;
        }
        // Apply packets the netty thread has already handed over, or they'd be in the old segment but not in the snapshot.
        ((BlockableEventLoopInvoker) (Object) Minecraft.getInstance()).flashbackreforged$runAllTasks();
        beginSegment();
        beginSnapshot(false);
        saveMeta();
    }

    private void beginSegment() {
        String file = "seg" + String.format("%04d", this.meta.segments.size()) + ".gz";
        synchronized (this.lock) {
            this.parking = true;
            this.segment = new ReplayMeta.Segment(file, this.tick);
            this.segment.blobs = ReplayWriter.BLOB_FILE;
            this.meta.segments.add(this.segment);
            this.writer.openSegment(this.dir.resolve(file));
        }
    }

    /** Takes what must be from a single moment now. Chunks follow in {@link #frame()}. */
    private void beginSnapshot(boolean first) {
        long start = Perf.start();
        SnapshotBuilder.Task task = SnapshotBuilder.begin(this::writeSnapshotPacket, this.writer, this.chunkVersions, JOURNAL, first);
        this.tracker.reset();
        LocalPlayer player = Minecraft.getInstance().player;
        if (player != null) this.tracker.writeInitial(player, task::addLast);
        this.captureStartTick = this.tick;
        this.capture = task;
        Perf.end("record.snapshotInstant", start);
    }

    private void finishSnapshot() {
        long start = Perf.start();
        SnapshotBuilder.Task task = this.capture;
        this.capture = null;
        synchronized (this.lock) {
            task.writeLast(this::writeSnapshotPacket, this.writer::snapshotPayload);
            this.writer.entry(EntryType.SNAPSHOT_END, EMPTY);
            for (Parked entry : this.parked) {
                if (entry.version != null) {
                    this.writer.chunk(entry.bytes, entry.version);
                    entry.version.parked = false;
                } else {
                    this.writer.entry(entry.type, entry.bytes);
                }
            }
            Perf.peak("record.parkedBytes", this.parkedBytes);
            this.parked.clear();
            this.parkedBytes = 0;
            this.parking = false;
        }
        Perf.end("record.snapshotFinish", start);
    }

    private void writeSnapshotPacket(byte[] encoded) {
        this.writer.entry(EntryType.PACKET, encoded);
    }

    /** Finishes a snapshot in progress at once, for when there won't be any more frames to do it in. */
    private void completeCapture() {
        if (this.capture == null) return;
        this.capture.step(Long.MAX_VALUE / 2);
        finishSnapshot();
    }

    public void addMarker(ReplayMeta.Marker marker) {
        this.meta.markers.put(this.tick, marker);
    }

    /** Pausing cuts the stream; resuming starts a fresh segment so the replay jumps straight to the new state. */
    public void setPaused(boolean pause) {
        if (pause == this.paused) return;
        if (pause) {
            completeCapture();
            this.paused = true;
            saveMeta();
        } else {
            synchronized (this.lock) {
                this.parking = true;
                // Whatever arrived during the pause went unrecorded.
                this.chunkVersions.clear();
            }
            this.paused = false;
            ((BlockableEventLoopInvoker) (Object) Minecraft.getInstance()).flashbackreforged$runAllTasks();
            beginSegment();
            this.segment.discontinuity = true;
            beginSnapshot(false);
        }
    }

    /** Stops and throws the recording away. */
    public void cancel() {
        finish(() -> {
            try {
                ReplayStorage.delete(this.dir);
            } catch (IOException e) {
                FlashbackReforged.LOGGER.warn("Could not remove cancelled recording {}", this.dir, e);
            }
        });
    }

    /**
     * Render thread, once the world is drawn and before the HUD and any open screen are. An early picture covers short
     * recordings, then a later one replaces it once the world has loaded around the player.
     */
    public void beforeGui() {
        collectThumbnail(false);
        if (this.paused || this.tick < this.nextThumbnailTick) return;
        this.nextThumbnailTick = this.nextThumbnailTick == THUMBNAIL_EARLY_TICK ? THUMBNAIL_TICK : Integer.MAX_VALUE;
        saveThumbnail();
    }

    private void saveThumbnail() {
        this.thumbnailSaved = true;
        long start = Perf.start();
        try {
            // Only ever one at a time, and in order, so a later picture always wins.
            collectThumbnail(true);
            this.thumbnail = new Thumbnails.Capture(Minecraft.getInstance().getMainRenderTarget());        } catch (Throwable t) {
            // A missing picture is no reason to disturb a recording.
            FlashbackReforged.LOGGER.warn("Could not save replay thumbnail", t);
        }
        Perf.end("record.thumbnail", start);
    }

    private void collectThumbnail(boolean wait) {
        if (this.thumbnail == null) return;
        byte[] pixels = this.thumbnail.poll(wait);
        if (pixels == null) return;
        this.thumbnail = null;
        this.writer.run(() -> Thumbnails.write(pixels, this.dir));
    }

    public Path stop() {
        return finish(null);
    }

    /** Returns at once: the writer finishes in the background, and {@link #awaitWrites} waits for it. */
    private Path finish(Runnable afterWrites) {
        if (this.stopped) return this.dir;
        if (!this.thumbnailSaved) saveThumbnail();
        collectThumbnail(true);
        completeCapture();
        active = null;
        synchronized (this.lock) {
            this.stopped = true;
        }
        this.meta.finished = true;
        saveMeta();
        CLOSING.add(this.writer);
        this.writer.finish(() -> {
            if (afterWrites != null) afterWrites.run();
            CLOSING.remove(this.writer);
        });
        FlashbackReforged.LOGGER.info("Recording finished: {} ticks, {} packets skipped", this.tick, this.packetFailures);
        return this.dir;
    }

    /** The picture of the metadata is taken here, where it's safe, and written elsewhere. */
    private void saveMeta() {
        byte[] json = this.meta.toJson();
        this.writer.run(() -> ReplayMeta.writeJson(this.dir, json));
    }
}
