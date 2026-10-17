package dervogel101.de.emulator_i86_crafted.client.screen;

import dervogel101.de.emulator_i86_crafted.ModContent;
import dervogel101.de.emulator_i86_crafted.network.*;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.*;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.item.ItemStack;
import java.util.*;
import java.util.function.Supplier;

public final class ControlPanelScreen extends Screen {
    private ControlPanel panel;
    private final List<Placed> controls = new ArrayList<>();
    private final List<Label> labels = new ArrayList<>();
    private final Map<String, Supplier<String>> values = new LinkedHashMap<>();
    private final Map<String, String> retained = new HashMap<>();
    private List<FormattedCharSequence> info = List.of();
    private int x, y, panelWidth, panelHeight, contentTop, contentBottom, infoWidth, infoHeight, scroll, extent, accent;
    private boolean sent;
    private record Placed(AbstractWidget widget, int y) {}
    private record Label(String text, int x, int y, int width) {}
    public ControlPanelScreen(ControlPanel panel) { super(Component.literal(panel.title())); this.panel = panel; }
    public ControlPanel model() { return panel; }
    public void update(ControlPanel next) {
        if (panel.kind() != next.kind() || !panel.token().equals(next.token())) return;
        boolean follow = scroll >= Math.max(0, extent - (contentBottom - contentTop));
        panel = next;
        rebuildWidgets();
        if (follow) scroll = Math.max(0, extent - (contentBottom - contentTop));
        positionControls();
    }
    @Override protected void init() {
        values.forEach((key, value) -> retained.put(key, value.get()));
        controls.clear(); labels.clear(); values.clear();
        accent = panel.icon().equals("access_terminal") ? 0xFF82DC8A : panel.icon().equals("interrupt_controller") || panel.kind() == ControlPanel.Kind.BURNER ? 0xFFE7BA63 : PanelStyle.CYAN;
        panelWidth = Math.min(600, width - 16); panelHeight = Math.min(420, height - 16); x = (width - panelWidth) / 2; y = (height - panelHeight) / 2;
        contentTop = y + 46; contentBottom = y + panelHeight - 39;
        boolean columns = panelWidth >= 470;
        int controlWidth = columns ? 220 : panelWidth - 34;
        infoWidth = columns ? panelWidth - controlWidth - 46 : panelWidth - 34;
        info = font.split(Component.literal(panel.body()), infoWidth - 14); infoHeight = 27 + info.size() * 11;
        int controlX = columns ? x + panelWidth - controlWidth - 16 : x + 17;
        int cy = columns ? 0 : infoHeight + 14;
        for (var field : panel.fields()) {
            String initial = retained.getOrDefault(field.key(), field.value());
            var wrapped = font.split(Component.literal(field.label()), controlWidth);
            labels.add(new Label(field.label(), controlX, cy, controlWidth));
            cy += wrapped.size() * 10 + 4;
            AbstractWidget widget;
            if (field.key().equals("description")) {
                var edit = MultiLineEditBox.builder().setX(controlX).setY(contentTop + cy).setTextColor(PanelStyle.TEXT).setTextShadow(false)
                        .build(font, controlWidth, 66, Component.literal(field.label()));
                edit.setCharacterLimit(field.maximum()); edit.setValue(initial); edit.active = !panel.actions().isEmpty();
                widget = edit; values.put(field.key(), edit::getValue);
            } else if (field.options().isEmpty()) {
                var edit = new EditBox(font, controlX + 4, contentTop + cy, controlWidth - 8, 20, Component.literal(field.label()));
                edit.setMaxLength(field.maximum()); edit.setValue(initial); edit.setTextColor(PanelStyle.TEXT);
                edit.setEditable(!panel.actions().isEmpty()); widget = edit; values.put(field.key(), edit::getValue);
            } else {
                int[] selected = {Math.max(0, field.options().indexOf(initial))};
                var choice = new PanelStyle.Key(controlX, contentTop + cy, controlWidth, choice(field.key(), field.options().get(selected[0])), accent, button -> {
                    selected[0] = (selected[0] + 1) % field.options().size(); button.setMessage(Component.literal(choice(field.key(), field.options().get(selected[0]))));
                });
                choice.active = !panel.actions().isEmpty(); widget = choice; values.put(field.key(), () -> field.options().get(selected[0]));
            }
            controls.add(new Placed(widget, cy)); addWidget(widget); cy += widget.getHeight() + 14;
        }
        if (!panel.fields().isEmpty()) cy += 8;
        for (var action : panel.actions()) {
            var button = new PanelStyle.Key(controlX, contentTop + cy, controlWidth, action.label(), accent, ignored -> submit(action));
            controls.add(new Placed(button, cy)); addWidget(button); cy += 28;
        }
        extent = Math.max(infoHeight, cy);
        addRenderableWidget(new PanelStyle.Key(x + panelWidth - 112, y + panelHeight - 30, 96, "Close", accent, ignored -> onClose()));
        positionControls();
    }
    private static String choice(String key, String value) {
        if (key.equals("sides")) return switch (value) { case "0" -> "Off"; case "1" -> "Front clicks"; case "2" -> "Back clicks"; default -> "Front + back"; };
        if (key.equals("irq")) return switch (value) { case "DISABLED" -> "Disabled"; case "HIGH" -> "Rising edge"; case "LOW" -> "Falling edge"; case "BOTH" -> "Both edges"; case "ANALOG" -> "Every level change"; default -> value; };
        return value.equals("input") ? "Input" : value.equals("output") ? "Output" : value;
    }
    private void submit(ControlPanel.Action action) {
        var data = new HashMap<>(action.values()); values.forEach((key, value) -> data.put(key, value.get()));
        sent = true; ClientPlayNetworking.send(new PanelPackets.Submit(panel.kind(), panel.token(), action.action(), data)); minecraft.gui.setScreen(null);
    }
    private void positionControls() {
        scroll = Math.clamp(scroll, 0, Math.max(0, extent - (contentBottom - contentTop)));
        for (var placed : controls) {
            var widget = placed.widget(); widget.setY(contentTop + placed.y() - scroll);
            widget.visible = true;
        }
    }
    @Override public boolean mouseScrolled(double mouseX, double mouseY, double horizontal, double vertical) {
        if (mouseX >= x && mouseX < x + panelWidth && mouseY >= contentTop && mouseY < contentBottom) {
            for (var placed : controls) if (placed.widget() instanceof MultiLineEditBox edit && edit.isMouseOver(mouseX, mouseY)) {
                double before = edit.scrollAmount();
                edit.mouseScrolled(mouseX, mouseY, horizontal, vertical);
                if (edit.scrollAmount() != before) return true;
            }
            scroll -= (int) (vertical * 28); positionControls(); return true;
        }
        return super.mouseScrolled(mouseX, mouseY, horizontal, vertical);
    }
    @Override public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        for (var placed : controls) placed.widget().visible = event.y() >= contentTop && event.y() < contentBottom
                && placed.widget().getY() >= contentTop && placed.widget().getBottom() <= contentBottom;
        boolean handled = super.mouseClicked(event, doubleClick);
        controls.forEach(placed -> placed.widget().visible = true);
        return handled;
    }
    @Override public boolean keyPressed(KeyEvent event) {
        boolean handled = super.keyPressed(event);
        if (getFocused() instanceof AbstractWidget widget) {
            var placed = controls.stream().filter(p -> p.widget() == widget).findFirst().orElse(null);
            if (placed != null) {
                int top = placed.y(), bottom = top + widget.getHeight(), viewport = contentBottom - contentTop;
                if (top < scroll) scroll = top; else if (bottom > scroll + viewport) scroll = bottom - viewport;
                positionControls();
            }
        }
        return handled;
    }
    @Override public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float delta) {
        g.fill(0, 0, width, height, 0x99050B10);
        PanelStyle.frame(g, x, y, panelWidth, panelHeight, accent);
        var item = ModContent.ITEMS.getOrDefault(panel.icon(), ModContent.ITEMS.get("rom_chip"));
        g.item(new ItemStack(item), x + 17, y + 11);
        g.text(font, "I86 / " + (panel.kind() == ControlPanel.Kind.BURNER ? "ROM PROGRAMMER" : "CONTROL BUS"), x + 42, y + 7, PanelStyle.MUTED);
        g.text(font, font.plainSubstrByWidth(panel.title(), panelWidth - 62), x + 42, y + 20, PanelStyle.TEXT);
        g.fill(x + 12, contentTop - 2, x + 22 + infoWidth, contentBottom + 1, 0xFF080F13);
        g.enableScissor(x + 12, contentTop, x + panelWidth - 12, contentBottom);
        int textY = contentTop + 7 - scroll;
        g.text(font, panel.icon().equals("access_terminal") ? "TERMINAL OUTPUT" : "DEVICE STATUS", x + 19, textY, accent); textY += 19;
        for (var line : info) { g.text(font, line, x + 19, textY, panel.icon().equals("access_terminal") ? accent : PanelStyle.TEXT); textY += 11; }
        for (var label : labels) g.textWithWordWrap(font, Component.literal(label.text()), label.x(), contentTop + label.y() - scroll, label.width(), PanelStyle.MUTED);
        for (var placed : controls) if (placed.widget().getBottom() >= contentTop && placed.widget().getY() <= contentBottom) placed.widget().extractRenderState(g, mouseX, mouseY, delta);
        g.disableScissor();
        if (extent > contentBottom - contentTop) {
            int track = contentBottom - contentTop, thumb = Math.max(12, track * track / extent);
            int thumbY = contentTop + scroll * (track - thumb) / Math.max(1, extent - track);
            g.fill(x + panelWidth - 7, contentTop, x + panelWidth - 5, contentBottom, 0xFF25343E);
            g.fill(x + panelWidth - 7, thumbY, x + panelWidth - 5, thumbY + thumb, accent);
        }
        g.text(font, extent > contentBottom - contentTop ? "Scroll to view more" : "SERVER LINK / CONNECTED", x + 17, y + panelHeight - 22, PanelStyle.MUTED);
        super.extractRenderState(g, mouseX, mouseY, delta);
    }
    @Override public boolean isPauseScreen() { return false; }
    @Override public void removed() {
        if (!sent && minecraft.getConnection() != null) ClientPlayNetworking.send(new PanelPackets.Submit(panel.kind(), panel.token(), "__close", Map.of()));
    }
}
