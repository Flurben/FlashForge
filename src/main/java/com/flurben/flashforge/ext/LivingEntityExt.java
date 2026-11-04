package com.flurben.flashforge.ext;

/** Added to LivingEntity by mixin. Lives outside the mixin package so normal code may reference it. */
public interface LivingEntityExt {
    /** Jumps straight to the pose the entity is interpolating towards. */
    void flashforge$finishLerp();
}
