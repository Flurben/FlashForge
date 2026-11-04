package com.flurben.flashforge.playback;

import net.minecraft.network.protocol.game.ClientboundCustomPayloadPacket;
import com.flurben.flashforge.FlashForge;
import com.flurben.flashforge.compat.DistantHorizonsCompat;
import com.flurben.flashforge.editor.EditorState;
import com.flurben.flashforge.ext.LivingEntityExt;
import com.flurben.flashforge.mixin.accessor.MinecraftAccessor;
import com.flurben.flashforge.mixin.accessor.TimerAccessor;
import com.flurben.flashforge.record.PacketCodec;
import com.flurben.flashforge.record.PacketPolicy;
import com.flurben.flashforge.replay.EntryType;
import com.flurben.flashforge.replay.LocalHud;
import com.flurben.flashforge.replay.LocalPose;
import com.flurben.flashforge.replay.ReplayMeta;
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
    private static final UUID VIEWER_UUID = UUID.nameUUIDFromBytes("flashforge:viewer".getBytes());
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
        Minecraft mc = Minecraft.getInstance();
        if (mc.level != null) throw new IllegalStateException("Leave the current world before opening a replay");

        ReplayMeta meta = ReplayMeta.read(dir);
        if (meta.segments.isEmpty()) throw new IOException("Replay has no recorded data");
        if (!meta.finished) recover(dir, meta);

        Map<ResourceLocation, ForgeRegistry.Snapshot> registries = readRegistries(dir);
        var missing = GameData.injectSnapshot(registries, false, false);
        if (!missing.isEmpty()) {
            FlashForge.LOGGER.warn("Replay references {} registry entries missing from this game (pack changed since recording?): {}", missing.size(), missing);
        }

        com.flurben.flashforge.replay.ServerConfigStore.restore(dir);

        ReplayPlayer player = new ReplayPlayer(dir, meta);
        active = player;
        try {
            player.loadSegment(0);
            player.applySnapshot();
            player.mc.setScreen(null);
        } catch (Throwable t) {
            player.close();
            throw t;
        }
        FlashForge.LOGGER.info("Opened replay {} ({} ticks, {} segments)", meta.name, meta.totalTicks, meta.segments.size());
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
        FlashForge.LOGGER.info("Recovered unfinished recording {}: {} ticks", meta.name, total);
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
        return (this.paused && !com.flurben.flashforge.export.ExportManager.isExporting()) || this.worldFrozen;
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
        ((TimerAccessor) ((MinecraftAccessor) this.mc).flashforge$timer()).flashforge$setMsPerTick(50f / this.speed);
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
        if (com.flurben.flashforge.export.ExportManager.isExporting()) return;
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
        this.segment = new SegmentReader(this.dir.resolve(this.meta.segments.get(index).file));
        this.segmentIndex = index;
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
                        resetWorld();
                        applySnapshot();
                        settleEntities();
                    } else {
                        // State carries over from the previous segment, so its snapshot is not needed.
                        skipSnapshot();
                    }
                } catch (IOException e) {
                    FlashForge.LOGGER.error("Could not read the next replay segment", e);
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
        this.inSnapshot = true;
        this.fastForwarding = true;
        try {
            while (this.segment.next() && this.segment.type != EntryType.SNAPSHOT_END) applyEntry();
            retryDeferredPayloads();
        } finally {
            this.inSnapshot = false;
            this.fastForwarding = false;
        }
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
                resetWorld();
                if (targetSegment == this.segmentIndex) this.segment.rewind();
                else loadSegment(targetSegment);
                this.worldTick = this.meta.segments.get(targetSegment).startTick;
                applySnapshot();
            }
            this.fastForwarding = true;
            while (this.worldTick < worldTarget && advanceOneTick()) this.worldTick++;
        } catch (IOException e) {
            FlashForge.LOGGER.error("Seek failed", e);
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
        FlashForge.LOGGER.debug("Seek to {} took {} ms", target, (System.nanoTime() - start) / 1_000_000);
    }

    /**
     * Removes everything the next snapshot will recreate. The ClientLevel survives, because replacing it would make
     * shaders, Embeddium and DH tear down all their per-world state on every backward seek.
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
                if (level.getChunkSource().getChunk(cx + dx, cz + dz, false) != null) {
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
            if (entity instanceof LivingEntity living) ((LivingEntityExt) living).flashforge$finishLerp();
            entity.setOldPosAndRot();
            entity.xOld = entity.getX();
            entity.yOld = entity.getY();
            entity.zOld = entity.getZ();
        }
    }

    private void applyEntry() {
        EntryType type = this.segment.type;
        switch (type) {
            case PACKET -> applyPacket();
            case LOCAL_POSE -> applyPose();
            case LOCAL_HUD -> {
                try {
                    this.hud = LocalHud.read(buffer());
                } catch (Exception e) {
                    FlashForge.LOGGER.debug("Bad HUD entry", e);
                }
            }
            case VOICE -> {
                if (!this.fastForwarding) com.flurben.flashforge.compat.VoiceChatCompat.playback(buffer());
            }
            default -> {
            }
        }
    }

    private FriendlyByteBuf buffer() {
        return new FriendlyByteBuf(Unpooled.wrappedBuffer(this.segment.data(), this.segment.payloadOffset, this.segment.payloadLength));
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

    @SuppressWarnings("unchecked")
    private void applyPacket() {
        Packet<?> packet;
        try {
            packet = PacketCodec.decode(buffer());
        } catch (Throwable t) {
            noteFailure("decode", t);
            return;
        }
        if (packet == null || PacketPolicy.dropAtPlayback(packet)) return;
        if (this.fastForwarding && PacketPolicy.isTransientEffect(packet)) return;

        if (packet instanceof ClientboundLoginPacket login) {
            if (this.loggedIn) return;
            this.loggedIn = true;
            packet = forViewer(login);
            guardedHandle(packet);
            installViewerInfo();
            return;
        }
        if (packet instanceof ClientboundUpdateTagsPacket && this.loggedIn && this.tagsApplied) return;
        if (packet instanceof ClientboundUpdateTagsPacket) this.tagsApplied = true;
        if (packet instanceof ClientboundSetChunkCacheCenterPacket center) this.lastCenter = center;
        if (packet instanceof ClientboundRespawnPacket respawn) {
            // Replacing the viewer is only needed when the dimension really changes.
            if (this.mc.level != null && respawn.getDimension() == this.mc.level.dimension()) return;
            packet = forViewer(respawn);
            guardedHandle(packet);
            installViewerInfo();
            if (!this.inSnapshot) this.viewerPlaced = false;
            if (!this.inSnapshot && this.mc.screen != null) this.mc.setScreen(null);
            return;
        }
        guardedHandle(packet);
    }

    private boolean tagsApplied;
    private final List<byte[]> deferredPayloads = new ArrayList<>();

    @SuppressWarnings("unchecked")
    private void guardedHandle(Packet<?> packet) {
        try {
            ((Packet<ClientGamePacketListener>) packet).handle(this.listener);
            this.packetsApplied++;
        } catch (Throwable t) {
            if (this.inSnapshot && packet instanceof ClientboundCustomPayloadPacket) {
                int offset = this.segment.payloadOffset;
                this.deferredPayloads.add(java.util.Arrays.copyOfRange(this.segment.data(), offset, offset + this.segment.payloadLength));
            } else {
                noteFailure(packet.getClass().getSimpleName(), t);
            }
        }
    }

    private void noteFailure(String what, Throwable t) {
        this.packetFailures++;
        int count = this.failuresByPacket.merge(what, 1, Integer::sum);
        if (count <= 3 && this.packetFailures <= MAX_LOGGED_FAILURES) {
            FlashForge.LOGGER.warn("Skipping replay packet ({}) that failed at tick {}", what, this.tick, t);
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
        com.flurben.flashforge.client.SpectatorHud.endFrame();
        this.editor.save();
        setSpeedNoCheck();
        this.connection.shutdown();
        DistantHorizonsCompat.onReplayClosed();
        if (this.packetFailures > 0) {
            FlashForge.LOGGER.warn("Replay closed with {} skipped packets: {}", this.packetFailures, this.failuresByPacket);
        }
    }

    private void setSpeedNoCheck() {
        ((TimerAccessor) ((MinecraftAccessor) this.mc).flashforge$timer()).flashforge$setMsPerTick(50f);
    }
}
