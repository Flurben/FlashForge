package com.flurben.flashforge.record;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import net.minecraft.network.ConnectionProtocol;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketFlow;

/** Encodes and decodes clientbound play packets exactly as they travel on the wire. */
public final class PacketCodec {
    private PacketCodec() {}

    /** @return the encoded payload (varint id + body), or null if this packet has no protocol id. */
    public static byte[] encode(Packet<?> packet) throws Exception {
        Integer id = ConnectionProtocol.PLAY.getPacketId(PacketFlow.CLIENTBOUND, packet);
        if (id == null) return null;
        ByteBuf raw = Unpooled.buffer(256);
        try {
            FriendlyByteBuf buf = new FriendlyByteBuf(raw);
            buf.writeVarInt(id);
            packet.write(buf);
            byte[] out = new byte[raw.readableBytes()];
            raw.readBytes(out);
            return out;
        } finally {
            raw.release();
        }
    }

    public static Packet<?> decode(FriendlyByteBuf buf) {
        int id = buf.readVarInt();
        return ConnectionProtocol.PLAY.createPacket(PacketFlow.CLIENTBOUND, id, buf);
    }
}
