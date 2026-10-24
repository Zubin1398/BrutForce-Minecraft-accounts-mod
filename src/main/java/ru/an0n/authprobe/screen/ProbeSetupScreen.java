package ru.an0n.authprobe.screen;

import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;
import ru.an0n.authprobe.BotController;
import ru.an0n.authprobe.ChatWatcher;
import ru.an0n.authprobe.ProbeConfig;
import ru.an0n.authprobe.ProbeTarget;
import ru.an0n.authprobe.ServerConnector;
import ru.an0n.authprobe.proxy.ProxyTunnel;
import ru.an0n.authprobe.utils.Nicks;
import ru.an0n.authprobe.utils.PasswordList;

import java.util.ArrayList;
import java.util.List;

/**
 * Main setup screen: target, nickname, password list, tuning knobs and the live chat pane.
 *
 * @author AN0N
 */
public final class ProbeSetupScreen extends Screen {
    /**
     * Selectable inter-attempt delays, milliseconds.
     */
    private static final int[] DELAYS = {0, 100, 200, 400, 800, 1200, 1500, 2000, 3000};

    /**
     * Selectable reconnect delays, milliseconds.
     */
    private static final int[] RECONNECTS = {0, 3000, 5000, 8000, 15_000, 30_000};

    /**
     * Parent screen.
     */
    private final Screen parent;

    /**
     * Address box.
     */
    private EditBox addressBox;

    /**
     * Nickname box.
     */
    private EditBox nickBox;

    /**
     * Target navigation and list buttons.
     */
    private Button prevNickButton;

    /**
     * Next target button.
     */
    private Button nextNickButton;

    /**
     * Add target button.
     */
    private Button addNickButton;

    /**
     * Remove target button.
     */
    private Button removeNickButton;

    /**
     * Clear every profile button.
     */
    private Button clearProfilesButton;

    /**
     * Password box.
     */
    private MultilineTextBox passwordBox;

    /**
     * Master mod toggle.
     */
    private Button modButton;

    /**
     * Start button, disabled while the mod is off.
     */
    private Button startButton;

    /**
     * Start macro toggle.
     */
    private Button macroButton;

    /**
     * Option buttons.
     */
    private Button modeButton;

    /**
     * Delay button.
     */
    private Button delayButton;

    /**
     * Reconnect button.
     */
    private Button reconnectButton;

    /**
     * Auto-start button.
     */
    private Button autoStartButton;

    /**
     * Auto-reconnect button.
     */
    private Button autoReconnectButton;

    /**
     * Debug logs button.
     */
    private Button debugButton;

    /**
     * Proxy toggle button.
     */
    private Button proxyButton;

    /**
     * Proxy settings gear button.
     */
    private Button proxyGearButton;

    /**
     * Cached password count, refreshed by the responder.
     */
    private int passwordCount;

    /**
     * Cached address validity.
     */
    private boolean addressValid;

    /**
     * Cached nickname validity.
     */
    private boolean nickValid;

    /**
     * Visual lines scrolled up from the bottom of the chat pane.
     */
    private int chatScroll;

    /**
     * Whether the chat scrollbar thumb is being dragged.
     */
    private boolean chatScrollbarDragging;

    /**
     * Grab offset inside the scrollbar thumb.
     */
    private double chatScrollbarGrab;

    /**
     * Creates a new setup screen.
     *
     * @param parent Parent screen
     */
    public ProbeSetupScreen(Screen parent) {
        super(Component.translatable("authprobe.title"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        assert this.minecraft != null;
        ProbeConfig config = ProbeConfig.INSTANCE;

        // Geometry.
        int leftX = 10;
        int columnWidth = Math.max(80, (this.width - 30) / 2);
        int rightX = leftX + columnWidth + 10;

        // Address.
        this.addressBox = new EditBox(this.font, leftX, 32, columnWidth, 18, Component.translatable("authprobe.address"));
        this.addressBox.setHint(Component.translatable("authprobe.address.hint"));
        this.addressBox.setMaxLength(256);
        this.addressBox.setValue(config.address());
        this.addressBox.setResponder(value -> {
            config.address(value);
            this.addressValid = ServerConnector.isValidAddress(value);
        });
        this.addRenderableWidget(this.addressBox);

        // Nickname with a target selector.
        int nickWidth = Math.max(30, columnWidth - 102);
        this.nickBox = new EditBox(this.font, leftX, 64, nickWidth, 18, Component.translatable("authprobe.nick"));
        this.nickBox.setHint(Component.translatable("authprobe.nick.hint"));
        this.nickBox.setMaxLength(16);
        this.nickBox.setValue(config.nick());
        this.nickBox.setResponder(value -> this.nickValid = Nicks.isValid(value.trim()));
        this.addRenderableWidget(this.nickBox);

        int nickButtonsX = leftX + nickWidth + 2;
        this.prevNickButton = this.addRenderableWidget(Button.builder(Component.literal("<"),
                        pressed -> this.switchTarget(-1))
                .bounds(nickButtonsX, 64, 18, 18).build());
        this.nextNickButton = this.addRenderableWidget(Button.builder(Component.literal(">"),
                        pressed -> this.switchTarget(1))
                .bounds(nickButtonsX + 20, 64, 18, 18).build());
        this.addNickButton = this.addRenderableWidget(Button.builder(Component.literal("+"),
                        pressed -> this.addTarget())
                .bounds(nickButtonsX + 40, 64, 18, 18).build());
        this.removeNickButton = this.addRenderableWidget(Button.builder(Component.literal("-"),
                        pressed -> this.removeTarget())
                .bounds(nickButtonsX + 60, 64, 18, 18).build());
        this.clearProfilesButton = this.addRenderableWidget(Button.builder(
                        Component.literal("X").withStyle(ChatFormatting.RED),
                        pressed -> this.clearProfiles())
                .bounds(nickButtonsX + 80, 64, 18, 18).build());
        this.prevNickButton.setTooltip(tooltip("authprobe.nick.prev.tip"));
        this.nextNickButton.setTooltip(tooltip("authprobe.nick.next.tip"));
        this.addNickButton.setTooltip(tooltip("authprobe.nick.add.tip"));
        this.removeNickButton.setTooltip(tooltip("authprobe.nick.remove.tip"));
        this.clearProfilesButton.setTooltip(tooltip("authprobe.nick.clear.tip"));

        // Passwords.
        int passwordTop = 96;
        // Leave room for the four status lines above the bottom button row.
        int passwordHeight = Math.max(24, this.height - passwordTop - 78);
        this.passwordBox = new MultilineTextBox(this.font, leftX, passwordTop, columnWidth, passwordHeight,
                Component.translatable("authprobe.passwords"));
        this.passwordBox.setHint(Component.translatable("authprobe.passwords.hint").getString());
        this.passwordBox.setMaxLines(PasswordList.MAX + 16);
        this.passwordBox.setMaxLength(200_000);
        this.passwordBox.setValue(String.join("\n", config.passwords()));
        this.passwordBox.setResponder(value -> this.passwordCount = PasswordList.count(value));
        this.addRenderableWidget(this.passwordBox);

        // Options, right column.
        int optionX = rightX;
        int optionWidth = Math.max(60, this.width - rightX - 10);
        int optionY = 22;
        int step = 17;
        this.modButton = this.addRenderableWidget(Button.builder(Component.empty(),
                pressed -> {
                    config.modEnabled(!config.modEnabled());
                    ChatWatcher.setEnabled(config.modEnabled());
                    if (!config.modEnabled()) {
                        ProxyTunnel.closeAll();
                        if (BotController.armed()) BotController.stop();
                    }
                    this.refreshOptions();
                }).bounds(optionX, optionY, optionWidth, 16).build());
        this.macroButton = this.addRenderableWidget(Button.builder(Component.empty(),
                pressed -> {
                    config.macroEnabled(!config.macroEnabled());
                    this.refreshOptions();
                }).bounds(optionX, optionY + step, optionWidth, 16).build());
        this.modeButton = this.addRenderableWidget(Button.builder(Component.empty(),
                pressed -> {
                    config.mode(config.mode().next());
                    this.refreshOptions();
                }).bounds(optionX, optionY + step * 2, optionWidth, 16).build());
        this.delayButton = this.addRenderableWidget(Button.builder(Component.empty(),
                pressed -> {
                    config.delayMs(nextOf(DELAYS, config.delayMs()));
                    this.refreshOptions();
                }).bounds(optionX, optionY + step * 3, optionWidth, 16).build());
        this.reconnectButton = this.addRenderableWidget(Button.builder(Component.empty(),
                pressed -> {
                    config.reconnectDelayMs(nextOf(RECONNECTS, config.reconnectDelayMs()));
                    this.refreshOptions();
                }).bounds(optionX, optionY + step * 4, optionWidth, 16).build());
        this.autoStartButton = this.addRenderableWidget(Button.builder(Component.empty(),
                pressed -> {
                    config.autoStart(!config.autoStart());
                    this.refreshOptions();
                }).bounds(optionX, optionY + step * 5, optionWidth, 16).build());
        this.autoReconnectButton = this.addRenderableWidget(Button.builder(Component.empty(),
                pressed -> {
                    config.autoReconnect(!config.autoReconnect());
                    this.refreshOptions();
                }).bounds(optionX, optionY + step * 8, optionWidth, 16).build());
        this.debugButton = this.addRenderableWidget(Button.builder(Component.empty(),
                pressed -> {
                    config.debugLogs(!config.debugLogs());
                    this.refreshOptions();
                }).bounds(optionX, optionY + step * 6, optionWidth, 16).build());
        int proxyWidth = Math.max(40, optionWidth - 22);
        this.proxyButton = this.addRenderableWidget(Button.builder(Component.empty(),
                pressed -> {
                    config.proxyEnabled(!config.proxyEnabled());
                    this.refreshOptions();
                }).bounds(optionX, optionY + step * 7, proxyWidth, 16).build());
        this.proxyGearButton = this.addRenderableWidget(Button.builder(Component.literal("..."),
                        pressed -> {
                            this.saveFields();
                            this.minecraft.setScreen(new ProxyScreen(this));
                        })
                .bounds(optionX + proxyWidth + 2, optionY + step * 7, 18, 16).build());
        this.modButton.setTooltip(tooltip("authprobe.option.mod.tip"));
        this.macroButton.setTooltip(tooltip("authprobe.option.macro.tip"));
        this.modeButton.setTooltip(tooltip("authprobe.option.mode.tip"));
        this.delayButton.setTooltip(tooltip("authprobe.option.delay.tip"));
        this.reconnectButton.setTooltip(tooltip("authprobe.option.reconnect.tip"));
        this.autoStartButton.setTooltip(tooltip("authprobe.option.autostart.tip"));
        this.autoReconnectButton.setTooltip(tooltip("authprobe.option.autoreconnect.tip"));
        this.debugButton.setTooltip(tooltip("authprobe.option.debug.tip"));
        this.proxyButton.setTooltip(tooltip("authprobe.option.proxy.tip"));
        this.proxyGearButton.setTooltip(tooltip("authprobe.option.proxy.settings.tip"));
        this.refreshOptions();

        // Bottom row.
        int buttonY = this.height - 26;
        int buttonWidth = Math.max(40, (this.width - 20 - 16) / 5);
        int buttonX = (this.width - (buttonWidth * 5 + 16)) / 2;
        this.startButton = Button.builder(
                        Component.translatable("authprobe.button.start").withStyle(ChatFormatting.GREEN),
                        pressed -> this.start())
                .bounds(buttonX, buttonY, buttonWidth, 20).build();
        this.startButton.setTooltip(tooltip("authprobe.button.start.tip"));
        this.startButton.active = config.modEnabled();
        this.addRenderableWidget(this.startButton);

        Button stopButton = Button.builder(
                        Component.translatable("authprobe.button.stop").withStyle(ChatFormatting.RED),
                        pressed -> BotController.stop())
                .bounds(buttonX + (buttonWidth + 4), buttonY, buttonWidth, 20).build();
        stopButton.setTooltip(tooltip("authprobe.button.stop.tip"));
        this.addRenderableWidget(stopButton);

        Button importButton = Button.builder(Component.translatable("authprobe.button.import"),
                        pressed -> this.minecraft.setScreen(new ImportScreen(this, true)))
                .bounds(buttonX + (buttonWidth + 4) * 2, buttonY, buttonWidth, 20).build();
        importButton.setTooltip(tooltip("authprobe.button.import.tip"));
        this.addRenderableWidget(importButton);

        Button patternsButton = Button.builder(Component.translatable("authprobe.button.patterns"), pressed -> {
                    this.saveFields();
                    this.minecraft.setScreen(new ProbePatternsScreen(this));
                }).bounds(buttonX + (buttonWidth + 4) * 3, buttonY, buttonWidth, 20).build();
        patternsButton.setTooltip(tooltip("authprobe.button.patterns.tip"));
        this.addRenderableWidget(patternsButton);

        Button menuButton = Button.builder(Component.translatable("authprobe.button.menu"), pressed -> this.onClose())
                .bounds(buttonX + (buttonWidth + 4) * 4, buttonY, buttonWidth, 20).build();
        menuButton.setTooltip(tooltip("authprobe.button.menu.tip"));
        this.addRenderableWidget(menuButton);

        // Clear the live chat pane, right-aligned under it.
        Button clearButton = Button.builder(Component.translatable("authprobe.chat.clear"),
                        pressed -> {
                            ChatWatcher.clear();
                            this.chatScroll = 0;
                        })
                .bounds(rightX + optionWidth - 92, this.height - 48, 92, 14).build();
        clearButton.setTooltip(tooltip("authprobe.chat.clear.tip"));
        this.addRenderableWidget(clearButton);

        // Prime the cached state.
        this.passwordCount = PasswordList.count(this.passwordBox.getValue());
        this.addressValid = ServerConnector.isValidAddress(this.addressBox.getValue());
        this.nickValid = Nicks.isValid(this.nickBox.getValue().trim());
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float delta) {
        ProbeConfig config = ProbeConfig.INSTANCE;
        int leftX = 10;
        int columnWidth = Math.max(80, (this.width - 30) / 2);
        int rightX = leftX + columnWidth + 10;
        int optionWidth = Math.max(60, this.width - rightX - 10);

        // Background; only the chat pane keeps a framed panel.
        this.renderBackground(graphics);
        super.render(graphics, mouseX, mouseY, delta);

        // Title.
        graphics.drawCenteredString(this.font, this.title, this.width / 2, 6, 0xFF_FF_D7_00);

        // Field labels.
        graphics.drawString(this.font, Component.translatable("authprobe.address"), leftX, 22, 0xFF_FF_D7_00, false);
        graphics.drawString(this.font, Component.translatable("authprobe.nick.slot",
                config.activeIndex() + 1, config.targets().size()), leftX, 54, 0xFF_FF_D7_00, false);
        graphics.drawString(this.font, Component.translatable("authprobe.passwords"), leftX, 86, 0xFF_FF_D7_00, false);

        // Status block, stacked above the button row.
        int lastY = this.height - 73;
        int stateY = this.height - 62;
        int countY = this.height - 51;
        int validY = this.height - 40;
        graphics.fill(leftX, this.height - 77, leftX + columnWidth, this.height - 76, 0x66_FF_FF_FF);
        String none = Component.translatable("authprobe.none").getString();
        Component lastSeen = Component.translatable("authprobe.status.lastseen",
                config.lastNick().isEmpty() ? none : config.lastNick(),
                config.lastPassword().isEmpty() ? none : config.lastPassword());
        Component addressStatus = Component.translatable("authprobe.status.address",
                Component.translatable(this.addressValid ? "authprobe.ok" : "authprobe.bad"));
        Component nickStatus = Component.translatable("authprobe.status.nick",
                Component.translatable(this.nickValid ? "authprobe.ok" : "authprobe.bad"));
        Component countStatus = Component.translatable("authprobe.status.count", this.passwordCount, PasswordList.MAX);
        graphics.drawString(this.font, lastSeen, leftX, lastY, 0xFF_FF_D0_00, false);
        graphics.drawString(this.font, Component.translatable("authprobe.status.state",
                        Component.translatable(BotController.stateKey())),
                leftX, stateY, 0xFF_C0_C0_C0, false);
        graphics.drawString(this.font, countStatus, leftX, countY, 0xFF_D0_D0_D0, false);
        graphics.drawString(this.font, addressStatus, leftX, validY, this.addressValid ? 0xFF_88_FF_88 : 0xFF_FF_66_66, false);
        graphics.drawString(this.font, nickStatus, leftX + this.font.width(addressStatus) + 8, validY,
                this.nickValid ? 0xFF_88_FF_88 : 0xFF_FF_66_66, false);

        // Live chat pane.
        int paneTop = 22 + 17 * 9 + 16;
        int paneBottom = this.height - 50;
        graphics.drawString(this.font, Component.translatable("authprobe.chat"), rightX, paneTop - 14, 0xFF_FF_D7_00, false);
        if (paneBottom > paneTop + 6) {
            this.drawPanel(graphics, rightX - 4, paneTop - 4, optionWidth + 8, paneBottom - paneTop + 4);
            this.renderChatPane(graphics, rightX, paneTop, optionWidth, paneBottom);
        }
    }

    /**
     * Draws a framed dark panel.
     *
     * @param graphics Drawing graphics
     * @param x        Panel X
     * @param y        Panel Y
     * @param width    Panel width
     * @param height   Panel height
     */
    private void drawPanel(GuiGraphics graphics, int x, int y, int width, int height) {
        graphics.fill(x, y, x + width, y + height, 0x66_08_10_18);
        graphics.fill(x, y, x + width, y + 1, 0xFF_3C_5A_78);
        graphics.fill(x, y + height - 1, x + width, y + height, 0xFF_3C_5A_78);
        graphics.fill(x, y, x + 1, y + height, 0xFF_3C_5A_78);
        graphics.fill(x + width - 1, y, x + width, y + height, 0xFF_3C_5A_78);
    }

    @Override
    public void onClose() {
        assert this.minecraft != null;
        this.saveFields();
        ProbeConfig.INSTANCE.save();
        this.minecraft.setScreen(this.parent);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public boolean keyPressed(int key, int scan, int mods) {
        // Make Enter reach the multiline editor even if the screen focus drifted.
        if ((key == GLFW.GLFW_KEY_ENTER || key == GLFW.GLFW_KEY_KP_ENTER)
                && this.passwordBox != null && this.passwordBox.isFocused()) {
            this.setFocused(this.passwordBox);
            return this.passwordBox.keyPressed(key, scan, mods);
        }
        return super.keyPressed(key, scan, mods);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button == 0 && this.startChatScrollDrag(mouseX, mouseY)) return true;
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
        if (this.chatScrollbarDragging) {
            this.dragChatScrollbar(mouseY);
            return true;
        }
        return super.mouseDragged(mouseX, mouseY, button, dragX, dragY);
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        if (this.chatScrollbarDragging) {
            this.chatScrollbarDragging = false;
            this.setDragging(false);
            return true;
        }
        return super.mouseReleased(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double delta) {
        int rightX = this.chatPaneLeft();
        int optionWidth = this.chatPaneWidth();
        int paneTop = this.chatPaneTop();
        int paneBottom = this.chatPaneBottom();
        if (paneBottom > paneTop + 6
                && mouseX >= rightX && mouseX <= rightX + optionWidth
                && mouseY >= paneTop && mouseY <= paneBottom) {
            int capacity = Math.max(1, (paneBottom - paneTop) / 9);
            int total = this.visualLines(ChatWatcher.snapshot(), optionWidth).size();
            int maxScroll = Math.max(0, total - capacity);
            this.chatScroll = Math.max(0, Math.min(maxScroll, this.chatScroll + (int) Math.signum(delta)));
            return true;
        }
        // Other widgets (the password editor) handle their own wheel.
        return super.mouseScrolled(mouseX, mouseY, delta);
    }

    /**
     * Starts dragging the chat scrollbar.
     *
     * @param mouseX Mouse X
     * @param mouseY Mouse Y
     * @return Whether the drag started
     */
    private boolean startChatScrollDrag(double mouseX, double mouseY) {
        int left = this.chatPaneLeft();
        int width = this.chatPaneWidth();
        int top = this.chatPaneTop();
        int bottom = this.chatPaneBottom();
        if (bottom <= top + 6) return false;
        if (mouseX < left + width - 7 || mouseX > left + width) return false;
        if (mouseY < top || mouseY > bottom) return false;

        int capacity = Math.max(1, (bottom - top) / 9);
        int total = this.visualLines(ChatWatcher.snapshot(), width).size();
        int maxScroll = Math.max(0, total - capacity);
        if (maxScroll <= 0) return false;

        int track = bottom - top;
        int barHeight = Math.max(8, track * capacity / total);
        int barY = top + (track - barHeight) * (maxScroll - this.chatScroll) / maxScroll;
        this.chatScrollbarGrab = mouseY >= barY && mouseY <= barY + barHeight
                ? mouseY - barY
                : barHeight / 2.0D;
        this.chatScrollbarDragging = true;
        this.setDragging(true);
        this.dragChatScrollbar(mouseY);
        return true;
    }

    /**
     * Moves the chat scroll from the scrollbar position.
     *
     * @param mouseY Mouse Y
     */
    private void dragChatScrollbar(double mouseY) {
        int width = this.chatPaneWidth();
        int top = this.chatPaneTop();
        int bottom = this.chatPaneBottom();
        int capacity = Math.max(1, (bottom - top) / 9);
        int total = this.visualLines(ChatWatcher.snapshot(), width).size();
        int maxScroll = Math.max(0, total - capacity);
        if (maxScroll <= 0) return;

        int track = bottom - top;
        int barHeight = Math.max(8, track * capacity / total);
        int travel = track - barHeight;
        if (travel <= 0) return;

        double position = Math.max(0.0D, Math.min(travel, mouseY - top - this.chatScrollbarGrab));
        this.chatScroll = (int) Math.round((1.0D - position / travel) * maxScroll);
        this.chatScroll = Math.max(0, Math.min(maxScroll, this.chatScroll));
    }

    /**
     * Gets the chat pane left.
     *
     * @return X
     */
    private int chatPaneLeft() {
        int columnWidth = Math.max(80, (this.width - 30) / 2);
        return 10 + columnWidth + 10;
    }

    /**
     * Gets the chat pane width.
     *
     * @return Width
     */
    private int chatPaneWidth() {
        return Math.max(60, this.width - this.chatPaneLeft() - 10);
    }

    /**
     * Gets the chat pane top.
     *
     * @return Top
     */
    private int chatPaneTop() {
        return 22 + 17 * 9 + 16;
    }

    /**
     * Gets the chat pane bottom.
     *
     * @return Bottom
     */
    private int chatPaneBottom() {
        return this.height - 50;
    }

    /**
     * Draws the captured server chat.
     *
     * @param graphics Drawing graphics
     * @param x        Pane X
     * @param top      Pane top
     * @param width    Pane width
     * @param bottom   Pane bottom
     */
private void renderChatPane(GuiGraphics graphics, int x, int top, int width, int bottom) {
        List<Visual> lines = this.visualLines(ChatWatcher.snapshot(), width);
        int lineHeight = 9;
        int capacity = Math.max(1, (bottom - top) / lineHeight);
        int maxScroll = Math.max(0, lines.size() - capacity);
        this.chatScroll = Math.max(0, Math.min(this.chatScroll, maxScroll));

        int start = Math.max(0, lines.size() - capacity - this.chatScroll);
        graphics.enableScissor(x, top, x + width, bottom);
        int y = top;
        for (int i = start; i < lines.size() && y + lineHeight <= bottom; i++) {
            Visual line = lines.get(i);
            int color = line.actionBar() ? 0xFF_88_88_FF : 0xFF_E0E0E0;
            graphics.drawString(this.font, line.text(), x, y, color, false);
            y += lineHeight;
        }
        graphics.disableScissor();

        if (maxScroll > 0) {
            int barX = x + width - 4;
            int track = bottom - top;
            int barHeight = Math.max(8, track * capacity / lines.size());
            int barY = top + (track - barHeight) * (maxScroll - this.chatScroll) / maxScroll;
            graphics.fill(barX, top, barX + 4, bottom, 0x40_FF_FF_FF);
            graphics.fill(barX, barY, barX + 4, barY + barHeight, 0xFF_88_CC_FF);
        }
    }

    /**
     * Splits the captured lines into visual lines that fit the pane width.
     *
     * @param lines Source lines
     * @param width Pane width
     * @return Visual lines, oldest first
     */
    private List<Visual> visualLines(List<ChatWatcher.Line> lines, int width) {
        List<Visual> result = new ArrayList<>();
        for (ChatWatcher.Line line : lines) {
            for (String part : wrap(line.text(), width)) {
                result.add(new Visual(part, line.actionBar()));
            }
        }
        return result;
    }

    /**
     * Wraps one line on spaces when possible, on characters otherwise.
     *
     * @param text  Source text
     * @param width Maximum width in pixels
     * @return Wrapped parts
     */
    private List<String> wrap(String text, int width) {
        List<String> parts = new ArrayList<>();
        if (text.isEmpty() || width <= 0) {
            parts.add(text == null ? "" : text);
            return parts;
        }
        String rest = text;
        while (this.font.width(rest) > width && parts.size() < 400) {
            String fit = this.font.plainSubstrByWidth(rest, width);
            if (fit.isEmpty()) fit = rest.substring(0, 1);
            int breakAt = fit.lastIndexOf(' ');
            if (breakAt > 0) {
                parts.add(rest.substring(0, breakAt));
                rest = rest.substring(breakAt + 1);
            } else {
                parts.add(fit);
                rest = rest.substring(fit.length());
            }
        }
        if (!rest.isEmpty() || parts.isEmpty()) parts.add(rest);
        return parts;
    }

    /**
     * One rendered chat line.
     *
     * @param text      Line text
     * @param actionBar Whether it came from the action bar
     */
    private record Visual(String text, boolean actionBar) {
    }

    /**
     * Writes the text fields into the config and starts the run.
     */
    private void start() {
        this.saveFields();
        BotController.start(this);
    }

    /**
     * Copies the text fields into the config.
     */
    private void saveFields() {
        ProbeConfig config = ProbeConfig.INSTANCE;
        config.address(this.addressBox.getValue());
        config.primary(new ProbeTarget(this.nickBox.getValue().trim(), PasswordList.parse(this.passwordBox.getValue())));
    }

    /**
     * Saves the fields and moves to another target.
     *
     * @param delta Direction
     */
    private void switchTarget(int delta) {
        this.saveFields();
        ProbeConfig config = ProbeConfig.INSTANCE;
        int next = config.activeIndex() + delta;
        if (next < 0 || next >= config.targets().size()) return;
        config.activeIndex(next);
        this.loadTargetFields();
    }

    /**
     * Adds a target and switches to it.
     */
    private void addTarget() {
        this.saveFields();
        ProbeConfig.INSTANCE.addTarget();
        this.loadTargetFields();
    }

    /**
     * Removes the active target.
     */
    private void removeTarget() {
        this.saveFields();
        ProbeConfig.INSTANCE.removeActiveTarget();
        this.loadTargetFields();
    }

    /**
     * Deletes every profile and its passwords, leaving one empty profile.
     */
    private void clearProfiles() {
        ProbeConfig config = ProbeConfig.INSTANCE;
        config.replaceTargets(List.of(new ProbeTarget("", List.of())));
        config.rememberLast("", "");
        config.save();
        this.loadTargetFields();
        this.passwordCount = 0;
        this.nickValid = false;
    }

    /**
     * Loads the active target into the text fields.
     */
    private void loadTargetFields() {
        ProbeConfig config = ProbeConfig.INSTANCE;
        this.nickBox.setValue(config.nick());
        this.passwordBox.setValue(String.join("\n", config.passwords()));
        this.passwordCount = PasswordList.count(this.passwordBox.getValue());
        this.nickValid = Nicks.isValid(this.nickBox.getValue().trim());
    }

    /**
     * Refreshes the option button labels.
     */
    private void refreshOptions() {
        ProbeConfig config = ProbeConfig.INSTANCE;
        this.modButton.setMessage(Component.translatable("authprobe.option.mod", onOff(config.modEnabled())));
        this.macroButton.setMessage(Component.translatable("authprobe.option.macro", onOff(config.macroEnabled())));
        this.modeButton.setMessage(Component.translatable("authprobe.option.mode", config.mode().display()));
        this.delayButton.setMessage(Component.translatable("authprobe.option.delay", formatDelay(config.delayMs())));
        this.reconnectButton.setMessage(Component.translatable("authprobe.option.reconnect", config.reconnectDelayMs() / 1000));
        this.autoStartButton.setMessage(Component.translatable("authprobe.option.autostart", onOff(config.autoStart())));
        this.autoReconnectButton.setMessage(Component.translatable("authprobe.option.autoreconnect", onOff(config.autoReconnect())));
        this.debugButton.setMessage(Component.translatable("authprobe.option.debug", onOff(config.debugLogs())));
        this.proxyButton.setMessage(Component.translatable("authprobe.option.proxy",
                onOff(config.modEnabled() && config.proxyEnabled())));
        this.proxyButton.active = config.modEnabled();
        if (this.startButton != null) this.startButton.active = config.modEnabled();
    }

    /**
     * Formats a delay for the button label.
     *
     * @param millis Delay in milliseconds
     * @return Short human-readable delay
     */
    private static String formatDelay(int millis) {
        if (millis <= 0) return Component.translatable("authprobe.delay.none").getString();
        if (millis < 1000) return millis + " " + Component.translatable("authprobe.unit.ms").getString();
        String seconds = millis % 1000 == 0
                ? Integer.toString(millis / 1000)
                : String.format(java.util.Locale.ROOT, "%.1f", millis / 1000.0D);
        return seconds + " " + Component.translatable("authprobe.unit.s").getString();
    }

    /**
     * Gets the localized on/off word.
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
     * Cycles to the next value of a selectable table.
     *
     * @param table   Table
     * @param current Current value
     * @return Next value
     */
    private static int nextOf(int[] table, int current) {
        for (int i = 0; i < table.length; i++) {
            if (table[i] == current) return table[(i + 1) % table.length];
        }
        return table[0];
    }
}