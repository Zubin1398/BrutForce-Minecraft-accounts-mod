package ru.an0n.authprobe;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.resolver.ServerAddress;

/**
 * Joins a server from inside the game.
 *
 * @author AN0N
 */
public final class ServerConnector {
    /**
     * An instance of this class cannot be created.
     *
     * @throws AssertionError Always
     */
    private ServerConnector() {
        throw new AssertionError("No instances.");
    }

    /**
     * Checks whether the address is something the client can dial.
     * This guard is not optional: {@code ServerAddress.parseString} never fails, it silently
     * falls back to {@code localhost:25565}, so a typo would otherwise connect to the wrong place.
     *
     * @param address Target address
     * @return Whether the address is valid
     */
    public static boolean isValidAddress(String address) {
        if (address == null || address.isBlank()) return false;
        return ServerAddress.isValidAddress(address.trim());
    }

    /**
     * Checks whether a connection attempt is already on screen.
     *
     * @return Whether the client is connecting
     */
    public static boolean isConnecting() {
        return Minecraft.getInstance().screen instanceof ConnectScreen;
    }

    /**
     * Starts connecting to the address.
     *
     * @param parent  Parent screen for the connect screen, may be {@code null}
     * @param address Target address
     * @return Whether the connection attempt was started
     */
    public static boolean connect(Screen parent, String address) {
        Minecraft minecraft = Minecraft.getInstance();

        // startConnecting logs an error and does nothing when a ConnectScreen is already up.
        if (isConnecting()) {
            AuthProbe.logger().warn("AuthProbe: already connecting, skipping connect request.");
            return false;
        }

        // Validate before dialling.
        if (!isValidAddress(address)) {
            AuthProbe.logger().warn("AuthProbe: refusing to connect to invalid address '{}'.", address);
            return false;
        }

        // Dial.
        String trimmed = address.trim();
        ServerAddress target = ServerAddress.parseString(trimmed);
        ServerData data = new ServerData("AuthProbe", trimmed, false);
        Screen actualParent = parent != null ? parent : new TitleScreen();
        ConnectScreen.startConnecting(actualParent, minecraft, target, data, false);
        AuthProbe.logger().info("AuthProbe: connecting to {}.", trimmed);
        return true;
    }
}