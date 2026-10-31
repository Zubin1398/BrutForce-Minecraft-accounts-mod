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
         * The server is still checking the password: hold the answer timeout, send nothing.
         */
        HOLD,

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
     * Two-factor phrase the last matching line hit, empty when none.
     */
    private static String twoFactorRule = "";

    /**
     * Last action-bar line written to the log, to avoid repeating the same text.
     */
    private static String lastChannelLine = "";

    /**
     * Last two-factor candidate written to the log.
     */
    private static String lastTwoFactorCandidate = "";

    /**
     * Captcha phrase the last matching line hit, empty when none.
     */
    private static String captchaRule = "";

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
     * Colour of a local service line in the pane.
     */
    public static final int COLOR_INFO = 0xFF_C0_C0_C0;

    /**
     * Colour of a nickname line.
     */
    public static final int COLOR_NICK = 0xFF_88_CC_FF;

    /**
     * Colour of a password attempt line.
     */
    public static final int COLOR_TRY = 0xFF_FF_D1_66;

    /**
     * Colour of a found-password line.
     */
    public static final int COLOR_FOUND = 0xFF_55_FF_88;

    /**
     * Colour of a warning line: pauses, captcha, two-factor.
     */
    public static final int COLOR_WARN = 0xFF_FF_AA_33;

    /**
     * Colour of an error line: skipped nick, dead proxy list.
     */
    public static final int COLOR_ERROR = 0xFF_FF_77_77;

    /**
     * Colour of a proxy line.
     */
    public static final int COLOR_PROXY = 0xFF_CC_99_FF;

    /**
     * A captured line.
     *
     * @param time  Capture time, milliseconds
     * @param kind  Where the line came from
     * @param text  Plain text
     * @param color Local line colour, zero when the default applies
     */
    public record Line(long time, Kind kind, String text, int color) {
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
     * Stops scoring incoming text and drops the pending signals.
     */
    public static synchronized void disarm() {
        armed = false;
        twoFactorRule = "";
        captchaRule = "";
    }

    /**
     * Takes the two-factor signal, clearing it.
     *
     * @return Matched phrase, empty when no 2FA line was seen
     */
    public static synchronized String takeTwoFactorRule() {
        String rule = twoFactorRule;
        twoFactorRule = "";
        return rule;
    }

    /**
     * Takes the captcha signal, clearing it.
     *
     * @return Matched phrase, empty when no captcha line was seen
     */
    public static synchronized String takeCaptchaRule() {
        String rule = captchaRule;
        captchaRule = "";
        return rule;
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
        logChannel("action bar", text);
        feed(text, true);
    }

    /**
     * Feeds an overlay system-chat line, which the client draws in the action bar.
     *
     * @param text Plain text
     */
    public static void onOverlayChat(String text) {
        logChannel("action bar (overlay)", text);
        feed(text, true);
    }

    /**
     * Feeds a title or subtitle line.
     *
     * @param text     Plain text
     * @param subtitle Whether it came from the subtitle slot
     */
    public static void onTitle(String text, boolean subtitle) {
        logChannel(subtitle ? "subtitle" : "title", text);
        feed(text, false);
    }

    /**
     * Feeds a boss-bar line.
     *
     * @param text Plain text
     */
    public static void onBossBar(String text) {
        logChannel("bossbar", text);
        feed(text, false);
    }

    /**
     * Writes a server message channel to the log once per distinct text, so a diagnosis shows
     * exactly where a phrase arrived from.
     *
     * @param channel Channel name
     * @param text    Plain text
     */
    private static void logChannel(String channel, String text) {
        String normalized = PatternBank.normalize(text);
        if (normalized.isBlank() || !ProbeConfig.INSTANCE.debugLogs()) return;
        String line = channel + "|" + normalized;
        if (line.equals(lastChannelLine)) return;
        lastChannelLine = line;
        AuthProbe.logger().info("AuthProbe: {}: {}", channel, normalized);
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
            twoFactorRule = "";
            captchaRule = "";
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
     * @param text  Plain text
     * @param color Line colour in the pane
     */
    public static synchronized void local(String text, int color) {
        if (!enabled) return;
        if (text == null || text.isBlank()) return;
        append(text, Kind.LOCAL, color);
    }

    /**
     * Appends a line and trims the buffer.
     *
     * @param text  Plain text
     * @param kind  Origin
     * @param color Local line colour, zero when the default applies
     */
    private static void append(String text, Kind kind, int color) {
        BUFFER.addLast(new Line(System.currentTimeMillis(), kind, text, color));
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
        // Non-breaking spaces are not blank for Java, so normalise before anything else.
        text = PatternBank.normalize(text);
        if (text.isBlank()) return;

        // Skip our own output.
        if (text.startsWith(OWN_PREFIX)) return;

        // Record, unless the pane is filtered down to local logs. Scoring still happens below,
        // so a filtered pane never changes what the probe decides.
        if (!logsOnly) {
            append(text, actionBar ? Kind.ACTION_BAR : Kind.SERVER, 0);
        }

        // Two-factor and captcha signals are independent of the answer verdict: 2FA has to be
        // visible before .start as well, and a captcha line must never be read as 2FA.
        ProbeConfig config = ProbeConfig.INSTANCE;
        boolean captchaLine = PatternBank.matchesCaptcha(config.captchaPatterns(), text);
        if (config.twoFactorDetection()
                && PatternBank.matchesTwoFactor(config.twoFactorPatterns(), config.captchaPatterns(), text)) {
            twoFactorRule = PatternBank.matchesAny(config.twoFactorPatterns(), text);
            if (config.debugLogs()) {
                String candidate = twoFactorRule + "|" + text;
                if (!candidate.equals(lastTwoFactorCandidate)) {
                    lastTwoFactorCandidate = candidate;
                    AuthProbe.logger().info("AuthProbe: 2FA candidate by '{}': {}", twoFactorRule, text);
                }
            }
            return;
        }
        if (config.captchaBeforeStart() && captchaLine) {
            captchaRule = PatternBank.matchesAny(config.captchaPatterns(), text);
            return;
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
            long pause = ServerPause.parseMillis(text);
            if (pause > 0) {
                verdict = Verdict.WAIT;
                waitMillis = pause;
            } else {
                // No duration named: the server is still working on the password. Hold the
                // answer timeout instead of re-sending.
                verdict = Verdict.HOLD;
            }
            return;
        }
        if (PatternBank.matches(patterns.kick(), text)) {
            verdict = Verdict.KICK;
        }
    }
}