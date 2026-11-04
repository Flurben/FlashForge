package com.flurben.flashforge.mixin;

import net.minecraftforge.fml.loading.LoadingModList;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

import java.util.List;
import java.util.Set;

/** Applies mixins in a mod-specific "compat" sub-package only when that mod is actually installed. */
public final class FlashForgeMixinPlugin implements IMixinConfigPlugin {
    private static boolean loaded(String modId) {
        LoadingModList list = LoadingModList.get();
        return list != null && list.getModFileById(modId) != null;
    }

    @Override
    public boolean shouldApplyMixin(String target, String mixin) {
        if (mixin.contains(".compat.embeddium.")) return loaded("embeddium") || loaded("sodium");
        if (mixin.contains(".compat.iris.")) return loaded("oculus") || loaded("iris");
        if (mixin.contains(".compat.voicechat.")) return loaded("voicechat");
        if (mixin.contains(".compat.dh.")) return loaded("distanthorizons");
        return true;
    }

    @Override public void onLoad(String root) {}
    @Override public String getRefMapperConfig() { return null; }
    @Override public void acceptTargets(Set<String> mine, Set<String> others) {}
    @Override public List<String> getMixins() { return null; }
    @Override public void preApply(String t, ClassNode c, String m, IMixinInfo i) {}
    @Override public void postApply(String t, ClassNode c, String m, IMixinInfo i) {}
}
