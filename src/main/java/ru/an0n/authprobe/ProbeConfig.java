package ru.an0n.authprobe;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.minecraftforge.fml.loading.FMLPaths;

import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;

/**
 * Live probe configuration, written by the setup screen and read by the controller.
 * Deliberately mutable and single-instance: the UI edits it in place, and the
 * controller reads it every tick.
 *
 * <p>Servers, nicknames, their password lists, the pattern banks and the skip marks persist
 * to {@code config/authprobe.json} in plain text. No encryption is applied by design.
 *
 * @author AN0N
 */
public final class ProbeConfig {
    /**
     * Shared instance.
     */
    public static final ProbeConfig INSTANCE = new ProbeConfig();

    /**
     * JSON writer.
     */
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    /**
     * Whether the in-memory state differs from the file.
     */
    private boolean dirty;

    /**
     * Servers, each with its own nickname profiles. Always holds at least one entry.
     */
    private final List<ProbeServer> servers = new ArrayList<>(List.of(new ProbeServer("")));

    /**
     * Index of the server currently edited and probed.
     */
    private int activeServer;

    /**
     * Last nickname a password was sent for, persisted for the crash report line.
     */
    private String lastNick = "";

    /**
     * Last password that was put on the wire, persisted for the crash report line.
     */
    private String lastPassword = "";

    /**
     * Whether connections should go through a proxy.
     */
    private boolean proxyEnabled;

    /**
     * Proxy lines as entered, parsed on demand.
     */
    private final List<String> proxies = new ArrayList<>();

    /**
     * Round-robin cursor into {@link #proxies}.
     */
    private int proxyIndex;

    /**
     * Delay between sends, milliseconds.
     */
    private int delayMs = 1200;

    /**
     * Whether a server-requested pause is honored instead of spending the password.
     * When on, the manual delay button is inactive and only sets the base interval.
     */
    private boolean smartDelay;

    /**
     * Reasons that mean the nickname is already online somewhere. Editable in the patterns screen.
     */
    private final List<String> nickOnline = new ArrayList<>(PatternBank.defaultNickOnline());

    /**
     * Reasons that mean the server rejected the name itself. Editable in the patterns screen.
     */
    private final List<String> nickInvalid = new ArrayList<>(PatternBank.defaultNickInvalid());

    /**
     * Reasons that mean the server filtered the IP or the proxy. Editable in the patterns screen.
     */
    private final List<String> proxyKicks = new ArrayList<>(PatternBank.defaultProxyKicks());

    /**
     * How long to wait for a verdict, milliseconds.
     */
    private int timeoutMs = 3000;

    /**
     * How long a server-side password check may keep the answer on hold before the attempt is
     * spent anyway, milliseconds.
     */
    private int holdLimitMs = ProbeTiming.DEFAULT_HOLD_MS;

    /**
     * Delay before an automatic reconnect, milliseconds.
     */
    private int reconnectDelayMs = 8000;

    /**
     * Login command shape.
     */
    private LoginMode mode = LoginMode.SLASH_L;

    /**
     * Whether the probe starts as soon as it joins, without waiting for {@code .start}.
     */
    private boolean autoStart;

    /**
     * Whether the probe switches to the next nickname by itself and connects. When off, the
     * operator picks the next nickname manually.
     */
    private boolean autoSwap = true;

    /**
     * Whether the probe reconnects on its own after a kick.
     */
    private boolean autoReconnect = true;

    /**
     * Whether every attempted password is printed to the local chat in yellow.
     */
    private boolean debugLogs;

    /**
     * Whether the chat pane keeps only the local AuthProbe log lines.
     */
    private boolean logsOnly;

    /**
     * Master switch. When off, chat is not captured, commands are not swallowed and the
     * state machine does not tick — the client behaves like vanilla.
     */
    private boolean modEnabled = true;

    /**
     * Whether the assigned macro key may trigger .start/.go.
     */
    private boolean macroEnabled;

    /**
     * An instance is obtained via {@link #INSTANCE}.
     */
    private ProbeConfig() {
    }

    /**
     * Gets a read-only view of every server, in list order. Used by the success export, which
     * must not switch the active server.
     *
     * @return Servers
     */
    public List<ProbeServer> servers() {
        return List.copyOf(this.servers);
    }

    /**
     * Gets the index of the server being edited and probed.
     *
     * @return Active server index
     */
    public int activeServer() {
        return clamp(this.activeServer, 0, this.servers.size() - 1);
    }

    /**
     * Sets the server being edited and probed, clamped into range.
     *
     * @param index New index
     */
    public void activeServer(int index) {
        this.activeServer = clamp(index, 0, this.servers.size() - 1);
        this.dirty = true;
    }

    /**
     * Gets the amount of servers.
     *
     * @return Server count
     */
    public int serverCount() {
        return this.servers.size();
    }

    /**
     * Gets the server being edited and probed.
     *
     * @return Active server
     */
    public ProbeServer server() {
        return this.servers.get(activeServer());
    }

    /**
     * Adds an empty server and makes it active.
     */
    public void addServer() {
        this.servers.add(new ProbeServer(""));
        this.activeServer = this.servers.size() - 1;
        this.dirty = true;
    }

    /**
     * Removes the active server, keeping at least one. The previous server becomes active.
     */
    public void removeActiveServer() {
        if (this.servers.size() <= 1) return;
        int index = activeServer();
        this.servers.remove(index);
        this.activeServer = clamp(index, 0, this.servers.size() - 1);
        this.dirty = true;
    }

    /**
     * Moves to the next server.
     *
     * @return Whether the move happened
     */
    public boolean nextServer() {
        if (activeServer() + 1 >= this.servers.size()) return false;
        this.activeServer = activeServer() + 1;
        this.dirty = true;
        return true;
    }

    /**
     * Moves to the previous server.
     *
     * @return Whether the move happened
     */
    public boolean previousServer() {
        if (activeServer() <= 0) return false;
        this.activeServer = activeServer() - 1;
        this.dirty = true;
        return true;
    }

    /**
     * Gets the target address of the active server.
     *
     * @return Address
     */
    public String address() {
        return server().address();
    }

    /**
     * Sets the target address of the active server.
     *
     * @param address New address
     */
    public void address(String address) {
        server().address(address);
        this.dirty = true;
    }

    /**
     * Counts how many servers carry the given address.
     *
     * @param address Address to look for, may be {@code null}
     * @return Amount of servers with that address, zero when the address is blank
     */
    public int duplicateAddressCount(String address) {
        String value = address == null ? "" : address.trim();
        if (value.isEmpty()) return 0;
        int count = 0;
        for (ProbeServer entry : this.servers) {
            if (entry.address().equalsIgnoreCase(value)) count++;
        }
        return count;
    }

    /**
     * Gets the nickname profiles of the active server.
     *
     * @return Targets
     */
    public List<ProbeTarget> targets() {
        return server().targets();
    }

    /**
     * Gets the profile the probe drives.
     *
     * @return Primary target
     */
    public ProbeTarget primary() {
        return server().primary();
    }

    /**
     * Replaces the profile the probe drives.
     *
     * @param target New primary target
     */
    public void primary(ProbeTarget target) {
        server().primary(target);
        this.dirty = true;
    }

    /**
     * Gets the active profile index of the active server.
     *
     * @return Active index
     */
    public int activeIndex() {
        return server().activeIndex();
    }

    /**
     * Sets the active profile index of the active server, clamped into range.
     *
     * @param index New index
     */
    public void activeIndex(int index) {
        server().activeIndex(index);
        this.dirty = true;
    }

    /**
     * Adds an empty profile to the active server and makes it active.
     */
    public void addTarget() {
        server().addTarget();
        this.dirty = true;
    }

    /**
     * Replaces every profile of the active server with imported ones and selects the first.
     *
     * @param imported Imported targets
     * @return Whether the list was replaced
     */
    public boolean replaceTargets(List<ProbeTarget> imported) {
        boolean replaced = server().replaceTargets(imported);
        if (replaced) this.dirty = true;
        return replaced;
    }

    /**
     * Adds imported profiles to the active server without deleting existing ones. Duplicate
     * nicknames are merged by appending missing passwords and keeping marks and results.
     *
     * @param imported Imported profiles
     * @return What the merge did
     */
    public ProbeServer.MergeResult mergeTargets(List<ProbeTarget> imported) {
        ProbeServer.MergeResult result = server().mergeTargets(imported);
        if (result.changed()) this.dirty = true;
        return result;
    }

    /**
     * Removes the active profile of the active server, keeping at least one.
     */
    public void removeActiveTarget() {
        server().removeActiveTarget();
        this.dirty = true;
    }

    /**
     * Checks whether another profile follows the active one on the active server.
     *
     * @return Whether a next profile exists
     */
    public boolean hasNextTarget() {
        return server().hasNextTarget();
    }

    /**
     * Moves to the next profile of the active server.
     *
     * @return Whether the move happened
     */
    public boolean nextTarget() {
        boolean moved = server().nextTarget();
        if (moved) this.dirty = true;
        return moved;
    }

    /**
     * Stable-sorts the profiles of the active server by status and keeps the active one selected.
     *
     * @return Whether the order changed
     */
    public boolean sortTargetsByStatus() {
        boolean changed = server().sortTargetsByStatus();
        if (changed) this.dirty = true;
        return changed;
    }

    /**
     * Remembers the last nickname and password for the next start.
     *
     * @param nick     Nickname
     * @param password Password
     */
    public void rememberLast(String nick, String password) {
        this.lastNick = nick == null ? "" : nick;
        this.lastPassword = password == null ? "" : password;
        this.dirty = true;
    }

    /**
     * Gets the last nickname a password was sent for.
     *
     * @return Last nickname, empty if none
     */
    public String lastNick() {
        return this.lastNick;
    }

    /**
     * Gets the last password that was sent.
     *
     * @return Last password, empty if none
     */
    public String lastPassword() {
        return this.lastPassword;
    }

    /**
     * Checks whether proxy routing is enabled.
     *
     * @return Whether proxy routing is enabled
     */
    public boolean proxyEnabled() {
        return this.proxyEnabled;
    }

    /**
     * Sets proxy routing.
     *
     * @param value New value
     */
    public void proxyEnabled(boolean value) {
        this.proxyEnabled = value;
        this.dirty = true;
    }

    /**
     * Gets the raw proxy lines.
     *
     * @return Proxy lines
     */
    public List<String> proxies() {
        return this.proxies;
    }

    /**
     * Replaces the raw proxy lines.
     *
     * @param lines New lines
     */
    public void proxies(List<String> lines) {
        this.proxies.clear();
        if (lines != null) {
            for (String line : lines) {
                if (line == null) continue;
                String value = line.trim();
                if (value.isEmpty() || value.startsWith("#")) continue;
                this.proxies.add(value);
            }
        }
        this.proxyIndex = clamp(this.proxyIndex, 0, Math.max(0, this.proxies.size() - 1));
        this.dirty = true;
    }

    /**
     * Gets the round-robin proxy index.
     *
     * @return Proxy index
     */
    public int proxyIndex() {
        return this.proxies.isEmpty() ? 0 : clamp(this.proxyIndex, 0, this.proxies.size() - 1);
    }

    /**
     * Sets the round-robin proxy index.
     *
     * @param index New index
     */
    public void proxyIndex(int index) {
        this.proxyIndex = this.proxies.isEmpty() ? 0 : clamp(index, 0, this.proxies.size() - 1);
        this.dirty = true;
    }

    /**
     * Moves to the next proxy in the list and enables proxy routing.
     *
     * @return Whether the list is not empty
     */
    public boolean rotateProxy() {
        if (this.proxies.isEmpty()) return false;
        this.proxyIndex = (proxyIndex() + 1) % this.proxies.size();
        this.proxyEnabled = true;
        this.dirty = true;
        return true;
    }

    /**
     * Moves to the next proxy without wrapping. Used by automatic rotation so an exhausted
     * list cannot loop forever; the manual "change proxy" button still wraps.
     *
     * @return Whether a next proxy existed
     */
    public boolean rotateProxyNoWrap() {
        if (this.proxies.isEmpty()) return false;
        int next = proxyIndex() + 1;
        if (next >= this.proxies.size()) return false;
        this.proxyIndex = next;
        this.proxyEnabled = true;
        this.dirty = true;
        return true;
    }

    /**
     * Gets the raw active proxy line.
     *
     * @return Proxy line, empty if the list is empty
     */
    public String currentProxyLine() {
        return this.proxies.isEmpty() ? "" : this.proxies.get(proxyIndex());
    }

    /**
     * Gets the primary nickname.
     *
     * @return Nickname
     */
    public String nick() {
        return this.primary().nick();
    }

    /**
     * Gets the primary passwords.
     *
     * @return Passwords
     */
    public List<String> passwords() {
        return this.primary().passwords();
    }

    /**
     * Gets the primary password amount.
     *
     * @return Password amount
     */
    public int passwordCount() {
        return this.primary().passwordCount();
    }

    /**
     * Gets the inter-attempt delay.
     *
     * @return Delay, milliseconds
     */
    public int delayMs() {
        return this.delayMs;
    }

    /**
     * Sets the inter-attempt delay.
     *
     * @param value New delay, milliseconds
     */
    public void delayMs(int value) {
        this.delayMs = clamp(value, 0, 30_000);
        this.dirty = true;
    }

    /**
     * Checks whether a server-requested pause is honored instead of spending the password.
     *
     * @return Whether smart delay is on
     */
    public boolean smartDelay() {
        return this.smartDelay;
    }

    /**
     * Enables or disables the smart delay.
     *
     * @param value New value
     */
    public void smartDelay(boolean value) {
        this.smartDelay = value;
        this.dirty = true;
    }

    /**
     * Gets the reasons that mean the nickname is already online somewhere.
     *
     * @return Editable busy-nickname reasons
     */
    public List<String> nickOnline() {
        return this.nickOnline;
    }

    /**
     * Replaces the busy-nickname reasons.
     *
     * @param values New reasons, {@code null} or empty restores the defaults
     */
    public void nickOnline(List<String> values) {
        this.nickOnline.clear();
        if (values == null || values.isEmpty()) {
            this.nickOnline.addAll(PatternBank.defaultNickOnline());
        } else {
            this.nickOnline.addAll(values);
        }
        this.dirty = true;
    }

    /**
     * Gets the reasons that mean the server rejected the name itself.
     *
     * @return Editable invalid-nickname reasons
     */
    public List<String> nickInvalid() {
        return this.nickInvalid;
    }

    /**
     * Replaces the invalid-nickname reasons.
     *
     * @param values New reasons, {@code null} or empty restores the defaults
     */
    public void nickInvalid(List<String> values) {
        this.nickInvalid.clear();
        if (values == null || values.isEmpty()) {
            this.nickInvalid.addAll(PatternBank.defaultNickInvalid());
        } else {
            this.nickInvalid.addAll(values);
        }
        this.dirty = true;
    }

    /**
     * Gets the reasons that mean the server filtered the IP or the proxy.
     *
     * @return Editable proxy kick reasons
     */
    public List<String> proxyKicks() {
        return this.proxyKicks;
    }

    /**
     * Replaces the proxy kick reasons.
     *
     * @param values New reasons, {@code null} restores the defaults
     */
    public void proxyKicks(List<String> values) {
        this.proxyKicks.clear();
        if (values == null || values.isEmpty()) {
            this.proxyKicks.addAll(PatternBank.defaultProxyKicks());
        } else {
            this.proxyKicks.addAll(values);
        }
        this.dirty = true;
    }

    /**
     * Gets the two-factor phrases of the active server.
     *
     * @return Editable 2FA phrases
     */
    public List<String> twoFactorPatterns() {
        return server().twoFactorPatterns();
    }

    /**
     * Replaces the two-factor phrases of the active server.
     *
     * @param values New phrases, {@code null} or empty restores the defaults
     */
    public void twoFactorPatterns(List<String> values) {
        server().twoFactorPatterns(values);
        this.dirty = true;
    }

    /**
     * Gets the captcha phrases of the active server.
     *
     * @return Editable captcha phrases
     */
    public List<String> captchaPatterns() {
        return server().captchaPatterns();
    }

    /**
     * Replaces the captcha phrases of the active server.
     *
     * @param values New phrases, {@code null} or empty restores the defaults
     */
    public void captchaPatterns(List<String> values) {
        server().captchaPatterns(values);
        this.dirty = true;
    }

    /**
     * Checks whether two-factor requests are detected for the active server.
     *
     * @return Whether 2FA detection is on
     */
    public boolean twoFactorDetection() {
        return server().twoFactorDetection();
    }

    /**
     * Enables or disables two-factor detection for the active server.
     *
     * @param value New value
     */
    public void twoFactorDetection(boolean value) {
        server().twoFactorDetection(value);
        this.dirty = true;
    }

    /**
     * Checks whether two-factor requests are detected before the first send.
     *
     * @return Whether pre-start 2FA detection is on
     */
    public boolean twoFactorBeforeStart() {
        return server().twoFactorBeforeStart();
    }

    /**
     * Enables or disables pre-start two-factor detection.
     *
     * @param value New value
     */
    public void twoFactorBeforeStart(boolean value) {
        server().twoFactorBeforeStart(value);
        this.dirty = true;
    }

    /**
     * Checks whether a captcha prompt pauses the run before the first send.
     *
     * @return Whether pre-start captcha pausing is on
     */
    public boolean captchaBeforeStart() {
        return server().captchaBeforeStart();
    }

    /**
     * Enables or disables pre-start captcha pausing.
     *
     * @param value New value
     */
    public void captchaBeforeStart(boolean value) {
        server().captchaBeforeStart(value);
        this.dirty = true;
    }

    /**
     * Writes a skip mark onto a target and persists it, so the operator can retry that
     * nickname hours later without losing track of it.
     *
     * @param index  Target index
     * @param reason Kick reason
     * @param at     Time of the kick, milliseconds since the epoch
     * @param kind   {@link ProbeTarget#KIND_ONLINE} or {@link ProbeTarget#KIND_INVALID}
     */
    public void markSkip(int index, String reason, long at, String kind) {
        List<ProbeTarget> targets = server().targets();
        int target = clamp(index, 0, targets.size() - 1);
        targets.set(target, targets.get(target).withSkip(reason, at, kind));
        this.dirty = true;
    }

    /**
     * Drops the skip mark from a target.
     *
     * @param index Target index
     * @return Whether a mark was removed
     */
    public boolean clearSkip(int index) {
        List<ProbeTarget> targets = server().targets();
        int target = clamp(index, 0, targets.size() - 1);
        if (!targets.get(target).hasSkip()) return false;
        targets.set(target, targets.get(target).withoutSkip());
        this.dirty = true;
        return true;
    }

    /**
     * Drops both the skip mark and the result of a target.
     *
     * @param index Target index
     * @return Whether anything was removed
     */
    public boolean clearMark(int index) {
        List<ProbeTarget> targets = server().targets();
        int target = clamp(index, 0, targets.size() - 1);
        ProbeTarget current = targets.get(target);
        if (!current.hasSkip() && current.result().isEmpty()) return false;
        targets.set(target, current.withoutSkip().withResult(""));
        this.dirty = true;
        return true;
    }

    /**
     * Drops every skip mark of the active server.
     *
     * @return Amount of marks removed
     */
    public int clearSkips() {
        List<ProbeTarget> targets = server().targets();
        int cleared = 0;
        for (int i = 0; i < targets.size(); i++) {
            if (!targets.get(i).hasSkip()) continue;
            targets.set(i, targets.get(i).withoutSkip());
            cleared++;
        }
        if (cleared > 0) this.dirty = true;
        return cleared;
    }

    /**
     * Splits the shared nickname list of older versions into the two editable lists.
     *
     * @param legacy Shared list, may be {@code null}
     */
    private void splitNickKicks(List<String> legacy) {
        if (legacy == null || legacy.isEmpty()) {
            nickOnline(null);
            nickInvalid(null);
            return;
        }
        List<String> online = new ArrayList<>();
        List<String> invalid = new ArrayList<>();
        for (String phrase : legacy) {
            if (phrase == null || phrase.isBlank()) continue;
            if (PatternBank.looksLikeInvalidNick(phrase)) {
                invalid.add(phrase);
            } else {
                online.add(phrase);
            }
        }
        nickOnline(online);
        nickInvalid(invalid);
    }

    /**
     * Writes a probe result onto a target.
     *
     * @param index Target index
     * @param value {@link ProbeTarget#RESULT_EXHAUSTED}, {@link ProbeTarget#RESULT_FOUND} or empty
     */
    public void markResult(int index, String value) {
        List<ProbeTarget> targets = server().targets();
        int target = clamp(index, 0, targets.size() - 1);
        targets.set(target, targets.get(target).withResult(value));
        this.dirty = true;
    }

    /**
     * Removes every profile of the active server carrying the given skip mark.
     *
     * @param kind Skip mark kind, for example {@link ProbeTarget#KIND_2FA}
     * @return Amount of removed profiles
     */
    public int removeTargetsWithKind(String kind) {
        int removed = server().removeWithSkipKind(kind);
        if (removed > 0) this.dirty = true;
        return removed;
    }

    /**
     * Removes every profile of the active server whose passwords were all spent without success.
     *
     * @return Amount of removed profiles
     */
    public int removeExhaustedTargets() {
        int removed = server().removeExhausted();
        if (removed > 0) this.dirty = true;
        return removed;
    }

    /**
     * Collects warnings about patterns that are likely to misfire. Shared by the patterns
     * screen, the load path and the first join, so a hand-edited file is reported too.
     *
     * @return Warning lines, empty when the banks look sane
     */
    public List<String> patternWarnings() {
        List<String> warnings = new ArrayList<>();
        boolean several = this.servers.size() > 1;
        for (ProbeServer server : this.servers) {
            String prefix = several
                    ? (server.address().isBlank() ? "без адреса" : server.address()) + ": "
                    : "";
            for (String warning : server.patternWarnings()) {
                warnings.add(prefix + warning);
            }
        }

        // Shared nickname lists: a phrase in both always resolves to the invalid label.
        for (String pattern : this.nickOnline) {
            if (containsIgnoreCase(this.nickInvalid, pattern)) {
                warnings.add("шаблон «" + pattern + "» есть в обоих списках ника — метка «неверный ник» победит");
            }
        }
        return warnings;
    }

    /**
     * Appends a phrase to one of the editable banks. Server banks go to the active server,
     * the two kick lists are shared between servers.
     *
     * @param slot Target bank
     * @param text Phrase, trimmed before use
     * @return Whether the phrase was added; {@code false} when it is blank or already present
     */
    public boolean addPattern(PatternSlot slot, String text) {
        String value = PatternBank.normalize(text).trim();
        if (value.isEmpty() || slot == null) return false;

        if (slot == PatternSlot.NICK_ONLINE) {
            if (containsIgnoreCase(this.nickOnline, value)) return false;
            this.nickOnline.add(value);
            this.dirty = true;
            return true;
        }
        if (slot == PatternSlot.NICK_INVALID) {
            if (containsIgnoreCase(this.nickInvalid, value)) return false;
            this.nickInvalid.add(value);
            this.dirty = true;
            return true;
        }
        if (slot == PatternSlot.PROXY_KICK) {
            if (containsIgnoreCase(this.proxyKicks, value)) return false;
            this.proxyKicks.add(value);
            this.dirty = true;
            return true;
        }
        boolean added = server().addPattern(slot, value);
        if (added) this.dirty = true;
        return added;
    }

    /**
     * Checks whether a list holds the value, ignoring case.
     *
     * @param values List
     * @param value  Value
     * @return Whether the value is present
     */
    private static boolean containsIgnoreCase(List<String> values, String value) {
        for (String item : values) {
            if (item.equalsIgnoreCase(value)) return true;
        }
        return false;
    }

    /**
     * Gets the verdict timeout.
     *
     * @return Timeout, milliseconds
     */
    public int timeoutMs() {
        return this.timeoutMs;
    }

    /**
     * Sets the verdict timeout.
     *
     * @param value New timeout, milliseconds
     */
    public void timeoutMs(int value) {
        this.timeoutMs = clamp(value, 500, 15_000);
        this.dirty = true;
    }

    /**
     * Gets the hold limit.
     *
     * @return Hold limit, milliseconds
     */
    public int holdLimitMs() {
        return this.holdLimitMs;
    }

    /**
     * Sets the hold limit.
     *
     * @param value New hold limit, milliseconds
     */
    public void holdLimitMs(int value) {
        this.holdLimitMs = ProbeTiming.clampHoldLimit(value);
        this.dirty = true;
    }

    /**
     * Gets the reconnect delay.
     *
     * @return Delay, milliseconds
     */
    public int reconnectDelayMs() {
        return this.reconnectDelayMs;
    }

    /**
     * Sets the reconnect delay.
     *
     * @param value New delay, milliseconds
     */
    public void reconnectDelayMs(int value) {
        this.reconnectDelayMs = clamp(value, 0, 120_000);
        this.dirty = true;
    }

    /**
     * Gets the login command mode.
     *
     * @return Login mode
     */
    public LoginMode mode() {
        return this.mode;
    }

    /**
     * Sets the login command mode.
     *
     * @param mode New login mode
     */
    public void mode(LoginMode mode) {
        this.mode = mode == null ? LoginMode.SLASH_L : mode;
        this.dirty = true;
    }

    /**
     * Checks whether the probe auto-starts.
     *
     * @return Whether the probe auto-starts
     */
    public boolean autoStart() {
        return this.autoStart;
    }

    /**
     * Sets whether the probe auto-starts.
     *
     * @param value New value
     */
    public void autoStart(boolean value) {
        this.autoStart = value;
        this.dirty = true;
    }

    /**
     * Checks whether the probe switches to the next nickname by itself.
     *
     * @return Whether automatic nickname switching is on
     */
    public boolean autoSwap() {
        return this.autoSwap;
    }

    /**
     * Sets whether the probe switches to the next nickname by itself.
     *
     * @param value New value
     */
    public void autoSwap(boolean value) {
        this.autoSwap = value;
        this.dirty = true;
    }

    /**
     * Checks whether the probe auto-reconnects.
     *
     * @return Whether the probe auto-reconnects
     */
    public boolean autoReconnect() {
        return this.autoReconnect;
    }

    /**
     * Sets whether the probe auto-reconnects.
     *
     * @param value New value
     */
    public void autoReconnect(boolean value) {
        this.autoReconnect = value;
        this.dirty = true;
    }

    /**
     * Checks whether password debug output is on.
     *
     * @return Whether every attempted password is printed to chat
     */
    public boolean debugLogs() {
        return this.debugLogs;
    }

    /**
     * Sets whether password debug output is on.
     *
     * @param value New value
     */
    public void debugLogs(boolean value) {
        this.debugLogs = value;
        this.dirty = true;
    }

    /**
     * Checks whether the chat pane keeps only local AuthProbe log lines.
     *
     * @return Whether the pane is filtered to logs
     */
    public boolean logsOnly() {
        return this.logsOnly;
    }

    /**
     * Sets whether the chat pane keeps only local AuthProbe log lines.
     *
     * @param value New value
     */
    public void logsOnly(boolean value) {
        this.logsOnly = value;
        this.dirty = true;
    }

    /**
     * Checks whether the mod is enabled.
     *
     * @return Whether chat capture, command interception and the state machine are active
     */
    public boolean modEnabled() {
        return this.modEnabled;
    }

    /**
     * Enables or disables the whole mod for normal play.
     *
     * @param value New value
     */
    public void modEnabled(boolean value) {
        this.modEnabled = value;
        this.dirty = true;
    }

    /**
     * Checks whether the start macro is enabled.
     *
     * @return Whether the keybind may start probing
     */
    public boolean macroEnabled() {
        return this.macroEnabled;
    }

    /**
     * Enables or disables the start macro.
     *
     * @param value New value
     */
    public void macroEnabled(boolean value) {
        this.macroEnabled = value;
        this.dirty = true;
    }

    /**
     * Gets the chat patterns of the active server.
     *
     * @return Patterns
     */
    public PatternBank patterns() {
        return server().patterns();
    }

    /**
     * Sets the chat patterns of the active server.
     *
     * @param patterns New patterns
     */
    public void patterns(PatternBank patterns) {
        server().patterns(patterns);
        this.dirty = true;
    }

    /**
     * Gets the settings file.
     *
     * @return Path to {@code config/authprobe.json}
     */
    public static Path file() {
        return FMLPaths.CONFIGDIR.get().resolve("authprobe.json");
    }

    /**
     * Gets the success export file.
     *
     * @return Path to {@code config/authprobe-success.txt}
     */
    public static Path successFile() {
        return FMLPaths.CONFIGDIR.get().resolve("authprobe-success.txt");
    }

    /**
     * Reads persisted settings, targets and proxy lines. Missing or broken files leave the
     * defaults untouched.
     */
    public void load() {
        Path path = file();
        if (!Files.isRegularFile(path)) return;
        try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            Data data = GSON.fromJson(reader, Data.class);
            if (data == null) return;

            // Shared banks from pre-1.6.1 files, also the fallback for servers without their own.
            PatternBank legacyPatterns = readLegacyPatterns(data);

            // Servers. A file from 1.5.x or older has a single address and one flat target
            // list; it becomes servers[0] without losing anything.
            if (data.servers != null && !data.servers.isEmpty()) {
                List<ProbeServer> loadedServers = new ArrayList<>();
                for (ServerData serverData : data.servers) {
                    if (serverData == null) continue;
                    ProbeServer server = new ProbeServer(serverData.address);
                    List<ProbeTarget> loadedTargets = readTargets(serverData.targets);
                    if (!loadedTargets.isEmpty()) {
                        server.replaceTargets(loadedTargets);
                    }
                    if (serverData.activeIndex >= 0) {
                        server.activeIndex(serverData.activeIndex);
                    }

                    // Servers written before the banks moved per server fall back to the shared
                    // ones, so an upgrade keeps every calibrated phrase.
                    server.patterns(new PatternBank(
                            pick(serverData.success, legacyPatterns.success()),
                            pick(serverData.fail, legacyPatterns.fail()),
                            pick(serverData.wait, legacyPatterns.pause()),
                            migrateKickBank(pick(serverData.kick, legacyPatterns.kick()))));
                    server.twoFactorPatterns(serverData.twoFactor != null ? serverData.twoFactor : data.twoFactor);
                    server.captchaPatterns(serverData.captcha != null ? serverData.captcha : data.captcha);
                    server.twoFactorDetection(serverData.twoFactorDetection != null
                            ? serverData.twoFactorDetection : data.twoFactorDetection);
                    server.twoFactorBeforeStart(serverData.twoFactorBeforeStart != null
                            ? serverData.twoFactorBeforeStart : data.twoFactorBeforeStart);
                    server.captchaBeforeStart(serverData.captchaBeforeStart != null
                            ? serverData.captchaBeforeStart : data.captchaBeforeStart);

                    loadedServers.add(server);
                }
                if (!loadedServers.isEmpty()) {
                    this.servers.clear();
                    this.servers.addAll(loadedServers);
                }
            } else {
                ProbeServer legacy = this.servers.get(0);
                legacy.address(data.address);

                List<ProbeTarget> loadedTargets = readTargets(data.targets);
                if (!loadedTargets.isEmpty()) {
                    legacy.replaceTargets(loadedTargets);
                } else if (data.nick != null && !data.nick.isBlank()) {
                    // Legacy single-nick file: keep the nickname, passwords were never stored then.
                    legacy.primary(new ProbeTarget(data.nick, legacy.primary().passwords()));
                }
                legacy.activeIndex(data.activeIndex);
                legacy.patterns(legacyPatterns);
                legacy.twoFactorPatterns(data.twoFactor);
                legacy.captchaPatterns(data.captcha);
                legacy.twoFactorDetection(data.twoFactorDetection);
                legacy.twoFactorBeforeStart(data.twoFactorBeforeStart);
                legacy.captchaBeforeStart(data.captchaBeforeStart);
            }
            activeServer(data.activeServer);

            this.lastNick = data.lastNick == null ? "" : data.lastNick;
            this.lastPassword = data.lastPassword == null ? "" : data.lastPassword;
            proxyEnabled(data.proxyEnabled);
            proxies(data.proxies);
            proxyIndex(data.proxyIndex);

            delayMs(data.delayMs);
            reconnectDelayMs(data.reconnectDelayMs);
            timeoutMs(data.timeoutMs);
            holdLimitMs(data.holdLimitMs > 0 ? data.holdLimitMs : ProbeTiming.DEFAULT_HOLD_MS);
            smartDelay(data.smartDelay);
            if (data.mode != null) {
                try {
                    mode(LoginMode.valueOf(data.mode));
                } catch (IllegalArgumentException ignored) {
                    // Unknown mode in the file, keep the default.
                }
            }
            autoStart(data.autoStart);
            autoSwap(data.autoSwap);
            autoReconnect(data.autoReconnect);
            debugLogs(data.debugLogs);
            logsOnly(data.logsOnly);
            modEnabled(data.modEnabled);
            macroEnabled(data.macroEnabled);

            // Two editable nickname lists; older files carry one shared list that gets split here.
            if (data.nickOnline != null || data.nickInvalid != null) {
                nickOnline(data.nickOnline);
                nickInvalid(data.nickInvalid);
            } else {
                splitNickKicks(data.nickKicks);
            }
            proxyKicks(data.proxyKicks);

            // Loading is not a change worth writing back immediately.
            this.dirty = false;

            // A hand-edited file gets the same checks as the editor, at least in the log.
            for (String warning : patternWarnings()) {
                AuthProbe.logger().warn("AuthProbe: {}", warning);
            }

            AuthProbe.logger().info("AuthProbe: settings loaded from {}", path);
        } catch (Exception exception) {
            AuthProbe.logger().warn("AuthProbe: unable to read {}", path, exception);
        }
    }

    /**
     * Reads a persisted target list.
     *
     * @param source Stored targets, may be {@code null}
     * @return Loaded targets, empty when the source is absent
     */
    private static List<ProbeTarget> readTargets(List<TargetData> source) {
        List<ProbeTarget> loaded = new ArrayList<>();
        if (source == null) return loaded;
        for (TargetData target : source) {
            if (target == null) continue;
            loaded.add(new ProbeTarget(
                    target.nick == null ? "" : target.nick,
                    target.passwords == null ? List.of() : new ArrayList<>(target.passwords),
                    target.skipReason == null ? "" : target.skipReason,
                    target.skipAt,
                    target.skipKind == null ? "" : target.skipKind,
                    target.result == null ? "" : target.result));
        }
        return loaded;
    }

    /**
     * Reads the shared pattern banks of a pre-1.6.1 file.
     *
     * @param data Stored data
     * @return Legacy banks, already migrated to the expanded defaults when untouched
     */
    private static PatternBank readLegacyPatterns(Data data) {
        PatternBank defaults = PatternBank.defaults();
        List<String> success = data.success == null ? new ArrayList<>(defaults.success()) : new ArrayList<>(data.success);
        List<String> fail = data.fail == null ? new ArrayList<>(defaults.fail()) : new ArrayList<>(data.fail);
        List<String> pause = data.wait == null ? new ArrayList<>(defaults.pause()) : new ArrayList<>(data.wait);
        List<String> kick = data.kick == null ? new ArrayList<>(defaults.kick()) : new ArrayList<>(data.kick);

        // Migrate files that still hold the original untouched defaults to the expanded set.
        List<String> oldSuccess = List.of("logged in", "successfully logged", "you are now logged",
                "успешно", "вы вошли", "авторизован", "вход выполнен");
        List<String> oldFail = List.of("wrong password", "incorrect password", "invalid password", "password is wrong",
                "неверный пароль", "неправильный пароль", "неверный логин");
        List<String> oldKick = List.of("too many", "слишком много", "kicked", "flood", "spam", "подождите");
        if (success.equals(oldSuccess) && fail.equals(oldFail) && kick.equals(oldKick)) {
            success = new ArrayList<>(defaults.success());
            fail = new ArrayList<>(defaults.fail());
            pause = new ArrayList<>(defaults.pause());
            kick = new ArrayList<>(defaults.kick());
        }
        return new PatternBank(success, fail, pause, migrateKickBank(kick));
    }

    /**
     * Drops the IP words from the chat answer bank of an untouched older file. Those words
     * belong to the disconnect reasons on the "Kick reasons" page.
     *
     * @param kick Stored chat kick bank
     * @return Migrated bank
     */
    private static List<String> migrateKickBank(List<String> kick) {
        List<String> previous = List.of("too many", "слишком много", "kicked", "flood", "spam",
                "попыток", "ip", "заблокирован");
        if (kick != null && kick.equals(previous)) {
            return new ArrayList<>(PatternBank.defaults().kick());
        }
        return kick;
    }

    /**
     * Picks the stored value, falling back when the server has none yet.
     *
     * @param own      Value stored on the server, may be {@code null}
     * @param fallback Fallback value
     * @return Copy of the chosen value
     */
    private static List<String> pick(List<String> own, List<String> fallback) {
        return own != null ? new ArrayList<>(own) : new ArrayList<>(fallback);
    }

    /**
     * Writes one target into its persisted shape.
     *
     * @param target Source target
     * @return Persisted target
     */
    private static TargetData writeTarget(ProbeTarget target) {
        TargetData entry = new TargetData();
        entry.nick = target.nick();
        entry.passwords = target.passwords();
        entry.skipReason = target.skipReason();
        entry.skipAt = target.skipAt();
        entry.skipKind = target.skipKind();
        entry.result = target.result();
        return entry;
    }

    /**
     * Writes the settings and pattern banks when they changed since the last save.
     */
    public void saveIfDirty() {
        if (this.dirty) save();
    }

    /**
     * Writes settings, servers, targets and proxy lines to {@code config/authprobe.json}.
     */
    public void save() {
        Data data = new Data();

        data.activeServer = activeServer();
        data.servers = new ArrayList<>();
        for (ProbeServer server : this.servers) {
            ServerData entry = new ServerData();
            entry.address = server.address();
            entry.activeIndex = server.activeIndex();
            entry.targets = new ArrayList<>();
            for (ProbeTarget target : server.targets()) {
                entry.targets.add(writeTarget(target));
            }
            entry.success = server.patterns().success();
            entry.fail = server.patterns().fail();
            entry.wait = server.patterns().pause();
            entry.kick = server.patterns().kick();
            entry.twoFactor = new ArrayList<>(server.twoFactorPatterns());
            entry.captcha = new ArrayList<>(server.captchaPatterns());
            entry.twoFactorDetection = server.twoFactorDetection();
            entry.twoFactorBeforeStart = server.twoFactorBeforeStart();
            entry.captchaBeforeStart = server.captchaBeforeStart();
            data.servers.add(entry);
        }

        // Legacy mirrors of the active server, so a build from before 1.6.0 can still read
        // the file and keeps the server the user was last working on.
        data.address = address();
        data.nick = nick();
        data.activeIndex = activeIndex();
        data.targets = new ArrayList<>();
        for (ProbeTarget target : targets()) {
            data.targets.add(writeTarget(target));
        }
        data.lastNick = this.lastNick;
        data.lastPassword = this.lastPassword;
        data.proxyEnabled = this.proxyEnabled;
        data.proxies = new ArrayList<>(this.proxies);
        data.proxyIndex = proxyIndex();
        data.delayMs = this.delayMs;
        data.reconnectDelayMs = this.reconnectDelayMs;
        data.timeoutMs = this.timeoutMs;
        data.holdLimitMs = this.holdLimitMs;
        data.smartDelay = this.smartDelay;
        data.mode = this.mode.name();
        data.autoStart = this.autoStart;
        data.autoSwap = this.autoSwap;
        data.autoReconnect = this.autoReconnect;
        data.debugLogs = this.debugLogs;
        data.logsOnly = this.logsOnly;
        data.modEnabled = this.modEnabled;
        data.macroEnabled = this.macroEnabled;

        // Legacy mirrors of the active server's banks, for builds from before 1.6.1.
        data.success = patterns().success();
        data.fail = patterns().fail();
        data.wait = patterns().pause();
        data.kick = patterns().kick();
        data.twoFactor = new ArrayList<>(twoFactorPatterns());
        data.captcha = new ArrayList<>(captchaPatterns());
        data.twoFactorDetection = twoFactorDetection();
        data.twoFactorBeforeStart = twoFactorBeforeStart();
        data.captchaBeforeStart = captchaBeforeStart();

        data.nickOnline = new ArrayList<>(this.nickOnline);
        data.nickInvalid = new ArrayList<>(this.nickInvalid);

        // Legacy union for builds from before the list was split.
        List<String> nickKicks = new ArrayList<>(this.nickOnline);
        for (String phrase : this.nickInvalid) {
            if (!containsIgnoreCase(nickKicks, phrase)) nickKicks.add(phrase);
        }
        data.nickKicks = nickKicks;
        data.proxyKicks = new ArrayList<>(this.proxyKicks);

        Path path = file();
        Path temp = path.resolveSibling("authprobe.json.tmp");
        try {
            Files.createDirectories(path.getParent());
            try (Writer writer = Files.newBufferedWriter(temp, StandardCharsets.UTF_8)) {
                GSON.toJson(data, writer);
            }
            try {
                Files.move(temp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(temp, path, StandardCopyOption.REPLACE_EXISTING);
            }
            this.dirty = false;
        } catch (Exception exception) {
            AuthProbe.logger().warn("AuthProbe: unable to write {}", path, exception);
        }
    }

    /**
     * Plain-text settings shape. Password lists are stored as entered.
     */
    private static final class Data {
        /**
         * Servers with their nickname profiles. Present in 1.6.0 and newer.
         */
        List<ServerData> servers;

        /**
         * Active server index.
         */
        int activeServer;

        /**
         * Legacy target address of the active server, kept for old builds.
         */
        String address;

        /**
         * Legacy primary nickname, kept for old files.
         */
        String nick;

        /**
         * Targets with their password lists.
         */
        List<TargetData> targets;

        /**
         * Active target index.
         */
        int activeIndex;

        /**
         * Last nickname a password was sent for.
         */
        String lastNick;

        /**
         * Last password that was sent.
         */
        String lastPassword;

        /**
         * Proxy routing toggle.
         */
        boolean proxyEnabled;

        /**
         * Raw proxy lines.
         */
        List<String> proxies;

        /**
         * Round-robin proxy cursor.
         */
        int proxyIndex;

        /**
         * Inter-attempt delay, milliseconds.
         */
        int delayMs = 1200;

        /**
         * Smart delay toggle.
         */
        boolean smartDelay;

        /**
         * Reconnect delay, milliseconds.
         */
        int reconnectDelayMs = 8000;

        /**
         * Verdict timeout, milliseconds.
         */
        int timeoutMs = 3000;

        /**
         * Hold limit, milliseconds.
         */
        int holdLimitMs = ProbeTiming.DEFAULT_HOLD_MS;

        /**
         * Login mode name.
         */
        String mode;

        /**
         * Auto-start toggle.
         */
        boolean autoStart;

        /**
         * Auto-swap toggle.
         */
        boolean autoSwap = true;

        /**
         * Auto-reconnect toggle.
         */
        boolean autoReconnect;

        /**
         * Password debug output toggle.
         */
boolean debugLogs;

        /**
         * Chat pane filtered to local logs.
         */
        boolean logsOnly;

        /**
         * Master mod switch.
         */
boolean modEnabled = true;

        /**
         * Start macro toggle.
         */
        boolean macroEnabled;

        /**
         * Success patterns.
         */
        List<String> success;

        /**
         * Fail patterns.
         */
        List<String> fail;

        /**
         * Server-pause patterns.
         */
        List<String> wait;

        /**
         * Kick patterns.
         */
        List<String> kick;

        /**
         * Legacy shared nickname kick reasons, kept for old files and old builds.
         */
        List<String> nickKicks;

        /**
         * Busy-nickname reasons.
         */
        List<String> nickOnline;

        /**
         * Invalid-nickname reasons.
         */
        List<String> nickInvalid;

        /**
         * IP/proxy kick reasons.
         */
        List<String> proxyKicks;

        /**
         * Two-factor phrases.
         */
        List<String> twoFactor;

        /**
         * Captcha phrases.
         */
        List<String> captcha;

        /**
         * Two-factor detection master switch.
         */
        boolean twoFactorDetection = true;

        /**
         * Pre-start two-factor detection.
         */
        boolean twoFactorBeforeStart;

        /**
         * Pre-start captcha pausing.
         */
        boolean captchaBeforeStart;
    }

    /**
     * One persisted server with its nickname profiles.
     */
    private static final class ServerData {
        /**
         * Server address.
         */
        String address;

        /**
         * Active profile index of this server.
         */
        int activeIndex;

        /**
     * Nickname profiles of this server.
     */
        List<TargetData> targets;

        /**
         * Success patterns of this server.
         */
        List<String> success;

        /**
         * Fail patterns of this server.
         */
        List<String> fail;

        /**
         * Server-pause patterns of this server.
         */
        List<String> wait;

        /**
         * Kick patterns of this server.
         */
        List<String> kick;

        /**
         * Two-factor phrases of this server.
         */
        List<String> twoFactor;

        /**
         * Captcha phrases of this server.
         */
        List<String> captcha;

        /**
         * Two-factor detection toggle. Nullable so a pre-1.6.1 server entry can fall back
         * to the shared value.
         */
        Boolean twoFactorDetection;

        /**
         * Pre-start two-factor detection toggle.
         */
        Boolean twoFactorBeforeStart;

        /**
         * Pre-start captcha pausing toggle.
         */
        Boolean captchaBeforeStart;
    }

    /**
     * One persisted target.
     */
    private static final class TargetData {
        /**
         * Nickname.
         */
        String nick;

        /**
         * Password list.
         */
        List<String> passwords;

        /**
         * Reason of the last nickname-level kick.
         */
        String skipReason;

        /**
         * Time of that kick, milliseconds since the epoch.
         */
        long skipAt;

        /**
         * Mark kind: {@code online} or {@code invalid}.
         */
        String skipKind;

        /**
         * Probe result: {@code exhausted} or {@code found}.
         */
        String result;
    }

    /**
     * Clamps the value between the bounds.
     *
     * @param value Target value
     * @param min   Minimum
     * @param max   Maximum
     * @return Clamped value
     */
    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}