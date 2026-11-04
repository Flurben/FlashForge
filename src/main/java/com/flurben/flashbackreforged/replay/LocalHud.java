package com.flurben.flashbackreforged.replay;

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

    /** Compares without copying anything, since copying an item is expensive and this rarely finds a change. */
    public boolean matches(LocalPlayer p) {
        if (selected != p.getInventory().selected || health != p.getHealth() || food != p.getFoodData().getFoodLevel()
                || saturation != p.getFoodData().getSaturationLevel() || xpLevel != p.experienceLevel || xpProgress != p.experienceProgress
                || armor != p.getArmorValue() || air != p.getAirSupply()) {
            return false;
        }
        NonNullList<ItemStack> items = p.getInventory().items;
        for (int i = 0; i < 9; i++) {
            if (!ItemStack.matches(hotbar.get(i), items.get(i))) return false;
        }
        return ItemStack.matches(hotbar.get(9), p.getOffhandItem());
    }
}
