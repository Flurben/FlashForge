package com.flurben.flashbackreforged.record;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import net.minecraft.network.ConnectionProtocol;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketFlow;

/** Encodes and decodes clientbound play packets exactly as they travel on the wire. */
public final class PacketCodec {
    /** One large packet shouldn't keep its buffer's memory for the rest of the session. */
    private static final int MAX_KEPT_CAPACITY = 1 << 20;

    /** Encoding happens constantly on a few threads, and each buffer otherwise regrows from small for every chunk. */
    private static final ThreadLocal<Scratch> SCRATCH = ThreadLocal.withInitial(Scratch::new);

    private static final class Scratch {
        ByteBuf buffer = Unpooled.buffer(1 << 16);
        boolean inUse;
    }

    private PacketCodec() {}

    /** @return the encoded payload (varint id + body), or null if this packet has no protocol id. */
    public static byte[] encode(Packet<?> packet) throws Exception {
        int id = ConnectionProtocol.PLAY.getPacketId(PacketFlow.CLIENTBOUND, packet);
        if (id < 0) return null;
        Scratch scratch = SCRATCH.get();
        // A mod's encoder could encode something else from inside this one.
        ByteBuf raw = scratch.inUse ? Unpooled.buffer(256) : scratch.buffer;
        boolean shared = raw == scratch.buffer;
        if (shared) {
            scratch.inUse = true;
            raw.clear();
        }
        try {
            FriendlyByteBuf buf = new FriendlyByteBuf(raw);
            buf.writeVarInt(id);
            packet.write(buf);
            byte[] out = new byte[raw.readableBytes()];
            raw.getBytes(raw.readerIndex(), out);
            return out;
        } finally {
            if (shared) {
                scratch.inUse = false;
                if (raw.capacity() > MAX_KEPT_CAPACITY) {
                    raw.release();
                    scratch.buffer = Unpooled.buffer(1 << 16);
                }
            } else {
                raw.release();
            }
        }
    }

    public static Packet<?> decode(FriendlyByteBuf buf) {
        int id = buf.readVarInt();
        return ConnectionProtocol.PLAY.createPacket(PacketFlow.CLIENTBOUND, id, buf);
    }
}
