package com.flurben.flashbackreforged.mixin;

import com.flurben.flashbackreforged.record.Recorder;
import io.netty.channel.ChannelHandlerContext;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.Connection;
import net.minecraft.network.PacketListener;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.game.ClientboundBundlePacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Sees each clientbound play packet on the netty thread, in arrival order and before it is handled. */
@Mixin(Connection.class)
public abstract class MixinConnection {
    @Shadow public abstract PacketFlow getReceiving();

    @Shadow public abstract PacketListener getPacketListener();

    @Inject(method = "channelRead0(Lio/netty/channel/ChannelHandlerContext;Lnet/minecraft/network/protocol/Packet;)V", at = @At("HEAD"))
    private void flashbackreforged$capture(ChannelHandlerContext context, Packet<?> packet, CallbackInfo ci) {
        if (getReceiving() != PacketFlow.CLIENTBOUND || !(getPacketListener() instanceof ClientPacketListener)) return;
        if (packet instanceof ClientboundBundlePacket bundle) {
            for (Packet<?> inner : bundle.subPackets()) Recorder.journal().observe(inner);
        } else {
            Recorder.journal().observe(packet);
        }
        Recorder recorder = Recorder.active();
        if (recorder != null) recorder.onPacket(packet);
    }
}
