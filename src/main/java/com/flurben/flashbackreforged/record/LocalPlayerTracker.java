package com.flurben.flashbackreforged.record;

import com.flurben.flashbackreforged.replay.LocalHud;
import com.flurben.flashbackreforged.replay.LocalPose;
import com.mojang.datafixers.util.Pair;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundAddPlayerPacket;
import net.minecraft.network.protocol.game.ClientboundAnimatePacket;
import net.minecraft.network.protocol.game.ClientboundSetEntityDataPacket;
import net.minecraft.network.protocol.game.ClientboundSetEquipmentPacket;
import net.minecraft.network.protocol.game.ClientboundUpdateAttributesPacket;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * A server never tells a client about that client's own player, so in a replay the recording player would simply
 * not exist. Each tick this writes what other players would have received: spawn, pose, equipment, swings.
 */
final class LocalPlayerTracker {
    private final Recorder recorder;
    private final Map<EquipmentSlot, ItemStack> lastEquipment = new EnumMap<>(EquipmentSlot.class);
    private LocalPlayer lastPlayer;
    private LocalHud lastHud;
    private int lastSwingTime;

    LocalPlayerTracker(Recorder recorder) {
        this.recorder = recorder;
    }

    void reset() {
        this.lastPlayer = null;
        this.lastHud = null;
        this.lastEquipment.clear();
    }

    /** Written into a snapshot so the player exists as soon as the segment is entered. */
    void writeInitial(LocalPlayer player, Consumer<byte[]> out) {
        emit(new ClientboundAddPlayerPacket(player), out);
        emit(new ClientboundUpdateAttributesPacket(player.getId(), player.getAttributes().getSyncableAttributes()), out);
        var data = player.getEntityData().getNonDefaultValues();
        if (data != null) emit(new ClientboundSetEntityDataPacket(player.getId(), data), out);
        List<Pair<EquipmentSlot, ItemStack>> equipment = new ArrayList<>();
        for (EquipmentSlot slot : EquipmentSlot.values()) {
            ItemStack stack = player.getItemBySlot(slot);
            this.lastEquipment.put(slot, stack.copy());
            if (!stack.isEmpty()) equipment.add(Pair.of(slot, stack.copy()));
        }
        if (!equipment.isEmpty()) emit(new ClientboundSetEquipmentPacket(player.getId(), equipment), out);
        this.lastPlayer = player;
        this.lastSwingTime = player.swingTime;
    }

    private static void emit(Packet<?> packet, Consumer<byte[]> out) {
        try {
            byte[] bytes = PacketCodec.encode(packet);
            if (bytes != null) out.accept(bytes);
        } catch (Exception ignored) {
            // A partial player is better than an aborted snapshot.
        }
    }

    void tick(LocalPlayer player) {
        if (player != this.lastPlayer) {
            // A new LocalPlayer object means a respawn or dimension change, and the old remote copy is gone.
            this.lastEquipment.clear();
            writeInitial(player, this::writeRaw);
        }

        this.recorder.writePose(LocalPose.of(player));

        List<Pair<EquipmentSlot, ItemStack>> changed = null;
        for (EquipmentSlot slot : EquipmentSlot.values()) {
            ItemStack now = player.getItemBySlot(slot);
            ItemStack before = this.lastEquipment.get(slot);
            if (before == null || !ItemStack.matches(before, now)) {
                ItemStack copy = now.copy();
                this.lastEquipment.put(slot, copy);
                if (changed == null) changed = new ArrayList<>();
                changed.add(Pair.of(slot, copy));
            }
        }
        if (changed != null) this.recorder.writeSynthetic(new ClientboundSetEquipmentPacket(player.getId(), changed));

        // swingTime restarts from zero each time an arm swing begins.
        if (player.swinging && player.swingTime <= this.lastSwingTime && player.swingTime <= 1) {
            this.recorder.writeSynthetic(new ClientboundAnimatePacket(player,
                    player.swingingArm == InteractionHand.OFF_HAND ? ClientboundAnimatePacket.SWING_OFF_HAND : ClientboundAnimatePacket.SWING_MAIN_HAND));
        }
        this.lastSwingTime = player.swinging ? player.swingTime : Integer.MAX_VALUE;

        LocalHud hud = LocalHud.of(player);
        if (!hud.sameAs(this.lastHud)) {
            this.recorder.writeHud(hud);
            this.lastHud = hud;
        }
    }

    private void writeRaw(byte[] encoded) {
        this.recorder.writeEncoded(encoded);
    }
}
