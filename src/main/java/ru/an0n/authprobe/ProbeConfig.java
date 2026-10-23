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
 * <p>Settings and pattern banks persist to {@code config/authprobe.json} in plain text.
 * The password list is deliberately not written to disk.
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
     * Target server address.
     */
    private String address = "";

    /**
     * Targets. The probe drives {@code targets[activeIndex]}.
     */
    private final List<ProbeTarget> targets = new ArrayList<>(List.of(new ProbeTarget("", List.of())));

    /**
     * Index of the target currently edited and probed.
     */
    private int activeIndex;

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
     * How long to wait for a verdict, milliseconds.
     */
    private int timeoutMs = 3000;

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
     * Whether the probe reconnects on its own after a kick.
     */
    private boolean autoReconnect = true;

    /**
     * Whether every attempted password is printed to the local chat in yellow.
     */
    private boolean debugLogs;

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
     * Chat patterns.
     */
    private PatternBank patterns = PatternBank.defaults();

    /**
     * An instance is obtained via {@link #INSTANCE}.
     */
    private ProbeConfig() {
    }

    /**
     * Gets the target address.
     *
     * @return Address
     */
    public String address() {
        return this.address;
    }

    /**
     * Sets the target address.
     *
     * @param address New address
     */
    public void address(String address) {
        this.address = address == null ? "" : address.trim();
        this.dirty = true;
    }

    /**
     * Gets the targets.
     *
     * @return Targets
     */
    public List<ProbeTarget> targets() {
        return this.targets;
    }

    /**
     * Gets the target the probe drives.
     *
     * @return Primary target
     */
    public ProbeTarget primary() {
        return this.targets.get(this.clampedActive());
    }

    /**
     * Replaces the target the probe drives.
     *
     * @param target New primary target
     */
    public void primary(ProbeTarget target) {
        this.targets.set(this.clampedActive(), target == null ? new ProbeTarget("", List.of()) : target);
        this.dirty = true;
    }

    /**
     * Gets the active target index.
     *
     * @return Active index
     */
    public int activeIndex() {
        return this.clampedActive();
    }

    /**
     * Sets the active target index, clamped into range.
     *
     * @param index New index
     */
    public void activeIndex(int index) {
        this.activeIndex = clamp(index, 0, this.targets.size() - 1);
        this.dirty = true;
    }

    /**
     * Adds an empty target and makes it active.
     */
    public void addTarget() {
        this.targets.add(new ProbeTarget("", List.of()));
        this.activeIndex = this.targets.size() - 1;
        this.dirty = true;
    }

    /**
     * Replaces every target with imported profiles and selects the first one.
     *
     * @param imported Imported targets
     * @return Whether the list was replaced
     */
    public boolean replaceTargets(List<ProbeTarget> imported) {
        if (imported == null || imported.isEmpty()) return false;
        this.targets.clear();
        this.targets.addAll(imported);
        this.activeIndex = 0;
        this.dirty = true;
        return true;
    }

    /**
     * Removes the active target, keeping at least one. The previous target becomes active.
     */
    public void removeActiveTarget() {
        if (this.targets.size() <= 1) return;
        int index = this.clampedActive();
        this.targets.remove(index);
        this.activeIndex = clamp(index, 0, this.targets.size() - 1);
        this.dirty = true;
    }

    /**
     * Checks whether another target follows the active one.
     *
     * @return Whether a next target exists
     */
    public boolean hasNextTarget() {
        return this.clampedActive() + 1 < this.targets.size();
    }

    /**
     * Moves to the next target.
     *
     * @return Whether the move happened
     */
    public boolean nextTarget() {
        if (!hasNextTarget()) return false;
        this.activeIndex = this.clampedActive() + 1;
        this.dirty = true;
        return true;
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
     * Gets the raw active proxy line.
     *
     * @return Proxy line, empty if the list is empty
     */
    public String currentProxyLine() {
        return this.proxies.isEmpty() ? "" : this.proxies.get(proxyIndex());
    }

    /**
     * Gets the active index clamped into the target list.
     *
     * @return Clamped active index
     */
    private int clampedActive() {
        return clamp(this.activeIndex, 0, this.targets.size() - 1);
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
     * Gets the chat patterns.
     *
     * @return Patterns
     */
    public PatternBank patterns() {
        return this.patterns;
    }

    /**
     * Sets the chat patterns.
     *
     * @param patterns New patterns
     */
    public void patterns(PatternBank patterns) {
        this.patterns = patterns == null ? PatternBank.defaults() : patterns;
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
     * Reads persisted settings, targets and proxy lines. Missing or broken files leave the
     * defaults untouched.
     */
    public void load() {
        Path path = file();
        if (!Files.isRegularFile(path)) return;
        try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            Data data = GSON.fromJson(reader, Data.class);
            if (data == null) return;

            address(data.address);

            // Targets. The legacy single-nick field is still read when the list is absent.
            if (data.targets != null && !data.targets.isEmpty()) {
                List<ProbeTarget> loaded = new ArrayList<>();
                for (TargetData target : data.targets) {
                    if (target == null) continue;
                    loaded.add(new ProbeTarget(
                            target.nick == null ? "" : target.nick,
                            target.passwords == null ? List.of() : new ArrayList<>(target.passwords)));
                }
                if (!loaded.isEmpty()) {
                    this.targets.clear();
                    this.targets.addAll(loaded);
                }
            } else if (data.nick != null && !data.nick.isBlank()) {
                // Legacy single-nick file: keep the nickname, passwords were never stored then.
                this.targets.set(0, new ProbeTarget(data.nick, this.targets.get(0).passwords()));
            }
            activeIndex(data.activeIndex);
            this.lastNick = data.lastNick == null ? "" : data.lastNick;
            this.lastPassword = data.lastPassword == null ? "" : data.lastPassword;
            proxyEnabled(data.proxyEnabled);
            proxies(data.proxies);
            proxyIndex(data.proxyIndex);

            delayMs(data.delayMs);
            reconnectDelayMs(data.reconnectDelayMs);
            if (data.mode != null) {
                try {
                    mode(LoginMode.valueOf(data.mode));
                } catch (IllegalArgumentException ignored) {
                    // Unknown mode in the file, keep the default.
                }
            }
            autoStart(data.autoStart);
            autoReconnect(data.autoReconnect);
            debugLogs(data.debugLogs);
            modEnabled(data.modEnabled);
            macroEnabled(data.macroEnabled);

            PatternBank defaults = PatternBank.defaults();
            List<String> success = data.success == null ? new ArrayList<>(defaults.success()) : new ArrayList<>(data.success);
            List<String> fail = data.fail == null ? new ArrayList<>(defaults.fail()) : new ArrayList<>(data.fail);
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
                kick = new ArrayList<>(defaults.kick());
            }
            patterns(new PatternBank(success, fail, kick));

            // Loading is not a change worth writing back immediately.
            this.dirty = false;
            AuthProbe.logger().info("AuthProbe: settings loaded from {}", path);
        } catch (Exception exception) {
            AuthProbe.logger().warn("AuthProbe: unable to read {}", path, exception);
        }
    }

    /**
     * Writes the settings and pattern banks when they changed since the last save.
     */
    public void saveIfDirty() {
        if (this.dirty) save();
    }

    /**
     * Writes settings, targets and proxy lines to {@code config/authprobe.json} in plain text.
     */
    public void save() {
        Data data = new Data();
        data.address = this.address;
        data.nick = nick();
        data.targets = new ArrayList<>();
        for (ProbeTarget target : this.targets) {
            TargetData entry = new TargetData();
            entry.nick = target.nick();
            entry.passwords = target.passwords();
            data.targets.add(entry);
        }
        data.activeIndex = activeIndex();
        data.lastNick = this.lastNick;
        data.lastPassword = this.lastPassword;
        data.proxyEnabled = this.proxyEnabled;
        data.proxies = new ArrayList<>(this.proxies);
        data.proxyIndex = proxyIndex();
        data.delayMs = this.delayMs;
        data.reconnectDelayMs = this.reconnectDelayMs;
        data.mode = this.mode.name();
        data.autoStart = this.autoStart;
        data.autoReconnect = this.autoReconnect;
        data.debugLogs = this.debugLogs;
        data.modEnabled = this.modEnabled;
        data.macroEnabled = this.macroEnabled;
        data.success = this.patterns.success();
        data.fail = this.patterns.fail();
        data.kick = this.patterns.kick();

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
         * Target address.
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
         * Reconnect delay, milliseconds.
         */
        int reconnectDelayMs = 8000;

        /**
         * Login mode name.
         */
        String mode;

        /**
         * Auto-start toggle.
         */
        boolean autoStart;

        /**
         * Auto-reconnect toggle.
         */
        boolean autoReconnect;

        /**
         * Password debug output toggle.
         */
boolean debugLogs;

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
         * Kick patterns.
         */
        List<String> kick;
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