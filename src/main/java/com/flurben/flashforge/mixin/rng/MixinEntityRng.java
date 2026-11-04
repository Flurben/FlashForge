package com.flurben.flashforge.mixin.rng;

import com.flurben.flashforge.export.ExportManager;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Entity.class)
public abstract class MixinEntityRng {
    @Shadow @Final protected RandomSource random;

    @Inject(method = "<init>", at = @At("RETURN"), require = 0)
    private void flashforge$seed(EntityType<?> type, Level level, CallbackInfo ci) {
        if (ExportManager.rngActive()) this.random.setSeed(ExportManager.nextSeed());
    }
}
