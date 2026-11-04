package com.flurben.flashbackreforged.mixin;

import com.flurben.flashbackreforged.record.Recorder;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.network.protocol.game.ClientboundBlockDestructionPacket;
import net.minecraft.network.protocol.game.ClientboundLevelEventPacket;
import net.minecraft.network.protocol.game.ClientboundSoundEntityPacket;
import net.minecraft.network.protocol.game.ClientboundSoundPacket;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Effects that the local client predicts are excluded by the server from what it sends back, so without this a
 * replay would have no sound or particles for anything the recording player did.
 */
@Mixin(ClientLevel.class)
public abstract class MixinClientLevel {
    private static boolean flashbackreforged$isLocal(Player player) {
        return player != null && player == Minecraft.getInstance().player && Recorder.active() != null;
    }

    @Inject(method = "playSeededSound(Lnet/minecraft/world/entity/player/Player;DDDLnet/minecraft/core/Holder;Lnet/minecraft/sounds/SoundSource;FFJ)V", at = @At("HEAD"))
    private void flashbackreforged$sound(Player player, double x, double y, double z, Holder<SoundEvent> sound, SoundSource source, float volume, float pitch, long seed, CallbackInfo ci) {
        if (flashbackreforged$isLocal(player)) Recorder.active().writeSynthetic(new ClientboundSoundPacket(sound, source, x, y, z, volume, pitch, seed));
    }

    @Inject(method = "playSeededSound(Lnet/minecraft/world/entity/player/Player;Lnet/minecraft/world/entity/Entity;Lnet/minecraft/core/Holder;Lnet/minecraft/sounds/SoundSource;FFJ)V", at = @At("HEAD"))
    private void flashbackreforged$entitySound(Player player, Entity entity, Holder<SoundEvent> sound, SoundSource source, float volume, float pitch, long seed, CallbackInfo ci) {
        if (flashbackreforged$isLocal(player)) Recorder.active().writeSynthetic(new ClientboundSoundEntityPacket(sound, source, entity, volume, pitch, seed));
    }

    @Inject(method = "levelEvent", at = @At("HEAD"))
    private void flashbackreforged$levelEvent(Player player, int type, BlockPos pos, int data, CallbackInfo ci) {
        if (flashbackreforged$isLocal(player)) Recorder.active().writeSynthetic(new ClientboundLevelEventPacket(type, pos, data, false));
    }

    @Inject(method = "destroyBlockProgress", at = @At("HEAD"))
    private void flashbackreforged$destroyProgress(int id, BlockPos pos, int progress, CallbackInfo ci) {
        Player self = Minecraft.getInstance().player;
        if (self != null && id == self.getId() && Recorder.active() != null) {
            Recorder.active().writeSynthetic(new ClientboundBlockDestructionPacket(id, pos, progress));
        }
    }
}
