package ru.an0n.authprobe.utils;

import ru.an0n.authprobe.ProbeTarget;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Parser for the plain-text import format.
 *
 * <pre>
 * nick | comment (ignored)
 * password
 * password
 *
 * anotherNick | comment
 * password
 * </pre>
 *
 * Blank lines are ignored, passwords may contain spaces, and a header is a line containing
 * {@code " | "}. The text after the pipe is ignored (for example a donation rank).
 *
 * @author AN0N
 */
public final class NickImport {
    /**
     * An instance of this class cannot be created.
     *
     * @throws AssertionError Always
     */
    private NickImport() {
        throw new AssertionError("No instances.");
    }

    /**
     * Parses nicknames and passwords.
     *
     * @param raw Raw file text
     * @return Targets in file order; duplicate nicks are merged
     */
    public static List<ProbeTarget> parse(String raw) {
        List<ProbeTarget> targets = new ArrayList<>();
        if (raw == null) return targets;
        String text = raw.startsWith("\uFEFF") ? raw.substring(1) : raw;

        Map<String, List<String>> collected = new LinkedHashMap<>();
        String currentNick = null;
        for (String line : text.split("\\R", -1)) {
            String trimmed = line.trim();
            if (trimmed.isEmpty()) continue;
            if (trimmed.contains(" | ")) {
                String[] parts = trimmed.split("\\s*\\|\\s*", 2);
                currentNick = parts[0].trim();
                if (currentNick.isEmpty()) {
                    currentNick = null;
                    continue;
                }
                collected.computeIfAbsent(currentNick, key -> new ArrayList<>());
            } else if (currentNick != null) {
                collected.get(currentNick).add(trimmed);
            }
        }

        for (Map.Entry<String, List<String>> entry : collected.entrySet()) {
            targets.add(new ProbeTarget(entry.getKey(), entry.getValue()));
        }
        return targets;
    }
}