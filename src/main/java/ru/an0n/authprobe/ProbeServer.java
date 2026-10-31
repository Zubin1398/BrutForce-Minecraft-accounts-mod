package ru.an0n.authprobe;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * One server address with its own nickname profiles and its own pattern banks. Every address
 * keeps its own active nickname, password lists, skip marks and chat wording; only the proxy
 * list, the last-used report and the two kick-reason banks are shared between servers.
 *
 * @author AN0N
 */
public final class ProbeServer {
    /**
     * Server address, {@code host} or {@code host:port}.
     */
    private String address;

    /**
     * Nickname profiles of this server. Always holds at least one entry.
     */
    private final List<ProbeTarget> targets = new ArrayList<>(List.of(new ProbeTarget("", List.of())));

    /**
     * Index of the profile currently edited and probed.
     */
    private int activeIndex;

    /**
     * Chat answer banks of this server.
     */
    private PatternBank patterns = PatternBank.defaults();

    /**
     * Two-factor phrases of this server.
     */
    private final List<String> twoFactorPatterns = new ArrayList<>(PatternBank.defaultTwoFactor());

    /**
     * Captcha phrases of this server.
     */
    private final List<String> captchaPatterns = new ArrayList<>(PatternBank.defaultCaptcha());

    /**
     * Whether two-factor requests are detected for this server.
     */
    private boolean twoFactorDetection = true;

    /**
     * Whether two-factor requests are detected before the first password is sent.
     */
    private boolean twoFactorBeforeStart;

    /**
     * Whether a captcha prompt pauses the run before the first password is sent.
     */
    private boolean captchaBeforeStart;

    /**
     * Creates a server entry.
     *
     * @param address Address, may be empty
     */
    public ProbeServer(String address) {
        this.address = address == null ? "" : address.trim();
    }

    /**
     * Gets the address.
     *
     * @return Address
     */
    public String address() {
        return this.address;
    }

    /**
     * Sets the address.
     *
     * @param value New address
     */
    public void address(String value) {
        this.address = value == null ? "" : value.trim();
    }

    /**
     * Gets the nickname profiles.
     *
     * @return Profiles
     */
    public List<ProbeTarget> targets() {
        return this.targets;
    }

    /**
     * Gets the active profile index, clamped into range.
     *
     * @return Active index
     */
    public int activeIndex() {
        return clamp(this.activeIndex, 0, this.targets.size() - 1);
    }

    /**
     * Sets the active profile index, clamped into range.
     *
     * @param index New index
     */
    public void activeIndex(int index) {
        this.activeIndex = clamp(index, 0, this.targets.size() - 1);
    }

    /**
     * Gets the profile the probe drives.
     *
     * @return Primary profile
     */
    public ProbeTarget primary() {
        return this.targets.get(activeIndex());
    }

    /**
     * Stable-sorts the profiles by their status rank and keeps the active profile selected.
     *
     * @return Whether the order changed
     */
    public boolean sortTargetsByStatus() {
        if (this.targets.size() < 2) return false;
        ProbeTarget active = primary();
        List<ProbeTarget> sorted = new ArrayList<>(this.targets);
        sorted.sort(Comparator.comparingInt(ProbeTarget::sortRank));
        if (sorted.equals(this.targets)) return false;
        this.targets.clear();
        this.targets.addAll(sorted);
        int index = this.targets.indexOf(active);
        this.activeIndex = index < 0 ? 0 : index;
        return true;
    }

    /**
     * Merge result.
     *
     * @param added          New profiles appended
     * @param merged         Existing profiles that received new passwords
     * @param skipped        Duplicates or blank names that changed nothing
     * @param passwordsAdded Passwords appended to existing profiles
     */
    public record MergeResult(int added, int merged, int skipped, int passwordsAdded) {
        /**
         * Whether the merge changed the profile list.
         *
         * @return Whether something was added or merged
         */
        public boolean changed() {
            return added > 0 || merged > 0;
        }
    }

    /**
     * Adds imported profiles without deleting existing ones. Nicknames are matched
     * case-insensitively; missing passwords are appended, marks are kept and an exhausted
     * result is cleared when new passwords arrive.
     *
     * @param imported Imported profiles
     * @return What the merge did
     */
    public MergeResult mergeTargets(List<ProbeTarget> imported) {
        if (imported == null || imported.isEmpty()) return new MergeResult(0, 0, 0, 0);

        // A fresh server has one blank placeholder; drop it so imported nicks start at the top.
        if (this.targets.size() == 1 && isEmptyPlaceholder(this.targets.get(0))) {
            this.targets.clear();
            this.activeIndex = 0;
        }

        int added = 0;
        int merged = 0;
        int skipped = 0;
        int passwordsAdded = 0;
        for (ProbeTarget source : imported) {
            if (source == null) {
                skipped++;
                continue;
            }
            String nick = source.nick() == null ? "" : source.nick().trim();
            if (nick.isEmpty()) {
                skipped++;
                continue;
            }

            int existingIndex = -1;
            for (int i = 0; i < this.targets.size(); i++) {
                if (this.targets.get(i).nick().trim().equalsIgnoreCase(nick)) {
                    existingIndex = i;
                    break;
                }
            }

            if (existingIndex < 0) {
                this.targets.add(new ProbeTarget(nick, new ArrayList<>(source.passwords())));
                added++;
                continue;
            }

            ProbeTarget existing = this.targets.get(existingIndex);
            // The found password list is already final; do not add alternatives to it.
            if (ProbeTarget.RESULT_FOUND.equals(existing.result())) {
                skipped++;
                continue;
            }

            List<String> combined = new ArrayList<>(existing.passwords());
            int before = combined.size();
            for (String password : source.passwords()) {
                if (password != null && !combined.contains(password)) combined.add(password);
            }
            int newPasswords = combined.size() - before;
            if (newPasswords == 0) {
                skipped++;
                continue;
            }
            this.targets.set(existingIndex, existing.withPasswords(combined).withResult(""));
            merged++;
            passwordsAdded += newPasswords;
        }

        if (this.targets.isEmpty()) this.targets.add(new ProbeTarget("", List.of()));
        this.activeIndex = clamp(this.activeIndex, 0, this.targets.size() - 1);
        return new MergeResult(added, merged, skipped, passwordsAdded);
    }

    /**
     * Whether a target is an empty profile placeholder.
     *
     * @param target Target
     * @return Whether it has no nick, passwords, mark or result
     */
    private static boolean isEmptyPlaceholder(ProbeTarget target) {
        return target.nick().isBlank() && target.passwords().isEmpty()
                && !target.hasSkip() && target.result().isBlank();
    }

    /**
     * Replaces the profile the probe drives.
     *
     * @param target New primary profile
     */
    public void primary(ProbeTarget target) {
        this.targets.set(activeIndex(), target == null ? new ProbeTarget("", List.of()) : target);
    }

    /**
     * Adds an empty profile and makes it active.
     */
    public void addTarget() {
        this.targets.add(new ProbeTarget("", List.of()));
        this.activeIndex = this.targets.size() - 1;
    }

    /**
     * Removes the active profile, keeping at least one. The previous profile becomes active.
     */
    public void removeActiveTarget() {
        if (this.targets.size() <= 1) return;
        int index = activeIndex();
        this.targets.remove(index);
        this.activeIndex = clamp(index, 0, this.targets.size() - 1);
    }

    /**
     * Replaces every profile with imported ones and selects the first.
     *
     * @param imported Imported profiles
     * @return Whether the list was replaced
     */
    public boolean replaceTargets(List<ProbeTarget> imported) {
        if (imported == null || imported.isEmpty()) return false;
        this.targets.clear();
        this.targets.addAll(imported);
        this.activeIndex = 0;
        return true;
    }

    /**
     * Checks whether another profile follows the active one.
     *
     * @return Whether a next profile exists
     */
    public boolean hasNextTarget() {
        return activeIndex() + 1 < this.targets.size();
    }

    /**
     * Moves to the next profile.
     *
     * @return Whether the move happened
     */
    public boolean nextTarget() {
        if (!hasNextTarget()) return false;
        this.activeIndex = activeIndex() + 1;
        return true;
    }

    /**
     * Removes every profile carrying the given skip mark, keeping at least one profile.
     *
     * @param kind Skip mark kind, for example {@link ProbeTarget#KIND_2FA}
     * @return Amount of removed profiles
     */
    public int removeWithSkipKind(String kind) {
        if (kind == null || kind.isBlank()) return 0;
        int before = this.targets.size();
        this.targets.removeIf(target -> kind.equals(target.skipKind()));
        if (this.targets.isEmpty()) this.targets.add(new ProbeTarget("", List.of()));
        this.activeIndex = clamp(this.activeIndex, 0, this.targets.size() - 1);
        return before - this.targets.size();
    }

    /**
     * Removes every profile whose passwords were all spent without success, keeping at least
     * one profile.
     *
     * @return Amount of removed profiles
     */
    public int removeExhausted() {
        int before = this.targets.size();
        this.targets.removeIf(ProbeTarget::isExhausted);
        if (this.targets.isEmpty()) this.targets.add(new ProbeTarget("", List.of()));
        this.activeIndex = clamp(this.activeIndex, 0, this.targets.size() - 1);
        return before - this.targets.size();
    }

    /**
     * Gets the chat answer banks.
     *
     * @return Pattern bank
     */
    public PatternBank patterns() {
        return this.patterns;
    }

    /**
     * Replaces the chat answer banks.
     *
     * @param bank New bank, {@code null} restores the defaults
     */
    public void patterns(PatternBank bank) {
        this.patterns = bank == null ? PatternBank.defaults() : bank;
    }

    /**
     * Gets the two-factor phrases.
     *
     * @return 2FA phrases
     */
    public List<String> twoFactorPatterns() {
        return this.twoFactorPatterns;
    }

    /**
     * Replaces the two-factor phrases.
     *
     * @param values New phrases, {@code null} or empty restores the defaults
     */
    public void twoFactorPatterns(List<String> values) {
        this.twoFactorPatterns.clear();
        if (values == null || values.isEmpty()) {
            this.twoFactorPatterns.addAll(PatternBank.defaultTwoFactor());
        } else {
            this.twoFactorPatterns.addAll(values);
        }
    }

    /**
     * Gets the captcha phrases.
     *
     * @return Captcha phrases
     */
    public List<String> captchaPatterns() {
        return this.captchaPatterns;
    }

    /**
     * Replaces the captcha phrases.
     *
     * @param values New phrases, {@code null} or empty restores the defaults
     */
    public void captchaPatterns(List<String> values) {
        this.captchaPatterns.clear();
        if (values == null || values.isEmpty()) {
            this.captchaPatterns.addAll(PatternBank.defaultCaptcha());
        } else {
            this.captchaPatterns.addAll(values);
        }
    }

    /**
     * Checks whether two-factor requests are detected for this server.
     *
     * @return Whether 2FA detection is on
     */
    public boolean twoFactorDetection() {
        return this.twoFactorDetection;
    }

    /**
     * Enables or disables two-factor detection for this server.
     *
     * @param value New value
     */
    public void twoFactorDetection(boolean value) {
        this.twoFactorDetection = value;
    }

    /**
     * Checks whether two-factor requests are detected before the first send.
     *
     * @return Whether pre-start 2FA detection is on
     */
    public boolean twoFactorBeforeStart() {
        return this.twoFactorBeforeStart;
    }

    /**
     * Enables or disables pre-start two-factor detection.
     *
     * @param value New value
     */
    public void twoFactorBeforeStart(boolean value) {
        this.twoFactorBeforeStart = value;
    }

    /**
     * Checks whether a captcha prompt pauses the run before the first send.
     *
     * @return Whether pre-start captcha pausing is on
     */
    public boolean captchaBeforeStart() {
        return this.captchaBeforeStart;
    }

    /**
     * Enables or disables pre-start captcha pausing.
     *
     * @param value New value
     */
    public void captchaBeforeStart(boolean value) {
        this.captchaBeforeStart = value;
    }

    /**
     * Appends a phrase to one of this server's banks.
     *
     * @param slot Target bank
     * @param text Phrase, trimmed before use
     * @return Whether the phrase was added; {@code false} when it is blank or already present
     */
    public boolean addPattern(PatternSlot slot, String text) {
        String value = PatternBank.normalize(text).trim();
        if (value.isEmpty() || slot == null || !slot.serverBank()) return false;

        switch (slot) {
            case SUCCESS -> {
                if (contains(this.patterns.success(), value)) return false;
                List<String> values = new ArrayList<>(this.patterns.success());
                values.add(value);
                this.patterns = new PatternBank(values, this.patterns.fail(), this.patterns.pause(), this.patterns.kick());
            }
            case FAIL -> {
                if (contains(this.patterns.fail(), value)) return false;
                List<String> values = new ArrayList<>(this.patterns.fail());
                values.add(value);
                this.patterns = new PatternBank(this.patterns.success(), values, this.patterns.pause(), this.patterns.kick());
            }
            case PAUSE -> {
                if (contains(this.patterns.pause(), value)) return false;
                List<String> values = new ArrayList<>(this.patterns.pause());
                values.add(value);
                this.patterns = new PatternBank(this.patterns.success(), this.patterns.fail(), values, this.patterns.kick());
            }
            case KICK -> {
                if (contains(this.patterns.kick(), value)) return false;
                List<String> values = new ArrayList<>(this.patterns.kick());
                values.add(value);
                this.patterns = new PatternBank(this.patterns.success(), this.patterns.fail(), this.patterns.pause(), values);
            }
            case TWO_FACTOR -> {
                if (contains(this.twoFactorPatterns, value)) return false;
                this.twoFactorPatterns.add(value);
            }
            case CAPTCHA -> {
                if (contains(this.captchaPatterns, value)) return false;
                this.captchaPatterns.add(value);
            }
            default -> {
                return false;
            }
        }
        return true;
    }

    /**
     * Collects warnings about patterns of this server that are likely to misfire.
     *
     * @return Warning lines, empty when the banks look sane
     */
    public List<String> patternWarnings() {
        List<String> warnings = new ArrayList<>();
        collectShortWarnings(warnings, this.twoFactorPatterns, "2FA");
        collectShortWarnings(warnings, this.captchaPatterns, "капча");

        for (String pattern : this.twoFactorPatterns) {
            if (containsIgnoreCase(this.captchaPatterns, pattern)) {
                warnings.add("шаблон «" + pattern + "» есть и в 2FA, и в капче — капча всегда побеждает");
            }
            if (containsIgnoreCase(this.patterns.success(), pattern)
                    || containsIgnoreCase(this.patterns.fail(), pattern)) {
                warnings.add("шаблон «" + pattern + "» перебивает определение пароля (успех/провал)");
            }
        }
        return warnings;
    }

    /**
     * Adds a warning for every phrase shorter than three characters.
     *
     * @param warnings Target list
     * @param patterns Phrases to check
     * @param bankName Bank name for the message
     */
    private static void collectShortWarnings(List<String> warnings, List<String> patterns, String bankName) {
        for (String pattern : patterns) {
            if (pattern.length() < 3) {
                warnings.add("шаблон «" + pattern + "» в списке " + bankName
                        + " слишком короткий — будет ложно срабатывать");
            }
        }
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
     * Checks whether a list holds the value exactly, ignoring surrounding spaces.
     *
     * @param values List
     * @param value  Value
     * @return Whether the value is present
     */
    private static boolean contains(List<String> values, String value) {
        return containsIgnoreCase(values, value);
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