package com.flurben.flashforge.mixin.accessor;

import net.minecraft.client.renderer.ItemInHandRenderer;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(ItemInHandRenderer.class)
public interface ItemInHandRendererAccessor {
    @Accessor("mainHandItem") void flashforge$setMainHandItem(ItemStack stack);
    @Accessor("offHandItem") void flashforge$setOffHandItem(ItemStack stack);
    @Accessor("mainHandHeight") void flashforge$setMainHandHeight(float height);
    @Accessor("oMainHandHeight") void flashforge$setOMainHandHeight(float height);
    @Accessor("offHandHeight") void flashforge$setOffHandHeight(float height);
    @Accessor("oOffHandHeight") void flashforge$setOOffHandHeight(float height);
}
