package com.flurben.flashforge.mixin;

import com.flurben.flashforge.ext.LivingEntityExt;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

@Mixin(LivingEntity.class)
public abstract class MixinLivingEntity implements LivingEntityExt {
    @Shadow protected int lerpSteps;
    @Shadow protected double lerpX;
    @Shadow protected double lerpY;
    @Shadow protected double lerpZ;
    @Shadow protected double lerpYRot;
    @Shadow protected double lerpXRot;
    @Shadow protected double lyHeadRot;
    @Shadow protected int lerpHeadSteps;
    @Shadow public float yHeadRot;

    @Override
    public void flashforge$finishLerp() {
        Entity self = (Entity) (Object) this;
        if (this.lerpSteps > 0) {
            self.setPos(this.lerpX, this.lerpY, this.lerpZ);
            self.setYRot((float) this.lerpYRot);
            self.setXRot((float) this.lerpXRot);
            this.lerpSteps = 0;
        }
        if (this.lerpHeadSteps > 0) {
            this.yHeadRot = (float) this.lyHeadRot;
            this.lerpHeadSteps = 0;
        }
    }
}
