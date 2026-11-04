package com.flurben.flashforge;

import com.mojang.logging.LogUtils;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.fml.IExtensionPoint;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.common.Mod;
import org.slf4j.Logger;

/** Mod entry point. FlashForge is client-only, so servers without it never reject a client that has it. */
@Mod(FlashForge.MOD_ID)
public final class FlashForge {
    public static final String MOD_ID = "flashforge";
    public static final Logger LOGGER = LogUtils.getLogger();

    public FlashForge() {
        ModLoadingContext.get().registerExtensionPoint(IExtensionPoint.DisplayTest.class,
                () -> new IExtensionPoint.DisplayTest(() -> IExtensionPoint.DisplayTest.IGNORESERVERONLY, (version, fromServer) -> true));
        // Everything below touches client-only classes; a server with this jar installed just ignores it.
        if (net.minecraftforge.fml.loading.FMLEnvironment.dist != Dist.CLIENT) return;
        ModLoadingContext.get().registerConfig(net.minecraftforge.fml.config.ModConfig.Type.CLIENT, com.flurben.flashforge.client.FlashForgeConfig.SPEC);
        ModLoadingContext.get().registerExtensionPoint(net.minecraftforge.client.ConfigScreenHandler.ConfigScreenFactory.class,
                () -> new net.minecraftforge.client.ConfigScreenHandler.ConfigScreenFactory((mc, parent) -> new com.flurben.flashforge.client.ui.SettingsScreen(parent)));
        com.flurben.flashforge.compat.DistantHorizonsCompat.init();
        LOGGER.info("FlashForge loading");
    }
}
