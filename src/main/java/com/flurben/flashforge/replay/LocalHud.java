package com.flurben.flashforge.replay;

import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.NonNullList;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;

/** Hotbar, selected slot and vitals of the recording player, used to draw the HUD while spectating them. */
public record LocalHud(int selected, float health, int food, float saturation, int xpLevel, float xpProgress,
                       int armor, int air, List<ItemStack> hotbar) {

    public static LocalHud of(LocalPlayer p) {
        List<ItemStack> hotbar = new ArrayList<>(10);
        NonNullList<ItemStack> items = p.getInventory().items;
        for (int i = 0; i < 9; i++) hotbar.add(items.get(i).copy());
        hotbar.add(p.getOffhandItem().copy());
        return new LocalHud(p.getInventory().selected, p.getHealth(), p.getFoodData().getFoodLevel(),
                p.getFoodData().getSaturationLevel(), p.experienceLevel, p.experienceProgress,
                p.getArmorValue(), p.getAirSupply(), hotbar);
    }

    public void write(FriendlyByteBuf b) {
        b.writeVarInt(selected);
        b.writeFloat(health);
        b.writeVarInt(food);
        b.writeFloat(saturation);
        b.writeVarInt(xpLevel);
        b.writeFloat(xpProgress);
        b.writeVarInt(armor);
        b.writeVarInt(air);
        b.writeCollection(hotbar, FriendlyByteBuf::writeItem);
    }

    public static LocalHud read(FriendlyByteBuf b) {
        return new LocalHud(b.readVarInt(), b.readFloat(), b.readVarInt(), b.readFloat(),
                b.readVarInt(), b.readFloat(), b.readVarInt(), b.readVarInt(), b.readList(FriendlyByteBuf::readItem));
    }

    public boolean sameAs(LocalHud o) {
        if (o == null || selected != o.selected || health != o.health || food != o.food || saturation != o.saturation
                || xpLevel != o.xpLevel || xpProgress != o.xpProgress || armor != o.armor || air != o.air) {
            return false;
        }
        for (int i = 0; i < hotbar.size(); i++) {
            if (!ItemStack.matches(hotbar.get(i), o.hotbar.get(i))) return false;
        }
        return true;
    }
}
