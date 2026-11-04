package com.flurben.flashforge.mixin.accessor;

import net.minecraft.client.gui.components.PlayerTabOverlay;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(PlayerTabOverlay.class)
public interface PlayerTabOverlayAccessor {
    @Accessor("header")
    Component flashforge$header();

    @Accessor("footer")
    Component flashforge$footer();
}
