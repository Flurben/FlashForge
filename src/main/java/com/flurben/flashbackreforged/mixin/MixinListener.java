package com.flurben.flashbackreforged.mixin;

import com.flurben.flashbackreforged.client.VisualsRuntime;
import com.mojang.blaze3d.audio.Listener;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/** Hears the replay from a chosen entity. Applies to the speakers and to exported audio alike. */
@Mixin(Listener.class)
public abstract class MixinListener {
    @ModifyVariable(method = "setListenerPosition", at = @At("HEAD"), argsOnly = true, require = 0)
    private Vec3 flashbackreforged$position(Vec3 position) {
        return VisualsRuntime.listenerPosition(position);
    }

    @ModifyVariable(method = "setListenerOrientation", at = @At("HEAD"), argsOnly = true, ordinal = 0, require = 0)
    private Vector3f flashbackreforged$look(Vector3f look) {
        return VisualsRuntime.listenerLook(look);
    }

    @ModifyVariable(method = "setListenerOrientation", at = @At("HEAD"), argsOnly = true, ordinal = 1, require = 0)
    private Vector3f flashbackreforged$up(Vector3f up) {
        return VisualsRuntime.listenerUp(up);
    }
}
