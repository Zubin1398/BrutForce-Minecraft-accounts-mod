package ru.an0n.authprobe.utils;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * Parser for pasted password lists.
 * Splits on line breaks only, because a password may legitimately contain spaces,
 * commas and semicolons. Nothing is trimmed for the same reason.
 *
 * @author AN0N
 */
public final class PasswordList {
    /**
     * Maximum amount of passwords accepted.
     */
    public static final int MAX = 2048;

    /**
     * An instance of this class cannot be created.
     *
     * @throws AssertionError Always
     */
    private PasswordList() {
        throw new AssertionError("No instances.");
    }

    /**
     * Parses the pasted text into a deduplicated password list, keeping the input order.
     *
     * @param raw Pasted text, may be {@code null}
     * @return Passwords
     */
    public static List<String> parse(String raw) {
        // Handle nothing.
        if (raw == null || raw.isEmpty()) return List.of();
        // Collect unique, keeping order.
        LinkedHashSet<String> unique = new LinkedHashSet<>();
        for (String token : raw.split("\n", -1)) {
            // Drop the CR of a CRLF pair, keep everything else exactly.
            String value = token.endsWith("\r") ? token.substring(0, token.length() - 1) : token;
            if (value.isEmpty()) continue;
            unique.add(value);
            if (unique.size() >= MAX) break;
        }
        return new ArrayList<>(unique);
    }

    /**
     * Counts the parsed passwords.
     *
     * @param raw Pasted text
     * @return Password amount
     */
    public static int count(String raw) {
        return parse(raw).size();
    }
}