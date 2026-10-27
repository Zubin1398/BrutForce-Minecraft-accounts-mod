package ru.an0n.authprobe.screen;

import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.network.chat.Component;
import ru.an0n.authprobe.BotController;
import ru.an0n.authprobe.ProbeConfig;
import ru.an0n.authprobe.ProbeTarget;
import ru.an0n.authprobe.proxy.ProxyTunnel;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/**
 * Shown instead of the vanilla disconnect screen while the probe owns the session,
 * so the operator can see the last password used and choose what happens next.
 * Chat is unreachable on the disconnect screen, which is why these buttons exist at all.
 *
 * @author AN0N
 */
public final class BotStatusScreen extends Screen {
    /**
     * Format of the skip mark time, local zone.
     */
    private static final DateTimeFormatter SKIP_TIME =
            DateTimeFormatter.ofPattern("dd.MM HH:mm").withZone(ZoneId.systemDefault());

    /**
     * Creates a new status screen.
     */
    public BotStatusScreen() {
        super(Component.translatable("authprobe.status.title"));
    }

    @Override
    protected void init() {
        assert this.minecraft != null;
        int centerX = this.width / 2;
        int rowOne = this.height - 56;
        int rowTwo = this.height - 30;

        // Reconnect and wait for .start/.go to continue from the current password.
        this.addRenderableWidget(Button.builder(
                        Component.translatable("authprobe.status.continue").withStyle(ChatFormatting.GREEN),
                        pressed -> BotController.resume())
                .bounds(centerX - 100, rowOne, 200, 20).build());

        // Change the proxy and reconnect.
        this.addRenderableWidget(Button.builder(
                        Component.translatable("authprobe.status.proxy").withStyle(ChatFormatting.AQUA), pressed -> {
                    if (!ProbeConfig.INSTANCE.rotateProxy()) {
                        BotController.announce("no proxies configured");
                        return;
                    }
                    ProxyTunnel.closeAll();
                    BotController.announceProxyChange();
                    BotController.resume();
                })
                .bounds(centerX - 148, rowTwo, 144, 20).build());

        // Stop the run.
        this.addRenderableWidget(Button.builder(
                        Component.translatable("authprobe.status.stop").withStyle(ChatFormatting.RED),
                        pressed -> BotController.stop())
                .bounds(centerX + 4, rowTwo, 70, 20).build());

        // Give up and go back to the title screen.
        this.addRenderableWidget(Button.builder(Component.translatable("authprobe.status.menu"), pressed -> {
                    BotController.stop();
                    this.minecraft.setScreen(new TitleScreen());
                })
                .bounds(centerX + 80, rowTwo, 70, 20).build());
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float delta) {
        int centerX = this.width / 2;
        int panelWidth = Math.min(this.width - 20, 430);
        int panelLeft = centerX - panelWidth / 2;

        // Framed info panel behind the report.
        this.renderBackground(graphics);
        graphics.fill(panelLeft, 12, panelLeft + panelWidth, this.height - 64, 0x88_08_10_18);
        graphics.fill(panelLeft, 12, panelLeft + panelWidth, 13, 0xFF_3C_5A_78);
        graphics.fill(panelLeft, this.height - 65, panelLeft + panelWidth, this.height - 64, 0xFF_3C_5A_78);
        graphics.fill(panelLeft, 12, panelLeft + 1, this.height - 64, 0xFF_3C_5A_78);
        graphics.fill(panelLeft + panelWidth - 1, 12, panelLeft + panelWidth, this.height - 64, 0xFF_3C_5A_78);
        super.render(graphics, mouseX, mouseY, delta);

        ProbeConfig config = ProbeConfig.INSTANCE;
        int y = 18;

        // Title.
        graphics.drawCenteredString(this.font, this.title, centerX, y, 0xFF_FF_66_66);
        y += 24;

        // State.
        graphics.drawCenteredString(this.font, Component.translatable("authprobe.status.state",
                        Component.translatable(BotController.stateKey())),
                centerX, y, 0xFF_FF_FF_FF);
        y += 13;

        // Target.
        graphics.drawCenteredString(this.font, Component.translatable("authprobe.status.target", config.nick(), config.address()),
                centerX, y, 0xFF_FF_FF_FF);
        y += 13;

        // Skip mark of the current nickname, if the last nickname-level kick left one.
        ProbeTarget currentTarget = config.primary();
        if (currentTarget.hasSkip()) {
            String mark = currentTarget.skipIsInvalid()
                    ? Component.translatable("authprobe.skip.invalid").getString()
                    : currentTarget.isTwoFactor()
                    ? Component.translatable("authprobe.skip.2fa").getString()
                    : Component.translatable("authprobe.skip.online",
                    SKIP_TIME.format(Instant.ofEpochMilli(currentTarget.skipAt()))).getString();
            boolean red = currentTarget.skipIsInvalid() || currentTarget.isTwoFactor();
            graphics.drawCenteredString(this.font, mark, centerX, y, red ? 0xFF_FF_66_66 : 0xFF_FF_AA_33);
            y += 12;
        }

        // Proxy position, when routing is enabled.
        if (config.proxyEnabled() && !config.proxies().isEmpty()) {
            graphics.drawCenteredString(this.font, Component.translatable("authprobe.status.proxyCounter",
                    config.proxyIndex() + 1, config.proxies().size()), centerX, y, 0xFF_CC99FF);
            y += 12;
        }

        // Run report: started at, interrupted at, per-nick password counts.
        int start = Math.max(0, BotController.runStartTarget());
        int current = config.activeIndex();
        int skipped = BotController.skippedTarget();
        int interrupted = skipped >= 0 ? skipped : current;
        int completed = Math.max(0, current - start);
        boolean allDone = BotController.state() == BotController.State.EXHAUSTED && skipped < 0;
        if (allDone) {
            completed = Math.max(0, config.targets().size() - start);
        }
        graphics.drawCenteredString(this.font, Component.translatable(allDone
                        ? "authprobe.status.run.done" : "authprobe.status.run",
                completed, start + 1, interrupted + 1, config.targets().size()), centerX, y, 0xFF_88_CC_FF);
        y += 12;
        int first = Math.max(start, current - 5);
        int lastIndex = current;
        if (first > start) {
            graphics.drawString(this.font, "...", panelLeft + 12, y, 0xFF_A0_A0_A0, false);
            y += 10;
        }
        for (int i = first; i <= lastIndex; i++) {
            var target = config.targets().get(i);
            boolean wasSkipped = i == skipped;
            int color = wasSkipped ? 0xFF_FF_66_66 : (allDone || i < current) ? 0xFF_88_FF_88 : 0xFF_FF_D7_00;
            String status = Component.translatable(wasSkipped ? "authprobe.status.nick.skipped"
                    : (allDone || i < current) ? "authprobe.status.nick.done" : "authprobe.status.nick.current").getString();
            String line = Component.translatable("authprobe.status.nick.line",
                    i + 1, target.nick(), target.passwordCount(), status).getString();
            graphics.drawString(this.font, this.font.plainSubstrByWidth(line, panelWidth - 24),
                    panelLeft + 12, y, color, false);
            y += 10;
        }
        if (lastIndex < current) {
            graphics.drawString(this.font, "...", panelLeft + 12, y, 0xFF_A0_A0_A0, false);
            y += 10;
        }
        y += 3;

        // Position.
        graphics.drawCenteredString(this.font, BotController.describePositionComponent(), centerX, y, 0xFF_FF_FF_FF);
        y += 13;

        // What happens on continue.
        graphics.drawCenteredString(this.font, Component.translatable("authprobe.status.upnext",
                BotController.describeNextComponent()), centerX, y, 0xFF_88_CC_FF);
        y += 13;

        // Last password used, the thing that has to be readable at a glance.
        String last = BotController.lastPassword();
        graphics.drawCenteredString(this.font, Component.translatable("authprobe.status.last", last.isEmpty() ? "-" : last),
                centerX, y, 0xFF_FF_D0_00);
        y += 16;

        // Server pauses waited out by the smart delay.
        if (BotController.pauseCount() > 0) {
            graphics.drawCenteredString(this.font, Component.translatable("authprobe.status.pauses",
                    BotController.pauseCount()), centerX, y, 0xFF_88_CC_FF);
            y += 12;
        }

        // Disconnect reason and how the probe read it.
        String reason = BotController.kickReason();
        if (!reason.isEmpty()) {
            y = this.drawWrappedCentered(graphics,
                    Component.translatable("authprobe.status.reason", reason).getString(),
                    centerX, y, panelWidth - 24, 0xFF_B0_B0_B0, this.height - 86);
            graphics.drawCenteredString(this.font, Component.translatable("authprobe.status.classified",
                            Component.translatable(BotController.kickClassKey())),
                    centerX, y, 0xFF_A0_A0_A0);
            y += 12;
        }

        // Reconnect countdown.
        if (BotController.reconnectPending()) {
            String seconds = String.format(Locale.ROOT, "%.0f", BotController.reconnectRemainingMs() / 1000.0D);
            graphics.drawCenteredString(this.font,
                    Component.translatable("authprobe.status.countdown", seconds), centerX, y, 0xFF_88_FF_88);
        }
    }

@Override
    public boolean isPauseScreen() {
        return false;
    }

    /**
     * Draws text centered, wrapping on spaces and hard-cutting when a word is too long.
     *
     * @param graphics Drawing graphics
     * @param text     Text
     * @param centerX  Center X
     * @param y        Start Y
     * @param width    Max line width
     * @param color    Text color
     * @param maxY     Last Y that may be used
     * @return Y after the last drawn line
     */
    private int drawWrappedCentered(GuiGraphics graphics, String text, int centerX, int y,
                                    int width, int color, int maxY) {
        for (String paragraph : text.split("\\R", -1)) {
            if (paragraph.isEmpty()) {
                y += 9;
                continue;
            }
            String rest = paragraph;
            while (!rest.isEmpty()) {
                String line = this.font.plainSubstrByWidth(rest, width);
                if (line.isEmpty()) line = rest.substring(0, 1);
                if (line.length() < rest.length()) {
                    int space = line.lastIndexOf(' ');
                    if (space > 0) line = line.substring(0, space);
                }
                if (y > maxY) {
                    graphics.drawCenteredString(this.font, "...", centerX, y, color);
                    return y + 9;
                }
                graphics.drawCenteredString(this.font, line, centerX, y, color);
                y += 10;
                rest = rest.substring(line.length()).stripLeading();
            }
        }
        return y;
    }
}