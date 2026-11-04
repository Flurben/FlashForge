package com.flurben.flashbackreforged.compat;

import com.flurben.flashbackreforged.FlashbackReforged;
import de.maxhenkel.voicechat.api.ForgeVoicechatPlugin;
import de.maxhenkel.voicechat.api.VoicechatClientApi;
import de.maxhenkel.voicechat.api.VoicechatPlugin;
import de.maxhenkel.voicechat.api.events.ClientReceiveSoundEvent;
import de.maxhenkel.voicechat.api.events.ClientSoundEvent;
import de.maxhenkel.voicechat.api.events.ClientVoicechatInitializationEvent;
import de.maxhenkel.voicechat.api.events.EventRegistration;
import net.minecraft.client.Minecraft;
import net.minecraft.world.phys.Vec3;

/** Found by Simple Voice Chat through its annotation, so it is never loaded when that mod isn't installed. */
@ForgeVoicechatPlugin
public final class FlashbackReforgedVoicePlugin implements VoicechatPlugin {
    private static VoicechatClientApi api;

    static VoicechatClientApi api() {
        return api;
    }

    @Override
    public String getPluginId() {
        return FlashbackReforged.MOD_ID;
    }

    @Override
    public void registerEvents(EventRegistration registration) {
        registration.registerEvent(ClientVoicechatInitializationEvent.class, e -> api = e.getVoicechat());
        registration.registerEvent(ClientReceiveSoundEvent.EntitySound.class, e ->
                VoiceChatCompat.record(VoiceChatCompat.ENTITY, e.getId(), e.getRawAudio(), e.isWhispering(), e.getDistance(), null));
        registration.registerEvent(ClientReceiveSoundEvent.LocationalSound.class, e ->
                VoiceChatCompat.record(VoiceChatCompat.LOCATIONAL, e.getId(), e.getRawAudio(), false, e.getDistance(),
                        new Vec3(e.getPosition().getX(), e.getPosition().getY(), e.getPosition().getZ())));
        registration.registerEvent(ClientReceiveSoundEvent.StaticSound.class, e ->
                VoiceChatCompat.record(VoiceChatCompat.STATIC, e.getId(), e.getRawAudio(), false, 0f, null));
        // Your own microphone never comes back from the server, so it's captured as it is sent.
        registration.registerEvent(ClientSoundEvent.class, e -> {
            Minecraft mc = Minecraft.getInstance();
            if (mc.player != null && api != null) {
                VoiceChatCompat.record(VoiceChatCompat.ENTITY, mc.player.getUUID(), e.getRawAudio(), e.isWhispering(), (float) api.getVoiceChatDistance(), null);
            }
        });
    }
}
