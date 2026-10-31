package ru.an0n.authprobe;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads the pause length out of a server rate-limit message such as
 * {@code "Подождите перед отправкой 30 сек"} or {@code "Please wait 15s"}.
 *
 * <p>A number alone means nothing: it is only accepted when a time unit sits right after it.
 * Messages like {@code "У вас 3 попытки"} or {@code "Онлайн 250 игроков"} therefore never
 * turn into a pause.
 *
 * @author AN0N
 */
public final class ServerPause {
    /**
     * Shortest pause the probe will wait out, milliseconds.
     */
    public static final long MIN_MS = 1_000L;

    /**
     * Longest pause the probe will wait out, milliseconds.
     */
    public static final long MAX_MS = 300_000L;

    /**
     * A number followed by a time unit. The lookaheads stop a bare "s" or "с" from matching
     * the first letter of a word such as "sent" or "сообщений".
     */
    private static final Pattern NUMBER_WITH_UNIT = Pattern.compile(
            "(?iu)(\\d{1,5})\\s*(мс|ms|секунд[а-яё]*|сек[а-яё]*|мин[а-яё]*|min\\w*|sec\\w*|s(?![a-z])|с(?![а-яё]))");

    /**
     * A number no unit follows.
     */
    private static final Pattern NANOS = Pattern.compile("(?iu)мс|ms");

    /**
     * An instance of this class cannot be created.
     *
     * @throws AssertionError Always
     */
    private ServerPause() {
        throw new AssertionError("No instances.");
    }

    /**
     * Extracts the pause the server asked for.
     *
     * @param text Message text, may be {@code null}
     * @return Pause in milliseconds, clamped into {@link #MIN_MS}..{@link #MAX_MS},
     *         or {@code -1} when the text names no duration
     */
    public static long parseMillis(String text) {
        if (text == null || text.isBlank()) return -1L;
        Matcher matcher = NUMBER_WITH_UNIT.matcher(text);
        if (!matcher.find()) return -1L;

        long amount;
        try {
            amount = Long.parseLong(matcher.group(1));
        } catch (NumberFormatException ignored) {
            return -1L;
        }
        if (amount <= 0L) return -1L;

        return clamp(amount * unitMillis(matcher.group(2)));
    }

    /**
     * Clamps a pause into the accepted range.
     *
     * @param millis Requested pause, milliseconds
     * @return Pause inside {@link #MIN_MS}..{@link #MAX_MS}
     */
    public static long clamp(long millis) {
        return Math.max(MIN_MS, Math.min(MAX_MS, millis));
    }

    /**
     * Gets the length of one unit in milliseconds.
     *
     * @param unit Matched unit
     * @return Unit length, milliseconds
     */
    private static long unitMillis(String unit) {
        if (unit != null && NANOS.matcher(unit).matches()) return 1L;
        String lower = unit == null ? "" : unit.toLowerCase(java.util.Locale.ROOT);
        if (lower.startsWith("мин") || lower.startsWith("min")) return 60_000L;
        return 1_000L;
    }
}