package dervogel101.de.emulator_i86_crafted.client.screen;

import dervogel101.de.emulator_i86_crafted.network.KeyboardPackets;
import dervogel101.de.i86.runtime.KeyboardInput;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.network.chat.Component;
import org.lwjgl.sdl.SDLKeyboard;
import java.util.UUID;

public final class KeyboardScreen extends Screen {
    private final UUID token;
    private KeyboardInput.Layout layout;
    public KeyboardScreen(UUID token) { super(Component.literal("Keyboard input — Escape to release")); this.token = token; }
    public UUID token() { return token; }
    @Override protected void init() {
        KeyMapping.releaseAll();
        layout = SDLKeyboard.SDL_GetKeyFromScancode(28, (short) 0, false) == 'z'
                && SDLKeyboard.SDL_GetKeyFromScancode(29, (short) 0, false) == 'y' ? KeyboardInput.Layout.DE : KeyboardInput.Layout.US;
    }
    public void accept(KeyEvent event) {
        int value = KeyboardInput.translate(layout, event.key(), event.modifiers());
        if (value >= 0) ClientPlayNetworking.send(new KeyboardPackets.Key(token, value));
    }
    @Override public boolean keyPressed(KeyEvent event) { return true; }
    @Override public boolean charTyped(CharacterEvent event) { return true; }
    @Override public boolean isPauseScreen() { return false; }
    @Override public void onClose() { minecraft.gui.setScreen(null); }
    @Override public void removed() {
        KeyMapping.releaseAll();
        if (ClientPlayNetworking.canSend(KeyboardPackets.Key.TYPE)) ClientPlayNetworking.send(new KeyboardPackets.Key(token, 0));
    }
    @Override public void extractBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {}
    @Override public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
        int x = width - 34, y = height / 2 - 10;
        graphics.fill(x, y, x + 28, y + 19, 0xff14181c);
        graphics.fill(x + 1, y + 1, x + 27, y + 18, 0xffb6c3c9);
        for (int row = 0; row < 2; row++) for (int column = 0; column < 5; column++)
            graphics.fill(x + 3 + column * 5, y + 3 + row * 5, x + 6 + column * 5, y + 6 + row * 5, 0xff29343b);
        graphics.fill(x + 7, y + 13, x + 22, y + 16, 0xff29343b);
    }
}
