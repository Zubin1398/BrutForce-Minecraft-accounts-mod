package ru.an0n.authprobe.screen;

import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;
import ru.an0n.authprobe.BotController;
import ru.an0n.authprobe.PatternBank;
import ru.an0n.authprobe.ProbeConfig;

import java.util.ArrayList;
import java.util.List;

/**
 * Editor for the pattern banks, on three pages: the server's chat answers, the reasons a
 * disconnect is blamed on the nickname or on the IP/proxy, and an advanced page that holds
 * the 2FA and captcha phrases plus their detection switches.
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
     * Advanced page with 2FA and captcha settings.
     */
    private static final int PAGE_ADVANCED = 2;

    /**
     * Amount of pages.
     */
    private static final int PAGES = 3;

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
     * Busy-nickname reason draft.
     */
    private List<String> draftNickOnline;

    /**
     * Invalid-nickname reason draft.
     */
    private List<String> draftNickInvalid;

    /**
     * IP/proxy kick reason draft.
     */
    private List<String> draftProxyKicks;

    /**
     * Two-factor phrase draft.
     */
    private List<String> draftTwoFactor;

    /**
     * Captcha phrase draft.
     */
    private List<String> draftCaptcha;

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
     * Busy-nickname reason editor.
     */
    private MultilineTextBox nickOnlineBox;

    /**
     * Invalid-nickname reason editor.
     */
    private MultilineTextBox nickInvalidBox;

    /**
     * IP/proxy kick reason editor.
     */
    private MultilineTextBox proxyKicksBox;

    /**
     * Two-factor phrase editor.
     */
    private MultilineTextBox twoFactorBox;

    /**
     * Captcha phrase editor.
     */
    private MultilineTextBox captchaBox;

    /**
     * Page switch button.
     */
    private Button pageButton;

    /**
     * 2FA detection master switch.
     */
    private Button twoFactorButton;

    /**
     * Pre-start 2FA switch.
     */
    private Button twoFactorBeforeButton;

    /**
     * Pre-start captcha switch.
     */
    private Button captchaBeforeButton;

    /**
     * Creates a new patterns screen.
     *
     * @param parent Parent screen
     */
    public ProbePatternsScreen(Screen parent) {
        super(Component.translatable("authprobe.patterns.title.server",
                Component.literal(ProbeConfig.INSTANCE.address().isBlank()
                                ? "—" : ProbeConfig.INSTANCE.address())
                        .withStyle(ChatFormatting.AQUA),
                ProbeConfig.INSTANCE.activeServer() + 1,
                ProbeConfig.INSTANCE.serverCount()));
        this.parent = parent;

        ProbeConfig config = ProbeConfig.INSTANCE;
        PatternBank bank = config.patterns();
        this.draftSuccess = new ArrayList<>(bank.success());
        this.draftFail = new ArrayList<>(bank.fail());
        this.draftWait = new ArrayList<>(bank.pause());
        this.draftKick = new ArrayList<>(bank.kick());
        this.draftNickOnline = new ArrayList<>(config.nickOnline());
        this.draftNickInvalid = new ArrayList<>(config.nickInvalid());
        this.draftProxyKicks = new ArrayList<>(config.proxyKicks());
        this.draftTwoFactor = new ArrayList<>(config.twoFactorPatterns());
        this.draftCaptcha = new ArrayList<>(config.captchaPatterns());
    }

    @Override
    protected void init() {
        assert this.minecraft != null;

        // Anything typed since the last page switch survives a resize.
        this.flushCurrentPage();

        switch (this.page) {
            case PAGE_CHAT -> this.buildChatPage();
            case PAGE_KICKS -> this.buildKicksPage();
            default -> this.buildAdvancedPage();
        }

        // Page switch, save and reset, laid out so a narrow window keeps them on screen.
        int buttonY = this.height - 26;
        int total = 150 + 6 + 110 + 6 + 120;
        int startX = Math.max(4, (this.width - total) / 2);
        this.pageButton = this.addRenderableWidget(Button.builder(this.pageLabel(), pressed -> {
                    this.flushCurrentPage();
                    this.page = (this.page + 1) % PAGES;
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
     * Builds the three kick-reason editors.
     */
    private void buildKicksPage() {
        int columnWidth = Math.max(60, this.width / 3 - 12);
        int top = 42;
        int boxHeight = Math.max(30, this.height - top - 62);

        this.nickOnlineBox = this.addBox(8, top, columnWidth, boxHeight,
                "authprobe.patterns.nickonline", this.draftNickOnline);
        this.nickInvalidBox = this.addBox(8 + columnWidth + 8, top, columnWidth, boxHeight,
                "authprobe.patterns.nickinvalid", this.draftNickInvalid);
        this.proxyKicksBox = this.addBox(8 + (columnWidth + 8) * 2, top, columnWidth, boxHeight,
                "authprobe.patterns.proxykicks", this.draftProxyKicks);
    }

    /**
     * Builds the advanced page: two phrase editors, three switches and the clear-marks button.
     */
    private void buildAdvancedPage() {
        int columnWidth = Math.max(80, this.width / 2 - 14);
        int top = 42;
        int boxHeight = Math.max(30, this.height - top - 174);

        this.twoFactorBox = this.addBox(8, top, columnWidth, boxHeight,
                "authprobe.patterns.twofactor", this.draftTwoFactor);
        this.captchaBox = this.addBox(8 + columnWidth + 10, top, columnWidth, boxHeight,
                "authprobe.patterns.captcha", this.draftCaptcha);

        int rowWidth = Math.max(120, this.width - 16);
        int firstRow = this.height - 126;
        this.twoFactorButton = this.addRenderableWidget(Button.builder(Component.empty(),
                pressed -> this.toggleTwoFactor()).bounds(8, firstRow, rowWidth, 20).build());
        this.twoFactorBeforeButton = this.addRenderableWidget(Button.builder(Component.empty(),
                pressed -> this.toggleTwoFactorBefore()).bounds(8, firstRow + 24, rowWidth, 20).build());
        this.captchaBeforeButton = this.addRenderableWidget(Button.builder(Component.empty(),
                pressed -> this.toggleCaptchaBefore()).bounds(8, firstRow + 48, rowWidth, 20).build());

        this.twoFactorButton.setTooltip(tooltip("authprobe.patterns.option.twofactor.tip"));
        this.twoFactorBeforeButton.setTooltip(tooltip("authprobe.patterns.option.twobefore.tip"));
        this.captchaBeforeButton.setTooltip(tooltip("authprobe.patterns.option.captchabefore.tip"));

        this.addRenderableWidget(Button.builder(Component.translatable("authprobe.patterns.clearMarks"),
                        pressed -> this.clearMarks())
                .bounds(8, this.height - 52, rowWidth, 20).build());

        this.refreshAdvanced();
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
        if (this.page == PAGE_ADVANCED) {
            graphics.drawCenteredString(this.font, tr("authprobe.patterns.advanced.warning"),
                    this.width / 2, 20, 0xFF_FF_AA_33);
        } else {
            graphics.drawCenteredString(this.font, tr("authprobe.patterns.hint"),
                    this.width / 2, 20, 0xFF_A0_A0_A0);
        }

        switch (this.page) {
            case PAGE_CHAT -> {
                int columnWidth = Math.max(50, this.width / 4 - 10);
                String[] labels = {"authprobe.patterns.success.desc", "authprobe.patterns.fail.desc",
                        "authprobe.patterns.wait.desc", "authprobe.patterns.kick.desc"};
                int[] colors = {0xFF_88_FF_88, 0xFF_FF_88_88, 0xFF_88_CC_FF, 0xFF_FF_D7_00};
                for (int i = 0; i < labels.length; i++) {
                    int x = 8 + (columnWidth + 8) * i;
                    graphics.drawString(this.font, tr(labels[i]), x, 31, colors[i], false);
                    this.drawBadge(graphics, x, columnWidth, 42, "authprobe.patterns.badge.server", false);
                }
            }
            case PAGE_KICKS -> {
                int columnWidth = Math.max(60, this.width / 3 - 12);
                graphics.drawString(this.font, tr("authprobe.patterns.nickonline.desc"), 8, 31, 0xFF_FF_AA_33, false);
                graphics.drawString(this.font, tr("authprobe.patterns.nickinvalid.desc"),
                        8 + columnWidth + 8, 31, 0xFF_FF_66_66, false);
                graphics.drawString(this.font, tr("authprobe.patterns.proxykicks.desc"),
                        8 + (columnWidth + 8) * 2, 31, 0xFF_88_CC_FF, false);
                for (int i = 0; i < 3; i++) {
                    this.drawBadge(graphics, 8 + (columnWidth + 8) * i, columnWidth, 42,
                            "authprobe.patterns.badge.shared", true);
                }
            }
            default -> {
                int columnWidth = Math.max(80, this.width / 2 - 14);
                graphics.drawString(this.font, tr("authprobe.patterns.twofactor.desc"), 8, 31, 0xFF_FF_66_66, false);
                graphics.drawString(this.font, tr("authprobe.patterns.captcha.desc"),
                        8 + columnWidth + 10, 31, 0xFF_88_CC_FF, false);
                this.drawBadge(graphics, 8, columnWidth, 42, "authprobe.patterns.badge.server", false);
                this.drawBadge(graphics, 8 + columnWidth + 10, columnWidth, 42, "authprobe.patterns.badge.server", false);
            }
        }
    }

    /**
     * Draws a small area badge in the top-right corner of an editor: per-server or shared.
     *
     * @param graphics  Drawing graphics
     * @param boxX      Editor left
     * @param boxWidth  Editor width
     * @param top       Editor top
     * @param key       Badge label key
     * @param shared    Whether the bank is shared by every server
     */
    private void drawBadge(GuiGraphics graphics, int boxX, int boxWidth, int top, String key, boolean shared) {
        int background = shared ? 0xCC_3A_2A_10 : 0xCC_0E_2A_38;
        int border = shared ? 0xFF_B8_86_0B : 0xFF_38_BD_F8;
        int textColor = shared ? 0xFF_FF_CC_66 : 0xFF_9F_D8_FF;

        String text = tr(key).getString();
        int width = this.font.width(text) + 6;
        int x = boxX + boxWidth - width - 3;
        int y = top + 3;
        graphics.fill(x, y, x + width, y + 11, border);
        graphics.fill(x + 1, y + 1, x + width - 1, y + 10, background);
        graphics.drawString(this.font, text, x + 3, y + 2, textColor, false);
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
     * @return Editors
     */
    private List<MultilineTextBox> editors() {
        List<MultilineTextBox> boxes = new ArrayList<>();
        switch (this.page) {
            case PAGE_CHAT -> {
                boxes.add(this.successBox);
                boxes.add(this.failBox);
                boxes.add(this.waitBox);
                boxes.add(this.kickBox);
            }
            case PAGE_KICKS -> {
                boxes.add(this.nickOnlineBox);
                boxes.add(this.nickInvalidBox);
                boxes.add(this.proxyKicksBox);
            }
            default -> {
                boxes.add(this.twoFactorBox);
                boxes.add(this.captchaBox);
            }
        }
        boxes.removeIf(java.util.Objects::isNull);
        return boxes;
    }

    /**
     * Copies the current editors into the drafts.
     */
    private void flushCurrentPage() {
        switch (this.page) {
            case PAGE_CHAT -> {
                if (this.successBox == null) return;
                this.draftSuccess = splitLines(this.successBox.getValue());
                this.draftFail = splitLines(this.failBox.getValue());
                this.draftWait = splitLines(this.waitBox.getValue());
                this.draftKick = splitLines(this.kickBox.getValue());
            }
            case PAGE_KICKS -> {
                if (this.nickOnlineBox == null) return;
                this.draftNickOnline = splitLines(this.nickOnlineBox.getValue());
                this.draftNickInvalid = splitLines(this.nickInvalidBox.getValue());
                this.draftProxyKicks = splitLines(this.proxyKicksBox.getValue());
            }
            default -> {
                if (this.twoFactorBox == null) return;
                this.draftTwoFactor = splitLines(this.twoFactorBox.getValue());
                this.draftCaptcha = splitLines(this.captchaBox.getValue());
            }
        }
    }

    /**
     * Writes every draft into the config, saves it and reports obvious pattern mistakes.
     */
    private void save() {
        this.flushCurrentPage();
        ProbeConfig config = ProbeConfig.INSTANCE;
        config.patterns(new PatternBank(this.draftSuccess, this.draftFail, this.draftWait, this.draftKick));
        config.nickOnline(this.draftNickOnline);
        config.nickInvalid(this.draftNickInvalid);
        config.proxyKicks(this.draftProxyKicks);
        config.twoFactorPatterns(this.draftTwoFactor);
        config.captchaPatterns(this.draftCaptcha);
        config.save();

        // The check lives in the config, so the same warnings fire for a hand-edited file.
        for (String warning : config.patternWarnings()) {
            BotController.warn(warning);
        }
    }

    /**
     * Restores the defaults of the current page.
     */
    private void resetPage() {
        switch (this.page) {
            case PAGE_CHAT -> {
                PatternBank bank = PatternBank.defaults();
                this.successBox.setValue(String.join("\n", bank.success()));
                this.failBox.setValue(String.join("\n", bank.fail()));
                this.waitBox.setValue(String.join("\n", bank.pause()));
                this.kickBox.setValue(String.join("\n", bank.kick()));
            }
            case PAGE_KICKS -> {
                this.nickOnlineBox.setValue(String.join("\n", PatternBank.defaultNickOnline()));
                this.nickInvalidBox.setValue(String.join("\n", PatternBank.defaultNickInvalid()));
                this.proxyKicksBox.setValue(String.join("\n", PatternBank.defaultProxyKicks()));
            }
            default -> {
                this.twoFactorBox.setValue(String.join("\n", PatternBank.defaultTwoFactor()));
                this.captchaBox.setValue(String.join("\n", PatternBank.defaultCaptcha()));
                ProbeConfig.INSTANCE.twoFactorDetection(true);
                ProbeConfig.INSTANCE.twoFactorBeforeStart(false);
                ProbeConfig.INSTANCE.captchaBeforeStart(false);
                this.refreshAdvanced();
            }
        }
    }

    /**
     * Turns 2FA detection on or off. Turning it off is worth a warning: on a 2FA server the
     * password lists would be spent one by one.
     */
    private void toggleTwoFactor() {
        ProbeConfig config = ProbeConfig.INSTANCE;
        config.twoFactorDetection(!config.twoFactorDetection());
        if (!config.twoFactorDetection()) {
            BotController.warn("обнаружение 2FA выключено — на серверах с 2FA пароли будут тратиться");
        }
        this.refreshAdvanced();
    }

    /**
     * Turns pre-start 2FA detection on or off.
     */
    private void toggleTwoFactorBefore() {
        ProbeConfig config = ProbeConfig.INSTANCE;
        config.twoFactorBeforeStart(!config.twoFactorBeforeStart());
        this.refreshAdvanced();
    }

    /**
     * Turns pre-start captcha pausing on or off.
     */
    private void toggleCaptchaBefore() {
        ProbeConfig config = ProbeConfig.INSTANCE;
        config.captchaBeforeStart(!config.captchaBeforeStart());
        this.refreshAdvanced();
    }

    /**
     * Clears every skip mark of the active server.
     */
    private void clearMarks() {
        int cleared = ProbeConfig.INSTANCE.clearSkips();
        ProbeConfig.INSTANCE.save();
        BotController.announce(cleared == 0
                ? "меток нет"
                : "снято меток: " + cleared);
    }

    /**
     * Refreshes the advanced page labels.
     */
    private void refreshAdvanced() {
        if (this.twoFactorButton == null) return;
        ProbeConfig config = ProbeConfig.INSTANCE;
        this.twoFactorButton.setMessage(tr("authprobe.patterns.option.twofactor", onOff(config.twoFactorDetection())));
        this.twoFactorBeforeButton.setMessage(tr("authprobe.patterns.option.twobefore", onOff(config.twoFactorBeforeStart())));
        this.captchaBeforeButton.setMessage(tr("authprobe.patterns.option.captchabefore", onOff(config.captchaBeforeStart())));
        this.twoFactorBeforeButton.active = config.twoFactorDetection();
    }

    /**
     * Builds the page switch label: always the next page.
     *
     * @return Label
     */
    private Component pageLabel() {
        return switch (this.page) {
            case PAGE_CHAT -> tr("authprobe.patterns.page.toKicks");
            case PAGE_KICKS -> tr("authprobe.patterns.page.toAdvanced");
            default -> tr("authprobe.patterns.page.toChat");
        };
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
     * Translates a key with one argument.
     *
     * @param key  Translation key
     * @param args Arguments
     * @return Component
     */
    private static Component tr(String key, Object... args) {
        return Component.translatable(key, args);
    }

    /**
     * Builds the on/off word, coloured.
     *
     * @param value Value
     * @return Component
     */
    private static Component onOff(boolean value) {
        return Component.translatable(value ? "authprobe.on" : "authprobe.off")
                .withStyle(value ? ChatFormatting.GREEN : ChatFormatting.RED);
    }

    /**
     * Builds a tooltip from a translation key.
     *
     * @param key Translation key
     * @return Tooltip
     */
    private static Tooltip tooltip(String key) {
        return Tooltip.create(Component.translatable(key));
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
            String value = PatternBank.normalize(line).trim();
            if (value.isEmpty()) continue;
            patterns.add(value);
        }
        return patterns;
    }
}