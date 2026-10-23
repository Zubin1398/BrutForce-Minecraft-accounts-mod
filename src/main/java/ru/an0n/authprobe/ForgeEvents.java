package ru.an0n.authprobe;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.DisconnectedScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.network.chat.Component;
import net.minecraftforge.client.event.ClientChatEvent;
import net.minecraftforge.client.event.ClientChatReceivedEvent;
import net.minecraftforge.client.event.ScreenEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import ru.an0n.authprobe.screen.BotStatusScreen;
import ru.an0n.authprobe.screen.ProbeSetupScreen;

import java.util.Locale;

/**
 * Forge event subscribers.
 *
 * <p>Dot commands (".start", ".go") are plain chat, not commands, and that is deliberate:
 * {@code ClientChatEvent} only fires for the chat path. Cancelling the event makes Forge's hook
 * return an empty string, so the line never leaves the client — the server never sees it.
 * A "/"-prefixed equivalent would route through {@code ClientCommandHandler} and be unreachable.
 *
 * <p>Every message that starts with a dot is cancelled, including typos and unknown commands,
 * so a mistyped command can never leak to the server as ordinary chat.
 *
 * @author AN0N
 */
public final class ForgeEvents {
    /**
     * Start aliases.
     */
    private static final String CMD_START = ".start";

    /**
     * Start alias.
     */
    private static final String CMD_GO = ".go";

    /**
     * Stop the probe.
     */
    private static final String CMD_STOP = ".stop";

    /**
     * Print the position.
     */
    private static final String CMD_STATUS = ".status";

    /**
     * Print the command list.
     */
    private static final String CMD_HELP = ".help";

    /**
     * Ticks between background settings saves.
     */
    private static final int SAVE_INTERVAL = 100;

    /**
     * Ticks since the last background settings save.
     */
    private int saveTicks;

    /**
     * Adds the entry button to the title screen.
     * Placed in the bottom-right corner, which is clear of every vanilla widget at any window size.
     *
     * @param event Screen init event
     */
    @SubscribeEvent
    public void onScreenInit(ScreenEvent.Init.Post event) {
        Screen screen = event.getScreen();
        if (!(screen instanceof TitleScreen)) return;
        Button button = Button.builder(
                        Component.translatable("authprobe.menuButton"),
                        pressed -> Minecraft.getInstance().setScreen(new ProbeSetupScreen(screen)))
                .bounds(screen.width - 102, screen.height - 24, 98, 20)
                .build();
        event.addListener(button);
    }

    /**
     * Intercepts the probe's dot commands before they reach the server.
     *
     * @param event Chat send event
     */
    @SubscribeEvent
    public void onChatSend(ClientChatEvent event) {
        if (!ProbeConfig.INSTANCE.modEnabled()) return;
        String message = event.getMessage();
        if (message == null) return;
        String command = message.trim().toLowerCase(Locale.ROOT);
        if (!command.startsWith(".")) return;
        switch (command) {
            case CMD_START, CMD_GO -> {
                event.setCanceled(true);
                BotController.begin();
            }
            case CMD_STOP -> {
                event.setCanceled(true);
                BotController.stop();
            }
            case CMD_STATUS -> {
                event.setCanceled(true);
                BotController.announce(BotController.state().name() + " | " + BotController.describePosition());
            }
            case CMD_HELP -> {
                event.setCanceled(true);
                BotController.announce(".start / .go - begin or continue"
                        + " | .stop - stop | .status - position | .help - this");
            }
            default -> {
                // Swallow every dot-prefixed line, known or not: a typo must never reach the server.
                event.setCanceled(true);
                BotController.announce("unknown command '" + command + "'. .help - command list");
            }
        }
    }

    /**
     * Feeds incoming chat into the watcher.
     *
     * @param event Chat received event
     */
    @SubscribeEvent
    public void onChatReceived(ClientChatReceivedEvent event) {
        ChatWatcher.onChat(event.getMessage().getString());
    }

    /**
     * Swaps the disconnect screen for the probe status screen while the probe owns the session.
     *
     * @param event Screen opening event
     */
    @SubscribeEvent
    public void onScreenOpening(ScreenEvent.Opening event) {
        if (!ProbeConfig.INSTANCE.modEnabled()) return;
        if (!BotController.armed() && BotController.state() != BotController.State.EXHAUSTED) return;
        if (!(event.getNewScreen() instanceof DisconnectedScreen disconnected)) return;

        // A disconnect screen while still CONNECTING means the dial itself failed: the server was
        // down, the address was wrong, or the handshake was refused. Nothing was sent, so this must
        // not spend an attempt, and the state has to leave CONNECTING or the probe hangs.
        Component reason = disconnected.getNarrationMessage();
        if (BotController.state() == BotController.State.CONNECTING) {
            BotController.onConnectFailed(reason);
        }

        event.setNewScreen(new BotStatusScreen());
    }

    /**
     * Advances the state machine.
     *
     * @param event Client tick event
     */
    @SubscribeEvent
    public void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;

        // Drain the macro even while the mod is off, so an old click cannot fire later.
        KeyBindings.handleMacro();

        if (!ProbeConfig.INSTANCE.modEnabled()) {
            // Normal play: no state machine, no disconnect-screen swap, no chat capture.
            if (BotController.armed()) BotController.stop();
            if (++this.saveTicks >= SAVE_INTERVAL) {
                this.saveTicks = 0;
                ProbeConfig.INSTANCE.saveIfDirty();
            }
            return;
        }

        BotController.tick();

        // Persist settings and pattern banks typed into the UI, including on an abrupt exit.
        if (++this.saveTicks >= SAVE_INTERVAL) {
            this.saveTicks = 0;
            ProbeConfig.INSTANCE.saveIfDirty();
        }
    }
}