package ru.an0n.authprobe.mixins;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundCustomPayloadPacket;
import net.minecraft.network.protocol.game.ClientboundLoginPacket;
import net.minecraft.network.protocol.game.ClientboundPlayerPositionPacket;
import net.minecraft.network.protocol.game.ClientboundRespawnPacket;
import net.minecraft.network.protocol.game.ClientboundSetActionBarTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetSubtitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitleTextPacket;
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

    /**
     * Handles a login packet. On limbo servers a correct password is followed by a second login
     * packet that moves the player into the world, which is the only success signal they send.
     *
     * @param packet Login packet
     * @param ci     Callback info
     */
    @Inject(method = "handleLogin", at = @At("HEAD"))
    private void authprobe$handleLogin(ClientboundLoginPacket packet, CallbackInfo ci) {
        BotController.onLoginPacket();
    }

    /**
     * Handles a respawn packet, which is how a limbo server teleports the player into the lobby
     * after a correct password without any chat message.
     *
     * @param packet Respawn packet
     * @param ci     Callback info
     */
    @Inject(method = "handleRespawn", at = @At("HEAD"))
    private void authprobe$handleRespawn(ClientboundRespawnPacket packet, CallbackInfo ci) {
        BotController.onRespawnPacket();
    }

    /**
     * Handles a plugin-channel payload. A payload right after a password check means the server
     * accepted the password and its lobby plugin started talking to the client.
     *
     * @param packet Payload packet
     * @param ci     Callback info
     */
    @Inject(method = "handleCustomPayload", at = @At("HEAD"))
    private void authprobe$handleCustomPayload(ClientboundCustomPayloadPacket packet, CallbackInfo ci) {
        BotController.onCustomPayload();
    }

    /**
     * Handles a teleport. Limbo servers move the player into the lobby after a correct password.
     *
     * @param packet Position packet
     * @param ci     Callback info
     */
    @Inject(method = "handleMovePlayer", at = @At("HEAD"))
    private void authprobe$handleMovePlayer(ClientboundPlayerPositionPacket packet, CallbackInfo ci) {
        BotController.onPlayerPosition();
    }

    /**
     * Feeds the title into the watcher, so a diagnosis shows where a phrase came from.
     *
     * @param packet Title packet
     * @param ci     Callback info
     */
    @Inject(method = "setTitleText", at = @At("TAIL"))
    private void authprobe$setTitleText(ClientboundSetTitleTextPacket packet, CallbackInfo ci) {
        ChatWatcher.onTitle(packet.getText().getString(), false);
    }

    /**
     * Feeds the subtitle into the watcher.
     *
     * @param packet Subtitle packet
     * @param ci     Callback info
     */
    @Inject(method = "setSubtitleText", at = @At("TAIL"))
    private void authprobe$setSubtitleText(ClientboundSetSubtitleTextPacket packet, CallbackInfo ci) {
        ChatWatcher.onTitle(packet.getText().getString(), true);
    }
}