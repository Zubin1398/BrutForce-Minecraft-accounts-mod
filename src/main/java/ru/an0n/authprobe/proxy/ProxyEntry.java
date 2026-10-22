package ru.an0n.authprobe.proxy;

import java.util.Locale;

/**
 * One parsed proxy line.
 *
 * <p>Accepted forms (one per line, {@code #} starts a comment):
 * <ul>
 *     <li>{@code host:port}</li>
 *     <li>{@code [ipv6]:port}</li>
 *     <li>{@code user:pass@host:port}</li>
 *     <li>{@code host:port@user:pass}</li>
 *     <li>{@code host:port:user:pass}</li>
 *     <li>{@code socks5://user:pass@host:port} or {@code http://host:port}</li>
 * </ul>
 *
 * @param type     Proxy protocol
 * @param host     Proxy host
 * @param port     Proxy port
 * @param username Username, {@code null} when the proxy needs no auth
 * @param password Password, may be empty
 * @author AN0N
 */
public record ProxyEntry(Type type, String host, int port, String username, String password) {
    /**
     * Supported proxy protocols.
     */
    public enum Type {
        /**
         * SOCKS5 with no-auth or username/password auth.
         */
        SOCKS5,

        /**
         * HTTP CONNECT.
         */
        HTTP
    }

    /**
     * Parses a raw line.
     *
     * @param raw Raw line
     * @return Parsed entry, or {@code null} when the line is not a valid proxy
     */
    public static ProxyEntry parse(String raw) {
        if (raw == null) return null;
        String line = raw.trim();
        if (line.isEmpty() || line.startsWith("#")) return null;

        // Scheme.
        Type type = Type.SOCKS5;
        int schemeEnd = line.indexOf("://");
        if (schemeEnd > 0) {
            String scheme = line.substring(0, schemeEnd).toLowerCase(Locale.ROOT);
            line = line.substring(schemeEnd + 3).trim();
            if (scheme.equals("socks5") || scheme.equals("socks5h") || scheme.equals("socks")) {
                type = Type.SOCKS5;
            } else if (scheme.equals("http") || scheme.equals("https")) {
                type = Type.HTTP;
            } else {
                return null;
            }
        }

        // Credentials via '@', either user:pass@host:port or host:port@user:pass.
        int at = line.lastIndexOf('@');
        if (at > 0 && at < line.length() - 1) {
            String left = line.substring(0, at).trim();
            String right = line.substring(at + 1).trim();
            String[] leftHost = splitHostPort(left);
            String[] rightHost = splitHostPort(right);
            if (leftHost != null && right.contains(":")) {
                String[] credentials = right.split(":", 2);
                return build(type, leftHost[0], leftHost[1], credentials[0], credentials.length > 1 ? credentials[1] : "");
            }
            if (rightHost != null) {
                String[] credentials = left.split(":", 2);
                return build(type, rightHost[0], rightHost[1], credentials[0], credentials.length > 1 ? credentials[1] : "");
            }
            return null;
        }

        // Plain host:port or host:port:user:pass.
        String[] hostPort = splitHostPort(line);
        if (hostPort != null) {
            return build(type, hostPort[0], hostPort[1], null, null);
        }
        String[] parts = line.split(":", -1);
        if (parts.length >= 4) {
            StringBuilder password = new StringBuilder(parts[3]);
            for (int i = 4; i < parts.length; i++) password.append(':').append(parts[i]);
            return build(type, parts[0], parts[1], parts[2], password.toString());
        }
        return null;
    }

    /**
     * Checks whether credentials are present.
     *
     * @return Whether the proxy uses username/password auth
     */
    public boolean hasAuth() {
        return this.username != null && !this.username.isEmpty();
    }

    /**
     * Gets a credential-free description for logs and the UI.
     *
     * @return Description without the password
     */
    public String display() {
        String scheme = this.type == Type.HTTP ? "http" : "socks5";
        String host = this.host.contains(":") ? "[" + this.host + "]" : this.host;
        return scheme + "://" + (this.username == null ? "" : this.username + "@") + host + ":" + this.port;
    }

    /**
     * Builds an entry, validating host and port.
     *
     * @param type     Protocol
     * @param host     Host
     * @param portText Port, as text
     * @param username Username, may be {@code null}
     * @param password Password, may be {@code null}
     * @return Entry or {@code null}
     */
    private static ProxyEntry build(Type type, String host, String portText, String username, String password) {
        if (host == null) return null;
        String cleanHost = host.trim();
        if (cleanHost.isEmpty() || cleanHost.contains(" ") || cleanHost.contains("/")) return null;
        int port;
        try {
            port = Integer.parseInt(portText.trim());
        } catch (NumberFormatException exception) {
            return null;
        }
        if (port < 1 || port > 65_535) return null;
        String user = username == null || username.isBlank() ? null : username;
        String pass = password == null ? "" : password;
        return new ProxyEntry(type, cleanHost, port, user, pass);
    }

    /**
     * Splits a plain host:port, including bracketed IPv6.
     *
     * @param value Raw host:port
     * @return {@code {host, port}}, or {@code null}
     */
    private static String[] splitHostPort(String value) {
        if (value == null) return null;
        String text = value.trim();
        if (text.isEmpty()) return null;
        if (text.startsWith("[")) {
            int close = text.indexOf(']');
            if (close < 0) return null;
            String host = text.substring(1, close).trim();
            String rest = text.substring(close + 1).trim();
            if (!rest.startsWith(":")) return null;
            String portText = rest.substring(1).trim();
            if (!portText.matches("\\d{1,5}")) return null;
            return host.isEmpty() ? null : new String[]{host, portText};
        }
        int colon = text.lastIndexOf(':');
        if (colon <= 0 || colon == text.length() - 1) return null;
        String host = text.substring(0, colon).trim();
        String portText = text.substring(colon + 1).trim();
        if (host.isEmpty() || host.contains(":") || host.contains("@")) return null;
        if (!portText.matches("\\d{1,5}")) return null;
        return new String[]{host, portText};
    }
}
