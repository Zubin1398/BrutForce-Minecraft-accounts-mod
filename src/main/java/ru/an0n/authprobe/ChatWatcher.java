package ru.an0n.authprobe;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

/**
 * Reads the server's answer out of incoming chat and action-bar text, and keeps a
 * ring buffer of recent lines for the live pane in the setup screen.
 *
 * @author AN0N
 */
public final class ChatWatcher {
    /**
     * Verdict of the attempt currently in flight.
     */
    public enum Verdict {
        /**
         * No answer yet.
         */
        NONE,

        /**
         * The password was accepted.
         */
        SUCCESS,

        /**
         * The password was rejected.
         */
        FAIL,

        /**
         * The server complained about the attempt rate.
         */
        KICK
    }

    /**
     * Ring buffer capacity.
     */
    private static final int CAPACITY = 80;

    /**
     * Our own message prefix, never fed back as a server answer.
     */
    private static final String OWN_PREFIX = "[AuthProbe]";

    /**
     * Recent lines.
     */
    private static final ArrayDeque<Line> BUFFER = new ArrayDeque<>(CAPACITY);

    /**
     * Whether incoming text should be scored.
     */
    private static boolean armed;

    /**
     * Master capture switch. When off, nothing is buffered or scored.
     */
    private static boolean enabled = true;

    /**
     * Current verdict.
     */
    private static Verdict verdict = Verdict.NONE;

    /**
     * An instance of this class cannot be created.
     *
     * @throws AssertionError Always
     */
    private ChatWatcher() {
        throw new AssertionError("No instances.");
    }

    /**
     * A captured line.
     *
     * @param time      Capture time, milliseconds
     * @param actionBar Whether it came from the action bar
     * @param text      Plain text
     */
    public record Line(long time, boolean actionBar, String text) {
    }

    /**
     * Starts scoring incoming text.
     */
    public static synchronized void arm() {
        verdict = Verdict.NONE;
        armed = true;
    }

    /**
     * Stops scoring incoming text.
     */
    public static synchronized void disarm() {
        armed = false;
    }

    /**
     * Gets the current verdict.
     *
     * @return Verdict
     */
    public static synchronized Verdict verdict() {
        return verdict;
    }

    /**
     * Clears the current verdict.
     */
    public static synchronized void consume() {
        verdict = Verdict.NONE;
    }

    /**
     * Feeds a chat line.
     *
     * @param text Plain text
     */
    public static void onChat(String text) {
        feed(text, false);
    }

    /**
     * Feeds an action-bar line.
     *
     * @param text Plain text
     */
    public static void onActionBar(String text) {
        feed(text, true);
    }

    /**
     * Gets a snapshot of the recent lines.
     *
     * @return Recent lines, oldest first
     */
    public static synchronized List<Line> snapshot() {
        return new ArrayList<>(BUFFER);
    }

    /**
     * Clears the captured lines. The current verdict is left alone.
     */
    public static synchronized void clear() {
        BUFFER.clear();
    }

    /**
     * Enables or disables capture. Disabling also clears the buffer and any verdict.
     *
     * @param value New value
     */
    public static synchronized void setEnabled(boolean value) {
        enabled = value;
        if (!value) {
            BUFFER.clear();
            verdict = Verdict.NONE;
            armed = false;
        }
    }

    /**
     * Records and scores a line.
     *
     * @param text      Plain text
     * @param actionBar Whether it came from the action bar
     */
    private static synchronized void feed(String text, boolean actionBar) {
        if (!enabled) return;
        // Skip nothing.
        if (text == null || text.isBlank()) return;

        // Skip our own output.
        if (text.startsWith(OWN_PREFIX)) return;

        // Record.
        BUFFER.addLast(new Line(System.currentTimeMillis(), actionBar, text));
        while (BUFFER.size() > CAPACITY) {
            BUFFER.removeFirst();
        }

        // Skip scoring if idle or already decided.
        if (!armed || verdict != Verdict.NONE) return;

        // Score. First match wins, the server usually answers with one line.
        PatternBank patterns = ProbeConfig.INSTANCE.patterns();
        if (PatternBank.matches(patterns.success(), text)) {
            verdict = Verdict.SUCCESS;
            return;
        }
        if (PatternBank.matches(patterns.fail(), text)) {
            verdict = Verdict.FAIL;
            return;
        }
        if (PatternBank.matches(patterns.kick(), text)) {
            verdict = Verdict.KICK;
        }
    }
}