package ru.an0n.authprobe.screen;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import ru.an0n.authprobe.BotController;
import ru.an0n.authprobe.PatternSlot;
import ru.an0n.authprobe.ProbeConfig;

/**
 * Asks which pattern bank a chat line belongs to. Opened by clicking a line in the chat pane,
 * so a server's wording can be captured right after a test instead of retyped.
 *
 * @author AN0N
 */
public final class PatternPickScreen extends Screen {
    /**
     * Parent screen to return to.
     */
    private final Screen parent;

    /**
     * Line the operator clicked.
     */
    private final String line;

    /**
     * Creates the picker.
     *
     * @param parent Parent screen
     * @param line   Chat line to add
     */
    public PatternPickScreen(Screen parent, String line) {
        super(Component.translatable("authprobe.pick.title"));
        this.parent = parent;
        this.line = line == null ? "" : line;
    }

    @Override
    protected void init() {
        assert this.minecraft != null;
        PatternSlot[] slots = PatternSlot.values();
        int columns = 2;
        int buttonWidth = Math.min(190, (this.width - 30) / columns);
        int startX = this.width / 2 - (buttonWidth * columns + 8) / 2;
        int firstY = 88;

        for (int i = 0; i < slots.length; i++) {
            PatternSlot slot = slots[i];
            int column = i % columns;
            int row = i / columns;
            this.addRenderableWidget(Button.builder(Component.translatable(slot.labelKey()),
                            pressed -> this.add(slot))
                    .bounds(startX + column * (buttonWidth + 8), firstY + row * 24, buttonWidth, 20).build());
        }

        int rows = (slots.length + columns - 1) / columns;
        this.addRenderableWidget(Button.builder(Component.translatable("authprobe.patterns.back"),
                        pressed -> this.onClose())
                .bounds(this.width / 2 - 60, firstY + rows * 24 + 8, 120, 20).build());
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float delta) {
        this.renderBackground(graphics);
        super.render(graphics, mouseX, mouseY, delta);

        graphics.drawCenteredString(this.font, this.title, this.width / 2, 18, 0xFF_FF_FF_FF);
        graphics.drawCenteredString(this.font, Component.translatable("authprobe.pick.hint"),
                this.width / 2, 34, 0xFF_A0_A0_A0);

        // The captured line, wrapped so a long server message still fits.
        int y = 52;
        int width = Math.max(60, this.width - 40);
        String rest = this.line;
        for (int guard = 0; !rest.isEmpty() && guard < 3; guard++) {
            String part = this.font.plainSubstrByWidth(rest, width);
            if (part.isEmpty()) break;
            if (part.length() < rest.length()) {
                int space = part.lastIndexOf(' ');
                if (space > 0) part = part.substring(0, space);
            }
            graphics.drawCenteredString(this.font, part, this.width / 2, y, 0xFF_FF_D7_00);
            y += 10;
            rest = rest.substring(part.length()).stripLeading();
        }
    }

    @Override
    public void onClose() {
        assert this.minecraft != null;
        this.minecraft.setScreen(this.parent);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    /**
     * Adds the line to the chosen bank and returns to the parent screen.
     *
     * @param slot Target bank
     */
    private void add(PatternSlot slot) {
        ProbeConfig config = ProbeConfig.INSTANCE;
        boolean added = config.addPattern(slot, this.line);
        config.save();
        String bank = Component.translatable(slot.labelKey()).getString();
        BotController.announce((added ? "добавлено в «" : "уже есть в «") + bank + "»");
        if (this.minecraft != null) this.minecraft.setScreen(this.parent);
    }
}