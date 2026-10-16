package dervogel101.de.emulator_i86_crafted.client.screen;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.network.chat.Component;

final class PanelStyle {
    static final int TEXT = 0xFFE0E8E9, MUTED = 0xFF8A9BA4, CYAN = 0xFF66D8D0;
    private PanelStyle() {}
    static void frame(GuiGraphicsExtractor g, int x, int y, int width, int height, int accent) {
        g.fill(x + 3, y + 4, x + width + 3, y + height + 4, 0xA0000000);
        g.fill(x, y, x + width, y + height, 0xFF121A20);
        g.outline(x, y, width, height, 0xFF53616B);
        g.fill(x + 2, y + 2, x + width - 2, y + 35, 0xFF27333D);
        g.fill(x + 2, y + 35, x + width - 2, y + 37, accent);
        for (int sx : new int[]{x + 5, x + width - 8}) for (int sy : new int[]{y + 5, y + height - 8}) {
            g.fill(sx, sy, sx + 3, sy + 3, 0xFF71808A); g.fill(sx, sy + 1, sx + 3, sy + 2, 0xFF1D252B);
        }
    }
    static void slot(GuiGraphicsExtractor g, int x, int y) { g.fill(x - 1, y - 1, x + 17, y + 17, 0xFF53616B); g.fill(x, y, x + 16, y + 16, 0xFF080E12); }
    static final class Key extends Button {
        private final int accent;
        Key(int x, int y, int width, String label, int accent, OnPress action) { super(x, y, width, 22, Component.literal(label), action, DEFAULT_NARRATION); this.accent = accent; }
        @Override protected void extractContents(GuiGraphicsExtractor g, int mouseX, int mouseY, float delta) {
            boolean hover = isHoveredOrFocused();
            g.fill(getX(), getY(), getRight(), getBottom(), active ? hover ? 0xFF344B56 : 0xFF25343E : 0xFF1B242B);
            g.outline(getX(), getY(), getWidth(), getHeight(), active && hover ? accent : 0xFF4B5C66);
            g.fill(getX() + 1, getY() + 1, getX() + 3, getBottom() - 1, active ? accent : 0xFF47535C);
            var font = Minecraft.getInstance().font;
            String text = getMessage().getString();
            if (font.width(text) > getWidth() - 14) text = font.plainSubstrByWidth(text, getWidth() - 22) + "…";
            g.centeredText(font, text, getX() + getWidth() / 2, getY() + 7, active ? TEXT : MUTED);
        }
    }
}
