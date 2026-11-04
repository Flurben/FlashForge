package com.flurben.flashbackreforged.client;

import com.flurben.flashbackreforged.FlashbackReforged;
import com.flurben.flashbackreforged.editor.Visuals;
import com.flurben.flashbackreforged.playback.ReplayPlayer;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.toasts.SystemToast;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.player.Player;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RegisterClientCommandsEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * /hide and /show mark entities to leave out of an export. Registered only while a replay is open, because a client command
 * would otherwise take the name from a server's own /hide during normal play.
 */
@Mod.EventBusSubscriber(modid = FlashbackReforged.MOD_ID, value = Dist.CLIENT)
public final class ReplayCommands {
    private ReplayCommands() {}

    @SubscribeEvent
    public static void register(RegisterClientCommandsEvent event) {
        if (!ReplayPlayer.isActive()) return;
        event.getDispatcher().register(Commands.literal("hide").then(Commands.argument("targets", StringArgumentType.greedyString()).executes(c -> run(c, true))));
        event.getDispatcher().register(Commands.literal("show").then(Commands.argument("targets", StringArgumentType.greedyString()).executes(c -> run(c, false))));
    }

    private static int run(CommandContext<CommandSourceStack> context, boolean hide) {
        ReplayPlayer replay = ReplayPlayer.active();
        if (replay == null) return 0;
        Visuals visuals = replay.editor().visuals();
        List<Entity> targets = resolve(StringArgumentType.getString(context, "targets"));
        for (Entity entity : targets) {
            if (hide) visuals.hiddenEntities.add(entity.getUUID().toString());
            else visuals.hiddenEntities.remove(entity.getUUID().toString());
        }
        String title = (hide ? "Hidden from exports: " : "Shown in exports: ") + targets.size();
        SystemToast.add(Minecraft.getInstance().getToasts(), SystemToast.SystemToastIds.PERIODIC_NOTIFICATION, Component.literal(title),
                Component.literal(targets.isEmpty() ? "Nothing matched" : ""));
        return targets.size();
    }

    /** Accepts @e, @a, @e[type=id], an entity type id, a UUID or a name. */
    static List<Entity> resolve(String query) {
        String q = query.trim();
        String type = q.startsWith("@e[type=") && q.endsWith("]") ? q.substring(8, q.length() - 1) : null;
        Minecraft mc = Minecraft.getInstance();
        List<Entity> out = new ArrayList<>();
        for (Entity entity : mc.level.entitiesForRendering()) {
            if (entity == mc.player) continue;
            String id = EntityType.getKey(entity.getType()).toString();
            boolean match;
            if (q.equals("@e")) match = true;
            else if (q.equals("@a")) match = entity instanceof Player;
            else if (type != null) match = id.equals(type) || id.equals("minecraft:" + type);
            else match = id.equals(q) || id.equals("minecraft:" + q) || entity.getUUID().toString().equalsIgnoreCase(q)
                        || entity.getName().getString().toLowerCase(Locale.ROOT).equals(q.toLowerCase(Locale.ROOT));
            if (match) out.add(entity);
        }
        return out;
    }
}
