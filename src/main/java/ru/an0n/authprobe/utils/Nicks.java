package ru.an0n.authprobe.utils;

/**
 * Nickname rules, matching what the vanilla client and the mod it borrows from enforce.
 *
 * @author AN0N
 */
public final class Nicks {
    /**
     * Minimum nickname length.
     */
    public static final int MIN = 3;

    /**
     * Maximum nickname length.
     */
    public static final int MAX = 16;

    /**
     * An instance of this class cannot be created.
     *
     * @throws AssertionError Always
     */
    private Nicks() {
        throw new AssertionError("No instances.");
    }

    /**
     * Checks whether the nickname is acceptable to a server.
     *
     * @param name Target nickname
     * @return Whether the nickname is valid
     */
    public static boolean isValid(String name) {
        if (name == null) return false;
        int length = name.length();
        if (length < MIN || length > MAX) return false;
        for (int i = 0; i < length; i++) {
            char c = name.charAt(i);
            if (c == '_' || c >= '0' && c <= '9' || c >= 'a' && c <= 'z' || c >= 'A' && c <= 'Z') continue;
            return false;
        }
        return true;
    }
}