package com.flurben.flashforge.record;

import com.flurben.flashforge.FlashForge;
import com.flurben.flashforge.mixin.accessor.BossHealthOverlayAccessor;
import com.flurben.flashforge.mixin.accessor.PlayerTabOverlayAccessor;
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

    static void write(Consumer<byte[]> out, SessionJournal journal) {
        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        ClientPacketListener listener = mc.getConnection();
        LocalPlayer self = mc.player;
        if (level == null || listener == null || self == null) throw new IllegalStateException("No world to snapshot");

        Emitter e = new Emitter(out);
        e.emit(journal.login());
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
        e.step("bossbars", () -> ((BossHealthOverlayAccessor) mc.gui.getBossOverlay()).flashforge$events().values()
                .forEach(boss -> e.emit(ClientboundBossEventPacket.createAddPacket(boss))));
        e.step("tablist", () -> {
            PlayerTabOverlayAccessor tab = (PlayerTabOverlayAccessor) mc.gui.getTabList();
            Component header = tab.flashforge$header();
            Component footer = tab.flashforge$footer();
            if (header != null || footer != null) {
                e.emit(new ClientboundTabListPacket(header == null ? Component.empty() : header, footer == null ? Component.empty() : footer));
            }
        });

        writeChunks(e, level, self);
        writeEntities(e, level, self);
        journal.maps().forEach(e::emit);

        // Last, so handlers that attach data to chunks and entities find them loaded.
        for (byte[] payload : journal.payloads()) out.accept(payload);
    }

    private static void writeChunks(Emitter e, ClientLevel level, LocalPlayer self) {
        int radius = Minecraft.getInstance().options.getEffectiveRenderDistance() + 3;
        ChunkPos center = self.chunkPosition();
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                LevelChunk chunk = level.getChunkSource().getChunk(center.x + dx, center.z + dz, false);
                if (chunk == null || chunk.isEmpty()) continue;
                try {
                    e.emit(new ClientboundLevelChunkWithLightPacket(chunk, level.getLightEngine(), null, null));
                } catch (Throwable t) {
                    e.failure("chunk " + chunk.getPos(), t);
                }
            }
        }
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
            if (this.failures++ < 10) FlashForge.LOGGER.warn("Snapshot could not include {}", what, t);
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
