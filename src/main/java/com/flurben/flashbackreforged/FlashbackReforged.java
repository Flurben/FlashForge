package com.flurben.flashbackreforged;

import com.mojang.logging.LogUtils;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.fml.IExtensionPoint;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.common.Mod;
import org.slf4j.Logger;

/** Mod entry point. Flashback Reforged is client-only, so servers without it never reject a client that has it. */
@Mod(FlashbackReforged.MOD_ID)
public final class FlashbackReforged {
    public static final String MOD_ID = "flashback_reforged";
    public static final Logger LOGGER = LogUtils.getLogger();

    public FlashbackReforged() {
        ModLoadingContext.get().registerExtensionPoint(IExtensionPoint.DisplayTest.class,
                () -> new IExtensionPoint.DisplayTest(() -> IExtensionPoint.DisplayTest.IGNORESERVERONLY, (version, fromServer) -> true));
        // Everything below touches client-only classes; a server with this jar installed just ignores it.
        if (net.minecraftforge.fml.loading.FMLEnvironment.dist != Dist.CLIENT) return;
        LegacyNameMigration.run();
        ModLoadingContext.get().registerConfig(net.minecraftforge.fml.config.ModConfig.Type.CLIENT, com.flurben.flashbackreforged.client.FlashbackReforgedConfig.SPEC);
        ModLoadingContext.get().registerExtensionPoint(net.minecraftforge.client.ConfigScreenHandler.ConfigScreenFactory.class,
                () -> new net.minecraftforge.client.ConfigScreenHandler.ConfigScreenFactory((mc, parent) -> new com.flurben.flashbackreforged.client.ui.SettingsScreen(parent)));
        com.flurben.flashbackreforged.compat.DistantHorizonsCompat.init();
        LOGGER.info("Flashback Reforged loading");
    }
}
