package ru.an0n.authprobe.proxy;

import ru.an0n.authprobe.AuthProbe;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.List;

/**
 * SOCKS5 / HTTP CONNECT client and the loopback relay that lets vanilla's Netty connection
 * go through a proxy without touching the handshake.
 *
 * @author AN0N
 */
public final class ProxyTunnel {
    /**
     * Live relay listeners, closed when the proxy changes or the probe stops.
     */
    private static final List<ServerSocket> RELAYS = Collections.synchronizedList(new ArrayList<>());

    /**
     * An instance of this class cannot be created.
     *
     * @throws AssertionError Always
     */
    private ProxyTunnel() {
        throw new AssertionError("No instances.");
    }

    /**
     * Opens a tunnel through the proxy to the target.
     *
     * @param proxy     Proxy
     * @param target    Target address
     * @param timeoutMs Timeout in milliseconds
     * @return Connected socket positioned at the target payload
     * @throws IOException When the proxy or the target refuses
     */
    public static Socket open(ProxyEntry proxy, InetSocketAddress target, int timeoutMs) throws IOException {
        Socket socket = new Socket();
        try {
            socket.connect(new InetSocketAddress(proxy.host(), proxy.port()), timeoutMs);
            socket.setSoTimeout(timeoutMs);
            if (proxy.type() == ProxyEntry.Type.SOCKS5) {
                socks5(socket, proxy, target);
            } else {
                httpConnect(socket, proxy, target);
            }
            socket.setSoTimeout(0);
            return socket;
        } catch (IOException | RuntimeException exception) {
            try {
                socket.close();
            } catch (IOException ignored) {
                // Nothing to do.
            }
            if (exception instanceof IOException io) throw io;
            throw new IOException(exception.getMessage(), exception);
        }
    }

    /**
     * Checks a proxy against a target with a short timeout.
     *
     * @param proxy     Proxy
     * @param target    Target address
     * @param timeoutMs Timeout in milliseconds
     * @return Check result
     */
    public static Check check(ProxyEntry proxy, InetSocketAddress target, int timeoutMs) {
        long started = System.currentTimeMillis();
        try (Socket ignored = open(proxy, target, timeoutMs)) {
            return new Check(true, System.currentTimeMillis() - started, "");
        } catch (Exception exception) {
            return new Check(false, System.currentTimeMillis() - started, shortReason(exception));
        }
    }

    /**
     * Starts a loopback relay for one connection.
     *
     * @param proxy  Proxy
     * @param target Real target address
     * @return Local port to point the client at, or {@code -1} on failure
     */
    public static int startRelay(ProxyEntry proxy, InetSocketAddress target) {
        try {
            ServerSocket listener = new ServerSocket(0, 1, InetAddress.getLoopbackAddress());
            RELAYS.add(listener);
            Thread thread = new Thread(() -> serve(listener, proxy, target), "AuthProbe-ProxyRelay");
            thread.setDaemon(true);
            thread.start();
            return listener.getLocalPort();
        } catch (IOException exception) {
            AuthProbe.logger().warn("AuthProbe: unable to start proxy relay for {}.", proxy.display(), exception);
            return -1;
        }
    }

    /**
     * Closes every live relay.
     */
    public static void closeAll() {
        synchronized (RELAYS) {
            for (ServerSocket listener : RELAYS) {
                try {
                    listener.close();
                } catch (IOException ignored) {
                    // Nothing to do.
                }
            }
            RELAYS.clear();
        }
    }

    /**
     * Accepts one client, opens the proxy tunnel and pumps bytes.
     *
     * @param listener Relay listener
     * @param proxy    Proxy
     * @param target   Real target
     */
    private static void serve(ServerSocket listener, ProxyEntry proxy, InetSocketAddress target) {
        try (ServerSocket server = listener) {
            server.setSoTimeout(15_000);
            try (Socket client = server.accept(); Socket remote = open(proxy, target, 10_000)) {
                pump(client, remote);
            }
        } catch (IOException exception) {
            AuthProbe.logger().warn("AuthProbe: proxy relay failed for {}.", proxy.display(), exception);
        } finally {
            RELAYS.remove(listener);
        }
    }

    /**
     * Pumps bytes both ways until one side closes.
     *
     * @param client Client socket
     * @param remote Proxy socket
     */
    private static void pump(Socket client, Socket remote) {
        Thread upload = new Thread(() -> copy(client, remote), "AuthProbe-ProxyUp");
        upload.setDaemon(true);
        upload.start();
        copy(remote, client);
        try {
            client.close();
        } catch (IOException ignored) {
            // Nothing to do.
        }
        try {
            remote.close();
        } catch (IOException ignored) {
            // Nothing to do.
        }
    }

    /**
     * Copies one stream to the other until EOF.
     *
     * @param from Source socket
     * @param to   Destination socket
     */
    private static void copy(Socket from, Socket to) {
        byte[] buffer = new byte[8192];
        try (InputStream input = from.getInputStream(); OutputStream output = to.getOutputStream()) {
            int read;
            while ((read = input.read(buffer)) >= 0) {
                output.write(buffer, 0, read);
                output.flush();
            }
        } catch (IOException ignored) {
            // Peer closed.
        }
    }

    /**
     * Performs the SOCKS5 greeting, optional auth and CONNECT.
     *
     * @param socket Proxy socket
     * @param proxy  Proxy
     * @param target Target
     * @throws IOException On protocol failure
     */
    private static void socks5(Socket socket, ProxyEntry proxy, InetSocketAddress target) throws IOException {
        DataOutputStream out = new DataOutputStream(socket.getOutputStream());
        DataInputStream in = new DataInputStream(socket.getInputStream());

        // Greeting.
        out.writeByte(5);
        if (proxy.hasAuth()) {
            out.writeByte(2);
            out.writeByte(0);
            out.writeByte(2);
        } else {
            out.writeByte(1);
            out.writeByte(0);
        }
        out.flush();

        int version = in.readUnsignedByte();
        int method = in.readUnsignedByte();
        if (version != 5) throw new IOException("SOCKS5: bad version " + version);
        if (method == 0xFF) throw new IOException("SOCKS5: no acceptable auth method");

        // Username/password auth (RFC 1929).
        if (method == 2) {
            if (!proxy.hasAuth()) throw new IOException("SOCKS5: proxy wants auth, none configured");
            byte[] user = proxy.username().getBytes(StandardCharsets.UTF_8);
            byte[] pass = (proxy.password() == null ? "" : proxy.password()).getBytes(StandardCharsets.UTF_8);
            if (user.length > 255 || pass.length > 255) throw new IOException("SOCKS5: credentials too long");
            out.writeByte(1);
            out.writeByte(user.length);
            out.write(user);
            out.writeByte(pass.length);
            out.write(pass);
            out.flush();
            int authVersion = in.readUnsignedByte();
            int status = in.readUnsignedByte();
            if (authVersion != 1 || status != 0) throw new IOException("SOCKS5: auth rejected");
        } else if (method != 0) {
            throw new IOException("SOCKS5: unsupported auth method " + method);
        }

        // CONNECT.
        out.writeByte(5);
        out.writeByte(1);
        out.writeByte(0);
        InetAddress address = target.getAddress();
        if (address instanceof Inet4Address) {
            out.writeByte(1);
            out.write(address.getAddress());
        } else if (address != null) {
            out.writeByte(4);
            out.write(address.getAddress());
        } else {
            byte[] host = target.getHostString().getBytes(StandardCharsets.US_ASCII);
            if (host.length > 255) throw new IOException("SOCKS5: hostname too long");
            out.writeByte(3);
            out.writeByte(host.length);
            out.write(host);
        }
        out.writeShort(target.getPort());
        out.flush();

        int replyVersion = in.readUnsignedByte();
        int reply = in.readUnsignedByte();
        in.readUnsignedByte();
        if (replyVersion != 5 || reply != 0) throw new IOException("SOCKS5: connect failed (code " + reply + ")");
        int addressType = in.readUnsignedByte();
        switch (addressType) {
            case 1 -> in.readNBytes(4);
            case 4 -> in.readNBytes(16);
            case 3 -> in.readNBytes(in.readUnsignedByte());
            default -> throw new IOException("SOCKS5: bad address type " + addressType);
        }
        in.readUnsignedShort();
    }

    /**
     * Performs an HTTP CONNECT handshake.
     *
     * @param socket Proxy socket
     * @param proxy  Proxy
     * @param target Target
     * @throws IOException On protocol failure
     */
    private static void httpConnect(Socket socket, ProxyEntry proxy, InetSocketAddress target) throws IOException {
        String host = target.getHostString();
        String authority = (host.contains(":") ? "[" + host + "]" : host) + ":" + target.getPort();
        StringBuilder request = new StringBuilder()
                .append("CONNECT ").append(authority).append(" HTTP/1.1\r\n")
                .append("Host: ").append(authority).append("\r\n")
                .append("Proxy-Connection: Keep-Alive\r\n");
        if (proxy.hasAuth()) {
            String credentials = proxy.username() + ":" + (proxy.password() == null ? "" : proxy.password());
            request.append("Proxy-Authorization: Basic ")
                    .append(Base64.getEncoder().encodeToString(credentials.getBytes(StandardCharsets.UTF_8)))
                    .append("\r\n");
        }
        request.append("\r\n");

        OutputStream out = socket.getOutputStream();
        out.write(request.toString().getBytes(StandardCharsets.US_ASCII));
        out.flush();

        InputStream in = socket.getInputStream();
        String status = readLine(in);
        if (status == null || !status.contains(" 200 ")) {
            throw new IOException("HTTP proxy: " + (status == null ? "no response" : status));
        }
        String line;
        while ((line = readLine(in)) != null && !line.isEmpty()) {
            // Skip headers.
        }
    }

    /**
     * Reads a CRLF line without buffering ahead.
     *
     * @param in Input stream
     * @return Line without CRLF, or {@code null} on EOF
     * @throws IOException On read failure
     */
    private static String readLine(InputStream in) throws IOException {
        StringBuilder builder = new StringBuilder();
        int value;
        while ((value = in.read()) >= 0) {
            if (value == '\n') return builder.toString().replace("\r", "");
            builder.append((char) value);
            if (builder.length() > 8192) throw new IOException("HTTP proxy: header line too long");
        }
        return builder.isEmpty() ? null : builder.toString();
    }

    /**
     * Shortens an exception message for the UI.
     *
     * @param exception Exception
     * @return Short reason
     */
    private static String shortReason(Exception exception) {
        String message = exception.getMessage();
        if (message == null || message.isBlank()) return exception.getClass().getSimpleName();
        return message.length() > 60 ? message.substring(0, 60) : message;
    }

    /**
     * Result of checking one proxy.
     *
     * @param ok        Whether the tunnel worked
     * @param millis    Time spent, milliseconds
     * @param error     Failure reason, empty on success
     */
    public record Check(boolean ok, long millis, String error) {
    }
}
