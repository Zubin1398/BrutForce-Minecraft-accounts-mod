package ru.an0n.authprobe.mixins;

import net.minecraft.network.Connection;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import ru.an0n.authprobe.AuthProbe;
import ru.an0n.authprobe.proxy.ProxyEntry;
import ru.an0n.authprobe.proxy.ProxyManager;
import ru.an0n.authprobe.proxy.ProxyTunnel;

import java.net.InetAddress;
import java.net.InetSocketAddress;

/**
 * Redirects the client's TCP connect through the loopback proxy relay.
 *
 * <p>The argument is the only thing replaced: the handshake packet is built from the untouched
 * {@code ServerAddress}, so the server still sees the real host name while the bytes travel
 * through the proxy.
 *
 * @author AN0N
 */
@Mixin(Connection.class)
public final class ConnectionProxyMixin {
    /**
     * Replaces the target address with a loopback relay port when a proxy is active.
     *
     * @param address Original target
     * @return Relay address, original address, or a closed loopback port when the relay failed
     */
    @ModifyVariable(method = "connect", at = @At("HEAD"), argsOnly = true, index = 0)
    private static InetSocketAddress authprobe$routeThroughProxy(InetSocketAddress address) {
        ProxyEntry proxy = ProxyManager.active();
        if (proxy == null) return address;

        int port = ProxyTunnel.startRelay(proxy, address);
        if (port <= 0) {
            // Fail closed: a broken proxy must not silently leak the real IP.
            AuthProbe.logger().warn("AuthProbe: proxy relay failed for {}, refusing direct fallback.", proxy.display());
            return new InetSocketAddress(InetAddress.getLoopbackAddress(), 1);
        }
        AuthProbe.logger().info("AuthProbe: routing {} via {} (relay port {}).", address, proxy.display(), port);
        return new InetSocketAddress(InetAddress.getLoopbackAddress(), port);
    }
}
