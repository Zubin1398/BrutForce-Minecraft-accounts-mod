package ru.an0n.authprobe.screen;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.network.chat.Component;
import ru.an0n.authprobe.BotController;
import ru.an0n.authprobe.ProbeConfig;
import ru.an0n.authprobe.proxy.ProxyEntry;
import ru.an0n.authprobe.proxy.ProxyTunnel;

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

        // Reconnect and continue from the current password.
        this.addRenderableWidget(Button.builder(Component.translatable("authprobe.status.continue"),
                        pressed -> BotController.resume())
                .bounds(centerX - 148, rowOne, 144, 20).build());

        // Skip this password and continue.
        this.addRenderableWidget(Button.builder(Component.translatable("authprobe.status.next"),
                        pressed -> BotController.next())
                .bounds(centerX + 4, rowOne, 144, 20).build());

        // Change the proxy and reconnect.
        this.addRenderableWidget(Button.builder(Component.translatable("authprobe.status.proxy"), pressed -> {
                    if (!ProbeConfig.INSTANCE.rotateProxy()) {
                        BotController.announce("no proxies configured");
                        return;
                    }
                    ProxyTunnel.closeAll();
                    ProxyEntry entry = ProxyEntry.parse(ProbeConfig.INSTANCE.currentProxyLine());
                    BotController.announce("proxy: " + (entry == null ? ProbeConfig.INSTANCE.currentProxyLine() : entry.display()));
                    BotController.resume();
                })
                .bounds(centerX - 148, rowTwo, 144, 20).build());

        // Stop the run.
        this.addRenderableWidget(Button.builder(Component.translatable("authprobe.status.stop"),
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

        // Disconnect reason.
        String reason = BotController.kickReason();
        if (!reason.isEmpty()) {
            graphics.drawCenteredString(this.font, Component.translatable("authprobe.status.reason", reason),
                    centerX, y, 0xFF_B0_B0_B0);
            y += 13;
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
}