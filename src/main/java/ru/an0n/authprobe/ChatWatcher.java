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
         * The server asked to slow down before the next attempt.
         */
        WAIT,

        /**
         * The server complained about the attempt rate.
         */
        KICK
    }

    /**
     * Ring buffer capacity.
     */
    private static final int CAPACITY = 200;

    /**
     * When on, only local AuthProbe log lines are kept in the pane. The server chat is still
     * scored, it is just not recorded.
     */
    private static boolean logsOnly;

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
     * Pause the server asked for with the current verdict, milliseconds. Zero when unknown.
     */
    private static long waitMillis;

    /**
     * An instance of this class cannot be created.
     *
     * @throws AssertionError Always
     */
    private ChatWatcher() {
        throw new AssertionError("No instances.");
    }

    /**
     * Where a captured line came from.
     */
    public enum Kind {
        /**
         * Server chat.
         */
        SERVER,

        /**
         * Server action bar.
         */
        ACTION_BAR,

        /**
         * A local AuthProbe log line, shown only while debug logs are on.
         */
        LOCAL
    }

    /**
     * A captured line.
     *
     * @param time Capture time, milliseconds
     * @param kind Where the line came from
     * @param text Plain text
     */
    public record Line(long time, Kind kind, String text) {
        /**
         * Checks whether the line came from the action bar.
         *
         * @return Whether it is an action-bar line
         */
        public boolean actionBar() {
            return this.kind == Kind.ACTION_BAR;
        }
    }

    /**
     * Starts scoring incoming text.
     */
    public static synchronized void arm() {
        verdict = Verdict.NONE;
        waitMillis = 0L;
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
        waitMillis = 0L;
    }

    /**
     * Gets the pause the server asked for together with the current verdict.
     *
     * @return Pause in milliseconds, zero when the message named no duration
     */
    public static synchronized long waitMillis() {
        return waitMillis;
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
            waitMillis = 0L;
            armed = false;
        }
    }

    /**
     * Shows only local log lines in the pane, or everything.
     *
     * @param value Whether to keep only local lines
     */
    public static synchronized void setLogsOnly(boolean value) {
        logsOnly = value;
    }

    /**
     * Checks whether the pane keeps only local log lines.
     *
     * @return Whether only local lines are kept
     */
    public static synchronized boolean logsOnly() {
        return logsOnly;
    }

    /**
     * Records a local AuthProbe line in the pane. Local lines are never scored, so the
     * probe cannot read its own output as a server answer.
     *
     * @param text Plain text
     */
    public static synchronized void local(String text) {
        if (!enabled) return;
        if (text == null || text.isBlank()) return;
        append(text, Kind.LOCAL);
    }

    /**
     * Appends a line and trims the buffer.
     *
     * @param text Plain text
     * @param kind Origin
     */
    private static void append(String text, Kind kind) {
        BUFFER.addLast(new Line(System.currentTimeMillis(), kind, text));
        while (BUFFER.size() > CAPACITY) {
            BUFFER.removeFirst();
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

        // Record, unless the pane is filtered down to local logs. Scoring still happens below,
        // so a filtered pane never changes what the probe decides.
        if (!logsOnly) {
            append(text, actionBar ? Kind.ACTION_BAR : Kind.SERVER);
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
        if (PatternBank.matches(patterns.pause(), text)) {
            verdict = Verdict.WAIT;
            waitMillis = ServerPause.parseMillis(text);
            return;
        }
        if (PatternBank.matches(patterns.kick(), text)) {
            verdict = Verdict.KICK;
        }
    }
}