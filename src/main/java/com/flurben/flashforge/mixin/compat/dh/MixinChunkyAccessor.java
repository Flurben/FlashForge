package com.flurben.flashforge.mixin.compat.dh;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Distant Horizons connects to Chunky on the first chunk it sees, but Chunky only starts once the server has. TerraFirmaCraft
 * loads chunks earlier than that while choosing a spawn point, so creating a world with these three mods crashes.
 * Holding off until Chunky is up means the connection is made on a later chunk instead.
 */
@Pseudo
@Mixin(targets = "com.seibel.distanthorizons.core.wrapperInterfaces.modAccessor.AbstractChunkyAccessor", remap = false)
public abstract class MixinChunkyAccessor {
    @Inject(method = "tryRunFirstTimeSetup", at = @At("HEAD"), cancellable = true, require = 0)
    private void flashforge$waitForChunky(CallbackInfo ci) {
        try {
            Class.forName("org.popcraft.chunky.ChunkyProvider").getMethod("get").invoke(null);
        } catch (ReflectiveOperationException | LinkageError e) {
            ci.cancel();
        }
    }
}
