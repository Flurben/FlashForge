package com.flurben.flashbackreforged.playback;

import net.minecraft.network.protocol.game.ClientboundCustomPayloadPacket;
import com.flurben.flashbackreforged.FlashbackReforged;
import com.flurben.flashbackreforged.compat.DistantHorizonsCompat;
import com.flurben.flashbackreforged.editor.EditorState;
import com.flurben.flashbackreforged.ext.LivingEntityExt;
import com.flurben.flashbackreforged.mixin.accessor.MinecraftAccessor;
import com.flurben.flashbackreforged.mixin.accessor.TimerAccessor;
import com.flurben.flashbackreforged.record.PacketCodec;
import com.flurben.flashbackreforged.record.PacketPolicy;
import com.flurben.flashbackreforged.replay.EntryType;
import com.flurben.flashbackreforged.replay.LocalHud;
import com.flurben.flashbackreforged.replay.LocalPose;
import com.flurben.flashbackreforged.replay.ReplayMeta;
import com.mojang.authlib.GameProfile;
import io.netty.buffer.Unpooled;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.player.RemotePlayer;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.*;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.GameType;
import net.minecraftforge.registries.ForgeRegistry;
import net.minecraftforge.registries.GameData;

import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Plays a replay into a real {@link ClientPacketListener}, so every vanilla and modded client-side code path runs
 * as it would online. All work is on the client thread.
 * <p>
 * Decoding and handling are guarded per packet: a replay must survive a modded handler that throws, where a real
 * connection would disconnect.
 */
public final class ReplayPlayer {
    /** The viewer can't share an id or UUID with the recorded player, who is a normal entity in the world. */
    public static final int VIEWER_ID = Integer.MIN_VALUE + 1337;
    private static final UUID VIEWER_UUID = UUID.nameUUIDFromBytes("flashback_reforged:viewer".getBytes());
    private static final int MAX_LOGGED_FAILURES = 25;

    private static ReplayPlayer active;

    private final Path dir;
    private final ReplayMeta meta;
    private final Minecraft mc = Minecraft.getInstance();
    private final ReplayConnection connection = new ReplayConnection();
    private final EditorState editor;
    private final ClientPacketListener listener;
    private final Map<String, Integer> failuresByPacket = new HashMap<>();
    private final ViewerCamera camera = new ViewerCamera();

    private final ReplayFiles files;
    private BlobReader blobs;
    private int blobFileId;
    /** The entry being applied, wherever its bytes turned out to be. */
    private byte[] payloadData;
    private int payloadOffset;
    private int payloadLength;
    /** Which stored content each loaded chunk came from, for as long as nothing has changed it. */
    private final Long2LongOpenHashMap chunkContent = new Long2LongOpenHashMap();
    /** The chunks in the snapshot about to be applied. */
    private final LongOpenHashSet snapshotChunks = new LongOpenHashSet();
    private int segmentIndex = -1;
    private SegmentReader segment;
    /** Position on the timeline. */
    private int tick;
    /** How far the recording has been applied to the world. Only differs from {@link #tick} during a freeze. */
    private int worldTick;
    private boolean worldFrozen;
    private int totalTicks;
    private boolean paused = true;
    private float speed = 1f;
    private int pendingSeek = -1;
    private boolean inSnapshot;
    private boolean fastForwarding;
    private boolean loggedIn;
    private boolean viewerPlaced;
    private int spectatedEntity = Integer.MIN_VALUE;
    private LocalHud hud;
    private int recordedPlayerId = Integer.MIN_VALUE;
    private long packetsApplied;
    private long packetFailures;

    private ReplayPlayer(Path dir, ReplayMeta meta) {
        this.dir = dir;
        this.meta = meta;
        this.totalTicks = meta.totalTicks;
        GameProfile viewer = new GameProfile(VIEWER_UUID, "Viewer");
        this.listener = new ClientPacketListener(this.mc, null, this.connection, null, viewer,
                this.mc.getTelemetryManager().createWorldSessionManager(false, null, null));
        this.connection.setListener(this.listener);
        this.editor = EditorState.load(dir, this);
        this.files = new ReplayFiles(dir, meta);
    }

    public static ReplayPlayer active() {
        return active;
    }

    public static boolean isActive() {
        return active != null;
    }

    /** Client thread only. Any open world must be closed first. */
    public static ReplayPlayer open(Path dir) throws IOException {
        if (active != null) throw new IllegalStateException("A replay is already open");
        // A recording that just stopped may still be reaching the disk.
        com.flurben.flashbackreforged.record.Recorder.awaitWrites();
        Minecraft mc = Minecraft.getInstance();
        if (mc.level != null) throw new IllegalStateException("Leave the current world before opening a replay");

        long openStart = com.flurben.flashbackreforged.perf.Perf.start();
        ReplayMeta meta = ReplayMeta.read(dir);
        if (meta.segments.isEmpty()) throw new IOException("Replay has no recorded data");
        if (meta.format > ReplayMeta.FORMAT) throw new IOException("This replay was made by a newer version of the mod");
        if (!meta.finished) recover(dir, meta);

        long registryStart = com.flurben.flashbackreforged.perf.Perf.start();
        Map<ResourceLocation, ForgeRegistry.Snapshot> registries = readRegistries(dir);
        com.flurben.flashbackreforged.perf.Perf.end("open.readRegistries", registryStart);
        long injectStart = com.flurben.flashbackreforged.perf.Perf.start();
        var missing = GameData.injectSnapshot(registries, false, false);
        com.flurben.flashbackreforged.perf.Perf.end("open.injectRegistries", injectStart);
        if (!missing.isEmpty()) {
            FlashbackReforged.LOGGER.warn("Replay references {} registry entries missing from this game (pack changed since recording?): {}", missing.size(), missing);
        }

        long configStart = com.flurben.flashbackreforged.perf.Perf.start();
        com.flurben.flashbackreforged.replay.ServerConfigStore.restore(dir);
        com.flurben.flashbackreforged.perf.Perf.end("open.restoreConfigs", configStart);

        ReplayPlayer player = new ReplayPlayer(dir, meta);
        player.chunkContent.defaultReturnValue(-1L);
        active = player;
        try {
            player.loadSegment(0);
            player.applySnapshot();
            player.mc.setScreen(null);
        } catch (Throwable t) {
            player.close();
            throw t;
        }
        com.flurben.flashbackreforged.perf.Perf.end("open.total", openStart);
        FlashbackReforged.LOGGER.info("Opened replay {} ({} ticks, {} segments)", meta.name, meta.totalTicks, meta.segments.size());
        return player;
    }

    /** A recording that never finished (crash, power loss) has stale counts in its metadata. Rebuild them. */
    private static void recover(Path dir, ReplayMeta meta) throws IOException {
        int total = 0;
        List<ReplayMeta.Segment> usable = new ArrayList<>();
        for (ReplayMeta.Segment s : meta.segments) {
            if (!Files.exists(dir.resolve(s.file))) continue;
            int ticks = new SegmentReader(dir.resolve(s.file)).countTicks();
            s.startTick = total;
            s.ticks = ticks;
            total += ticks;
            usable.add(s);
        }
        meta.segments = usable;
        meta.totalTicks = total;
        meta.finished = true;
        meta.write(dir);
        FlashbackReforged.LOGGER.info("Recovered unfinished recording {}: {} ticks", meta.name, total);
    }

    private static Map<ResourceLocation, ForgeRegistry.Snapshot> readRegistries(Path dir) throws IOException {
        CompoundTag root = NbtIo.readCompressed(dir.resolve("registries.nbt").toFile());
        Map<ResourceLocation, ForgeRegistry.Snapshot> out = new HashMap<>();
        for (String key : root.getAllKeys()) out.put(new ResourceLocation(key), ForgeRegistry.Snapshot.read(root.getCompound(key)));
        return out;
    }

    public Path dir() {
        return this.dir;
    }

    public ReplayMeta meta() {
        return this.meta;
    }

    public EditorState editor() {
        return this.editor;
    }

    public ViewerCamera camera() {
        return this.camera;
    }

    public int tick() {
        return this.tick;
    }

    public int totalTicks() {
        return this.totalTicks;
    }

    public boolean paused() {
        return this.paused;
    }

    /** Whether the game's world simulation should stand still right now. */
    public boolean worldStopped() {
        // An export keeps the replay paused so nothing else advances it, but its frames still need the world running.
        return (this.paused && !com.flurben.flashbackreforged.export.ExportManager.isExporting()) || this.worldFrozen;
    }

    /** For callers already at the top of a frame, where changing the world is safe. */
    public void runPendingSeek() {
        if (this.pendingSeek >= 0) {
            int target = this.pendingSeek;
            this.pendingSeek = -1;
            performSeek(target);
        }
    }

    public void setPaused(boolean paused) {
        this.paused = paused;
    }

    public float speed() {
        return this.speed;
    }

    public void setSpeed(float speed) {
        this.speed = Math.max(0.01f, speed);
        ((TimerAccessor) ((MinecraftAccessor) this.mc).flashbackreforged$timer()).flashbackreforged$setMsPerTick(50f / this.speed);
    }

    public LocalHud hud() {
        return this.hud;
    }

    public int recordedPlayerId() {
        return this.recordedPlayerId;
    }

    public long packetFailures() {
        return this.packetFailures;
    }

    public long packetsApplied() {
        return this.packetsApplied;
    }

    public boolean seekPending() {
        return this.pendingSeek >= 0;
    }

    public void seek(int target) {
        this.pendingSeek = Math.max(0, Math.min(target, this.totalTicks));
    }

    public Entity spectated() {
        return this.mc.level == null || this.spectatedEntity == Integer.MIN_VALUE ? null : this.mc.level.getEntity(this.spectatedEntity);
    }

    public void spectate(Entity entity) {
        this.spectatedEntity = entity == null ? Integer.MIN_VALUE : entity.getId();
        this.mc.setCameraEntity(entity == null ? this.mc.player : entity);
    }

    /**
     * Called at the start of every client tick, but the work is queued rather than done here. Handling a login or a
     * dimension change calls Minecraft.setLevel, which renders a nested frame; that is only legal from the task queue
     * at the top of a frame, which is also where packets from a real server get handled.
     */
    public void clientTick() {
        // During an export the export clock moves the recording, one video frame at a time.
        if (com.flurben.flashbackreforged.export.ExportManager.isExporting()) return;
        this.mc.tell(this::pump);
    }

    private void pump() {
        if (active != this) return;
        runPendingSeek();
        if (this.paused) return;
        step();
    }

    /** Moves the timeline forward one tick. The world follows unless a Freeze keyframe is holding it back. */
    public void step() {
        if (this.tick >= this.totalTicks) {
            this.paused = true;
            return;
        }
        boolean frozen = this.editor.scene().frozenAt(this.tick + 1);
        this.tick++;
        this.worldFrozen = frozen;
        if (frozen) return;
        if (this.worldTick < this.tick - 1) {
            // A freeze just ended: catch the world up silently, so it resumes at the right moment.
            this.fastForwarding = true;
            try {
                while (this.worldTick < this.tick - 1 && advanceOneTick()) this.worldTick++;
            } finally {
                this.fastForwarding = false;
            }
            settleEntities();
        }
        if (advanceOneTick()) this.worldTick++;
        else this.paused = true;
    }

    private void loadSegment(int index) throws IOException {
        this.segment = new SegmentReader(this.files.segment(index));
        this.blobs = this.files.blobsFor(index);
        this.blobFileId = this.files.blobFileId(index);
        this.segmentIndex = index;
    }

    /**
     * Notes what the snapshot at the start of this segment contains and unpacks the parts that will be needed, all at once.
     * Leaves the cursor at the start.
     */
    private void scanSnapshot() {
        this.segment.rewind();
        this.snapshotChunks.clear();
        long[] needed = new long[1024];
        int count = 0;
        while (this.segment.next() && this.segment.type != EntryType.SNAPSHOT_END) {
            if (this.segment.type == EntryType.BLOB_REF) {
                long offset = this.segment.readBlobOffset();
                if (this.segment.blobIsChunk()) {
                    long pos = ChunkPos.asLong(this.segment.blobChunkX(), this.segment.blobChunkZ());
                    this.snapshotChunks.add(pos);
                    if (this.chunkContent.get(pos) == contentId(offset, this.segment.blobChangeHash())) continue;
                }
                if (count == needed.length) needed = java.util.Arrays.copyOf(needed, count * 2);
                needed[count++] = offset;
            } else if (this.segment.type == EntryType.PACKET) {
                int at = PacketPolicy.chunkCoordinatesOffset(this.segment.data(), this.segment.payloadOffset, this.segment.payloadLength);
                if (at >= 0) {
                    var coords = java.nio.ByteBuffer.wrap(this.segment.data(), at, 8);
                    this.snapshotChunks.add(ChunkPos.asLong(coords.getInt(), coords.getInt()));
                }
            }
        }
        if (this.blobs != null) this.blobs.preload(java.util.Arrays.copyOf(needed, count));
        this.segment.rewind();
    }

    /** Which stored copy of a chunk, in which file, with which changes on top. */
    private long contentId(long offset, long changeHash) {
        return (offset | (long) this.blobFileId << 48) ^ changeHash * 0xC6A4A7935BD1E995L;
    }

    /** Called when something outside the recording changes a block, so a later seek knows the chunk isn't as stored. */
    public void chunkChanged(net.minecraft.core.BlockPos pos) {
        this.chunkContent.remove(ChunkPos.asLong(pos));
    }

    /** The loaded chunk, if it's there to be kept. */
    private net.minecraft.world.level.chunk.LevelChunk loadedChunk(int x, int z) {
        return this.mc.level == null ? null : this.mc.level.getChunkSource().getChunk(x, z, false);
    }

    /**
     * Vanilla's block entities only change through packets this class sees. A mod's can also change through its own
     * channels, which can't be followed, so those get put back as stored.
     */
    private static boolean hasModdedBlockEntities(net.minecraft.world.level.chunk.LevelChunk chunk) {
        for (var blockEntity : chunk.getBlockEntities().values()) {
            var id = net.minecraft.world.level.block.entity.BlockEntityType.getKey(blockEntity.getType());
            if (id == null || !id.getNamespace().equals("minecraft")) return true;
        }
        return false;
    }

    /** Puts a kept chunk's block entities back as stored, without touching its blocks, so nothing needs rebuilding. */
    private void restoreBlockEntities(byte[] data) {
        try {
            if (!(PacketCodec.decode(new FriendlyByteBuf(Unpooled.wrappedBuffer(data))) instanceof ClientboundLevelChunkWithLightPacket packet)) return;
            ClientLevel level = this.mc.level;
            packet.getChunkData().getBlockEntitiesTagsConsumer(packet.getX(), packet.getZ()).accept((pos, type, tag) -> {
                var blockEntity = level.getBlockEntity(pos);
                if (blockEntity != null && tag != null) blockEntity.handleUpdateTag(tag);
            });
        } catch (Throwable t) {
            noteFailure("block entities", t);
        }
    }

    /**
     * Applies entries up to and including the next tick boundary, moving into the next segment if needed.
     *
     * @return false if the recording ended before another tick boundary.
     */
    private boolean advanceOneTick() {
        while (true) {
            if (!this.segment.next()) {
                if (this.segmentIndex + 1 >= this.meta.segments.size()) {
                    this.totalTicks = Math.min(this.totalTicks, this.worldTick);
                    return false;
                }
                try {
                    boolean fresh = this.meta.segments.get(this.segmentIndex + 1).discontinuity;
                    loadSegment(this.segmentIndex + 1);
                    if (fresh) {
                        scanSnapshot();
                        resetWorld();
                        applySnapshot();
                        settleEntities();
                    } else {
                        // State carries over from the previous segment, so its snapshot is not needed.
                        skipSnapshot();
                    }
                } catch (IOException e) {
                    FlashbackReforged.LOGGER.error("Could not read the next replay segment", e);
                    return false;
                }
                continue;
            }
            if (this.segment.type == EntryType.TICK) return true;
            applyEntry();
        }
    }

    private void skipSnapshot() {
        while (this.segment.next()) {
            if (this.segment.type == EntryType.SNAPSHOT_END) return;
        }
    }

    private void applySnapshot() {
        long start = com.flurben.flashbackreforged.perf.Perf.start();
        this.inSnapshot = true;
        this.fastForwarding = true;
        try {
            while (this.segment.next() && this.segment.type != EntryType.SNAPSHOT_END) applyEntry();
            retryDeferredPayloads();
        } finally {
            this.inSnapshot = false;
            this.fastForwarding = false;
        }
        com.flurben.flashbackreforged.perf.Perf.end("replay.applySnapshot", start);
    }

    /**
     * A snapshot carries mod payloads before the world, because mods need their state before chunks arrive. Some of them
     * refer to a player or entity, so one that fails gets another try once the whole snapshot is in place.
     */
    @SuppressWarnings("unchecked")
    private void retryDeferredPayloads() {
        List<byte[]> payloads = new ArrayList<>(this.deferredPayloads);
        this.deferredPayloads.clear();
        for (byte[] bytes : payloads) {
            try {
                ((Packet<ClientGamePacketListener>) (Packet<?>) PacketCodec.decode(new FriendlyByteBuf(Unpooled.wrappedBuffer(bytes)))).handle(this.listener);
                this.packetsApplied++;
            } catch (Throwable t) {
                noteFailure("ClientboundCustomPayloadPacket", t);
            }
        }
    }

    private void performSeek(int target) {
        long start = System.nanoTime();
        target = Math.min(target, this.totalTicks);
        // Landing inside a freeze shows the world as it stood when the freeze began.
        int worldTarget = target;
        while (worldTarget > 0 && this.editor.scene().frozenAt(worldTarget)) worldTarget--;
        this.worldFrozen = worldTarget != target;

        int targetSegment = 0;
        for (int i = 0; i < this.meta.segments.size(); i++) {
            if (this.meta.segments.get(i).startTick <= worldTarget) targetSegment = i;
        }

        double vx = 0, vy = 0, vz = 0;
        float yaw = 0, pitch = 0;
        LocalPlayer viewer = this.mc.player;
        if (viewer != null) {
            vx = viewer.getX();
            vy = viewer.getY();
            vz = viewer.getZ();
            yaw = viewer.getYRot();
            pitch = viewer.getXRot();
        }

        boolean sameSegmentForward = targetSegment == this.segmentIndex && worldTarget >= this.worldTick;
        try {
            if (!sameSegmentForward) {
                if (targetSegment != this.segmentIndex) loadSegment(targetSegment);
                long scanStart = com.flurben.flashbackreforged.perf.Perf.start();
                scanSnapshot();
                com.flurben.flashbackreforged.perf.Perf.end("seek.scanSnapshot", scanStart);
                long resetStart = com.flurben.flashbackreforged.perf.Perf.start();
                resetWorld();
                com.flurben.flashbackreforged.perf.Perf.end("seek.resetWorld", resetStart);
                this.worldTick = this.meta.segments.get(targetSegment).startTick;
                applySnapshot();
            }
            this.fastForwarding = true;
            long forwardStart = com.flurben.flashbackreforged.perf.Perf.start();
            int forwardFrom = this.worldTick;
            while (this.worldTick < worldTarget && advanceOneTick()) this.worldTick++;
            com.flurben.flashbackreforged.perf.Perf.end("seek.fastForward", forwardStart);
            com.flurben.flashbackreforged.perf.Perf.peak("seek.fastForwardTicks", this.worldTick - forwardFrom);
        } catch (IOException e) {
            FlashbackReforged.LOGGER.error("Seek failed", e);
        } finally {
            this.fastForwarding = false;
        }
        this.tick = target;

        settleEntities();
        LocalPlayer now = this.mc.player;
        if (now != null) {
            now.moveTo(vx, vy, vz, yaw, pitch);
            this.camera.syncFrom(now);
        }
        if (this.spectatedEntity != Integer.MIN_VALUE) {
            Entity entity = spectated();
            this.mc.setCameraEntity(entity == null ? this.mc.player : entity);
        }
        this.editor.onSeek(this.tick);
        com.flurben.flashbackreforged.perf.Perf.add("seek.total", System.nanoTime() - start);
        FlashbackReforged.LOGGER.debug("Seek to {} took {} ms", target, (System.nanoTime() - start) / 1_000_000);
    }

    /**
     * Removes everything the next snapshot will recreate. The ClientLevel survives, because replacing it would make
     * shaders, Embeddium and DH tear down all their per-world state on every backward seek. Chunks the snapshot also has
     * are left in place, to be replaced or kept as it's applied, so the world doesn't go missing and need rebuilding.
     */
    private void resetWorld() {
        ClientLevel level = this.mc.level;
        if (level == null) return;

        List<Integer> entities = new ArrayList<>();
        for (Entity entity : level.entitiesForRendering()) {
            if (entity != this.mc.player) entities.add(entity.getId());
        }
        for (int id : entities) level.removeEntity(id, Entity.RemovalReason.DISCARDED);

        int radius = this.mc.options.getEffectiveRenderDistance() + 4;
        ChunkPos center = new ChunkPos(this.mc.player.blockPosition());
        // The chunk cache is centred on the recorded player, so sweep generously rather than around the free camera.
        ClientboundSetChunkCacheCenterPacket last = lastCenter;
        int cx = last != null ? last.getX() : center.x;
        int cz = last != null ? last.getZ() : center.z;
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                if (!this.snapshotChunks.contains(ChunkPos.asLong(cx + dx, cz + dz)) && level.getChunkSource().getChunk(cx + dx, cz + dz, false) != null) {
                    this.chunkContent.remove(ChunkPos.asLong(cx + dx, cz + dz));
                    guardedHandle(new ClientboundForgetLevelChunkPacket(cx + dx, cz + dz));
                }
            }
        }

        List<UUID> players = new ArrayList<>();
        for (PlayerInfo info : this.listener.getOnlinePlayers()) {
            if (!info.getProfile().getId().equals(VIEWER_UUID)) players.add(info.getProfile().getId());
        }
        if (!players.isEmpty()) guardedHandle(new ClientboundPlayerInfoRemovePacket(players));

        this.mc.gui.getBossOverlay().reset();
        this.mc.gui.resetTitleTimes();
        this.mc.gui.getChat().clearMessages(false);
        this.mc.particleEngine.setLevel(level);
        this.mc.getSoundManager().stop();
        var board = level.getScoreboard();
        for (var team : new ArrayList<>(board.getPlayerTeams())) board.removePlayerTeam(team);
        for (var objective : new ArrayList<>(board.getObjectives())) board.removeObjective(objective);
        guardedHandle(new ClientboundGameEventPacket(ClientboundGameEventPacket.STOP_RAINING, 0));
    }

    private ClientboundSetChunkCacheCenterPacket lastCenter;

    /** Seeking skips ticks, so interpolating entities need to be moved to where they were heading. */
    private void settleEntities() {
        if (this.mc.level == null) return;
        for (Entity entity : this.mc.level.entitiesForRendering()) {
            if (entity == this.mc.player) continue;
            if (entity instanceof LivingEntity living) ((LivingEntityExt) living).flashbackreforged$finishLerp();
            entity.setOldPosAndRot();
            entity.xOld = entity.getX();
            entity.yOld = entity.getY();
            entity.zOld = entity.getZ();
        }
    }

    private void applyEntry() {
        EntryType type = this.segment.type;
        this.payloadData = this.segment.data();
        this.payloadOffset = this.segment.payloadOffset;
        this.payloadLength = this.segment.payloadLength;
        switch (type) {
            case PACKET -> applyPacket();
            case BLOB_REF -> applyBlobReference();
            case LOCAL_POSE -> applyPose();
            case LOCAL_HUD -> {
                try {
                    this.hud = LocalHud.read(buffer());
                } catch (Exception e) {
                    FlashbackReforged.LOGGER.debug("Bad HUD entry", e);
                }
            }
            case VOICE -> {
                if (!this.fastForwarding) com.flurben.flashbackreforged.compat.VoiceChatCompat.playback(buffer());
            }
            default -> {
            }
        }
    }

    private void applyBlobReference() {
        long offset = this.segment.readBlobOffset();
        boolean isChunk = this.segment.blobIsChunk();
        int x = isChunk ? this.segment.blobChunkX() : 0, z = isChunk ? this.segment.blobChunkZ() : 0;
        long pos = isChunk ? ChunkPos.asLong(x, z) : 0;
        int changes = isChunk ? this.segment.blobChanges() : 0;
        long id = contentId(offset, isChunk ? this.segment.blobChangeHash() : 0);
        // Already showing exactly this, so there's nothing to do, and nothing for the renderer to rebuild.
        var kept = isChunk && this.inSnapshot && this.chunkContent.get(pos) == id ? loadedChunk(x, z) : null;
        if (kept != null && !hasModdedBlockEntities(kept)) {
            com.flurben.flashbackreforged.perf.Perf.add("seek.chunkKept", 0);
            for (int i = 0; i < changes; i++) this.segment.next();
            return;
        }
        byte[] data = null;
        try {
            if (this.blobs != null) data = this.blobs.get(offset);
        } catch (IOException e) {
            noteFailure("blob", e);
        }
        if (data == null) {
            for (int i = 0; i < changes; i++) this.segment.next();
            return;
        }
        if (kept != null) {
            com.flurben.flashbackreforged.perf.Perf.add("seek.chunkKeptRestoringBlockEntities", 0);
            restoreBlockEntities(data);
        } else {
            this.payloadData = data;
            this.payloadOffset = 0;
            this.payloadLength = data.length;
            if (!applyPacket()) isChunk = false;
        }
        // These are part of what the chunk is, so they don't count as it having been changed since.
        this.applyingChunkChanges = true;
        for (int i = 0; i < changes && this.segment.next(); i++) applyEntry();
        this.applyingChunkChanges = false;
        if (isChunk) this.chunkContent.put(pos, id);
    }

    private boolean applyingChunkChanges;

    private FriendlyByteBuf buffer() {
        return new FriendlyByteBuf(Unpooled.wrappedBuffer(this.payloadData, this.payloadOffset, this.payloadLength));
    }

    private void applyPose() {
        LocalPose pose = LocalPose.read(buffer());
        this.recordedPlayerId = pose.entityId();
        if (!this.viewerPlaced && this.mc.player != null) {
            this.viewerPlaced = true;
            this.mc.player.moveTo(pose.x(), pose.y() + 1.5, pose.z(), pose.yaw(), pose.pitch());
            this.camera.syncFrom(this.mc.player);
        }
        if (this.mc.level == null) return;
        Entity entity = this.mc.level.getEntity(pose.entityId());
        if (entity instanceof RemotePlayer player) {
            // One-step lerp: the entity reaches exactly this pose by the end of this tick, with no smoothing delay.
            player.lerpTo(pose.x(), pose.y(), pose.z(), pose.yaw(), pose.pitch(), 1, false);
            player.lerpHeadTo(pose.headYaw(), 1);
            player.setPose(pose.poseValue());
            player.setOnGround(pose.onGround());
        }
    }

    /** @return whether the packet was given to the game. */
    @SuppressWarnings("unchecked")
    private boolean applyPacket() {
        // Decided from the id, because decoding something only to throw it away adds up across a long seek.
        int id = this.payloadLength > 0 ? PacketPolicy.readId(this.payloadData, this.payloadOffset) : -1;
        if (PacketPolicy.dropAtPlayback(id)) return false;
        if (this.fastForwarding && PacketPolicy.isTransientEffect(id)) return false;
        if (this.loggedIn && PacketPolicy.isLogin(id)) return false;
        if (this.loggedIn && this.tagsApplied && PacketPolicy.isTags(id)) return false;

        Packet<?> packet;
        long decodeStart = com.flurben.flashbackreforged.perf.Perf.start();
        try {
            packet = PacketCodec.decode(buffer());
        } catch (Throwable t) {
            noteFailure("decode", t);
            return false;
        }
        com.flurben.flashbackreforged.perf.Perf.end(this.fastForwarding ? "packet.decodeSeeking" : "packet.decodePlaying", decodeStart);
        if (packet == null) return false;

        if (packet instanceof ClientboundLoginPacket login) {
            this.loggedIn = true;
            this.chunkContent.clear();
            packet = forViewer(login);
            guardedHandle(packet);
            installViewerInfo();
            return true;
        }
        if (packet instanceof ClientboundUpdateTagsPacket) this.tagsApplied = true;
        if (packet instanceof ClientboundSetChunkCacheCenterPacket center) this.lastCenter = center;
        if (packet instanceof ClientboundRespawnPacket respawn) {
            // Replacing the viewer is only needed when the dimension really changes.
            if (this.mc.level != null && respawn.getDimension() == this.mc.level.dimension()) return true;
            this.chunkContent.clear();
            packet = forViewer(respawn);
            guardedHandle(packet);
            installViewerInfo();
            if (!this.inSnapshot) this.viewerPlaced = false;
            if (!this.inSnapshot && this.mc.screen != null) this.mc.setScreen(null);
            return true;
        }
        if (!this.applyingChunkChanges) noteChunkChange(packet);
        guardedHandle(packet);
        return true;
    }

    /** Keeps {@link #chunkContent} honest. */
    private void noteChunkChange(Packet<?> packet) {
        if (packet instanceof ClientboundBlockUpdatePacket p) {
            chunkChanged(p.getPos());
        } else if (packet instanceof ClientboundSectionBlocksUpdatePacket p) {
            p.runUpdates((pos, state) -> chunkChanged(pos));
        } else if (packet instanceof ClientboundBlockEntityDataPacket p) {
            chunkChanged(p.getPos());
        } else if (packet instanceof ClientboundLightUpdatePacket p) {
            this.chunkContent.remove(ChunkPos.asLong(p.getX(), p.getZ()));
        } else if (packet instanceof ClientboundForgetLevelChunkPacket p) {
            this.chunkContent.remove(ChunkPos.asLong(p.getX(), p.getZ()));
        } else if (packet instanceof ClientboundLevelChunkWithLightPacket p) {
            this.chunkContent.remove(ChunkPos.asLong(p.getX(), p.getZ()));
        } else if (packet instanceof ClientboundExplodePacket) {
            this.chunkContent.clear();
        }
    }

    private boolean tagsApplied;
    private final List<byte[]> deferredPayloads = new ArrayList<>();

    @SuppressWarnings("unchecked")
    private void guardedHandle(Packet<?> packet) {
        long start = com.flurben.flashbackreforged.perf.Perf.start();
        try {
            ((Packet<ClientGamePacketListener>) packet).handle(this.listener);
            this.packetsApplied++;
            if (start != 0) {
                String name = packet instanceof ClientboundCustomPayloadPacket custom ? "custom:" + custom.getIdentifier() : packet.getClass().getSimpleName();
                com.flurben.flashbackreforged.perf.Perf.add((this.fastForwarding ? "handleSeeking:" : "handlePlaying:") + name, System.nanoTime() - start);
            }
        } catch (Throwable t) {
            if (this.inSnapshot && packet instanceof ClientboundCustomPayloadPacket) {
                this.deferredPayloads.add(java.util.Arrays.copyOfRange(this.payloadData, this.payloadOffset, this.payloadOffset + this.payloadLength));
            } else {
                noteFailure(packet.getClass().getSimpleName(), t);
            }
        }
    }

    private void noteFailure(String what, Throwable t) {
        this.packetFailures++;
        int count = this.failuresByPacket.merge(what, 1, Integer::sum);
        if (count <= 3 && this.packetFailures <= MAX_LOGGED_FAILURES) {
            FlashbackReforged.LOGGER.warn("Skipping replay packet ({}) that failed at tick {}", what, this.tick, t);
        }
    }

    private static ClientboundLoginPacket forViewer(ClientboundLoginPacket p) {
        return new ClientboundLoginPacket(VIEWER_ID, false, GameType.SPECTATOR, GameType.SPECTATOR, p.levels(), p.registryHolder(),
                p.dimensionType(), p.dimension(), p.seed(), p.maxPlayers(), p.chunkRadius(), p.simulationDistance(),
                false, false, p.isDebug(), p.isFlat(), p.lastDeathLocation(), p.portalCooldown());
    }

    private static ClientboundRespawnPacket forViewer(ClientboundRespawnPacket p) {
        return new ClientboundRespawnPacket(p.getDimensionType(), p.getDimension(), p.getSeed(), GameType.SPECTATOR, GameType.SPECTATOR,
                p.isDebug(), p.isFlat(), ClientboundRespawnPacket.KEEP_ALL_DATA, p.getLastDeathLocation(), p.getPortalCooldown());
    }

    /**
     * Spectator mode is read from the player's tab entry, so the viewer needs one. It's unlisted, keeping it out of the
     * tab overlay. Built in wire format because the packet's real constructor needs server-side player objects.
     */
    private void installViewerInfo() {
        if (this.listener.getPlayerInfo(VIEWER_UUID) != null) return;
        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
        try {
            buf.writeEnumSet(EnumSet.of(ClientboundPlayerInfoUpdatePacket.Action.ADD_PLAYER,
                    ClientboundPlayerInfoUpdatePacket.Action.UPDATE_GAME_MODE,
                    ClientboundPlayerInfoUpdatePacket.Action.UPDATE_LISTED), ClientboundPlayerInfoUpdatePacket.Action.class);
            buf.writeVarInt(1);
            buf.writeUUID(VIEWER_UUID);
            buf.writeUtf("Viewer", 16);
            buf.writeVarInt(0);
            buf.writeVarInt(GameType.SPECTATOR.getId());
            buf.writeBoolean(false);
            guardedHandle(new ClientboundPlayerInfoUpdatePacket(buf));
        } finally {
            buf.release();
        }
    }

    /** Leaves the replay and returns to the title screen. */
    public void exit() {
        close();
        if (this.mc.level != null) {
            this.mc.level.disconnect();
            this.mc.clearLevel(new net.minecraft.client.gui.screens.GenericDirtMessageScreen(Component.translatable("menu.savingLevel")));
        }
        this.mc.setScreen(new net.minecraft.client.gui.screens.TitleScreen());
    }

    /** Also runs when the world closes some other way (pause menu, crash handling). */
    public void close() {
        if (active != this) return;
        active = null;
        this.mc.options.hideGui = false;
        com.flurben.flashbackreforged.client.SpectatorHud.endFrame();
        this.editor.save();
        this.files.close();
        setSpeedNoCheck();
        this.connection.shutdown();
        DistantHorizonsCompat.onReplayClosed();
        if (this.packetFailures > 0) {
            FlashbackReforged.LOGGER.warn("Replay closed with {} skipped packets: {}", this.packetFailures, this.failuresByPacket);
        }
    }

    private void setSpeedNoCheck() {
        ((TimerAccessor) ((MinecraftAccessor) this.mc).flashbackreforged$timer()).flashbackreforged$setMsPerTick(50f);
    }
}
