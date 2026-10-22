package ru.an0n.authprobe.mixins;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundSetActionBarTextPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import ru.an0n.authprobe.BotController;
import ru.an0n.authprobe.ChatWatcher;

/**
 * Captures the two signals the probe needs out of the connection.
 *
 * <p>{@code onDisconnect} is the kick path: the server's Disconnect packet lands here with the
 * reason, which is where the last-used password gets reported.
 *
 * <p>{@code setActionBarText} is separate from system chat and does NOT raise
 * {@code ClientChatReceivedEvent}, yet login plugins put their "please login" reminder there.
 * Watching it is what makes the action bar usable as a signal at all.
 *
 * @author AN0N
 */
@Mixin(ClientPacketListener.class)
public final class ClientPacketListenerMixin {
    /**
     * Records the disconnect and queues the reconnect.
     *
     * @param reason Disconnect reason
     * @param ci     Callback info
     */
    @Inject(method = "onDisconnect", at = @At("HEAD"), cancellable = true)
    private void authprobe$onDisconnect(Component reason, CallbackInfo ci) {
        // Ignore and suppress disconnects from a session that was already replaced
        // (nickname switch, planned reconnect): otherwise vanilla would clobber the new
        // ConnectScreen with a DisconnectedScreen.
        if (Minecraft.getInstance().getConnection() != (Object) this) {
            ci.cancel();
            return;
        }
        BotController.onDisconnected(reason);
    }

    /**
     * Feeds the action bar into the watcher.
     *
     * @param packet Action bar packet
     * @param ci     Callback info
     */
    @Inject(method = "setActionBarText", at = @At("TAIL"))
    private void authprobe$setActionBarText(ClientboundSetActionBarTextPacket packet, CallbackInfo ci) {
        ChatWatcher.onActionBar(packet.getText().getString());
    }
}