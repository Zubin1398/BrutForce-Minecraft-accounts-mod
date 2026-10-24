package ru.an0n.authprobe.screen;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;
import ru.an0n.authprobe.PatternBank;
import ru.an0n.authprobe.ProbeConfig;

import java.util.ArrayList;
import java.util.List;

/**
 * Editor for the chat pattern banks. Every server words its login messages differently,
 * so these are editable rather than baked in.
 *
 * @author AN0N
 */
public final class ProbePatternsScreen extends Screen {
    /**
     * Parent screen.
     */
    private final Screen parent;

    /**
     * Success bank editor.
     */
    private MultilineTextBox successBox;

    /**
     * Fail bank editor.
     */
    private MultilineTextBox failBox;

    /**
     * Kick bank editor.
     */
    private MultilineTextBox kickBox;

    /**
     * Creates a new patterns screen.
     *
     * @param parent Parent screen
     */
    public ProbePatternsScreen(Screen parent) {
        super(Component.translatable("authprobe.patterns.title"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        assert this.minecraft != null;
        PatternBank bank = ProbeConfig.INSTANCE.patterns();

        // Three columns.
        int columnWidth = Math.max(60, this.width / 3 - 12);
        int firstX = 8;
        int secondX = firstX + columnWidth + 8;
        int thirdX = secondX + columnWidth + 8;
        int top = 42;
        int boxHeight = Math.max(30, this.height - top - 34);

        this.successBox = new MultilineTextBox(this.font, firstX, top, columnWidth, boxHeight,
                Component.translatable("authprobe.patterns.success"));
        this.successBox.setValue(String.join("\n", bank.success()));
        this.addRenderableWidget(this.successBox);

        this.failBox = new MultilineTextBox(this.font, secondX, top, columnWidth, boxHeight,
                Component.translatable("authprobe.patterns.fail"));
        this.failBox.setValue(String.join("\n", bank.fail()));
        this.addRenderableWidget(this.failBox);

        this.kickBox = new MultilineTextBox(this.font, thirdX, top, columnWidth, boxHeight,
                Component.translatable("authprobe.patterns.kick"));
        this.kickBox.setValue(String.join("\n", bank.kick()));
        this.addRenderableWidget(this.kickBox);

        // Save and reset.
        int buttonY = this.height - 26;
        this.addRenderableWidget(Button.builder(Component.translatable("authprobe.patterns.save"), pressed -> {
                    this.save();
                    this.minecraft.setScreen(this.parent);
                })
                .bounds(this.width / 2 - 126, buttonY, 120, 20).build());
        this.addRenderableWidget(Button.builder(Component.translatable("authprobe.patterns.reset"), pressed -> this.apply(PatternBank.defaults()))
                .bounds(this.width / 2 + 6, buttonY, 120, 20).build());
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float delta) {
        this.renderBackground(graphics);
        super.render(graphics, mouseX, mouseY, delta);

        // Title, hint and per-column labels.
        graphics.drawCenteredString(this.font, this.title, this.width / 2, 10, 0xFF_FF_FF_FF);
        graphics.drawCenteredString(this.font, Component.translatable("authprobe.patterns.hint"), this.width / 2, 20, 0xFF_A0_A0_A0);

        int columnWidth = Math.max(60, this.width / 3 - 12);
        int firstX = 8;
        int secondX = firstX + columnWidth + 8;
        int thirdX = secondX + columnWidth + 8;
        graphics.drawString(this.font, Component.translatable("authprobe.patterns.success.desc"),
                firstX, 31, 0xFF_88_FF_88, false);
        graphics.drawString(this.font, Component.translatable("authprobe.patterns.fail.desc"),
                secondX, 31, 0xFF_FF_88_88, false);
        graphics.drawString(this.font, Component.translatable("authprobe.patterns.kick.desc"),
                thirdX, 31, 0xFF_FF_D7_00, false);
    }

    @Override
    public void onClose() {
        assert this.minecraft != null;
        this.minecraft.setScreen(this.parent);
    }

    @Override
    public boolean keyPressed(int key, int scan, int mods) {
        if (key == GLFW.GLFW_KEY_ENTER || key == GLFW.GLFW_KEY_KP_ENTER) {
            for (MultilineTextBox box : List.of(this.successBox, this.failBox, this.kickBox)) {
                if (box != null && box.isFocused()) {
                    this.setFocused(box);
                    return box.keyPressed(key, scan, mods);
                }
            }
        }
        return super.keyPressed(key, scan, mods);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    /**
     * Writes the editors into the config.
     */
    private void save() {
        ProbeConfig.INSTANCE.patterns(new PatternBank(
                splitLines(this.successBox.getValue()),
                splitLines(this.failBox.getValue()),
                splitLines(this.kickBox.getValue())
        ));
        ProbeConfig.INSTANCE.save();
    }

    /**
     * Writes the bank into the editors.
     *
     * @param bank Target bank
     */
    private void apply(PatternBank bank) {
        this.successBox.setValue(String.join("\n", bank.success()));
        this.failBox.setValue(String.join("\n", bank.fail()));
        this.kickBox.setValue(String.join("\n", bank.kick()));
    }

    /**
     * Splits an editor value into non-blank patterns.
     *
     * @param raw Raw value
     * @return Patterns
     */
    private static List<String> splitLines(String raw) {
        List<String> patterns = new ArrayList<>();
        for (String line : raw.split("\n", -1)) {
            String value = line.trim();
            if (value.isEmpty()) continue;
            patterns.add(value);
        }
        return patterns;
    }
}