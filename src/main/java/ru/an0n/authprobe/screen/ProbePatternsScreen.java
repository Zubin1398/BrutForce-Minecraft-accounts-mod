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
 * Editor for the pattern banks, on two pages: the server's chat answers and the reasons a
 * disconnect is blamed on the nickname or on the IP/proxy. Every server words these
 * differently, so they are editable rather than baked in.
 *
 * @author AN0N
 */
public final class ProbePatternsScreen extends Screen {
    /**
     * Chat answer page.
     */
    private static final int PAGE_CHAT = 0;

    /**
     * Kick reason page.
     */
    private static final int PAGE_KICKS = 1;

    /**
     * Parent screen.
     */
    private final Screen parent;

    /**
     * Page currently shown.
     */
    private int page = PAGE_CHAT;

    /**
     * Success bank draft.
     */
    private List<String> draftSuccess;

    /**
     * Fail bank draft.
     */
    private List<String> draftFail;

    /**
     * Server-pause bank draft.
     */
    private List<String> draftWait;

    /**
     * Kick bank draft.
     */
    private List<String> draftKick;

    /**
     * Nickname kick reason draft.
     */
    private List<String> draftNickKicks;

    /**
     * IP/proxy kick reason draft.
     */
    private List<String> draftProxyKicks;

    /**
     * Success bank editor.
     */
    private MultilineTextBox successBox;

    /**
     * Fail bank editor.
     */
    private MultilineTextBox failBox;

    /**
     * Server-pause bank editor.
     */
    private MultilineTextBox waitBox;

    /**
     * Kick bank editor.
     */
    private MultilineTextBox kickBox;

    /**
     * Nickname kick reason editor.
     */
    private MultilineTextBox nickKicksBox;

    /**
     * IP/proxy kick reason editor.
     */
    private MultilineTextBox proxyKicksBox;

    /**
     * Page switch button.
     */
    private Button pageButton;

    /**
     * Creates a new patterns screen.
     *
     * @param parent Parent screen
     */
    public ProbePatternsScreen(Screen parent) {
        super(Component.translatable("authprobe.patterns.title"));
        this.parent = parent;

        ProbeConfig config = ProbeConfig.INSTANCE;
        PatternBank bank = config.patterns();
        this.draftSuccess = new ArrayList<>(bank.success());
        this.draftFail = new ArrayList<>(bank.fail());
        this.draftWait = new ArrayList<>(bank.pause());
        this.draftKick = new ArrayList<>(bank.kick());
        this.draftNickKicks = new ArrayList<>(config.nickKicks());
        this.draftProxyKicks = new ArrayList<>(config.proxyKicks());
    }

    @Override
    protected void init() {
        assert this.minecraft != null;

        // Anything typed since the last page switch survives a resize.
        this.flushCurrentPage();

        if (this.page == PAGE_CHAT) {
            this.buildChatPage();
        } else {
            this.buildKicksPage();
        }

        // Page switch, save and reset, laid out so a narrow window keeps them on screen.
        int buttonY = this.height - 26;
        int total = 150 + 6 + 110 + 6 + 120;
        int startX = Math.max(4, (this.width - total) / 2);
        this.pageButton = this.addRenderableWidget(Button.builder(this.pageLabel(), pressed -> {
                    this.flushCurrentPage();
                    this.page = this.page == PAGE_CHAT ? PAGE_KICKS : PAGE_CHAT;
                    this.rebuildWidgets();
                })
                .bounds(startX, buttonY, 150, 20).build());
        this.addRenderableWidget(Button.builder(Component.translatable("authprobe.patterns.save"), pressed -> {
                    this.save();
                    this.minecraft.setScreen(this.parent);
                })
                .bounds(startX + 156, buttonY, 110, 20).build());
        this.addRenderableWidget(Button.builder(Component.translatable("authprobe.patterns.reset"), pressed -> this.resetPage())
                .bounds(startX + 272, buttonY, 120, 20).build());
    }

    /**
     * Builds the four chat-answer editors.
     */
    private void buildChatPage() {
        int columnWidth = Math.max(50, this.width / 4 - 10);
        int top = 42;
        int boxHeight = Math.max(30, this.height - top - 62);

        this.successBox = this.addBox(8, top, columnWidth, boxHeight,
                "authprobe.patterns.success", this.draftSuccess);
        this.failBox = this.addBox(8 + columnWidth + 8, top, columnWidth, boxHeight,
                "authprobe.patterns.fail", this.draftFail);
        this.waitBox = this.addBox(8 + (columnWidth + 8) * 2, top, columnWidth, boxHeight,
                "authprobe.patterns.wait", this.draftWait);
        this.kickBox = this.addBox(8 + (columnWidth + 8) * 3, top, columnWidth, boxHeight,
                "authprobe.patterns.kick", this.draftKick);
    }

    /**
     * Builds the two kick-reason editors.
     */
    private void buildKicksPage() {
        int columnWidth = Math.max(80, this.width / 2 - 14);
        int top = 42;
        int boxHeight = Math.max(30, this.height - top - 62);

        this.nickKicksBox = this.addBox(8, top, columnWidth, boxHeight,
                "authprobe.patterns.nickkicks", this.draftNickKicks);
        this.proxyKicksBox = this.addBox(8 + columnWidth + 10, top, columnWidth, boxHeight,
                "authprobe.patterns.proxykicks", this.draftProxyKicks);
    }

    /**
     * Creates and registers one editor.
     *
     * @param x      Left
     * @param y      Top
     * @param width  Width
     * @param height Height
     * @param key    Label key
     * @param values Starting lines
     * @return Editor
     */
    private MultilineTextBox addBox(int x, int y, int width, int height, String key, List<String> values) {
        MultilineTextBox box = new MultilineTextBox(this.font, x, y, width, height, tr(key));
        box.setValue(String.join("\n", values));
        this.addRenderableWidget(box);
        return box;
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float delta) {
        this.renderBackground(graphics);
        super.render(graphics, mouseX, mouseY, delta);

        // Title, hint and per-column labels.
        graphics.drawCenteredString(this.font, this.title, this.width / 2, 10, 0xFF_FF_FF_FF);
        graphics.drawCenteredString(this.font, tr("authprobe.patterns.hint"), this.width / 2, 20, 0xFF_A0_A0_A0);

        if (this.page == PAGE_CHAT) {
            int columnWidth = Math.max(50, this.width / 4 - 10);
            String[] labels = {"authprobe.patterns.success.desc", "authprobe.patterns.fail.desc",
                    "authprobe.patterns.wait.desc", "authprobe.patterns.kick.desc"};
            int[] colors = {0xFF_88_FF_88, 0xFF_FF_88_88, 0xFF_88_CC_FF, 0xFF_FF_D7_00};
            for (int i = 0; i < labels.length; i++) {
                graphics.drawString(this.font, tr(labels[i]), 8 + (columnWidth + 8) * i, 31, colors[i], false);
            }
        } else {
            int columnWidth = Math.max(80, this.width / 2 - 14);
            graphics.drawString(this.font, tr("authprobe.patterns.nickkicks.desc"), 8, 31, 0xFF_FF_D7_00, false);
            graphics.drawString(this.font, tr("authprobe.patterns.proxykicks.desc"),
                    8 + columnWidth + 10, 31, 0xFF_88_CC_FF, false);
        }
    }

    @Override
    public void onClose() {
        assert this.minecraft != null;
        this.minecraft.setScreen(this.parent);
    }

    @Override
    public boolean keyPressed(int key, int scan, int mods) {
        if (key == GLFW.GLFW_KEY_ENTER || key == GLFW.GLFW_KEY_KP_ENTER) {
            for (MultilineTextBox box : this.editors()) {
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
     * Gets the editors of the current page.
     *
     * @return Editors, may contain no nulls
     */
    private List<MultilineTextBox> editors() {
        List<MultilineTextBox> boxes = new ArrayList<>();
        if (this.page == PAGE_CHAT) {
            boxes.add(this.successBox);
            boxes.add(this.failBox);
            boxes.add(this.waitBox);
            boxes.add(this.kickBox);
        } else {
            boxes.add(this.nickKicksBox);
            boxes.add(this.proxyKicksBox);
        }
        boxes.removeIf(java.util.Objects::isNull);
        return boxes;
    }

    /**
     * Copies the current editors into the drafts.
     */
    private void flushCurrentPage() {
        if (this.page == PAGE_CHAT) {
            if (this.successBox == null) return;
            this.draftSuccess = splitLines(this.successBox.getValue());
            this.draftFail = splitLines(this.failBox.getValue());
            this.draftWait = splitLines(this.waitBox.getValue());
            this.draftKick = splitLines(this.kickBox.getValue());
        } else {
            if (this.nickKicksBox == null) return;
            this.draftNickKicks = splitLines(this.nickKicksBox.getValue());
            this.draftProxyKicks = splitLines(this.proxyKicksBox.getValue());
        }
    }

    /**
     * Writes every draft into the config and saves it.
     */
    private void save() {
        this.flushCurrentPage();
        ProbeConfig config = ProbeConfig.INSTANCE;
        config.patterns(new PatternBank(this.draftSuccess, this.draftFail, this.draftWait, this.draftKick));
        config.nickKicks(this.draftNickKicks);
        config.proxyKicks(this.draftProxyKicks);
        config.save();
    }

    /**
     * Restores the defaults of the current page.
     */
    private void resetPage() {
        if (this.page == PAGE_CHAT) {
            PatternBank bank = PatternBank.defaults();
            this.successBox.setValue(String.join("\n", bank.success()));
            this.failBox.setValue(String.join("\n", bank.fail()));
            this.waitBox.setValue(String.join("\n", bank.pause()));
            this.kickBox.setValue(String.join("\n", bank.kick()));
        } else {
            this.nickKicksBox.setValue(String.join("\n", PatternBank.defaultNickKicks()));
            this.proxyKicksBox.setValue(String.join("\n", PatternBank.defaultProxyKicks()));
        }
    }

    /**
     * Builds the page switch label.
     *
     * @return Label
     */
    private Component pageLabel() {
        return this.page == PAGE_CHAT
                ? tr("authprobe.patterns.page.toKicks")
                : tr("authprobe.patterns.page.toChat");
    }

    /**
     * Translates a key.
     *
     * @param key Translation key
     * @return Component
     */
    private static Component tr(String key) {
        return Component.translatable(key);
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