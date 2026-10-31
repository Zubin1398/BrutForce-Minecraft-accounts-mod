package ru.an0n.authprobe;

/**
 * Answer timeout rules for a sent password.
 *
 * @author AN0N
 */
public final class ProbeTiming {
    /**
     * Default hold limit, milliseconds.
     */
    public static final int DEFAULT_HOLD_MS = 15_000;

    /**
     * Smallest hold limit, milliseconds.
     */
    public static final int MIN_HOLD_MS = 5_000;

    /**
     * Largest hold limit, milliseconds.
     */
    public static final int MAX_HOLD_MS = 20_000;

    /**
     * Utility class.
     */
    private ProbeTiming() {
    }

    /**
     * Whether the answer wait is over.
     *
     * @param holdSeen         Whether the server said it is checking the password
     * @param now              Current time, milliseconds
     * @param sentAt           Time of the last send or hold line, milliseconds
     * @param attemptStartedAt Time the password was sent, milliseconds
     * @param timeoutMs        Short answer timeout, milliseconds
     * @param holdLimitMs      Hold limit, milliseconds
     * @return Whether the attempt should be spent
     */
    public static boolean answerExpired(boolean holdSeen, long now, long sentAt, long attemptStartedAt,
                                        int timeoutMs, int holdLimitMs) {
        if (holdSeen) return now - attemptStartedAt > holdLimitMs;
        return now - sentAt > timeoutMs;
    }

    /**
     * Clamps a hold limit to the allowed range.
     *
     * @param value Requested limit, milliseconds
     * @return Clamped limit, milliseconds
     */
    public static int clampHoldLimit(int value) {
        return Math.max(MIN_HOLD_MS, Math.min(MAX_HOLD_MS, value));
    }
}