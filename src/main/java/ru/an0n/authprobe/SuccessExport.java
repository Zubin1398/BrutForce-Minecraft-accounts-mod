package ru.an0n.authprobe;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Builds and writes the plain-text export of nicknames whose password was found.
 *
 * <p>The file is grouped by server: the address in square brackets, then one
 * {@code nick:password} line per successful nickname. Servers without a successful nickname
 * are omitted.
 *
 * @author AN0N
 */
public final class SuccessExport {
    /**
     * Header shown at the top of the file.
     */
    private static final String HEADER = "# AuthProbe — успешные ники";

    /**
     * Shown when a server has no address.
     */
    private static final String NO_ADDRESS = "без адреса";

    /**
     * Utility class.
     */
    private SuccessExport() {
    }

    /**
     * Counts successful nicknames across all servers.
     *
     * @param servers Servers
     * @return Amount of successful nicknames
     */
    public static int count(List<ProbeServer> servers) {
        int total = 0;
        for (ProbeServer server : servers) {
            for (ProbeTarget target : server.targets()) {
                if (isFound(target)) total++;
            }
        }
        return total;
    }

    /**
     * Builds the export text.
     *
     * @param servers   Servers
     * @param timestamp Export time
     * @return Text
     */
    public static String build(List<ProbeServer> servers, String timestamp) {
        StringBuilder out = new StringBuilder();
        out.append(HEADER).append('\n');
        out.append("# ").append(timestamp == null ? "" : timestamp).append('\n');

        for (ProbeServer server : servers) {
            List<ProbeTarget> found = new ArrayList<>();
            for (ProbeTarget target : server.targets()) {
                if (isFound(target)) found.add(target);
            }
            if (found.isEmpty()) continue;

            String address = server.address().isBlank() ? NO_ADDRESS : server.address();
            out.append('\n').append('[').append(address).append("]\n");
            for (ProbeTarget target : found) {
                out.append(target.nick());
                if (!target.passwords().isEmpty() && !target.passwords().get(0).isEmpty()) {
                    out.append(':').append(target.passwords().get(0));
                }
                out.append('\n');
            }
        }
        return out.toString();
    }

    /**
     * Writes the export text to a file in UTF-8, creating parent directories when needed.
     *
     * @param servers   Servers
     * @param timestamp Export time
     * @param target    Target file
     * @throws IOException When the file cannot be written
     */
    public static void write(List<ProbeServer> servers, String timestamp, Path target) throws IOException {
        Path parent = target.getParent();
        if (parent != null) Files.createDirectories(parent);
        Files.writeString(target, build(servers, timestamp), StandardCharsets.UTF_8);
    }

    /**
     * Whether a target is a successful nickname.
     *
     * @param target Target
     * @return Whether the password was found
     */
    private static boolean isFound(ProbeTarget target) {
        return !target.nick().isBlank() && ProbeTarget.RESULT_FOUND.equals(target.result());
    }
}