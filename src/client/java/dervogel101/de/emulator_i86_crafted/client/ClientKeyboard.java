package dervogel101.de.emulator_i86_crafted.client;

import dervogel101.de.emulator_i86_crafted.ModSounds;
import dervogel101.de.emulator_i86_crafted.client.screen.KeyboardScreen;
import dervogel101.de.emulator_i86_crafted.network.KeyboardPackets;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import com.mojang.blaze3d.platform.InputConstants;

public final class ClientKeyboard {
    private static boolean escapeRelease;
    private ClientKeyboard() {}
    public static void initialize() {
        ClientPlayNetworking.registerGlobalReceiver(KeyboardPackets.Capture.TYPE, (packet, context) -> {
            var client = context.client();
            if (packet.active()) client.gui.setScreen(new KeyboardScreen(packet.token()));
            else if (client.gui.screen() instanceof KeyboardScreen screen && screen.token().equals(packet.token()))
                client.gui.setScreen(null);
        });
        ClientPlayNetworking.registerGlobalReceiver(KeyboardPackets.Overflow.TYPE, (packet, context) -> {
            if (context.client().gui.screen() instanceof KeyboardScreen screen && screen.token().equals(packet.token()))
                context.client().getSoundManager().play(SimpleSoundInstance.forUI(ModSounds.FAULT, 1F, .30F));
        });
    }
    public static boolean active() { return Minecraft.getInstance().gui.screen() instanceof KeyboardScreen; }
    public static boolean key(long window, int action, KeyEvent event) {
        var client = Minecraft.getInstance();
        if (client.getWindow().handle() != window) return false;
        if (event.key() == InputConstants.KEY_ESCAPE && escapeRelease) {
            if (action == InputConstants.RELEASE) escapeRelease = false;
            return true;
        }
        if (!(client.gui.screen() instanceof KeyboardScreen screen)) return false;
        if (action == InputConstants.PRESS || action == InputConstants.REPEAT) {
            if (event.key() == InputConstants.KEY_ESCAPE) { escapeRelease = true; screen.onClose(); }
            else screen.accept(event);
        }
        return true;
    }
}
