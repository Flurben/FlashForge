package com.flurben.flashforge.mixin.rng;

import com.flurben.flashforge.export.ExportManager;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.particle.Particle;
import net.minecraft.util.RandomSource;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Particle.class)
public abstract class MixinParticleRng {
    @Shadow @Final protected RandomSource random;

    @Inject(method = "<init>(Lnet/minecraft/client/multiplayer/ClientLevel;DDD)V", at = @At("RETURN"), require = 0)
    private void flashforge$seed(ClientLevel level, double x, double y, double z, CallbackInfo ci) {
        if (ExportManager.rngActive()) this.random.setSeed(ExportManager.nextSeed());
    }
}
