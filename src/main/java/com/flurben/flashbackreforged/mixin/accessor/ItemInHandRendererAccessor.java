package com.flurben.flashbackreforged.mixin.accessor;

import net.minecraft.client.renderer.ItemInHandRenderer;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(ItemInHandRenderer.class)
public interface ItemInHandRendererAccessor {
    @Accessor("mainHandItem") void flashbackreforged$setMainHandItem(ItemStack stack);
    @Accessor("offHandItem") void flashbackreforged$setOffHandItem(ItemStack stack);
    @Accessor("mainHandHeight") void flashbackreforged$setMainHandHeight(float height);
    @Accessor("oMainHandHeight") void flashbackreforged$setOMainHandHeight(float height);
    @Accessor("offHandHeight") void flashbackreforged$setOffHandHeight(float height);
    @Accessor("oOffHandHeight") void flashbackreforged$setOOffHandHeight(float height);
}
