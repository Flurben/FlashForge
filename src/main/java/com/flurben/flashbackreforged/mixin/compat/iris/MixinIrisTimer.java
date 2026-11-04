package com.flurben.flashbackreforged.mixin.compat.iris;

import com.flurben.flashbackreforged.export.ExportManager;
import net.irisshaders.iris.uniforms.SystemTimeUniforms;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/**
 * Shader packs animate from frameTimeCounter and frameTime. Rendering a frame takes as long as it takes during an export,
 * so these must follow video time, or water and clouds would race and temporal effects would see huge frame gaps.
 */
@Mixin(value = SystemTimeUniforms.Timer.class, remap = false)
public abstract class MixinIrisTimer {
    @ModifyVariable(method = "beginFrame", at = @At("HEAD"), argsOnly = true)
    private long flashbackreforged$exportTime(long realNanos) {
        return ExportManager.shaderClock(realNanos);
    }
}
