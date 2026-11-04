package com.flurben.flashbackreforged.replay;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.player.Player;

/** Per-tick pose of the recording player, which the server never sends back to its own client. */
public record LocalPose(int entityId, double x, double y, double z, float yaw, float pitch, float headYaw, float bodyYaw,
                        int pose, boolean onGround, float walkDist, float bob) {

    public static LocalPose of(Player p) {
        return new LocalPose(p.getId(), p.getX(), p.getY(), p.getZ(), p.getYRot(), p.getXRot(), p.getYHeadRot(), p.yBodyRot,
                p.getPose().ordinal(), p.onGround(), p.walkDist, p.bob);
    }

    public void write(FriendlyByteBuf b) {
        b.writeVarInt(entityId);
        b.writeDouble(x);
        b.writeDouble(y);
        b.writeDouble(z);
        b.writeFloat(yaw);
        b.writeFloat(pitch);
        b.writeFloat(headYaw);
        b.writeFloat(bodyYaw);
        b.writeVarInt(pose);
        b.writeBoolean(onGround);
        b.writeFloat(walkDist);
        b.writeFloat(bob);
    }

    public static LocalPose read(FriendlyByteBuf b) {
        return new LocalPose(b.readVarInt(), b.readDouble(), b.readDouble(), b.readDouble(),
                b.readFloat(), b.readFloat(), b.readFloat(), b.readFloat(),
                b.readVarInt(), b.readBoolean(), b.readFloat(), b.readFloat());
    }

    public Pose poseValue() {
        Pose[] all = Pose.values();
        return pose >= 0 && pose < all.length ? all[pose] : Pose.STANDING;
    }
}
