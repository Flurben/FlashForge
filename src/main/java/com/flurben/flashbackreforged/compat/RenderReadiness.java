package com.flurben.flashbackreforged.compat;

import me.jellysquid.mods.sodium.client.render.SodiumWorldRenderer;
import net.minecraft.client.Minecraft;
import net.minecraftforge.fml.ModList;

/** Whether terrain has finished building, so exports don't capture holes. Embeddium replaces the vanilla path. */
public final class RenderReadiness {
    private static final boolean EMBEDDIUM = ModList.get().isLoaded("embeddium") || ModList.get().isLoaded("sodium");

    private RenderReadiness() {}

    public static boolean terrainReady() {
        Minecraft mc = Minecraft.getInstance();
        if (EMBEDDIUM) {
            try {
                return Embeddium.ready();
            } catch (Throwable t) {
                // A renderer API change should mean slightly less waiting, never a failed export.
                return true;
            }
        }
        return mc.levelRenderer.hasRenderedAllChunks();
    }

    /** Separate class so the Embeddium types only load if the mod is present. */
    private static final class Embeddium {
        static boolean ready() {
            SodiumWorldRenderer renderer = SodiumWorldRenderer.instanceNullable();
            return renderer == null || renderer.isTerrainRenderComplete();
        }
    }
}
