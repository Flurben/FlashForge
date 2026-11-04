package com.flurben.flashforge.record;

import com.flurben.flashforge.FlashForge;
import com.flurben.flashforge.compat.DistantHorizonsCompat;
import com.flurben.flashforge.replay.EntryType;
import com.flurben.flashforge.replay.LocalHud;
import com.flurben.flashforge.replay.LocalPose;
import com.flurben.flashforge.replay.ReplayMeta;
import com.flurben.flashforge.replay.ReplayStorage;
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
import java.util.function.Consumer;

/**
 * An active recording. Packets arrive on the netty thread and are encoded there, in arrival order, so the
 * stream matches what the client saw. Ticks, snapshots and synthesized local-player data come from the client
 * thread; {@link #lock} merges the two.
 * <p>
 * Each segment starts with a full state snapshot, which is what makes seeking possible. While a snapshot is being
 * built, incoming packets are parked in {@link #parked} so they land after it rather than inside it.
 */
public final class Recorder {
    private static final int SEGMENT_TICKS = 20 * 60 * 5;
    private static final int META_SAVE_TICKS = 20 * 30;
    private static final long MAX_PACKET_WARNINGS = 20;

    private static volatile Recorder active;
    private static final SessionJournal JOURNAL = new SessionJournal();

    private final Object lock = new Object();
    private final Path dir;
    private final ReplayMeta meta = new ReplayMeta();
    private final List<byte[]> parked = new ArrayList<>();
    private final LocalPlayerTracker tracker = new LocalPlayerTracker(this);
    private SegmentWriter writer;
    private ReplayMeta.Segment segment;
    private boolean parking;
    private volatile boolean paused;
    private int tick;
    private int ticksSinceMetaSave;
    private long packetFailures;

    private Recorder(Path dir, String name) {
        this.dir = dir;
        this.meta.name = name;
    }

    public static Recorder active() {
        return active;
    }

    public static SessionJournal journal() {
        return JOURNAL;
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
        r.saveRegistries();
        com.flurben.flashforge.replay.ServerConfigStore.save(dir);
        r.beginSegment();
        active = r;
        r.finishSnapshot();
        FlashForge.LOGGER.info("Recording started: {}", dir);
        return r;
    }

    /**
     * Numeric ids in packets (blocks, items, entity types, data serializers) are only meaningful together with the
     * id map negotiated with the server. Forge assigns them per world, so a viewer's own map can disagree: mobs turn
     * into other mobs and textures shift. The map is stored here and re-applied when the replay is opened.
     */
    private void saveRegistries() throws IOException {
        Map<ResourceLocation, ForgeRegistry.Snapshot> snapshot = RegistryManager.ACTIVE.takeSnapshot(false);
        CompoundTag root = new CompoundTag();
        snapshot.forEach((key, value) -> root.put(key.toString(), value.write()));
        NbtIo.writeCompressed(root, this.dir.resolve("registries.nbt").toFile());
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
        synchronized (this.lock) {
            return this.writer != null && this.writer.failed();
        }
    }

    /** Called from the netty thread for every clientbound play packet. */
    public void onPacket(Packet<?> packet) {
        if (this.paused) return;
        if (packet instanceof ClientboundBundlePacket bundle) {
            for (Packet<?> inner : bundle.subPackets()) onPacket(inner);
            return;
        }
        if (PacketPolicy.dropAtRecord(packet)) return;
        byte[] bytes;
        try {
            bytes = PacketCodec.encode(packet);
        } catch (Throwable t) {
            // One mod's broken encoder must not end the recording.
            if (this.packetFailures++ < MAX_PACKET_WARNINGS) {
                FlashForge.LOGGER.warn("Skipping packet {} that failed to encode", packet.getClass().getName(), t);
            }
            return;
        }
        if (bytes == null) return;
        synchronized (this.lock) {
            if (this.parking) this.parked.add(bytes);
            else this.writer.entry(EntryType.PACKET, bytes);
        }
    }

    /** Client thread: a packet synthesized on behalf of the local player. */
    public void writeSynthetic(Packet<?> packet) {
        if (this.paused) return;
        try {
            byte[] bytes = PacketCodec.encode(packet);
            if (bytes == null) return;
            synchronized (this.lock) {
                if (this.parking) this.parked.add(bytes);
                else this.writer.entry(EntryType.PACKET, bytes);
            }
        } catch (Throwable t) {
            FlashForge.LOGGER.debug("Could not write synthetic packet {}", packet.getClass().getName(), t);
        }
    }

    void writeEncoded(byte[] bytes) {
        synchronized (this.lock) {
            if (this.parking) this.parked.add(bytes);
            else this.writer.entry(EntryType.PACKET, bytes);
        }
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
            synchronized (this.lock) {
                this.writer.entry(type, bytes);
            }
        } finally {
            raw.release();
        }
    }

    /** Client thread, at the end of every client tick. */
    public void endTick() {
        if (this.paused) return;
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (player != null && mc.level != null) this.tracker.tick(player);

        synchronized (this.lock) {
            this.writer.entry(EntryType.TICK, new byte[0]);
            this.segment.ticks++;
        }
        this.tick++;
        this.meta.totalTicks = this.tick;

        if (this.segment.ticks >= SEGMENT_TICKS) {
            rollSegment();
        }
        if (++this.ticksSinceMetaSave >= META_SAVE_TICKS) {
            this.ticksSinceMetaSave = 0;
            saveMeta();
        }
    }

    private void rollSegment() {
        synchronized (this.lock) {
            this.parking = true;
        }
        // Apply packets the netty thread has already handed over, or they'd be in the old segment but not in the snapshot.
        ((com.flurben.flashforge.mixin.accessor.BlockableEventLoopInvoker) (Object) Minecraft.getInstance()).flashforge$runAllTasks();
        synchronized (this.lock) {
            this.writer.close();
        }
        try {
            beginSegment();
        } catch (IOException e) {
            FlashForge.LOGGER.error("Could not start a new replay segment; stopping recording", e);
            stop();
            return;
        }
        finishSnapshot();
        saveMeta();
    }

    private void beginSegment() throws IOException {
        String file = "seg" + String.format("%04d", this.meta.segments.size()) + ".gz";
        synchronized (this.lock) {
            this.parking = true;
            this.writer = new SegmentWriter(this.dir.resolve(file));
            this.segment = new ReplayMeta.Segment(file, this.tick);
            this.meta.segments.add(this.segment);
        }
    }

    /** Writes the state snapshot, then releases packets that arrived while it was being built. */
    private void finishSnapshot() {
        long start = System.nanoTime();
        SnapshotBuilder.write(this::writeSnapshotPacket, JOURNAL);
        this.tracker.reset();
        LocalPlayer player = Minecraft.getInstance().player;
        if (player != null) this.tracker.writeInitial(player, this::writeSnapshotPacket);
        synchronized (this.lock) {
            this.writer.entry(EntryType.SNAPSHOT_END, new byte[0]);
            for (byte[] bytes : this.parked) this.writer.entry(EntryType.PACKET, bytes);
            this.parked.clear();
            this.parking = false;
        }
        FlashForge.LOGGER.debug("Snapshot written in {} ms", (System.nanoTime() - start) / 1_000_000);
    }

    private void writeSnapshotPacket(byte[] encoded) {
        synchronized (this.lock) {
            this.writer.entry(EntryType.PACKET, encoded);
        }
    }

    public void addMarker(ReplayMeta.Marker marker) {
        this.meta.markers.put(this.tick, marker);
    }

    /** Pausing cuts the stream; resuming starts a fresh segment so the replay jumps straight to the new state. */
    public void setPaused(boolean pause) {
        if (pause == this.paused) return;
        if (pause) {
            this.paused = true;
            saveMeta();
        } else {
            synchronized (this.lock) {
                this.parking = true;
            }
            this.paused = false;
            ((com.flurben.flashforge.mixin.accessor.BlockableEventLoopInvoker) (Object) Minecraft.getInstance()).flashforge$runAllTasks();
            synchronized (this.lock) {
                this.writer.close();
            }
            try {
                beginSegment();
                finishSnapshot();
            } catch (IOException e) {
                FlashForge.LOGGER.error("Could not resume recording", e);
                stop();
            }
        }
    }

    public Path stop() {
        active = null;
        this.meta.finished = true;
        synchronized (this.lock) {
            this.writer.close();
        }
        saveMeta();
        FlashForge.LOGGER.info("Recording finished: {} ticks, {} packets skipped", this.tick, this.packetFailures);
        return this.dir;
    }

    private void saveMeta() {
        try {
            this.meta.write(this.dir);
        } catch (IOException e) {
            FlashForge.LOGGER.error("Could not save replay metadata", e);
        }
    }
}
