package com.flurben.flashbackreforged.record;

import com.flurben.flashbackreforged.FlashbackReforged;
import com.flurben.flashbackreforged.mixin.accessor.BossHealthOverlayAccessor;
import com.flurben.flashbackreforged.mixin.accessor.PlayerTabOverlayAccessor;
import com.mojang.authlib.properties.Property;
import com.mojang.datafixers.util.Pair;
import io.netty.buffer.Unpooled;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.*;
import net.minecraft.server.ServerScoreboard;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.scores.Objective;
import net.minecraft.world.scores.PlayerTeam;
import net.minecraft.world.scores.Score;
import net.minecraft.world.scores.Scoreboard;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Describes the current client world as the packet sequence a freshly joining client would need. Each step is
 * isolated, because in a modded pack any individual entity or chunk may refuse to serialize client-side.
 */
final class SnapshotBuilder {
    private SnapshotBuilder() {}

    /**
     * A snapshot being written. What must describe one moment is taken when this is created. Chunks are encoded as time
     * allows, and everything that has to follow them is held here until they're done.
     */
    static final class Task {
        private final Emitter chunksOut;
        private final ReplayWriter writer;
        private final ChunkVersions versions;
        private final ClientLevel level;
        private final long[] chunks;
        private final List<byte[]> last = new ArrayList<>();
        private List<byte[]> payloads = List.of();
        private int next;

        private Task(Emitter chunksOut, ReplayWriter writer, ChunkVersions versions, ClientLevel level, long[] chunks) {
            this.chunksOut = chunksOut;
            this.writer = writer;
            this.versions = versions;
            this.level = level;
            this.chunks = chunks;
        }

        void addLast(byte[] encoded) {
            this.last.add(encoded);
        }

        /** @return true once there are no chunks left. */
        boolean step(long budgetNanos) {
            // A dimension change makes the rest of the list mean different chunks. The new ones are in the stream already.
            if (Minecraft.getInstance().level != this.level) this.next = this.chunks.length;
            long deadline = System.nanoTime() + budgetNanos;
            while (this.next < this.chunks.length) {
                long pos = this.chunks[this.next++];
                LevelChunk chunk = this.level.getChunkSource().getChunk(ChunkPos.getX(pos), ChunkPos.getZ(pos), false);
                if (chunk != null && !chunk.isEmpty()) writeChunk(pos, chunk);
                if (System.nanoTime() >= deadline) break;
            }
            return this.next >= this.chunks.length;
        }

        private void writeChunk(long pos, LevelChunk chunk) {
            ChunkVersions.Usable stored = this.versions.usable(pos, !onlyVanillaBlockEntities(chunk));
            if (stored != null) {
                this.writer.reuseChunk(stored, pos);
                for (byte[] change : stored.changes()) this.writer.entry(com.flurben.flashbackreforged.replay.EntryType.PACKET, change);
                com.flurben.flashbackreforged.perf.Perf.add("snapshot.chunkReused", 0);
                return;
            }
            try {
                long start = com.flurben.flashbackreforged.perf.Perf.start();
                byte[] bytes = PacketCodec.encode(new ClientboundLevelChunkWithLightPacket(chunk, this.level.getLightEngine(), null, null));
                com.flurben.flashbackreforged.perf.Perf.end("snapshot.oneChunk", start);
                if (bytes == null) return;
                ChunkVersions.Version version = new ChunkVersions.Version();
                this.writer.chunk(bytes, version);
                this.versions.encoded(pos, version);
            } catch (Throwable t) {
                this.chunksOut.failure("chunk " + chunk.getPos(), t);
            }
        }

        private static boolean onlyVanillaBlockEntities(LevelChunk chunk) {
            for (var blockEntity : chunk.getBlockEntities().values()) {
                var id = net.minecraft.world.level.block.entity.BlockEntityType.getKey(blockEntity.getType());
                if (id == null || !id.getNamespace().equals("minecraft")) return false;
            }
            return true;
        }

        void writeLast(Consumer<byte[]> out, Consumer<byte[]> payloadOut) {
            this.last.forEach(out);
            // Last, so handlers that attach data to chunks and entities find them loaded.
            this.payloads.forEach(payloadOut);
        }
    }

    /** @param session whether to include what's the same for a whole session, which playback only ever needs once. */
    static Task begin(Consumer<byte[]> out, ReplayWriter writer, ChunkVersions versions, SessionJournal journal, boolean session) {
        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        ClientPacketListener listener = mc.getConnection();
        LocalPlayer self = mc.player;
        if (level == null || listener == null || self == null) throw new IllegalStateException("No world to snapshot");

        Emitter e = new Emitter(out);
        if (session) {
            // These two take real time to encode in a big pack, and nothing else touches them, so that happens elsewhere.
            writer.packet(journal.login());
            if (journal.tags() != null) writer.packet(journal.tags());
        }
        journal.preamble().forEach(e::emit);
        e.emit(new ClientboundRespawnMarker(level, self).packet());

        e.step("players", () -> writePlayerInfo(e, listener));
        e.step("time", () -> {
            e.emit(new ClientboundSetTimePacket(level.getGameTime(), level.getDayTime(), level.getGameRules().getBoolean(net.minecraft.world.level.GameRules.RULE_DAYLIGHT)));
            e.emit(new ClientboundInitializeBorderPacket(level.getWorldBorder()));
            e.emit(new ClientboundGameEventPacket(level.isRaining() ? ClientboundGameEventPacket.START_RAINING : ClientboundGameEventPacket.STOP_RAINING, 0));
            e.emit(new ClientboundGameEventPacket(ClientboundGameEventPacket.RAIN_LEVEL_CHANGE, level.getRainLevel(1f)));
            e.emit(new ClientboundGameEventPacket(ClientboundGameEventPacket.THUNDER_LEVEL_CHANGE, level.getThunderLevel(1f)));
        });
        e.step("scoreboard", () -> writeScoreboard(e, level.getScoreboard()));
        e.step("bossbars", () -> ((BossHealthOverlayAccessor) mc.gui.getBossOverlay()).flashbackreforged$events().values()
                .forEach(boss -> e.emit(ClientboundBossEventPacket.createAddPacket(boss))));
        e.step("tablist", () -> {
            PlayerTabOverlayAccessor tab = (PlayerTabOverlayAccessor) mc.gui.getTabList();
            Component header = tab.flashbackreforged$header();
            Component footer = tab.flashbackreforged$footer();
            if (header != null || footer != null) {
                e.emit(new ClientboundTabListPacket(header == null ? Component.empty() : header, footer == null ? Component.empty() : footer));
            }
        });

        Task task = new Task(e, writer, versions, level, loadedChunks(level, self));
        // Entities and the rest describe this moment, but go after the chunks, so they wait in memory.
        Emitter later = new Emitter(task::addLast);
        long entitiesStart = com.flurben.flashbackreforged.perf.Perf.start();
        writeEntities(later, level, self);
        com.flurben.flashbackreforged.perf.Perf.end("snapshot.entities", entitiesStart);
        journal.maps().forEach(later::emit);

        task.payloads = journal.payloads();
        com.flurben.flashbackreforged.perf.Perf.peak("journal.payloadCount", task.payloads.size());
        return task;
    }

    private static long[] loadedChunks(ClientLevel level, LocalPlayer self) {
        int radius = Minecraft.getInstance().options.getEffectiveRenderDistance() + 3;
        ChunkPos center = self.chunkPosition();
        long[] found = new long[(2 * radius + 1) * (2 * radius + 1)];
        int count = 0;
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                if (level.getChunkSource().getChunk(center.x + dx, center.z + dz, false) != null) found[count++] = ChunkPos.asLong(center.x + dx, center.z + dz);
            }
        }
        return Arrays.copyOf(found, count);
    }

    private static void writeEntities(Emitter e, ClientLevel level, LocalPlayer self) {
        List<Entity> vehicles = new ArrayList<>();
        Set<Entity> seen = new HashSet<>();
        for (Entity entity : level.entitiesForRendering()) {
            if (entity == self || !seen.add(entity)) continue;
            try {
                writeEntity(e, entity);
                if (!entity.getPassengers().isEmpty()) vehicles.add(entity);
            } catch (Throwable t) {
                e.failure("entity " + entity.getType(), t);
            }
        }
        // Passengers link to entities that may have spawned after their vehicle, so this goes last.
        for (Entity vehicle : vehicles) e.emit(new ClientboundSetPassengersPacket(vehicle));
        for (Entity entity : seen) {
            if (entity instanceof Mob mob && mob.getLeashHolder() != null) e.emit(new ClientboundSetEntityLinkPacket(mob, mob.getLeashHolder()));
        }
    }

    private static void writeEntity(Emitter e, Entity entity) {
        Packet<?> spawn;
        if (entity instanceof Player player) {
            spawn = new ClientboundAddPlayerPacket(player);
        } else {
            try {
                spawn = entity.getAddEntityPacket();
            } catch (Throwable t) {
                // Modded spawn packets can touch server-only state; the plain packet is a good fallback.
                spawn = new ClientboundAddEntityPacket(entity);
            }
        }
        e.emit(spawn);

        var data = entity.getEntityData().getNonDefaultValues();
        if (data != null) e.emit(new ClientboundSetEntityDataPacket(entity.getId(), data));
        e.emit(new ClientboundSetEntityMotionPacket(entity));
        e.emit(new ClientboundRotateHeadPacket(entity, (byte) (entity.getYHeadRot() * 256f / 360f)));

        if (entity instanceof LivingEntity living) {
            e.emit(new ClientboundUpdateAttributesPacket(entity.getId(), living.getAttributes().getSyncableAttributes()));
            List<Pair<EquipmentSlot, ItemStack>> equipment = new ArrayList<>();
            for (EquipmentSlot slot : EquipmentSlot.values()) {
                ItemStack stack = living.getItemBySlot(slot);
                if (!stack.isEmpty()) equipment.add(Pair.of(slot, stack.copy()));
            }
            if (!equipment.isEmpty()) e.emit(new ClientboundSetEquipmentPacket(entity.getId(), equipment));
            for (MobEffectInstance effect : living.getActiveEffects()) e.emit(new ClientboundUpdateMobEffectPacket(entity.getId(), effect));
        }
    }

    private static void writeScoreboard(Emitter e, Scoreboard board) {
        for (PlayerTeam team : board.getPlayerTeams()) e.emit(ClientboundSetPlayerTeamPacket.createAddOrModifyPacket(team, true));
        for (Objective objective : board.getObjectives()) {
            e.emit(new ClientboundSetObjectivePacket(objective, ClientboundSetObjectivePacket.METHOD_ADD));
            for (Score score : board.getPlayerScores(objective)) {
                e.emit(new ClientboundSetScorePacket(ServerScoreboard.Method.CHANGE, objective.getName(), score.getOwner(), score.getScore()));
            }
        }
        for (int slot = 0; slot < Scoreboard.DISPLAY_SLOTS; slot++) {
            Objective objective = board.getDisplayObjective(slot);
            if (objective != null) e.emit(new ClientboundSetDisplayObjectivePacket(slot, objective));
        }
    }

    /** The real constructor needs ServerPlayers, so the entries are written in wire format and parsed back. */
    private static void writePlayerInfo(Emitter e, ClientPacketListener listener) {
        var actions = EnumSet.of(ClientboundPlayerInfoUpdatePacket.Action.ADD_PLAYER, ClientboundPlayerInfoUpdatePacket.Action.UPDATE_GAME_MODE,
                ClientboundPlayerInfoUpdatePacket.Action.UPDATE_LISTED, ClientboundPlayerInfoUpdatePacket.Action.UPDATE_LATENCY,
                ClientboundPlayerInfoUpdatePacket.Action.UPDATE_DISPLAY_NAME);
        var listed = listener.getListedOnlinePlayers();
        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
        buf.writeEnumSet(actions, ClientboundPlayerInfoUpdatePacket.Action.class);
        buf.writeCollection(listener.getOnlinePlayers(), (b, info) -> writeInfo(b, info, listed.contains(info)));
        e.emit(new ClientboundPlayerInfoUpdatePacket(buf));
        buf.release();
    }

    private static void writeInfo(FriendlyByteBuf b, PlayerInfo info, boolean listed) {
        b.writeUUID(info.getProfile().getId());
        b.writeUtf(info.getProfile().getName(), 16);
        b.writeCollection(info.getProfile().getProperties().values(), (bb, p) -> writeProperty(bb, p));
        b.writeVarInt(info.getGameMode().getId());
        b.writeBoolean(listed);
        b.writeVarInt(info.getLatency());
        b.writeNullable(info.getTabListDisplayName(), FriendlyByteBuf::writeComponent);
    }

    private static void writeProperty(FriendlyByteBuf b, Property p) {
        b.writeUtf(p.getName());
        b.writeUtf(p.getValue());
        b.writeNullable(p.getSignature(), FriendlyByteBuf::writeUtf);
    }

    private static final class Emitter {
        private final Consumer<byte[]> out;
        private int failures;

        Emitter(Consumer<byte[]> out) {
            this.out = out;
        }

        void emit(Packet<?> packet) {
            if (packet == null) return;
            try {
                byte[] bytes = PacketCodec.encode(packet);
                if (bytes != null) this.out.accept(bytes);
            } catch (Throwable t) {
                failure(packet.getClass().getSimpleName(), t);
            }
        }

        void step(String name, Runnable body) {
            try {
                body.run();
            } catch (Throwable t) {
                failure(name, t);
            }
        }

        void failure(String what, Throwable t) {
            if (this.failures++ < 10) FlashbackReforged.LOGGER.warn("Snapshot could not include {}", what, t);
        }
    }

    /** Puts the viewer in the right dimension when the snapshot's login isn't for the current one. */
    private record ClientboundRespawnMarker(ClientLevel level, LocalPlayer self) {
        Packet<?> packet() {
            return new ClientboundRespawnPacket(this.level.dimensionTypeId(), this.level.dimension(), 0L,
                    net.minecraft.world.level.GameType.SPECTATOR, null, this.level.isDebug(), Recorder.journal().login() != null && Recorder.journal().login().isFlat(),
                    ClientboundRespawnPacket.KEEP_ALL_DATA, this.self.getLastDeathLocation(), this.self.getPortalCooldown());
        }
    }

}
