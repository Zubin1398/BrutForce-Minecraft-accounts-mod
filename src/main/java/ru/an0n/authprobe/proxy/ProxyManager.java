package ru.an0n.authprobe.proxy;

import net.minecraft.client.multiplayer.resolver.ServerAddress;
import ru.an0n.authprobe.ProbeConfig;
import ru.an0n.authprobe.ServerConnector;

import java.net.InetAddress;
import java.net.InetSocketAddress;

/**
 * Glue between the persisted proxy list and the connection hook.
 *
 * @author AN0N
 */
public final class ProxyManager {
    /**
     * An instance of this class cannot be created.
     *
     * @throws AssertionError Always
     */
    private ProxyManager() {
        throw new AssertionError("No instances.");
    }

    /**
     * Gets the proxy that should carry the next connection.
     *
     * @return Parsed active proxy, or {@code null} for a direct connection
     */
    public static ProxyEntry active() {
        ProbeConfig config = ProbeConfig.INSTANCE;
        if (!config.proxyEnabled() || config.proxies().isEmpty()) return null;
        return ProxyEntry.parse(config.currentProxyLine());
    }

    /**
     * Resolves the configured server to a socket address for proxy checks.
     *
     * @return Resolved address, or {@code null} when the address is missing or invalid
     */
    public static InetSocketAddress checkTarget() {
        ProbeConfig config = ProbeConfig.INSTANCE;
        if (!ServerConnector.isValidAddress(config.address())) return null;
        ServerAddress address = ServerAddress.parseString(config.address().trim());
        try {
            return new InetSocketAddress(InetAddress.getByName(address.getHost()), address.getPort());
        } catch (Exception exception) {
            return null;
        }
    }
}
