package com.flurben.flashbackreforged.playback;

import io.netty.channel.embedded.EmbeddedChannel;
import net.minecraft.network.Connection;
import net.minecraft.network.ConnectionProtocol;
import net.minecraft.network.PacketSendListener;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraftforge.network.NetworkHooks;

/**
 * Stands in for a server link. Packets come from the replay file rather than the channel, and anything the client
 * or a mod tries to send has nowhere to go. A real (embedded) channel is kept because Forge's networking stores
 * connection state in channel attributes.
 */
final class ReplayConnection extends Connection {
    private final EmbeddedChannel embedded;

    ReplayConnection() {
        super(PacketFlow.CLIENTBOUND);
        this.embedded = new EmbeddedChannel(this);
        NetworkHooks.registerClientLoginChannel(this);
        setProtocol(ConnectionProtocol.PLAY);
    }

    @Override
    public void send(Packet<?> packet) {
    }

    @Override
    public void send(Packet<?> packet, PacketSendListener listener) {
    }

    @Override
    public void tick() {
        // Nothing to flush, and no disconnect handling: closing a replay is driven by ReplayPlayer.
    }

    @Override
    public void handleDisconnection() {
    }

    void shutdown() {
        this.embedded.close();
    }
}
