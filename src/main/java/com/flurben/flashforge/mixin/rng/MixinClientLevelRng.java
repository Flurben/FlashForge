package com.flurben.flashforge.mixin.rng;

import com.flurben.flashforge.export.ExportManager;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.Block;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Ambient block effects, like torch smoke and dripping water, choose where to appear with this random source. */
@Mixin(ClientLevel.class)
public abstract class MixinClientLevelRng {
    @Inject(method = "doAnimateTick", at = @At("HEAD"), require = 0)
    private void flashforge$seed(int x, int y, int z, int range, RandomSource random, Block block, BlockPos.MutableBlockPos pos, CallbackInfo ci) {
        if (ExportManager.rngActive()) random.setSeed(ExportManager.nextSeed());
    }
}
